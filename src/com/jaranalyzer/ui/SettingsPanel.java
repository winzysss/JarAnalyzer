package com.jaranalyzer.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

import com.jaranalyzer.LanguageManager;
import com.jaranalyzer.scan.ScanSettings;

/** Form over {@link ScanSettings}. Changes are written back on every edit. */
public class SettingsPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	public interface SchemeListener {
		void onSchemeChanged(String key);
	}

	private final ScanSettings settings;
	private final SchemeListener schemeListener;

	private final JComboBox<String> decompileModeBox = new JComboBox<>();
	private final JCheckBox opaqueSuspicious = new JCheckBox();
	private final JCheckBox bytecodeFallback = new JCheckBox();
	private final JCheckBox skipLibraryPackages = new JCheckBox();
	private final JCheckBox skipKnownLibraries = new JCheckBox();
	private final JCheckBox includeZip = new JCheckBox();
	private final JCheckBox computeHashes = new JCheckBox();
	private final JCheckBox detectObfuscation = new JCheckBox();
	private final JCheckBox keepCleanText = new JCheckBox();

	private final JSpinner maxClasses = new JSpinner(new SpinnerNumberModel(4000, 0, 500000, 100));
	private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(120, 5, 3600, 5));
	private final JSpinner nestedDepth = new JSpinner(new SpinnerNumberModel(2, 0, 8, 1));
	private final JSpinner workers = new JSpinner(new SpinnerNumberModel(0, 0, 128, 1));
	private final JSpinner obfSample = new JSpinner(new SpinnerNumberModel(400, 20, 20000, 20));
	private final JSpinner maxJarMb = new JSpinner(new SpinnerNumberModel(512, 1, 65536, 32));

	private final JTextField excludeDirs = new JTextField();
	private final JComboBox<String> schemeBox = new JComboBox<>();

	private boolean loading;

	public SettingsPanel(ScanSettings settings, SchemeListener schemeListener) {
		super(new BorderLayout());
		this.settings = settings;
		this.schemeListener = schemeListener;
		setOpaque(true);
		setBackground(WinzyPalette.BG);

		add(new UiKit.Header(t("wjf.set.sub")), BorderLayout.NORTH);

		JPanel form = new JPanel();
		form.setOpaque(false);
		form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
		form.setBorder(BorderFactory.createEmptyBorder(16, 22, 22, 22));

		for (String key : new String[] { "wjf.set.mode.off", "wjf.set.mode.flagged", "wjf.set.mode.all" }) {
			decompileModeBox.addItem(t(key));
		}
		decompileModeBox.setPreferredSize(new Dimension(220, 26));

		// The four settings a user actually reaches for. Everything else is tuning
		// that only matters once something has gone wrong, so it starts folded —
		// eighteen controls in one flat list is what made this screen unreadable.
		form.add(section(t("wjf.set.g.scan"), new Object[][] {
			{ t("wjf.set.decompileMode"), decompileModeBox, t("wjf.set.decompileMode.h") },
			{ t("wjf.set.opaque"), opaqueSuspicious, t("wjf.set.opaque.h") },
			{ t("wjf.set.skipLibJar"), skipKnownLibraries, t("wjf.set.skipLibJar.h") },
			{ t("wjf.set.scheme"), schemeBox, t("wjf.set.scheme.h") },
		}));

		form.add(Box.createVerticalStrut(14));

		JPanel advanced = new JPanel();
		advanced.setOpaque(false);
		advanced.setLayout(new BoxLayout(advanced, BoxLayout.Y_AXIS));
		advanced.setAlignmentX(LEFT_ALIGNMENT);
		advanced.add(section(t("wjf.set.g.scope"), new Object[][] {
			{ t("wjf.set.bytecode"), bytecodeFallback, t("wjf.set.bytecode.h") },
			{ t("wjf.set.obf"), detectObfuscation, t("wjf.set.obf.h") },
			{ t("wjf.set.hash"), computeHashes, t("wjf.set.hash.h") },
			{ t("wjf.set.skipLibPkg"), skipLibraryPackages, t("wjf.set.skipLibPkg.h") },
			{ t("wjf.set.zip"), includeZip, t("wjf.set.zip.h") },
			{ t("wjf.set.exclude"), excludeDirs, t("wjf.set.exclude.h") },
		}));
		advanced.add(Box.createVerticalStrut(14));
		advanced.add(section(t("wjf.set.g.limits"), new Object[][] {
			{ t("wjf.set.maxClasses"), maxClasses, t("wjf.set.maxClasses.h") },
			{ t("wjf.set.timeout"), timeout, t("wjf.set.timeout.h") },
			{ t("wjf.set.nested"), nestedDepth, t("wjf.set.nested.h") },
			{ t("wjf.set.maxJar"), maxJarMb, t("wjf.set.maxJar.h") },
			{ t("wjf.set.workers"), workers, t("wjf.set.workers.h") },
			{ t("wjf.set.obfSample"), obfSample, t("wjf.set.obfSample.h") },
			{ t("wjf.set.keepText"), keepCleanText, t("wjf.set.keepText.h") },
		}));
		advanced.setVisible(false);

		form.add(disclosure(t("wjf.set.advanced"), advanced));
		form.add(Box.createVerticalStrut(8));
		form.add(advanced);

		form.add(Box.createVerticalStrut(16));
		JLabel where = UiKit.body(t("wjf.set.storedAt") + "  "
				+ com.jaranalyzer.scan.BlacklistStore.configDir().getAbsolutePath());
		where.setAlignmentX(LEFT_ALIGNMENT);
		form.add(where);

		JScrollPane sp = new JScrollPane(form);
		sp.setBorder(BorderFactory.createEmptyBorder());
		sp.getViewport().setBackground(WinzyPalette.BG);
		sp.getVerticalScrollBar().setUnitIncrement(20);
		add(sp, BorderLayout.CENTER);

		for (String key : com.jaranalyzer.ui.WinzyPalette.SCHEMES.keySet()) {
			schemeBox.addItem(WinzyPalette.SCHEMES.get(key).label);
		}

		load();
		wire();
	}

	private static String t(String k) {
		return LanguageManager.getString(k);
	}

	// ---- layout ------------------------------------------------------------

	/**
	 * Picks a disclosure marker the UI font can actually draw.
	 *
	 * <p>The obvious triangles are not in every Segoe UI installation, and a
	 * missing glyph renders as a hollow box — which is what the first version of
	 * this header did. Rather than guess, ask the font.
	 */
	private static String[] chevrons() {
		java.awt.Font f = WinzyTheme.ui();
		String[][] candidates = {
			{ "▸", "▾" },   // small triangles
			{ "▶", "▼" },   // black triangles
			{ "›", "ˇ" },   // single angle quote / caron
			{ "+", "−" },        // plus / minus sign
			{ "+", "-" },             // plain ASCII, always available
		};
		for (String[] pair : candidates) {
			if (f.canDisplayUpTo(pair[0]) < 0 && f.canDisplayUpTo(pair[1]) < 0) {
				return pair;
			}
		}
		return new String[] { "+", "-" };
	}

	/** Clickable header that folds the panel below it. */
	private JComponent disclosure(String title, JComponent target) {
		final String[] mark = chevrons();
		JButton header = new JButton(mark[0] + "  " + title);
		header.setFont(WinzyTheme.ui(Font.BOLD, 12.5f));
		header.setForeground(WinzyPalette.TEXT_DIM);
		header.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
		header.setContentAreaFilled(false);
		header.setBorderPainted(false);
		header.setFocusPainted(false);
		header.setOpaque(false);
		header.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		header.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

		header.addActionListener(e -> {
			boolean open = !target.isVisible();
			target.setVisible(open);
			header.setText((open ? mark[1] : mark[0]) + "  " + title);
			header.setForeground(open ? WinzyPalette.accent() : WinzyPalette.TEXT_DIM);
			revalidate();
			repaint();
		});
		return header;
	}

	private JComponent section(String title, Object[][] rows) {
		UiKit.Card card = new UiKit.Card();
		card.setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel inner = new JPanel(new GridBagLayout());
		inner.setOpaque(false);

		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(5, 0, 5, 12);
		c.anchor = GridBagConstraints.WEST;

		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 3;
		JLabel heading = UiKit.caption(title.toUpperCase(java.util.Locale.ROOT));
		heading.setForeground(WinzyPalette.accent());
		inner.add(heading, c);
		c.gridwidth = 1;

		int y = 1;
		for (Object[] row : rows) {
			String label = (String) row[0];
			Component field = (Component) row[1];
			String hint = row.length > 2 ? (String) row[2] : null;

			c.gridx = 0;
			c.gridy = y;
			c.weightx = 0;
			c.fill = GridBagConstraints.NONE;
			JLabel l = new JLabel(label);
			l.setFont(WinzyTheme.ui(Font.PLAIN, 12.5f));
			l.setForeground(WinzyPalette.TEXT);
			l.setPreferredSize(new Dimension(300, 22));
			inner.add(l, c);

			c.gridx = 1;
			if (field instanceof JCheckBox) {
				((JCheckBox) field).setOpaque(false);
				((JCheckBox) field).setFocusPainted(false);
				field.setPreferredSize(new Dimension(28, 22));
			} else if (field instanceof JSpinner) {
				field.setPreferredSize(new Dimension(110, 26));
			} else if (field instanceof JTextField) {
				field.setPreferredSize(new Dimension(340, 26));
			} else if (field instanceof JComboBox) {
				field.setPreferredSize(new Dimension(160, 26));
			}
			inner.add(field, c);

			c.gridx = 2;
			c.weightx = 1;
			c.fill = GridBagConstraints.HORIZONTAL;
			if (hint != null && !hint.equals(label)) {
				JLabel hl = new JLabel(hint);
				hl.setFont(WinzyTheme.ui(Font.PLAIN, 11.5f));
				hl.setForeground(WinzyPalette.TEXT_FAINT);
				inner.add(hl, c);
			} else {
				inner.add(Box.createHorizontalGlue(), c);
			}
			y++;
		}

		card.add(inner, BorderLayout.CENTER);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height + 40));
		return card;
	}

	// ---- binding -----------------------------------------------------------

	private void load() {
		loading = true;
		decompileModeBox.setSelectedIndex(settings.decompileMode.ordinal());
		opaqueSuspicious.setSelected(settings.opaqueMeansSuspicious);
		bytecodeFallback.setSelected(settings.bytecodeFallback);
		skipLibraryPackages.setSelected(settings.skipLibraryPackages);
		skipKnownLibraries.setSelected(settings.skipKnownLibraries);
		includeZip.setSelected(settings.includeZipFiles);
		computeHashes.setSelected(settings.computeHashes);
		detectObfuscation.setSelected(settings.detectObfuscation);
		keepCleanText.setSelected(settings.keepTextForCleanJars);

		maxClasses.setValue(settings.maxClassesPerJar);
		timeout.setValue(settings.perJarTimeoutSeconds);
		nestedDepth.setValue(settings.maxNestedDepth);
		workers.setValue(settings.workerThreads);
		obfSample.setValue(settings.obfuscationSampleSize);
		maxJarMb.setValue((int) Math.max(1, settings.maxJarBytes / (1024 * 1024)));

		excludeDirs.setText(String.join(";", settings.excludeDirectories));

		String active = WinzyPalette.scheme().label;
		schemeBox.setSelectedItem(active);
		loading = false;
	}

	private void wire() {
		decompileModeBox.addActionListener(e -> push());

		JCheckBox[] boxes = {
			opaqueSuspicious, bytecodeFallback, skipLibraryPackages,
			skipKnownLibraries, includeZip, computeHashes, detectObfuscation, keepCleanText,
		};
		for (JCheckBox b : boxes) {
			b.addActionListener(e -> push());
		}
		JSpinner[] spinners = { maxClasses, timeout, nestedDepth, workers, obfSample, maxJarMb };
		for (JSpinner s : spinners) {
			s.addChangeListener(e -> push());
		}
		excludeDirs.addActionListener(e -> push());
		excludeDirs.addFocusListener(new java.awt.event.FocusAdapter() {
			@Override
			public void focusLost(java.awt.event.FocusEvent e) {
				push();
			}
		});

		schemeBox.addActionListener(e -> {
			if (loading) return;
			int i = schemeBox.getSelectedIndex();
			if (i < 0) return;
			String key = new java.util.ArrayList<>(WinzyPalette.SCHEMES.keySet()).get(i);
			if (schemeListener != null) schemeListener.onSchemeChanged(key);
		});
	}

	private void push() {
		if (loading) return;
		int mi = decompileModeBox.getSelectedIndex();
		if (mi >= 0 && mi < ScanSettings.DecompileMode.values().length) {
			settings.decompileMode = ScanSettings.DecompileMode.values()[mi];
		}
		settings.opaqueMeansSuspicious = opaqueSuspicious.isSelected();
		settings.bytecodeFallback = bytecodeFallback.isSelected();
		settings.skipLibraryPackages = skipLibraryPackages.isSelected();
		settings.skipKnownLibraries = skipKnownLibraries.isSelected();
		settings.includeZipFiles = includeZip.isSelected();
		settings.computeHashes = computeHashes.isSelected();
		settings.detectObfuscation = detectObfuscation.isSelected();
		settings.keepTextForCleanJars = keepCleanText.isSelected();

		settings.maxClassesPerJar = (Integer) maxClasses.getValue();
		settings.perJarTimeoutSeconds = (Integer) timeout.getValue();
		settings.maxNestedDepth = (Integer) nestedDepth.getValue();
		settings.workerThreads = (Integer) workers.getValue();
		settings.obfuscationSampleSize = (Integer) obfSample.getValue();
		settings.maxJarBytes = ((Integer) maxJarMb.getValue()).longValue() * 1024 * 1024;

		settings.excludeDirectories.clear();
		for (String part : excludeDirs.getText().split(";")) {
			String s = part.trim();
			if (!s.isEmpty()) settings.excludeDirectories.add(s);
		}

		settings.save();
	}

	public void updateLanguage() {
		removeAll();
		// The form is built from localised strings, so a language switch rebuilds
		// it rather than trying to retitle every widget in place.
		SettingsPanel fresh = new SettingsPanel(settings, schemeListener);
		setLayout(new BorderLayout());
		add(fresh.getComponent(0), BorderLayout.NORTH);
		add(fresh.getComponent(1), BorderLayout.CENTER);
		revalidate();
		repaint();
	}
}
