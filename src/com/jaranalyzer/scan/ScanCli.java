package com.jaranalyzer.scan;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Headless entry point.
 *
 * <p>Useful on its own for scripted sweeps, and useful during development
 * because it exercises the whole engine without the UI in the way.
 *
 * <pre>
 *   --scan-all [outDir]        sweep every drive
 *   --scan &lt;jar|dir&gt; [outDir]  analyse one file or directory
 * </pre>
 */
public final class ScanCli {

	private ScanCli() {
	}

	public static int run(String[] args) {
		boolean sweepAll = "--scan-all".equals(args[0]);
		String targetArg = sweepAll ? null : (args.length > 1 ? args[1] : null);
		String outArg = sweepAll
				? (args.length > 1 ? args[1] : null)
				: (args.length > 2 ? args[2] : null);

		if (!sweepAll && targetArg == null) {
			System.out.println("Usage: --scan <jar-or-directory> [output-dir]");
			System.out.println("       --scan-all [output-dir]");
			return 1;
		}

		ScanSettings settings = new ScanSettings();
		Blacklist blacklist = BlacklistStore.load();
		blacklist.compile();

		System.out.println("Jar Analyzer (CLI)  —  Made by Winzys");
		System.out.println("  blacklist : " + blacklist.enabledCount() + " active terms"
				+ "  (" + BlacklistStore.blacklistFile() + ")");
		System.out.println("  workers   : " + settings.effectiveWorkers());
		System.out.println("  disk scan : " + describeDiscovery(settings));
		System.out.println();

		final long[] lastPrint = { 0 };
		ScanController controller = new ScanController(settings, blacklist);

		ScanController.Listener listener = new ScanController.Listener() {
			@Override
			public void onPhase(String phase) {
				System.out.println("[phase] " + phase);
			}

			@Override
			public void onDiscovery(long filesSeen, long jarsFound, String dir) {
				long now = System.currentTimeMillis();
				if (now - lastPrint[0] < 1000) return;
				lastPrint[0] = now;
				System.out.printf("  scanning… %,d files, %,d JARs  %s%n",
						filesSeen, jarsFound, trim(dir, 60));
			}

			@Override
			public void onJarStarted(File jar) {
			}

			@Override
			public void onJarAnalyzed(JarAnalysis a, int done, int total) {
				if (a.getVerdict().needsAttention()) {
					System.out.printf("  [%-10s] %s  (%d findings, score %d)%n",
							a.getVerdict().en(), trim(a.getPath(), 80),
							a.getFindingCount(), a.getRiskScore());
				}
			}

			@Override
			public void onFinished(ScanController.Summary s) {
			}
		};

		ScanController.Summary summary;
		if (sweepAll) {
			summary = controller.run(listener);
		} else {
			List<File> targets = collect(new File(targetArg), settings);
			System.out.println("  targets   : " + targets.size() + " archive(s)");
			System.out.println();
			summary = controller.runOn(targets, listener);
		}

		printSummary(summary);

		File outDir = new File(outArg != null ? outArg : "wjf-report");
		try {
			ReportWriter.writeHtml(new File(outDir, "report.html"), summary);
			ReportWriter.writeJson(new File(outDir, "report.json"), summary);
			ReportWriter.writeText(new File(outDir, "report.txt"), summary);
			System.out.println("\nReports written to " + outDir.getAbsolutePath());
		} catch (Exception e) {
			System.err.println("Report write failed: " + e);
			return 2;
		}
		return 0;
	}

	/**
	 * Whether the fast MFT sweep is actually available right now.
	 *
	 * <p>Worth printing: it is the difference between a whole-disk discovery
	 * taking seconds and taking minutes, and the reason is invisible otherwise —
	 * the scan silently falls back rather than failing.
	 */
	private static String describeDiscovery(ScanSettings settings) {
		if (!settings.useMftScan) return "directory walk (MFT disabled in settings)";
		if (!MftScanner.isAvailable()) return "directory walk (native access unavailable)";

		java.util.List<String> ntfs = MftScanner.ntfsDriveLetters();
		if (ntfs.isEmpty()) return "directory walk (no NTFS volume)";

		// Probe by actually opening a volume: elevation is the usual blocker and
		// there is no cheaper way to know.
		boolean ok = MftScanner.probeVolume(ntfs.get(0));
		return ok
				? "MFT (fast) on " + ntfs
				: "directory walk (not elevated — MFT needs administrator)";
	}

	private static List<File> collect(File target, ScanSettings settings) {
		List<File> out = new ArrayList<>();
		if (target.isFile()) {
			out.add(target);
			return out;
		}
		if (!target.isDirectory()) {
			System.err.println("Not found: " + target);
			return out;
		}
		collectInto(target, settings, out, 0);
		out.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
		return out;
	}

	private static void collectInto(File dir, ScanSettings settings, List<File> out, int depth) {
		if (depth > 24) return;
		File[] kids = dir.listFiles();
		if (kids == null) return;
		for (File f : kids) {
			if (f.isDirectory()) {
				collectInto(f, settings, out, depth + 1);
				continue;
			}
			if (settings.matchesExtension(f.getName().toLowerCase(java.util.Locale.ROOT))) {
				out.add(f);
				continue;
			}
			// Same content probe the full-disk sweep does. Without it a directory
			// scan would silently miss exactly the files a renamed cheat hides in,
			// and would disagree with what the same folder reports in the UI.
			if (settings.detectDisguisedArchives
					&& f.length() >= settings.disguisedMinBytes
					&& ArchiveSniffer.isDisguisedJavaArchive(f)) {
				out.add(f);
			}
		}
	}

	private static void printSummary(ScanController.Summary s) {
		System.out.println();
		System.out.println("================ SUMMARY ================");
		System.out.printf("  files seen : %,d%n", s.filesSeen);
		System.out.printf("  JARs found : %,d%n", s.totalFound);
		System.out.printf("  analyzed   : %,d%n", s.analyzed);
		System.out.printf("  elapsed    : %.1f s%n", s.elapsedMillis / 1000.0);
		System.out.println("  -------------------------------------");
		for (Verdict v : Verdict.values()) {
			System.out.printf("  %-12s %,d%n", v.en(), s.count(v));
		}
		System.out.println("=========================================");
	}

	private static String trim(String s, int max) {
		if (s == null) return "";
		return s.length() <= max ? s : "…" + s.substring(s.length() - max + 1);
	}
}
