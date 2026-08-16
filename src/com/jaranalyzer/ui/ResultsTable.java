package com.jaranalyzer.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;

import com.jaranalyzer.LanguageManager;
import com.jaranalyzer.scan.JarAnalysis;
import com.jaranalyzer.scan.Verdict;

/** The results grid: model, renderers and filtering in one place. */
public class ResultsTable extends JTable {

	private static final long serialVersionUID = 1L;

	public static final int COL_VERDICT = 0;
	public static final int COL_NAME = 1;
	public static final int COL_SIZE = 2;
	public static final int COL_DECOMPILE = 3;
	public static final int COL_SCORE = 4;
	public static final int COL_FINDINGS = 5;
	public static final int COL_MODIFIED = 6;
	public static final int COL_TOP = 7;
	public static final int COL_PATH = 8;

	private final Model model = new Model();
	private final TableRowSorter<Model> sorter;

	private String searchText = "";
	private final java.util.EnumSet<Verdict> allowed = java.util.EnumSet.allOf(Verdict.class);
	/** Only show JARs modified within this many days. 0 = no limit. */
	private int maxAgeDays;

	public ResultsTable() {
		setModel(model);
		setShowGrid(false);
		setIntercellSpacing(new java.awt.Dimension(0, 0));
		setRowHeight(30);
		setFillsViewportHeight(true);
		setAutoResizeMode(AUTO_RESIZE_LAST_COLUMN);
		setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		getTableHeader().setReorderingAllowed(false);
		setBackground(WinzyPalette.PANEL);
		setForeground(WinzyPalette.TEXT);

		sorter = new TableRowSorter<>(model);
		// Verdict sorts by severity (the enum order), not alphabetically, so the
		// things that need attention stay at the top.
		sorter.setComparator(COL_VERDICT, (a, b) -> ((Verdict) a).ordinal() - ((Verdict) b).ordinal());
		sorter.setRowFilter(new javax.swing.RowFilter<Model, Integer>() {
			@Override
			public boolean include(Entry<? extends Model, ? extends Integer> entry) {
				JarAnalysis a = model.rows.get(entry.getIdentifier());
				if (!allowed.contains(a.getVerdict())) return false;

				if (maxAgeDays > 0) {
					long ms = a.getLastModified();
					// A file with no usable timestamp is kept rather than hidden:
					// an age filter should narrow what you look at, not silently
					// drop things it could not date.
					if (ms > 0) {
						long ageDays = (System.currentTimeMillis() - ms) / 86_400_000L;
						if (ageDays > maxAgeDays) return false;
					}
				}

				if (searchText.isEmpty()) return true;
				return a.getPath().toLowerCase(Locale.ROOT).contains(searchText)
						|| a.getFileName().toLowerCase(Locale.ROOT).contains(searchText);
			}
		});
		setRowSorter(sorter);
		sorter.setSortKeys(List.of(new RowSorter.SortKey(COL_VERDICT, SortOrder.DESCENDING)));

		installRenderers();
		sizeColumns();
	}

	// =====================================================================

	public void addResult(JarAnalysis a) {
		model.rows.add(a);
		int i = model.rows.size() - 1;
		model.fireTableRowsInserted(i, i);
	}

	public void clearResults() {
		int n = model.rows.size();
		model.rows.clear();
		if (n > 0) model.fireTableRowsDeleted(0, n - 1);
	}

	public List<JarAnalysis> allResults() {
		return new ArrayList<>(model.rows);
	}

	public JarAnalysis selected() {
		int view = getSelectedRow();
		if (view < 0) return null;
		return model.rows.get(convertRowIndexToModel(view));
	}

	/** The analysis behind a view row, for renderers that need more than the cell value. */
	JarAnalysis analysisForViewRow(int viewRow) {
		if (viewRow < 0) return null;
		int m = convertRowIndexToModel(viewRow);
		if (m < 0 || m >= model.rows.size()) return null;
		return model.rows.get(m);
	}

	public void setSearch(String text) {
		searchText = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
		sorter.sort();
	}

	public void setVerdictAllowed(Verdict v, boolean on) {
		if (on) allowed.add(v);
		else allowed.remove(v);
		sorter.sort();
	}

	public boolean isVerdictAllowed(Verdict v) {
		return allowed.contains(v);
	}

	/** @param days 0 shows everything */
	public void setMaxAgeDays(int days) {
		maxAgeDays = Math.max(0, days);
		sorter.sort();
	}

	public int visibleCount() {
		return getRowCount();
	}

	public void refreshLabels() {
		model.fireTableStructureChanged();
		installRenderers();
		sizeColumns();
	}

	// =====================================================================

	private void sizeColumns() {
		// Verdict column is wide enough for the pill plus a reason tag
		// ("ŞÜPHELİ  Obfuscate"); the top-finding column gives that width back.
		int[] widths = { 178, 240, 78, 116, 60, 62, 120, 250, 400 };
		for (int i = 0; i < widths.length && i < getColumnModel().getColumnCount(); i++) {
			getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
		}
	}

	private void installRenderers() {
		if (getColumnModel().getColumnCount() <= COL_PATH) return;

		getColumnModel().getColumn(COL_VERDICT).setCellRenderer(new VerdictRenderer());
		getColumnModel().getColumn(COL_NAME).setCellRenderer(new TextRenderer(
				SwingConstants.LEFT, WinzyPalette.TEXT, WinzyTheme.ui(Font.BOLD, 12.5f)));
		getColumnModel().getColumn(COL_SIZE).setCellRenderer(new TextRenderer(
				SwingConstants.RIGHT, WinzyPalette.TEXT_DIM, WinzyTheme.mono(Font.PLAIN, 11.5f)));
		getColumnModel().getColumn(COL_DECOMPILE).setCellRenderer(new DecompileRenderer());
		getColumnModel().getColumn(COL_SCORE).setCellRenderer(new ScoreRenderer());
		getColumnModel().getColumn(COL_FINDINGS).setCellRenderer(new TextRenderer(
				SwingConstants.CENTER, WinzyPalette.TEXT_DIM, WinzyTheme.mono(Font.PLAIN, 11.5f)));
		getColumnModel().getColumn(COL_MODIFIED).setCellRenderer(new AgeRenderer());
		getColumnModel().getColumn(COL_TOP).setCellRenderer(new TextRenderer(
				SwingConstants.LEFT, WinzyPalette.TEXT_DIM, WinzyTheme.ui(Font.PLAIN, 12f)));
		getColumnModel().getColumn(COL_PATH).setCellRenderer(new TextRenderer(
				SwingConstants.LEFT, WinzyPalette.TEXT_FAINT, WinzyTheme.ui(Font.PLAIN, 11.5f)));
	}

	/** Alternating row tint, applied by every renderer so the stripes line up. */
	private static Color rowBackground(JTable t, int row, boolean selected) {
		if (selected) {
			return WinzyPalette.over(WinzyPalette.accentWash(58), WinzyPalette.PANEL);
		}
		return row % 2 == 0 ? WinzyPalette.PANEL
				: WinzyPalette.mix(WinzyPalette.PANEL, WinzyPalette.PANEL_HI, 0.45);
	}

	// ---- renderers ---------------------------------------------------------

	/**
	 * Cell padding.
	 *
	 * <p>Applied after every {@code super.getTableCellRendererComponent} call, not
	 * once in the constructor: that method resets the border to its own
	 * {@code noFocusBorder} on each paint, so a border set at construction is
	 * silently discarded and adjacent columns end up touching.
	 */
	private static final javax.swing.border.Border CELL_PAD =
			BorderFactory.createEmptyBorder(0, 10, 0, 10);

	private static class TextRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;
		private final Color fg;
		private final Font font;

		TextRenderer(int align, Color fg, Font font) {
			this.fg = fg;
			this.font = font;
			setHorizontalAlignment(align);
		}

		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
				boolean focus, int row, int col) {
			super.getTableCellRendererComponent(t, value, sel, false, row, col);
			setBorder(CELL_PAD);
			setOpaque(true);
			setBackground(rowBackground(t, row, sel));
			setForeground(sel ? WinzyPalette.TEXT : fg);
			setFont(font);
			String s = value == null ? "" : value.toString();
			setToolTipText(s.isEmpty() ? null : s);
			return this;
		}
	}

	private static class VerdictRenderer extends JLabel implements TableCellRenderer {
		private static final long serialVersionUID = 1L;
		private Verdict verdict = Verdict.CLEAN;
		private Color backdrop = WinzyPalette.PANEL;
		private String reason = "";

		VerdictRenderer() {
			setOpaque(true);
		}

		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
				boolean focus, int row, int col) {
			verdict = value instanceof Verdict ? (Verdict) value : Verdict.CLEAN;
			backdrop = rowBackground(t, row, sel);
			setBackground(backdrop);

			// The reason the verdict is what it is — "Şifreli", "Obfuscate: Allatori"
			// — pulled from the row's analysis so an obfuscated or encrypted JAR says
			// so on its own line instead of just reading "Şüpheli".
			reason = "";
			if (t instanceof ResultsTable) {
				JarAnalysis a = ((ResultsTable) t).analysisForViewRow(row);
				if (a != null) reason = a.suspicionReason();
			}
			setToolTipText(reason.isEmpty() ? verdict.display()
					: verdict.display() + " — " + reason);
			return this;
		}

		@Override
		protected void paintComponent(Graphics g0) {
			super.paintComponent(g0);
			Graphics2D g = (Graphics2D) g0.create();
			int h = 19;
			int y = (getHeight() - h) / 2;
			int w = UiKit.paintBadge(g, verdict.display().toUpperCase(Locale.ROOT), verdict.color(),
					backdrop, 10, y, h, WinzyTheme.ui(Font.BOLD, 10f));

			if (!reason.isEmpty()) {
				g.setFont(WinzyTheme.ui(Font.PLAIN, 10.5f));
				g.setColor(WinzyPalette.TEXT_DIM);
				java.awt.FontMetrics fm = g.getFontMetrics();
				int tx = 10 + w + 7;
				int baseline = y + (h - fm.getHeight()) / 2 + fm.getAscent();
				// Clip to the cell so a long obfuscator name is trimmed, not spilled.
				String text = reason;
				int avail = getWidth() - tx - 4;
				while (text.length() > 1 && fm.stringWidth(text) > avail) {
					text = text.substring(0, text.length() - 1);
				}
				if (!text.equals(reason) && text.length() > 1) {
					text = text.substring(0, text.length() - 1) + "…";
				}
				g.drawString(text, tx, baseline);
			}
			g.dispose();
		}
	}

	private static class DecompileRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		DecompileRenderer() {
			setHorizontalAlignment(SwingConstants.LEFT);
		}

		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
				boolean focus, int row, int col) {
			super.getTableCellRendererComponent(t, value, sel, false, row, col);
			setBorder(CELL_PAD);
			setOpaque(true);
			setBackground(rowBackground(t, row, sel));
			setFont(WinzyTheme.mono(Font.PLAIN, 11.5f));

			String s = value == null ? "" : value.toString();
			// The whole point of the tool is whether the code could be read, so
			// the failure states are the ones that get colour here.
			Color c = WinzyPalette.TEXT_DIM;
			String lower = s.toLowerCase(Locale.ROOT);
			if (lower.contains("fail") || lower.contains("encrypt") || lower.contains("unread")) {
				c = WinzyPalette.WORST;
			} else if (lower.contains("bytecode") || lower.contains("partial")) {
				c = WinzyPalette.WARN;
			} else if (lower.contains("src")) {
				c = WinzyPalette.OK;
			}
			setForeground(sel ? WinzyPalette.TEXT : c);
			setToolTipText(s);
			return this;
		}
	}

	/**
	 * Modification date, tinted by how recent it is.
	 *
	 * <p>Age is the cheapest triage signal there is: a JAR written today during a
	 * session is a different proposition from a library that has sat untouched
	 * since 2019, and the tool already had the timestamp without ever showing it.
	 */
	private static class AgeRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		private static final java.text.SimpleDateFormat FMT =
				new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");

		AgeRenderer() {
			setHorizontalAlignment(SwingConstants.LEFT);
		}

		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
				boolean focus, int row, int col) {
			super.getTableCellRendererComponent(t, value, sel, false, row, col);
			setBorder(CELL_PAD);
			setOpaque(true);
			setBackground(rowBackground(t, row, sel));
			setFont(WinzyTheme.mono(Font.PLAIN, 11.5f));

			long ms = value instanceof Long ? (Long) value : 0L;
			if (ms <= 0) {
				setText("-");
				setForeground(WinzyPalette.TEXT_FAINT);
				setToolTipText(null);
				return this;
			}

			long ageDays = (System.currentTimeMillis() - ms) / 86_400_000L;
			setText(FMT.format(new java.util.Date(ms)));
			setToolTipText(FMT.format(new java.util.Date(ms)));

			Color c = ageDays <= 1 ? WinzyPalette.WORST
					: ageDays <= 7 ? WinzyPalette.BAD
					: ageDays <= 30 ? WinzyPalette.WARN
					: WinzyPalette.TEXT_FAINT;
			setForeground(sel ? WinzyPalette.TEXT : c);
			return this;
		}
	}

	private static class ScoreRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		ScoreRenderer() {
			setHorizontalAlignment(SwingConstants.RIGHT);
		}

		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
				boolean focus, int row, int col) {
			super.getTableCellRendererComponent(t, value, sel, false, row, col);
			setBorder(CELL_PAD);
			setOpaque(true);
			setBackground(rowBackground(t, row, sel));
			setFont(WinzyTheme.mono(Font.BOLD, 11.5f));
			int score = value instanceof Integer ? (Integer) value : 0;
			Color c = score >= 300 ? WinzyPalette.WORST
					: score >= 120 ? WinzyPalette.BAD
					: score >= 40 ? WinzyPalette.WARN
					: WinzyPalette.TEXT_DIM;
			setForeground(sel ? WinzyPalette.TEXT : c);
			return this;
		}
	}

	// =====================================================================

	private static class Model extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		final List<JarAnalysis> rows = new ArrayList<>();

		@Override
		public int getRowCount() {
			return rows.size();
		}

		@Override
		public int getColumnCount() {
			return 9;
		}

		@Override
		public String getColumnName(int c) {
			switch (c) {
				case COL_VERDICT: return LanguageManager.getString("wjf.col.verdict");
				case COL_NAME: return LanguageManager.getString("wjf.col.file");
				case COL_SIZE: return LanguageManager.getString("wjf.col.size");
				case COL_DECOMPILE: return LanguageManager.getString("wjf.col.decompile");
				case COL_SCORE: return LanguageManager.getString("wjf.col.score");
				case COL_FINDINGS: return LanguageManager.getString("wjf.col.findings");
				case COL_MODIFIED: return LanguageManager.getString("wjf.col.modified");
				case COL_TOP: return LanguageManager.getString("wjf.col.top");
				case COL_PATH: return LanguageManager.getString("wjf.col.path");
				default: return "";
			}
		}

		@Override
		public Class<?> getColumnClass(int c) {
			switch (c) {
				case COL_VERDICT: return Verdict.class;
				case COL_SCORE:
				case COL_FINDINGS: return Integer.class;
				case COL_MODIFIED: return Long.class;
				default: return String.class;
			}
		}

		@Override
		public boolean isCellEditable(int r, int c) {
			return false;
		}

		@Override
		public Object getValueAt(int r, int c) {
			JarAnalysis a = rows.get(r);
			switch (c) {
				case COL_VERDICT: return a.getVerdict();
				case COL_NAME: return a.getFileName();
				case COL_SIZE: return a.getSizeDisplay();
				case COL_DECOMPILE: return a.getDecompileSummary();
				case COL_SCORE: return a.getRiskScore();
				case COL_FINDINGS: return a.getFindingCount();
				case COL_MODIFIED: return a.getLastModified();
				case COL_TOP: {
					com.jaranalyzer.scan.Finding f = a.topFinding();
					return f == null ? "" : f.getTitle();
				}
				case COL_PATH: return a.getDirectory();
				default: return "";
			}
		}
	}
}
