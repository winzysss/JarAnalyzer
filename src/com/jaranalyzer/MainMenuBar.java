package com.jaranalyzer;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.prefs.Preferences;
import javax.swing.AbstractAction;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;

public class MainMenuBar extends JMenuBar {

	private static final long serialVersionUID = 1L;

	private MainWindow mainWindow;
	private DecompilerConfig decompilerConfig;
	private AppPreferences appPrefs;
	private ConfigSaver configSaver;

	private JMenuItem recentFilesItem;
	private JMenu fileMenu, settingsMenu, helpMenu;

	public MainMenuBar(MainWindow mainWnd) {
		this.mainWindow = mainWnd;
		configSaver = ConfigSaver.getLoadedInstance();
		decompilerConfig = configSaver.getDecompilerConfig();
		appPrefs = configSaver.getAppPreferences();

		rebuildMenus();
	}

	public void rebuildMenus() {
		this.removeAll();

		fileMenu = new JMenu(LanguageManager.getString("menu.file"));
		fileMenu.setMnemonic(KeyEvent.VK_F);
		fileMenu.setToolTipText(LanguageManager.getString("tooltip.fileMenu"));
		fileMenu.add(new JMenuItem("..."));
		this.add(fileMenu);


		settingsMenu = new JMenu(LanguageManager.getString("menu.settings"));
		settingsMenu.setMnemonic(KeyEvent.VK_S);
		settingsMenu.setToolTipText(LanguageManager.getString("tooltip.settingsMenu"));
		settingsMenu.add(new JMenuItem("..."));
		this.add(settingsMenu);

		helpMenu = new JMenu(LanguageManager.getString("menu.help"));
		helpMenu.setMnemonic(KeyEvent.VK_H);
		helpMenu.setToolTipText(LanguageManager.getString("tooltip.helpMenu"));
		helpMenu.add(new JMenuItem("..."));
		this.add(helpMenu);

		new Thread() {
			public void run() {
				try {
					buildFileMenu(fileMenu);
					buildSettingsMenu(settingsMenu);
					buildHelpMenu(helpMenu);

					updateRecentFiles();
				} catch (Exception e) {
					JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e);
				}
			}
		}.start();
	}

	private void buildFileMenu(JMenu fileMenu) {
		fileMenu.removeAll();

		JMenuItem openItem = new JMenuItem(LanguageManager.getString("menu.file.open"));
		openItem.setMnemonic(KeyEvent.VK_O);
		openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, KeyEvent.CTRL_DOWN_MASK));
		openItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				mainWindow.openFile();
			}
		});
		openItem.setToolTipText(LanguageManager.getString("tooltip.open"));
		fileMenu.add(openItem);

		JMenuItem closeItem = new JMenuItem(LanguageManager.getString("menu.file.close"));
		closeItem.setMnemonic(KeyEvent.VK_C);
		closeItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				mainWindow.closeFile();
			}
		});
		closeItem.setToolTipText(LanguageManager.getString("tooltip.close"));
		fileMenu.add(closeItem);

		fileMenu.addSeparator();

		JMenuItem saveItem = new JMenuItem(LanguageManager.getString("menu.file.save"));
		saveItem.setMnemonic(KeyEvent.VK_S);
		saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK));
		saveItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				mainWindow.saveFile();
			}
		});
		saveItem.setToolTipText(LanguageManager.getString("tooltip.save"));
		fileMenu.add(saveItem);

		JMenuItem saveAllItem = new JMenuItem(LanguageManager.getString("menu.file.saveAll"));
		saveAllItem.setMnemonic(KeyEvent.VK_A);
		saveAllItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				mainWindow.saveAllFiles();
			}
		});
		saveAllItem.setToolTipText(LanguageManager.getString("tooltip.saveAll"));
		fileMenu.add(saveAllItem);

		JMenuItem exportHtmlItem = new JMenuItem(LanguageManager.getString("menu.file.exportHtml"));
		exportHtmlItem.setMnemonic(KeyEvent.VK_H);
		exportHtmlItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				mainWindow.exportAsHtml();
			}
		});
		exportHtmlItem.setToolTipText(LanguageManager.getString("tooltip.exportHtml"));
		fileMenu.add(exportHtmlItem);

		fileMenu.addSeparator();

		recentFilesItem = new JMenu(LanguageManager.getString("menu.file.recent"));
		fileMenu.add(recentFilesItem);

		fileMenu.addSeparator();

		JMenuItem exitItem = new JMenuItem(LanguageManager.getString("menu.file.exit"));
		exitItem.setMnemonic(KeyEvent.VK_X);
		exitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F4, KeyEvent.ALT_DOWN_MASK));
		exitItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				configSaver.getMainWindowPosition().readPositionFromWindow(mainWindow);
				configSaver.saveConfig();
				mainWindow.dispose();
				System.exit(0);
			}
		});
		fileMenu.add(exitItem);
	}

	private void buildSettingsMenu(JMenu settingsMenu) {
		settingsMenu.removeAll();

		JMenu uiLanguageMenu = new JMenu(LanguageManager.getString("menu.settings.language"));
		ButtonGroup uiLangGroup = new ButtonGroup();

		JRadioButtonMenuItem trItem = new JRadioButtonMenuItem("Türkçe");
		trItem.setSelected(LanguageManager.getCurrentLanguage() == LanguageManager.Language.TR);
		trItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				LanguageManager.setLanguage(LanguageManager.Language.TR);
				appPrefs.setLanguage("tr");
				mainWindow.updateAllLanguageTexts();
			}
		});
		uiLangGroup.add(trItem);
		uiLanguageMenu.add(trItem);

		JRadioButtonMenuItem enItem = new JRadioButtonMenuItem("English");
		enItem.setSelected(LanguageManager.getCurrentLanguage() == LanguageManager.Language.EN);
		enItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				LanguageManager.setLanguage(LanguageManager.Language.EN);
				appPrefs.setLanguage("en");
				mainWindow.updateAllLanguageTexts();
			}
		});
		uiLangGroup.add(enItem);
		uiLanguageMenu.add(enItem);

		settingsMenu.add(uiLanguageMenu);

		settingsMenu.addSeparator();

		JCheckBoxMenuItem deobfItem = new JCheckBoxMenuItem(LanguageManager.getString("menu.settings.deobfuscate"), appPrefs.isDeobfuscateEnabled());
		deobfItem.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				appPrefs.setDeobfuscateEnabled(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		settingsMenu.add(deobfItem);
	}

	private void buildHelpMenu(JMenu helpMenu) {
		helpMenu.removeAll();

		JMenuItem discordItem = new JMenuItem(LanguageManager.getString("menu.help.discord"));
		discordItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				JOptionPane.showMessageDialog(mainWindow,
						LanguageManager.getString("dialog.discord.text").replace("\\n", "\n"),
						LanguageManager.getString("menu.help.discord"), JOptionPane.INFORMATION_MESSAGE);
			}
		});
		helpMenu.add(discordItem);

		JMenuItem aboutItem = new JMenuItem(LanguageManager.getString("menu.help.about"));
		aboutItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				JOptionPane.showMessageDialog(mainWindow, aboutText(),
						LanguageManager.getString("menu.help.about"), JOptionPane.INFORMATION_MESSAGE);
			}
		});
		helpMenu.add(aboutItem);
	}

	/**
	 * Version plus the running jar's own SHA-256.
	 *
	 * <p>Lets someone confirm the copy they are running is the published build:
	 * the release notes list the same hash, so a modified jar shows a different
	 * one. The version is read from the manifest the build stamps.
	 */
	private static String aboutText() {
		String version = MainMenuBar.class.getPackage().getImplementationVersion();
		if (version == null) version = "dev";

		String hash = LanguageManager.getString("dialog.about.hashUnknown");
		try {
			java.security.CodeSource cs =
					MainMenuBar.class.getProtectionDomain().getCodeSource();
			if (cs != null && cs.getLocation() != null) {
				java.io.File self = new java.io.File(cs.getLocation().toURI());
				if (self.isFile()) hash = sha256(self);
			}
		} catch (Exception ignored) {
			// Running from a classes directory, not a jar; hash stays "unknown".
		}

		return LanguageManager.getString("dialog.about.text").replace("\\n", "\n")
				+ "\n\nJar Analyzer " + version
				+ "\nSHA-256: " + hash;
	}

	private static String sha256(java.io.File f) {
		try (java.io.InputStream in = new java.io.FileInputStream(f)) {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
			StringBuilder sb = new StringBuilder(64);
			for (byte b : md.digest()) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (Exception e) {
			return "?";
		}
	}

	public void updateRecentFiles() {
		if (recentFilesItem == null) return;
		recentFilesItem.removeAll();
		ArrayList<String> paths = RecentFiles.paths;
		if (paths.isEmpty()) {
			JMenuItem empty = new JMenuItem("(-)");
			empty.setEnabled(false);
			recentFilesItem.add(empty);
			return;
		}
		for (String path : paths) {
			File f = new File(path);
			String name = f.getName();
			JMenuItem item = new JMenuItem(name);
			item.setToolTipText(path);
			item.addActionListener(new RecentFileActionListener(path));
			recentFilesItem.add(item);
		}
	}

	private class RecentFileActionListener implements ActionListener {
		private String path;

		RecentFileActionListener(String path) {
			this.path = path;
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			File file = new File(path);
			if (file.exists()) {
				mainWindow.getModel().loadFile(file);
			}
		}
	}
}
