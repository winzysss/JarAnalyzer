package com.jaranalyzer;

import java.awt.Component;
import java.awt.Toolkit;
import java.util.HashSet;
import java.util.Set;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeNode;

public class CellRenderer extends DefaultTreeCellRenderer {
	private static final long serialVersionUID = -5691181006363313993L;

	Icon folderClosed;
	Icon folderOpen;
	Icon classIcon;
	Icon ymlIcon;
	Icon fileIcon;
	Icon deobfIcon;
	Icon suspiciousIcon;

	private static Set<String> suspiciousClassNames = new HashSet<>();
	private static Set<String> deobfuscatedClassNames = new HashSet<>();

	public CellRenderer() {
		this.folderClosed = new ImageIcon(
				Toolkit.getDefaultToolkit().getImage(this.getClass().getResource("/resources/folder_closed.png")));
		this.folderOpen = new ImageIcon(
				Toolkit.getDefaultToolkit().getImage(this.getClass().getResource("/resources/folder_open.png")));
		this.classIcon = new ImageIcon(
				Toolkit.getDefaultToolkit().getImage(this.getClass().getResource("/resources/class_file.png")));
		this.ymlIcon = new ImageIcon(
				Toolkit.getDefaultToolkit().getImage(this.getClass().getResource("/resources/yml.png")));
		this.fileIcon = new ImageIcon(
				Toolkit.getDefaultToolkit().getImage(this.getClass().getResource("/resources/file_generic.png")));
		this.deobfIcon = new ImageIcon(
				Toolkit.getDefaultToolkit().getImage(this.getClass().getResource("/resources/deobf_icon.png")));
		this.suspiciousIcon = new ImageIcon(
				Toolkit.getDefaultToolkit().getImage(this.getClass().getResource("/resources/suspicious_class.png")));
	}

	public static void setSuspiciousClassNames(Set<String> names) {
		suspiciousClassNames = (names != null) ? names : new HashSet<>();
	}

	public static void setDeobfuscatedClassNames(Set<String> names) {
		deobfuscatedClassNames = (names != null) ? names : new HashSet<>();
	}

	public static void clearSpecialMarkers() {
		suspiciousClassNames.clear();
		deobfuscatedClassNames.clear();
	}

	@Override
	public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf,
			int row, boolean hasFocus) {
		super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
		DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
		String fileName = getFileName(node);

		if (node.getChildCount() > 0) {
			setIcon(expanded ? this.folderOpen : this.folderClosed);
		} else if (fileName.endsWith(".class") || fileName.endsWith(".java")) {
			String fullName = getFullPath(node);
			if (suspiciousClassNames.contains(fullName)) {
				setIcon(this.suspiciousIcon);
			} else if (deobfuscatedClassNames.contains(fullName)) {
				setIcon(this.deobfIcon);
			} else {
				setIcon(this.classIcon);
			}
		} else if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
			setIcon(this.ymlIcon);
		} else if (fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")
				|| fileName.endsWith(".gif") || fileName.endsWith(".bmp") || fileName.endsWith(".webp")
				|| fileName.endsWith(".ico") || fileName.endsWith(".tga") || fileName.endsWith(".svg")) {
			setIcon(this.fileIcon);
		} else if (fileName.endsWith(".exe") || fileName.endsWith(".dll") || fileName.endsWith(".so")
				|| fileName.endsWith(".dylib") || fileName.endsWith(".bin") || fileName.endsWith(".dat")) {
			setIcon(this.fileIcon);
		} else if (fileName.endsWith(".json") || fileName.endsWith(".xml") || fileName.endsWith(".properties")
				|| fileName.endsWith(".txt") || fileName.endsWith(".cfg") || fileName.endsWith(".conf")
				|| fileName.endsWith(".ini") || fileName.endsWith(".lang") || fileName.endsWith(".toml")
				|| fileName.endsWith(".md") || fileName.endsWith(".sql") || fileName.endsWith(".mf")
				|| fileName.endsWith(".css") || fileName.endsWith(".js") || fileName.endsWith(".html")
				|| fileName.endsWith(".htm")) {
			setIcon(this.fileIcon);
		} else {
			setIcon(this.fileIcon);
		}

		return this;
	}

	public String getFileName(DefaultMutableTreeNode node) {
		return ((TreeNodeUserObject) node.getUserObject()).getOriginalName();
	}

	private String getFullPath(DefaultMutableTreeNode node) {
		TreeNode[] path = node.getPath();
		StringBuilder sb = new StringBuilder();
		for (int i = 1; i < path.length; i++) {
			if (sb.length() > 0) sb.append("/");
			sb.append(((TreeNodeUserObject) ((DefaultMutableTreeNode) path[i]).getUserObject()).getOriginalName());
		}
		return sb.toString();
	}
}
