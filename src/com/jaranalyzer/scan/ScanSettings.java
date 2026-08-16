package com.jaranalyzer.scan;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Persisted knobs for a scan run. Stored next to the blacklist in APPDATA. */
public class ScanSettings {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// ---- discovery --------------------------------------------------------

	/** Sweep every local drive. This is the tool's default mode. */
	public boolean scanAllDrives = true;

	/** Explicit roots, used when {@link #scanAllDrives} is off. */
	public List<String> roots = new ArrayList<>();

	/**
	 * Archive extensions treated as candidates.
	 *
	 * <p>{@code .jmod} is deliberately absent. JMOD files are JDK runtime modules,
	 * never user-supplied mods, and they are not plain ZIPs either — a 4-byte magic
	 * header sits in front of the archive. Including them means every scan reports
	 * the JDK's own {@code java.instrument} and {@code java.scripting} modules,
	 * which exist precisely to provide the agent and scripting APIs the blacklist
	 * watches for.
	 */
	public List<String> extensions = new ArrayList<>(Arrays.asList(
			".jar", ".war", ".ear", ".aar"));

	/** Also open plain .zip files. Off by default: mostly noise on a real disk. */
	public boolean includeZipFiles = false;

	/**
	 * Directory name fragments skipped anywhere in the tree.
	 *
	 * <p>{@code $Recycle.Bin} is deliberately absent: a deleted cheat is still
	 * sitting there under a {@code $R…} name with its contents intact, and that is
	 * one of the first places worth looking. See {@link #scanRecycleBin}.
	 */
	public List<String> excludeDirectories = new ArrayList<>(Arrays.asList(
			"system volume information", "\\windows\\winsxs",
			"\\windows\\servicing", "\\windows\\installer"));

	/** Include the recycle bin. Deleted files keep their contents there. */
	public boolean scanRecycleBin = true;

	/**
	 * Find archives by content rather than by file name.
	 *
	 * <p>Renaming {@code killaura.jar} to {@code d3d9.dll} defeats an
	 * extension-only scan completely, which makes this the difference between a
	 * tool that finds hidden JARs and one that finds only the plainly named ones.
	 * Reading a 4-byte signature per candidate parallelises across threads and is
	 * cheap enough to leave on.
	 */
	public boolean detectDisguisedArchives = true;

	/** Smallest file worth probing. A real mod JAR is never under a kilobyte. */
	public long disguisedMinBytes = 1024;

	/** Skip files larger than this. 0 disables the cap. */
	public long maxJarBytes = 512L * 1024 * 1024;

	/** Skip JARs whose name matches a known third-party library. */
	public boolean skipKnownLibraries = false;

	/**
	 * Read NTFS volumes through the Master File Table instead of walking
	 * directories. Much faster, needs administrator rights; falls back
	 * automatically when unavailable.
	 */
	public boolean useMftScan = true;

	// ---- decompilation ----------------------------------------------------

	/**
	 * When to reconstruct Java source.
	 *
	 * <p>Detection never needs it. Every identifier and string literal a blacklist
	 * term can match lives in the constant pool, which is read in milliseconds;
	 * decompiling the same classes costs ~2 minutes for a large archive and finds
	 * the same terms. Source is what a human reads to judge a hit, so it is
	 * produced for the archives that actually have one.
	 */
	public enum DecompileMode {
		/**
		 * Do not reconstruct source during the sweep. Default.
		 *
		 * <p>This costs no detection: every class's constant pool is still read and
		 * every blacklist term still run over it. Decompiled source produces no
		 * verdict the pool has not already made — a decompiler builds its output out
		 * of that pool — so reconstructing every archive during the sweep is a large
		 * cost for nothing.
		 *
		 * <p>Source is still available: selecting a row reconstructs that one
		 * archive on demand, and the Decompile tab does it when a JAR is opened.
		 * The work happens for the archive somebody is reading, not for all of them.
		 */
		OFF,
		/** Reconstruct archives that produced a finding or resisted analysis. */
		FLAGGED,
		/** Reconstruct everything. Thorough, and slow enough to matter on a sweep. */
		ALL,
	}

	public DecompileMode decompileMode = DecompileMode.OFF;

	/** Bumped when a default changes in a way that should reach existing installs. */
	public int settingsVersion = 2;

	/** Per-JAR class cap. 0 = unlimited. */
	public int maxClassesPerJar = 4000;

	/** Per-JAR wall-clock budget. */
	public int perJarTimeoutSeconds = 120;

	/** Combined decompiled text kept per JAR. */
	public int maxDecompiledChars = 12_000_000;

	/** Skip java/, kotlin/, org/apache/ and friends while decompiling. */
	public boolean skipLibraryPackages = true;

	/** Disassemble with ASM when the decompiler gives up. */
	public boolean bytecodeFallback = true;

	/** How deep to open JARs nested inside JARs. */
	public int maxNestedDepth = 2;

	// ---- analysis ---------------------------------------------------------

	public boolean computeHashes = true;
	public boolean detectObfuscation = true;

	/** Classes sampled by the obfuscation pass. */
	public int obfuscationSampleSize = 400;

	/**
	 * Report an obfuscated-but-otherwise-clean JAR as SUSPICIOUS. This is the
	 * user-facing switch for the tool's central rule.
	 */
	public boolean opaqueMeansSuspicious = true;

	/** Entropy above which a non-class resource is treated as encrypted. */
	public double encryptedEntropyThreshold = 7.5;

	/** Keep decompiled text for clean JARs too. Costs a lot of memory on a full sweep. */
	public boolean keepTextForCleanJars = false;

	// ---- execution --------------------------------------------------------

	/** Parallel analysis workers. 0 = derive from CPU count. */
	public int workerThreads = 0;

	public int effectiveWorkers() {
		return workerThreads > 0
				? workerThreads
				: Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
	}

	public boolean matchesExtension(String lowerName) {
		for (String ext : extensions) {
			if (lowerName.endsWith(ext)) return true;
		}
		return includeZipFiles && lowerName.endsWith(".zip");
	}

	// ---- persistence ------------------------------------------------------

	public static File settingsFile() {
		return new File(BlacklistStore.configDir(), "settings.json");
	}

	public static ScanSettings load() {
		File f = settingsFile();
		if (!f.isFile()) return new ScanSettings();
		try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
			ScanSettings s = GSON.fromJson(r, ScanSettings.class);
			return s == null ? new ScanSettings() : s.sanitised();
		} catch (Exception e) {
			return new ScanSettings();
		}
	}

	public void save() {
		try (Writer w = Files.newBufferedWriter(settingsFile().toPath(), StandardCharsets.UTF_8)) {
			GSON.toJson(this, w);
		} catch (IOException ignored) {
			// Settings are a convenience; an unwritable APPDATA is not fatal.
		}
	}

	/** Repairs nulls and nonsense values from a hand-edited or older settings file. */
	private ScanSettings sanitised() {
		if (roots == null) roots = new ArrayList<>();
		if (extensions == null || extensions.isEmpty()) {
			extensions = new ArrayList<>(Arrays.asList(".jar", ".war", ".ear", ".aar"));
		}
		// Settings written by an earlier build may still carry .jmod; drop it.
		extensions.remove(".jmod");
		if (excludeDirectories == null) excludeDirectories = new ArrayList<>();
		// Older settings files carry the recycle bin in the exclude list; the
		// dedicated flag is the one that decides now.
		excludeDirectories.removeIf(s -> s != null
				&& s.toLowerCase(java.util.Locale.ROOT).contains("recycle"));
		if (disguisedMinBytes < 64) disguisedMinBytes = 1024;
		if (decompileMode == null) decompileMode = DecompileMode.OFF;

		// One-time migration to the OFF default for installs that still carry the
		// older FLAGGED value. Gated on the version so that choosing FLAGGED
		// deliberately in Settings afterwards sticks.
		if (settingsVersion < 2) {
			if (decompileMode == DecompileMode.FLAGGED) decompileMode = DecompileMode.OFF;
			settingsVersion = 2;
		}
		if (perJarTimeoutSeconds < 5) perJarTimeoutSeconds = 5;
		if (maxNestedDepth < 0) maxNestedDepth = 0;
		if (obfuscationSampleSize < 20) obfuscationSampleSize = 20;
		if (maxDecompiledChars < 100_000) maxDecompiledChars = 100_000;
		return this;
	}

	public DecompileEngine.Options toDecompileOptions(long deadlineNanos) {
		DecompileEngine.Options o = new DecompileEngine.Options();
		o.maxClasses = maxClassesPerJar;
		o.maxTextChars = maxDecompiledChars;
		o.deadlineNanos = deadlineNanos;
		o.skipKnownLibraryPackages = skipLibraryPackages;
		o.bytecodeFallback = bytecodeFallback;
		return o;
	}
}
