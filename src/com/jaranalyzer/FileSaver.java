package com.jaranalyzer;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

public class FileSaver {

	private JProgressBar bar;
	private JLabel label;
	private boolean cancel;
	private boolean extracting;

	public FileSaver(JProgressBar bar, JLabel label) {
		this.bar = bar;
		this.label = label;
		final JPopupMenu menu = new JPopupMenu("Cancel");
		final JMenuItem item = new JMenuItem("Cancel");
		item.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent arg0) {
				setCancel(true);
			}
		});
		menu.add(item);
		this.label.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent ev) {
				if (SwingUtilities.isRightMouseButton(ev) && isExtracting())
					menu.show(ev.getComponent(), ev.getX(), ev.getY());
			}
		});
	}

	public void saveText(final String text, final File file) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				long time = System.currentTimeMillis();
				try (FileOutputStream fos = new FileOutputStream(file);
						OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
						BufferedWriter bw = new BufferedWriter(writer);) {
					label.setText(LanguageManager.getString("status.extracting") + " " + file.getName());
					bar.setVisible(true);
					bw.write(text);
					bw.flush();
					label.setText(LanguageManager.getString("status.completed") + " " + getTime(time));
				} catch (Exception e1) {
					label.setText(LanguageManager.getString("status.cannotSaveFile") + " " + file.getName());
					JarAnalyzer.showExceptionDialog(LanguageManager.getString("status.unableToSave"), e1);
				} finally {
					setExtracting(false);
					bar.setVisible(false);
				}
			}
		}).start();
	}

	public void saveAllDecompiled(final File inFile, final File outFile) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				long time = System.currentTimeMillis();
				try {
					bar.setVisible(true);
					setExtracting(true);
					label.setText(LanguageManager.getString("status.extracting") + " " + outFile.getName());
					System.out.println("[SaveAll]: " + inFile.getName() + " -> " + outFile.getName());
					String inFileName = inFile.getName().toLowerCase();
					String outName = outFile.getName().toLowerCase();

					if (outName.endsWith(".html") || outName.endsWith(".htm")) {
						doSaveJarAsHtml(inFile, outFile);
					} else if (inFileName.endsWith(".jar") || inFileName.endsWith(".zip")) {
						doSaveJarDecompiled(inFile, outFile);
					} else if (inFileName.endsWith(".class")) {
						doSaveClassDecompiled(inFile, outFile);
					} else {
						doSaveUnknownFile(inFile, outFile);
					}
					if (cancel) {
						label.setText(LanguageManager.getString("status.cancelled"));
						outFile.delete();
						setCancel(false);
					} else {
						label.setText(LanguageManager.getString("status.completed") + " " + getTime(time));
					}
				} catch (Exception e1) {
					label.setText(LanguageManager.getString("status.cannotSaveFile") + " " + outFile.getName());
					JarAnalyzer.showExceptionDialog(LanguageManager.getString("status.unableToSave"), e1);
				} finally {
					setExtracting(false);
					bar.setVisible(false);
				}
			}
		}).start();
	}

	private void doSaveJarDecompiled(File inFile, File outFile) throws Exception {
		try (JarFile jfile = new JarFile(inFile);
				FileOutputStream dest = new FileOutputStream(outFile);
				BufferedOutputStream buffDest = new BufferedOutputStream(dest);
				ZipOutputStream out = new ZipOutputStream(buffDest);) {
			bar.setMinimum(0);
			bar.setMaximum(jfile.size());
			byte data[] = new byte[1024];
			DecompilerConfig decompilerConfig = ConfigSaver.getLoadedInstance().getDecompilerConfig();

			JarEntryFilter jarEntryFilter = new JarEntryFilter(jfile);
			List<String> mass = jarEntryFilter.getAllEntriesFromJar();

			Enumeration<JarEntry> ent = jfile.entries();
			Set<String> history = new HashSet<String>();
			int tick = 0;
			while (ent.hasMoreElements() && !cancel) {
				bar.setValue(++tick);
				JarEntry entry = ent.nextElement();
				if (!mass.contains(entry.getName()))
					continue;
				label.setText(LanguageManager.getString("status.extracting") + " " + entry.getName());
				bar.setVisible(true);
				if (entry.getName().endsWith(".class")) {
					JarEntry etn = new JarEntry(entry.getName().replace(".class", ".java"));
					label.setText(LanguageManager.getString("status.extracting") + " " + etn.getName());
					System.out.println("[SaveAll]: " + etn.getName() + " -> " + outFile.getName());

					if (history.add(etn.getName())) {
						out.putNextEntry(etn);
						try {
							String internalName = entry.getName();
							if (internalName.endsWith(".class")) {
								internalName = internalName.substring(0, internalName.length() - ".class".length());
							}
							String source = CfrDecompiler.decompileFromJar(jfile, internalName, decompilerConfig);
							if (source == null) source = LanguageManager.getString("decompile.failedFor") + " " + internalName;
							Writer writer = new OutputStreamWriter(out, "UTF-8");
							writer.write(source);
							writer.flush();
						} catch (Exception e) {
							label.setText(LanguageManager.getString("decompile.cannotDecompileFile") + " " + entry.getName());
							JarAnalyzer.showExceptionDialog(LanguageManager.getString("decompile.unableToDecompile"), e);
						} finally {
							out.closeEntry();
						}
					}
				} else {
					try {
						JarEntry etn = new JarEntry(entry.getName());
						if (entry.getName().endsWith(".java"))
							etn = new JarEntry(entry.getName().replace(".java", ".src.java"));
						if (history.add(etn.getName())) {
							out.putNextEntry(etn);
							try {
								InputStream in = jfile.getInputStream(etn);
								if (in != null) {
									try {
										int count;
										while ((count = in.read(data, 0, 1024)) != -1) {
											out.write(data, 0, count);
										}
									} finally {
										in.close();
									}
								}
							} finally {
								out.closeEntry();
							}
						}
					} catch (ZipException ze) {
						if (!ze.getMessage().contains("duplicate")) {
							throw ze;
						}
					}
				}
			}
		}
	}

	private void doSaveClassDecompiled(File inFile, File outFile) throws Exception {
		DecompilerConfig decompilerConfig = ConfigSaver.getLoadedInstance().getDecompilerConfig();
		byte[] classBytes;
		try (FileInputStream fis = new FileInputStream(inFile)) {
			java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int len;
			while ((len = fis.read(buf)) > 0) baos.write(buf, 0, len);
			classBytes = baos.toByteArray();
		}
		String internalName = inFile.getName();
		if (internalName.endsWith(".class")) {
			internalName = internalName.substring(0, internalName.length() - ".class".length());
		}
		String decompiledSource = CfrDecompiler.decompileFromBytes(classBytes, internalName, decompilerConfig);
		if (decompiledSource == null) decompiledSource = LanguageManager.getString("decompile.failedFor") + " " + inFile.getName();

		System.out.println("[SaveAll]: " + inFile.getName() + " -> " + outFile.getName());
		try (FileOutputStream fos = new FileOutputStream(outFile);
				OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
				BufferedWriter bw = new BufferedWriter(writer);) {
			bw.write(decompiledSource);
			bw.flush();
		}
	}

	private void doSaveUnknownFile(File inFile, File outFile) throws Exception {
		try (FileInputStream in = new FileInputStream(inFile); FileOutputStream out = new FileOutputStream(outFile);) {
			System.out.println("[SaveAll]: " + inFile.getName() + " -> " + outFile.getName());

			byte data[] = new byte[1024];
			int count;
			while ((count = in.read(data, 0, 1024)) != -1) {
				out.write(data, 0, count);
			}
		}
	}

	private void doSaveJarAsHtml(File inFile, File outFile) throws Exception {
		try (JarFile jfile = new JarFile(inFile);
				FileOutputStream fos = new FileOutputStream(outFile);
				OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
				BufferedWriter bw = new BufferedWriter(osw);) {

			bar.setMinimum(0);
			bar.setMaximum(jfile.size());
			DecompilerConfig decompilerConfig = ConfigSaver.getLoadedInstance().getDecompilerConfig();

			JarEntryFilter jarEntryFilter = new JarEntryFilter(jfile);
			List<String> mass = jarEntryFilter.getAllEntriesFromJar();

			bw.write("<!DOCTYPE html>\n");
			bw.write("<html>\n<head>\n");
			bw.write("<meta charset=\"UTF-8\">\n");
			bw.write("<title>JarAnalyzer - " + escapeHtml(inFile.getName()) + "</title>\n");
			bw.write("<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/atom-one-dark.min.css\">\n");
			bw.write("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js\"></script>\n");
			bw.write("<script>hljs.highlightAll();</script>\n");
			bw.write("<style>\n");
			bw.write("body{background:#282c34;color:#abb2bf;font-family:'Courier New',monospace;padding:20px}\n");
			bw.write("h1{color:#61afef;border-bottom:1px solid #3b4048;padding-bottom:10px}\n");
			bw.write("h2{color:#e5c07b;cursor:pointer;margin-top:30px}\n");
			bw.write("section{margin:20px 0}\n");
			bw.write("pre{border-radius:8px;overflow-x:auto}\n");
			bw.write("code{font-size:14px}\n");
			bw.write("</style>\n");
			bw.write("</head>\n<body>\n");
			bw.write("<h1>" + escapeHtml(inFile.getName()) + "</h1>\n");

			Enumeration<JarEntry> ent = jfile.entries();
			Set<String> history = new HashSet<String>();
			int tick = 0;
			while (ent.hasMoreElements() && !cancel) {
				bar.setValue(++tick);
				JarEntry entry = ent.nextElement();
				if (!mass.contains(entry.getName()))
					continue;
				if (entry.isDirectory())
					continue;

				String entryName = entry.getName();

				if (entryName.endsWith(".class")) {
					String internalName = entryName.substring(0, entryName.length() - ".class".length());
					String displayPath = entryName.replace(".class", ".java");
					if (!history.add(displayPath))
						continue;

					label.setText(LanguageManager.getString("status.extracting") + " " + displayPath);
					System.out.println("[ExportHTML]: " + displayPath);

					bw.write("<section>\n");
					bw.write("<h2>" + escapeHtml(displayPath) + "</h2>\n");
					bw.write("<pre><code class=\"language-java\">");

					try {
						String source = CfrDecompiler.decompileFromJar(jfile, internalName, decompilerConfig);
						if (source == null) source = LanguageManager.getString("decompile.failedFor") + " " + internalName;
						bw.write(escapeHtml(source));
					} catch (Exception e) {
						bw.write(escapeHtml(LanguageManager.getString("decompile.error") + " " + e.getMessage()));
						label.setText(LanguageManager.getString("decompile.cannotDecompile") + " " + entryName);
					}

					bw.write("</code></pre>\n");
					bw.write("</section>\n");
					bw.flush();
				} else {
					// Non-class file: read bytes and display based on type
					if (!history.add(entryName))
						continue;

					label.setText(LanguageManager.getString("status.extracting") + " " + entryName);
					System.out.println("[ExportHTML]: " + entryName + " (non-class)");

					try (InputStream is = jfile.getInputStream(entry)) {
						ByteArrayOutputStream baos = new ByteArrayOutputStream();
						byte[] buf = new byte[8192];
						int len;
						while ((len = is.read(buf)) > 0) baos.write(buf, 0, len);
						byte[] fileBytes = baos.toByteArray();

						String fileType = detectHtmlFileType(fileBytes, entryName);
						String sizeStr = formatFileSize(fileBytes.length);

						bw.write("<section>\n");
						bw.write("<h2>" + escapeHtml(entryName) + " <span style=\"font-size:12px;color:#61afef\">[" + fileType + ", " + sizeStr + "]</span></h2>\n");

						if (fileType.startsWith("image/")) {
							String base64 = Base64.getEncoder().encodeToString(fileBytes);
							bw.write("<img src=\"data:" + fileType + ";base64," + base64 + "\" style=\"max-width:100%;border:1px solid #3b4048;border-radius:8px\" />\n");
						} else if (fileType.equals("text/plain")) {
							String text = new String(fileBytes, StandardCharsets.UTF_8);
							bw.write("<pre><code>" + escapeHtml(text) + "</code></pre>\n");
						} else if (fileType.equals("text/xml") || entryName.endsWith(".xml")) {
							String text = new String(fileBytes, StandardCharsets.UTF_8);
							bw.write("<pre><code class=\"language-xml\">" + escapeHtml(text) + "</code></pre>\n");
						} else if (fileType.equals("text/json") || entryName.endsWith(".json")) {
							String text = new String(fileBytes, StandardCharsets.UTF_8);
							bw.write("<pre><code class=\"language-json\">" + escapeHtml(text) + "</code></pre>\n");
						} else if (fileType.equals("text/yaml") || entryName.endsWith(".yml") || entryName.endsWith(".yaml")) {
							String text = new String(fileBytes, StandardCharsets.UTF_8);
							bw.write("<pre><code class=\"language-yaml\">" + escapeHtml(text) + "</code></pre>\n");
						} else if (fileType.equals("text/properties") || entryName.endsWith(".properties")) {
							String text = new String(fileBytes, StandardCharsets.UTF_8);
							bw.write("<pre><code>" + escapeHtml(text) + "</code></pre>\n");
						} else {
							// Binary: hex dump
							String hexDump = bytesToHexDumpHtml(fileBytes);
							bw.write("<pre><code>" + hexDump + "</code></pre>\n");
						}

						bw.write("</section>\n");
						bw.flush();
					} catch (Exception e) {
						System.err.println("[ExportHTML] Error reading non-class entry " + entryName + ": " + e);
					}
				}
			}

			bw.write("</body>\n</html>\n");
			bw.flush();
		}
	}

	private static String escapeHtml(String text) {
		if (text == null) return "";
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
			case '&': sb.append("&amp;"); break;
			case '<': sb.append("&lt;"); break;
			case '>': sb.append("&gt;"); break;
			case '"': sb.append("&quot;"); break;
			case '\'': sb.append("&#39;"); break;
			default: sb.append(c);
			}
		}
		return sb.toString();
	}

	private static String detectHtmlFileType(byte[] bytes, String entryName) {
		if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50
				&& bytes[2] == 0x4E && bytes[3] == 0x47) return "image/png";
		if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) return "image/jpeg";
		if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') return "image/gif";
		if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') return "image/bmp";
		if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
				&& bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
		if (bytes.length >= 2 && bytes[0] == 'M' && bytes[1] == 'Z') return "binary/pe-exe";
		if (bytes.length >= 4 && bytes[0] == 0x7F && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F') return "binary/elf";
		if (bytes.length >= 4 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xED && bytes[2] == (byte) 0xFA && bytes[3] == (byte) 0xCE) return "binary/macho";
		if (bytes.length >= 4 && bytes[0] == (byte) 0xCA && bytes[1] == (byte) 0xFE && bytes[2] == (byte) 0xBA && bytes[3] == (byte) 0xBE) return "binary/java-class";
		if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 0x03 && bytes[3] == 0x04) return "binary/zip";

		String lower = entryName.toLowerCase();
		if (lower.endsWith(".xml")) return "text/xml";
		if (lower.endsWith(".json")) return "text/json";
		if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "text/yaml";
		if (lower.endsWith(".properties")) return "text/properties";
		if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".cfg") || lower.endsWith(".conf") || lower.endsWith(".ini") || lower.endsWith(".lang") || lower.endsWith(".toml")) return "text/plain";
		if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
		if (lower.endsWith(".css")) return "text/css";
		if (lower.endsWith(".js")) return "text/javascript";
		if (lower.endsWith(".sql")) return "text/sql";
		if (lower.endsWith(".mf")) return "text/plain";

		// Heuristic: check if mostly printable
		if (bytes.length > 0) {
			int nonPrintable = 0;
			int checkLen = Math.min(bytes.length, 4096);
			for (int i = 0; i < checkLen; i++) {
				int b = bytes[i] & 0xFF;
				if (b < 9 || (b > 13 && b < 32) || b > 126) nonPrintable++;
			}
			if (nonPrintable < checkLen / 5) return "text/plain";
		}

		return "binary/unknown";
	}

	private static String formatFileSize(int bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
		return String.format("%.1f MB", bytes / (1024.0 * 1024));
	}

	private static String bytesToHexDumpHtml(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		int offset = 0;
		int maxLines = 512; // Limit to 512 lines (8KB) to keep HTML manageable
		while (offset < bytes.length && (offset / 16) < maxLines) {
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
		if (offset < bytes.length) {
			sb.append("... (").append(bytes.length - offset).append(" more bytes, truncated for display)\n");
		}
		return escapeHtml(sb.toString());
	}

	public boolean isCancel() {
		return cancel;
	}

	public void setCancel(boolean cancel) {
		this.cancel = cancel;
	}

	public boolean isExtracting() {
		return extracting;
	}

	public void setExtracting(boolean extracting) {
		this.extracting = extracting;
	}

	public static String getTime(long time) {
		long lap = System.currentTimeMillis() - time;
		lap = lap / 1000;
		StringBuilder sb = new StringBuilder();
		long hour =  ((lap / 60) / 60);
		long min = ((lap - (hour * 60 * 60)) / 60);
		long sec = ((lap - (hour * 60 * 60) - (min * 60)));
		if (hour > 0)
			sb.append("Hour:").append(hour).append(" ");
		sb.append("Min(s): ").append(min).append(" Sec: ").append(sec);
		return sb.toString();
	}
}
