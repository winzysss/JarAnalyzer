package com.jaranalyzer.scan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fixed scan behaviour, plus the little runtime state a run needs.
 *
 * <p>There are no user-adjustable options. Every check the tool can make is
 * always on, nothing is ever skipped, and the limits below are the same on every
 * machine — a forensics result means the same thing no matter who produced it,
 * and there is no switch anyone can flip to make a scan quietly miss something.
 */
public class ScanSettings {

	// ---- what to scan (runtime state, set by the UI or the CLI) ------------

	/** Sweep every local drive. This is the tool's default mode. */
	public boolean scanAllDrives = true;

	/** Explicit roots, used when {@link #scanAllDrives} is off. */
	public List<String> roots = new ArrayList<>();

	// ---- fixed behaviour ---------------------------------------------------

	/**
	 * Archive extensions treated as candidates by name.
	 *
	 * <p>{@code .zip} is included: it is a recognised archive extension, so the
	 * disguised-archive probe deliberately ignores it, and leaving it unscanned
	 * would make a cheat renamed {@code cheat.zip} invisible to both paths.
	 *
	 * <p>{@code .jmod} is deliberately absent. JMOD files are JDK runtime modules,
	 * never user-supplied mods, and a JDK ships around 150 of them — including them
	 * means every scan reports the JDK's own {@code java.instrument} and
	 * {@code java.scripting} modules, which exist precisely to provide the agent and
	 * scripting APIs the blacklist watches for.
	 */
	public final List<String> extensions = new ArrayList<>(Arrays.asList(
			".jar", ".war", ".ear", ".aar", ".zip"));

	/** Include the recycle bin. Deleted files keep their contents there. */
	public final boolean scanRecycleBin = true;

	/**
	 * Find archives by content rather than by file name.
	 *
	 * <p>Renaming {@code killaura.jar} to {@code d3d9.dll} defeats an
	 * extension-only scan completely, which makes this the difference between a
	 * tool that finds hidden JARs and one that finds only the plainly named ones.
	 */
	public final boolean detectDisguisedArchives = true;

	/** Smallest file worth probing. A real mod JAR is never under a kilobyte. */
	public final long disguisedMinBytes = 1024;

	/** Skip files larger than this. */
	public final long maxJarBytes = 512L * 1024 * 1024;

	/**
	 * Read NTFS volumes through the Master File Table instead of walking
	 * directories. Much faster, needs administrator rights; falls back
	 * automatically when unavailable.
	 */
	public final boolean useMftScan = true;

	/** Per-JAR wall-clock budget. */
	public final int perJarTimeoutSeconds = 120;

	/** How deep to open JARs nested inside JARs. */
	public final int maxNestedDepth = 2;

	/** Entropy above which a non-class resource is treated as encrypted. */
	public final double encryptedEntropyThreshold = 7.5;

	// ---- execution --------------------------------------------------------

	public int effectiveWorkers() {
		return Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
	}

	public boolean matchesExtension(String lowerName) {
		for (String ext : extensions) {
			if (lowerName.endsWith(ext)) return true;
		}
		return false;
	}
}
