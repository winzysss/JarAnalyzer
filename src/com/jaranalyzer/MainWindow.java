package com.jaranalyzer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.io.File;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.dnd.DropTarget;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.border.BevelBorder;
import javax.swing.border.EmptyBorder;

public class MainWindow extends JFrame {

	private static final long serialVersionUID = 1L;

	public static Model model;
	public static MainWindow instance;

	private ConfigSaver configSaver;
	private WindowPosition windowPosition;
	private AppPreferences appPrefs;
	private MainMenuBar mainMenuBar;
	private MainToolBar toolBar;

	private JLabel label;
	private JProgressBar bar;
	private FileDialog fileDialog;
	private FileSaver fileSaver;


	private JTabbedPane mainTabbedPane;

	// ---- Winzy engine + UI ------------------------------------------------
	private com.jaranalyzer.scan.ScanSettings scanSettings;
	private com.jaranalyzer.scan.Blacklist blacklist;
	private com.jaranalyzer.ui.ScanPanel scanPanel;
	private com.jaranalyzer.ui.BlacklistPanel blacklistPanel;
	private com.jaranalyzer.ui.SettingsPanel settingsPanel;

	public MainWindow(File fileFromCommandLine) {
		instance = this;
		configSaver = ConfigSaver.getLoadedInstance();
		windowPosition = configSaver.getMainWindowPosition();
		appPrefs = configSaver.getAppPreferences();
		LanguageManager.setLanguage(LanguageManager.Language.fromCode(appPrefs.getLanguage()));

		// Apply user keyword preferences
		java.util.List<String> savedUserKws = appPrefs.getUserKeywords();
		if (savedUserKws != null && !savedUserKws.isEmpty()) {
			java.util.Set<String> defaultKws = CheatDetector.DEFAULT_CHEAT_KEYWORDS;
			java.util.Set<String> finalKws = new java.util.HashSet<>(defaultKws);
			for (String uk : savedUserKws) {
				if (!defaultKws.contains(uk)) {
					finalKws.add(uk);
				}
			}
			java.util.Set<String> toRemove = new java.util.HashSet<>();
			for (String dk : defaultKws) {
				if (!savedUserKws.contains(dk) && !finalKws.contains(dk)) {
					toRemove.add(dk);
				}
			}
			for (String uk : savedUserKws) {
				if (!defaultKws.contains(uk)) {
					finalKws.add(uk);
				}
			}
			for (String dk : defaultKws) {
				if (!savedUserKws.contains(dk)) {
					finalKws.remove(dk);
				}
			}
			CheatDetector.CHEAT_KEYWORDS.clear();
			CheatDetector.CHEAT_KEYWORDS.addAll(finalKws);
		}

		applyFlatTheme();

		mainMenuBar = new MainMenuBar(this);
		this.setJMenuBar(mainMenuBar);

		toolBar = new MainToolBar(this);
		this.add(toolBar, BorderLayout.NORTH);

		this.adjustWindowPositionBySavedState();
		this.setQuitOnWindowClosing();
		this.setTitle(LanguageManager.getString("app.title"));
		try {
			this.setIconImage(new ImageIcon(
					Toolkit.getDefaultToolkit().getImage(this.getClass().getResource("/resources/appicon.png"))).getImage());
		} catch (Exception e) {}

		JPanel statusPanel = createStatusBar();
		model = new Model(this);

		// The engine's configuration is loaded once and shared: the blacklist
		// instance edited on the Blacklist tab is the same object the scanner
		// compiles, so an added term is live on the next scan.
		scanSettings = com.jaranalyzer.scan.ScanSettings.load();
		blacklist = com.jaranalyzer.scan.BlacklistStore.load();

		scanPanel = new com.jaranalyzer.ui.ScanPanel(scanSettings, blacklist, appPrefs);
		blacklistPanel = new com.jaranalyzer.ui.BlacklistPanel(blacklist);
		settingsPanel = new com.jaranalyzer.ui.SettingsPanel(scanSettings, this::changeAccentScheme);

		mainTabbedPane = new JTabbedPane(JTabbedPane.TOP);
		// Metal paints the strip behind the tabs with the pane's own background
		// rather than the TabbedPane.* keys, so it has to be set on the instance
		// or the empty run to the right of the last tab stays light.
		mainTabbedPane.setOpaque(true);
		mainTabbedPane.setBackground(com.jaranalyzer.ui.WinzyPalette.BG);
		mainTabbedPane.setForeground(com.jaranalyzer.ui.WinzyPalette.TEXT);
		mainTabbedPane.setBorder(javax.swing.BorderFactory.createEmptyBorder());
		mainTabbedPane.addTab(LanguageManager.getString("wjf.tab.scan"), scanPanel);
		mainTabbedPane.addTab(LanguageManager.getString("wjf.tab.blacklist"), blacklistPanel);
		mainTabbedPane.addTab(LanguageManager.getString("tab.decompile"), model);
		mainTabbedPane.addTab(LanguageManager.getString("wjf.tab.settings"), settingsPanel);
		applyTabTooltips();

		// Double-click or right-click a result to open it in the Decompile tab,
		// so a suspicious JAR is one click from being read rather than reopened by
		// hand.
		scanPanel.setDecompilerOpener((jar, terms) -> {
			mainTabbedPane.setSelectedIndex(2);
			model.setScanHits(terms);
			model.loadFile(jar);
			RecentFiles.add(jar.getAbsolutePath());
			mainMenuBar.updateRecentFiles();
		});

		this.getContentPane().add(mainTabbedPane, BorderLayout.CENTER);
		this.add(statusPanel, BorderLayout.SOUTH);

		if (fileFromCommandLine != null) {
			model.loadFile(fileFromCommandLine);
		}

		try {
			DropTarget dt = new DropTarget();
			dt.addDropTargetListener(new DropListener(this));
			this.setDropTarget(dt);
		} catch (Exception e) {
			JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e);
		}

		fileDialog = new FileDialog(this);
		fileSaver = new FileSaver(bar, label);

		this.setExitOnEscWhenEnabled();


		if (RecentFiles.load() > 0) mainMenuBar.updateRecentFiles();

		setInitialTheme();
	}

	private void applyFlatTheme() {
		com.jaranalyzer.ui.WinzyTheme.apply(appPrefs.getAccentScheme());
	}

	private void setInitialTheme() {
		// The code viewer keeps its own syntax palette; "dracula" is the dark
		// scheme that sits closest to the Winzy chrome.
		String editorTheme = appPrefs.getThemeXml();
		if (editorTheme == null || editorTheme.isEmpty() || "light".equals(editorTheme)) {
			editorTheme = "dracula";
		}
		changeTheme(editorTheme);
	}

	/** Switches the syntax-highlighting theme used by the decompiled-code viewer. */
	public void changeTheme(String themeKey) {
		appPrefs.setThemeXml(themeKey);
		model.changeTheme(themeKey);
		repaint();
	}

	/** Switches the accent pair for the whole application chrome. */
	public void changeAccentScheme(String schemeKey) {
		appPrefs.setAccentScheme(schemeKey);
		com.jaranalyzer.ui.WinzyTheme.apply(schemeKey);
		SwingUtilitiesUpdateUI();
		repaint();
	}

	private void applyTabTooltips() {
		String[] tips = {
			LanguageManager.getString("wjf.tip.scan"),
			LanguageManager.getString("wjf.tip.blacklist"),
			LanguageManager.getString("tooltip.decompileTab"),
			LanguageManager.getString("wjf.tip.settings"),
		};
		for (int i = 0; i < tips.length && i < mainTabbedPane.getTabCount(); i++) {
			mainTabbedPane.setToolTipTextAt(i, tips[i]);
		}
	}

	private void SwingUtilitiesUpdateUI() {
		javax.swing.SwingUtilities.updateComponentTreeUI(this);
	}

	private JPanel createStatusBar() {
		JPanel statusPanel = new JPanel(new BorderLayout());
		statusPanel.setBorder(new BevelBorder(BevelBorder.LOWERED));

		JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		label = new JLabel(LanguageManager.getString("status.ready"));
		label.setHorizontalAlignment(JLabel.LEFT);
		leftPanel.add(label);

		JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		bar = new JProgressBar();
		bar.setStringPainted(true);
		bar.setOpaque(false);
		bar.setVisible(false);
		bar.setPreferredSize(new Dimension(200, 16));
		rightPanel.add(bar);

		statusPanel.add(leftPanel, BorderLayout.CENTER);
		statusPanel.add(rightPanel, BorderLayout.EAST);
		statusPanel.setPreferredSize(new Dimension(this.getWidth(), 24));

		return statusPanel;
	}

	private void adjustWindowPositionBySavedState() {
		if (windowPosition.isSavedWindowPositionValid()) {
			if (windowPosition.isFullScreen()) {
				this.setExtendedState(JFrame.MAXIMIZED_BOTH);
			} else {
				this.setSize(windowPosition.getWindowWidth(), windowPosition.getWindowHeight());
				this.setLocation(windowPosition.getWindowX(), windowPosition.getWindowY());
			}
		} else {
			Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
			int width = (int) (screenSize.width * 0.85);
			int height = (int) (screenSize.height * 0.85);
			this.setSize(width, height);
			this.setLocation((screenSize.width - width) / 2, (screenSize.height - height) / 2);
		}
	}

	private void setQuitOnWindowClosing() {
		this.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		this.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				// The divider position is only knowable while the window still has
				// a size, so it is captured here rather than in the panel's own
				// teardown.
				if (scanPanel != null) scanPanel.saveLayoutState();
				configSaver.getMainWindowPosition().readPositionFromWindow(MainWindow.this);
				configSaver.saveConfig();
				dispose();
				System.exit(0);
			}
		});
	}

	private void setExitOnEscWhenEnabled() {
		KeyStroke escKey = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, false);
		this.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escKey, "ExitOnEsc");
		this.getRootPane().getActionMap().put("ExitOnEsc", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				if (appPrefs.isExitByEscEnabled()) {
					configSaver.getMainWindowPosition().readPositionFromWindow(MainWindow.this);
					configSaver.saveConfig();
					dispose();
					System.exit(0);
				}
			}
		});
	}

	public void onFileDropped(File file) {
		model.loadFile(file);
	}

	public void onNavigationRequest(String uniqueStr) {
		model.onNavigationRequest(uniqueStr);
	}

	public void openFile() {
		File file = fileDialog.doOpenDialog();
		if (file != null) {
			model.loadFile(file);
			RecentFiles.add(file.getAbsolutePath());
			mainMenuBar.updateRecentFiles();
		}
	}

	public void closeFile() {
		model.closeFile();
	}

	public void saveFile() {
		OpenFile openFile = model.getCurrentOpenFile();
		if (openFile == null || !openFile.isContentValid()) {
			JOptionPane.showMessageDialog(this, LanguageManager.getString("dialog.noFileOpenOrContent"), LanguageManager.getString("menu.file.save"), JOptionPane.WARNING_MESSAGE);
			return;
		}
		String content = openFile.getText();
		if (content == null || content.trim().isEmpty()) {
			JOptionPane.showMessageDialog(this, LanguageManager.getString("dialog.noContentToSave"), LanguageManager.getString("menu.file.save"), JOptionPane.WARNING_MESSAGE);
			return;
		}
		File file = fileDialog.doSaveDialog(openFile.name);
		if (file != null) {
			fileSaver.saveText(content, file);
		}
	}

	public void saveAllFiles() {
		File openedFile = model.getOpenedFile();
		if (openedFile == null) {
			JOptionPane.showMessageDialog(this, LanguageManager.getString("dialog.noFileOpen"), LanguageManager.getString("menu.file.saveAll"), JOptionPane.WARNING_MESSAGE);
			return;
		}
		File file = fileDialog.doSaveAllDialog(openedFile.getName());
		if (file != null) {
			fileSaver.saveAllDecompiled(openedFile, file);
		}
	}

	public void exportAsHtml() {
		File openedFile = model.getOpenedFile();
		if (openedFile == null) {
			JOptionPane.showMessageDialog(this, LanguageManager.getString("dialog.noFileOpen"), LanguageManager.getString("menu.file.exportHtml"), JOptionPane.WARNING_MESSAGE);
			return;
		}
		String recommendedName = openedFile.getName().replaceAll("\\.(jar|zip)$", "") + ".html";
		File file = fileDialog.doSaveHtmlDialog(recommendedName);
		if (file != null) {
			fileSaver.saveAllDecompiled(openedFile, file);
		}
	}

	public void closeTab() {
		int pos = model.house.getSelectedIndex();
		if (pos >= 0) {
			model.closeOpenTab(pos);
		}
	}

	public Model getModel() {
		return model;
	}

	public JLabel getLabel() {
		return label;
	}

	public JProgressBar getBar() {
		return bar;
	}

	public FileDialog getFileDialog() {
		return fileDialog;
	}

	public FileSaver getFileSaver() {
		return fileSaver;
	}

	public MainMenuBar getMainMenuBar() {
		return mainMenuBar;
	}

	public MainToolBar getToolBar() {
		return toolBar;
	}

	public ConfigSaver getConfigSaver() {
		return configSaver;
	}

	public AppPreferences getAppPreferences() {
		return appPrefs;
	}

	public void toggleLanguage() {
		LanguageManager.toggleLanguage();
		appPrefs.setLanguage(LanguageManager.getCurrentLanguage().getCode());
		updateAllLanguageTexts();
	}

	public void updateAllLanguageTexts() {
		setTitle(LanguageManager.getString("app.title"));
		label.setText(LanguageManager.getString("status.ready"));
		mainMenuBar.rebuildMenus();
		toolBar.updateLanguage();
		model.updateLanguage();
		scanPanel.updateLanguage();
		blacklistPanel.updateLanguage();
		mainTabbedPane.setTitleAt(0, LanguageManager.getString("wjf.tab.scan"));
		mainTabbedPane.setTitleAt(1, LanguageManager.getString("wjf.tab.blacklist"));
		mainTabbedPane.setTitleAt(2, LanguageManager.getString("tab.decompile"));
		mainTabbedPane.setTitleAt(3, LanguageManager.getString("wjf.tab.settings"));
		applyTabTooltips();
	}

	public com.jaranalyzer.ui.ScanPanel getScanPanel() {
		return scanPanel;
	}

	public com.jaranalyzer.scan.Blacklist getBlacklist() {
		return blacklist;
	}

	public com.jaranalyzer.scan.ScanSettings getScanSettings() {
		return scanSettings;
	}

	public JTabbedPane getMainTabbedPane() {
		return mainTabbedPane;
	}
}
