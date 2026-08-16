package com.jaranalyzer.scan;

/**
 * How much weight a single finding carries. Scores accumulate into the per-JAR
 * risk total that {@link Verdict} thresholds are read from.
 */
public enum Severity {

	INFO("Info", "Bilgi", 0),
	LOW("Low", "Düşük", 5),
	MEDIUM("Medium", "Orta", 20),
	HIGH("High", "Yüksek", 50),
	CRITICAL("Critical", "Kritik", 100);

	private final String en;
	private final String tr;
	private final int weight;

	Severity(String en, String tr, int weight) {
		this.en = en;
		this.tr = tr;
		this.weight = weight;
	}

	public int weight() {
		return weight;
	}

	public String display() {
		return com.jaranalyzer.LanguageManager.getCurrentLanguage()
				== com.jaranalyzer.LanguageManager.Language.TR ? tr : en;
	}

	public String en() {
		return en;
	}

	public static Severity parse(String raw, Severity fallback) {
		if (raw == null) return fallback;
		for (Severity s : values()) {
			if (s.name().equalsIgnoreCase(raw.trim())) return s;
		}
		return fallback;
	}
}
