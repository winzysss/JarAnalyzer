package com.jaranalyzer;

import java.awt.EventQueue;
import java.awt.Toolkit;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class JarAnalyzer extends JFrame {

	private static final long serialVersionUID = 1L;
	public static final String TITLE = "Jar Analyzer";
	public static final AtomicReference<MainWindow> mainWindowRef = new AtomicReference<>();
	private static java.util.List<File> pendingFiles = new java.util.ArrayList<>();

	public static void main(String[] args) {
		if (args != null && args.length > 0
				&& ("--scan".equals(args[0]) || "--scan-all".equals(args[0]))) {
			System.exit(com.jaranalyzer.scan.ScanCli.run(args));
			return;
		}
		if (args != null && args.length > 0 && "--probe-tree".equals(args[0])) {
			System.exit(com.jaranalyzer.ui.TreeProbe.run(args));
			return;
		}
		if (args != null && args.length > 0 && "--shot".equals(args[0])) {
			System.exit(com.jaranalyzer.ui.UiShot.run(args));
			return;
		}

		try {
			UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
		} catch (Exception e) {
			e.printStackTrace();
		}

		final File fileFromCommandLine = getFileFromCommandLine(args);

		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				if (!mainWindowRef.compareAndSet(null, new MainWindow(fileFromCommandLine))) {
					openFileInInstance(fileFromCommandLine);
				}
				processPendingFiles();
				mainWindowRef.get().setVisible(true);
			}
		});
	}

	private static File getFileFromCommandLine(String[] args) {
		File file = null;
		if (args != null && args.length > 0) {
			file = new File(args[0]);
		}
		if (file != null && !file.exists()) {
			file = null;
		}
		return file;
	}

	public static void openFileInInstance(File file) {
		if (file != null && file.exists()) {
			pendingFiles.add(file);
		}
	}

	public static void processPendingFiles() {
		MainWindow mainWindow = mainWindowRef.get();
		if (mainWindow == null) return;
		for (File file : pendingFiles) {
			mainWindow.getModel().loadFile(file);
		}
		pendingFiles.clear();
	}

	public static void quitInstance() {
		MainWindow mainWindow = mainWindowRef.getAndSet(null);
		if (mainWindow != null) {
			mainWindow.dispose();
		}
		System.exit(0);
	}

	public static void showExceptionDialog(String message, Throwable e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		String trace = sw.toString();
		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				JOptionPane.showMessageDialog(null, trace, message, JOptionPane.ERROR_MESSAGE);
			}
		});
	}
}
