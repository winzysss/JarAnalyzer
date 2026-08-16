package com.jaranalyzer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;

import com.jaranalyzer.ui.WinzyPalette;

/**
 * Find-in-file for the decompiled code pane.
 *
 * <p>The Decompile tab exists so a person can read a suspicious class, and a
 * decompiled Minecraft client class runs to thousands of lines. Without a search
 * the tab could show the code but not let anyone actually find anything in it,
 * which is most of the value gone.
 *
 * <p>Every match is highlighted at once and the current one is highlighted more
 * strongly, so the shape of the answer ("this word is everywhere" versus "this
 * word appears once") is visible before stepping through the hits.
 */
public final class CodeSearchBar extends JPanel {

	private static final long serialVersionUID = 1L;

	private final JTextPane target;
	private final JTextField field = new JTextField();
	private final JLabel count = new JLabel();

	private final List<int[]> matches = new ArrayList<>();
	private int current = -1;

	/** Painters are kept as fields: removing a highlight needs the same instance. */
	private final Highlighter.HighlightPainter allPainter =
			new DefaultHighlighter.DefaultHighlightPainter(new Color(0x7A, 0x33, 0x3C));
	private final Highlighter.HighlightPainter currentPainter =
			new DefaultHighlighter.DefaultHighlightPainter(new Color(0xE8, 0x39, 0x4C));
	/** Terms the scan flagged, highlighted on load without the user typing. */
	private final Highlighter.HighlightPainter hitPainter =
			new DefaultHighlighter.DefaultHighlightPainter(new Color(0x8C, 0x2A, 0x36));

	/** Highlights belonging to the current search; replaced on every keystroke. */
	private final List<Object> tags = new ArrayList<>();
	/**
	 * Highlights for the terms the scan matched.
	 *
	 * <p>Kept apart from {@link #tags} because the search rebuilds its own marks
	 * from scratch each time. Sharing one list meant merely opening the find bar
	 * wiped the scan's marks — the one thing on screen the reader most wanted.
	 */
	private final List<Object> scanTags = new ArrayList<>();

	public CodeSearchBar(JTextPane target) {
		super(new BorderLayout(6, 0));
		this.target = target;

		setOpaque(true);
		setBackground(WinzyPalette.PANEL_HI);
		setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, WinzyPalette.LINE),
				BorderFactory.createEmptyBorder(4, 8, 4, 8)));
		setVisible(false);

		JLabel icon = new JLabel(LanguageManager.getString("find.label"));
		icon.setForeground(WinzyPalette.TEXT_DIM);
		icon.setFont(new Font("Segoe UI", Font.PLAIN, 11));

		field.setPreferredSize(new Dimension(260, 24));
		field.setBackground(WinzyPalette.INSET);
		field.setForeground(WinzyPalette.TEXT);
		field.setCaretColor(WinzyPalette.TEXT);
		field.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(WinzyPalette.LINE),
				BorderFactory.createEmptyBorder(2, 6, 2, 6)));

		count.setForeground(WinzyPalette.TEXT_DIM);
		count.setFont(new Font("Consolas", Font.PLAIN, 11));

		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		left.setOpaque(false);
		left.add(icon);
		left.add(field);
		left.add(button("‹", e -> step(-1), LanguageManager.getString("find.prev")));
		left.add(button("›", e -> step(1), LanguageManager.getString("find.next")));
		left.add(count);

		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		right.setOpaque(false);
		right.add(button("×", e -> dismiss(), LanguageManager.getString("find.close")));

		add(left, BorderLayout.WEST);
		add(right, BorderLayout.EAST);

		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) { research(); }
			@Override public void removeUpdate(DocumentEvent e) { research(); }
			@Override public void changedUpdate(DocumentEvent e) { research(); }
		});

		bind(field, KeyEvent.VK_ENTER, 0, e -> step(1));
		bind(field, KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK, e -> step(-1));
		bind(field, KeyEvent.VK_ESCAPE, 0, e -> dismiss());
	}

	private JButton button(String text, java.awt.event.ActionListener a, String tip) {
		JButton b = new JButton(text);
		b.setToolTipText(tip);
		b.setFocusPainted(false);
		b.setBorder(BorderFactory.createEmptyBorder(1, 7, 1, 7));
		b.setBackground(WinzyPalette.PANEL);
		b.setForeground(WinzyPalette.TEXT);
		b.setFont(new Font("Segoe UI", Font.PLAIN, 13));
		b.addActionListener(a);
		return b;
	}

	private void bind(JTextField f, int key, int mod, java.awt.event.ActionListener a) {
		String name = "wjf-" + key + "-" + mod;
		f.getInputMap(JTextField.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, mod), name);
		f.getActionMap().put(name, new AbstractAction() {
			private static final long serialVersionUID = 1L;
			@Override public void actionPerformed(ActionEvent e) { a.actionPerformed(e); }
		});
	}

	// =====================================================================

	/** Opens the bar and takes focus, pre-filling from the selection if any. */
	public void reveal() {
		String sel = target.getSelectedText();
		if (sel != null && !sel.isEmpty() && !sel.contains("\n")) {
			field.setText(sel);
		}
		setVisible(true);
		revalidate();
		SwingUtilities.invokeLater(() -> {
			field.requestFocusInWindow();
			field.selectAll();
		});
		research();
	}

	/** Named dismiss, not hide: Component.hide() is inherited and setVisible
	 * would call straight back into it — the first version recursed until the
	 * stack ran out the moment the close button was pressed. */
	public void dismiss() {
		setVisible(false);
		clearTags();
		revalidate();
		target.requestFocusInWindow();
	}

	/**
	 * Marks terms the scan already flagged, without the user searching for them.
	 *
	 * <p>This is what joins the two halves of the tool: the scan says a JAR is
	 * suspicious, and opening it should land on the reason rather than on line 1
	 * of a four-thousand-line file.
	 */
	public void markScanHits(java.util.Collection<String> terms) {
		if (terms == null || terms.isEmpty()) return;
		String text = text();
		if (text.isEmpty()) return;
		String hay = text.toLowerCase(Locale.ROOT);

		int first = -1;
		for (String term : terms) {
			if (term == null || term.length() < 3) continue;
			String needle = term.toLowerCase(Locale.ROOT);
			int from = 0;
			while (true) {
				int at = hay.indexOf(needle, from);
				if (at < 0) break;
				try {
					scanTags.add(target.getHighlighter().addHighlight(at, at + needle.length(), hitPainter));
				} catch (BadLocationException ignored) {
					// Document changed underneath; the rest of the marks still apply.
				}
				if (first < 0 || at < first) first = at;
				from = at + needle.length();
			}
		}
		if (first >= 0) scrollTo(first);
	}

	// =====================================================================

	private String text() {
		try {
			return target.getDocument().getText(0, target.getDocument().getLength());
		} catch (BadLocationException e) {
			return "";
		}
	}

	private void clearTags() {
		Highlighter h = target.getHighlighter();
		for (Object t : tags) h.removeHighlight(t);
		tags.clear();
	}

	private void research() {
		clearTags();
		matches.clear();
		current = -1;

		String needle = field.getText();
		if (needle == null || needle.isEmpty()) {
			count.setText("");
			return;
		}
		String hay = text().toLowerCase(Locale.ROOT);
		String n = needle.toLowerCase(Locale.ROOT);

		int from = 0;
		// Capped: a one-character search on a large file would otherwise try to
		// paint a highlight for every second character and freeze the window.
		while (matches.size() < 5000) {
			int at = hay.indexOf(n, from);
			if (at < 0) break;
			matches.add(new int[] { at, at + n.length() });
			from = at + n.length();
		}

		Highlighter h = target.getHighlighter();
		for (int[] m : matches) {
			try {
				tags.add(h.addHighlight(m[0], m[1], allPainter));
			} catch (BadLocationException ignored) {
				// Out of range after a reload; skip this one.
			}
		}

		if (matches.isEmpty()) {
			count.setText(LanguageManager.getString("find.none"));
			field.setForeground(WinzyPalette.WORST);
		} else {
			field.setForeground(WinzyPalette.TEXT);
			step(1);
		}
	}

	private void step(int dir) {
		if (matches.isEmpty()) return;
		current = (current + dir + matches.size()) % matches.size();
		int[] m = matches.get(current);

		// Repaint the previous "current" back to the ordinary colour by rebuilding
		// only that one highlight, rather than re-scanning the whole document.
		Highlighter h = target.getHighlighter();
		for (Object t : tags) h.removeHighlight(t);
		tags.clear();
		for (int i = 0; i < matches.size(); i++) {
			int[] mm = matches.get(i);
			try {
				tags.add(h.addHighlight(mm[0], mm[1], i == current ? currentPainter : allPainter));
			} catch (BadLocationException ignored) {
				// Skip a match that no longer fits the document.
			}
		}

		count.setText((current + 1) + " / " + matches.size());
		scrollTo(m[0]);
	}

	private void scrollTo(int offset) {
		try {
			java.awt.Rectangle r = target.modelToView(offset);
			if (r != null) {
				// Show a little of what is above the hit; landing with the match on
				// the very first visible line hides the context that explains it.
				r.y = Math.max(0, r.y - 80);
				r.height += 160;
				target.scrollRectToVisible(r);
			}
			target.setCaretPosition(offset);
		} catch (BadLocationException ignored) {
			// Offset no longer valid; nothing to scroll to.
		}
	}
}
