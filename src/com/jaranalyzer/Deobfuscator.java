package com.jaranalyzer;

import java.io.File;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class Deobfuscator {

	public interface DeobfuscationCallback {
		void onProgress(String stage, String message);
		void onComplete(File deobfuscatedJar, ObfuscationDetector.DetectionResult detection);
		void onError(String message, Exception e);
	}

	public static File deobfuscate(File jarFile, DeobfuscationCallback callback) {
		File tempDir = new File(System.getProperty("java.io.tmpdir"), "jaranalyzer_deobf");
		if (!tempDir.exists()) {
			tempDir.mkdirs();
		}

		String baseName = jarFile.getName().replaceAll("\\.(jar|zip)$", "");
		File outputFile = new File(tempDir, baseName + "_deobf.jar");

		try (JarFile jf = new JarFile(jarFile)) {
			if (callback != null) callback.onProgress("detect", "Detecting obfuscation...");

			ObfuscationDetector.DetectionResult detection = ObfuscationDetector.detect(jf);

			if (!detection.isObfuscated) {
				if (callback != null) callback.onProgress("detect", "No obfuscation detected.");
				if (callback != null) callback.onComplete(jarFile, detection);
				return jarFile;
			}

			if (callback != null) {
				callback.onProgress("detect", "Obfuscation detected: " + detection.type.getDisplayName()
						+ " (score: " + String.format("%.2f", detection.obfuscationScore) + ")");
			}

			File stage1File = new File(tempDir, baseName + "_stage1.jar");
			File stage2File = new File(tempDir, baseName + "_stage2.jar");
			File stage3File = new File(tempDir, baseName + "_stage3.jar");
			File stage4File = new File(tempDir, baseName + "_stage4.jar");

			// Stage 1a: StringDecryptor (StringBuilder char-by-char patterns)
			File stage1aFile = new File(tempDir, baseName + "_stage1a.jar");
			if (callback != null) callback.onProgress("strings", "Decrypting StringBuilder patterns...");
			try (JarFile jfForStrings = new JarFile(jarFile)) {
				StringDecryptor stringDecryptor = new StringDecryptor(msg -> {
					if (callback != null) callback.onProgress("strings", msg);
				});
				stringDecryptor.decryptStrings(jfForStrings, stage1aFile);
			}

			// Stage 1b: AdvancedStringDecryptor (LDC/XOR/AES encrypted strings)
			if (callback != null) callback.onProgress("strings", "Decrypting encrypted strings (advanced)...");
			try (JarFile stage1aJf = new JarFile(stage1aFile)) {
				AdvancedStringDecryptor decryptor = new AdvancedStringDecryptor(msg -> {
					if (callback != null) callback.onProgress("strings", msg);
				});
				decryptor.decrypt(stage1aJf, stage1File);
			}
			stage1aFile.delete();

			if (callback != null) callback.onProgress("intfold", "Folding integer constants...");
			try (JarFile stage1Jf = new JarFile(stage1File)) {
				IntegerConstantFolder folder = new IntegerConstantFolder(msg -> {
					if (callback != null) callback.onProgress("intfold", msg);
				});
				folder.fold(stage1Jf, stage2File);
			}

			if (detection.obfuscationScore > 0.5) {
				if (callback != null) callback.onProgress("unflatten", "Unflattening control flow...");
				try (JarFile stage2Jf = new JarFile(stage2File)) {
					ControlFlowFlattener flattener = new ControlFlowFlattener(msg -> {
						if (callback != null) callback.onProgress("unflatten", msg);
					});
					flattener.unflatten(stage2Jf, stage3File);
				}
			} else {
				copyFile(stage2File, stage3File);
			}

			if (callback != null) callback.onProgress("reflection", "Resolving reflection...");
			try (JarFile stage3Jf = new JarFile(stage3File)) {
				ReflectionResolver resolver = new ReflectionResolver(msg -> {
					if (callback != null) callback.onProgress("reflection", msg);
				});
				resolver.resolve(stage3Jf, stage4File);
			}

			if (detection.hasShortNames) {
				if (callback != null) callback.onProgress("rename", "Remapping names...");
				try (JarFile stage4Jf = new JarFile(stage4File)) {
					NameRemapper remapper = new NameRemapper(msg -> {
						if (callback != null) callback.onProgress("rename", msg);
					});
					remapper.remap(stage4Jf, outputFile);
				}
			} else {
				copyFile(stage4File, outputFile);
			}

			stage1File.delete();
			stage2File.delete();
			stage3File.delete();
			stage4File.delete();

			// Copy non-class files from original JAR to output (deobf stages only process .class)
			copyNonClassFiles(jarFile, outputFile);

			if (callback != null) callback.onComplete(outputFile, detection);
			return outputFile;

		} catch (Exception e) {
			if (callback != null) callback.onError("Deobfuscation failed: " + e.getMessage(), e);
			return jarFile;
		}
	}

	private static void copyFile(File src, File dest) throws Exception {
		try (java.io.FileInputStream fis = new java.io.FileInputStream(src);
				java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
			byte[] buf = new byte[8192];
			int len;
			while ((len = fis.read(buf)) > 0) {
				fos.write(buf, 0, len);
			}
		}
	}

	private static void copyNonClassFiles(File srcJar, File destJar) throws Exception {
		// First pass: check if there are any non-class files to copy
		boolean hasNonClassFiles = false;
		try (JarFile srcJf = new JarFile(srcJar)) {
			java.util.Enumeration<JarEntry> srcEntries = srcJf.entries();
			while (srcEntries.hasMoreElements()) {
				JarEntry e = srcEntries.nextElement();
				if (e.isDirectory()) continue;
				if (!e.getName().endsWith(".class")) { hasNonClassFiles = true; break; }
			}
		}
		if (!hasNonClassFiles) return;

		// Read existing entries from destJar
		java.util.Set<String> existingEntries = new java.util.HashSet<>();
		java.util.Map<String, byte[]> existingData = new java.util.LinkedHashMap<>();
		try (JarFile destJf = new JarFile(destJar)) {
			java.util.Enumeration<JarEntry> destEntries = destJf.entries();
			while (destEntries.hasMoreElements()) {
				JarEntry e = destEntries.nextElement();
				if (e.isDirectory()) continue;
				existingEntries.add(e.getName());
				java.io.InputStream is = destJf.getInputStream(e);
				java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
				byte[] buf = new byte[8192];
				int len;
				while ((len = is.read(buf)) > 0) baos.write(buf, 0, len);
				is.close();
				existingData.put(e.getName(), baos.toByteArray());
			}
		}

		// Add non-class files from source JAR that are not already in dest
		try (JarFile srcJf = new JarFile(srcJar)) {
			java.util.Enumeration<JarEntry> srcEntries = srcJf.entries();
			while (srcEntries.hasMoreElements()) {
				JarEntry e = srcEntries.nextElement();
				if (e.isDirectory()) continue;
				String name = e.getName();
				if (name.endsWith(".class")) continue;
				if (existingEntries.contains(name)) continue;
				java.io.InputStream is = srcJf.getInputStream(e);
				java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
				byte[] buf = new byte[8192];
				int len;
				while ((len = is.read(buf)) > 0) baos.write(buf, 0, len);
				is.close();
				existingData.put(name, baos.toByteArray());
			}
		}

		// Rewrite destJar with all entries
		try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
				new java.io.FileOutputStream(destJar))) {
			for (java.util.Map.Entry<String, byte[]> entry : existingData.entrySet()) {
				zos.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
				zos.write(entry.getValue());
				zos.closeEntry();
			}
		}
	}
}
