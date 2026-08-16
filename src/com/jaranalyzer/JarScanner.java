package com.jaranalyzer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class JarScanner {

	public interface ScanCallback {
		void onStatusUpdate(String status);
		void onJarFound(String jarPath);
		void onProgress(int current, int total);
		void onComplete(List<String> jarPaths);
	}

	private static final Set<String> DEFAULT_EXCLUDE_DIRS = new HashSet<>(Arrays.asList(
			"\\Windows\\", "\\Program Files\\", "\\Program Files (x86)\\", "\\$Recycle.Bin\\",
			"\\System Volume Information\\"
	));

	private static final String[] MINECRAFT_SUBPATHS = {
			".minecraft/versions", ".minecraft/mods", ".minecraft/resourcepacks",
			".minecraft/texturepacks"
	};

	public static List<String> findAllJars(ScanCallback callback, Set<String> excludeDirs) {
		List<String> allJars = new ArrayList<>();
		callback.onStatusUpdate(LanguageManager.getString("scan.status.searching"));

		// First: scan Minecraft folders
		String userHome = System.getProperty("user.home");
		for (String subPath : MINECRAFT_SUBPATHS) {
			File mcDir = new File(userHome, subPath.replace("/", File.separator));
			if (mcDir.isDirectory()) {
				callback.onStatusUpdate(LanguageManager.getString("scan.minecraft.scanning") + " " + mcDir.getName());
				collectJars(mcDir, allJars, callback);
			}
		}
		// Also check AppData/Roaming/.minecraft
		String appData = System.getenv("APPDATA");
		if (appData != null) {
			for (String subPath : MINECRAFT_SUBPATHS) {
				File mcDir = new File(appData, (".minecraft/" + subPath).replace("/", File.separator));
				if (mcDir.isDirectory()) {
					callback.onStatusUpdate(LanguageManager.getString("scan.minecraft.scanning") + " " + mcDir.getName());
					collectJars(mcDir, allJars, callback);
				}
			}
		}

		if (!allJars.isEmpty()) {
			callback.onStatusUpdate(allJars.size() + " " + LanguageManager.getString("scan.minecraft.found"));
		}

		// Then: scan all drives
		File[] roots = File.listRoots();
		if (roots == null || roots.length == 0) {
			callback.onComplete(allJars);
			return allJars;
		}

		ExecutorService executor = Executors.newFixedThreadPool(Math.min(roots.length, 4));
		List<Future<List<String>>> futures = new ArrayList<>();

		for (File root : roots) {
			final String drivePath = root.getAbsolutePath();
			final Set<String> excludes = excludeDirs != null ? excludeDirs : DEFAULT_EXCLUDE_DIRS;

			futures.add(executor.submit(() -> {
				List<String> jars = new ArrayList<>();
				try {
					ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "dir", "/s", "/b", drivePath + "*.jar");
					pb.redirectErrorStream(false);
					Process process = pb.start();

					try (BufferedReader reader = new BufferedReader(
							new InputStreamReader(process.getInputStream(), "UTF-8"))) {
						String line;
						while ((line = reader.readLine()) != null) {
							line = line.trim();
							if (line.endsWith(".jar") && new File(line).isFile()) {
								boolean shouldExclude = false;
								for (String exclude : excludes) {
									if (line.contains(exclude)) {
										shouldExclude = true;
										break;
									}
								}
								if (!shouldExclude) {
									jars.add(line);
									callback.onJarFound(line);
								}
							}
						}
					}
					process.waitFor();
				} catch (Exception e) {
					// ignore errors for individual drives
				}
				return jars;
			}));
		}

		for (Future<List<String>> future : futures) {
			try {
				allJars.addAll(future.get());
			} catch (Exception e) {
				// ignore
			}
		}

		executor.shutdown();
		callback.onStatusUpdate(allJars.size() + " " + LanguageManager.getString("scan.status.found"));
		callback.onComplete(allJars);
		return allJars;
	}

	public static List<String> findJarsInDirectory(File dir, ScanCallback callback) {
		List<String> jars = new ArrayList<>();
		if (dir == null || !dir.isDirectory()) {
			callback.onComplete(jars);
			return jars;
		}

		callback.onStatusUpdate(LanguageManager.getString("scan.status.searching"));
		collectJars(dir, jars, callback);
		callback.onStatusUpdate(jars.size() + " " + LanguageManager.getString("scan.status.found"));
		callback.onComplete(jars);
		return jars;
	}

	private static void collectJars(File dir, List<String> jars, ScanCallback callback) {
		collectJars(dir, jars, callback, null);
	}

	private static void collectJars(File dir, List<String> jars, ScanCallback callback, Set<String> excludes) {
		File[] files = dir.listFiles();
		if (files == null) return;

		for (File file : files) {
			if (file.isDirectory()) {
				collectJars(file, jars, callback, excludes);
			} else if (file.getName().toLowerCase().endsWith(".jar")) {
				String path = file.getAbsolutePath();
				if (excludes != null) {
					boolean shouldExclude = false;
					for (String exclude : excludes) {
						if (path.contains(exclude)) {
							shouldExclude = true;
							break;
						}
					}
					if (shouldExclude) continue;
				}
				if (!jars.contains(path)) {
					jars.add(path);
					callback.onJarFound(path);
				}
			}
		}
	}
}
