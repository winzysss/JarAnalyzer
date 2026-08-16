package com.jaranalyzer;

import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextPane;
import javax.swing.JTree;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

public class Model extends JSplitPane {
	private static final long serialVersionUID = 6896857630400910200L;

	private static final long MAX_JAR_FILE_SIZE_BYTES = 10_000_000_000L;
	private static final long MAX_UNPACKED_FILE_SIZE_BYTES = 10_000_000L;

	private static final List<File> tempFiles = new CopyOnWriteArrayList<>();

	static {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			for (File f : tempFiles) {
				try { f.delete(); } catch (Exception ignored) {}
			}
		}));
	}

	private JTree tree;
	public JTabbedPane house;

	/**
	 * Blacklist terms the scan matched in the JAR currently being opened.
	 *
	 * <p>Set by the scan tab just before it hands a JAR over, and applied to every
	 * class opened from it, so the reason the JAR was flagged is visible in the
	 * code instead of being something the reader has to go looking for. Cleared
	 * when a file is opened any other way — stale marks from a previous JAR would
	 * be worse than none.
	 */
	private java.util.Collection<String> scanHits = java.util.Collections.emptyList();

	public void setScanHits(java.util.Collection<String> terms) {
		this.scanHits = terms == null ? java.util.Collections.emptyList()
				: new java.util.ArrayList<>(terms);
	}

	/** The entry tree. Exposed for {@link com.jaranalyzer.ui.TreeProbe}. */
	public JTree getTree() {
		return tree;
	}

	/** How many entries are currently open as tabs. */
	public int openTabCount() {
		return house == null ? 0 : house.getTabCount();
	}

	/** Current status-bar text, so a probe can report why an entry did not open. */
	public String statusText() {
		return label == null ? "" : label.getText();
	}

	private File file;
	private DecompilerConfig decompilerConfig;
	private ThemeManager.ThemeColors themeColors;
	private MainWindow mainWindow;
	private JProgressBar bar;
	private JLabel label;
	private HashSet<OpenFile> hmap = new HashSet<OpenFile>();
	private Set<String> treeExpansionState;
	private boolean open = false;
	private JPanel panel2;
	private JPanel panel;
	private State state;
	private JarFile currentJarFile;

	private JLabel obfSummaryLabel;
	private JButton reprocessButton;
	private ConfigSaver configSaver;
	private AppPreferences appPrefs;

	public Model(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
		this.bar = mainWindow.getBar();
		this.setLabel(mainWindow.getLabel());

		configSaver = ConfigSaver.getLoadedInstance();
		decompilerConfig = configSaver.getDecompilerConfig();
		appPrefs = configSaver.getAppPreferences();

		try {
			setThemeColors(ThemeManager.getTheme("light"));
		} catch (Exception e1) {
			JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e1);
		}

		tree = new JTree();
		tree.setModel(new DefaultTreeModel(null));
		tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
		tree.setCellRenderer(new CellRenderer());
		TreeListener tl = new TreeListener();
		tree.addMouseListener(tl);
		tree.addTreeExpansionListener(new FurtherExpandingTreeExpansionListener());
		tree.addKeyListener(new KeyAdapter() {

			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ENTER) {
					openEntryByTreePath(tree.getSelectionPath());
				}
			}
		});

		panel2 = new JPanel();
		panel2.setLayout(new BorderLayout(0, 2));
		panel2.setBorder(BorderFactory.createTitledBorder(LanguageManager.getString("panel.structure")));

		// Search bar for tree
		javax.swing.JTextField treeSearchField = new javax.swing.JTextField();
		treeSearchField.setToolTipText(LanguageManager.getString("panel.searchTree"));
		treeSearchField.putClientProperty("JTextField.placeholderText", LanguageManager.getString("panel.searchTree"));
		treeSearchField.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent e) {
				String query = treeSearchField.getText().trim().toLowerCase();
				filterTree(query);
			}
		});

		JPanel searchPanel = new JPanel(new BorderLayout());
		searchPanel.add(treeSearchField, BorderLayout.CENTER);
		searchPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

		panel2.add(searchPanel, BorderLayout.NORTH);
		panel2.add(new JScrollPane(tree), BorderLayout.CENTER);

		house = new JTabbedPane();
		house.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
		house.addChangeListener(new TabChangeListener());
		house.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (SwingUtilities.isMiddleMouseButton(e)) {
					closeOpenTab(house.getSelectedIndex());
				}
			}
		});

		KeyStroke sfuncF4 = KeyStroke.getKeyStroke(KeyEvent.VK_F4, Keymap.ctrlDownModifier(), false);
		mainWindow.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(sfuncF4, "CloseTab");

		mainWindow.getRootPane().getActionMap().put("CloseTab", new AbstractAction() {
			private static final long serialVersionUID = -885398399200419492L;

			@Override
			public void actionPerformed(ActionEvent e) {
				closeOpenTab(house.getSelectedIndex());
			}

		});

		// Obf summary label
		obfSummaryLabel = new JLabel(" ");
		obfSummaryLabel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));

		// Top button bar
		JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
		reprocessButton = new JButton(LanguageManager.getString("button.reprocess"));
		reprocessButton.setToolTipText(LanguageManager.getString("tooltip.reprocess"));
		reprocessButton.addActionListener(e -> {
			if (state == null || state.jarFile == null) {
				javax.swing.JOptionPane.showMessageDialog(Model.this, LanguageManager.getString("dialog.loadJarFirst"));
				return;
			}
			autoProcessSkip = false;
			runAutoProcess();
		});
		buttonBar.add(reprocessButton);

		// North panel: buttons + obf summary
		JPanel northPanel = new JPanel(new BorderLayout());
		northPanel.add(buttonBar, BorderLayout.WEST);
		northPanel.add(obfSummaryLabel, BorderLayout.CENTER);

		panel = new JPanel(new BorderLayout());
		panel.setBorder(BorderFactory.createTitledBorder(LanguageManager.getString("panel.code")));
		panel.add(northPanel, BorderLayout.NORTH);
		panel.add(house, BorderLayout.CENTER);

		this.setOrientation(JSplitPane.HORIZONTAL_SPLIT);
		int winWidth = mainWindow.getWidth();
		this.setDividerLocation(winWidth > 0 ? 250 % winWidth : 250);
		this.setLeftComponent(panel2);
		this.setRightComponent(panel);

	}

	private void filterTree(String query) {
		DefaultTreeModel model = (DefaultTreeModel) tree.getModel();

		if (query.isEmpty()) {
			if (fullTreeRoot != null) {
				model.setRoot(fullTreeRoot);
			}
			return;
		}

		DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
		if (root == null) {
			if (fullTreeRoot == null) return;
			root = fullTreeRoot;
		}

		if (fullTreeRoot == null) {
			fullTreeRoot = root;
		}

		DefaultMutableTreeNode filteredRoot = filterTreeNodes(fullTreeRoot, query);
		if (filteredRoot == null) {
			filteredRoot = new DefaultMutableTreeNode(fullTreeRoot.getUserObject());
		}
		model.setRoot(filteredRoot);
	}

	private DefaultMutableTreeNode fullTreeRoot = null;

	private DefaultMutableTreeNode filterTreeNodes(DefaultMutableTreeNode node, String query) {
		Object userObj = node.getUserObject();
		String nodeText = userObj != null ? userObj.toString().toLowerCase() : "";

		DefaultMutableTreeNode result = new DefaultMutableTreeNode(userObj);
		boolean childMatch = false;

		for (int i = 0; i < node.getChildCount(); i++) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
			DefaultMutableTreeNode filteredChild = filterTreeNodes(child, query);
			if (filteredChild != null) {
				result.add(filteredChild);
				childMatch = true;
			}
		}

		if (childMatch || nodeText.contains(query)) {
			return result;
		}
		return null;
	}

	public void show(String name, String contents) {
		OpenFile open = new OpenFile(name, "*/" + name, themeColors, mainWindow);
		open.setContent(contents);
		hmap.add(open);
		addOrSwitchToTab(open);
	}

	private String extractRawStrings(byte[] classBytes, String entryName) {
		StringBuilder sb = new StringBuilder();
		sb.append("// ").append(entryName).append("\n");

		if (classBytes == null || classBytes.length == 0) {
			sb.append("// Dosya bos veya okunamadi\n");
			return sb.toString();
		}

		// Detect file type
		boolean isPE = classBytes.length >= 2 && classBytes[0] == 'M' && classBytes[1] == 'Z';
		boolean isMachO = classBytes.length >= 4
				&& ((classBytes[0] == (byte)0xFE && classBytes[1] == (byte)0xED && classBytes[2] == (byte)0xFA && classBytes[3] == (byte)0xCE)
				|| (classBytes[0] == (byte)0xCF && classBytes[1] == (byte)0xFA && classBytes[2] == (byte)0xED && classBytes[3] == (byte)0xFE));
		boolean isELF = classBytes.length >= 4
				&& classBytes[0] == 0x7F && classBytes[1] == 'E' && classBytes[2] == 'L' && classBytes[3] == 'F';
		boolean isJavaClass = classBytes.length >= 4
				&& classBytes[0] == (byte)0xCA && classBytes[1] == (byte)0xFE
				&& classBytes[2] == (byte)0xBA && classBytes[3] == (byte)0xBE;

		if (isPE) {
			sb.append("// Bu bir Windows DLL/EXE dosyasi (PE format) - Java bytecode degil\n");
			sb.append("// Native makine kodu icerir, Java decompiler ile decompile edilemez\n");
			sb.append("// Icindeki anlamlı string'ler asagida gosteriliyor:\n\n");
		} else if (isMachO) {
			sb.append("// Bu bir macOS native library (Mach-O format) - Java bytecode degil\n");
			sb.append("// Native makine kodu icerir, Java decompiler ile decompile edilemez\n");
			sb.append("// Icindeki anlamlı string'ler asagida gosteriliyor:\n\n");
		} else if (isELF) {
			sb.append("// Bu bir Linux native library (ELF format) - Java bytecode degil\n");
			sb.append("// Native makine kodu icerir, Java decompiler ile decompile edilemez\n");
			sb.append("// Icindeki anlamlı string'ler asagida gosteriliyor:\n\n");
		} else if (!isJavaClass) {
			sb.append("// Bu dosya Java class formatinda degil\n");
			sb.append("// Icindeki anlamlı string'ler asagida gosteriliyor:\n\n");
		} else {
			sb.append("// CFR ile decompile edilemedi (bozuk veya sifreli class)\n");
			sb.append("// Icindeki string'ler asagida gosteriliyor:\n\n");
		}

		String asLatin1 = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
		StringBuilder current = new StringBuilder();
		int count = 0;
		for (int i = 0; i < asLatin1.length(); i++) {
			char c = asLatin1.charAt(i);
			if (c >= 32 && c <= 126) {
				current.append(c);
			} else {
				String s = current.toString();
				if (s.length() >= 4 && isMeaningfulString(s)) {
					sb.append(s).append("\n");
					count++;
				}
				current.setLength(0);
			}
		}
		String s = current.toString();
		if (s.length() >= 4 && isMeaningfulString(s)) {
			sb.append(s).append("\n");
			count++;
		}

		if (count == 0) {
			sb.append("// Anlamlı string bulunamadi\n");
		}
		return sb.toString();
	}

	private boolean isMeaningfulString(String s) {
		return s.length() >= 4;
	}

	private void addOrSwitchToTab(final OpenFile open) {
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				try {
					final String title = open.name;
					java.awt.Component scrollPane = open.component;
					if (house.indexOfTab(title) < 0) {
						house.addTab(title, scrollPane);
						house.setSelectedIndex(house.indexOfTab(title));
						int index = house.indexOfTab(title);
						Tab ct = new Tab(title);
						ct.getButton().addMouseListener(new CloseTab(title));
						house.setTabComponentAt(index, ct);
					} else {
						house.setSelectedIndex(house.indexOfTab(title));
					}
					open.onAddedToScreen();
					// Applied here rather than at each of the dozen call sites that
					// open a tab, so no path can forget it.
					if (!scanHits.isEmpty() && open.searchBar != null) {
						open.searchBar.markScanHits(scanHits);
					}
				} catch (Exception e) {
					JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e);
				}
			}
		});
	}

	public void closeOpenTab(int index) {
		// Matched on the tab's own component rather than by digging a JTextPane out
		// of a JScrollPane: the tab now holds a panel (search bar over the code), so
		// the old cast would have thrown the moment a tab was closed.
		java.awt.Component co = house.getComponentAt(index);
		OpenFile open = null;
		for (OpenFile file : hmap) {
			if (co == file.component) {
				open = file;
				break;
			}
		}
		if (open != null)
			hmap.remove(open);
		house.remove(co);
		if (open != null)
			open.close();
	}

	private String getName(String path) {
		if (path == null)
			return "";
		int i = path.lastIndexOf("/");
		if (i == -1)
			i = path.lastIndexOf("\\");
		if (i != -1)
			return path.substring(i + 1);
		return path;
	}

	private class TreeListener extends MouseAdapter {
		private void handleTreeClick(MouseEvent event) {
			boolean isClickCountMatches = (event.getClickCount() == 1 && appPrefs.isSingleClickOpenEnabled())
					|| (event.getClickCount() == 2 && !appPrefs.isSingleClickOpenEnabled());
			if (!isClickCountMatches)
				return;

			if (!SwingUtilities.isLeftMouseButton(event))
				return;

			final TreePath trp = tree.getPathForLocation(event.getX(), event.getY());
			if (trp == null) {
				System.out.println("[CLICK] No path found at location");
				return;
			}

			Object lastPathComponent = trp.getLastPathComponent();
			boolean isLeaf = (lastPathComponent instanceof TreeNode && ((TreeNode) lastPathComponent).isLeaf());
			System.out.println("[CLICK] path=" + trp + " isLeaf=" + isLeaf);

			if (!isLeaf) {
				if (tree.isExpanded(trp)) {
					tree.collapsePath(trp);
				} else {
					tree.expandPath(trp);
				}
				return;
			}

			new Thread() {
				public void run() {
					openEntryByTreePath(trp);
				}
			}.start();
		}

		private void handleRightClick(MouseEvent event) {
			if (!SwingUtilities.isRightMouseButton(event))
				return;

			final TreePath trp = tree.getPathForLocation(event.getX(), event.getY());
			if (trp == null) return;

			tree.setSelectionPath(trp);

			Object lastPathComponent = trp.getLastPathComponent();
			boolean isLeaf = (lastPathComponent instanceof TreeNode && ((TreeNode) lastPathComponent).isLeaf());

			JPopupMenu popup = new JPopupMenu();

			if (isLeaf) {
				JMenuItem extractItem = new JMenuItem("Extract to Desktop");
				extractItem.addActionListener(e -> {
					new Thread(() -> {
						String entryPath = getSelectedClassPath(trp);
						if (entryPath != null) {
							extractEntryToDesktop(entryPath);
						}
					}).start();
				});
				popup.add(extractItem);
			}

			JMenuItem extractAllItem = new JMenuItem("Extract All to Desktop");
			extractAllItem.addActionListener(e -> {
				new Thread(() -> {
					extractAllToDesktop();
				}).start();
			});
			popup.add(extractAllItem);

			popup.show(tree, event.getX(), event.getY());
		}

		@Override
		public void mousePressed(MouseEvent event) {
			if (SwingUtilities.isRightMouseButton(event)) {
				handleRightClick(event);
				return;
			}
			handleTreeClick(event);
		}

		@Override
		public void mouseReleased(MouseEvent event) {
			if (SwingUtilities.isRightMouseButton(event)) {
				handleRightClick(event);
			}
		}

		@Override
		public void mouseClicked(MouseEvent event) {
			if (SwingUtilities.isRightMouseButton(event))
				return;
			if (event.getClickCount() == 1 && !appPrefs.isSingleClickOpenEnabled()) {
				return;
			}
			if (event.getClickCount() == 2 && appPrefs.isSingleClickOpenEnabled()) {
				return;
			}
			handleTreeClick(event);
		}
	}

	private class FurtherExpandingTreeExpansionListener implements TreeExpansionListener {
		private static final int MAX_AUTO_EXPAND_DEPTH = 10;

		@Override
		public void treeExpanded(final TreeExpansionEvent event) {
			final TreePath treePath = event.getPath();

			final Object expandedTreePathObject = treePath.getLastPathComponent();
			if (!(expandedTreePathObject instanceof TreeNode)) {
				return;
			}

			if (treePath.getPathCount() > MAX_AUTO_EXPAND_DEPTH) {
				return;
			}

			final TreeNode expandedTreeNode = (TreeNode) expandedTreePathObject;
			if (expandedTreeNode.getChildCount() == 1) {
				final TreeNode descendantTreeNode = expandedTreeNode.getChildAt(0);

				if (descendantTreeNode.isLeaf()) {
					return;
				}

				final TreePath nextTreePath = treePath.pathByAddingChild(descendantTreeNode);
				tree.expandPath(nextTreePath);
			}
		}

		@Override
		public void treeCollapsed(final TreeExpansionEvent event) {

		}
	}

	public void openEntryByTreePath(TreePath trp) {
		String name = "";
		String path = "";
		try {
			bar.setVisible(true);
			if (trp.getPathCount() > 1) {
				for (int i = 1; i < trp.getPathCount(); i++) {
					DefaultMutableTreeNode node = (DefaultMutableTreeNode) trp.getPathComponent(i);
					TreeNodeUserObject userObject = (TreeNodeUserObject) node.getUserObject();
					if (i == trp.getPathCount() - 1) {
						name = userObject.getOriginalName();
					} else {
						path = path + userObject.getOriginalName() + "/";
					}
				}
				path = path + name;

				System.out.println("[OPEN] name=" + name + " path=" + path);

				if (isArchive(file)) {
					if (state == null) {
						JarFile jfile = new JarFile(file);
						state = new State(file.getCanonicalPath(), file, jfile);
						currentJarFile = jfile;
					}

					JarEntry entry = state.jarFile.getJarEntry(path);
					if (entry == null) {
						String altPath = path.replace(".", "/");
						System.out.println("[OPEN] Entry not found, trying alt path: " + altPath);
						entry = state.jarFile.getJarEntry(altPath);
					}
					if (entry == null) {
						Enumeration<JarEntry> entries = state.jarFile.entries();
						JarEntry suffixMatch = null;
						while (entries.hasMoreElements()) {
							JarEntry e = entries.nextElement();
							String eName = e.getName();
							if (eName.equals(path)) {
								entry = e;
								System.out.println("[OPEN] Found by exact match: " + eName);
								break;
							}
							if (eName.endsWith("/" + path) && suffixMatch == null) {
								suffixMatch = e;
								System.out.println("[OPEN] Found by path-boundary suffix match: " + eName);
							}
						}
						if (entry == null && suffixMatch != null) {
							entry = suffixMatch;
						}
						if (entry == null) {
							entries = state.jarFile.entries();
							while (entries.hasMoreElements()) {
								JarEntry e2 = entries.nextElement();
								if (e2.getName().endsWith(path)) {
									entry = e2;
									System.out.println("[OPEN] Found by bare suffix match (last resort): " + e2.getName());
									break;
								}
							}
						}
					}
					if (entry == null) {
						System.err.println("[ERROR] Entry not found: " + path);
						throw new FileEntryNotFoundException();
					}
					// A file bigger than the cap is shown truncated, with a note, so
					// clicking it does something useful — seeing the first megabyte
					// of a suspicious blob beats seeing nothing.
					final boolean truncated = entry.getSize() > MAX_UNPACKED_FILE_SIZE_BYTES;
					String entryName = entry.getName();
					System.out.println("[OPEN] entryName=" + entryName + " size=" + entry.getSize());

					byte[] entryBytes = null;
					boolean isClassFile = entryName.endsWith(".class");

					if (!isClassFile) {
						try (InputStream in = state.jarFile.getInputStream(entry)) {
							java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
							byte[] buf = new byte[4096];
							int len;
							while ((len = in.read(buf)) > 0) {
								baos.write(buf, 0, len);
								if (baos.size() >= MAX_UNPACKED_FILE_SIZE_BYTES) break;
							}
							entryBytes = baos.toByteArray();
							System.out.println("[OPEN] Read " + entryBytes.length + " bytes, checking magic...");
							if (entryBytes.length >= 4 && entryBytes[0] == (byte) 0xCA && entryBytes[1] == (byte) 0xFE
									&& entryBytes[2] == (byte) 0xBA && entryBytes[3] == (byte) 0xBE) {
								isClassFile = true;
								System.out.println("[OPEN] Magic byte CAFEBABE detected — is class file");
							}
						}
					}

					if (isClassFile) {
						getLabel().setText(LanguageManager.getString("status.loading") + " " + name);

						if (entryBytes == null) {
							try (InputStream in = state.jarFile.getInputStream(entry)) {
								java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
								byte[] buf = new byte[4096];
								int len;
								while ((len = in.read(buf)) > 0) {
									baos.write(buf, 0, len);
								}
								entryBytes = baos.toByteArray();
							}
						}

						String internalName = entryName;
						if (internalName.endsWith(".class")) {
							internalName = internalName.substring(0, internalName.length() - ".class".length());
						}

						System.out.println("[OPEN] Decompiling: " + internalName);
						boolean decompiled = false;
						try {
							String source = CfrDecompiler.decompileFromJar(state.jarFile, internalName, decompilerConfig);
							if (source != null && !source.trim().isEmpty()) {
								OpenFile open = new OpenFile(name, path, themeColors, mainWindow);
								open.setDecompilationInfo(state.jarFile, internalName, decompilerConfig);
								open.setContent(source);
								hmap.add(open);
								addOrSwitchToTab(open);
								decompiled = true;
								System.out.println("[OPEN] Decompile SUCCESS");
							}
						} catch (Throwable t) {
							System.err.println("[DECOMPILE FAIL] " + name + ": " + t);
						}

						if (!decompiled) {
							getLabel().setText(LanguageManager.getString("status.deobfuscatingFile") + " " + name);
							try {
								byte[] deobfBytes = deobfuscateClassBytes(entryBytes);
								if (deobfBytes != null) {
									System.out.println("[OPEN] Deobf success, size=" + deobfBytes.length);
									String deobfName = name + " (deobf)";
									String source = CfrDecompiler.decompileFromBytes(deobfBytes, internalName, decompilerConfig);
									if (source == null || source.trim().isEmpty()) {
										source = LanguageManager.getString("decompile.failedForDeobf") + " " + internalName;
									}
									OpenFile open = new OpenFile(deobfName, path + " (deobf)", themeColors, mainWindow);
									open.setContent(source);
									hmap.add(open);
									addOrSwitchToTab(open);
									decompiled = true;
									getLabel().setText(LanguageManager.getString("status.deobfuscatedFile") + " " + name);
								} else {
									System.err.println("[DEOBF FAIL] deobfuscateClassBytes returned null for " + name);
								}
							} catch (Exception e2) {
								System.err.println("[DEOBF FAIL] " + name + ": " + e2);
							}
						}

						if (!decompiled) {
							getLabel().setText(LanguageManager.getString("status.decompileFailed") + " " + entryName);
							String rawStrings = extractRawStrings(entryBytes, entryName);
							OpenFile open = new OpenFile(name, path, themeColors, mainWindow);
							open.setContent(rawStrings);
							hmap.add(open);
							addOrSwitchToTab(open);
						}
					} else {
						getLabel().setText(LanguageManager.getString("status.opening") + " " + name);
						String truncNote = truncated
								? LanguageManager.getString("open.truncated")
										.replace("{0}", formatBytes(entry.getSize()))
										.replace("{1}", formatBytes(MAX_UNPACKED_FILE_SIZE_BYTES))
								: null;
						if (entryBytes != null) {
							java.io.ByteArrayInputStream bain = new java.io.ByteArrayInputStream(entryBytes);
							extractSimpleFileEntryToTextPane(bain, name, path, truncNote);
						} else {
							try (InputStream in = state.jarFile.getInputStream(entry);) {
								extractSimpleFileEntryToTextPane(in, name, path, truncNote);
							}
						}
					}
				}
			} else {
				name = file.getName();
				path = file.getPath().replaceAll("\\\\", "/");
				if (file.length() > MAX_UNPACKED_FILE_SIZE_BYTES) {
					throw new TooLargeFileException(file.length());
				}
				if (name.endsWith(".class")) {
					getLabel().setText(LanguageManager.getString("status.loading") + " " + name);
					boolean decompiled = false;
					try {
						byte[] classBytes;
						try (InputStream in = new FileInputStream(file)) {
							java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
							byte[] buf = new byte[4096];
							int len;
							while ((len = in.read(buf)) > 0) baos.write(buf, 0, len);
							classBytes = baos.toByteArray();
						}
						String internalName = name.endsWith(".class") ? name.substring(0, name.length() - ".class".length()) : name;
						String source = CfrDecompiler.decompileFromBytes(classBytes, internalName, decompilerConfig);
						if (source != null && !source.trim().isEmpty()) {
							OpenFile open = new OpenFile(name, path, themeColors, mainWindow);
							open.setContent(source);
							hmap.add(open);
							addOrSwitchToTab(open);
							decompiled = true;
						}
					} catch (Throwable t) {
						decompiled = false;
					}
					if (!decompiled) {
						getLabel().setText(LanguageManager.getString("status.decompileFailed") + " " + name);
						OpenFile open = new OpenFile(name, path, themeColors, mainWindow);
						try {
							byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());
							open.setContent(extractRawStrings(fileBytes, name));
						} catch (Exception ex) {
							open.setContent("// " + name + "\n// Hata: " + ex.getMessage());
						}
						hmap.add(open);
						addOrSwitchToTab(open);
					}
				} else {
					getLabel().setText(LanguageManager.getString("status.opening") + " " + name);
					try (InputStream in = new FileInputStream(file);) {
						extractSimpleFileEntryToTextPane(in, name, path);
					}
				}
			}

			getLabel().setText(LanguageManager.getString("status.complete"));
		} catch (FileEntryNotFoundException e) {
			getLabel().setText(LanguageManager.getString("status.fileNotFound") + " " + name);
		} catch (FileIsBinaryException e) {
			getLabel().setText(LanguageManager.getString("status.binaryResource") + " " + name);
		} catch (TooLargeFileException e) {
			getLabel().setText(LanguageManager.getString("status.fileTooLarge") + " " + name + " - " + e.getReadableFileSize());
		} catch (Exception e) {
			getLabel().setText(LanguageManager.getString("status.cannotOpen") + " " + name);
			JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.unableToOpen"), e);
		} finally {
			bar.setVisible(false);
		}
	}

	void extractClassToTextPane(String internalName, String tabTitle, String path, String navigatonLink)
			throws Exception {
		if (tabTitle == null || tabTitle.trim().length() < 1 || path == null) {
			throw new FileEntryNotFoundException();
		}
		OpenFile sameTitledOpen = null;
		for (OpenFile nextOpen : hmap) {
			if (tabTitle.equals(nextOpen.name)) {
				sameTitledOpen = nextOpen;
				break;
			}
		}
		if (sameTitledOpen != null && path.equals(sameTitledOpen.path)
				&& sameTitledOpen.isClassFile() && sameTitledOpen.isContentValid()) {
			sameTitledOpen.setInitialNavigationLink(navigatonLink);
			addOrSwitchToTab(sameTitledOpen);
			return;
		}

		if (state == null || state.jarFile == null) {
			throw new Exception("No JAR file loaded.");
		}

		if (sameTitledOpen != null) {
			sameTitledOpen.path = path;
			sameTitledOpen.invalidateContent();
			sameTitledOpen.setDecompilationInfo(state.jarFile, internalName, decompilerConfig);
			sameTitledOpen.setInitialNavigationLink(navigatonLink);
			sameTitledOpen.resetScrollPosition();
			sameTitledOpen.decompile();
			addOrSwitchToTab(sameTitledOpen);
		} else {
			OpenFile open = new OpenFile(tabTitle, path, themeColors, mainWindow);
			open.setDecompilationInfo(state.jarFile, internalName, decompilerConfig);
			open.setInitialNavigationLink(navigatonLink);
			open.decompile();
			hmap.add(open);
			addOrSwitchToTab(open);
		}
	}

	public void extractHexDumpToTextPane(InputStream inputStream, String tabTitle, String path)
			throws Exception {
		if (inputStream == null || tabTitle == null || tabTitle.trim().length() < 1 || path == null) {
			throw new FileEntryNotFoundException();
		}
		OpenFile sameTitledOpen = null;
		for (OpenFile nextOpen : hmap) {
			if (tabTitle.equals(nextOpen.name)) {
				sameTitledOpen = nextOpen;
				break;
			}
		}

		java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int bytesRead;
		while ((bytesRead = inputStream.read(buffer)) != -1) {
			baos.write(buffer, 0, bytesRead);
		}
		String hexContent = bytesToHexDump(baos.toByteArray());

		if (sameTitledOpen != null) {
			sameTitledOpen.path = path;
			sameTitledOpen.resetScrollPosition();
			sameTitledOpen.setContent(hexContent);
			addOrSwitchToTab(sameTitledOpen);
		} else {
			OpenFile open = new OpenFile(tabTitle, path, themeColors, mainWindow);
			open.setContent(hexContent);
			hmap.add(open);
			addOrSwitchToTab(open);
		}
	}

	private String bytesToHexDump(byte[] bytes) {
		return bytesToHexDumpStatic(bytes);
	}

	public static String bytesToHexDumpStatic(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		int offset = 0;
		while (offset < bytes.length) {
			sb.append(String.format("%08X  ", offset));
			int lineLen = Math.min(16, bytes.length - offset);
			for (int i = 0; i < 16; i++) {
				if (i < lineLen) {
					sb.append(String.format("%02X ", bytes[offset + i] & 0xFF));
				} else {
					sb.append("   ");
				}
				if (i == 7) sb.append(" ");
			}
			sb.append(" |");
			for (int i = 0; i < lineLen; i++) {
				int b = bytes[offset + i] & 0xFF;
				sb.append(b >= 32 && b < 127 ? (char) b : '.');
			}
			sb.append("|\n");
			offset += 16;
		}
		return sb.toString();
	}

	/**
	 * Analyzes suspicious binary files: all-zero, padding, low entropy.
	 * Attempts XOR brute-force decryption if file looks encrypted.
	 * Returns analysis text if suspicious, null otherwise.
	 */
	private String analyzeSuspiciousFile(byte[] fileBytes, String fileName) {
		if (fileBytes == null || fileBytes.length == 0) return null;

		// Skip analysis for files with known magic bytes — they are legitimate formats
		if (fileBytes.length >= 4) {
			int b0 = fileBytes[0] & 0xFF, b1 = fileBytes[1] & 0xFF, b2 = fileBytes[2] & 0xFF, b3 = fileBytes[3] & 0xFF;
			if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return null; // PNG
			if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return null; // JPEG
			if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return null; // GIF
			if (b0 == 0x42 && b1 == 0x4D) return null; // BMP
			if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && fileBytes.length >= 12
					&& (fileBytes[8] & 0xFF) == 0x57 && (fileBytes[9] & 0xFF) == 0x45
					&& (fileBytes[10] & 0xFF) == 0x42 && (fileBytes[11] & 0xFF) == 0x50) return null; // WEBP
			if (b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04) return null; // ZIP/JAR
			if (b0 == 0x50 && b1 == 0x4B && b2 == 0x05 && b3 == 0x06) return null; // Empty ZIP
			if (b0 == 0x1F && b1 == 0x8B) return null; // GZIP
			if (b0 == 0xCA && b1 == 0xFE && b2 == 0xBA && b3 == 0xBE) return null; // Java class
			if (b0 == 0x4D && b1 == 0x5A) return null; // PE/EXE
			if (b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46) return null; // ELF
			if (b0 == 0x52 && b1 == 0x61 && b2 == 0x72 && b3 == 0x21) return null; // RAR
			if (b0 == 0x42 && b1 == 0x5A && b2 == 0x68) return null; // BZIP2
			if (b0 == 0x49 && b1 == 0x44 && b2 == 0x33) return null; // MP3
			if (b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53) return null; // OGG
			if (b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43) return null; // FLAC
			if (b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46) return null; // PDF
			if (b1 == 0x55 && b2 == 0x4C && b3 == 0x41) return null; // LHA/LHZ (various first byte)
		}

		// Check if all bytes are the same (padding/dummy file)
		boolean allSame = true;
		byte firstByte = fileBytes[0];
		for (int i = 1; i < fileBytes.length; i++) {
			if (fileBytes[i] != firstByte) {
				allSame = false;
				break;
			}
		}

		if (allSame) {
			StringBuilder sb = new StringBuilder();
			sb.append("=== SUSPICIOUS FILE ANALYSIS ===\n\n");
			sb.append("File: ").append(fileName).append("\n");
			sb.append("Size: ").append(fileBytes.length).append(" bytes (").append(formatBytes(fileBytes.length)).append(")\n\n");
			sb.append("WARNING: All bytes are identical: 0x").append(String.format("%02X", firstByte & 0xFF)).append("\n");
			sb.append("This is likely a PADDING / DUMMY file created by the obfuscator.\n");
			sb.append("Purpose: Anti-analysis, anti-tampering, or alignment padding.\n");
			sb.append("This file contains NO real data — it is safe to ignore.\n\n");

			sb.append("--- XOR Brute-Force: SKIPPED ---\n");
			sb.append("XOR brute-force is meaningless for uniform data.\n");
			sb.append("When all bytes are 0x").append(String.format("%02X", firstByte & 0xFF));
			sb.append(", XOR with any key K produces K repeated — no real data is hidden.\n");
			sb.append("This file is genuinely padding/dummy data.\n");
			return sb.toString();
		}

		// Calculate Shannon entropy
		double entropy = calculateEntropy(fileBytes);
		System.out.println("[ANALYSIS] " + fileName + " entropy=" + String.format("%.2f", entropy) + " size=" + fileBytes.length);

		// Low entropy threshold (very repetitive data)
		if (entropy < 1.0) {
			StringBuilder sb = new StringBuilder();
			sb.append("=== SUSPICIOUS FILE ANALYSIS ===\n\n");
			sb.append("File: ").append(fileName).append("\n");
			sb.append("Size: ").append(fileBytes.length).append(" bytes (").append(formatBytes(fileBytes.length)).append(")\n");
			sb.append("Entropy: ").append(String.format("%.2f", entropy)).append(" bits/byte (VERY LOW)\n\n");
			sb.append("WARNING: This file has extremely low entropy.\n");
			sb.append("The data is highly repetitive — likely padding, dummy, or simple XOR encrypted.\n\n");

			// Count unique bytes
			int[] freq = new int[256];
			for (byte b : fileBytes) freq[b & 0xFF]++;
			int uniqueCount = 0;
			for (int f : freq) if (f > 0) uniqueCount++;
			sb.append("Unique byte values: ").append(uniqueCount).append("/256\n\n");

			// Try XOR brute force
			sb.append("--- XOR Brute-Force Attempt ---\n");
			String xorResult = tryXorBruteForce(fileBytes, fileName);
			if (xorResult != null) {
				sb.append(xorResult);
			} else {
				sb.append("XOR brute-force: No meaningful result found.\n");
			}
			sb.append("\n--- Hex Dump (first 512 bytes) ---\n");
			int previewLen = Math.min(fileBytes.length, 512);
			sb.append(bytesToHexDumpStatic(java.util.Arrays.copyOf(fileBytes, previewLen)));
			if (fileBytes.length > 512) {
				sb.append("\n... (").append(fileBytes.length - 512).append(" more bytes truncated)\n");
			}
			return sb.toString();
		}

		// High entropy — possibly encrypted
		if (entropy > 7.5 && fileBytes.length > 64) {
			StringBuilder sb = new StringBuilder();
			sb.append("=== SUSPICIOUS FILE ANALYSIS ===\n\n");
			sb.append("File: ").append(fileName).append("\n");
			sb.append("Size: ").append(fileBytes.length).append(" bytes (").append(formatBytes(fileBytes.length)).append(")\n");
			sb.append("Entropy: ").append(String.format("%.2f", entropy)).append(" bits/byte (HIGH)\n\n");
			sb.append("NOTE: This file has very high entropy — it may be encrypted or compressed.\n");
			sb.append("Possible formats: AES encrypted, LZMA compressed, or obfuscated container.\n\n");

			// Check for known magic bytes
			sb.append("--- Magic Byte Check ---\n");
			sb.append(checkMagicBytes(fileBytes));
			sb.append("\n");

			// Try XOR brute force
			sb.append("--- XOR Brute-Force Attempt ---\n");
			String xorResult = tryXorBruteForce(fileBytes, fileName);
			if (xorResult != null) {
				sb.append(xorResult);
			} else {
				sb.append("XOR brute-force: No meaningful result found (file may use stronger encryption).\n");
			}
			sb.append("\n--- Hex Dump (first 512 bytes) ---\n");
			int previewLen = Math.min(fileBytes.length, 512);
			sb.append(bytesToHexDumpStatic(java.util.Arrays.copyOf(fileBytes, previewLen)));
			if (fileBytes.length > 512) {
				sb.append("\n... (").append(fileBytes.length - 512).append(" more bytes truncated)\n");
			}
			return sb.toString();
		}

		return null; // Not suspicious, use normal hex dump
	}

	/**
	 * Calculates Shannon entropy in bits/byte.
	 */
	private double calculateEntropy(byte[] bytes) {
		if (bytes.length == 0) return 0.0;
		int[] freq = new int[256];
		for (byte b : bytes) freq[b & 0xFF]++;
		double entropy = 0.0;
		for (int f : freq) {
			if (f > 0) {
				double p = (double) f / bytes.length;
				entropy -= p * (Math.log(p) / Math.log(2));
			}
		}
		return entropy;
	}

	/**
	 * Attempts single-byte XOR brute-force (0-255).
	 * Returns result text if a meaningful decryption is found, null otherwise.
	 */
	private String tryXorBruteForce(byte[] fileBytes, String fileName) {
		int bestKey = -1;
		int bestScore = 0;
		String bestPreview = null;

		for (int key = 0; key < 256; key++) {
			byte[] decoded = new byte[Math.min(fileBytes.length, 256)];
			for (int i = 0; i < decoded.length; i++) {
				decoded[i] = (byte) (fileBytes[i] ^ key);
			}

			// Score: count printable ASCII chars
			int printable = 0;
			for (byte b : decoded) {
				int v = b & 0xFF;
				if (v >= 32 && v < 127) printable++;
				else if (v == '\n' || v == '\r' || v == '\t') printable++;
			}
			int score = printable * 100 / decoded.length;

			// Check for known magic bytes after XOR
			if (decoded.length >= 4) {
				if (decoded[0] == (byte) 0xCA && decoded[1] == (byte) 0xFE
						&& decoded[2] == (byte) 0xBA && decoded[3] == (byte) 0xBE) {
					score += 1000; // Java class file
				}
				if (decoded[0] == 0x50 && decoded[1] == 0x4B && decoded[2] == 0x03 && decoded[3] == 0x04) {
					score += 1000; // ZIP/JAR
				}
				if (decoded[0] == 0x1F && decoded[1] == (byte) 0x8B) {
					score += 1000; // GZIP
				}
				if (decoded[0] == (byte) 0x5D && decoded[1] == 0x00 && decoded[2] == 0x00 && decoded[3] == 0x00) {
					score += 1000; // LZMA container
				}
			}

			if (score > bestScore) {
				bestScore = score;
				bestKey = key;
				bestPreview = new String(decoded, java.nio.charset.StandardCharsets.ISO_8859_1);
			}
		}

		if (bestScore >= 80 && bestKey >= 0) {
			StringBuilder sb = new StringBuilder();
			sb.append("POSSIBLE XOR KEY FOUND: 0x").append(String.format("%02X", bestKey)).append(" (").append(bestKey).append(")\n");
			sb.append("Confidence: ").append(bestScore >= 1000 ? "HIGH (magic bytes matched)" : "MEDIUM (printable ratio " + bestScore + "%)").append("\n\n");

			// Full decode
			byte[] fullDecoded = new byte[fileBytes.length];
			for (int i = 0; i < fileBytes.length; i++) {
				fullDecoded[i] = (byte) (fileBytes[i] ^ bestKey);
			}

			// Check if decoded is a class file
			if (fullDecoded.length >= 4 && fullDecoded[0] == (byte) 0xCA && fullDecoded[1] == (byte) 0xFE
					&& fullDecoded[2] == (byte) 0xBA && fullDecoded[3] == (byte) 0xBE) {
				sb.append("Decoded content: JAVA CLASS FILE (CAFEBABE magic detected)\n");
				sb.append("Size: ").append(fullDecoded.length).append(" bytes\n");
				sb.append("\n--- Decoded Hex Dump (first 512 bytes) ---\n");
				int previewLen = Math.min(fullDecoded.length, 512);
				sb.append(bytesToHexDumpStatic(java.util.Arrays.copyOf(fullDecoded, previewLen)));
			} else if (fullDecoded.length >= 4 && fullDecoded[0] == 0x50 && fullDecoded[1] == 0x4B
					&& fullDecoded[2] == 0x03 && fullDecoded[3] == 0x04) {
				sb.append("Decoded content: ZIP/JAR FILE (PK magic detected)\n");
				sb.append("Size: ").append(fullDecoded.length).append(" bytes\n");
				sb.append("\n--- Decoded Hex Dump (first 512 bytes) ---\n");
				int previewLen = Math.min(fullDecoded.length, 512);
				sb.append(bytesToHexDumpStatic(java.util.Arrays.copyOf(fullDecoded, previewLen)));
			} else {
				// Show as text if mostly printable
				int printable = 0;
				for (byte b : fullDecoded) {
					int v = b & 0xFF;
					if (v >= 32 && v < 127 || v == '\n' || v == '\r' || v == '\t') printable++;
				}
				if (printable * 100 / fullDecoded.length >= 80) {
					sb.append("Decoded content (text):\n\n");
					String text = new String(fullDecoded, java.nio.charset.StandardCharsets.UTF_8);
					int textPreview = Math.min(text.length(), 4096);
					sb.append(text.substring(0, textPreview));
					if (text.length() > 4096) {
						sb.append("\n\n... (").append(text.length() - 4096).append(" more chars truncated)\n");
					}
				} else {
					sb.append("Decoded content (hex, first 512 bytes):\n\n");
					int previewLen = Math.min(fullDecoded.length, 512);
					sb.append(bytesToHexDumpStatic(java.util.Arrays.copyOf(fullDecoded, previewLen)));
				}
			}
			return sb.toString();
		}

		return null;
	}

	/**
	 * Checks for known file format magic bytes.
	 */
	private String checkMagicBytes(byte[] bytes) {
		if (bytes.length < 4) return "File too small for magic byte check.\n";
		StringBuilder sb = new StringBuilder();
		int b0 = bytes[0] & 0xFF, b1 = bytes[1] & 0xFF, b2 = bytes[2] & 0xFF, b3 = bytes[3] & 0xFF;

		if (b0 == 0xCA && b1 == 0xFE && b2 == 0xBA && b3 == 0xBE) sb.append("  Java CLASS file detected\n");
		else if (b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04) sb.append("  ZIP/JAR archive detected\n");
		else if (b0 == 0x50 && b1 == 0x4B && b2 == 0x05 && b3 == 0x06) sb.append("  Empty ZIP archive detected\n");
		else if (b0 == 0x1F && b1 == 0x8B) sb.append("  GZIP compressed data detected\n");
		else if (b0 == 0x5D && b1 == 0x00 && b2 == 0x00 && b3 == 0x00) sb.append("  LZMA compressed data detected\n");
		else if (b0 == 0x4D && b1 == 0x5A) sb.append("  PE/EXE (Windows) executable detected\n");
		else if (b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46) sb.append("  ELF (Linux) executable detected\n");
		else if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) sb.append("  PNG image detected\n");
		else if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) sb.append("  JPEG image detected\n");
		else if (b0 == 0x52 && b1 == 0x61 && b2 == 0x72 && b3 == 0x21) sb.append("  RAR archive detected\n");
		else if (b0 == 0x42 && b1 == 0x5A && b2 == 0x68) sb.append("  BZIP2 compressed data detected\n");
		else sb.append("  Unknown format (no known magic bytes matched)\n");

		sb.append("  First 8 bytes: ");
		for (int i = 0; i < Math.min(8, bytes.length); i++) {
			sb.append(String.format("%02X ", bytes[i] & 0xFF));
		}
		sb.append("\n");
		return sb.toString();
	}

	/**
	 * Formats byte count as human-readable string.
	 */
	/** Takes a long: archive entry sizes are longs and an int caps out at 2 GB. */
	private String formatBytes(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
		if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
		return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
	}

	public void extractSimpleFileEntryToTextPane(InputStream inputStream, String tabTitle, String path)
			throws Exception {
		extractSimpleFileEntryToTextPane(inputStream, tabTitle, path, null);
	}

	/**
	 * @param truncNote prepended when only the head of the entry was read, so the
	 *        pane never quietly implies it is showing the whole file
	 */
	public void extractSimpleFileEntryToTextPane(InputStream inputStream, String tabTitle, String path,
			String truncNote) throws Exception {
		if (inputStream == null || tabTitle == null || tabTitle.trim().length() < 1 || path == null) {
			throw new FileEntryNotFoundException();
		}
		OpenFile sameTitledOpen = null;
		for (OpenFile nextOpen : hmap) {
			if (tabTitle.equals(nextOpen.name)) {
				sameTitledOpen = nextOpen;
				break;
			}
		}
		if (sameTitledOpen != null && path.equals(sameTitledOpen.path)) {
			addOrSwitchToTab(sameTitledOpen);
			return;
		}

		java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int bytesRead;
		while ((bytesRead = inputStream.read(buffer)) != -1) {
			baos.write(buffer, 0, bytesRead);
		}
		byte[] fileBytes = baos.toByteArray();

		if (fileBytes.length == 0) {
			String emptyMsg = "=== EMPTY FILE ===\n\nFile: " + tabTitle + "\nSize: 0 bytes\n\nThis file is empty (0 bytes).\nIt may be a placeholder, marker, or dummy file created by the obfuscator.";
			if (sameTitledOpen != null) {
				sameTitledOpen.path = path;
				sameTitledOpen.resetScrollPosition();
				sameTitledOpen.setContent(emptyMsg);
				addOrSwitchToTab(sameTitledOpen);
			} else {
				OpenFile open = new OpenFile(tabTitle, path, themeColors, mainWindow);
				open.setContent(emptyMsg);
				hmap.add(open);
				addOrSwitchToTab(open);
			}
			return;
		}

		String extension = "." + tabTitle.replaceAll("^[^\\.]*$", "").replaceAll("[^\\.]*\\.", "");
		boolean isTextFile = OpenFile.WELL_KNOWN_TEXT_FILE_EXTENSIONS.contains(extension);
		if (!isTextFile) {
			long nonprintableCount = 0;
			for (int i = 0; i < Math.min(fileBytes.length, 4096); i++) {
				if (fileBytes[i] <= 0 && fileBytes[i] != '\n' && fileBytes[i] != '\r' && fileBytes[i] != '\t') {
					nonprintableCount++;
				}
			}
			isTextFile = nonprintableCount < Math.min(fileBytes.length, 4096) / 5;
		}

		// Detect image files by magic bytes
		boolean isImageFile = false;
		String imageTypeInfo = null;
		if (fileBytes.length >= 4) {
			int b0 = fileBytes[0] & 0xFF, b1 = fileBytes[1] & 0xFF, b2 = fileBytes[2] & 0xFF, b3 = fileBytes[3] & 0xFF;
			if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) { isImageFile = true; imageTypeInfo = "PNG"; }
			else if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) { isImageFile = true; imageTypeInfo = "JPEG"; }
			else if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) { isImageFile = true; imageTypeInfo = "GIF"; }
			else if (b0 == 0x42 && b1 == 0x4D) { isImageFile = true; imageTypeInfo = "BMP"; }
			else if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && fileBytes.length >= 12
					&& (fileBytes[8] & 0xFF) == 0x57 && (fileBytes[9] & 0xFF) == 0x45
					&& (fileBytes[10] & 0xFF) == 0x42 && (fileBytes[11] & 0xFF) == 0x50) { isImageFile = true; imageTypeInfo = "WEBP"; }
		}

		String content;
		if (isTextFile) {
			content = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);
		} else if (isImageFile) {
			content = "=== IMAGE FILE ===\n\n"
					+ "File: " + tabTitle + "\n"
					+ "Format: " + imageTypeInfo + "\n"
					+ "Size: " + fileBytes.length + " bytes (" + formatBytes(fileBytes.length) + ")\n\n"
					+ "This is a valid " + imageTypeInfo + " image file.\n"
					+ ">>> Right-click here and select \"Image Preview\" to view the image. <<<\n\n"
					+ "--- Hex Dump (first 256 bytes) ---\n";
			int previewLen = Math.min(fileBytes.length, 256);
			content += bytesToHexDumpStatic(java.util.Arrays.copyOf(fileBytes, previewLen));
			if (fileBytes.length > 256) {
				content += "\n... (" + (fileBytes.length - 256) + " more bytes)\n";
			}
		} else {
			// Analyze suspicious binary files (all-zero, low entropy, padding)
			String analysis = analyzeSuspiciousFile(fileBytes, tabTitle);
			if (analysis != null) {
				content = analysis;
			} else {
				content = bytesToHexDump(fileBytes);
			}
		}

		if (truncNote != null) content = truncNote + "\n\n" + content;

		if (sameTitledOpen != null) {
			sameTitledOpen.path = path;
			sameTitledOpen.resetScrollPosition();
			sameTitledOpen.setContent(content);
			sameTitledOpen.setRawBytes(fileBytes);
			addOrSwitchToTab(sameTitledOpen);
		} else {
			OpenFile open = new OpenFile(tabTitle, path, themeColors, mainWindow);
			open.setContent(content);
			open.setRawBytes(fileBytes);
			hmap.add(open);
			addOrSwitchToTab(open);
		}
	}

	private class TabChangeListener implements ChangeListener {
		@Override
		public void stateChanged(ChangeEvent e) {
			int selectedIndex = house.getSelectedIndex();
			if (selectedIndex < 0) {
				return;
			}
			for (OpenFile open : hmap) {
				if (house.indexOfTab(open.name) == selectedIndex) {

					if (open.isClassFile() && !open.isContentValid()) {
						updateOpenClass(open);
						break;
					}

				}
			}
		}
	}

	public void updateOpenClasses() {
		for (OpenFile open : hmap) {
			if (open.isClassFile()) {
				open.invalidateContent();
			}
		}
		for (OpenFile open : hmap) {
			if (open.isClassFile() && isTabInForeground(open)) {
				updateOpenClass(open);
				break;
			}
		}
	}

	private void updateOpenClass(final OpenFile open) {
		if (!open.isClassFile()) {
			return;
		}
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					bar.setVisible(true);
					getLabel().setText(LanguageManager.getString("status.loading") + " " + open.name);
					open.invalidateContent();
					try {
						open.decompile();
						getLabel().setText(LanguageManager.getString("status.complete"));
					} catch (Throwable t) {
						getLabel().setText(LanguageManager.getString("status.decompileFailed"));
					}
				} catch (Exception e) {
					getLabel().setText(LanguageManager.getString("status.cannotUpdate") + " " + open.name);
				} finally {
					bar.setVisible(false);
				}
			}
		}).start();
	}

	private boolean isTabInForeground(OpenFile open) {
		String title = open.name;
		int selectedIndex = house.getSelectedIndex();
		return (selectedIndex >= 0 && selectedIndex == house.indexOfTab(title));
	}

	final class State implements AutoCloseable {
		private final String key;
		private final File file;
		final JarFile jarFile;

		private State(String key, File file, JarFile jarFile) {
			this.key = key;
			this.file = file;
			this.jarFile = jarFile;
		}

		@Override
		public void close() {
			if (jarFile != null) {
				try { jarFile.close(); } catch (Throwable ignored) {}
			}
		}

		public File getFile() {
			return file;
		}

		public String getKey() {
			return key;
		}
	}

	private class Tab extends JPanel {
		private static final long serialVersionUID = -514663009333644974L;
		private JLabel closeButton = createCloseButton();
		private JLabel tabTitle = new JLabel();
		private String title = "";

		private JLabel createCloseButton() {
			int size = 16;
			java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D g2 = img.createGraphics();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(new Color(180, 180, 180));
			g2.setStroke(new java.awt.BasicStroke(2f));
			int margin = 3;
			g2.drawLine(margin, margin, size - margin, size - margin);
			g2.drawLine(size - margin, margin, margin, size - margin);
			g2.dispose();
			return new JLabel(new ImageIcon(img));
		}

		public Tab(String t) {
			super(new GridBagLayout());
			this.setOpaque(false);

			this.title = t;
			this.tabTitle = new JLabel(title);

			this.createTab();
		}

		public JLabel getButton() {
			return this.closeButton;
		}

		public void createTab() {
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.gridx = 0;
			gbc.gridy = 0;
			gbc.weightx = 1;
			this.add(tabTitle, gbc);
			gbc.gridx++;
			gbc.insets = new Insets(0, 5, 0, 0);
			gbc.anchor = GridBagConstraints.EAST;
			this.add(closeButton, gbc);
		}
	}

	private class CloseTab extends MouseAdapter {
		String title;

		public CloseTab(String title) {
			this.title = title;
		}

		@Override
		public void mouseClicked(MouseEvent e) {
			int index = house.indexOfTab(title);
			closeOpenTab(index);
		}
	}

	public DefaultMutableTreeNode loadNodesByNames(DefaultMutableTreeNode node, List<String> originalNames) {
		List<TreeNodeUserObject> args = new ArrayList<>();
		for (String originalName : originalNames) {
			args.add(new TreeNodeUserObject(originalName));
		}
		return loadNodesByUserObj(node, args);
	}

	@SuppressWarnings("unchecked")
	public DefaultMutableTreeNode loadNodesByUserObj(DefaultMutableTreeNode node, List<TreeNodeUserObject> args) {
		DefaultMutableTreeNode current = node;
		for (int i = 0; i < args.size(); i++) {
			TreeNodeUserObject name = args.get(i);
			DefaultMutableTreeNode child = getChild(current, name);
			if (child == null) {
				child = new DefaultMutableTreeNode(name);
				current.add(child);
			}
			current = child;
		}
		return node;
	}

	@SuppressWarnings("unchecked")
	public DefaultMutableTreeNode getChild(DefaultMutableTreeNode node, TreeNodeUserObject name) {
		for (int i = 0; i < node.getChildCount(); i++) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
			if (((TreeNodeUserObject) child.getUserObject()).getOriginalName().equals(name.getOriginalName())) {
				return child;
			}
		}
		return null;
	}

	private boolean autoProcessSkip = false;

	/**
	 * Whether this file is a Java archive, judged by its first four bytes.
	 *
	 * <p>The name is deliberately not consulted. The scan tab reports archives
	 * that are hiding under another extension — a {@code doomsday.jar} renamed to
	 * {@code yks1233.dll} — and refusing to open exactly those in the Decompile
	 * tab made the tool useless for the case it is best at finding.
	 */
	private static boolean isArchive(File f) {
		return com.jaranalyzer.scan.ArchiveSniffer.looksLikeZip(f);
	}

	public void loadFile(File file) {
		if (file == null) return;
		if (!file.isFile()) {
			JOptionPane.showMessageDialog(mainWindow,
				LanguageManager.getString("dialog.unsupportedFile.msg") + " " + file.getName(),
				LanguageManager.getString("dialog.unsupportedFile"), JOptionPane.WARNING_MESSAGE);
			return;
		}
		// Anything that exists can be opened. An archive gets a tree; everything
		// else opens as source, text or a hex dump — being unable to look at a
		// file is never the useful answer in a forensics tool.
		if (open)
			closeFile();
		this.file = file;

		RecentFiles.add(file.getAbsolutePath());
		mainWindow.getMainMenuBar().updateRecentFiles();
		loadTree();
	}

	public void loadLiveSrcTree(File jarFile) {
		if (jarFile == null) return;
		if (open)
			closeFile();
		this.file = jarFile;
		RecentFiles.add(jarFile.getAbsolutePath());
		mainWindow.getMainMenuBar().updateRecentFiles();
		new Thread(() -> {
			try {
				JarFile jf = new JarFile(jarFile);
				currentJarFile = jf;
				state = new State(jarFile.getCanonicalPath(), jarFile, jf);
				List<String> mass = new ArrayList<>();
				Enumeration<JarEntry> entries = jf.entries();
				while (entries.hasMoreElements()) {
					JarEntry entry = entries.nextElement();
					if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
					mass.add(entry.getName());
				}
				SwingUtilities.invokeLater(() -> {
					tree.setModel(new DefaultTreeModel(null));
					buildTreeFromMassLive(mass, jarFile.getName());
					open = true;
					getLabel().setText(LanguageManager.getString("status.complete"));
				});
			} catch (Exception ex) {
				SwingUtilities.invokeLater(() -> getLabel().setText("Error: " + ex.getMessage()));
			}
		}).start();
	}

	private void buildTreeFromMassLive(List<String> mass, String topName) {
		TreeNodeUserObject topNodeUserObject = new TreeNodeUserObject(getName(topName), getName(topName));
		DefaultMutableTreeNode top = new DefaultMutableTreeNode(topNodeUserObject);
		Collections.sort(mass, String.CASE_INSENSITIVE_ORDER);
		java.util.Map<String, DefaultMutableTreeNode> nodeCache = new java.util.HashMap<>();
		nodeCache.put("", top);
		for (String m : mass) {
			String[] parts = m.split("/");
			String currentPath = "";
			DefaultMutableTreeNode parent = top;
			for (int i = 0; i < parts.length; i++) {
				String part = parts[i];
				currentPath = currentPath.isEmpty() ? part : currentPath + "/" + part;
				DefaultMutableTreeNode child = nodeCache.get(currentPath);
				if (child == null) {
					if (i == parts.length - 1 && part.endsWith(".class")) {
						String javaName = part.substring(0, part.length() - 6) + ".java";
						child = new DefaultMutableTreeNode(new TreeNodeUserObject(part, javaName));
					} else {
						child = new DefaultMutableTreeNode(new TreeNodeUserObject(part));
					}
					parent.add(child);
					nodeCache.put(currentPath, child);
				}
				parent = child;
			}
		}
		tree.setModel(new DefaultTreeModel(top));
	}

	public void refreshTree() {
		if (file != null && open) {
			updateTree();
		}
	}

	public void updateTree() {
		TreeUtil treeUtil = new TreeUtil(tree);
		treeExpansionState = treeUtil.getExpansionState();
		loadTree();
	}

	public void loadTree() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					if (file == null) {
						return;
					}
					tree.setModel(new DefaultTreeModel(null));

					if (file.length() > MAX_JAR_FILE_SIZE_BYTES) {
						throw new TooLargeFileException(file.length());
					}
					if (isArchive(file)) {
						JarFile jfile = new JarFile(file);
						getLabel().setText(LanguageManager.getString("status.loading") + " " + jfile.getName());
						bar.setVisible(true);

						JarEntryFilter jarEntryFilter = new JarEntryFilter(jfile);
						List<String> mass = jarEntryFilter.getAllEntriesFromJar();
						buildTreeFromMass(mass);

						if (state == null) {
							state = new State(file.getCanonicalPath(), file, jfile);
							currentJarFile = jfile;
						} else {
							// The tree is rebuilt on every refresh, but the state only
							// takes ownership of the handle the first time. Without this
							// close every refresh leaked one open JarFile — and on
							// Windows an open JarFile is a file lock, so the jar being
							// inspected could not be deleted or moved afterwards.
							try {
								jfile.close();
							} catch (Exception ignored) {
								// Nothing useful to do; the tree is already built.
							}
						}
						open = true;
						getLabel().setText(LanguageManager.getString("status.complete"));

						if (autoProcessSkip) {
							autoProcessSkip = false;
							System.out.println("[AUTOPROC] Skipping auto-process (already processed)");
						} else {
							runAutoProcess();
						}
					} else {
						TreeNodeUserObject topNodeUserObject = new TreeNodeUserObject(getName(file.getName()));
						final DefaultMutableTreeNode top = new DefaultMutableTreeNode(topNodeUserObject);
						tree.setModel(new DefaultTreeModel(top));
	open = true;
						getLabel().setText(LanguageManager.getString("status.complete"));

						new Thread() {
							public void run() {
								TreePath trp = new TreePath(top.getPath());
								openEntryByTreePath(trp);
							};
						}.start();
					}

					if (treeExpansionState != null) {
						try {
							TreeUtil treeUtil = new TreeUtil(tree);
							treeUtil.restoreExpanstionState(treeExpansionState);
						} catch (Exception e) {
							JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e);
						}
					}
				} catch (TooLargeFileException e) {
					getLabel().setText(LanguageManager.getString("status.fileTooLarge") + " " + file.getName() + " - " + e.getReadableFileSize());
					closeFile();
				} catch (Exception e1) {
					JarAnalyzer.showExceptionDialog(LanguageManager.getString("status.cannotOpen") + " " + file.getName() + "!", e1);
					getLabel().setText(LanguageManager.getString("status.cannotOpen") + " " + file.getName());
					closeFile();
				} finally {
					bar.setVisible(false);
				}
			}

		}).start();
	}

	private void runTxtProcess() {
		new Thread(() -> {
			try {
				StringBuilder log = new StringBuilder();
				log.append("=== TXT PROCESS: ").append(file.getName()).append(" ===\n\n");

				getLabel().setText(LanguageManager.getString("status.decrypting"));
				log.append("--- Step 1: Reading & Analysis ---\n");

				byte[] rawBytes = java.nio.file.Files.readAllBytes(file.toPath());
				log.append("File size: ").append(rawBytes.length).append(" bytes\n");

				String content = new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);

				boolean isBase64 = false;
				boolean isXorEncrypted = false;
				byte[] decrypted = null;
				String detectedType = "Plain text";

				String trimmed = content.trim();
				if (trimmed.length() > 0 && trimmed.length() % 4 == 0
						&& trimmed.matches("^[A-Za-z0-9+/\\s\\r\\n]+=*$")
						&& !trimmed.contains("the ") && !trimmed.contains("and ")
						&& !trimmed.contains("import ") && !trimmed.contains("public ")
						&& !trimmed.contains("class ")) {
					isBase64 = true;
					detectedType = "Base64 encoded";
					log.append("Detected: Base64 encoding\n");
					try {
						byte[] decoded = java.util.Base64.getDecoder().decode(trimmed.replaceAll("\\s", ""));
						String decodedStr = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
						if (decodedStr.matches("^[\\x00-\\x7F]+$") || decodedStr.contains("class ")
								|| decodedStr.contains("import ") || decodedStr.contains("package ")) {
							decrypted = decoded;
							content = decodedStr;
							log.append("Base64 decoded successfully: ").append(decoded.length).append(" bytes\n");
						} else {
							byte[] xorResult = tryXorBruteForce(decoded);
							if (xorResult != null) {
								isXorEncrypted = true;
								detectedType = "Base64 + XOR encrypted";
								decrypted = xorResult;
								content = new String(xorResult, java.nio.charset.StandardCharsets.UTF_8);
								log.append("Base64 decoded, then XOR decrypted (key found)\n");
							}
						}
					} catch (Exception e) {
						log.append("Base64 decode failed: ").append(e.getMessage()).append("\n");
						isBase64 = false;
					}
				}

				if (!isBase64) {
					byte[] xorResult = tryXorBruteForce(rawBytes);
					if (xorResult != null) {
						isXorEncrypted = true;
						detectedType = "XOR encrypted";
						decrypted = xorResult;
						content = new String(xorResult, java.nio.charset.StandardCharsets.UTF_8);
						log.append("XOR decryption successful (key found)\n");
					}
				}

				if (decrypted == null) {
					int nonPrintable = 0;
					for (int i = 0; i < Math.min(rawBytes.length, 1024); i++) {
						int b = rawBytes[i] & 0xFF;
						if (b < 9 || (b > 13 && b < 32) || b > 126) nonPrintable++;
					}
					if (nonPrintable > Math.min(rawBytes.length, 1024) / 3) {
						detectedType = "Encrypted/Binary (unknown)";
						log.append("File appears to be encrypted or binary. High non-printable ratio.\n");
						log.append("Trying XOR brute force on raw bytes...\n");
						byte[] xorResult = tryXorBruteForce(rawBytes);
						if (xorResult != null) {
							isXorEncrypted = true;
							detectedType = "XOR encrypted";
							decrypted = xorResult;
							content = new String(xorResult, java.nio.charset.StandardCharsets.UTF_8);
							log.append("XOR decryption successful!\n");
						} else {
							log.append("XOR brute force failed. Showing hex dump.\n");
							content = bytesToHexDump(rawBytes);
						}
					} else {
						log.append("File is plain text. No encryption detected.\n");
					}
				}

				log.append("\nDetected type: ").append(detectedType).append("\n");
				log.append("--- Done ---\n");

				final String finalContent = content;
				final String finalDetectedType = detectedType;
				final String finalLog = log.toString();
				final boolean finalIsBase64 = isBase64;
				final boolean finalIsXorEncrypted = isXorEncrypted;

				SwingUtilities.invokeLater(() -> {
					show(LanguageManager.getString("tab.processLog"), finalLog);
					getLabel().setText(LanguageManager.getString("status.complete"));

					OpenFile open = new OpenFile(file.getName(), file.getName(), themeColors, mainWindow);
					open.setContent(finalContent);
					hmap.add(open);
					addOrSwitchToTab(open);

					String msg = LanguageManager.getString("process.complete.msg");
					msg += LanguageManager.getString("process.decrypt") + " " +
							(finalIsXorEncrypted || finalIsBase64 ? LanguageManager.getString("process.done") : LanguageManager.getString("process.notEncrypted")) + "\n";
					msg += LanguageManager.getString("process.deobf") + " " + LanguageManager.getString("process.notObfuscated") + "\n";
					msg += LanguageManager.getString("txt.detectedType") + " " + finalDetectedType;

					JOptionPane.showMessageDialog(Model.this, msg,
							LanguageManager.getString("process.complete.title"), JOptionPane.INFORMATION_MESSAGE);
				});

			} catch (Exception e) {
				System.err.println("[TXTPROC] Error: " + e);
				SwingUtilities.invokeLater(() -> {
					getLabel().setText(LanguageManager.getString("status.autoProcessError") + " " + e.getMessage());
				});
			}
		}).start();
	}

	private void runAutoProcess() {
		if (file == null) return;
		String fileName = file.getName().toLowerCase();
		if (!fileName.endsWith(".jar") && !fileName.endsWith(".txt")) return;
		if (fileName.endsWith(".txt")) {
			runTxtProcess();
			return;
		}
		new Thread(() -> {
			try {
				StringBuilder log = new StringBuilder();
				log.append("=== ")
						.append(LanguageManager.getString("proc.header")
								.replace("{0}", file.getName()))
						.append(" ===\n\n");

				// Step 1: Decrypt encrypted files
				getLabel().setText(LanguageManager.getString("status.decrypting"));
				log.append(LanguageManager.getString("proc.step1")).append("\n");
				System.out.println("[AUTOPROC] Step 1: Decryption");

				StringBuilder decryptLog = new StringBuilder();
				File decryptedJar = decryptAllToTempJar(decryptLog);
				log.append(decryptLog).append("\n");

				if (decryptedJar != null) {
					log.append(LanguageManager.getString("proc.decrypted")).append(' ')
							.append(decryptedJar.getAbsolutePath()).append("\n\n");
					System.out.println("[AUTOPROC] Decrypted JAR: " + decryptedJar.getAbsolutePath());
				} else {
					log.append(LanguageManager.getString("proc.noEncrypted")).append("\n\n");
					System.out.println("[AUTOPROC] No encrypted files, using original JAR");
				}
				final boolean wasEncrypted = decryptedJar != null;

				File jarToProcess = (decryptedJar != null) ? decryptedJar : file;

				// Step 2: Deobfuscate
				getLabel().setText(LanguageManager.getString("status.deobfuscating"));
				log.append(LanguageManager.getString("proc.step2")).append("\n");
				System.out.println("[AUTOPROC] Step 2: Deobfuscation");

				JarFile jf = new JarFile(jarToProcess);
				ObfuscationDetector.DetectionResult detection = ObfuscationDetector.detect(jf);
				jf.close();

				StringBuilder summary = new StringBuilder();
				summary.append(LanguageManager.getString("obf.summary")).append(": ");
				summary.append(detection.type.getDisplayName());
				summary.append(" | ").append(LanguageManager.getString("obf.score")).append(": ");
				summary.append(String.format("%.2f", detection.obfuscationScore));
				summary.append(" | ").append(LanguageManager.getString("obf.encryptedStrings")).append(": ");
				summary.append(LanguageManager.getString(
						detection.hasEncryptedStrings ? "proc.yes" : "proc.no"));
				summary.append(" | ").append(LanguageManager.getString("obf.shortNames")).append(": ");
				summary.append(LanguageManager.getString(
						detection.hasShortNames ? "proc.yes" : "proc.no"));

				SwingUtilities.invokeLater(() -> {
					obfSummaryLabel.setText(summary.toString());
				});

				log.append(LanguageManager.getString("proc.obfuscator")).append(' ')
						.append(detection.type.getDisplayName()).append("\n");
				log.append(LanguageManager.getString("proc.score")).append(' ')
						.append(String.format("%.2f", detection.obfuscationScore)).append("\n");

				if (!detection.isObfuscated) {
					log.append(LanguageManager.getString("proc.notObf")).append("\n\n");
					System.out.println("[AUTOPROC] Not obfuscated, skipping deobf");
				} else {
					SwingUtilities.invokeLater(() -> {
						show(LanguageManager.getString("tab.processLog"), log.toString());
					});

					File deobfResult = Deobfuscator.deobfuscate(jarToProcess, new Deobfuscator.DeobfuscationCallback() {
						@Override
						public void onProgress(String stage, String msg) {
							log.append("[").append(stage).append("] ").append(msg).append("\n");
							System.out.println("[AUTOPROC] [" + stage + "] " + msg);
							SwingUtilities.invokeLater(() -> {
								show(LanguageManager.getString("tab.processLog"), log.toString());
							});
						}

						@Override
						public void onComplete(File deobfuscatedJar, ObfuscationDetector.DetectionResult det) {
							log.append("\n" + LanguageManager.getString("status.deobfComplete") + " ").append(deobfuscatedJar.getAbsolutePath()).append("\n\n");
							System.out.println("[AUTOPROC] Deobf complete: " + deobfuscatedJar.getAbsolutePath());
							SwingUtilities.invokeLater(() -> {
								show(LanguageManager.getString("tab.processLog"), log.toString());
							});
							runCheatScanAndLoad(deobfuscatedJar, log, wasEncrypted, true);
						}

						@Override
						public void onError(String message, Exception e) {
							log.append("\n" + LanguageManager.getString("status.deobfError") + " ").append(message).append("\n\n");
							System.err.println("[AUTOPROC] Deobf error: " + message);
							SwingUtilities.invokeLater(() -> {
								show(LanguageManager.getString("tab.processLog"), log.toString());
							});
							runCheatScanAndLoad(jarToProcess, log, wasEncrypted, true);
						}
					});
					return;
				}

				// Not obfuscated, go straight to cheat scan
				runCheatScanAndLoad(jarToProcess, log, wasEncrypted, false);

			} catch (Exception e) {
				System.err.println("[AUTOPROC] Fatal: " + e);
				getLabel().setText(LanguageManager.getString("status.autoProcessError") + " " + e.getMessage());
			}
		}).start();
	}

	/**
	 * @param wasEncrypted  whether step 1 actually decrypted anything
	 * @param wasObfuscated whether step 2 found obfuscation
	 *
	 * <p>Both are passed as flags rather than recovered by searching the log text,
	 * so the summary stays correct in any language.
	 */
	private void runCheatScanAndLoad(File jarToLoad, StringBuilder log,
			boolean wasEncrypted, boolean wasObfuscated) {
		new Thread(() -> {
			try {
				// Step 3: Cheat scan
				getLabel().setText(LanguageManager.getString("status.scanningCheats"));
				log.append(LanguageManager.getString("proc.step3")).append("\n");
				System.out.println("[AUTOPROC] Step 3: Cheat scan");

				// Close current file to clear old obfuscated type loaders
				if (open) {
					closeFile();
				}

				JarFile scanJf = new JarFile(jarToLoad);

				// Add only the deobfuscated JAR

				List<CheatDetector.DetectionResult> cheatResults = new ArrayList<>();
				try {
					cheatResults = CheatDetector.scanJar(
							jarToLoad.getAbsolutePath(), scanJf, decompilerConfig, null, null, true);
				} catch (Exception e) {
					log.append(LanguageManager.getString("proc.cheatError"))
							.append(' ').append(e.getMessage()).append("\n");
				}
				scanJf.close();

				int highCount = 0, medCount = 0, lowCount = 0;
				for (CheatDetector.DetectionResult r : cheatResults) {
					if (r.risk == CheatDetector.RiskLevel.HIGH) highCount++;
					else if (r.risk == CheatDetector.RiskLevel.MEDIUM) medCount++;
					else lowCount++;
				}

				final int finalHighCount = highCount;
				final int finalCheatCount = cheatResults.size();
				final boolean hasCheats = !cheatResults.isEmpty();

				log.append(LanguageManager.getString("proc.cheatResults")
						.replace("{0}", String.valueOf(cheatResults.size()))).append("\n");
				log.append(LanguageManager.getString("proc.cheatCounts")
						.replace("{0}", String.valueOf(highCount))
						.replace("{1}", String.valueOf(medCount))
						.replace("{2}", String.valueOf(lowCount))).append("\n\n");

				if (!cheatResults.isEmpty()) {
					log.append(LanguageManager.getString("proc.cheatDetails")).append("\n");
					for (CheatDetector.DetectionResult r : cheatResults) {
						log.append(String.format("[%s] %s:%d  %s='%s'\n",
								riskLabel(r.risk), r.className, r.lineNumber,
								LanguageManager.getString("detection.column.keyword"), r.keyword));
						if (r.lineContent != null && !r.lineContent.trim().isEmpty()) {
							log.append("    ").append(r.lineContent.trim()).append("\n");
						}
					}
				}

				System.out.println("[AUTOPROC] Cheat scan: " + cheatResults.size() + " findings");

				// Step 4: Load result JAR
				log.append(LanguageManager.getString("proc.step4")).append("\n");
				log.append(LanguageManager.getString("proc.loading")).append(' ')
						.append(jarToLoad.getAbsolutePath()).append("\n");
				System.out.println("[AUTOPROC] Step 4: Loading " + jarToLoad.getAbsolutePath());

				final String finalLog = log.toString();
				SwingUtilities.invokeLater(() -> {
					show(LanguageManager.getString("tab.processLog"), finalLog);
					getLabel().setText(LanguageManager.getString("status.complete"));
					autoProcessSkip = true;
					loadFile(jarToLoad);

					// Show summary dialog
					String msg = LanguageManager.getString("process.complete.msg");
					msg += LanguageManager.getString("process.decrypt") + " "
							+ LanguageManager.getString(wasEncrypted
									? "process.done" : "process.notEncrypted") + "\n";
					msg += LanguageManager.getString("process.deobf") + " "
							+ LanguageManager.getString(wasObfuscated
									? "process.done" : "process.notObfuscated") + "\n";
					msg += LanguageManager.getString("process.cheatScan") + " " + finalCheatCount + " " + LanguageManager.getString("process.found");
					if (finalHighCount > 0) msg += " (" + finalHighCount + " " + LanguageManager.getString("detection.risk.high").toUpperCase() + "!)";
					msg += "\n";

					// Findings are reported in the dialog below and in the Tarama tab.

					javax.swing.JOptionPane.showMessageDialog(Model.this, msg,
							LanguageManager.getString("process.complete.title"), javax.swing.JOptionPane.INFORMATION_MESSAGE);
				});

			} catch (Exception e) {
				System.err.println("[AUTOPROC] Cheat scan error: " + e);
				SwingUtilities.invokeLater(() -> {
					getLabel().setText(LanguageManager.getString("status.cheatScanError") + " " + e.getMessage());
				});
			}
		}).start();
	}

	/** Risk level as the user's language spells it, not the enum's name. */
	private static String riskLabel(CheatDetector.RiskLevel risk) {
		switch (risk) {
			case HIGH:   return LanguageManager.getString("detection.risk.high");
			case MEDIUM: return LanguageManager.getString("detection.risk.medium");
			default:     return LanguageManager.getString("detection.risk.low");
		}
	}

	private String getSelectedClassPath(TreePath trp) {
		if (trp == null || trp.getPathCount() < 2) return null;
		StringBuilder path = new StringBuilder();
		for (int i = 1; i < trp.getPathCount(); i++) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) trp.getPathComponent(i);
			TreeNodeUserObject userObject = (TreeNodeUserObject) node.getUserObject();
			if (i < trp.getPathCount() - 1) {
				path.append(userObject.getOriginalName()).append("/");
			} else {
				path.append(userObject.getOriginalName());
			}
		}
		return path.toString();
	}

	private byte[] getClassBytes(String entryPath) {
		if (state == null || state.jarFile == null) return null;
		try {
			JarEntry entry = state.jarFile.getJarEntry(entryPath);
			if (entry == null) return null;
			try (InputStream in = state.jarFile.getInputStream(entry)) {
				java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
				byte[] buf = new byte[4096];
				int len;
				while ((len = in.read(buf)) > 0) {
					baos.write(buf, 0, len);
				}
				return baos.toByteArray();
			}
		} catch (Exception e) {
			return null;
		}
	}

	private File getDesktopDir() {
		String home = System.getProperty("user.home");
		File desktop = new File(home, "Desktop");
		if (!desktop.exists()) {
			desktop = new File(new File(home, "OneDrive"), "Desktop");
		}
		if (!desktop.exists()) {
			desktop = new File(new File(home, "OneDrive"), "Masaüstü");
		}
		if (!desktop.exists()) {
			desktop = new File(home);
		}
		return desktop;
	}

	private void extractEntryToDesktop(String entryPath) {
		if (state == null || state.jarFile == null) {
			SwingUtilities.invokeLater(() -> {
				JOptionPane.showMessageDialog(Model.this, "No JAR file loaded.",
						"Extract", JOptionPane.WARNING_MESSAGE);
			});
			return;
		}

		try {
			JarEntry entry = state.jarFile.getJarEntry(entryPath);
			if (entry == null) {
				SwingUtilities.invokeLater(() -> {
					JOptionPane.showMessageDialog(Model.this, "Entry not found: " + entryPath,
							"Extract", JOptionPane.ERROR_MESSAGE);
				});
				return;
			}

			String fileName = entryPath;
			int lastSlash = fileName.lastIndexOf('/');
			if (lastSlash >= 0) fileName = fileName.substring(lastSlash + 1);

			File desktopDir = getDesktopDir();
			File outFile = new File(desktopDir, fileName);

			if (outFile.exists()) {
				int choice = JOptionPane.showConfirmDialog(Model.this,
						"File already exists on Desktop:\n" + fileName + "\n\nOverwrite?",
						"Extract to Desktop", JOptionPane.YES_NO_OPTION);
				if (choice != JOptionPane.YES_OPTION) return;
			}

			try (InputStream in = state.jarFile.getInputStream(entry);
					FileOutputStream out = new FileOutputStream(outFile)) {
				byte[] buf = new byte[8192];
				int len;
				while ((len = in.read(buf)) > 0) {
					out.write(buf, 0, len);
				}
			}

			final File finalOutFile = outFile;
			SwingUtilities.invokeLater(() -> {
				getLabel().setText("Extracted: " + finalOutFile.getAbsolutePath());
				JOptionPane.showMessageDialog(Model.this,
						"Extracted to Desktop:\n" + finalOutFile.getName() + "\n(" + finalOutFile.length() + " bytes)",
						"Extract Complete", JOptionPane.INFORMATION_MESSAGE);
			});

		} catch (Exception e) {
			SwingUtilities.invokeLater(() -> {
				JOptionPane.showMessageDialog(Model.this, "Extract failed: " + e.getMessage(),
						"Extract Error", JOptionPane.ERROR_MESSAGE);
			});
		}
	}

	private void extractAllToDesktop() {
		if (state == null || state.jarFile == null) {
			SwingUtilities.invokeLater(() -> {
				JOptionPane.showMessageDialog(Model.this, "No JAR file loaded.",
						"Extract All", JOptionPane.WARNING_MESSAGE);
			});
			return;
		}

		try {
			String jarBaseName = file.getName().replaceAll("\\.(jar|zip)$", "");
			File desktopDir = getDesktopDir();
			File outDir = new File(desktopDir, jarBaseName + "_extracted");

			if (outDir.exists()) {
				int choice = JOptionPane.showConfirmDialog(Model.this,
						"Folder already exists on Desktop:\n" + outDir.getName() + "\n\nOverwrite?",
						"Extract All to Desktop", JOptionPane.YES_NO_OPTION);
				if (choice != JOptionPane.YES_OPTION) return;
			} else {
				outDir.mkdirs();
			}

			Enumeration<JarEntry> entries = state.jarFile.entries();
			int count = 0;
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				if (entry.isDirectory()) continue;

				String entryName = entry.getName();
				File outFile = new File(outDir, entryName.replace("/", File.separator));

				File parent = outFile.getParentFile();
				if (parent != null && !parent.exists()) parent.mkdirs();

				try (InputStream in = state.jarFile.getInputStream(entry);
						FileOutputStream out = new FileOutputStream(outFile)) {
					byte[] buf = new byte[8192];
					int len;
					while ((len = in.read(buf)) > 0) {
						out.write(buf, 0, len);
					}
				}
				count++;
			}

			final int finalCount = count;
			final File finalOutDir = outDir;
			SwingUtilities.invokeLater(() -> {
				getLabel().setText("Extracted " + finalCount + " files to " + finalOutDir.getAbsolutePath());
				JOptionPane.showMessageDialog(Model.this,
						"Extracted " + finalCount + " files to:\n" + finalOutDir.getAbsolutePath(),
						"Extract All Complete", JOptionPane.INFORMATION_MESSAGE);
			});

		} catch (Exception e) {
			SwingUtilities.invokeLater(() -> {
				JOptionPane.showMessageDialog(Model.this, "Extract All failed: " + e.getMessage(),
						"Extract All Error", JOptionPane.ERROR_MESSAGE);
			});
		}
	}

	private void runDeobfuscation() {
		if (file == null) return;

		StringBuilder log = new StringBuilder();

		new Thread(() -> {
			File result = Deobfuscator.deobfuscate(file, new Deobfuscator.DeobfuscationCallback() {
				@Override
				public void onProgress(String stage, String msg) {
					log.append("[").append(stage).append("] ").append(msg).append("\n");
					SwingUtilities.invokeLater(() -> {
						show(LanguageManager.getString("tab.deobfLog"), log.toString());
					});
				}

				@Override
				public void onComplete(File deobfuscatedJar, ObfuscationDetector.DetectionResult detection) {
					log.append("\n=== DEOBFUSCATION COMPLETE ===\n");
					log.append("Output: ").append(deobfuscatedJar.getAbsolutePath()).append("\n");
					log.append("Obfuscator: ").append(detection.type.getDisplayName()).append("\n");
					SwingUtilities.invokeLater(() -> {
						show(LanguageManager.getString("tab.deobfLog"), log.toString());
						int choice = javax.swing.JOptionPane.showConfirmDialog(
								Model.this,
								LanguageManager.getString("deobf.analysis.openResult"),
								LanguageManager.getString("deobf.analysis.complete"),
								javax.swing.JOptionPane.YES_NO_OPTION);
						if (choice == javax.swing.JOptionPane.YES_OPTION) {
							loadFile(deobfuscatedJar);
						}
					});
				}

				@Override
				public void onError(String message, Exception e) {
					log.append("\n" + LanguageManager.getString("log.error") + " ").append(message).append("\n");
					SwingUtilities.invokeLater(() -> {
						show(LanguageManager.getString("tab.deobfLog"), log.toString());
					});
				}
			});
		}).start();
	}

	private void runDeobfuscateSelected(TreePath trp) {
		String entryPath = getSelectedClassPath(trp);
		if (entryPath == null) {
			javax.swing.JOptionPane.showMessageDialog(Model.this, LanguageManager.getString("dialog.selectFileFirst"));
			return;
		}

		new Thread(() -> {
			byte[] classBytes = getClassBytes(entryPath);
			if (classBytes == null) {
				SwingUtilities.invokeLater(() -> {
					javax.swing.JOptionPane.showMessageDialog(Model.this, LanguageManager.getString("dialog.failedToRead") + " " + entryPath);
				});
				return;
			}

			String fileNameTemp = entryPath;
			int lastSlash = fileNameTemp.lastIndexOf('/');
			if (lastSlash >= 0) fileNameTemp = fileNameTemp.substring(lastSlash + 1);
			final String fileName = fileNameTemp;

			boolean isClass = (entryPath.endsWith(".class") ||
					(classBytes.length >= 4 && classBytes[0] == (byte) 0xCA && classBytes[1] == (byte) 0xFE
							&& classBytes[2] == (byte) 0xBA && classBytes[3] == (byte) 0xBE));

			if (!isClass) {
				StringBuilder msg = new StringBuilder();
				msg.append("File: ").append(fileName).append("\n");
				msg.append("Size: ").append(classBytes.length).append(" bytes\n\n");
				msg.append("This file is NOT a class file (no CAFEBABE magic).\n");
				msg.append("It may be encrypted or a resource file.\n\n");
				msg.append("First 16 bytes: ");
				for (int i = 0; i < Math.min(16, classBytes.length); i++) {
					msg.append(String.format("%02X ", classBytes[i]));
				}
				msg.append("\n\n");

				byte[] decrypted = tryXorBruteForce(classBytes);
				if (decrypted != null) {
					msg.append("XOR key found: 0x").append(String.format("%02X", decrypted[classBytes.length])).append("\n");
					msg.append("Decrypted file has CAFEBABE magic!\n\n");

					byte[] deobfBytes = deobfuscateClassBytes(decrypted);
					if (deobfBytes != null) {
						String hexDump = bytesToHexDump(deobfBytes);
						SwingUtilities.invokeLater(() -> {
							show(fileName + " (XOR decrypted + deobf)", hexDump);
						});
						return;
					}
					String hexDump = bytesToHexDump(decrypted);
					SwingUtilities.invokeLater(() -> {
						show(fileName + " (XOR decrypted)", hexDump);
					});
					return;
				}

				msg.append("No simple XOR key found. Attempting advanced decryption...\n");

				byte[] advDecrypted = tryAdvancedDecryption(entryPath, classBytes, msg);
				if (advDecrypted != null) {
					msg.append("\nAdvanced decryption successful!\n");
					msg.append("Decrypted size: ").append(advDecrypted.length).append(" bytes\n");
					msg.append("First 16 bytes: ");
					for (int i = 0; i < Math.min(16, advDecrypted.length); i++) {
						msg.append(String.format("%02X ", advDecrypted[i]));
					}
					String hexDump = bytesToHexDump(advDecrypted);
					SwingUtilities.invokeLater(() -> {
						show(fileName + " (decrypted)", msg.toString() + "\n\n" + hexDump);
					});
					return;
				}

				msg.append("Advanced decryption failed. Showing raw hex dump.\n");
				String hexDump = bytesToHexDump(classBytes);
				SwingUtilities.invokeLater(() -> {
					show(fileName + " (encrypted - hex)", msg.toString() + "\n\n" + hexDump);
				});
				return;
			}

			getLabel().setText(LanguageManager.getString("status.deobfuscatingFile") + " " + fileName);
			byte[] deobfBytes = deobfuscateClassBytes(classBytes);
			if (deobfBytes != null) {
				String hexDump = bytesToHexDump(deobfBytes);
				SwingUtilities.invokeLater(() -> {
					show(fileName + " (deobf)", hexDump);
					getLabel().setText(LanguageManager.getString("status.deobfuscatedFile") + " " + fileName);
				});
			} else {
				String hexDump = bytesToHexDump(classBytes);
				SwingUtilities.invokeLater(() -> {
					show(fileName + " (deobf failed - raw hex)", hexDump);
					getLabel().setText(LanguageManager.getString("status.deobfFailed") + " " + fileName);
				});
			}
		}).start();
	}

	private byte[] tryXorBruteForce(byte[] data) {
		if (data.length < 4) return null;
		for (int key = 1; key < 256; key++) {
			if ((data[0] ^ key) == (byte) 0xCA &&
					(data[1] ^ key) == (byte) 0xFE &&
					(data[2] ^ key) == (byte) 0xBA &&
					(data[3] ^ key) == (byte) 0xBE) {
				byte[] result = new byte[data.length + 1];
				for (int i = 0; i < data.length; i++) {
					result[i] = (byte) (data[i] ^ key);
				}
				result[data.length] = (byte) key;
				return result;
			}
		}
		return null;
	}

	private byte[] tryAdvancedDecryption(String entryPath, byte[] rawBytes, StringBuilder log) {
		try {
			if (state == null || state.jarFile == null) return null;
			File jarFile = state.file;

			log.append("Trying path-based XOR decryption...\n");

			String resourcePath = "/" + entryPath;
			byte[] xorKey = deriveXorKeyFromPath(resourcePath);
			if (xorKey == null) {
				log.append("  Failed to derive XOR key from path.\n");
				return null;
			}

			log.append("  Derived 8-byte XOR key: ");
			for (byte b : xorKey) log.append(String.format("%02X ", b & 0xFF));
			log.append("\n");

			byte[] xorDecrypted = new byte[rawBytes.length];
			for (int i = 0; i < rawBytes.length; i++) {
				xorDecrypted[i] = (byte) (rawBytes[i] ^ xorKey[i % 8]);
			}

			boolean isContainer = xorDecrypted.length >= 5
					&& xorDecrypted[0] == 0x5D
					&& xorDecrypted[1] == 0x00
					&& xorDecrypted[2] == 0x00
					&& xorDecrypted[3] == 0x00
					&& xorDecrypted[4] == 0x04;

			if (rawBytes[0] == 0x5D && rawBytes[1] == 0x00
					&& rawBytes[2] == 0x00 && rawBytes[3] == 0x00 && rawBytes[4] == 0x04) {
				log.append("  File is already in container format (no XOR needed).\n");
				xorDecrypted = rawBytes;
				isContainer = true;
			}

			if (!isContainer) {
				log.append("  After XOR: not container format (5D 00 00 00 04). Path-based XOR failed.\n");
				return null;
			}

			log.append("  Container format detected! Trying cipher decryption via JAR class loader...\n");

			byte[] cipherDecrypted = tryCipherDecryption(jarFile, xorDecrypted, log);
			if (cipherDecrypted != null) {
				return cipherDecrypted;
			}

			log.append("  Cipher decryption failed, returning XOR-decrypted container.\n");
			return xorDecrypted;
		} catch (Exception e) {
			log.append("  Advanced decryption error: ").append(e.getMessage()).append("\n");
			return null;
		}
	}

	private static byte[] deriveXorKeyFromPath(String path) {
		try {
			byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
			long hash = -4953706369002393500L;
			long mult = 7664345821815920749L;
			for (byte b : pathBytes) {
				hash = hash * mult ^ pathHashTable[b & 0xFF];
			}
			byte[] key = new byte[8];
			long val = hash;
			for (int i = 7; i >= 0; i--) {
				key[i] = (byte) (val & 0xFF);
				val >>= 8;
			}
			return key;
		} catch (Exception e) {
			return null;
		}
	}

	private static final long[] pathHashTable = {
		-3480515477827284314L,-3307358911275897274L,2262014355238238183L,-6768715312815710428L,-1361935169926033423L,-329230681982008839L,-3440304206788337213L,2867858071806528111L,
		-8596772195713692333L,-8976692496864135163L,4591629993832563184L,7770547292939826974L,8648074652193402690L,7989906104122808095L,7833474959911929480L,-3236301672554511903L,
		5692742246141660865L,-5186809798123896852L,-3143318607907665191L,-2426078442329855404L,-3746832036013371997L,1998435151822477392L,-670025558597441761L,-1250784308060119782L,
		1771464809674919995L,1910884771278601512L,370566924444113725L,-6863640461266585432L,8377643218524972116L,4924429569076085374L,47583700571916822L,-4545470526160126167L,
		8395968838571341375L,-5022915209227679816L,-4084671909599045616L,-2755734677672249486L,2949410485976134594L,-4696113903351293696L,-4793098847497190830L,3274809889905678903L,
		-573276321285810930L,-2320028162224687701L,7549374284762044030L,-6833225243513010636L,-7615600885679970609L,-6411552413921715013L,-1003302319195411276L,-5248824436507568263L,
		-8246856280812544388L,-1241148451251718771L,-3122779499083438280L,-1689995964891925724L,1631615095491599441L,8010434746464252822L,1010664603686173607L,-351491500717746338L,
		-1487102970799145042L,8094607092924976823L,-8679127727935041269L,3944070590698097824L,-8075236462730314448L,-3120055265415286864L,7110415066501411298L,7730486204346052600L,
		-3733669536466097934L,-5525281948038127700L,-2664264206677000827L,-8226717907549575671L,-6053405031854430469L,2611884437997545198L,-8883806921176195773L,-5464132922437201029L,
		6114232650842024585L,-7110961761650278764L,5499490877457599072L,-8194495940663107147L,-630633481968614694L,-8665908672003527587L,-6105071496752242620L,1436192807487635024L,
		-2076647606073954255L,5968026341841934143L,4938642129126233212L,-8175880615780701581L,-2264130582333703029L,-821849254730529651L,-1492763349733573986L,-3420450311071321836L,
		8714691308830070484L,-5072184612544497637L,6063645121644628526L,3871329301211779646L,-93459444024257970L,-6267322802559012045L,-48495887231403723L,103806064002401577L,
		-8258382889448368916L,4364497966305475062L,8187232884581007326L,5112856389069587259L,-3695972166753054001L,-925420352653443722L,-502437504084969331L,8535384488422134537L,
		8797269199380259979L,-6589054335629657478L,-965483979892245935L,-6848337635005874081L,4466361303615168174L,1016010164410668383L,6502427480846791419L,4523409138600186326L,
		4782256234291794626L,1387062054421315546L,-7323235810728624751L,8373375858658632306L,3215435465907060030L,1578915207441810671L,-780158064672387510L,-5258954611293607201L,
		-1476331197317369267L,3031109076515099231L,-6513029593008994513L,5238343839092047854L,179764997137220255L,-4142804025283056092L,-2328223599069799599L,5390572148285821844L,
		-3508138558213461729L,7401316701244379557L,3188176410935613991L,2613096410086511181L,-1210330987478954241L,-262665717720507328L,-7924236570179611405L,-8501180480958139534L,
		2286898453697603562L,-7296189493579172643L,-1505697375801193187L,8866282940483413083L,3462491682569765449L,-7719674551353089811L,-2161194498650812514L,-9142084895327164217L,
		2912269426253424631L,-2094038580941862293L,7603300406230694745L,-2156232326582767390L,-8595368888295842804L,7038089351650850263L,5385857580712720222L,3159223839514813084L,
		7449395206321399379L,-5553026780323764608L,-1945192731452579623L,4306813996256725841L,1921134291979451637L,-2948440929853472943L,8039017109570446028L,-6306409939402452116L,
		6033142984240109625L,-1529461589995132384L,9212007006731649032L,6073776529800772667L,-6653500679927351123L,2851888106878263839L,3064555868169304787L,-8789684711242970580L,
		-6636024529547316901L,-1696268702754803932L,7962120236423073014L,6310294755067445108L,6952956091616363864L,-1257969866064956051L,-3230197009330043090L,466051326882664234L,
		-8990502422291877759L,3511884079593940586L,-673172333283142235L,-8387973872236573825L,5801748266428596550L,-765473790636724417L,2299791812217240052L,-3982099247510642510L,
		8363167232250933010L,-284867118239866829L,-695673455101359417L,1337057017176526006L,1928674199725149542L,3027969140523417273L,134390639076266432L,5178326254108383635L,
		305200236323501870L,-1916420834436969918L,-3628730979870316789L,5111373756676551019L,-7577261024546646812L,3340920341289787690L,-9031099925207558717L,8359247114012761102L,
		7628748628125057437L,1127242291666912707L,-6322263984570680122L,6003284039635515681L,-3032099228994682726L,-1884700960914865879L,-5788904642192910968L,2854932081746735331L,
		-7428071783526009222L,8573670888428201568L,5810073386670273709L,-7519873288890813123L,2175631643081917093L,989012021910301725L,6994713847597884117L,3765307143604171584L,
		7080051105120987513L,-3224276316720215554L,-6536903602631015789L,-7464153181488350413L,872865169683943692L,4812315419915240848L,-7686482479531469484L,6628482486149420513L,
		-6200169312732968583L,1423783026527937547L,7129518922975669267L,7533504780256217948L,3650372496225600680L,6463552836629620157L,7833479444823382622L,3693238099526532399L,
		2068290963696667194L,-7369914207768820440L,-2139560453621866945L,-3862094089889838238L,-3281240462630792858L,1322452504935018966L,6185189471196572406L,-6894816618483581829L,
		-4395696721694463508L,3296715924975286578L,3121401685695772513L,-7749132016723285999L,7574420387761718216L,8628303115545545892L,3016699154499719643L,-3843394535643880234L,
		4055081606798156314L,4137556512058409676L,-7563373001546752990L,-4014144882173344324L,-2212638506836738290L,3602957959237188066L,-3269164780412519188L,-6136318789233837323L
	};

	private static byte[] tryCipherDecryption(File jarFile, byte[] containerData, StringBuilder log) {
		try {
			URL[] urls = {jarFile.toURI().toURL()};
			JarFile jf = new JarFile(jarFile);
			URLClassLoader ucl = new URLClassLoader(urls, Model.class.getClassLoader()) {
				@Override
				public InputStream getResourceAsStream(String name) {
					try {
						if (name.startsWith("/")) name = name.substring(1);
						JarEntry entry = jf.getJarEntry(name);
						if (entry == null) return null;
						return jf.getInputStream(entry);
					} catch (Exception e) {
						return null;
					}
				}
			};

			Class<?> lClass = ucl.loadClass("net.java.l");
			Class<?> mClass = ucl.loadClass("net.java.m");

			Method m_a = mClass.getDeclaredMethod("a");
			m_a.setAccessible(true);
			m_a.invoke(null);

			Method l_b_noargs = null;
			for (Method m : lClass.getDeclaredMethods()) {
				if (m.getName().equals("b") && m.getParameterTypes().length == 0
						&& m.getReturnType() == byte[].class) {
					l_b_noargs = m;
					break;
				}
			}
			if (l_b_noargs != null) {
				l_b_noargs.setAccessible(true);
				try { l_b_noargs.invoke(null); } catch (Throwable ignored) {}
			}

			Method l_a_noargs = null;
			for (Method m : lClass.getDeclaredMethods()) {
				if (m.getName().equals("a") && m.getParameterTypes().length == 0
						&& m.getReturnType() == byte[].class) {
					l_a_noargs = m;
					break;
				}
			}
			if (l_a_noargs != null) {
				l_a_noargs.setAccessible(true);
				try { l_a_noargs.invoke(null); } catch (Throwable ignored) {}
			}

			Method l_b_bytes = null;
			for (Method m : lClass.getDeclaredMethods()) {
				if (m.getName().equals("b") && m.getParameterTypes().length == 1
						&& m.getParameterTypes()[0] == byte[].class) {
					l_b_bytes = m;
					break;
				}
			}
			if (l_b_bytes == null) {
				log.append("  l.b(byte[]) method not found.\n");
				ucl.close();
				jf.close();
				return null;
			}
			l_b_bytes.setAccessible(true);

			byte[] decrypted = (byte[]) l_b_bytes.invoke(null, containerData);
			ucl.close();
			jf.close();

			if (decrypted == null) {
				log.append("  l.b(byte[]) returned null.\n");
				return null;
			}

			log.append("  Cipher decryption successful! Size: ").append(decrypted.length).append("\n");
			return decrypted;
		} catch (Exception e) {
			log.append("  Cipher decryption error: ").append(e.getMessage()).append("\n");
			return null;
		}
	}

	private File decryptAllToTempJar(StringBuilder log) {
		if (state == null || state.jarFile == null) return null;
		return decryptJarStatic(state.file, state.jarFile, log);
	}

	public static File decryptJarStatic(File jarFile, JarFile jf, StringBuilder log) {
		try {
			Map<String, byte[]> allDecrypted = new LinkedHashMap<>();
			int encryptedCount = 0;

			java.util.Enumeration<JarEntry> entries = jf.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				String name = entry.getName();
				if (entry.isDirectory()) continue;
				if (name.endsWith(".class")) continue;
				if (name.equals("META-INF/MANIFEST.MF") || name.startsWith("META-INF/")) continue;

				InputStream in = jf.getInputStream(entry);
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				byte[] buf = new byte[4096];
				int len;
				while ((len = in.read(buf)) > 0) baos.write(buf, 0, len);
				in.close();
				byte[] rawBytes = baos.toByteArray();

				boolean isClass = rawBytes.length >= 4
						&& rawBytes[0] == (byte) 0xCA && rawBytes[1] == (byte) 0xFE
						&& rawBytes[2] == (byte) 0xBA && rawBytes[3] == (byte) 0xBE;

				if (isClass) continue;

				boolean isContainer = rawBytes.length >= 5
						&& rawBytes[0] == 0x5D && rawBytes[1] == 0x00
						&& rawBytes[2] == 0x00 && rawBytes[3] == 0x00 && rawBytes[4] == 0x04;

				if (!isContainer && rawBytes.length >= 16) {
					byte[] xorKey = deriveXorKeyFromPath("/" + name);
					if (xorKey != null) {
						byte[] test = new byte[5];
						for (int i = 0; i < 5; i++) test[i] = (byte) (rawBytes[i] ^ xorKey[i % 8]);
						if (test[0] == 0x5D && test[1] == 0x00 && test[2] == 0x00 && test[3] == 0x00 && test[4] == 0x04) {
							isContainer = true;
							byte[] xored = new byte[rawBytes.length];
							for (int i = 0; i < rawBytes.length; i++) xored[i] = (byte) (rawBytes[i] ^ xorKey[i % 8]);
							rawBytes = xored;
						}
					}
				}

				if (!isContainer) continue;

				encryptedCount++;
				log.append("Found encrypted file: ").append(name).append(" (").append(rawBytes.length).append(" bytes)\n");

				StringBuilder subLog = new StringBuilder();
				byte[] decrypted = tryCipherDecryption(jarFile, rawBytes, subLog);
				if (decrypted == null) {
					log.append("  Decryption failed.\n\n");
					continue;
				}

				log.append("  Decrypted: ").append(decrypted.length).append(" bytes\n");

				try {
					DataInputStream dis = new DataInputStream(new ByteArrayInputStream(decrypted));
					int classCount = 0;
					while (dis.available() > 0) {
						String className = dis.readUTF();
						int classSize = dis.readInt();
						byte[] classBytes = new byte[classSize];
						dis.readFully(classBytes);
						allDecrypted.put(className, classBytes);
						classCount++;
					}
					log.append("  Extracted ").append(classCount).append(" classes\n\n");
				} catch (Exception ex) {
					try {
						int pos = 0;
						int count = 0;
						while (pos < decrypted.length) {
							int nameLen = decrypted[pos] & 0xFF;
							pos++;
							if (nameLen == 0 || pos + nameLen > decrypted.length) break;
							String className = new String(decrypted, pos, nameLen, StandardCharsets.UTF_8);
							pos += nameLen;
							if (pos + 4 > decrypted.length) break;
							int size = ((decrypted[pos] & 0xFF) << 24) | ((decrypted[pos+1] & 0xFF) << 16)
									| ((decrypted[pos+2] & 0xFF) << 8) | (decrypted[pos+3] & 0xFF);
							pos += 4;
							if (size < 0 || pos + size > decrypted.length) break;
							byte[] classBytes = new byte[size];
							System.arraycopy(decrypted, pos, classBytes, 0, size);
							pos += size;
							allDecrypted.put(className, classBytes);
							count++;
						}
						log.append("  Extracted ").append(count).append(" entries (custom format)\n\n");
					} catch (Exception ex2) {
						log.append("  Container parse failed: ").append(ex2.getMessage()).append("\n\n");
					}
				}
			}

			if (encryptedCount == 0) {
				log.append(LanguageManager.getString("proc.noEncryptedFound")).append("\n");
				return null;
			}

			log.append("\nTotal decrypted classes: ").append(allDecrypted.size()).append("\n");

			File tempDir = new File(System.getProperty("java.io.tmpdir"), "jaranalyzer_deobf");
			if (!tempDir.exists()) tempDir.mkdirs();
			String baseName = jarFile.getName().replaceAll("\\.(jar|zip)$", "");
			File outFile = new File(tempDir, baseName + "_decrypted.jar");

			ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outFile));

			for (Map.Entry<String, byte[]> e : allDecrypted.entrySet()) {
				String className = e.getKey();
				String entryName = className.replace("\\", "/").replace(".", "/") + ".class";
				if (!entryName.endsWith(".class")) entryName = className + ".class";
				zos.putNextEntry(new ZipEntry(entryName));
				zos.write(e.getValue());
				zos.closeEntry();
			}

			Enumeration<JarEntry> origEntries = jf.entries();
			while (origEntries.hasMoreElements()) {
				JarEntry entry = origEntries.nextElement();
				if (entry.isDirectory()) continue;
				String entryName = entry.getName();
				if (allDecrypted.containsKey(entryName.replace("/", "."))) continue;
				// Copy ALL files from original JAR (not just .class)
				if (!entryName.startsWith("net/java/")) {
					InputStream in = jf.getInputStream(entry);
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					byte[] buf = new byte[4096];
					int len;
					while ((len = in.read(buf)) > 0) baos.write(buf, 0, len);
					in.close();
					zos.putNextEntry(new ZipEntry(entryName));
					zos.write(baos.toByteArray());
					zos.closeEntry();
				}
			}

			zos.close();
			log.append("Saved to: ").append(outFile.getAbsolutePath()).append("\n");
			return outFile;
		} catch (Exception e) {
			log.append("ERROR: ").append(e.getMessage()).append("\n");
			return null;
		}
	}

	private void runHexView(TreePath trp) {
		String entryPath = getSelectedClassPath(trp);
		if (entryPath == null) return;

		new Thread(() -> {
			byte[] bytes = getClassBytes(entryPath);
			if (bytes == null) {
				SwingUtilities.invokeLater(() -> {
					show(LanguageManager.getString("dialog.failedToRead") + " " + entryPath, LanguageManager.getString("dialog.failedToReadFile"));
				});
				return;
			}
			String hexDump = bytesToHexDump(bytes);
			String fileNameTemp = entryPath;
			int lastSlash = fileNameTemp.lastIndexOf('/');
			if (lastSlash >= 0) fileNameTemp = fileNameTemp.substring(lastSlash + 1);
			final String fileName = fileNameTemp;
			SwingUtilities.invokeLater(() -> {
				show("Hex: " + fileName, hexDump);
			});
		}).start();
	}

	private void runCheatScanAll() {
		if (state == null || state.jarFile == null) {
			javax.swing.JOptionPane.showMessageDialog(Model.this, LanguageManager.getString("dialog.loadJarFirst"));
			return;
		}

		new Thread(() -> {
			StringBuilder report = new StringBuilder();
			report.append(LanguageManager.getString("cheatscan.header"));

			String[] cheatKeywords = {
				"killaura", "aimbot", "fly", "speed", "noclip", "godmode",
				"reach", "forceop", "op", "bypass", "hack", "cheat",
				"exploit", "inject", "hook", "modify", "packet",
				"sendchat", "sethealth", "setspeed", "setflyable",
				"damage", "attack", "target", "entity", "player",
				"movement", "velocity", "position", "teleport",
				"xray", "esp", "tracers", "scaffold", "autoclick", "clicker",
				"fastplace", "nuker", "criticals", "antiknockback", "sneak",
				"fastbow", "rapidfire", "projectile", "prediction", "rotation",
				"silent", "backtrack", "timer", "tickspeed", "fastbreak",
				"autobuild", "autoeat", "autofish", "autopot", "autorun",
				"reach", "hitbox", "triggerbot", "multiaura", "killloop",
				"setmotion", "setposition", "dispatchcommand", "getcommandsender",
				"deop", "ban", "kick", "unban", "pardon", "vanish",
				"freecam", "nohand", "nohurt", "antiafk", "afk", "macro",
				"baritone", "autowalk", "sprint", "step", "highjump",
				"longjump", "leap", "flyspeed", "speedhack", "timerhack"
			};

			int totalDetections = 0;
			int classesScanned = 0;
			int suspiciousClasses = 0;

			Enumeration<JarEntry> entries = state.jarFile.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				if (entry.isDirectory()) continue;
				if (!entry.getName().endsWith(".class")) continue;

				try {
					byte[] classBytes;
					try (InputStream in = state.jarFile.getInputStream(entry)) {
						java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
						byte[] buf = new byte[4096];
						int len;
						while ((len = in.read(buf)) > 0) baos.write(buf, 0, len);
						classBytes = baos.toByteArray();
					}

					if (classBytes.length < 4) continue;
					if (!(classBytes[0] == (byte) 0xCA && classBytes[1] == (byte) 0xFE
							&& classBytes[2] == (byte) 0xBA && classBytes[3] == (byte) 0xBE)) continue;

					classesScanned++;
					org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(classBytes);
					org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
					cr.accept(cn, org.objectweb.asm.ClassReader.SKIP_FRAMES);

					int classDetections = 0;
					StringBuilder classReport = new StringBuilder();

					for (Object o : cn.methods) {
						org.objectweb.asm.tree.MethodNode mn = (org.objectweb.asm.tree.MethodNode) o;
						if (mn.instructions == null) continue;

						for (int i = 0; i < mn.instructions.size(); i++) {
							org.objectweb.asm.tree.AbstractInsnNode insn = mn.instructions.get(i);
							if (insn.getType() == org.objectweb.asm.tree.AbstractInsnNode.LDC_INSN) {
								org.objectweb.asm.tree.LdcInsnNode ldc = (org.objectweb.asm.tree.LdcInsnNode) insn;
								if (ldc.cst instanceof String) {
									String str = ((String) ldc.cst).toLowerCase();
									for (String keyword : cheatKeywords) {
										if (str.contains(keyword)) {
											classReport.append("  [STRING] ").append(mn.name)
													.append(" | \"").append(ldc.cst).append("\"\n");
											classDetections++;
											break;
										}
									}
								}
							}
							if (insn.getType() == org.objectweb.asm.tree.AbstractInsnNode.METHOD_INSN) {
								org.objectweb.asm.tree.MethodInsnNode min = (org.objectweb.asm.tree.MethodInsnNode) insn;
								String methodName = min.name != null ? min.name.toLowerCase() : "";
								String owner = min.owner != null ? min.owner : "";
								for (String keyword : cheatKeywords) {
									if (methodName.contains(keyword)) {
										classReport.append("  [CALL] ").append(owner).append(".").append(min.name).append("\n");
										classDetections++;
										break;
									}
								}
							}
						}

						if (mn.name != null) {
							String mnLower = mn.name.toLowerCase();
							for (String keyword : cheatKeywords) {
								if (mnLower.contains(keyword)) {
									classReport.append("  [METHOD] ").append(mn.name).append("\n");
									classDetections++;
									break;
								}
							}
						}
					}

					if (classDetections > 0) {
						suspiciousClasses++;
						totalDetections += classDetections;
						report.append("\n--- ").append(cn.name).append(" (").append(classDetections).append(" hits) ---\n");
						report.append(classReport);
					}
				} catch (Exception e) {
					// skip
				}
			}

			report.append(LanguageManager.getString("cheatscan.summary"));
			report.append(LanguageManager.getString("cheatscan.classesScanned") + " ").append(classesScanned).append("\n");
			report.append(LanguageManager.getString("cheatscan.suspiciousClasses") + " ").append(suspiciousClasses).append("\n");
			report.append(LanguageManager.getString("cheatscan.totalDetections") + " ").append(totalDetections).append("\n");
			if (totalDetections > 20) {
				report.append(LanguageManager.getString("cheatscan.riskHigh") + "\n");
			} else if (totalDetections > 5) {
				report.append(LanguageManager.getString("cheatscan.riskMedium") + "\n");
			} else if (totalDetections > 0) {
				report.append(LanguageManager.getString("cheatscan.riskLow") + "\n");
			} else {
				report.append(LanguageManager.getString("cheatscan.riskClean") + "\n");
			}

			final String finalReport = report.toString();
			SwingUtilities.invokeLater(() -> {
				show(LanguageManager.getString("cheatscan.title"), finalReport);
			});
		}).start();
	}

	private byte[] deobfuscateClassBytes(byte[] classBytes) {
		if (classBytes == null || classBytes.length < 4) return null;
		try {
			org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(classBytes);
			org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
			cr.accept(cn, org.objectweb.asm.ClassReader.SKIP_FRAMES);

			System.out.println("[DEOBF] Class: " + cn.name + " methods: " + cn.methods.size() + " fields: " + cn.fields.size());

			StringDecryptor sd = new StringDecryptor(null);
			int stringDecryptCount = 0;
			for (Object o : cn.methods) {
				org.objectweb.asm.tree.MethodNode mn = (org.objectweb.asm.tree.MethodNode) o;
				if (mn.instructions == null) continue;
				try {
					if (sd.decryptMethodStringsStatic(mn)) stringDecryptCount++;
				} catch (Exception e) {
					System.err.println("[DEOBF] String decrypt error in " + mn.name + ": " + e);
				}
			}
			System.out.println("[DEOBF] Strings decrypted in " + stringDecryptCount + " methods");

			for (Object o : cn.methods) {
				org.objectweb.asm.tree.MethodNode mn = (org.objectweb.asm.tree.MethodNode) o;
				if (mn.instructions == null) continue;
				try {
					removeNopsAndSimplifyGotos(mn);
				} catch (Exception e) {
				}
			}

			org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
			cn.accept(cw);
			byte[] result = cw.toByteArray();
			System.out.println("[DEOBF] Output: " + result.length + " bytes (orig: " + classBytes.length + ")");
			return result;
		} catch (Exception e) {
			System.err.println("[DEOBF] Fatal error: " + e);
			e.printStackTrace();
			return null;
		}
	}

	private void removeNopsAndSimplifyGotos(org.objectweb.asm.tree.MethodNode mn) {
		org.objectweb.asm.tree.InsnList instructions = mn.instructions;
		for (int i = instructions.size() - 1; i >= 0; i--) {
			org.objectweb.asm.tree.AbstractInsnNode insn = instructions.get(i);
			if (insn.getOpcode() == org.objectweb.asm.Opcodes.NOP) {
				instructions.remove(insn);
			}
		}
	}

	private void buildTreeFromMass(List<String> mass) {
		if (appPrefs.isPackageExplorerStyle()) {
			buildFlatTreeFromMass(mass);
		} else {
			buildDirectoryTreeFromMass(mass);
		}
	}

	private void buildDirectoryTreeFromMass(List<String> mass) {
		TreeNodeUserObject topNodeUserObject = new TreeNodeUserObject(getName(file.getName()));
		DefaultMutableTreeNode top = new DefaultMutableTreeNode(topNodeUserObject);
		Collections.sort(mass, String.CASE_INSENSITIVE_ORDER);
		java.util.Map<String, DefaultMutableTreeNode> nodeCache = new java.util.HashMap<>();
		nodeCache.put("", top);
		for (String m : mass) {
			String[] parts = m.split("/");
			String currentPath = "";
			DefaultMutableTreeNode parent = top;
			for (int i = 0; i < parts.length; i++) {
				String part = parts[i];
				currentPath = currentPath.isEmpty() ? part : currentPath + "/" + part;
				DefaultMutableTreeNode child = nodeCache.get(currentPath);
				if (child == null) {
					child = new DefaultMutableTreeNode(new TreeNodeUserObject(part));
					parent.add(child);
					nodeCache.put(currentPath, child);
				}
				parent = child;
			}
		}
		tree.setModel(new DefaultTreeModel(top));
	}

	private void buildFlatTreeFromMass(List<String> mass) {
		TreeNodeUserObject topNodeUserObject = new TreeNodeUserObject(getName(file.getName()));
		DefaultMutableTreeNode top = new DefaultMutableTreeNode(topNodeUserObject);

		TreeMap<String, TreeSet<String>> packages = new TreeMap<>();
		HashSet<String> classContainingPackageRoots = new HashSet<>();

		Comparator<String> sortByFileExtensionsComparator = new Comparator<String>() {
			@Override
			public int compare(String o1, String o2) {
				int comp = o1.replaceAll("[^\\.]*\\.", "").compareTo(o2.replaceAll("[^\\.]*\\.", ""));
				if (comp != 0)
					return comp;
				return o1.compareTo(o2);
			}
		};

		for (String entry : mass) {
			String packagePath = "";
			String packageRoot = "";
			if (entry.contains("/")) {
				packagePath = entry.replaceAll("/[^/]*$", "");
				packageRoot = entry.replaceAll("/.*$", "");
			}
			String packageEntry = entry.replace(packagePath + "/", "");
			if (!packages.containsKey(packagePath)) {
				packages.put(packagePath, new TreeSet<String>(sortByFileExtensionsComparator));
			}
			packages.get(packagePath).add(packageEntry);
			if (!entry.startsWith("META-INF") && packageRoot.trim().length() > 0
					&& entry.matches(".*\\.(class|java|prop|properties)$")) {
				classContainingPackageRoots.add(packageRoot);
			}
		}

		for (String packagePath : packages.keySet()) {
			if (packagePath.startsWith("META-INF")) {
				List<String> packagePathElements = Arrays.asList(packagePath.split("/"));
				for (String entry : packages.get(packagePath)) {
					ArrayList<String> list = new ArrayList<>(packagePathElements);
					list.add(entry);
					loadNodesByNames(top, list);
				}
			}
		}

		for (String packagePath : packages.keySet()) {
			String packageRoot = packagePath.replaceAll("/.*$", "");
			if (classContainingPackageRoots.contains(packageRoot)) {
				for (String entry : packages.get(packagePath)) {
					ArrayList<TreeNodeUserObject> list = new ArrayList<>();
					list.add(new TreeNodeUserObject(packagePath, packagePath.replaceAll("/", ".")));
					list.add(new TreeNodeUserObject(entry));
					loadNodesByUserObj(top, list);
				}
			}
		}

		for (String packagePath : packages.keySet()) {
			String packageRoot = packagePath.replaceAll("/.*$", "");
			if (!classContainingPackageRoots.contains(packageRoot) && !packagePath.startsWith("META-INF")
					&& packagePath.length() > 0) {
				List<String> packagePathElements = Arrays.asList(packagePath.split("/"));
				for (String entry : packages.get(packagePath)) {
					ArrayList<String> list = new ArrayList<>(packagePathElements);
					list.add(entry);
					loadNodesByNames(top, list);
				}
			}
		}

		String packagePath = "";
		if (packages.containsKey(packagePath)) {
			for (String entry : packages.get(packagePath)) {
				ArrayList<String> list = new ArrayList<>();
				list.add(entry);
				loadNodesByNames(top, list);
			}
		}
		tree.setModel(new DefaultTreeModel(top));
	}

	public void closeFile() {
		for (OpenFile co : hmap) {
			int pos = house.indexOfTab(co.name);
			if (pos >= 0)
				house.remove(pos);
			co.close();
		}

		final State oldState = state;
		Model.this.state = null;
		if (oldState != null) {
			try { oldState.close(); } catch (Throwable ignored) {}
		}

		hmap.clear();
		tree.setModel(new DefaultTreeModel(null));

		currentJarFile = null;

		file = null;
		treeExpansionState = null;
		open = false;
	}

	public void changeTheme(String themeKey) {
		try {
			setThemeColors(ThemeManager.getTheme(themeKey));
			for (OpenFile f : hmap) {
				f.applyTheme(themeColors);
			}
		} catch (Exception e1) {
			JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e1);
		}
	}

	public void updateLanguage() {
		panel2.setBorder(BorderFactory.createTitledBorder(LanguageManager.getString("panel.structure")));
		panel.setBorder(BorderFactory.createTitledBorder(LanguageManager.getString("panel.code")));
		if (reprocessButton != null) {
			reprocessButton.setText(LanguageManager.getString("button.reprocess"));
		}
	}

	public void applyThemeToAllTabs() {
		if (themeColors != null) {
			for (OpenFile f : hmap) {
				f.applyTheme(themeColors);
			}
		}
	}

	public File getOpenedFile() {
		File openedFile = null;
		if (file != null && open) {
			openedFile = file;
		}
		if (openedFile == null) {
			getLabel().setText(LanguageManager.getString("status.noOpenFile"));
		}
		return openedFile;
	}

	public String getCurrentTabTitle() {
		String tabTitle = null;
		try {
			int pos = house.getSelectedIndex();
			if (pos >= 0) {
				tabTitle = house.getTitleAt(pos);
			}
		} catch (Exception e1) {
			JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e1);
		}
		if (tabTitle == null) {
			getLabel().setText(LanguageManager.getString("status.noOpenTab"));
		}
		return tabTitle;
	}

	public JTextPane getCurrentTextPane() {
		OpenFile open = getCurrentOpenFile();
		if (open == null) {
			getLabel().setText(LanguageManager.getString("status.noOpenTab"));
			return null;
		}
		return open.textPane;
	}

	public OpenFile getCurrentOpenFile() {
		// Identified by the tab's component, which wraps a search bar above the
		// code rather than being a bare JScrollPane.
		try {
			int pos = house.getSelectedIndex();
			if (pos < 0) return null;
			java.awt.Component co = house.getComponentAt(pos);
			for (OpenFile open : hmap) {
				if (co == open.component) return open;
			}
		} catch (Exception e1) {
			JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e1);
		}
		return null;
	}

	public void startWarmUpThread() {
		new Thread() {
			public void run() {
				try {
					Thread.sleep(500);
					String internalName = MainWindow.class.getName().replace(".", "/");
					String decompiledSource = CfrDecompiler.decompileFromBytes(
							MainWindow.class.getName().getBytes(), internalName, decompilerConfig);
					if (decompiledSource == null) return;
					OpenFile open = new OpenFile(internalName, "*/" + internalName, themeColors, mainWindow);
					open.setContent(decompiledSource);
					JTabbedPane pane = new JTabbedPane();
					pane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
					pane.addTab("title", open.component);
					pane.setSelectedIndex(pane.indexOfTab("title"));
				} catch (Exception e) {
					JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e);
				}
			}
		}.start();
	}

	public void onNavigationRequest(final String uniqueStr) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				if (uniqueStr == null)
					return;
				String[] linkParts = uniqueStr.split("\\|");
				if (linkParts.length <= 1)
					return;
				String destinationTypeStr = linkParts[1];
				try {
					bar.setVisible(true);
					getLabel().setText(LanguageManager.getString("status.navigating") + " " + destinationTypeStr.replaceAll("/", "."));

					String tabTitle = destinationTypeStr.replaceAll("/", ".");
					if (!tabTitle.endsWith(".class")) tabTitle = tabTitle + ".class";
					int lastDot = tabTitle.lastIndexOf(".");
					if (lastDot > 0) tabTitle = tabTitle.substring(lastDot + 1);
					extractClassToTextPane(destinationTypeStr, tabTitle, destinationTypeStr, uniqueStr);

					getLabel().setText(LanguageManager.getString("status.complete"));
				} catch (Exception e) {
					getLabel().setText(LanguageManager.getString("status.cannotNavigate") + " " + destinationTypeStr.replaceAll("/", "."));
					JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.cannotNavigate"), e);
				} finally {
					bar.setVisible(false);
				}
			}
		}).start();
	}

	public JLabel getLabel() {
		return label;
	}

	public void setLabel(JLabel label) {
		this.label = label;
	}

	public State getState() {
		return state;
	}

	public ThemeManager.ThemeColors getThemeColors() {
		return themeColors;
	}

	public void setThemeColors(ThemeManager.ThemeColors themeColors) {
		this.themeColors = themeColors;
	}

}
