package com.jaranalyzer.scan;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything learned about one JAR. Produced by {@link JarAnalyzer}, consumed by
 * the results table, the detail pane and the report writer.
 */
public class JarAnalysis {

	private final File file;
	private final String path;

	private long sizeBytes;
	private long lastModified;
	private String sha256 = "";

	// ---- archive facts ---------------------------------------------------
	private int entryCount;
	private int classCount;
	private int resourceCount;
	private int nestedJarCount;
	private int nativeLibCount;
	private boolean hasManifest;
	private String manifestText = "";
	private String mainClass;
	private String premainClass;
	private String agentClass;
	private String tweakClass;
	private String modLoader;
	private final List<String> entryNames = new ArrayList<>();

	// ---- how far the read got ---------------------------------------------
	private DecompileOutcome decompileOutcome = DecompileOutcome.NOT_ATTEMPTED;

	/** Classes whose constant pool was parsed, and so actually searched. */
	private int classesRead;

	/**
	 * Classes present but unparseable — truncated, encrypted, or deliberately
	 * malformed to break tooling. A class counted here was never searched, so
	 * this is the measure of how much of an archive stayed hidden.
	 */
	private int classesUnreadable;

	private String decompileError;

	// ---- heuristics -------------------------------------------------------
	private boolean obfuscated;
	private boolean encrypted;
	private boolean structurallyBroken;
	private double obfuscationScore;
	private double archiveEntropy;
	private String obfuscatorGuess = "";

	// ---- results ---------------------------------------------------------
	private final List<Finding> findings = new ArrayList<>();
	private final Map<String, Finding> foldIndex = new LinkedHashMap<>();
	private Verdict verdict = Verdict.CLEAN;
	private int riskScore;
	private long analysisMillis;
	private final List<String> log = new ArrayList<>();

	public JarAnalysis(File file) {
		this.file = file;
		this.path = file.getAbsolutePath();
		this.sizeBytes = file.length();
		this.lastModified = file.lastModified();
	}

	// ---- findings ---------------------------------------------------------

	/** Adds a finding, folding repeats of the same pattern into a hit counter. */
	public synchronized void add(Finding f) {
		Finding existing = foldIndex.get(f.foldKey());
		if (existing != null) {
			existing.addHits(f.getHits());
			return;
		}
		foldIndex.put(f.foldKey(), f);
		findings.add(f);
	}

	public synchronized List<Finding> getFindings() {
		return Collections.unmodifiableList(new ArrayList<>(findings));
	}

	public synchronized int getFindingCount() {
		return findings.size();
	}

	/** Highest-severity finding, for the summary column. */
	public synchronized Finding topFinding() {
		Finding best = null;
		for (Finding f : findings) {
			if (best == null || f.getSeverity().weight() > best.getSeverity().weight()) {
				best = f;
			}
		}
		return best;
	}

	public synchronized int countBlacklistHits() {
		int n = 0;
		for (Finding f : findings) if (f.getPattern() != null) n += f.getHits();
		return n;
	}

	public void note(String message) {
		synchronized (log) {
			log.add(message);
		}
	}

	public List<String> getLog() {
		synchronized (log) {
			return new ArrayList<>(log);
		}
	}

	// ---- derived display helpers -----------------------------------------

	public String getFileName() {
		return file.getName();
	}

	public String getDirectory() {
		String d = file.getParent();
		return d == null ? "" : d;
	}

	public String getSizeDisplay() {
		return humanSize(sizeBytes);
	}

	public static String humanSize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		String[] units = { "KB", "MB", "GB", "TB" };
		double v = bytes / 1024.0;
		int i = 0;
		while (v >= 1024 && i < units.length - 1) {
			v /= 1024;
			i++;
		}
		return String.format(java.util.Locale.ROOT, "%.1f %s", v, units[i]);
	}

	public String getDecompileSummary() {
		switch (decompileOutcome) {
			case NO_CLASSES:
				return "-";
			case POOL_SCANNED:
				// The count is the point: "Scanned" alone does not say whether the
				// archive gave up all of its classes or only most of them.
				return classesUnreadable > 0
						? classesRead + " / " + (classesRead + classesUnreadable)
						: decompileOutcome.display();
			default:
				return decompileOutcome.display();
		}
	}

	// ---- accessors --------------------------------------------------------

	public File getFile() { return file; }

	public String getPath() { return path; }

	public long getSizeBytes() { return sizeBytes; }

	public void setSizeBytes(long v) { sizeBytes = v; }

	public long getLastModified() { return lastModified; }

	public void setLastModified(long v) { lastModified = v; }

	public String getSha256() { return sha256; }

	public void setSha256(String v) { sha256 = v == null ? "" : v; }

	public int getEntryCount() { return entryCount; }

	public void setEntryCount(int v) { entryCount = v; }

	public int getClassCount() { return classCount; }

	public void setClassCount(int v) { classCount = v; }

	public int getResourceCount() { return resourceCount; }

	public void setResourceCount(int v) { resourceCount = v; }

	public int getNestedJarCount() { return nestedJarCount; }

	public void setNestedJarCount(int v) { nestedJarCount = v; }

	public int getNativeLibCount() { return nativeLibCount; }

	public void setNativeLibCount(int v) { nativeLibCount = v; }

	public boolean hasManifest() { return hasManifest; }

	public void setHasManifest(boolean v) { hasManifest = v; }

	public String getManifestText() { return manifestText; }

	public void setManifestText(String v) { manifestText = v == null ? "" : v; }

	public String getMainClass() { return mainClass; }

	public void setMainClass(String v) { mainClass = v; }

	public String getPremainClass() { return premainClass; }

	public void setPremainClass(String v) { premainClass = v; }

	public String getAgentClass() { return agentClass; }

	public void setAgentClass(String v) { agentClass = v; }

	public String getTweakClass() { return tweakClass; }

	public void setTweakClass(String v) { tweakClass = v; }

	public String getModLoader() { return modLoader; }

	public void setModLoader(String v) { modLoader = v; }

	public List<String> getEntryNames() { return entryNames; }

	public DecompileOutcome getDecompileOutcome() { return decompileOutcome; }

	public void setDecompileOutcome(DecompileOutcome v) { decompileOutcome = v; }

	public int getClassesRead() { return classesRead; }

	public void setClassesRead(int v) { classesRead = v; }

	public int getClassesUnreadable() { return classesUnreadable; }

	public void setClassesUnreadable(int v) { classesUnreadable = v; }

	public String getDecompileError() { return decompileError; }

	public void setDecompileError(String v) { decompileError = v; }

	public boolean isObfuscated() { return obfuscated; }

	public void setObfuscated(boolean v) { obfuscated = v; }

	public boolean isEncrypted() { return encrypted; }

	public void setEncrypted(boolean v) { encrypted = v; }

	public boolean isStructurallyBroken() { return structurallyBroken; }

	public void setStructurallyBroken(boolean v) { structurallyBroken = v; }

	public double getObfuscationScore() { return obfuscationScore; }

	public void setObfuscationScore(double v) { obfuscationScore = v; }

	public double getArchiveEntropy() { return archiveEntropy; }

	public void setArchiveEntropy(double v) { archiveEntropy = v; }

	public String getObfuscatorGuess() { return obfuscatorGuess; }

	public void setObfuscatorGuess(String v) { obfuscatorGuess = v == null ? "" : v; }

	public Verdict getVerdict() { return verdict; }

	public void setVerdict(Verdict v) { verdict = v; }

	/**
	 * A short word for <em>why</em> a JAR is suspicious or unreadable, so the
	 * verdict does not just say "suspicious" and leave the reason to be hunted
	 * for. Empty when the verdict speaks for itself (clean, a named detection).
	 *
	 * <p>Encrypted is checked before obfuscated: a password-protected archive
	 * cannot even be read far enough to judge obfuscation, so that is the more
	 * fundamental fact. The obfuscator's name is appended when it was identified,
	 * since "Allatori" tells a reader more than a bare "obfuscated".
	 */
	public String suspicionReason() {
		if (encrypted || decompileOutcome == DecompileOutcome.ENCRYPTED) {
			return Msg.t("wjf.reason.encrypted");
		}
		if (obfuscated) {
			String named = obfuscatorGuess == null ? "" : obfuscatorGuess.trim();
			boolean generic = named.isEmpty()
					|| named.equalsIgnoreCase("Generic")
					|| named.equalsIgnoreCase(Msg.t("proc.none"));
			return generic
					? Msg.t("wjf.reason.obfuscated")
					: Msg.t("wjf.reason.obfuscated") + ": " + named;
		}
		if (decompileOutcome == DecompileOutcome.NOT_AN_ARCHIVE) {
			return Msg.t("wjf.reason.notArchive");
		}
		if (structurallyBroken || decompileOutcome == DecompileOutcome.UNREADABLE) {
			return Msg.t("wjf.reason.broken");
		}
		return "";
	}

	public int getRiskScore() { return riskScore; }

	public void setRiskScore(int v) { riskScore = v; }

	public long getAnalysisMillis() { return analysisMillis; }

	public void setAnalysisMillis(long v) { analysisMillis = v; }

	@Override
	public String toString() {
		return getFileName() + " -> " + verdict.en() + " (" + getFindingCount() + " findings)";
	}
}
