package com.jaranalyzer.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import com.jaranalyzer.MainWindow;

/**
 * Renders the window to a PNG without a human at the keyboard.
 *
 * <p>Development aid: a Swing layout that compiles is not the same as a Swing
 * layout that looks right, and this is the only way to check the difference
 * while working non-interactively.
 *
 * <pre>--shot &lt;out.png&gt; [tabIndex] [width] [height]</pre>
 */
public final class UiShot {

	private UiShot() {
	}

	public static int run(String[] args) {
		String out = args.length > 1 ? args[1] : "ui.png";
		final int tab = args.length > 2 ? Integer.parseInt(args[2]) : 0;
		final int w = args.length > 3 ? Integer.parseInt(args[3]) : 1600;
		final int h = args.length > 4 ? Integer.parseInt(args[4]) : 950;
		final File scanTarget = args.length > 5 ? new File(args[5]) : null;

		try {
			final MainWindow[] holder = new MainWindow[1];

			SwingUtilities.invokeAndWait(() -> {
				MainWindow win = new MainWindow(null);
				win.setSize(w, h);
				win.setLocation(0, 0);
				win.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
				win.setVisible(true);
				if (tab > 0 && tab < win.getMainTabbedPane().getTabCount()) {
					win.getMainTabbedPane().setSelectedIndex(tab);
				}
				holder[0] = win;
			});

			// Let layout, first paint and any lazy icon loading settle before the
			// snapshot; otherwise half the panels come out blank.
			Thread.sleep(1500);

			// ":memory:" instead of a path runs the running-JVM scan. It has no
			// folder to point at, so without this token it is the one scan mode
			// that could never be checked without a human clicking the button.
			boolean memoryMode = args.length > 5 && ":memory:".equals(args[5]);

			if (memoryMode || (scanTarget != null && scanTarget.exists())) {
				SwingUtilities.invokeLater(() -> {
					if (memoryMode) holder[0].getScanPanel().startMemoryScan();
					else holder[0].getScanPanel().scanTarget(scanTarget);
				});
				long deadline = System.currentTimeMillis() + 180_000;
				Thread.sleep(1200);
				while (holder[0].getScanPanel().isScanning()
						&& System.currentTimeMillis() < deadline) {
					Thread.sleep(300);
				}
				// Give the queued EDT updates from the last few results time to run.
				Thread.sleep(900);
				// ":decompile:" does what double-clicking a result row does, so the
				// hand-off from the scan table to the Decompile tab can be checked
				// the same way everything else here is.
				final boolean openInDecompiler = args.length > 6 && ":decompile:".equals(args[6]);
				final int detailTab = !openInDecompiler && args.length > 6
						? Integer.parseInt(args[6]) : -1;
				SwingUtilities.invokeAndWait(() -> {
					holder[0].getScanPanel().selectFirstRow();
					if (detailTab >= 0) holder[0].getScanPanel().selectDetailTab(detailTab);
					if (openInDecompiler) holder[0].getScanPanel().openSelectedInDecompiler();
				});
				// Decompiling a whole jar is slower than a repaint.
				Thread.sleep(openInDecompiler ? 6000 : 700);

				// Open a class and its find bar, so the search UI is in the shot.
				if (openInDecompiler) {
					SwingUtilities.invokeAndWait(() -> {
						javax.swing.JTree tree = holder[0].getModel().getTree();
						for (int row = 0; row < tree.getRowCount(); row++) {
							tree.expandRow(row);
						}
						for (int row = tree.getRowCount() - 1; row >= 0; row--) {
							javax.swing.tree.TreePath p = tree.getPathForRow(row);
							if (p != null && String.valueOf(p.getLastPathComponent()).endsWith(".class")) {
								tree.setSelectionPath(p);
								holder[0].getModel().openEntryByTreePath(p);
								break;
							}
						}
					});
					Thread.sleep(5000);
					SwingUtilities.invokeAndWait(() -> {
						com.jaranalyzer.OpenFile of = holder[0].getModel().getCurrentOpenFile();
						if (of != null && of.searchBar != null) of.searchBar.reveal();
					});
					Thread.sleep(1200);
				}
			}

			final BufferedImage[] img = new BufferedImage[1];
			SwingUtilities.invokeAndWait(() -> {
				MainWindow win = holder[0];
				win.validate();
				BufferedImage bi = new BufferedImage(win.getWidth(), win.getHeight(),
						BufferedImage.TYPE_INT_RGB);
				Graphics2D g = bi.createGraphics();
				win.printAll(g);
				g.dispose();
				img[0] = bi;
			});

			File f = new File(out);
			if (f.getParentFile() != null) f.getParentFile().mkdirs();
			ImageIO.write(img[0], "png", f);
			System.out.println("wrote " + f.getAbsolutePath()
					+ "  (" + img[0].getWidth() + "x" + img[0].getHeight() + ")");
			return 0;
		} catch (Throwable t) {
			t.printStackTrace();
			return 1;
		}
	}
}
