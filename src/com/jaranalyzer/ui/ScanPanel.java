package com.jaranalyzer.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.jaranalyzer.LanguageManager;
import com.jaranalyzer.scan.Blacklist;
import com.jaranalyzer.scan.JarAnalysis;
import com.jaranalyzer.scan.ReportWriter;
import com.jaranalyzer.scan.ScanController;
import com.jaranalyzer.scan.ScanSettings;
import com.jaranalyzer.scan.Verdict;

/**
 * The scan workspace: controls, live counters, results grid and detail pane.
 *
 * <p>Results are pushed into the table as each JAR finishes rather than in one
 * batch at the end, so a full-disk sweep is usable while it runs. Everything the
 * worker threads hand over is marshalled onto the EDT here — Swing models are not
 * thread safe, and a scan is by design many threads producing results at once.
 */
public class ScanPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	private final ScanSettings settings;
	private final Blacklist blacklist;
	private final com.jaranalyzer.AppPreferences prefs;

	private final UiKit.Header header;
	private final ResultsTable table = new ResultsTable();
	private final DetailPane detail = new DetailPane();

	// ---- collapsible layout ------------------------------------------------
	private JPanel cardsRow;
	private JSplitPane bodySplit;
	private UiKit.PillButton cardsToggle;
	private UiKit.PillButton detailToggle;

	private final UiKit.PillButton scanAllBtn;
	private final UiKit.PillButton scanFolderBtn;
	private final UiKit.PillButton scanMemoryBtn;
	private final UiKit.PillButton stopBtn;
	private final UiKit.PillButton exportBtn;

	private final JProgressBar progress = new JProgressBar();
	private final JLabel statusLabel = new JLabel();
	private final JTextField searchField = new JTextField();
	private final javax.swing.JComboBox<String> ageFilter = new javax.swing.JComboBox<>();

	/** Opens a JAR in the Decompile tab. Wired by MainWindow. */
	public interface OpenInDecompiler {
		/**
		 * @param terms what the blacklist matched in this JAR, so the code view can
		 *        mark them. Opening a flagged JAR on line 1 of a four-thousand-line
		 *        class and leaving the reader to hunt for the reason wastes the
		 *        work the scan already did.
		 */
		void open(File jar, java.util.Collection<String> terms);
	}

	private OpenInDecompiler decompilerOpener;

	private final UiKit.StatCard cardFound;
	private final UiKit.StatCard cardDone;
	private final UiKit.StatCard cardCritical;
	private final UiKit.StatCard cardDetected;
	private final UiKit.StatCard cardSuspicious;
	private final UiKit.StatCard cardUnreadable;
	private final UiKit.StatCard cardClean;

	private final List<JCheckBox> filterBoxes = new ArrayList<>();
	private JLabel filterCountLabel;

	private volatile ScanController controller;
	private volatile Thread scanThread;

	private final AtomicInteger found = new AtomicInteger();
	private final AtomicInteger analyzed = new AtomicInteger();
	private ScanController.Summary lastSummary;

	// =====================================================================

	public ScanPanel(ScanSettings settings, Blacklist blacklist,
			com.jaranalyzer.AppPreferences prefs) {
		super(new BorderLayout());
		this.settings = settings;
		this.blacklist = blacklist;
		this.prefs = prefs;
		setOpaque(true);
		setBackground(WinzyPalette.BG);

		header = new UiKit.Header(t("wjf.header.sub"));

		scanAllBtn = (UiKit.PillButton) UiKit.primary(t("wjf.btn.scanAll"), e -> startFullScan());
		scanFolderBtn = (UiKit.PillButton) UiKit.ghost(t("wjf.btn.scanFolder"), e -> startFolderScan());
		scanMemoryBtn = (UiKit.PillButton) UiKit.ghost(t("wjf.btn.scanMemory"), e -> startMemoryScan());
		scanMemoryBtn.setToolTipText(t("wjf.btn.scanMemory.tip"));
		stopBtn = (UiKit.PillButton) UiKit.ghost(t("wjf.btn.stop"), e -> stopScan());
		stopBtn.tint(WinzyPalette.BAD);
		stopBtn.setEnabled(false);
		exportBtn = (UiKit.PillButton) UiKit.ghost(t("wjf.btn.export"), e -> exportReport());
		exportBtn.setEnabled(false);

		cardFound = new UiKit.StatCard(t("wjf.card.found"), WinzyPalette.accent());
		cardDone = new UiKit.StatCard(t("wjf.card.analyzed"), WinzyPalette.accent2());
		cardCritical = new UiKit.StatCard(t("wjf.card.critical"), Verdict.CRITICAL.color());
		cardDetected = new UiKit.StatCard(t("wjf.card.detected"), Verdict.DETECTED.color());
		cardSuspicious = new UiKit.StatCard(t("wjf.card.suspicious"), Verdict.SUSPICIOUS.color());
		cardUnreadable = new UiKit.StatCard(t("wjf.card.unreadable"), Verdict.UNREADABLE.color());
		cardClean = new UiKit.StatCard(t("wjf.card.clean"), Verdict.CLEAN.color());

		add(buildTop(), BorderLayout.NORTH);
		add(buildBody(), BorderLayout.CENTER);

		table.getSelectionModel().addListSelectionListener(e -> {
			if (e.getValueIsAdjusting()) return;
			JarAnalysis a = table.selected();
			if (a == null) detail.showEmpty();
			else detail.show(a);
		});

		installRowActions();
		resetCounters();
		syncToggleLook();
	}

	public void setDecompilerOpener(OpenInDecompiler opener) {
		this.decompilerOpener = opener;
	}

	// ---- row actions -------------------------------------------------------

	/**
	 * Double-click and right-click on a result.
	 *
	 * <p>Until now a finding was a dead end: you could read it but not act on it,
	 * and had to retype the path into Explorer to see the file. These are the four
	 * things anyone actually wants next.
	 */
	private void installRowActions() {
		final javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();

		javax.swing.JMenuItem openDecompiler = new javax.swing.JMenuItem(t("wjf.ctx.decompile"));
		openDecompiler.addActionListener(e -> openSelectedInDecompiler());
		menu.add(openDecompiler);
		menu.addSeparator();

		javax.swing.JMenuItem openFolder = new javax.swing.JMenuItem(t("wjf.ctx.folder"));
		openFolder.addActionListener(e -> revealSelected());
		menu.add(openFolder);

		javax.swing.JMenuItem copyPath = new javax.swing.JMenuItem(t("wjf.ctx.copyPath"));
		copyPath.addActionListener(e -> {
			JarAnalysis a = table.selected();
			if (a != null) toClipboard(a.getPath());
		});
		menu.add(copyPath);

		javax.swing.JMenuItem copyHash = new javax.swing.JMenuItem(t("wjf.ctx.copyHash"));
		copyHash.addActionListener(e -> {
			JarAnalysis a = table.selected();
			if (a != null && !a.getSha256().isEmpty()) toClipboard(a.getSha256());
		});
		menu.add(copyHash);

		table.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				if (e.getClickCount() == 2 && e.getButton() == java.awt.event.MouseEvent.BUTTON1) {
					openSelectedInDecompiler();
				}
			}

			@Override
			public void mousePressed(java.awt.event.MouseEvent e) {
				maybeShow(e);
			}

			@Override
			public void mouseReleased(java.awt.event.MouseEvent e) {
				maybeShow(e);
			}

			private void maybeShow(java.awt.event.MouseEvent e) {
				// isPopupTrigger fires on press on some platforms and release on
				// others, so both are checked.
				if (!e.isPopupTrigger()) return;
				int row = table.rowAtPoint(e.getPoint());
				if (row < 0) return;
				table.setRowSelectionInterval(row, row);

				JarAnalysis a = table.selected();
				copyHash.setEnabled(a != null && !a.getSha256().isEmpty());
				openDecompiler.setEnabled(a != null && a.getFile().isFile());
				menu.show(table, e.getX(), e.getY());
			}
		});
	}

	public void openSelectedInDecompiler() {
		JarAnalysis a = table.selected();
		if (a == null || decompilerOpener == null) return;
		if (!a.getFile().isFile()) {
			JOptionPane.showMessageDialog(this, t("wjf.ctx.gone"),
					t("wjf.ctx.decompile"), JOptionPane.WARNING_MESSAGE);
			return;
		}
		decompilerOpener.open(a.getFile(), matchedTerms(a));
	}

	/**
	 * The literal strings the blacklist matched, for highlighting in the code.
	 *
	 * <p>Regex patterns are skipped: the pattern text is not what appears in the
	 * file, and marking {@code (?i)\bbypass...} would highlight nothing while
	 * looking like a bug. Structural findings ("disguised extension") have no text
	 * to mark either.
	 */
	private static java.util.Collection<String> matchedTerms(JarAnalysis a) {
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
		for (com.jaranalyzer.scan.Finding f : a.getFindings()) {
			String p = f.getPattern();
			if (p == null || p.isEmpty()) continue;
			if (p.startsWith("(?") || p.contains("\\b") || p.contains("[")) continue;
			out.add(p);
		}
		return out;
	}

	private void revealSelected() {
		JarAnalysis a = table.selected();
		if (a == null) return;
		try {
			// "explorer /select," highlights the file itself rather than just
			// opening the folder. It exits non-zero even on success, so its
			// result is deliberately not checked.
			new ProcessBuilder("explorer.exe", "/select," + a.getPath()).start();
		} catch (Exception ex) {
			try {
				java.awt.Desktop.getDesktop().open(a.getFile().getParentFile());
			} catch (Exception ignored) {
				// Nothing sensible left to try.
			}
		}
	}

	private void toClipboard(String s) {
		java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new java.awt.datatransfer.StringSelection(s), null);
	}

	private static String t(String key) {
		return LanguageManager.getString(key);
	}

	// ---- layout ------------------------------------------------------------

	private JComponent buildTop() {
		JPanel top = new JPanel();
		top.setOpaque(false);
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

		header.actions().add(exportBtn);
		top.add(header);

		// --- action bar ---
		JPanel bar = new JPanel();
		bar.setOpaque(false);
		bar.setBorder(BorderFactory.createEmptyBorder(14, 22, 8, 22));
		bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));

		bar.add(scanAllBtn);
		bar.add(Box.createHorizontalStrut(8));
		bar.add(scanFolderBtn);
		bar.add(Box.createHorizontalStrut(8));
		bar.add(scanMemoryBtn);
		bar.add(Box.createHorizontalStrut(8));
		bar.add(stopBtn);
		bar.add(Box.createHorizontalStrut(16));
		bar.add(UiKit.vDivider(24));
		bar.add(Box.createHorizontalStrut(16));

		progress.setStringPainted(false);
		progress.setPreferredSize(new Dimension(220, 6));
		progress.setMaximumSize(new Dimension(320, 6));
		progress.setBorderPainted(false);
		progress.setBackground(WinzyPalette.INSET);
		progress.setForeground(WinzyPalette.accent());

		statusLabel.setFont(WinzyTheme.ui(Font.PLAIN, 12f));
		statusLabel.setForeground(WinzyPalette.TEXT_DIM);

		JPanel progressBox = new JPanel();
		progressBox.setOpaque(false);
		progressBox.setLayout(new BoxLayout(progressBox, BoxLayout.Y_AXIS));
		statusLabel.setAlignmentX(LEFT_ALIGNMENT);
		progress.setAlignmentX(LEFT_ALIGNMENT);
		progressBox.add(statusLabel);
		progressBox.add(Box.createVerticalStrut(6));
		progressBox.add(progress);
		bar.add(progressBox);
		bar.add(Box.createHorizontalGlue());

		top.add(bar);

		// --- stat cards ---
		cardsRow = new JPanel();
		cardsRow.setOpaque(false);
		cardsRow.setBorder(BorderFactory.createEmptyBorder(4, 22, 10, 22));
		cardsRow.setLayout(new BoxLayout(cardsRow, BoxLayout.X_AXIS));
		for (UiKit.StatCard c : new UiKit.StatCard[] {
				cardFound, cardDone, cardCritical, cardDetected,
				cardSuspicious, cardUnreadable, cardClean }) {
			cardsRow.add(c);
			cardsRow.add(Box.createHorizontalStrut(10));
		}
		cardsRow.setVisible(prefs.isShowStatCards());
		top.add(cardsRow);

		top.add(buildFilterBar());
		return top;
	}

	private JComponent buildFilterBar() {
		JPanel p = new JPanel();
		p.setOpaque(false);
		p.setBorder(BorderFactory.createEmptyBorder(0, 22, 10, 22));
		p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));

		JLabel searchIcon = UiKit.caption(t("wjf.filter.search"));
		p.add(searchIcon);
		p.add(Box.createHorizontalStrut(8));

		searchField.setPreferredSize(new Dimension(230, 28));
		searchField.setMaximumSize(new Dimension(230, 28));
		searchField.setFont(WinzyTheme.ui(Font.PLAIN, 12.5f));
		searchField.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) { apply(); }

			@Override public void removeUpdate(DocumentEvent e) { apply(); }

			@Override public void changedUpdate(DocumentEvent e) { apply(); }

			private void apply() {
				table.setSearch(searchField.getText());
				updateFilterCount();
			}
		});
		p.add(searchField);
		p.add(Box.createHorizontalStrut(14));

		// Age filter. A JAR written during today's session is a different
		// proposition from a library untouched since 2019, and the timestamp was
		// already being collected without ever being shown.
		ageFilter.removeAllItems();
		for (String key : new String[] {
				"wjf.age.all", "wjf.age.1", "wjf.age.7", "wjf.age.30" }) {
			ageFilter.addItem(t(key));
		}
		ageFilter.setPreferredSize(new Dimension(130, 26));
		ageFilter.setMaximumSize(new Dimension(130, 26));
		ageFilter.setFont(WinzyTheme.ui(Font.PLAIN, 11.5f));
		ageFilter.addActionListener(e -> {
			int[] days = { 0, 1, 7, 30 };
			int i = ageFilter.getSelectedIndex();
			table.setMaxAgeDays(i >= 0 && i < days.length ? days[i] : 0);
			updateFilterCount();
		});
		p.add(ageFilter);

		p.add(Box.createHorizontalStrut(16));
		p.add(UiKit.vDivider(20));
		p.add(Box.createHorizontalStrut(16));

		filterBoxes.clear();
		for (Verdict v : Verdict.values()) {
			JCheckBox cb = new JCheckBox(v.display(), true);
			cb.setOpaque(false);
			cb.setFocusPainted(false);
			cb.setFont(WinzyTheme.ui(Font.BOLD, 11.5f));
			cb.setForeground(v.color());
			cb.putClientProperty("verdict", v);
			cb.addActionListener(e -> {
				table.setVerdictAllowed(v, cb.isSelected());
				updateFilterCount();
			});
			filterBoxes.add(cb);
			p.add(cb);
			p.add(Box.createHorizontalStrut(6));
		}

		p.add(Box.createHorizontalGlue());
		filterCountLabel = UiKit.caption("");
		p.add(filterCountLabel);

		// View toggles, at the far right where File Explorer keeps its own.
		p.add(Box.createHorizontalStrut(16));
		p.add(UiKit.vDivider(20));
		p.add(Box.createHorizontalStrut(12));

		cardsToggle = (UiKit.PillButton) UiKit.ghost(t("wjf.view.cards"),
				e -> setStatCardsVisible(!cardsRow.isVisible()));
		detailToggle = (UiKit.PillButton) UiKit.ghost(t("wjf.view.detail"),
				e -> setDetailVisible(!detail.isVisible()));
		for (UiKit.PillButton b : new UiKit.PillButton[] { cardsToggle, detailToggle }) {
			b.setFont(WinzyTheme.ui(Font.BOLD, 11f));
			b.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
		}
		p.add(cardsToggle);
		p.add(Box.createHorizontalStrut(6));
		p.add(detailToggle);

		return p;
	}

	// ---- collapsible panels ------------------------------------------------

	private void setStatCardsVisible(boolean on) {
		cardsRow.setVisible(on);
		prefs.setShowStatCards(on);
		syncToggleLook();
		revalidate();
		repaint();
	}

	private void setDetailVisible(boolean on) {
		if (on) {
			detail.setVisible(true);
			bodySplit.setDividerSize(6);
			bodySplit.setDividerLocation(prefs.getDetailSplitRatio());
		} else {
			// Remember where the divider was before collapsing, so showing the
			// pane again restores the size the user chose rather than a default.
			rememberSplitRatio();
			detail.setVisible(false);
			// A zero-size divider is what makes the collapse read as "gone"
			// rather than "squashed to a line you can still grab".
			bodySplit.setDividerSize(0);
		}
		prefs.setShowDetailPane(on);
		syncToggleLook();
		bodySplit.revalidate();
		bodySplit.repaint();
	}

	private void rememberSplitRatio() {
		int h = bodySplit.getHeight();
		if (h > 0 && detail.isVisible()) {
			double ratio = bodySplit.getDividerLocation() / (double) h;
			if (ratio > 0.15 && ratio < 0.9) prefs.setDetailSplitRatio(ratio);
		}
	}

	private void syncToggleLook() {
		if (cardsToggle != null) cardsToggle.setActive(cardsRow.isVisible());
		if (detailToggle != null) detailToggle.setActive(detail.isVisible());
	}

	/** Called on window close so the layout survives a restart. */
	public void saveLayoutState() {
		rememberSplitRatio();
	}

	private JComponent buildBody() {
		JScrollPane tableScroll = new JScrollPane(table);
		tableScroll.setBorder(BorderFactory.createLineBorder(WinzyPalette.LINE));
		tableScroll.getViewport().setBackground(WinzyPalette.PANEL);
		tableScroll.getVerticalScrollBar().setUnitIncrement(20);

		final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, detail);
		bodySplit = split;
		split.setResizeWeight(prefs.getDetailSplitRatio());
		split.setBorder(BorderFactory.createEmptyBorder(0, 22, 18, 22));
		split.setOpaque(false);
		split.setContinuousLayout(true);
		tableScroll.setMinimumSize(new Dimension(200, 120));
		detail.setMinimumSize(new Dimension(200, 140));

		// Double-clicking the divider collapses or restores the detail pane, the
		// way the same gesture works on an Explorer splitter.
		split.setUI(new javax.swing.plaf.basic.BasicSplitPaneUI() {
			@Override
			public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
				return new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
					private static final long serialVersionUID = 1L;

					@Override
					public void paint(java.awt.Graphics g) {
						// Match the surrounding chrome; the default divider paints a
						// light 3D bevel that looks broken on a dark theme.
						g.setColor(WinzyPalette.BG);
						g.fillRect(0, 0, getWidth(), getHeight());
						g.setColor(WinzyPalette.LINE);
						int y = getHeight() / 2;
						g.fillRect(getWidth() / 2 - 18, y, 36, 1);
					}
				};
			}
		});
		split.setBorder(BorderFactory.createEmptyBorder(0, 22, 18, 22));
		split.getComponent(0).addMouseListener(new java.awt.event.MouseAdapter() { });
		for (java.awt.Component c : split.getComponents()) {
			if (c instanceof javax.swing.plaf.basic.BasicSplitPaneDivider) {
				c.addMouseListener(new java.awt.event.MouseAdapter() {
					@Override
					public void mouseClicked(java.awt.event.MouseEvent e) {
						if (e.getClickCount() == 2) setDetailVisible(!detail.isVisible());
					}
				});
			}
		}

		// A proportional divider location is ignored until the split pane has a
		// real size, so it is applied once, on the first layout that gives it one.
		split.addComponentListener(new java.awt.event.ComponentAdapter() {
			private boolean placed;

			@Override
			public void componentResized(java.awt.event.ComponentEvent e) {
				if (placed || split.getHeight() <= 0) return;
				placed = true;
				setDetailVisible(prefs.isShowDetailPane());
			}
		});
		return split;
	}

	// =====================================================================
	//  Scanning
	// =====================================================================

	private void startFullScan() {
		// No elevation check here any more: the packaged launcher requires
		// administrator in its manifest, so the fast MFT sweep is always
		// available and there is nothing to prompt about.
		settings.scanAllDrives = true;
		settings.roots.clear();
		launch(null, t("wjf.status.discovering"));
	}

	private void startFolderScan() {
		JFileChooser fc = new JFileChooser();
		fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
		fc.setDialogTitle(t("wjf.btn.scanFolder"));
		if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

		File chosen = fc.getSelectedFile();
		if (chosen == null) return;

		List<File> explicit = new ArrayList<>();
		if (chosen.isFile()) {
			explicit.add(chosen);
			launch(explicit, t("wjf.status.analyzing"));
		} else {
			settings.scanAllDrives = false;
			settings.roots.clear();
			settings.roots.add(chosen.getAbsolutePath());
			launch(null, t("wjf.status.discovering"));
		}
	}

	/**
	 * Analyses what the running Java processes have actually loaded.
	 *
	 * <p>Complements the disk sweep rather than repeating it: a cheat that was
	 * loaded and then deleted leaves nothing on disk but is still on the live
	 * JVM's classpath.
	 */
	public void startMemoryScan() {
		if (isScanning()) return;

		// Either route on its own is enough to run the scan. The attach route reads
		// more (agents, full classpath) but the target can refuse it; the process
		// route always answers because it asks Windows rather than the JVM.
		if (!com.jaranalyzer.scan.JvmScanner.isAvailable()
				&& !com.jaranalyzer.scan.ProcessScanner.isSupported()) {
			JOptionPane.showMessageDialog(this, t("wjf.mem.unavailable"),
					t("wjf.btn.scanMemory"), JOptionPane.WARNING_MESSAGE);
			return;
		}

		setScanning(true);
		statusLabel.setText(t("wjf.mem.scanning"));
		progress.setIndeterminate(true);
		table.clearResults();
		detail.showEmpty();
		resetCounters();

		scanThread = new Thread(() -> {
			List<com.jaranalyzer.scan.JvmScanner.JvmInfo> jvms =
					com.jaranalyzer.scan.JvmScanner.scan();

			final List<File> jars = new ArrayList<>();
			final List<String> missing = new ArrayList<>();
			int jvmCount = 0;

			for (com.jaranalyzer.scan.JvmScanner.JvmInfo v : jvms) {
				if (v.error != null && v.jars.isEmpty()) continue;
				jvmCount++;
				for (File f : v.jars) {
					if (!jars.contains(f)) jars.add(f);
				}
				missing.addAll(v.missingFromDisk);
			}

			// Second route, over the top of the first. A JVM launched with
			// -XX:+DisableAttachMechanism answers nothing above — verified: the
			// attach scan drops from one JVM to zero while the cheat keeps running
			// — but Windows still knows its command line, and the command line is
			// where "javaw.exe -cp yks1233.dll" names the disguised file outright.
			final List<com.jaranalyzer.scan.ProcessScanner.ProcInfo> hidden = new ArrayList<>();
			for (com.jaranalyzer.scan.ProcessScanner.ProcInfo p
					: com.jaranalyzer.scan.ProcessScanner.scan()) {
				boolean known = false;
				for (com.jaranalyzer.scan.JvmScanner.JvmInfo v : jvms) {
					if (String.valueOf(p.pid).equals(v.pid)) {
						known = true;
						break;
					}
				}
				if (!known) jvmCount++;
				if (p.attachDisabled) hidden.add(p);

				for (File f : p.referencedFiles) {
					if (!jars.contains(f)) jars.add(f);
				}
				for (String m : p.missingFiles) {
					if (!missing.contains(m)) missing.add(m);
				}
			}

			final int foundJvms = jvmCount;
			SwingUtilities.invokeLater(() -> {
				statusLabel.setText(String.format(Locale.ROOT, "%s  —  %d JVM, %d JAR",
						t("wjf.mem.scanning"), foundJvms, jars.size()));
				// A classpath entry whose file is gone is the strongest thing this
				// scan can surface, and there is no file left to analyse — so a row
				// is synthesised for it. A dialog would have been easier, but this
				// has to survive into the report and stay selectable: the whole
				// point is that the evidence no longer exists anywhere else.
				for (String m : missing) {
					JarAnalysis ghost = new JarAnalysis(new java.io.File(m));
					ghost.add(com.jaranalyzer.scan.Finding.heuristic(
							t("wjf.h.ghost"),
							com.jaranalyzer.scan.Severity.CRITICAL,
							com.jaranalyzer.scan.Finding.Source.FILE_NAME,
							t("wjf.cat.structure"),
							m,
							t("wjf.h.ghost.why")));
					ghost.setVerdict(com.jaranalyzer.scan.Verdict.DETECTED);
					ghost.setRiskScore(1000);
					table.addResult(ghost);
					bumpVerdictCard(ghost.getVerdict());
					ghostRows++;
				}
				// A process that turned off the attach mechanism gets its own row.
				// Ordinary games and build tools do not set that flag; the reason to
				// set it is to stop exactly this kind of inspection, so the fact is
				// worth reporting even when every file it named looks ordinary.
				for (com.jaranalyzer.scan.ProcessScanner.ProcInfo p : hidden) {
					JarAnalysis row = new JarAnalysis(new java.io.File(p.exe));
					row.add(com.jaranalyzer.scan.Finding.heuristic(
							t("wjf.h.attachOff"),
							com.jaranalyzer.scan.Severity.HIGH,
							com.jaranalyzer.scan.Finding.Source.FILE_NAME,
							t("wjf.cat.structure"),
							"PID " + p.pid,
							t("wjf.h.attachOff.why") + "\n" + p.commandLine));
					row.setVerdict(com.jaranalyzer.scan.Verdict.NOTABLE);
					row.setRiskScore(300);
					table.addResult(row);
					bumpVerdictCard(row.getVerdict());
					ghostRows++;
				}

				cardFound.setValue(String.format(Locale.ROOT, "%,d", jars.size() + ghostRows));
				cardDone.setValue(String.format(Locale.ROOT, "%,d", ghostRows));
				updateFilterCount();
			});

			if (jars.isEmpty()) {
				SwingUtilities.invokeLater(() -> {
					setScanning(false);
					progress.setIndeterminate(false);
					statusLabel.setText(t("wjf.mem.none"));
				});
				return;
			}

			controller = new ScanController(settings, blacklist);
			controller.runOn(jars, listener());
		}, "wjf-memscan");
		scanThread.setDaemon(true);
		scanThread.start();
	}

	/**
	 * Starts a scan of one file or directory programmatically — used by the
	 * drag-and-drop handler and by the headless screenshot mode.
	 */
	public void scanTarget(File target) {
		if (target == null || !target.exists()) return;
		if (target.isFile()) {
			List<File> one = new ArrayList<>();
			one.add(target);
			launch(one, t("wjf.status.analyzing"));
		} else {
			settings.scanAllDrives = false;
			settings.roots.clear();
			settings.roots.add(target.getAbsolutePath());
			launch(null, t("wjf.status.discovering"));
		}
	}

	private void launch(List<File> explicitTargets, String initialStatus) {
		if (scanThread != null && scanThread.isAlive()) return;

		table.clearResults();
		detail.showEmpty();
		resetCounters();
		blacklist.compile();

		setScanning(true);
		statusLabel.setText(initialStatus);
		progress.setIndeterminate(true);

		controller = new ScanController(settings, blacklist);
		final ScanController c = controller;
		final List<File> targets = explicitTargets;

		scanThread = new Thread(() -> {
			try {
				if (targets != null) c.runOn(targets, listener());
				else c.run(listener());
			} catch (Throwable ex) {
				SwingUtilities.invokeLater(() -> {
					setScanning(false);
					statusLabel.setText(t("wjf.status.error") + ": " + ex);
				});
			}
		}, "wjf-scan");
		scanThread.setDaemon(true);
		scanThread.start();
	}

	private void stopScan() {
		ScanController c = controller;
		if (c != null) {
			c.requestStop();
			statusLabel.setText(t("wjf.status.stopping"));
			stopBtn.setEnabled(false);
		}
	}

	private ScanController.Listener listener() {
		return new ScanController.Listener() {
			private long lastUi;

			@Override
			public void onPhase(String phase) {
				SwingUtilities.invokeLater(() -> statusLabel.setText(
						"discover".equals(phase) ? t("wjf.status.discovering") : t("wjf.status.analyzing")));
			}

			@Override
			public void onDiscovery(long filesSeen, long jarsFound, String dir) {
				found.set((int) jarsFound);
				long now = System.currentTimeMillis();
				// Discovery fires thousands of times a second; repainting on every
				// one starves the EDT and the window stops responding.
				if (now - lastUi < 120) return;
				lastUi = now;
				SwingUtilities.invokeLater(() -> {
					cardFound.setValue(String.format(Locale.ROOT, "%,d", jarsFound));
					statusLabel.setText(String.format(Locale.ROOT, "%s  —  %,d %s",
							t("wjf.status.discovering"), filesSeen, t("wjf.status.files")));
				});
			}

			@Override
			public void onJarStarted(File jar) {
			}

			@Override
			public void onJarAnalyzed(JarAnalysis a, int done, int total) {
				analyzed.set(done);
				SwingUtilities.invokeLater(() -> {
					table.addResult(a);
					bumpVerdictCard(a.getVerdict());
					cardDone.setValue(String.format(Locale.ROOT, "%,d", done + ghostRows));
					cardFound.setValue(String.format(Locale.ROOT, "%,d",
							Math.max(total, done) + ghostRows));
					if (total > 0) {
						progress.setIndeterminate(false);
						progress.setMaximum(total);
						progress.setValue(Math.min(done, total));
					}
					statusLabel.setText(String.format(Locale.ROOT, "%s  %,d / %,d",
							t("wjf.status.analyzing"), done, total));
					updateFilterCount();
				});
			}

			@Override
			public void onFinished(ScanController.Summary s) {
				lastSummary = s;
				SwingUtilities.invokeLater(() -> {
					setScanning(false);
					progress.setIndeterminate(false);
					progress.setValue(progress.getMaximum());
					statusLabel.setText(String.format(Locale.ROOT,
							"%s  —  %,d %s, %,d %s, %.1f s",
							t("wjf.status.done"), s.analyzed, t("wjf.status.analyzedWord"),
							s.attentionCount(), t("wjf.status.flagged"), s.elapsedMillis / 1000.0));
					exportBtn.setEnabled(!s.results.isEmpty());
					updateFilterCount();
				});
			}
		};
	}

	private void setScanning(boolean on) {
		scanAllBtn.setEnabled(!on);
		scanFolderBtn.setEnabled(!on);
		scanMemoryBtn.setEnabled(!on);
		stopBtn.setEnabled(on);
		if (!on) progress.setIndeterminate(false);
	}

	// ---- counters ----------------------------------------------------------

	private final int[] verdictCounts = new int[Verdict.values().length];

	/**
	 * Rows the memory scan synthesised for jars that are loaded but gone from
	 * disk. They are real results with no file behind them, so the controller
	 * never counts them — without this offset the cards would report fewer
	 * findings than the table visibly shows.
	 */
	private int ghostRows;

	private void resetCounters() {
		found.set(0);
		analyzed.set(0);
		ghostRows = 0;
		java.util.Arrays.fill(verdictCounts, 0);
		cardFound.setValue("0");
		cardDone.setValue("0");
		cardCritical.setValue("0");
		cardDetected.setValue("0");
		cardSuspicious.setValue("0");
		cardUnreadable.setValue("0");
		cardClean.setValue("0");
		progress.setValue(0);
		exportBtn.setEnabled(false);
		lastSummary = null;
		updateFilterCount();
	}

	private void bumpVerdictCard(Verdict v) {
		verdictCounts[v.ordinal()]++;
		int n = verdictCounts[v.ordinal()];
		String s = String.format(Locale.ROOT, "%,d", n);
		switch (v) {
			case CRITICAL: cardCritical.setValue(s); break;
			case DETECTED: cardDetected.setValue(s); break;
			case SUSPICIOUS: cardSuspicious.setValue(s); break;
			case UNREADABLE: cardUnreadable.setValue(s); break;
			case CLEAN:
			case NOTABLE:
				cardClean.setValue(String.format(Locale.ROOT, "%,d",
						verdictCounts[Verdict.CLEAN.ordinal()] + verdictCounts[Verdict.NOTABLE.ordinal()]));
				break;
			default: break;
		}
	}

	private void updateFilterCount() {
		if (filterCountLabel == null) return;
		filterCountLabel.setText(String.format(Locale.ROOT, "%,d / %,d %s",
				table.visibleCount(), table.allResults().size(), t("wjf.filter.shown")));
	}

	// ---- export ------------------------------------------------------------

	private void exportReport() {
		List<JarAnalysis> all = table.allResults();
		if (all.isEmpty()) return;

		// Built from what is on screen rather than from the controller's summary.
		// The running-JVM scan synthesises rows for jars that are loaded but no
		// longer on disk, and those never pass through the controller — exporting
		// its summary would drop the strongest evidence the tool can produce.
		ScanController.Summary s = new ScanController.Summary();
		s.results.addAll(all);
		s.analyzed = all.size();
		s.totalFound = all.size();
		for (JarAnalysis a : all) s.byVerdict.merge(a.getVerdict(), 1, Integer::sum);
		if (lastSummary != null) {
			s.filesSeen = lastSummary.filesSeen;
			s.elapsedMillis = lastSummary.elapsedMillis;
		}

		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle(t("wjf.export.title"));
		fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

		File dir = fc.getSelectedFile();
		try {
			File html = new File(dir, "wjf-report.html");
			ReportWriter.writeHtml(html, s);
			ReportWriter.writeJson(new File(dir, "wjf-report.json"), s);
			ReportWriter.writeText(new File(dir, "wjf-report.txt"), s);

			int choice = JOptionPane.showConfirmDialog(this,
					t("wjf.export.done") + "\n" + dir.getAbsolutePath() + "\n\n" + t("wjf.export.open"),
					t("wjf.export.title"), JOptionPane.YES_NO_OPTION);
			if (choice == JOptionPane.YES_OPTION && java.awt.Desktop.isDesktopSupported()) {
				java.awt.Desktop.getDesktop().browse(html.toURI());
			}
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this, t("wjf.export.fail") + "\n" + ex,
					t("wjf.export.title"), JOptionPane.ERROR_MESSAGE);
		}
	}

	// ---- language ----------------------------------------------------------

	public void updateLanguage() {
		header.setSubtitle(t("wjf.header.sub"));
		scanAllBtn.setText(t("wjf.btn.scanAll"));
		scanFolderBtn.setText(t("wjf.btn.scanFolder"));
		stopBtn.setText(t("wjf.btn.stop"));
		exportBtn.setText(t("wjf.btn.export"));
		cardFound.setCaption(t("wjf.card.found"));
		cardDone.setCaption(t("wjf.card.analyzed"));
		cardCritical.setCaption(t("wjf.card.critical"));
		cardDetected.setCaption(t("wjf.card.detected"));
		cardSuspicious.setCaption(t("wjf.card.suspicious"));
		cardUnreadable.setCaption(t("wjf.card.unreadable"));
		cardClean.setCaption(t("wjf.card.clean"));
		for (JCheckBox cb : filterBoxes) {
			Verdict v = (Verdict) cb.getClientProperty("verdict");
			if (v != null) cb.setText(v.display());
		}
		table.refreshLabels();
		detail.updateLanguage();
		updateFilterCount();
		repaint();
	}

	public boolean isScanning() {
		Thread th = scanThread;
		return th != null && th.isAlive();
	}

	/** Selects the first visible row, so the detail pane has something to show. */
	public void selectFirstRow() {
		if (table.getRowCount() > 0) {
			table.setRowSelectionInterval(0, 0);
		}
	}

	/** Switches the detail pane to a given tab (0 = overview, 2 = decompiled code). */
	public void selectDetailTab(int index) {
		detail.selectTab(index);
	}
}
