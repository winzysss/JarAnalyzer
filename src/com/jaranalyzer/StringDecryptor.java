package com.jaranalyzer;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public class StringDecryptor {

	public interface ProgressCallback {
		void onProgress(String message);
	}

	private ProgressCallback callback;

	public StringDecryptor(ProgressCallback callback) {
		this.callback = callback;
	}

	public void decryptStrings(JarFile jarFile, File outputFile) throws Exception {
		Map<String, byte[]> results = new HashMap<>();
		Enumeration<JarEntry> entries = jarFile.entries();

		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();
			if (!entry.getName().endsWith(".class")) {
				continue;
			}
			try {
				ClassReader cr = new ClassReader(jarFile.getInputStream(entry));
				ClassNode cn = new ClassNode();
				cr.accept(cn, ClassReader.SKIP_DEBUG);

				boolean modified = false;
				for (Object o : cn.methods) {
					MethodNode mn = (MethodNode) o;
					if (mn.instructions == null) continue;
					if (decryptMethodStrings(mn)) {
						modified = true;
					}
				}

				if (modified) {
					ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
					cn.accept(cw);
					results.put(entry.getName(), cw.toByteArray());
				}
			} catch (Exception e) {
			}
		}

		writeOutput(jarFile, results, outputFile);
	}

	public boolean decryptMethodStringsStatic(MethodNode mn) {
		return decryptMethodStrings(mn);
	}

	private boolean decryptMethodStrings(MethodNode mn) {
		boolean modified = false;
		InsnList instructions = mn.instructions;

		for (int i = 0; i < instructions.size(); i++) {
			AbstractInsnNode insn = instructions.get(i);

			if (insn.getType() == AbstractInsnNode.LDC_INSN) {
				LdcInsnNode ldc = (LdcInsnNode) insn;
				if (ldc.cst instanceof String) {
					String str = (String) ldc.cst;
					String decrypted = tryXorDecrypt(str, instructions, i);
					if (decrypted != null) {
						instructions.set(ldc, new LdcInsnNode(decrypted));
						modified = true;
						continue;
					}
				}
			}

			if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
				MethodInsnNode min = (MethodInsnNode) insn;
				if (isDecryptMethod(min)) {
					String decrypted = tryInvokeDecrypt(instructions, i, min);
					if (decrypted != null) {
						AbstractInsnNode prev = instructions.get(i - 1);
						instructions.remove(min);
						instructions.set(prev, new LdcInsnNode(decrypted));
						modified = true;
						i--;
						continue;
					}
				}
			}

			if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
				MethodInsnNode min = (MethodInsnNode) insn;
				if (min.owner != null && min.owner.equals("java/lang/StringBuilder") && min.name.equals("<init>")) {
					if (tryStringBuilderReconstruct(instructions, i)) {
						modified = true;
					}
				}
			}

			if (insn.getOpcode() == Opcodes.NEWARRAY) {
				if (tryCharArrayReconstruct(instructions, i)) {
					modified = true;
				}
			}
		}

		return modified;
	}

	private String tryXorDecrypt(String str, InsnList instructions, int index) {
		if (str == null || str.isEmpty()) return null;
		if (str.length() > 200) return null;

		boolean hasHighChars = false;
		for (char c : str.toCharArray()) {
			if (c > 127) {
				hasHighChars = true;
				break;
			}
		}
		if (!hasHighChars) return null;

		for (int key = 1; key <= 255; key++) {
			String result = xorWithKey(str, key);
			if (isReadable(result)) {
				return result;
			}
		}

		String addResult = tryAdditionCipher(str);
		if (addResult != null) return addResult;

		String shiftResult = tryBitShiftCipher(str);
		if (shiftResult != null) return shiftResult;

		String multiXorResult = tryMultiByteXor(str);
		if (multiXorResult != null) return multiXorResult;

		return null;
	}

	private String tryAdditionCipher(String str) {
		if (str == null || str.isEmpty()) return null;
		for (int key = 1; key <= 255; key++) {
			StringBuilder sb = new StringBuilder();
			for (char c : str.toCharArray()) {
				sb.append((char) ((c - key) & 0xFF));
			}
			if (isReadable(sb.toString())) return sb.toString();
		}
		return null;
	}

	private String tryBitShiftCipher(String str) {
		if (str == null || str.isEmpty()) return null;
		for (int shift = 1; shift <= 7; shift++) {
			StringBuilder leftShift = new StringBuilder();
			StringBuilder rightShift = new StringBuilder();
			for (char c : str.toCharArray()) {
				leftShift.append((char) ((c << shift) & 0xFF));
				rightShift.append((char) ((c >> shift) & 0xFF));
			}
			if (isReadable(leftShift.toString())) return leftShift.toString();
			if (isReadable(rightShift.toString())) return rightShift.toString();
		}
		return null;
	}

	private String tryMultiByteXor(String str) {
		if (str == null || str.length() < 4) return null;
		for (int keyLen = 2; keyLen <= 8; keyLen++) {
			if (str.length() < keyLen * 2) continue;
			char[] key = new char[keyLen];
			boolean keyFound = true;
			for (int pos = 0; pos < keyLen; pos++) {
				int bestScore = 0;
				char bestByte = 0;
				for (int k = 0; k < 256; k++) {
					int score = 0;
					int count = 0;
					for (int i = pos; i < str.length(); i += keyLen) {
						char dec = (char) (str.charAt(i) ^ k);
						if (dec >= 32 && dec <= 126) score++;
						count++;
					}
					if (count > 0 && score > bestScore) {
						bestScore = score;
						bestByte = (char) k;
					}
				}
				key[pos] = bestByte;
			}
			for (char c : key) {
				if (c == 0) {
					keyFound = false;
					break;
				}
			}
			if (keyFound) {
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < str.length(); i++) {
					sb.append((char) (str.charAt(i) ^ key[i % keyLen]));
				}
				if (isReadable(sb.toString())) return sb.toString();
			}
		}
		return null;
	}

	private String xorWithKey(String str, int key) {
		char[] chars = str.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			chars[i] = (char) (chars[i] ^ key);
		}
		return new String(chars);
	}

	private boolean isReadable(String s) {
		if (s == null || s.isEmpty()) return false;
		int printable = 0;
		for (char c : s.toCharArray()) {
			if (c >= 32 && c <= 126 || c == '\n' || c == '\r' || c == '\t') {
				printable++;
			}
		}
		return (double) printable / s.length() > 0.85;
	}

	private boolean isDecryptMethod(MethodInsnNode min) {
		if (min.desc == null) return false;
		return min.desc.contains("Ljava/lang/String;") && min.desc.endsWith("Ljava/lang/String;");
	}

	private String tryInvokeDecrypt(InsnList instructions, int index, MethodInsnNode min) {
		if (index < 1) return null;
		AbstractInsnNode prev = instructions.get(index - 1);
		if (prev.getType() != AbstractInsnNode.LDC_INSN) return null;
		LdcInsnNode ldc = (LdcInsnNode) prev;
		if (!(ldc.cst instanceof String)) return null;

		String encrypted = (String) ldc.cst;

		for (int key = 1; key <= 255; key++) {
			String result = xorWithKey(encrypted, key);
			if (isReadable(result)) {
				return result;
			}
		}

		try {
			return simpleCaesarDecrypt(encrypted);
		} catch (Exception e) {
			return null;
		}
	}

	private String simpleCaesarDecrypt(String str) {
		if (str == null || str.isEmpty()) return null;
		StringBuilder sb = new StringBuilder();
		for (char c : str.toCharArray()) {
			if (c >= 'a' && c <= 'z') {
				sb.append((char) ('z' - (c - 'a')));
			} else if (c >= 'A' && c <= 'Z') {
				sb.append((char) ('Z' - (c - 'A')));
			} else {
				sb.append(c);
			}
		}
		return isReadable(sb.toString()) ? sb.toString() : null;
	}

	private boolean tryStringBuilderReconstruct(InsnList instructions, int index) {
		if (index < 2) return false;

		AbstractInsnNode initInsn = instructions.get(index);
		AbstractInsnNode dupInsn = instructions.get(index - 1);
		AbstractInsnNode newInsn = instructions.get(index - 2);

		if (newInsn.getOpcode() != Opcodes.NEW || dupInsn.getOpcode() != Opcodes.DUP) {
			return false;
		}

		StringBuilder sb = new StringBuilder();
		int appendCount = 0;
		int i = index + 1;
		AbstractInsnNode insn = (i < instructions.size()) ? instructions.get(i) : null;
		AbstractInsnNode toStringInsn = null;

		List<AbstractInsnNode> toRemove = new ArrayList<>();
		toRemove.add(newInsn);
		toRemove.add(dupInsn);
		toRemove.add(initInsn);

		while (insn != null && appendCount < 50) {
			if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
				MethodInsnNode min = (MethodInsnNode) insn;
				if (min.owner != null && min.owner.equals("java/lang/StringBuilder")) {
					if (min.name.equals("append")) {
						if (i > 0) {
							AbstractInsnNode prev = instructions.get(i - 1);
							if (prev.getType() == AbstractInsnNode.LDC_INSN) {
								LdcInsnNode ldc = (LdcInsnNode) prev;
								if (ldc.cst instanceof Character) {
									sb.append((Character) ldc.cst);
									appendCount++;
								} else if (ldc.cst instanceof String) {
									sb.append((String) ldc.cst);
									appendCount++;
								} else if (ldc.cst instanceof Integer) {
									sb.append((char) ((Integer) ldc.cst).intValue());
									appendCount++;
								}
							} else if (prev.getOpcode() == Opcodes.BIPUSH) {
								IntInsnNode bipush = (IntInsnNode) prev;
								sb.append((char) bipush.operand);
								appendCount++;
							} else if (prev.getOpcode() == Opcodes.SIPUSH) {
								IntInsnNode sipush = (IntInsnNode) prev;
								sb.append((char) sipush.operand);
								appendCount++;
							} else if (prev.getOpcode() == Opcodes.ICONST_0) {
								sb.append((char) 0);
								appendCount++;
							} else if (prev.getOpcode() >= Opcodes.ICONST_1 && prev.getOpcode() <= Opcodes.ICONST_5) {
								sb.append((char) (prev.getOpcode() - Opcodes.ICONST_0));
								appendCount++;
							}
						}
						toRemove.add(insn);
					} else if (min.name.equals("toString")) {
						toStringInsn = insn;
						toRemove.add(insn);
						break;
					} else {
						break;
					}
				} else {
					break;
				}
			} else if (insn.getType() == AbstractInsnNode.LDC_INSN
					|| insn.getOpcode() == Opcodes.BIPUSH || insn.getOpcode() == Opcodes.SIPUSH
					|| (insn.getOpcode() >= Opcodes.ICONST_0 && insn.getOpcode() <= Opcodes.ICONST_5)) {
				toRemove.add(insn);
			} else if (insn.getType() == AbstractInsnNode.LABEL || insn.getType() == AbstractInsnNode.LINE) {
			} else {
				break;
			}
			i++;
			insn = (i < instructions.size()) ? instructions.get(i) : null;
		}

		if (appendCount >= 3 && sb.length() > 0 && toStringInsn != null) {
			LdcInsnNode ldcReplacement = new LdcInsnNode(sb.toString());
			instructions.insertBefore(newInsn, ldcReplacement);
			for (AbstractInsnNode n : toRemove) {
				instructions.remove(n);
			}
			return true;
		}
		return false;
	}

	private boolean tryCharArrayReconstruct(InsnList instructions, int index) {
		if (index < 1) return false;
		AbstractInsnNode prev = instructions.get(index - 1);
		if (prev == null) return false;

		int arraySize = -1;
		if (prev.getOpcode() == Opcodes.BIPUSH) {
			arraySize = ((IntInsnNode) prev).operand;
		} else if (prev.getOpcode() == Opcodes.SIPUSH) {
			arraySize = ((IntInsnNode) prev).operand;
		} else if (prev.getOpcode() >= Opcodes.ICONST_0 && prev.getOpcode() <= Opcodes.ICONST_5) {
			arraySize = prev.getOpcode() - Opcodes.ICONST_0;
		}

		if (arraySize <= 0 || arraySize > 500) return false;

		char[] chars = new char[arraySize];
		int fillCount = 0;
		int i = index + 1;
		AbstractInsnNode insn = (i < instructions.size()) ? instructions.get(i) : null;
		AbstractInsnNode endInsn = null;

		List<AbstractInsnNode> toRemove = new ArrayList<>();
		toRemove.add(prev);
		toRemove.add(instructions.get(index));

		while (insn != null && fillCount < arraySize) {
			if (insn.getType() == AbstractInsnNode.LDC_INSN) {
				LdcInsnNode ldc = (LdcInsnNode) insn;
				if (ldc.cst instanceof Integer && fillCount < arraySize) {
					chars[fillCount++] = (char) ((Integer) ldc.cst).intValue();
				} else if (ldc.cst instanceof Character && fillCount < arraySize) {
					chars[fillCount++] = (Character) ldc.cst;
				}
				toRemove.add(insn);
			} else if (insn.getOpcode() == Opcodes.BIPUSH) {
				chars[fillCount++] = (char) ((IntInsnNode) insn).operand;
				toRemove.add(insn);
			} else if (insn.getOpcode() == Opcodes.SIPUSH) {
				chars[fillCount++] = (char) ((IntInsnNode) insn).operand;
				toRemove.add(insn);
			} else if (insn.getOpcode() >= Opcodes.ICONST_0 && insn.getOpcode() <= Opcodes.ICONST_5) {
				chars[fillCount++] = (char) (insn.getOpcode() - Opcodes.ICONST_0);
				toRemove.add(insn);
			} else if (insn.getOpcode() == Opcodes.CASTORE) {
				toRemove.add(insn);
			} else if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
				MethodInsnNode min = (MethodInsnNode) insn;
				if (min.owner != null && min.owner.equals("java/lang/String") && min.name.equals("valueOf")) {
					endInsn = insn;
					toRemove.add(insn);
					break;
				}
			} else if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
				MethodInsnNode min = (MethodInsnNode) insn;
				if (min.owner != null && min.owner.equals("java/lang/String")) {
					endInsn = insn;
					toRemove.add(insn);
					break;
				}
			} else if (insn.getType() == AbstractInsnNode.LABEL || insn.getType() == AbstractInsnNode.LINE) {
			} else {
				break;
			}
			i++;
			insn = (i < instructions.size()) ? instructions.get(i) : null;
		}

		if (fillCount >= 3 && endInsn != null) {
			String reconstructed = new String(chars, 0, fillCount);
			LdcInsnNode ldcReplacement = new LdcInsnNode(reconstructed);
			instructions.insertBefore(prev, ldcReplacement);
			for (AbstractInsnNode n : toRemove) {
				instructions.remove(n);
			}
			return true;
		}
		return false;
	}

	private void writeOutput(JarFile jarFile, Map<String, byte[]> modifiedClasses, File outputFile) throws Exception {
		try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputFile))) {
			Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				if (entry.getName().endsWith(".class") && modifiedClasses.containsKey(entry.getName())) {
					ZipEntry ze = new ZipEntry(entry.getName());
					zos.putNextEntry(ze);
					zos.write(modifiedClasses.get(entry.getName()));
					zos.closeEntry();
				} else if (!entry.isDirectory()) {
					ZipEntry ze = new ZipEntry(entry.getName());
					zos.putNextEntry(ze);
					byte[] buf = new byte[4096];
					java.io.InputStream is = jarFile.getInputStream(entry);
					int len;
					while ((len = is.read(buf)) > 0) {
						zos.write(buf, 0, len);
					}
					is.close();
					zos.closeEntry();
				}
			}
		}
		if (callback != null) {
			callback.onProgress("String decryption complete: " + modifiedClasses.size() + " classes modified");
		}
	}
}
