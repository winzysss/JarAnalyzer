package com.jaranalyzer;

import java.awt.Component;
import java.io.File;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileFilter;

public class FileDialog {
    private final DirPreferences dirPreferences;
    private ConfigSaver configSaver;
    private Component parent;
	private JFileChooser fcOpen;
	private JFileChooser fcSave;
	private JFileChooser fcSaveAll;
	private JFileChooser fcSaveHtml;

	public FileDialog(Component parent) {
		this.parent = parent;
		configSaver = ConfigSaver.getLoadedInstance();
        AppPreferences appPrefs = configSaver.getAppPreferences();
        dirPreferences = new DirPreferences(appPrefs);

        new Thread() {
			public void run() {
				try {
					initOpenDialog();
					Thread.sleep(500);
					initSaveAllDialog();
					Thread.sleep(500);
					initSaveDialog();
				} catch (Exception e) {
					JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e);
				}
			};
		}.start();
	}

	public File doOpenDialog() {
		File selectedFile = null;
		initOpenDialog();

        dirPreferences.retrieveOpenDialogDir(fcOpen);
		int returnVal = fcOpen.showOpenDialog(parent);
        dirPreferences.saveOpenDialogDir(fcOpen);

		if (returnVal == JFileChooser.APPROVE_OPTION) {
			selectedFile = fcOpen.getSelectedFile();
		}
		return selectedFile;
	}

	public File doSaveDialog(String recommendedFileName) {
		File selectedFile = null;
		initSaveDialog();

        dirPreferences.retrieveSaveDialogDir(fcSave);
		fcSave.setSelectedFile(new File(recommendedFileName));
		int returnVal = fcSave.showSaveDialog(parent);
        dirPreferences.saveSaveDialogDir(fcSave);

		if (returnVal == JFileChooser.APPROVE_OPTION) {
			selectedFile = fcSave.getSelectedFile();
		}
		return selectedFile;
	}

	public File doSaveAllDialog(String recommendedFileName) {
		File selectedFile = null;
		initSaveAllDialog();

        dirPreferences.retrieveSaveDialogDir(fcSaveAll);
		fcSaveAll.setSelectedFile(new File(recommendedFileName));
		int returnVal = fcSaveAll.showSaveDialog(parent);
        dirPreferences.saveSaveDialogDir(fcSaveAll);

		if (returnVal == JFileChooser.APPROVE_OPTION) {
			selectedFile = fcSaveAll.getSelectedFile();
		}
		return selectedFile;
	}

	public File doSaveHtmlDialog(String recommendedFileName) {
		File selectedFile = null;
		initSaveHtmlDialog();

        dirPreferences.retrieveSaveDialogDir(fcSaveHtml);
		fcSaveHtml.setSelectedFile(new File(recommendedFileName));
		int returnVal = fcSaveHtml.showSaveDialog(parent);
        dirPreferences.saveSaveDialogDir(fcSaveHtml);

		if (returnVal == JFileChooser.APPROVE_OPTION) {
			selectedFile = fcSaveHtml.getSelectedFile();
		}
		return selectedFile;
	}

	public synchronized void initOpenDialog() {
		if (fcOpen == null) {
			fcOpen = createFileChooser("*.jar", "*.zip", "*.class", "*.txt");
            dirPreferences.retrieveOpenDialogDir(fcOpen);
		}
	}

	public synchronized void initSaveDialog() {
		if (fcSave == null) {
			fcSave = createFileChooser("*.txt", "*.java");
            dirPreferences.retrieveSaveDialogDir(fcSave);
		}
	}

	public synchronized void initSaveAllDialog() {
		if (fcSaveAll == null) {
			fcSaveAll = createFileChooser("*.jar", "*.zip");
            dirPreferences.retrieveSaveDialogDir(fcSaveAll);
		}
	}

	public synchronized void initSaveHtmlDialog() {
		if (fcSaveHtml == null) {
			fcSaveHtml = createFileChooser("*.html");
            dirPreferences.retrieveSaveDialogDir(fcSaveHtml);
		}
	}

	private JFileChooser createFileChooser(String... fileFilters) {
		JFileChooser fc = new JFileChooser();
		for (String fileFilter : fileFilters) {
			fc.addChoosableFileFilter(new FileChooserFileFilter(fileFilter));
		}
		fc.setAcceptAllFileFilterUsed(false);
		fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
		fc.setMultiSelectionEnabled(false);
		return fc;
	}

	public class FileChooserFileFilter extends FileFilter {
		String objType;

		public FileChooserFileFilter(String string) {
			objType = string;
		}

		@Override
		public boolean accept(File f) {
			if (f.isDirectory())
				return true;
			return f.getName().toLowerCase().endsWith(objType.substring(1));
		}

		@Override
		public String getDescription() {
			return objType;
		}
	}

}
