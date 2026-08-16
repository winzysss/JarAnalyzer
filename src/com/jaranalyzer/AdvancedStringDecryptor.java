package com.jaranalyzer;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.ArrayList;
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
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.Handle;

public class AdvancedStringDecryptor {

	public interface ProgressCallback {
		void onProgress(String message);
	}

	private ProgressCallback callback;
	private int totalDecrypted = 0;
	private int totalClasses = 0;
	private Map<String, MethodNode> customDecryptMethods = new HashMap<>();
	private List<byte[]> aesKeys = new ArrayList<>();
	private Map<String, ClassNode> classMap = new HashMap<>();

	public AdvancedStringDecryptor(ProgressCallback callback) {
		this.callback = callback;
	}

	public void decrypt(JarFile jarFile, File outputFile) throws Exception {
		Map<String, byte[]> results = new HashMap<>();
		Enumeration<JarEntry> entries = jarFile.entries();

		// Pass 1: Load all classes, collect custom decrypt methods + AES keys
		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();
			if (!entry.getName().endsWith(".class")) continue;
			try {
				ClassReader cr = new ClassReader(jarFile.getInputStream(entry));
				ClassNode cn = new ClassNode();
				cr.accept(cn, ClassReader.SKIP_DEBUG);
				classMap.put(cn.name, cn);

				for (Object o : cn.methods) {
					MethodNode mn = (MethodNode) o;
					if (mn.instructions == null) continue;
					if (isCustomDecryptMethod(mn, cn.name)) {
						customDecryptMethods.put(cn.name + "." + mn.name + mn.desc, mn);
					}
				}

				collectAESKeys(cn);
			} catch (Exception e) {
			}
		}

		if (callback != null) {
			callback.onProgress("Found " + customDecryptMethods.size() + " custom decrypt methods, "
					+ aesKeys.size() + " AES keys");
		}

		// Pass 2: Decrypt strings using all available methods
		for (ClassNode cn : classMap.values()) {
			try {
				boolean modified = false;
				for (Object o : cn.methods) {
					MethodNode mn = (MethodNode) o;
					if (mn.instructions == null) continue;
					if (decryptMethod(mn, cn.name, classMap)) {
						modified = true;
					}
				}

				if (modified) {
					ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
					cn.accept(cw);
					results.put(cn.name + ".class", cw.toByteArray());
					totalClasses++;
				}
			} catch (Exception e) {
			}
		}

		writeOutput(jarFile, results, outputFile);
		if (callback != null) {
			callback.onProgress("Advanced string decryption complete: " + totalDecrypted
					+ " strings decrypted in " + totalClasses + " classes");
		}
	}

	private boolean decryptMethod(MethodNode mn, String className, Map<String, ClassNode> allClasses) {
		boolean modified = false;
		InsnList instructions = mn.instructions;

		for (int i = 0; i < instructions.size(); i++) {
			AbstractInsnNode insn = instructions.get(i);

			if (insn.getType() == AbstractInsnNode.LDC_INSN) {
				LdcInsnNode ldc = (LdcInsnNode) insn;
				if (ldc.cst instanceof String) {
					String str = (String) ldc.cst;
					String decrypted = tryAllDecryptionMethods(str);
					if (decrypted != null && !decrypted.equals(str)) {
						instructions.set(ldc, new LdcInsnNode(decrypted));
						modified = true;
						totalDecrypted++;
						continue;
					}
					// Try AES decryption with collected keys
					String aesResult = tryAESDecrypt(str);
					if (aesResult != null) {
						instructions.set(ldc, new LdcInsnNode(aesResult));
						modified = true;
						totalDecrypted++;
						continue;
					}
				}
			}

			if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
				MethodInsnNode min = (MethodInsnNode) insn;
				if (isObfuscatedDecryptMethod(min)) {
					String decrypted = tryInvokeDecrypt(instructions, i, min);
					if (decrypted != null) {
						if (i > 0) {
							AbstractInsnNode prev = instructions.get(i - 1);
							instructions.remove(min);
							instructions.set(prev, new LdcInsnNode(decrypted));
							modified = true;
							totalDecrypted++;
							i--;
							continue;
						}
					}
				}
				// Try custom decrypt method interpretation
				String customKey = min.owner + "." + min.name + min.desc;
				if (customDecryptMethods.containsKey(customKey)) {
					String decrypted = tryCustomDecryptInterpret(instructions, i, min);
					if (decrypted != null) {
						if (i > 0) {
							AbstractInsnNode prev = instructions.get(i - 1);
							instructions.remove(min);
							instructions.set(prev, new LdcInsnNode(decrypted));
							modified = true;
							totalDecrypted++;
							i--;
							continue;
						}
					}
				}
			}

			if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
				MethodInsnNode min = (MethodInsnNode) insn;
				if (min.owner != null && min.owner.equals("java/lang/StringBuilder") && min.name.equals("<init>")) {
					if (tryStringBuilderReconstruct(instructions, i)) {
						modified = true;
						totalDecrypted++;
					}
				}
			}

			if (insn.getOpcode() == Opcodes.NEWARRAY) {
				if (tryCharArrayReconstruct(instructions, i)) {
					modified = true;
					totalDecrypted++;
				}
			}

			if (insn.getType() == AbstractInsnNode.LDC_INSN) {
				LdcInsnNode ldc = (LdcInsnNode) insn;
				if (ldc.cst instanceof String) {
					String str = (String) ldc.cst;
					String allatoriResult = tryAllatoriDecrypt(str, instructions, i);
					if (allatoriResult != null) {
						instructions.set(ldc, new LdcInsnNode(allatoriResult));
						modified = true;
						totalDecrypted++;
						continue;
					}
				}
			}

			if (insn.getType() == AbstractInsnNode.LDC_INSN) {
				LdcInsnNode ldc = (LdcInsnNode) insn;
				if (ldc.cst instanceof String) {
					String str = (String) ldc.cst;
					String stringerResult = tryStringerDecrypt(str, instructions, i);
					if (stringerResult != null) {
						instructions.set(ldc, new LdcInsnNode(stringerResult));
						modified = true;
						totalDecrypted++;
						continue;
					}
				}
			}

			if (insn.getType() == AbstractInsnNode.LDC_INSN) {
				LdcInsnNode ldc = (LdcInsnNode) insn;
				if (ldc.cst instanceof String) {
					String str = (String) ldc.cst;
					String zkmResult = tryZkmDecrypt(str, instructions, i);
					if (zkmResult != null) {
						instructions.set(ldc, new LdcInsnNode(zkmResult));
						modified = true;
						totalDecrypted++;
						continue;
					}
				}
			}

			if (insn.getType() == AbstractInsnNode.LDC_INSN) {
				LdcInsnNode ldc = (LdcInsnNode) insn;
				if (ldc.cst instanceof String) {
					String str = (String) ldc.cst;
					String dashOResult = tryDashODecrypt(str, instructions, i);
					if (dashOResult != null) {
						instructions.set(ldc, new LdcInsnNode(dashOResult));
						modified = true;
						totalDecrypted++;
						continue;
					}
				}
			}

			// Base64 encoded string detection
			if (insn.getType() == AbstractInsnNode.LDC_INSN) {
				LdcInsnNode ldc = (LdcInsnNode) insn;
				if (ldc.cst instanceof String) {
					String str = (String) ldc.cst;
					String b64Result = tryBase64Decode(str);
					if (b64Result != null) {
						instructions.set(ldc, new LdcInsnNode(b64Result));
						modified = true;
						totalDecrypted++;
						continue;
					}
				}
			}

			// XOR + Base64 combo (many obfuscators do XOR then base64)
			if (insn.getType() == AbstractInsnNode.LDC_INSN) {
				LdcInsnNode ldc = (LdcInsnNode) insn;
				if (ldc.cst instanceof String) {
					String str = (String) ldc.cst;
					String xorB64Result = tryXorBase64Combo(str);
					if (xorB64Result != null) {
						instructions.set(ldc, new LdcInsnNode(xorB64Result));
						modified = true;
						totalDecrypted++;
						continue;
					}
				}
			}

			// Skidfuscator / Colonial / qProtect style XOR variants
			if (insn.getType() == AbstractInsnNode.LDC_INSN) {
				LdcInsnNode ldc = (LdcInsnNode) insn;
				if (ldc.cst instanceof String) {
					String str = (String) ldc.cst;
					String skidResult = trySkidfuscatorDecrypt(str);
					if (skidResult != null) {
						instructions.set(ldc, new LdcInsnNode(skidResult));
						modified = true;
						totalDecrypted++;
						continue;
					}
				}
			}

			// Invokedynamic-based string hiding
			if (insn.getType() == AbstractInsnNode.INVOKE_DYNAMIC_INSN) {
				String indyResult = tryIndyStringRecover(instructions, i);
				if (indyResult != null) {
					instructions.set(insn, new LdcInsnNode(indyResult));
					modified = true;
					totalDecrypted++;
					continue;
				}
			}
		}

		// String concatenation reconstruction (split strings like "Trig" + "ger" + "bot")
		modified |= tryStringConcatReconstruct(mn);

		return modified;
	}

	private String tryAllDecryptionMethods(String str) {
		if (str == null || str.isEmpty() || str.length() > 500) return null;

		boolean hasHighChars = false;
		for (char c : str.toCharArray()) {
			if (c > 127) { hasHighChars = true; break; }
		}

		if (hasHighChars) {
			for (int key = 1; key <= 255; key++) {
				String result = xorWithKey(str, key);
				if (isReadable(result)) return result;
			}

			String addResult = tryAdditionCipher(str);
			if (addResult != null) return addResult;

			String shiftResult = tryBitShiftCipher(str);
			if (shiftResult != null) return shiftResult;

			String multiXorResult = tryMultiByteXor(str);
			if (multiXorResult != null) return multiXorResult;

			String rotResult = tryRotCipher(str);
			if (rotResult != null) return rotResult;

			String reverseResult = tryReverseCipher(str);
			if (reverseResult != null) return reverseResult;
		}

		// Try base64 even without high chars (some obfuscators base64 plain ASCII)
		String b64Result = tryBase64Decode(str);
		if (b64Result != null) return b64Result;

		return null;
	}

	private String tryAllatoriDecrypt(String str, InsnList instructions, int index) {
		if (str == null || str.isEmpty() || str.length() > 500) return null;

		boolean hasHighChars = false;
		for (char c : str.toCharArray()) {
			if (c > 127) { hasHighChars = true; break; }
		}
		if (!hasHighChars) return null;

		for (int key = 1; key <= 255; key++) {
			String result = allatoriXor(str, key);
			if (isReadable(result)) return result;
		}

		for (int key1 = 1; key1 <= 255; key1++) {
			for (int key2 = 1; key2 <= 255; key2++) {
				if (key1 == key2) continue;
				String result = allatoriDoubleXor(str, key1, key2);
				if (isReadable(result)) return result;
			}
		}

		for (int key = 1; key <= 255; key++) {
			String result = allatoriAddSub(str, key);
			if (isReadable(result)) return result;
		}

		return null;
	}

	private String allatoriXor(String str, int key) {
		char[] chars = str.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			chars[i] = (char) (chars[i] ^ key);
		}
		return new String(chars);
	}

	private String allatoriDoubleXor(String str, int key1, int key2) {
		char[] chars = str.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			chars[i] = (char) (chars[i] ^ (i % 2 == 0 ? key1 : key2));
		}
		return new String(chars);
	}

	private String allatoriAddSub(String str, int key) {
		char[] chars = str.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			chars[i] = (char) ((chars[i] - key + (i * 7)) & 0xFFFF);
		}
		return new String(chars);
	}

	private String tryStringerDecrypt(String str, InsnList instructions, int index) {
		if (str == null || str.isEmpty() || str.length() > 500) return null;

		boolean hasHighChars = false;
		for (char c : str.toCharArray()) {
			if (c > 127) { hasHighChars = true; break; }
		}
		if (!hasHighChars) return null;

		for (int key = 1; key <= 255; key++) {
			String result = stringerShiftXor(str, key);
			if (isReadable(result)) return result;
		}

		for (int key = 1; key <= 255; key++) {
			String result = stringerBitRotate(str, key);
			if (isReadable(result)) return result;
		}

		for (int key = 1; key <= 255; key++) {
			String result = stringerAddKey(str, key);
			if (isReadable(result)) return result;
		}

		return null;
	}

	private String stringerShiftXor(String str, int key) {
		char[] chars = str.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			chars[i] = (char) ((chars[i] >> (key & 7)) ^ key);
		}
		return new String(chars);
	}

	private String stringerBitRotate(String str, int key) {
		char[] chars = str.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			int c = chars[i] & 0xFF;
			int shift = key & 7;
			c = ((c << shift) | (c >> (8 - shift))) & 0xFF;
			chars[i] = (char) c;
		}
		return new String(chars);
	}

	private String stringerAddKey(String str, int key) {
		char[] chars = str.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			chars[i] = (char) ((chars[i] + key + i) & 0xFF);
		}
		return new String(chars);
	}

	private String tryZkmDecrypt(String str, InsnList instructions, int index) {
		if (str == null || str.isEmpty() || str.length() > 500) return null;

		boolean hasHighChars = false;
		for (char c : str.toCharArray()) {
			if (c > 127) { hasHighChars = true; break; }
		}
		if (!hasHighChars) return null;

		for (int key = 1; key <= 255; key++) {
			String result = zkmXorWithPosition(str, key);
			if (isReadable(result)) return result;
		}

		for (int key = 1; key <= 255; key++) {
			String result = zkmAddWithPosition(str, key);
			if (isReadable(result)) return result;
		}

		for (int key = 1; key <= 255; key++) {
			String result = zkmXorAddMix(str, key);
			if (isReadable(result)) return result;
		}

		return null;
	}

	private String zkmXorWithPosition(String str, int key) {
		char[] chars = str.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			chars[i] = (char) (chars[i] ^ (key + i));
		}
		return new String(chars);
	}

	private String zkmAddWithPosition(String str, int key) {
		char[] chars = str.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			chars[i] = (char) ((chars[i] - key - i) & 0xFF);
		}
		return new String(chars);
	}

	private String zkmXorAddMix(String str, int key) {
		char[] chars = str.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			chars[i] = (char) ((chars[i] ^ key) + i & 0xFF);
		}
		return new String(chars);
	}

	private String tryDashODecrypt(String str, InsnList instructions, int index) {
		if (str == null || str.isEmpty() || str.length() > 500) return null;

		boolean hasHighChars = false;
		for (char c : str.toCharArray()) {
			if (c > 127) { hasHighChars = true; break; }
		}
		if (!hasHighChars) return null;

		for (int key = 1; key <= 255; key++) {
			String result = dashOMultiPass(str, key);
			if (isReadable(result)) return result;
		}

		for (int key = 1; key <= 255; key++) {
			String result = dashOXorWithIndex(str, key);
			if (isReadable(result)) return result;
		}

		return null;
	}

	private String dashOMultiPass(String str, int key) {
		char[] chars = str.toCharArray();
		for (int pass = 0; pass < 3; pass++) {
			for (int i = 0; i < chars.length; i++) {
				chars[i] = (char) (chars[i] ^ (key + pass));
			}
		}
		return new String(chars);
	}

	private String dashOXorWithIndex(String str, int key) {
		char[] chars = str.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			chars[i] = (char) (chars[i] ^ (key * (i + 1)));
		}
		return new String(chars);
	}

	private String tryRotCipher(String str) {
		for (int rot = 1; rot < 26; rot++) {
			StringBuilder sb = new StringBuilder();
			for (char c : str.toCharArray()) {
				if (c >= 'a' && c <= 'z') {
					sb.append((char) ('a' + (c - 'a' + rot) % 26));
				} else if (c >= 'A' && c <= 'Z') {
					sb.append((char) ('A' + (c - 'A' + rot) % 26));
				} else {
					sb.append(c);
				}
			}
			if (isReadable(sb.toString())) return sb.toString();
		}
		return null;
	}

	private String tryReverseCipher(String str) {
		StringBuilder sb = new StringBuilder(str);
		String reversed = sb.reverse().toString();
		if (isReadable(reversed)) return reversed;

		StringBuilder sb2 = new StringBuilder();
		for (int i = 0; i < str.length() - 1; i += 2) {
			sb2.append(str.charAt(i + 1));
			sb2.append(str.charAt(i));
		}
		if (str.length() % 2 != 0) sb2.append(str.charAt(str.length() - 1));
		String swapped = sb2.toString();
		if (isReadable(swapped)) return swapped;

		return null;
	}

	private boolean isObfuscatedDecryptMethod(MethodInsnNode min) {
		if (min.desc == null) return false;
		if (min.desc.contains("Ljava/lang/String;") && min.desc.endsWith("Ljava/lang/String;")) {
			if (min.owner != null) {
				if (min.owner.startsWith("java/")) return false;
				if (min.owner.equals("java/lang/String")) return false;
				return true;
			}
		}
		if (min.desc.contains("(I)Ljava/lang/String;")) {
			if (min.owner != null && !min.owner.startsWith("java/")) {
				return true;
			}
		}
		if (min.desc.contains("([C)Ljava/lang/String;")) {
			if (min.owner != null && !min.owner.startsWith("java/")) {
				return true;
			}
		}
		return false;
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
			if (isReadable(result)) return result;
		}

		for (int key = 1; key <= 255; key++) {
			String result = allatoriDoubleXor(encrypted, key, (key * 7) & 0xFF);
			if (isReadable(result)) return result;
		}

		for (int key = 1; key <= 255; key++) {
			String result = zkmXorWithPosition(encrypted, key);
			if (isReadable(result)) return result;
		}

		for (int key = 1; key <= 255; key++) {
			String result = stringerShiftXor(encrypted, key);
			if (isReadable(result)) return result;
		}

		for (int key = 1; key <= 255; key++) {
			String result = dashOMultiPass(encrypted, key);
			if (isReadable(result)) return result;
		}

		return tryAllDecryptionMethods(encrypted);
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

		while (insn != null && appendCount < 100) {
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

		if (arraySize <= 0 || arraySize > 1000) return false;

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

	private String xorWithKey(String str, int key) {
		char[] chars = str.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			chars[i] = (char) (chars[i] ^ key);
		}
		return new String(chars);
	}

	private String tryAdditionCipher(String str) {
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
		if (str.length() < 4) return null;
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
				if (c == 0) { keyFound = false; break; }
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
	}

	// ========== New decryption methods ==========

	private String tryBase64Decode(String str) {
		if (str == null || str.length() < 4) return null;
		// Check if it looks like base64
		if (str.length() % 4 != 0) {
			// Try with padding
			int pad = 4 - (str.length() % 4);
			if (pad < 4) str = str + new String(new char[pad]).replace('\0', '=');
		}
		boolean looksLikeBase64 = true;
		for (char c : str.toCharArray()) {
			if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
					|| c == '+' || c == '/' || c == '=')) {
				looksLikeBase64 = false;
				break;
			}
		}
		if (!looksLikeBase64) return null;
		if (str.length() < 8) return null; // too short to be meaningful

		try {
			byte[] decoded = java.util.Base64.getDecoder().decode(str);
			String result = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
			if (isReadable(result) && result.length() >= 2) return result;
			// Try ISO-8859-1
			result = new String(decoded, java.nio.charset.StandardCharsets.ISO_8859_1);
			if (isReadable(result) && result.length() >= 2) return result;
		} catch (Exception e) {}
		return null;
	}

	private String tryXorBase64Combo(String str) {
		if (str == null || str.length() < 8) return null;
		// First try base64 decode, then XOR the decoded bytes
		boolean looksLikeBase64 = true;
		String padded = str;
		if (str.length() % 4 != 0) {
			int pad = 4 - (str.length() % 4);
			if (pad < 4) padded = str + new String(new char[pad]).replace('\0', '=');
		}
		for (char c : padded.toCharArray()) {
			if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
					|| c == '+' || c == '/' || c == '=')) {
				looksLikeBase64 = false;
				break;
			}
		}
		if (!looksLikeBase64) return null;

		try {
			byte[] decoded = java.util.Base64.getDecoder().decode(padded);
			// XOR decoded bytes with each possible key
			for (int key = 1; key <= 255; key++) {
				byte[] xored = new byte[decoded.length];
				for (int j = 0; j < decoded.length; j++) {
					xored[j] = (byte) (decoded[j] ^ key);
				}
				String result = new String(xored, java.nio.charset.StandardCharsets.UTF_8);
				if (isReadable(result) && result.length() >= 2) return result;
			}
			// XOR with position-dependent key
			for (int key = 1; key <= 255; key++) {
				byte[] xored = new byte[decoded.length];
				for (int j = 0; j < decoded.length; j++) {
					xored[j] = (byte) (decoded[j] ^ (key + j));
				}
				String result = new String(xored, java.nio.charset.StandardCharsets.UTF_8);
				if (isReadable(result) && result.length() >= 2) return result;
			}
		} catch (Exception e) {}
		return null;
	}

	private String trySkidfuscatorDecrypt(String str) {
		if (str == null || str.isEmpty() || str.length() > 500) return null;

		boolean hasHighChars = false;
		for (char c : str.toCharArray()) {
			if (c > 127) { hasHighChars = true; break; }
		}
		if (!hasHighChars) return null;

		// Skidfuscator style: XOR with rotating key derived from string length
		for (int key = 1; key <= 255; key++) {
			char[] chars = str.toCharArray();
			for (int i = 0; i < chars.length; i++) {
				chars[i] = (char) (chars[i] ^ (key * (i + 1) & 0xFF));
			}
			String result = new String(chars);
			if (isReadable(result)) return result;
		}

		// Colonial style: hard-coded switch-case, effectively XOR with constant + index
		for (int key = 1; key <= 255; key++) {
			char[] chars = str.toCharArray();
			for (int i = 0; i < chars.length; i++) {
				chars[i] = (char) ((chars[i] ^ key) - i & 0xFF);
			}
			String result = new String(chars);
			if (isReadable(result)) return result;
		}

		// qProtect style: XOR with key, then add key
		for (int key = 1; key <= 255; key++) {
			char[] chars = str.toCharArray();
			for (int i = 0; i < chars.length; i++) {
				chars[i] = (char) ((chars[i] ^ key) + key & 0xFF);
			}
			String result = new String(chars);
			if (isReadable(result)) return result;
		}

		// Souvenir style: simple XOR with multi-byte key cycling
		for (int keyLen = 2; keyLen <= 4; keyLen++) {
			for (int k0 = 1; k0 <= 255; k0++) {
				char[] chars = str.toCharArray();
				boolean ok = true;
				for (int i = 0; i < chars.length; i++) {
					chars[i] = (char) (chars[i] ^ (k0 + i * keyLen));
					if (chars[i] > 127 && i < 3) { ok = false; break; }
				}
				if (ok) {
					String result = new String(chars);
					if (isReadable(result)) return result;
				}
			}
		}

		// BranchLock / Caesium style: XOR with key then base64
		String b64xorResult = tryXorBase64Combo(str);
		if (b64xorResult != null) return b64xorResult;

		return null;
	}

	private String tryIndyStringRecover(InsnList instructions, int index) {
		AbstractInsnNode insn = instructions.get(index);
		if (!(insn instanceof InvokeDynamicInsnNode)) return null;
		InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;

		// Check if it's a string-related bootstrap method
		if (indy.bsmArgs != null && indy.bsmArgs.length > 0) {
			for (Object arg : indy.bsmArgs) {
				if (arg instanceof String) {
					String s = (String) arg;
					if (isReadable(s) && s.length() >= 2) return s;
				}
				if (arg instanceof Handle) {
					Handle h = (Handle) arg;
					if (h.getName() != null && h.getName().length() > 2 && isReadable(h.getName())) {
						return h.getName();
					}
				}
			}
		}

		// Look for LDC string before the indy
		if (index > 0) {
			AbstractInsnNode prev = instructions.get(index - 1);
			if (prev.getType() == AbstractInsnNode.LDC_INSN) {
				LdcInsnNode ldc = (LdcInsnNode) prev;
				if (ldc.cst instanceof String) {
					String str = (String) ldc.cst;
					// Try all decryption methods on the string
					String decrypted = tryAllDecryptionMethods(str);
					if (decrypted != null) return decrypted;
				}
			}
		}
		return null;
	}

	private boolean tryStringConcatReconstruct(MethodNode mn) {
		InsnList instructions = mn.instructions;
		boolean modified = false;

		for (int i = 0; i < instructions.size(); i++) {
			AbstractInsnNode insn = instructions.get(i);

			// Look for StringBuilder.<init>() pattern
			if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
				MethodInsnNode min = (MethodInsnNode) insn;
				if (min.owner != null && min.owner.equals("java/lang/StringBuilder") && min.name.equals("<init>")) {
					// Find the NEW before it
					int newIdx = -1;
					for (int j = i - 1; j >= Math.max(0, i - 5); j--) {
						AbstractInsnNode n = instructions.get(j);
						if (n.getOpcode() == Opcodes.NEW) {
							TypeInsnNode tin = (TypeInsnNode) n;
							if (tin.desc != null && tin.desc.equals("java/lang/StringBuilder")) {
								newIdx = j;
								break;
							}
						}
					}
					if (newIdx == -1) continue;

					// Collect all append() calls and find the toString()
					StringBuilder sb = new StringBuilder();
					int appendCount = 0;
					int lastAppendIdx = -1;
					int toStringIdx = -1;

					for (int j = i + 1; j < instructions.size() && j < i + 200; j++) {
						AbstractInsnNode n = instructions.get(j);
						if (n.getOpcode() == Opcodes.INVOKEVIRTUAL) {
							MethodInsnNode m = (MethodInsnNode) n;
							if (m.owner != null && m.owner.equals("java/lang/StringBuilder")) {
								if (m.name.equals("append")) {
									// Find the argument
									if (j > 0) {
										AbstractInsnNode arg = instructions.get(j - 1);
										if (arg.getType() == AbstractInsnNode.LDC_INSN) {
											LdcInsnNode ldc = (LdcInsnNode) arg;
											if (ldc.cst instanceof String) {
												sb.append((String) ldc.cst);
												appendCount++;
												lastAppendIdx = j;
											} else if (ldc.cst instanceof Character) {
												sb.append((Character) ldc.cst);
												appendCount++;
												lastAppendIdx = j;
											} else if (ldc.cst instanceof Integer) {
												sb.append((char) ((Integer) ldc.cst).intValue());
												appendCount++;
												lastAppendIdx = j;
											}
										} else if (arg.getOpcode() == Opcodes.BIPUSH) {
											sb.append((char) ((IntInsnNode) arg).operand);
											appendCount++;
											lastAppendIdx = j;
										} else if (arg.getOpcode() == Opcodes.SIPUSH) {
											sb.append((char) ((IntInsnNode) arg).operand);
											appendCount++;
											lastAppendIdx = j;
										}
									}
								} else if (m.name.equals("toString")) {
									toStringIdx = j;
									break;
								}
							}
						}
					}

					// If we found 2+ appends and a toString, replace the whole sequence
					if (appendCount >= 2 && toStringIdx > 0 && sb.length() > 0) {
						String result = sb.toString();
						if (isReadable(result)) {
							// Replace from NEW to toString with LDC
							// Remove instructions from newIdx to toStringIdx
							for (int j = toStringIdx; j >= newIdx; j--) {
								AbstractInsnNode toRemove = instructions.get(j);
								instructions.remove(toRemove);
							}
							instructions.insert(newIdx > 0 ? instructions.get(newIdx - 1) : null,
									new LdcInsnNode(result));
							modified = true;
							totalDecrypted++;
							// Restart scanning since we modified the list
							i = Math.max(0, newIdx - 1);
						}
					}
				}
			}
		}

		return modified;
	}

	// ========== AES Decryption ==========

	private boolean isCustomDecryptMethod(MethodNode mn, String className) {
		// Must be static, take a String parameter, return a String
		if ((mn.access & Opcodes.ACC_STATIC) == 0) return false;
		if (mn.desc == null) return false;
		// (Ljava/lang/String;)Ljava/lang/String; or (Ljava/lang/String;I)Ljava/lang/String;
		if (mn.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")
				|| mn.desc.equals("(Ljava/lang/String;I)Ljava/lang/String;")
				|| mn.desc.equals("(Ljava/lang/String;[B)Ljava/lang/String;")
				|| mn.desc.equals("([B)Ljava/lang/String;")
				|| mn.desc.equals("([BI)Ljava/lang/String;")) {
			// Check that the method body contains XOR or arithmetic operations (typical decrypt)
			if (mn.instructions == null) return false;
			boolean hasCryptoOps = false;
			for (int i = 0; i < mn.instructions.size(); i++) {
				AbstractInsnNode insn = mn.instructions.get(i);
				int op = insn.getOpcode();
				if (op == Opcodes.IXOR || op == Opcodes.IADD || op == Opcodes.ISUB
						|| op == Opcodes.IUSHR || op == Opcodes.ISHL || op == Opcodes.IXOR
						|| op == Opcodes.ILOAD || op == Opcodes.CALOAD || op == Opcodes.IALOAD
						|| op == Opcodes.BALOAD) {
					hasCryptoOps = true;
					break;
				}
			}
			return hasCryptoOps;
		}
		return false;
	}

	private void collectAESKeys(ClassNode cn) {
		if (cn.methods == null) return;
		for (Object o : cn.methods) {
			MethodNode mn = (MethodNode) o;
			if (mn.instructions == null) continue;

			for (int i = 0; i < mn.instructions.size(); i++) {
				AbstractInsnNode insn = mn.instructions.get(i);
				if (insn.getOpcode() == Opcodes.INVOKESTATIC || insn.getOpcode() == Opcodes.INVOKESPECIAL) {
					MethodInsnNode min = (MethodInsnNode) insn;
					// Look for SecretKeySpec constructor or KeyFactory
					if (min.owner != null && min.owner.equals("javax/crypto/spec/SecretKeySpec")) {
						// Look backwards for the key bytes (LDC or byte array)
						byte[] key = extractByteArrayBefore(mn.instructions, i);
						if (key != null && key.length >= 16) {
							aesKeys.add(key);
						}
					}
				}
				// Look for LDC byte arrays that could be AES keys
				if (insn.getType() == AbstractInsnNode.LDC_INSN) {
					LdcInsnNode ldc = (LdcInsnNode) insn;
					if (ldc.cst instanceof byte[]) {
						byte[] data = (byte[]) ldc.cst;
						if (data.length == 16 || data.length == 24 || data.length == 32) {
							aesKeys.add(data);
						}
					}
				}
			}
		}
	}

	private byte[] extractByteArrayBefore(InsnList instructions, int index) {
		// Walk backwards to find NEWARRAY + BIPUSH/SIPUSH sequence
		for (int i = index - 1; i >= Math.max(0, index - 100); i--) {
			AbstractInsnNode insn = instructions.get(i);
			if (insn.getOpcode() == Opcodes.NEWARRAY) {
				// Find the array size
				if (i > 0) {
					AbstractInsnNode prev = instructions.get(i - 1);
					if (prev.getOpcode() == Opcodes.BIPUSH) {
						int size = ((IntInsnNode) prev).operand;
						if (size >= 16 && size <= 32) {
							// Try to collect the byte values
							byte[] key = new byte[size];
							int collected = 0;
							for (int j = i + 1; j < instructions.size() && collected < size; j++) {
								AbstractInsnNode n = instructions.get(j);
								if (n.getOpcode() == Opcodes.BIPUSH) {
									key[collected++] = (byte) ((IntInsnNode) n).operand;
								} else if (n.getOpcode() == Opcodes.ICONST_0) {
									key[collected++] = 0;
								} else if (n.getOpcode() == Opcodes.ICONST_1) {
									key[collected++] = 1;
								} else if (n.getOpcode() == Opcodes.DUP) {
									continue;
								} else if (n.getOpcode() == Opcodes.BASTORE) {
									continue;
								} else {
									break;
								}
							}
							if (collected == size) return key;
						}
					}
				}
			}
		}
		return null;
	}

	private String tryAESDecrypt(String str) {
		if (str == null || str.length() < 16) return null;
		if (aesKeys.isEmpty()) return null;

		// Try base64 decode first (AES encrypted data is usually base64 encoded)
		byte[] encrypted = null;
		try {
			encrypted = java.util.Base64.getDecoder().decode(str);
		} catch (Exception e) {
			// Try raw bytes
			encrypted = str.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		}

		if (encrypted == null || encrypted.length == 0 || encrypted.length % 16 != 0) return null;

		for (byte[] key : aesKeys) {
			try {
				javax.crypto.spec.SecretKeySpec sks = new javax.crypto.spec.SecretKeySpec(key, "AES");
				javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
				cipher.init(javax.crypto.Cipher.DECRYPT_MODE, sks);
				byte[] decrypted = cipher.doFinal(encrypted);
				String result = new String(decrypted, java.nio.charset.StandardCharsets.UTF_8);
				if (isReadable(result) && result.length() >= 2) {
					// Strip padding
					int padLen = result.length();
					while (padLen > 0 && result.charAt(padLen - 1) == 0) padLen--;
					return result.substring(0, padLen);
				}
			} catch (Exception e) {}
			try {
				javax.crypto.spec.SecretKeySpec sks = new javax.crypto.spec.SecretKeySpec(key, "AES");
				javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
				cipher.init(javax.crypto.Cipher.DECRYPT_MODE, sks, new javax.crypto.spec.IvParameterSpec(new byte[16]));
				byte[] decrypted = cipher.doFinal(encrypted);
				String result = new String(decrypted, java.nio.charset.StandardCharsets.UTF_8);
				if (isReadable(result) && result.length() >= 2) {
					int padLen = result.length();
					while (padLen > 0 && result.charAt(padLen - 1) == 0) padLen--;
					return result.substring(0, padLen);
				}
			} catch (Exception e) {}
		}
		return null;
	}

	// ========== Custom Decrypt Method Interpretation ==========

	private String tryCustomDecryptInterpret(InsnList instructions, int index, MethodInsnNode min) {
		String customKey = min.owner + "." + min.name + min.desc;
		MethodNode decryptMethod = customDecryptMethods.get(customKey);
		if (decryptMethod == null || decryptMethod.instructions == null) return null;

		// Find the argument string (LDC before the INVOKESTATIC)
		String inputArg = null;
		int intArg = 0;
		if (index > 0) {
			AbstractInsnNode prev = instructions.get(index - 1);
			if (prev.getType() == AbstractInsnNode.LDC_INSN) {
				LdcInsnNode ldc = (LdcInsnNode) prev;
				if (ldc.cst instanceof String) {
					inputArg = (String) ldc.cst;
				} else if (ldc.cst instanceof Integer) {
					intArg = (Integer) ldc.cst;
					// Look further back for the string
					if (index > 1) {
						AbstractInsnNode prev2 = instructions.get(index - 2);
						if (prev2.getType() == AbstractInsnNode.LDC_INSN) {
							LdcInsnNode ldc2 = (LdcInsnNode) prev2;
							if (ldc2.cst instanceof String) {
								inputArg = (String) ldc2.cst;
							}
						}
					}
				}
			}
		}

		if (inputArg == null) return null;

		// Interpret the decrypt method bytecode
		return interpretDecryptMethod(decryptMethod, inputArg, intArg);
	}

	private String interpretDecryptMethod(MethodNode mn, String input, int intKey) {
		if (mn.instructions == null) return null;
		InsnList instructions = mn.instructions;

		// Simple bytecode interpreter for common decrypt patterns
		// Stack-based: we simulate the JVM stack
		java.util.Deque<Object> stack = new java.util.ArrayDeque<>();
		char[] chars = input.toCharArray();
		int[] intArray = null;
		byte[] byteArray = null;

		try {
			for (int i = 0; i < instructions.size(); i++) {
				AbstractInsnNode insn = instructions.get(i);
				int op = insn.getOpcode();

				switch (op) {
				case Opcodes.ACONST_NULL: stack.push(null); break;
				case Opcodes.ICONST_0: stack.push(0); break;
				case Opcodes.ICONST_1: stack.push(1); break;
				case Opcodes.ICONST_2: stack.push(2); break;
				case Opcodes.ICONST_3: stack.push(3); break;
				case Opcodes.ICONST_4: stack.push(4); break;
				case Opcodes.ICONST_5: stack.push(5); break;
				case Opcodes.BIPUSH: stack.push(((IntInsnNode) insn).operand); break;
				case Opcodes.SIPUSH: stack.push(((IntInsnNode) insn).operand); break;
				case Opcodes.LDC: {
					LdcInsnNode ldc = (LdcInsnNode) insn;
					if (ldc.cst instanceof String) stack.push(ldc.cst);
					else if (ldc.cst instanceof Integer) stack.push(ldc.cst);
					else if (ldc.cst instanceof byte[]) stack.push(ldc.cst);
					break;
				}
				case Opcodes.ILOAD:
				case Opcodes.ALOAD: {
					// Load from local variable - we only support param 0 (input string) and param 1 (int key)
					org.objectweb.asm.tree.VarInsnNode vin = (org.objectweb.asm.tree.VarInsnNode) insn;
					if (vin.var == 0) stack.push(input);
					else if (vin.var == 1) stack.push(intKey);
					break;
				}
				case Opcodes.ARRAYLENGTH: {
					Object arr = stack.pop();
					if (arr instanceof char[]) stack.push(((char[]) arr).length);
					else if (arr instanceof byte[]) stack.push(((byte[]) arr).length);
					else if (arr instanceof int[]) stack.push(((int[]) arr).length);
					break;
				}
				case Opcodes.CALOAD:
				case Opcodes.IALOAD:
				case Opcodes.BALOAD: {
					int idx = (Integer) stack.pop();
					Object arr = stack.pop();
					if (arr instanceof char[]) {
						char c = ((char[]) arr)[idx];
						stack.push((int) c);
					} else if (arr instanceof byte[]) {
						byte b = ((byte[]) arr)[idx];
						stack.push((int) b);
					} else if (arr instanceof int[]) {
						stack.push(((int[]) arr)[idx]);
					}
					break;
				}
				case Opcodes.IADD: {
					int b = (Integer) stack.pop();
					int a = (Integer) stack.pop();
					stack.push(a + b);
					break;
				}
				case Opcodes.ISUB: {
					int b = (Integer) stack.pop();
					int a = (Integer) stack.pop();
					stack.push(a - b);
					break;
				}
				case Opcodes.IMUL: {
					int b = (Integer) stack.pop();
					int a = (Integer) stack.pop();
					stack.push(a * b);
					break;
				}
				case Opcodes.IXOR: {
					int b = (Integer) stack.pop();
					int a = (Integer) stack.pop();
					stack.push(a ^ b);
					break;
				}
				case Opcodes.IAND: {
					int b = (Integer) stack.pop();
					int a = (Integer) stack.pop();
					stack.push(a & b);
					break;
				}
				case Opcodes.IOR: {
					int b = (Integer) stack.pop();
					int a = (Integer) stack.pop();
					stack.push(a | b);
					break;
				}
				case Opcodes.ISHL: {
					int b = (Integer) stack.pop();
					int a = (Integer) stack.pop();
					stack.push(a << b);
					break;
				}
				case Opcodes.IUSHR: {
					int b = (Integer) stack.pop();
					int a = (Integer) stack.pop();
					stack.push(a >>> b);
					break;
				}
				case Opcodes.ISHR: {
					int b = (Integer) stack.pop();
					int a = (Integer) stack.pop();
					stack.push(a >> b);
					break;
				}
				case Opcodes.INEG: {
					int a = (Integer) stack.pop();
					stack.push(-a);
					break;
				}
				case Opcodes.I2C: {
					int a = (Integer) stack.pop();
					stack.push(a & 0xFFFF);
					break;
				}
				case Opcodes.I2B: {
					int a = (Integer) stack.pop();
					stack.push(a & 0xFF);
					break;
				}
				case Opcodes.NEWARRAY: {
					int size = (Integer) stack.pop();
					IntInsnNode iin = (IntInsnNode) insn;
					if (iin.operand == Opcodes.T_CHAR) {
						stack.push(new char[size]);
					} else if (iin.operand == Opcodes.T_BYTE) {
						stack.push(new byte[size]);
					} else if (iin.operand == Opcodes.T_INT) {
						stack.push(new int[size]);
					}
					break;
				}
				case Opcodes.CASTORE:
				case Opcodes.BASTORE:
				case Opcodes.IASTORE: {
					int val = (Integer) stack.pop();
					int idx = (Integer) stack.pop();
					Object arr = stack.pop();
					if (arr instanceof char[]) ((char[]) arr)[idx] = (char) val;
					else if (arr instanceof byte[]) ((byte[]) arr)[idx] = (byte) val;
					else if (arr instanceof int[]) ((int[]) arr)[idx] = val;
					break;
				}
				case Opcodes.INVOKESTATIC: {
					MethodInsnNode min = (MethodInsnNode) insn;
					// Handle String.toCharArray()
					if (min.owner.equals("java/lang/String") && min.name.equals("toCharArray")) {
						String s = (String) stack.pop();
						stack.push(s.toCharArray());
					}
					// Handle String.getBytes()
					else if (min.owner.equals("java/lang/String") && min.name.equals("getBytes")) {
						String s = (String) stack.pop();
						stack.push(s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
					}
					// Handle String.valueOf(char[])
					else if (min.owner.equals("java/lang/String") && min.name.equals("valueOf")) {
						Object arg = stack.pop();
						if (arg instanceof char[]) stack.push(new String((char[]) arg));
						else if (arg instanceof byte[]) stack.push(new String((byte[]) arg, java.nio.charset.StandardCharsets.ISO_8859_1));
						else stack.push(String.valueOf(arg));
					}
					// Handle new String(byte[])
					else if (min.owner.equals("java/lang/String") && min.name.equals("<init>")) {
						// Constructor - skip for now
					}
					break;
				}
				case Opcodes.INVOKESPECIAL: {
					MethodInsnNode min = (MethodInsnNode) insn;
					if (min.owner.equals("java/lang/String") && min.name.equals("<init>")) {
						// new String(char[]) or new String(byte[])
						Object arg = stack.pop();
						// Pop the uninitialized ref
						if (!stack.isEmpty()) stack.pop();
						if (arg instanceof char[]) stack.push(new String((char[]) arg));
						else if (arg instanceof byte[]) stack.push(new String((byte[]) arg, java.nio.charset.StandardCharsets.ISO_8859_1));
						else stack.push(String.valueOf(arg));
					}
					break;
				}
				case Opcodes.INVOKEVIRTUAL: {
					MethodInsnNode min = (MethodInsnNode) insn;
					// String.charAt(int)
					if (min.owner.equals("java/lang/String") && min.name.equals("charAt")) {
						int idx = (Integer) stack.pop();
						String s = (String) stack.pop();
						stack.push((int) s.charAt(idx));
					}
					// String.length()
					else if (min.owner.equals("java/lang/String") && min.name.equals("length")) {
						String s = (String) stack.pop();
						stack.push(s.length());
					}
					// String.substring(int)
					else if (min.owner.equals("java/lang/String") && min.name.equals("substring")) {
						int idx = (Integer) stack.pop();
						String s = (String) stack.pop();
						stack.push(s.substring(idx));
					}
					// StringBuilder.append(char)
					else if (min.owner.equals("java/lang/StringBuilder") && min.name.equals("append")) {
						// Skip complex SB operations
					}
					// StringBuilder.toString()
					else if (min.owner.equals("java/lang/StringBuilder") && min.name.equals("toString")) {
						// Skip
					}
					break;
				}
				case Opcodes.ARETURN: {
					Object result = stack.pop();
					if (result instanceof String) return (String) result;
					if (result instanceof char[]) return new String((char[]) result);
					break;
				}
				case Opcodes.GOTO: {
					// Handle simple loops - find the jump target
					org.objectweb.asm.tree.JumpInsnNode jin = (org.objectweb.asm.tree.JumpInsnNode) insn;
					// For loops, we need to find the label index
					// This is complex - skip for now and try to handle common patterns
					break;
				}
				case Opcodes.IF_ICMPGE:
				case Opcodes.IF_ICMPGT:
				case Opcodes.IF_ICMPLT:
				case Opcodes.IF_ICMPLE:
				case Opcodes.IF_ICMPEQ:
				case Opcodes.IF_ICMPNE: {
					// Pop comparison values - loop handling is complex
					if (!stack.isEmpty()) stack.pop();
					if (!stack.isEmpty()) stack.pop();
					break;
				}
				case Opcodes.IINC: {
					// Increment local variable
					org.objectweb.asm.tree.IincInsnNode iin = (org.objectweb.asm.tree.IincInsnNode) insn;
					// We can't easily track local variable increments without full frame simulation
					break;
				}
				default:
					// Skip unknown opcodes
					break;
				}
			}

			// If we get here without ARETURN, try the last string on stack
			while (!stack.isEmpty()) {
				Object result = stack.pop();
				if (result instanceof String) return (String) result;
				if (result instanceof char[]) return new String((char[]) result);
			}
		} catch (Exception e) {
			// Interpretation failed
		}

		// Fallback: try common decrypt patterns on the input
		if (intKey != 0) {
			// XOR with intKey
			char[] result = new char[chars.length];
			for (int j = 0; j < chars.length; j++) {
				result[j] = (char) (chars[j] ^ intKey);
			}
			String r = new String(result);
			if (isReadable(r)) return r;
		}

		return null;
	}
}
