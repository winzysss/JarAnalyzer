package com.jaranalyzer;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.jaranalyzer.engine.ObfuscationDetails;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public class ObfuscationDetector {

	public enum ObfuscatorType {
		NONE("None"),
		PROGUARD("ProGuard"),
		ALLATORI("Allatori"),
		STRINGER("Stringer"),
		ZKM("Zelix KlassMaster"),
		DASHO("DashO"),
		JSHRINK("JShrink"),
		SMOKESCREEN("Smokescreen"),
		GENERIC("Generic");

		private final String displayName;

		ObfuscatorType(String displayName) {
			this.displayName = displayName;
		}

		/**
		 * Every other constant is a product name and stays as written; only
		 * NONE is an ordinary word, and leaving it as "None" put a stray English
		 * word in the middle of an otherwise Turkish summary line.
		 */
		public String getDisplayName() {
			if (this == NONE) return LanguageManager.getString("proc.none");
			return displayName;
		}
	}

	public static class DetectionResult {
		public final ObfuscatorType type;
		public final boolean isObfuscated;
		public final boolean hasEncryptedStrings;
		public final boolean hasShortNames;
		public final double obfuscationScore;

		public DetectionResult(ObfuscatorType type, boolean isObfuscated, boolean hasEncryptedStrings,
				boolean hasShortNames, double obfuscationScore) {
			this.type = type;
			this.isObfuscated = isObfuscated;
			this.hasEncryptedStrings = hasEncryptedStrings;
			this.hasShortNames = hasShortNames;
			this.obfuscationScore = obfuscationScore;
		}
	}

	public static DetectionResult detect(JarFile jarFile) {
		return detectWithDetails(jarFile).toDetectionResult();
	}

	public static ObfuscationDetails detectWithDetails(JarFile jarFile) {
		int totalClasses = 0;
		int shortNameClasses = 0;
		int shortNameMethods = 0;
		int totalMethods = 0;
		int encryptedStringPatterns = 0;
		int charArrayPatterns = 0;
		int sbPatterns = 0;
		int maxClassesToCheck = 30;

		int dashOPatterns = 0;
		int jshrinkPatterns = 0;
		int smokescreenPatterns = 0;
		int syntheticMethods = 0;
		int bridgeMethods = 0;
		int throwPatterns = 0;
		int gotoPatterns = 0;
		int nopPatterns = 0;
		List<String> sampleClasses = new ArrayList<>();

		Enumeration<JarEntry> entries = jarFile.entries();
		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();
			if (!entry.getName().endsWith(".class"))
				continue;
			if (totalClasses >= maxClassesToCheck)
				break;

			try {
				ClassReader cr = new ClassReader(jarFile.getInputStream(entry));
				ClassNode cn = new ClassNode();
				cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

				totalClasses++;

				String simpleName = cn.name;
				if (simpleName.contains("/"))
					simpleName = simpleName.substring(simpleName.lastIndexOf('/') + 1);
				if (simpleName.length() <= 2 && !simpleName.equals("<init>") && !simpleName.equals("<clinit>")) {
					shortNameClasses++;
					if (sampleClasses.size() < 10) sampleClasses.add(simpleName);
				}

				if ((cn.access & Opcodes.ACC_SYNTHETIC) != 0) {
					dashOPatterns++;
				}

				for (Object o : cn.methods) {
					MethodNode mn = (MethodNode) o;
					totalMethods++;
					String mname = mn.name;
					if (!mname.equals("<init>") && !mname.equals("<clinit>") && mname.length() <= 2) {
						shortNameMethods++;
					}

					if ((mn.access & Opcodes.ACC_SYNTHETIC) != 0) {
						syntheticMethods++;
					}
					if ((mn.access & Opcodes.ACC_BRIDGE) != 0) {
						bridgeMethods++;
					}

					if (mn.exceptions != null) {
						for (Object exo : mn.exceptions) {
							String ex = (String) exo;
							if (ex.contains("Throwable") || ex.contains("Exception")) {
								throwPatterns++;
							}
						}
					}

					if (mn.instructions != null) {
						for (int i = 0; i < mn.instructions.size(); i++) {
							org.objectweb.asm.tree.AbstractInsnNode insn = mn.instructions.get(i);
							int op = insn.getOpcode();
							if (op == org.objectweb.asm.Opcodes.NEWARRAY) {
								if (i > 0 && mn.instructions.get(i - 1).getOpcode() == org.objectweb.asm.Opcodes.BIPUSH) {
									charArrayPatterns++;
								}
							}
							if (op == org.objectweb.asm.Opcodes.INVOKESTATIC) {
								org.objectweb.asm.tree.MethodInsnNode min = (org.objectweb.asm.tree.MethodInsnNode) insn;
								if (min.desc != null && min.desc.contains("(Ljava/lang/String;)Ljava/lang/String;")) {
									encryptedStringPatterns++;
								}
								if (min.desc != null && min.desc.contains("(I)Ljava/lang/String;")) {
									encryptedStringPatterns++;
									smokescreenPatterns++;
								}
								if (min.desc != null && min.desc.contains("([C)Ljava/lang/String;")) {
									encryptedStringPatterns++;
									dashOPatterns++;
								}
								if (min.owner != null && min.owner.contains("java/lang/String") && min.name.equals("valueOf")) {
									jshrinkPatterns++;
								}
							}
							if (op == org.objectweb.asm.Opcodes.INVOKESPECIAL) {
								org.objectweb.asm.tree.MethodInsnNode min = (org.objectweb.asm.tree.MethodInsnNode) insn;
								if (min.owner != null && min.owner.equals("java/lang/StringBuilder")
										&& min.name.equals("<init>")) {
									sbPatterns++;
								}
							}
							if (op == org.objectweb.asm.Opcodes.GOTO) {
								gotoPatterns++;
							}
							if (op == org.objectweb.asm.Opcodes.NOP) {
								nopPatterns++;
							}
							if (op == org.objectweb.asm.Opcodes.ATHROW) {
								throwPatterns++;
							}
						}
					}
				}
			} catch (Exception e) {
			}
		}

		double classShortRatio = totalClasses > 0 ? (double) shortNameClasses / totalClasses : 0;
		double methodShortRatio = totalMethods > 0 ? (double) shortNameMethods / totalMethods : 0;
		double score = (classShortRatio * 0.35 + methodShortRatio * 0.35);
		if (encryptedStringPatterns > totalClasses * 2) score += 0.2;
		if (charArrayPatterns > totalClasses) score += 0.1;
		if (syntheticMethods > totalMethods * 0.3) score += 0.1;
		if (gotoPatterns > totalMethods * 5) score += 0.05;

		boolean hasShortNames = classShortRatio > 0.5 || methodShortRatio > 0.5;
		boolean hasEncryptedStrings = encryptedStringPatterns > totalClasses * 2 || charArrayPatterns > totalClasses;
		boolean isObfuscated = score > 0.3 || hasShortNames || hasEncryptedStrings;

		ObfuscatorType type = ObfuscatorType.NONE;
		if (isObfuscated) {
			if (hasEncryptedStrings && charArrayPatterns > totalClasses * 3) {
				type = ObfuscatorType.STRINGER;
			} else if (hasEncryptedStrings && sbPatterns > totalClasses * 5) {
				type = ObfuscatorType.ALLATORI;
			} else if (hasShortNames && !hasEncryptedStrings && syntheticMethods > totalMethods * 0.2) {
				type = ObfuscatorType.DASHO;
			} else if (hasEncryptedStrings && jshrinkPatterns > totalClasses * 3 && !hasShortNames) {
				type = ObfuscatorType.JSHRINK;
			} else if (hasEncryptedStrings && smokescreenPatterns > totalClasses * 2 && throwPatterns > totalMethods) {
				type = ObfuscatorType.SMOKESCREEN;
			} else if (hasShortNames && !hasEncryptedStrings) {
				type = ObfuscatorType.PROGUARD;
			} else if (hasEncryptedStrings && score > 0.6) {
				type = ObfuscatorType.ZKM;
			} else {
				type = ObfuscatorType.GENERIC;
			}
		}

		return new ObfuscationDetails(type.getDisplayName(), score, isObfuscated, hasEncryptedStrings,
				hasShortNames, totalClasses, shortNameClasses, shortNameMethods, totalMethods,
				encryptedStringPatterns, charArrayPatterns, sbPatterns, syntheticMethods, bridgeMethods,
				gotoPatterns, nopPatterns, throwPatterns, sampleClasses);
	}
}
