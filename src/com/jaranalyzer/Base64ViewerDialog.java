package com.jaranalyzer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

public class Base64ViewerDialog extends JDialog {

	private static final Pattern BASE64_PATTERN = Pattern.compile(
			"\"([A-Za-z0-9+/]{20,}={0,2})\"");

	private static final Pattern CONCAT_PATTERN = Pattern.compile(
			"\"([A-Za-z0-9+/]+={0,2})\"(?:\\s*\\+\\s*\"([A-Za-z0-9+/]+={0,2})\")+");

	private static final Pattern SINGLE_STRING_PATTERN = Pattern.compile(
			"\"([A-Za-z0-9+/]{20,}={0,2})\"");

	private JList<Base64Entry> entryList;
	private DefaultListModel<Base64Entry> listModel;
	private JTabbedPane previewTabs;
	private JLabel imageLabel;
	private JTextArea textArea;
	private JTextArea hexArea;
	private JLabel infoLabel;

	public Base64ViewerDialog(java.awt.Frame owner, String code) {
		super(owner, LanguageManager.getString("base64.viewer.title"), true);
		setSize(900, 600);
		setLocationRelativeTo(owner);

		listModel = new DefaultListModel<>();
		entryList = new JList<>(listModel);
		entryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		entryList.setCellRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
					boolean isSelected, boolean cellHasFocus) {
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (value instanceof Base64Entry) {
					Base64Entry entry = (Base64Entry) value;
					String label = "#" + (index + 1) + " [" + entry.getTypeName() + "] "
							+ entry.encodedLength + " chars";
					setText(label);
					if (!isSelected) {
						if (entry.isImage) {
							setBackground(new Color(220, 240, 255));
						} else if (entry.isText) {
							setBackground(new Color(220, 255, 220));
						} else {
							setBackground(Color.WHITE);
						}
					}
				}
				return this;
			}
		});

		entryList.addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(ListSelectionEvent e) {
				if (e.getValueIsAdjusting()) return;
				int idx = entryList.getSelectedIndex();
				if (idx >= 0) {
					showEntry(listModel.getElementAt(idx));
				}
			}
		});

		JScrollPane listScroll = new JScrollPane(entryList);
		listScroll.setPreferredSize(new Dimension(250, 0));

		previewTabs = new JTabbedPane();

		imageLabel = new JLabel("-", JLabel.CENTER);
		imageLabel.setBackground(Color.DARK_GRAY);
		imageLabel.setOpaque(true);
		JScrollPane imageScroll = new JScrollPane(imageLabel);
		imageScroll.getVerticalScrollBar().setUnitIncrement(16);
		previewTabs.addTab(LanguageManager.getString("base64.viewer.tab.image"), imageScroll);

		textArea = new JTextArea();
		textArea.setEditable(false);
		textArea.setFont(new Font("Consolas", Font.PLAIN, 12));
		textArea.setLineWrap(true);
		textArea.setWrapStyleWord(true);
		JScrollPane textScroll = new JScrollPane(textArea);
		previewTabs.addTab(LanguageManager.getString("base64.viewer.tab.text"), textScroll);

		hexArea = new JTextArea();
		hexArea.setEditable(false);
		hexArea.setFont(new Font("Consolas", Font.PLAIN, 12));
		JScrollPane hexScroll = new JScrollPane(hexArea);
		previewTabs.addTab(LanguageManager.getString("base64.viewer.tab.hex"), hexScroll);

		infoLabel = new JLabel(" ");
		infoLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
		infoLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

		JPanel previewPanel = new JPanel(new BorderLayout());
		previewPanel.add(previewTabs, BorderLayout.CENTER);
		previewPanel.add(infoLabel, BorderLayout.SOUTH);

		JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, previewPanel);
		splitPane.setDividerLocation(250);
		splitPane.setResizeWeight(0.0);

		JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton closeButton = new JButton(LanguageManager.getString("base64.viewer.close"));
		closeButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				dispose();
			}
		});
		bottomPanel.add(closeButton);

		getContentPane().add(splitPane, BorderLayout.CENTER);
		getContentPane().add(bottomPanel, BorderLayout.SOUTH);

		scanCode(code);
	}

	private void scanCode(String code) {
		if (code == null || code.isEmpty()) {
			infoLabel.setText(LanguageManager.getString("base64.viewer.codeEmpty"));
			return;
		}

		System.out.println("[BASE64VIEWER] Code length: " + code.length());

		java.util.Set<Integer> consumed = new java.util.HashSet<>();
		int count = 0;
		int concatCount = 0;
		int singleCount = 0;
		int decodeFail = 0;

		Matcher concatMatcher = CONCAT_PATTERN.matcher(code);
		while (concatMatcher.find()) {
			StringBuilder full = new StringBuilder();
			for (int g = 1; g <= concatMatcher.groupCount(); g++) {
				String part = concatMatcher.group(g);
				if (part != null) full.append(part);
			}
			String b64 = full.toString();
			if (b64.length() < 20) continue;
			try {
				byte[] decoded = Base64.getDecoder().decode(b64);
				Base64Entry entry = new Base64Entry(b64.length(), decoded);
				listModel.addElement(entry);
				count++;
				concatCount++;
				for (int i = concatMatcher.start(); i < concatMatcher.end(); i++) {
					consumed.add(i);
				}
			} catch (Exception e) {
				decodeFail++;
				System.out.println("[BASE64VIEWER] Concat decode fail: " + e.getMessage() + " len=" + b64.length());
			}
		}

		Matcher singleMatcher = SINGLE_STRING_PATTERN.matcher(code);
		while (singleMatcher.find()) {
			boolean alreadyConsumed = false;
			for (int i = singleMatcher.start(); i < singleMatcher.end(); i++) {
				if (consumed.contains(i)) {
					alreadyConsumed = true;
					break;
				}
			}
			if (alreadyConsumed) continue;

			String b64 = singleMatcher.group(1);
			try {
				byte[] decoded = Base64.getDecoder().decode(b64);
				Base64Entry entry = new Base64Entry(b64.length(), decoded);
				listModel.addElement(entry);
				count++;
				singleCount++;
			} catch (Exception e) {
				decodeFail++;
				System.out.println("[BASE64VIEWER] Single decode fail: " + e.getMessage() + " len=" + b64.length());
			}
		}

		System.out.println("[BASE64VIEWER] Found: " + count + " (concat=" + concatCount + " single=" + singleCount + " fail=" + decodeFail + ")");

		if (count == 0) {
			infoLabel.setText(LanguageManager.getString("base64.viewer.notFound"));
		} else {
			infoLabel.setText(count + " " + LanguageManager.getString("base64.viewer.found"));
			entryList.setSelectedIndex(0);
		}
	}

	private void showEntry(Base64Entry entry) {
		textArea.setText("");
		hexArea.setText("");
		imageLabel.setIcon(null);
		imageLabel.setText("");

		StringBuilder info = new StringBuilder();
		info.append(LanguageManager.getString("base64.viewer.size")).append(": ").append(entry.decoded.length).append(" bytes");
		info.append(" | ").append(LanguageManager.getString("base64.viewer.type")).append(": ").append(entry.getTypeName());

		if (entry.isImage) {
			try {
				BufferedImage img = ImageIO.read(new ByteArrayInputStream(entry.decoded));
				if (img != null) {
					info.append(" | ").append(LanguageManager.getString("base64.viewer.resolution")).append(": ").append(img.getWidth()).append("x").append(img.getHeight());
					Image scaled = img;
					int maxW = 700, maxH = 500;
					if (img.getWidth() > maxW || img.getHeight() > maxH) {
						double scale = Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight());
						int w = (int) (img.getWidth() * scale);
						int h = (int) (img.getHeight() * scale);
						scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
					}
					imageLabel.setIcon(new ImageIcon(scaled));
					previewTabs.setSelectedIndex(0);
					System.out.println("[BASE64VIEWER] Image shown: " + img.getWidth() + "x" + img.getHeight());
				} else {
					imageLabel.setText("Resim yüklenemedi (ImageIO.read null döndü, tip=" + entry.getTypeName() + ")");
					System.out.println("[BASE64VIEWER] ImageIO.read returned null for type " + entry.getTypeName());
				}
			} catch (Exception e) {
				imageLabel.setText("Resim hatası: " + e.getMessage());
				System.out.println("[BASE64VIEWER] Image error: " + e.getMessage());
			}
		} else if (entry.isText) {
			textArea.setText(entry.asText);
			textArea.setCaretPosition(0);
			previewTabs.setSelectedIndex(1);
		}

		StringBuilder hex = new StringBuilder();
		int len = Math.min(entry.decoded.length, 4096);
		for (int i = 0; i < len; i += 16) {
			hex.append(String.format("%08x  ", i));
			StringBuilder ascii = new StringBuilder();
			for (int j = 0; j < 16; j++) {
				if (i + j < len) {
					int b = entry.decoded[i + j] & 0xFF;
					hex.append(String.format("%02x ", b));
					if (b >= 32 && b < 127) {
						ascii.append((char) b);
					} else {
						ascii.append('.');
					}
				} else {
					hex.append("   ");
					ascii.append(' ');
				}
				if (j == 7) hex.append(' ');
			}
			hex.append(" |").append(ascii).append("|\n");
		}
		if (entry.decoded.length > 4096) {
			hex.append("\n... (").append(entry.decoded.length - 4096).append(" bytes daha)");
		}
		hexArea.setText(hex.toString());
		hexArea.setCaretPosition(0);

		infoLabel.setText(info.toString());
	}

	private static class Base64Entry {
		int encodedLength;
		byte[] decoded;
		boolean isImage;
		boolean isText;
		String asText;
		String typeName;

		Base64Entry(int encodedLength, byte[] decoded) {
			this.encodedLength = encodedLength;
			this.decoded = decoded;
			classify();
		}

		private void classify() {
			if (decoded.length >= 4) {
				int b0 = decoded[0] & 0xFF;
				int b1 = decoded[1] & 0xFF;
				int b2 = decoded[2] & 0xFF;
				int b3 = decoded[3] & 0xFF;

				if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
					isImage = true;
					typeName = "PNG";
					return;
				}
				if (b0 == 0xFF && b1 == 0xD8) {
					isImage = true;
					typeName = "JPEG";
					return;
				}
				if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) {
					isImage = true;
					typeName = "GIF";
					return;
				}
				if (b0 == 0x42 && b1 == 0x4D) {
					isImage = true;
					typeName = "BMP";
					return;
				}
				if (decoded.length >= 12 && b0 == 0x52 && b1 == 0x49 && b2 == 0x46
						&& decoded[8] == 0x57 && decoded[9] == 0x45 && decoded[10] == 0x42 && decoded[11] == 0x50) {
					isImage = true;
					typeName = "WEBP";
					return;
				}
				if (b0 == 0x1F && b1 == 0x8B) {
					typeName = "GZIP";
					return;
				}
				if (b0 == 0x50 && b1 == 0x4B) {
					typeName = "ZIP";
					return;
				}
				if (b0 == 0xCA && b1 == 0xFE && b2 == 0xBA && b3 == 0xBE) {
					typeName = "CLASS";
					return;
				}
			}

			int printable = 0;
			int total = Math.min(decoded.length, 256);
			for (int i = 0; i < total; i++) {
				int b = decoded[i] & 0xFF;
				if (b >= 32 && b < 127 || b == '\n' || b == '\r' || b == '\t') {
					printable++;
				}
			}
			if (total > 0 && (double) printable / total > 0.85) {
				isText = true;
				typeName = "TEXT";
				asText = new String(decoded, 0, Math.min(decoded.length, 100000));
			} else {
				typeName = "BINARY";
			}
		}

		String getTypeName() {
			return typeName;
		}
	}
}
