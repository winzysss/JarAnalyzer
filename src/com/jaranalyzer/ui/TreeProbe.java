package com.jaranalyzer.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import com.jaranalyzer.MainWindow;

/**
 * Opens every leaf of a loaded JAR's tree and reports which ones produced no tab.
 *
 * <p>Development aid, in the same spirit as {@link UiShot}. "The Decompile tab
 * cannot show all files" is not a claim that can be checked by reading the code
 * — the open path has three fallbacks, a size cap and a binary/text branch, and
 * which one a given entry takes depends on its bytes. This clicks every entry
 * the way a person would and prints what came back.
 *
 * <pre>--probe-tree &lt;jar&gt;</pre>
 */
public final class TreeProbe {

	private TreeProbe() {
	}

	public static int run(String[] args) {
		if (args.length < 2) {
			System.out.println("Usage: --probe-tree <jar>");
			return 1;
		}
		File jar = new File(args[1]);
		if (!jar.isFile()) {
			System.out.println("not a file: " + jar);
			return 1;
		}

		try {
			final MainWindow[] holder = new MainWindow[1];
			SwingUtilities.invokeAndWait(() -> {
				MainWindow win = new MainWindow(null);
				win.setSize(1400, 900);
				win.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
				win.setVisible(true);
				win.getMainTabbedPane().setSelectedIndex(2);
				holder[0] = win;
			});
			Thread.sleep(1200);

			SwingUtilities.invokeLater(() -> holder[0].getModel().loadFile(jar));
			Thread.sleep(6000);

			final List<TreePath> leaves = new ArrayList<>();
			final JTree[] treeHolder = new JTree[1];
			SwingUtilities.invokeAndWait(() -> {
				JTree tree = holder[0].getModel().getTree();
				treeHolder[0] = tree;
				Object rootObj = tree.getModel().getRoot();
				if (rootObj instanceof DefaultMutableTreeNode) {
					collectLeaves((DefaultMutableTreeNode) rootObj, leaves);
				}
			});

			System.out.println("leaves: " + leaves.size());
			if (leaves.isEmpty()) {
				System.out.println("FAIL: tree is empty — the file was not loaded at all");
				return 2;
			}

			int ok = 0;
			int failed = 0;
			for (TreePath p : leaves) {
				String label = describe(p);
				int before = tabCount(holder[0]);
				SwingUtilities.invokeAndWait(
						() -> holder[0].getModel().openEntryByTreePath(p));
				// The open path hands off to a worker thread for decompilation.
				Thread.sleep(1500);
				int after = tabCount(holder[0]);
				if (after > before) {
					ok++;
					System.out.println("  OK    " + label);
				} else {
					failed++;
					System.out.println("  FAIL  " + label
							+ "   (durum: " + holder[0].getModel().statusText() + ")");
				}
			}

			System.out.println();
			System.out.println("acilan: " + ok + "   acilamayan: " + failed);
			return failed == 0 ? 0 : 3;
		} catch (Throwable t) {
			t.printStackTrace();
			return 1;
		}
	}

	private static int tabCount(MainWindow win) {
		final int[] n = new int[1];
		try {
			SwingUtilities.invokeAndWait(() -> n[0] = win.getModel().openTabCount());
		} catch (Exception e) {
			return -1;
		}
		return n[0];
	}

	private static void collectLeaves(DefaultMutableTreeNode node, List<TreePath> out) {
		if (node.getChildCount() == 0) {
			if (node.getParent() != null) out.add(new TreePath(node.getPath()));
			return;
		}
		for (int i = 0; i < node.getChildCount(); i++) {
			collectLeaves((DefaultMutableTreeNode) node.getChildAt(i), out);
		}
	}

	private static String describe(TreePath p) {
		StringBuilder sb = new StringBuilder();
		for (int i = 1; i < p.getPathCount(); i++) {
			if (i > 1) sb.append('/');
			sb.append(p.getPathComponent(i));
		}
		return sb.toString();
	}
}
