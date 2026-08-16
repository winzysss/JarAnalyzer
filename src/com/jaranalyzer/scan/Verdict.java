package com.jaranalyzer.scan;

import java.awt.Color;

/**
 * Final classification of one JAR, ordered by how much it should worry the
 * operator. The ordinal is what the results table sorts on.
 */
public enum Verdict {

	/** Decompiled cleanly, nothing on the blacklist, no obfuscation. */
	CLEAN("Clean", "Temiz"),

	/** Readable and clean, but something is odd enough to mention. */
	NOTABLE("Notable", "Dikkat"),

	/**
	 * Obfuscated, encrypted, or refused to decompile.
	 *
	 * <p>This is the rule the tool is built around: if the contents cannot be
	 * read, the JAR is suspicious regardless of whether any blacklist term hit,
	 * because an unreadable JAR is exactly where a hidden one would sit.
	 */
	SUSPICIOUS("Suspicious", "Şüpheli"),

	/** At least one blacklist term matched. */
	DETECTED("Detected", "Tespit"),

	/** Blacklist hits AND the archive resisted analysis. */
	CRITICAL("Critical", "Kritik"),

	/** Not a usable archive at all — truncated, locked, or not really a JAR. */
	UNREADABLE("Unreadable", "Okunamadı");

	private final String en;
	private final String tr;

	Verdict(String en, String tr) {
		this.en = en;
		this.tr = tr;
	}

	public String display() {
		return com.jaranalyzer.LanguageManager.getCurrentLanguage()
				== com.jaranalyzer.LanguageManager.Language.TR ? tr : en;
	}

	public String en() {
		return en;
	}

	/** Whether this verdict warrants the operator actually looking at the JAR. */
	public boolean needsAttention() {
		return this == SUSPICIOUS || this == DETECTED || this == CRITICAL || this == UNREADABLE;
	}

	// ---- palette ----------------------------------------------------------

	/**
	 * Badge colour.
	 *
	 * <p>Kept far apart around the colour wheel rather than as a red ramp: the
	 * application chrome is itself red, so a red-on-red severity scale would read
	 * as decoration. Only CRITICAL is red, and it is the most saturated colour in
	 * the UI so it still stands out against the chrome.
	 */
	public Color color() {
		switch (this) {
			case CLEAN:      return new Color(0x3F, 0xD5, 0x9E);   // green
			case NOTABLE:    return new Color(0x5B, 0xB8, 0xFF);   // blue
			case SUSPICIOUS: return new Color(0xFF, 0xC2, 0x4D);   // amber
			case DETECTED:   return new Color(0xFF, 0x70, 0x43);   // deep orange
			case CRITICAL:   return new Color(0xFF, 0x2D, 0x55);   // vivid red
			case UNREADABLE: return new Color(0xA8, 0x90, 0x98);   // warm grey
			default:         return Color.GRAY;
		}
	}

	/** Muted background used behind the badge in the results table. */
	public Color badgeBackground() {
		Color c = color();
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), 38);
	}

	// =====================================================================
	//  Scoring
	// =====================================================================

	/**
	 * Turns a finished analysis into a verdict.
	 *
	 * <p>Two guards keep the output honest rather than alarming:
	 *
	 * <ul>
	 * <li><b>Name-only evidence is capped.</b> A hit that occurs solely in the
	 *     file name or an entry path never reaches CRITICAL on its own — renaming
	 *     a harmless JAR {@code killaura.jar} must not produce a confident
	 *     accusation.
	 * <li><b>CRITICAL needs corroboration.</b> Blacklist hits alone give DETECTED;
	 *     CRITICAL additionally requires that the JAR fought analysis, which is
	 *     the combination that actually distinguishes a hidden cheat from a mod
	 *     that merely mentions the word.
	 * </ul>
	 */
	public static Verdict decide(JarAnalysis a) {
		int score = 0;
		boolean anyBlacklistHit = false;
		boolean substantiveHit = false;   // strong term, seen somewhere other than a name
		int criticalHits = 0;
		int highHits = 0;

		for (Finding f : a.getFindings()) {
			boolean fromBlacklist = f.getPattern() != null;
			if (fromBlacklist) {
				anyBlacklistHit = true;
				boolean nameOnly = f.getSource() == Finding.Source.FILE_NAME
						|| f.getSource() == Finding.Source.ENTRY_PATH;
				// A MEDIUM or LOW term is deliberately not enough on its own.
				// Those are the generic English words — "phase", "step", "esp" —
				// that occur constantly in ordinary mod code, and letting one of
				// them produce DETECTED is what makes a scanner untrustworthy.
				boolean strong = f.getSeverity().weight() >= Severity.HIGH.weight();
				if (!nameOnly && strong) {
					substantiveHit = true;
				}
			}
			if (f.getSeverity() == Severity.CRITICAL) criticalHits++;
			if (f.getSeverity() == Severity.HIGH) highHits++;

			// Repeats of the same term add far less than the first sighting;
			// one obfuscated jar should not out-score a genuinely dirty one.
			score += f.getSeverity().weight() + Math.min(f.getHits() - 1, 10) * 2;
		}

		// "Resisted analysis" has to mean the archive as a whole fought back, not
		// that one class out of three thousand failed to parse. A handful of
		// unparseable classes is normal in any large jar (stripped stubs, multi-
		// release leftovers); treating that as resistance made every big library
		// eligible for CRITICAL.
		int attempted = a.getClassesDecompiled() + a.getClassesFailed();
		boolean mostlyUnreadable = attempted > 0
				&& (double) a.getClassesFailed() / attempted > 0.25;

		boolean resisted = a.isObfuscated() || a.isEncrypted() || a.isStructurallyBroken()
				|| a.getDecompileOutcome() == DecompileOutcome.FAILED
				|| mostlyUnreadable;

		a.setRiskScore(score);

		// An archive nothing could open tells us nothing about its contents, so it
		// is reported as unreadable rather than guessed at — but the score above is
		// still recorded, because the structural findings that explain *why* it
		// could not be opened are real evidence.
		if (a.getDecompileOutcome() == DecompileOutcome.NOT_AN_ARCHIVE
				|| a.getDecompileOutcome() == DecompileOutcome.UNREADABLE) {
			return UNREADABLE;
		}

		if (anyBlacklistHit && resisted && (substantiveHit || criticalHits >= 2)) {
			return CRITICAL;
		}
		if (anyBlacklistHit && substantiveHit) {
			return DETECTED;
		}
		if (anyBlacklistHit && criticalHits > 0) {
			// Name-only, but on a term with no benign reading (a named cheat
			// client, an exfiltration endpoint): still a detection. The
			// anyBlacklistHit guard matters — without it a purely structural
			// CRITICAL such as "archive is encrypted" would be reported as a
			// detection, claiming we found something in code we never read.
			return DETECTED;
		}
		if (resisted) {
			return SUSPICIOUS;
		}
		if (anyBlacklistHit || score >= 20) {
			return NOTABLE;
		}
		return CLEAN;
	}
}
