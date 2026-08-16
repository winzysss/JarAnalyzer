package com.jaranalyzer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

public class OpenFile {

	public static final HashSet<String> WELL_KNOWN_TEXT_FILE_EXTENSIONS = new HashSet<>(
			Arrays.asList(".java", ".xml", ".rss", ".project", ".classpath", ".h", ".c", ".cpp", ".yaml", ".yml", ".ini", ".sql", ".js", ".php", ".php5",
					".phtml", ".html", ".htm", ".xhtm", ".xhtml", ".lua", ".bat", ".pl", ".sh", ".css", ".json", ".txt",
					".rb", ".make", ".mak", ".py", ".properties", ".prop", ".cfg", ".conf", ".toml", ".gradle", ".kts",
					".kt", ".scala", ".groovy", ".md", ".log", ".csv", ".tsv", ".lang", ".mf", ".manifest", ".version",
					".gitignore", ".dockerignore", ".env", ".tf", ".tfvars", ".xml", ".xsd", ".xsl", ".xslt", ".wsdl"));

	private volatile boolean isContentValid = false;
	private volatile Double lastScrollPercent = null;
	private byte[] rawBytes = null;

	MainWindow mainWindow;
	JScrollPane scrollPane;
	/** What actually goes in the tab: the search bar stacked over the code. */
	JPanel component;
	public CodeSearchBar searchBar;
	JTextPane textPane;
	String name;
	String path;

	private ConfigSaver configSaver;
	private AppPreferences appPrefs;

	private JarFile jarFile;
	private String classEntryName;
	private DecompilerConfig decompilerConfig;
	private boolean isClassFile = false;

	private ThemeManager.ThemeColors themeColors;
	private SimpleAttributeSet defaultAttr;
	private SimpleAttributeSet keywordAttr;
	private SimpleAttributeSet stringAttr;
	private SimpleAttributeSet commentAttr;
	private SimpleAttributeSet numberAttr;
	private SimpleAttributeSet typeAttr;
	private SimpleAttributeSet lineNumberAttr;

	private static final Set<String> KEYWORDS = new HashSet<>();
	static {
		String[] kw = { "abstract", "assert", "boolean", "break", "byte", "case", "catch",
				"char", "class", "const", "continue", "default", "do", "double", "else",
				"enum", "extends", "final", "finally", "float", "for", "goto", "if",
				"implements", "import", "instanceof", "int", "interface", "long", "native",
				"new", "package", "private", "protected", "public", "return", "short",
				"static", "strictfp", "super", "switch", "synchronized", "this", "throw",
				"throws", "transient", "try", "void", "volatile", "while",
				"true", "false", "null", "var", "record", "yield", "sealed", "permits" };
		for (String k : kw) KEYWORDS.add(k);
	}

	public OpenFile(String name, String path, ThemeManager.ThemeColors themeColors, final MainWindow mainWindow) {
		this.name = name;
		this.path = path;
		this.mainWindow = mainWindow;
		this.themeColors = themeColors;

		configSaver = ConfigSaver.getLoadedInstance();
		appPrefs = configSaver.getAppPreferences();

		textPane = new JTextPane();
		textPane.setEditable(false);
		textPane.setFont(new Font("Consolas", Font.PLAIN, appPrefs.getFont_size()));
		textPane.setBackground(themeColors.bgColor);
		textPane.setForeground(themeColors.fgColor);
		textPane.setCaretColor(themeColors.fgColor);
		textPane.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

		initAttributes();

		scrollPane = new JScrollPane(textPane);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());

		searchBar = new CodeSearchBar(textPane);
		component = new JPanel(new BorderLayout());
		component.add(searchBar, BorderLayout.NORTH);
		component.add(scrollPane, BorderLayout.CENTER);

		// Ctrl+F from anywhere inside the tab, not just from the text pane, so it
		// works whether the caret or a scrollbar happens to hold focus.
		component.registerKeyboardAction(e -> searchBar.reveal(),
				javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F,
						java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
				JPanel.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

		final JScrollBar verticalScrollbar = scrollPane.getVerticalScrollBar();
		if (verticalScrollbar != null) {
			verticalScrollbar.addAdjustmentListener(new AdjustmentListener() {
				@Override
				public void adjustmentValueChanged(AdjustmentEvent e) {
					String content = getText();
					if (content == null || content.length() == 0) return;
					int scrollValue = verticalScrollbar.getValue() - verticalScrollbar.getMinimum();
					int scrollMax = verticalScrollbar.getMaximum() - verticalScrollbar.getMinimum();
					if (scrollMax < 1 || scrollValue < 0 || scrollValue > scrollMax) return;
					lastScrollPercent = (((double) scrollValue) / ((double) scrollMax));
				}
			});
		}

		JPopupMenu pop = new JPopupMenu();
		JMenuItem fontItem = new JMenuItem(LanguageManager.getString("menu.edit.font"));
		fontItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				JFontChooser fontChooser = new JFontChooser();
				fontChooser.setSelectedFont(textPane.getFont());
				fontChooser.setSelectedFontSize(textPane.getFont().getSize());
				int result = fontChooser.showDialog(mainWindow);
				if (result == JFontChooser.OK_OPTION) {
					textPane.setFont(fontChooser.getSelectedFont());
					appPrefs.setFont_size(fontChooser.getSelectedFontSize());
				}
			}
		});
		pop.add(fontItem);

		JMenuItem base64Item = new JMenuItem(LanguageManager.getString("base64.viewer.title"));
		base64Item.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				String code = getText();
				if (code == null || code.isEmpty()) return;
				Base64ViewerDialog dialog = new Base64ViewerDialog(
						javax.swing.JFrame.getFrames().length > 0
								? javax.swing.JFrame.getFrames()[0] : null,
						code);
				dialog.setVisible(true);
			}
		});
		pop.add(base64Item);

		JMenuItem imagePreviewItem = new JMenuItem("Image Preview");
		imagePreviewItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				showImagePreview();
			}
		});
		pop.add(imagePreviewItem);

		textPane.setComponentPopupMenu(pop);

		scrollPane.addMouseWheelListener(new MouseWheelListener() {
			@Override
			public void mouseWheelMoved(MouseWheelEvent e) {
				if ((e.getModifiersEx() & Keymap.ctrlDownModifier()) != 0) {
					Font font = textPane.getFont();
					int size = font.getSize();
					if (e.getWheelRotation() > 0) {
						size = Math.max(8, --size);
					} else {
						++size;
					}
					textPane.setFont(new Font(font.getName(), font.getStyle(), size));
					appPrefs.setFont_size(size);
					e.consume();
				}
			}
		});
	}

	public void setRawBytes(byte[] bytes) {
		this.rawBytes = bytes;
	}

	public byte[] getRawBytes() {
		return rawBytes;
	}

	public boolean isImage() {
		if (rawBytes == null || rawBytes.length < 4) return false;
		int b0 = rawBytes[0] & 0xFF, b1 = rawBytes[1] & 0xFF, b2 = rawBytes[2] & 0xFF, b3 = rawBytes[3] & 0xFF;
		if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true; // PNG
		if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true; // JPEG
		if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return true; // GIF
		if (b0 == 0x42 && b1 == 0x4D) return true; // BMP
		if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && rawBytes.length >= 12
				&& (rawBytes[8] & 0xFF) == 0x57 && (rawBytes[9] & 0xFF) == 0x45
				&& (rawBytes[10] & 0xFF) == 0x42 && (rawBytes[11] & 0xFF) == 0x50) return true; // WEBP
		return false;
	}

	private void showImagePreview() {
		if (rawBytes == null || rawBytes.length == 0) {
			javax.swing.JOptionPane.showMessageDialog(null,
					"No raw bytes available for this file.",
					"Image Preview",
					javax.swing.JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		try {
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(rawBytes));
			if (img == null) {
				javax.swing.JOptionPane.showMessageDialog(null,
						"ImageIO could not decode this image.\nFormat may not be supported.\nSize: " + rawBytes.length + " bytes",
						"Image Preview",
						javax.swing.JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			final JDialog dialog = new JDialog(
					javax.swing.JFrame.getFrames().length > 0 ? javax.swing.JFrame.getFrames()[0] : null,
					"Image Preview - " + name + " (" + img.getWidth() + "x" + img.getHeight() + ")",
					true);
			dialog.setLayout(new BorderLayout());
			Image scaled = img;
			int maxW = 800, maxH = 600;
			if (img.getWidth() > maxW || img.getHeight() > maxH) {
				double scale = Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight());
				int w = (int) (img.getWidth() * scale);
				int h = (int) (img.getHeight() * scale);
				scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
			}
			JLabel imageLabel = new JLabel(new ImageIcon(scaled));
			imageLabel.setBackground(Color.DARK_GRAY);
			imageLabel.setOpaque(true);
			JScrollPane scroll = new JScrollPane(imageLabel);
			dialog.add(scroll, BorderLayout.CENTER);
			JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			JButton closeBtn = new JButton("Close");
			closeBtn.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					dialog.dispose();
				}
			});
			bottom.add(closeBtn);
			dialog.add(bottom, BorderLayout.SOUTH);
			dialog.setSize(Math.min(img.getWidth() + 20, 900), Math.min(img.getHeight() + 60, 700));
			dialog.setLocationRelativeTo(null);
			dialog.setVisible(true);
		} catch (Exception ex) {
			javax.swing.JOptionPane.showMessageDialog(null,
					"Error loading image: " + ex.getMessage(),
					"Image Preview",
					javax.swing.JOptionPane.ERROR_MESSAGE);
		}
	}

	private void initAttributes() {
		defaultAttr = new SimpleAttributeSet();
		StyleConstants.setFontFamily(defaultAttr, "Consolas");
		StyleConstants.setFontSize(defaultAttr, appPrefs.getFont_size());
		StyleConstants.setForeground(defaultAttr, themeColors.fgColor);

		keywordAttr = new SimpleAttributeSet(defaultAttr);
		StyleConstants.setForeground(keywordAttr, Color.decode("#" + themeColors.keyword));
		StyleConstants.setBold(keywordAttr, true);

		stringAttr = new SimpleAttributeSet(defaultAttr);
		StyleConstants.setForeground(stringAttr, Color.decode("#" + themeColors.string));

		commentAttr = new SimpleAttributeSet(defaultAttr);
		StyleConstants.setForeground(commentAttr, Color.decode("#" + themeColors.comment));
		StyleConstants.setItalic(commentAttr, true);

		numberAttr = new SimpleAttributeSet(defaultAttr);
		StyleConstants.setForeground(numberAttr, Color.decode("#" + themeColors.number));

		typeAttr = new SimpleAttributeSet(defaultAttr);
		StyleConstants.setForeground(typeAttr, Color.decode("#" + themeColors.type));

		lineNumberAttr = new SimpleAttributeSet(defaultAttr);
		StyleConstants.setForeground(lineNumberAttr, new Color(120, 120, 120));
		StyleConstants.setAlignment(lineNumberAttr, StyleConstants.ALIGN_RIGHT);
	}

	public void applyTheme(ThemeManager.ThemeColors newColors) {
		this.themeColors = newColors;
		textPane.setBackground(newColors.bgColor);
		textPane.setForeground(newColors.fgColor);
		textPane.setCaretColor(newColors.fgColor);
		initAttributes();
		String content = getText();
		if (content != null && content.length() > 0) {
			setContent(content);
		}
	}

	public String getText() {
		try {
			return textPane.getDocument().getText(0, textPane.getDocument().getLength());
		} catch (BadLocationException e) {
			return "";
		}
	}

	public void setContent(String content) {
		if (content == null) content = "";
		boolean isJava = name.toLowerCase().endsWith(".java") || name.toLowerCase().endsWith(".class");
		StyledDocument doc = new DefaultStyledDocument();
		textPane.setStyledDocument(doc);
		if (isJava && content.length() > 0) {
			highlightJavaWithLineNumbers(doc, content);
		} else {
			try {
				doc.insertString(0, content, defaultAttr);
			} catch (BadLocationException e) {
			}
		}
		textPane.setCaretPosition(0);
	}

	private void highlightJavaWithLineNumbers(StyledDocument doc, String code) {
		String[] lines = code.split("\n", -1);
		int lineCount = lines.length;
		int numWidth = String.valueOf(lineCount).length();

		try {
			for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
				String line = lines[lineIdx];
				String lineNum = String.format("%" + numWidth + "d  ", lineIdx + 1);
				doc.insertString(doc.getLength(), lineNum, lineNumberAttr);

				// Highlight the line content
				highlightJavaLine(doc, line);

				if (lineIdx < lines.length - 1) {
					doc.insertString(doc.getLength(), "\n", defaultAttr);
				}
			}
		} catch (BadLocationException e) {
		}
	}

	private void highlightJavaLine(StyledDocument doc, String code) {
		int len = code.length();
		int i = 0;
		try {
			while (i < len) {
				char c = code.charAt(i);
				if (c == '/' && i + 1 < len && code.charAt(i + 1) == '/') {
					int end = code.indexOf('\n', i);
					if (end < 0) end = len;
					doc.insertString(doc.getLength(), code.substring(i, end), commentAttr);
					i = end;
				} else if (c == '/' && i + 1 < len && code.charAt(i + 1) == '*') {
					int end = code.indexOf("*/", i + 2);
					if (end < 0) end = len; else end += 2;
					doc.insertString(doc.getLength(), code.substring(i, end), commentAttr);
					i = end;
				} else if (c == '"') {
					int end = i + 1;
					while (end < len) {
						if (code.charAt(end) == '\\') { end += 2; continue; }
						if (code.charAt(end) == '"') { end++; break; }
						end++;
					}
					doc.insertString(doc.getLength(), code.substring(i, end), stringAttr);
					i = end;
				} else if (c == '\'') {
					int end = i + 1;
					while (end < len) {
						if (code.charAt(end) == '\\') { end += 2; continue; }
						if (code.charAt(end) == '\'') { end++; break; }
						end++;
					}
					doc.insertString(doc.getLength(), code.substring(i, end), stringAttr);
					i = end;
				} else if (Character.isLetter(c) || c == '_' || c == '$') {
					int end = i + 1;
					while (end < len && (Character.isLetterOrDigit(code.charAt(end)) || code.charAt(end) == '_' || code.charAt(end) == '$'))
						end++;
					String word = code.substring(i, end);
					if (KEYWORDS.contains(word)) {
						doc.insertString(doc.getLength(), word, keywordAttr);
					} else if (Character.isUpperCase(word.charAt(0))) {
						doc.insertString(doc.getLength(), word, typeAttr);
					} else {
						doc.insertString(doc.getLength(), word, defaultAttr);
					}
					i = end;
				} else if (Character.isDigit(c)) {
					int end = i + 1;
					while (end < len && (Character.isDigit(code.charAt(end)) || code.charAt(end) == '.'
							|| code.charAt(end) == 'x' || code.charAt(end) == 'X'
							|| code.charAt(end) == 'f' || code.charAt(end) == 'F'
							|| code.charAt(end) == 'd' || code.charAt(end) == 'D'
							|| code.charAt(end) == 'l' || code.charAt(end) == 'L'
							|| (code.charAt(end) >= 'a' && code.charAt(end) <= 'f')
							|| (code.charAt(end) >= 'A' && code.charAt(end) <= 'F')))
						end++;
					doc.insertString(doc.getLength(), code.substring(i, end), numberAttr);
					i = end;
				} else if (c == '@' && i + 1 < len && Character.isLetter(code.charAt(i + 1))) {
					int end = i + 1;
					while (end < len && (Character.isLetterOrDigit(code.charAt(end)) || code.charAt(end) == '_'))
						end++;
					doc.insertString(doc.getLength(), code.substring(i, end), keywordAttr);
					i = end;
				} else {
					doc.insertString(doc.getLength(), String.valueOf(c), defaultAttr);
					i++;
				}
			}
		} catch (BadLocationException e) {
		}
	}

	private void highlightJava(StyledDocument doc, String code) {
		int len = code.length();
		int i = 0;
		try {
			while (i < len) {
				char c = code.charAt(i);
				if (c == '/' && i + 1 < len && code.charAt(i + 1) == '/') {
					int end = code.indexOf('\n', i);
					if (end < 0) end = len;
					doc.insertString(doc.getLength(), code.substring(i, end), commentAttr);
					i = end;
				} else if (c == '/' && i + 1 < len && code.charAt(i + 1) == '*') {
					int end = code.indexOf("*/", i + 2);
					if (end < 0) end = len; else end += 2;
					doc.insertString(doc.getLength(), code.substring(i, end), commentAttr);
					i = end;
				} else if (c == '"') {
					int end = i + 1;
					while (end < len) {
						if (code.charAt(end) == '\\') { end += 2; continue; }
						if (code.charAt(end) == '"') { end++; break; }
						end++;
					}
					doc.insertString(doc.getLength(), code.substring(i, end), stringAttr);
					i = end;
				} else if (c == '\'') {
					int end = i + 1;
					while (end < len) {
						if (code.charAt(end) == '\\') { end += 2; continue; }
						if (code.charAt(end) == '\'') { end++; break; }
						end++;
					}
					doc.insertString(doc.getLength(), code.substring(i, end), stringAttr);
					i = end;
				} else if (Character.isLetter(c) || c == '_' || c == '$') {
					int end = i + 1;
					while (end < len && (Character.isLetterOrDigit(code.charAt(end)) || code.charAt(end) == '_' || code.charAt(end) == '$'))
						end++;
					String word = code.substring(i, end);
					if (KEYWORDS.contains(word)) {
						doc.insertString(doc.getLength(), word, keywordAttr);
					} else if (Character.isUpperCase(word.charAt(0))) {
						doc.insertString(doc.getLength(), word, typeAttr);
					} else {
						doc.insertString(doc.getLength(), word, defaultAttr);
					}
					i = end;
				} else if (Character.isDigit(c)) {
					int end = i + 1;
					while (end < len && (Character.isDigit(code.charAt(end)) || code.charAt(end) == '.'
							|| code.charAt(end) == 'x' || code.charAt(end) == 'X'
							|| code.charAt(end) == 'f' || code.charAt(end) == 'F'
							|| code.charAt(end) == 'd' || code.charAt(end) == 'D'
							|| code.charAt(end) == 'l' || code.charAt(end) == 'L'
							|| (code.charAt(end) >= 'a' && code.charAt(end) <= 'f')
							|| (code.charAt(end) >= 'A' && code.charAt(end) <= 'F')))
						end++;
					doc.insertString(doc.getLength(), code.substring(i, end), numberAttr);
					i = end;
				} else if (c == '@' && i + 1 < len && Character.isLetter(code.charAt(i + 1))) {
					int end = i + 1;
					while (end < len && (Character.isLetterOrDigit(code.charAt(end)) || code.charAt(end) == '_'))
						end++;
					doc.insertString(doc.getLength(), code.substring(i, end), keywordAttr);
					i = end;
				} else {
					doc.insertString(doc.getLength(), String.valueOf(c), defaultAttr);
					i++;
				}
			}
		} catch (BadLocationException e) {
		}
	}

	public void decompile() {
		if (!isClassFile || jarFile == null || classEntryName == null) return;
		this.invalidateContent();
		try {
			String source = CfrDecompiler.decompileFromJar(jarFile, classEntryName, decompilerConfig);
			if (source == null || source.trim().isEmpty()) {
				source = tryFallbackDisplay();
			}
			setContentPreserveLastScrollPosition(source);
			this.isContentValid = true;
		} catch (Exception e) {
			setContentPreserveLastScrollPosition(tryFallbackDisplay());
			this.isContentValid = true;
		}
	}

	private String tryFallbackDisplay() {
		StringBuilder sb = new StringBuilder();
		sb.append("// ").append(classEntryName).append("\n");

		try {
			java.util.jar.JarEntry entry = jarFile.getJarEntry(classEntryName.endsWith(".class")
					? classEntryName : classEntryName + ".class");
			if (entry == null) {
				sb.append("// Entry not found in JAR");
				return sb.toString();
			}

			byte[] classBytes;
			try (java.io.InputStream is = jarFile.getInputStream(entry)) {
				java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
				byte[] buf = new byte[16384];
				int len;
				while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
				classBytes = baos.toByteArray();
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

			// Extract meaningful readable ASCII strings (min length 4)
			String asLatin1 = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
			StringBuilder current = new StringBuilder();
			int count = 0;
			for (int i = 0; i < asLatin1.length(); i++) {
				char c = asLatin1.charAt(i);
				if (c >= 32 && c <= 126) {
					current.append(c);
				} else {
					String str = current.toString();
					if (str.length() >= 4 && isMeaningfulString(str)) {
						sb.append(str).append("\n");
						count++;
					}
					current.setLength(0);
				}
			}
			String str = current.toString();
			if (str.length() >= 4 && isMeaningfulString(str)) {
				sb.append(str).append("\n");
				count++;
			}

			if (count == 0) {
				sb.append("// Anlamlı string bulunamadi\n");
			}

		} catch (Exception ex) {
			sb.append("// Hata: ").append(ex.getMessage());
		}
		return sb.toString();
	}

	private boolean isMeaningfulString(String s) {
		return s.length() >= 4;
	}

	private void setContentPreserveLastScrollPosition(final String content) {
		final Double scrollPercent = lastScrollPercent;
		if (scrollPercent != null) {
			SwingUtilities.invokeLater(new Runnable() {
				@Override
				public void run() {
					setContent(content);
					restoreScrollPosition(scrollPercent);
				}
			});
		} else {
			setContent(content);
		}
	}

	private void restoreScrollPosition(final double position) {
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				JScrollBar verticalScrollbar = scrollPane.getVerticalScrollBar();
				if (verticalScrollbar == null) return;
				int scrollMax = verticalScrollbar.getMaximum() - verticalScrollbar.getMinimum();
				long newScrollValue = Math.round(position * scrollMax) + verticalScrollbar.getMinimum();
				if (newScrollValue < verticalScrollbar.getMinimum())
					newScrollValue = verticalScrollbar.getMinimum();
				if (newScrollValue > verticalScrollbar.getMaximum())
					newScrollValue = verticalScrollbar.getMaximum();
				verticalScrollbar.setValue((int) newScrollValue);
			}
		});
	}

	public void setDecompilationInfo(JarFile jarFile, String classEntryName, DecompilerConfig config) {
		this.jarFile = jarFile;
		this.classEntryName = classEntryName;
		this.decompilerConfig = config;
		this.isClassFile = true;
	}

	public boolean isClassFile() {
		return isClassFile;
	}

	public boolean isContentValid() {
		return isContentValid;
	}

	public void invalidateContent() {
		try {
			this.setContent("");
		} finally {
			this.isContentValid = false;
		}
	}

	public void resetScrollPosition() {
		lastScrollPercent = null;
	}

	public void setInitialNavigationLink(String initialNavigationLink) {
	}

	public void onAddedToScreen() {
	}

	public void close() {
		jarFile = null;
		classEntryName = null;
		invalidateContent();
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		OpenFile other = (OpenFile) obj;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		return true;
	}
}
