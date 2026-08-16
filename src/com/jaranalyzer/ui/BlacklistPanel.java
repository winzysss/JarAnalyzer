package com.jaranalyzer.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

import com.jaranalyzer.LanguageManager;
import com.jaranalyzer.scan.Blacklist;
import com.jaranalyzer.scan.BlacklistEntry;
import com.jaranalyzer.scan.BlacklistStore;
import com.jaranalyzer.scan.MatchKind;
import com.jaranalyzer.scan.Severity;

/**
 * Editor for the blacklist.
 *
 * <p>Edits apply to the live {@link Blacklist} instance the scanner uses, and
 * every change invalidates its compiled pattern, so a term added here takes
 * effect on the next scan without a restart.
 */
public class BlacklistPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	private static final int C_ON = 0;
	private static final int C_PATTERN = 1;
	private static final int C_KIND = 2;
	private static final int C_SEVERITY = 3;
	private static final int C_CATEGORY = 4;
	private static final int C_CODE = 5;
	private static final int C_PATH = 6;
	private static final int C_STR = 7;
	private static final int C_CASE = 8;
	private static final int C_DESC = 9;

	private final Blacklist blacklist;
	private final Model model = new Model();
	private final JTable table = new JTable(model);
	private final TableRowSorter<Model> sorter;
	private final JTextField search = new JTextField();
	private final JLabel countLabel = UiKit.caption("");

	private String filter = "";

	public BlacklistPanel(Blacklist blacklist) {
		super(new BorderLayout());
		this.blacklist = blacklist;
		setOpaque(true);
		setBackground(WinzyPalette.BG);

		sorter = new TableRowSorter<>(model);
		sorter.setRowFilter(new RowFilter<Model, Integer>() {
			@Override
			public boolean include(Entry<? extends Model, ? extends Integer> e) {
				if (filter.isEmpty()) return true;
				BlacklistEntry b = model.rows.get(e.getIdentifier());
				return b.getPattern().toLowerCase(Locale.ROOT).contains(filter)
						|| b.getCategory().toLowerCase(Locale.ROOT).contains(filter)
						|| b.getDescription().toLowerCase(Locale.ROOT).contains(filter);
			}
		});
		table.setRowSorter(sorter);

		configureTable();

		JPanel north = new JPanel(new BorderLayout());
		north.setOpaque(false);
		north.add(buildHeader(), BorderLayout.NORTH);
		north.add(buildTamperBar(), BorderLayout.SOUTH);
		add(north, BorderLayout.NORTH);

		JScrollPane sp = new JScrollPane(table);
		sp.setBorder(BorderFactory.createLineBorder(WinzyPalette.LINE));
		sp.getViewport().setBackground(WinzyPalette.PANEL);
		sp.getVerticalScrollBar().setUnitIncrement(20);

		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setOpaque(false);
		wrap.setBorder(BorderFactory.createEmptyBorder(0, 22, 18, 22));
		wrap.add(sp, BorderLayout.CENTER);
		add(wrap, BorderLayout.CENTER);

		reload();
	}

	private static String t(String k) {
		return LanguageManager.getString(k);
	}

	// =====================================================================

	private JComponent buildHeader() {
		JPanel top = new JPanel();
		top.setOpaque(false);
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

		UiKit.Header h = new UiKit.Header(t("wjf.bl.sub"));
		h.actions().add(UiKit.ghost(t("wjf.bl.import"), e -> doImport()));
		h.actions().add(Box.createHorizontalStrut(8));
		h.actions().add(UiKit.ghost(t("wjf.bl.export"), e -> doExport()));
		top.add(h);

		JPanel bar = new JPanel();
		bar.setOpaque(false);
		bar.setBorder(BorderFactory.createEmptyBorder(14, 22, 12, 22));
		bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));

		bar.add(UiKit.primary(t("wjf.bl.add"), e -> addEntry()));
		bar.add(Box.createHorizontalStrut(8));
		bar.add(UiKit.ghost(t("wjf.bl.remove"), e -> removeSelected()));
		bar.add(Box.createHorizontalStrut(8));
		bar.add(UiKit.ghost(t("wjf.bl.save"), e -> save()));
		bar.add(Box.createHorizontalStrut(8));
		bar.add(UiKit.ghost(t("wjf.bl.restore"), e -> restoreDefaults()));
		bar.add(Box.createHorizontalStrut(20));
		bar.add(UiKit.vDivider(22));
		bar.add(Box.createHorizontalStrut(16));

		bar.add(UiKit.caption(t("wjf.filter.search")));
		bar.add(Box.createHorizontalStrut(8));
		search.setPreferredSize(new Dimension(220, 28));
		search.setMaximumSize(new Dimension(220, 28));
		search.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) { apply(); }

			@Override public void removeUpdate(DocumentEvent e) { apply(); }

			@Override public void changedUpdate(DocumentEvent e) { apply(); }

			private void apply() {
				filter = search.getText().trim().toLowerCase(Locale.ROOT);
				sorter.sort();
				updateCount();
			}
		});
		bar.add(search);
		bar.add(Box.createHorizontalGlue());
		bar.add(countLabel);

		top.add(bar);
		return top;
	}

	private void configureTable() {
		table.setRowHeight(28);
		table.setShowGrid(false);
		table.setIntercellSpacing(new Dimension(0, 0));
		table.setBackground(WinzyPalette.PANEL);
		table.setForeground(WinzyPalette.TEXT);
		table.setSelectionBackground(WinzyPalette.over(WinzyPalette.accentWash(58), WinzyPalette.PANEL));
		table.setSelectionForeground(WinzyPalette.TEXT);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
		table.getTableHeader().setReorderingAllowed(false);

		table.getColumnModel().getColumn(C_KIND).setCellEditor(
				new DefaultCellEditor(new JComboBox<>(MatchKind.values())));
		table.getColumnModel().getColumn(C_SEVERITY).setCellEditor(
				new DefaultCellEditor(new JComboBox<>(Severity.values())));

		table.getColumnModel().getColumn(C_SEVERITY).setCellRenderer(new SeverityRenderer());
		table.getColumnModel().getColumn(C_PATTERN).setCellRenderer(new PatternRenderer());

		int[] w = { 44, 260, 90, 96, 120, 56, 56, 56, 56, 380 };
		for (int i = 0; i < w.length; i++) {
			table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
		}
	}

	// =====================================================================

	public void reload() {
		model.rows.clear();
		model.rows.addAll(blacklist.entries());
		model.fireTableDataChanged();
		updateCount();
		updateTamperBar();
	}

	private JLabel tamperBar;

	/**
	 * A warning strip shown only when built-in high-severity terms are missing.
	 *
	 * <p>The list is meant to be edited, so a normal edited list shows nothing.
	 * But a list with a chunk of the default cheat names removed is the sign of
	 * someone clearing the way for a known cheat, and that should not pass
	 * quietly during a check.
	 */
	private JComponent buildTamperBar() {
		tamperBar = new JLabel();
		tamperBar.setOpaque(true);
		tamperBar.setBackground(WinzyPalette.PANEL_HI);
		tamperBar.setForeground(WinzyPalette.WARN);
		tamperBar.setFont(WinzyTheme.ui(Font.BOLD, 12f));
		tamperBar.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 2, 0, WinzyPalette.WARN),
				BorderFactory.createEmptyBorder(6, 24, 6, 24)));
		tamperBar.setVisible(false);
		return tamperBar;
	}

	private void updateTamperBar() {
		if (tamperBar == null) return;
		int missing = BlacklistStore.missingDefaultTerms(blacklist);
		if (missing <= 0) {
			tamperBar.setVisible(false);
		} else {
			tamperBar.setText("⚠  " + String.format(Locale.ROOT, t("wjf.bl.tampered"), missing));
			tamperBar.setVisible(true);
		}
	}

	private void updateCount() {
		countLabel.setText(String.format(Locale.ROOT, "%,d %s  ·  %,d %s",
				blacklist.enabledCount(), t("wjf.bl.active"),
				model.rows.size(), t("wjf.bl.total")));
	}

	private void addEntry() {
		BlacklistEntry e = new BlacklistEntry("", MatchKind.WORD, Severity.HIGH,
				"Custom", "");
		blacklist.add(e);
		model.rows.add(e);
		int row = model.rows.size() - 1;
		model.fireTableRowsInserted(row, row);

		int view = table.convertRowIndexToView(row);
		if (view >= 0) {
			table.setRowSelectionInterval(view, view);
			table.scrollRectToVisible(table.getCellRect(view, C_PATTERN, true));
			table.editCellAt(view, C_PATTERN);
			Component ed = table.getEditorComponent();
			if (ed != null) ed.requestFocusInWindow();
		}
		updateCount();
	}

	private void removeSelected() {
		int[] view = table.getSelectedRows();
		if (view.length == 0) return;

		List<BlacklistEntry> doomed = new ArrayList<>();
		for (int v : view) doomed.add(model.rows.get(table.convertRowIndexToModel(v)));

		for (BlacklistEntry e : doomed) {
			blacklist.remove(e);
			model.rows.remove(e);
		}
		blacklist.invalidate();
		model.fireTableDataChanged();
		updateCount();
	}

	private void save() {
		blacklist.invalidate();
		try {
			BlacklistStore.save(blacklist);
			JOptionPane.showMessageDialog(this,
					t("wjf.bl.saved") + "\n" + BlacklistStore.blacklistFile().getAbsolutePath(),
					t("wjf.bl.save"), JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this, t("wjf.bl.saveFail") + "\n" + ex,
					t("wjf.bl.save"), JOptionPane.ERROR_MESSAGE);
		}
	}

	private void restoreDefaults() {
		int c = JOptionPane.showConfirmDialog(this, t("wjf.bl.restoreAsk"),
				t("wjf.bl.restore"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (c != JOptionPane.YES_OPTION) return;

		blacklist.clear();
		for (BlacklistEntry e : BlacklistStore.defaults().entries()) {
			blacklist.add(e);
		}
		blacklist.invalidate();
		reload();
	}

	private void doImport() {
		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle(t("wjf.bl.import"));
		if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
		try {
			Blacklist imported = BlacklistStore.importFrom(fc.getSelectedFile());
			int added = 0;
			for (BlacklistEntry e : imported.entries()) {
				// Import merges rather than replaces, so a shared term list can be
				// layered onto whatever the operator already tuned.
				if (!blacklist.entries().contains(e)) {
					blacklist.add(e);
					added++;
				}
			}
			blacklist.invalidate();
			reload();
			JOptionPane.showMessageDialog(this, added + " " + t("wjf.bl.imported"),
					t("wjf.bl.import"), JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this, t("wjf.bl.importFail") + "\n" + ex,
					t("wjf.bl.import"), JOptionPane.ERROR_MESSAGE);
		}
	}

	private void doExport() {
		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle(t("wjf.bl.export"));
		fc.setSelectedFile(new File("blacklist.json"));
		if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
		try {
			BlacklistStore.saveTo(blacklist, fc.getSelectedFile());
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this, t("wjf.bl.saveFail") + "\n" + ex,
					t("wjf.bl.export"), JOptionPane.ERROR_MESSAGE);
		}
	}

	public void updateLanguage() {
		model.fireTableStructureChanged();
		configureTable();
		updateCount();
	}

	// ---- renderers ---------------------------------------------------------

	private static class SeverityRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		SeverityRenderer() {
			setHorizontalAlignment(SwingConstants.CENTER);
		}

		@Override
		public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
				boolean focus, int row, int col) {
			super.getTableCellRendererComponent(t, v, sel, false, row, col);
			// See ResultsTable.CELL_PAD: the super call resets the border each paint.
			setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
			setFont(WinzyTheme.ui(Font.BOLD, 11f));
			Severity s = v instanceof Severity ? (Severity) v : Severity.MEDIUM;
			setText(s.display());
			Color c;
			switch (s) {
				case CRITICAL: c = WinzyPalette.WORST; break;
				case HIGH: c = WinzyPalette.BAD; break;
				case MEDIUM: c = WinzyPalette.WARN; break;
				case LOW: c = WinzyPalette.INFO; break;
				default: c = WinzyPalette.NEUTRAL;
			}
			setForeground(c);
			return this;
		}
	}

	private static class PatternRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		@Override
		public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel,
				boolean focus, int row, int col) {
			super.getTableCellRendererComponent(tb, v, sel, false, row, col);
			setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
			setFont(WinzyTheme.mono(Font.PLAIN, 12f));
			setForeground(WinzyPalette.TEXT);
			return this;
		}
	}

	// =====================================================================

	private class Model extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		final List<BlacklistEntry> rows = new ArrayList<>();

		@Override
		public int getRowCount() {
			return rows.size();
		}

		@Override
		public int getColumnCount() {
			return 10;
		}

		@Override
		public String getColumnName(int c) {
			switch (c) {
				case C_ON: return t("wjf.bl.col.on");
				case C_PATTERN: return t("wjf.bl.col.pattern");
				case C_KIND: return t("wjf.bl.col.kind");
				case C_SEVERITY: return t("wjf.bl.col.severity");
				case C_CATEGORY: return t("wjf.bl.col.category");
				case C_CODE: return t("wjf.bl.col.code");
				case C_PATH: return t("wjf.bl.col.path");
				case C_STR: return t("wjf.bl.col.string");
				case C_CASE: return t("wjf.bl.col.case");
				case C_DESC: return t("wjf.bl.col.desc");
				default: return "";
			}
		}

		@Override
		public Class<?> getColumnClass(int c) {
			switch (c) {
				case C_ON:
				case C_CODE:
				case C_PATH:
				case C_STR:
				case C_CASE: return Boolean.class;
				case C_KIND: return MatchKind.class;
				case C_SEVERITY: return Severity.class;
				default: return String.class;
			}
		}

		@Override
		public boolean isCellEditable(int r, int c) {
			return true;
		}

		@Override
		public Object getValueAt(int r, int c) {
			BlacklistEntry e = rows.get(r);
			switch (c) {
				case C_ON: return e.isEnabled();
				case C_PATTERN: return e.getPattern();
				case C_KIND: return e.getKind();
				case C_SEVERITY: return e.getSeverity();
				case C_CATEGORY: return e.getCategory();
				case C_CODE: return e.isScanCode();
				case C_PATH: return e.isScanPaths();
				case C_STR: return e.isScanStrings();
				case C_CASE: return e.isCaseSensitive();
				case C_DESC: return e.getDescription();
				default: return "";
			}
		}

		@Override
		public void setValueAt(Object v, int r, int c) {
			BlacklistEntry e = rows.get(r);
			switch (c) {
				case C_ON: e.setEnabled(Boolean.TRUE.equals(v)); break;
				case C_PATTERN: e.setPattern(String.valueOf(v)); break;
				case C_KIND: e.setKind((MatchKind) v); break;
				case C_SEVERITY: e.setSeverity((Severity) v); break;
				case C_CATEGORY: e.setCategory(String.valueOf(v)); break;
				case C_CODE: e.setScanCode(Boolean.TRUE.equals(v)); break;
				case C_PATH: e.setScanPaths(Boolean.TRUE.equals(v)); break;
				case C_STR: e.setScanStrings(Boolean.TRUE.equals(v)); break;
				case C_CASE: e.setCaseSensitive(Boolean.TRUE.equals(v)); break;
				case C_DESC: e.setDescription(String.valueOf(v)); break;
				default: break;
			}

			// A bad regex is reported the moment it is typed rather than silently
			// dropped at scan time.
			if (c == C_PATTERN || c == C_KIND) {
				String err = e.compileError();
				if (err != null) {
					JOptionPane.showMessageDialog(BlacklistPanel.this,
							t("wjf.bl.badRegex") + "\n\n" + e.getPattern() + "\n\n" + err,
							t("wjf.bl.badRegexTitle"), JOptionPane.WARNING_MESSAGE);
				}
			}

			blacklist.invalidate();
			fireTableRowsUpdated(r, r);
			updateCount();
		}
	}
}
