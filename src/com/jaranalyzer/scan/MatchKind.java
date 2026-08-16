package com.jaranalyzer.scan;

/**
 * How a blacklist pattern is turned into a regular expression.
 *
 * <p>{@link #WORD} is the default because Java identifiers are the usual target:
 * searching for {@code Fly} as a bare substring matches {@code Butterfly} and
 * {@code FlyweightFactory}, which is exactly the false-positive class of bug the
 * old hardcoded keyword list suffered from.
 */
public enum MatchKind {

	/** Plain substring, anywhere. */
	LITERAL("Literal", "Düz metin"),

	/** Substring that must not be flanked by other Java identifier characters. */
	WORD("Word", "Tam kelime"),

	/** Raw {@link java.util.regex.Pattern} syntax, supplied by the user. */
	REGEX("Regex", "Regex");

	private final String en;
	private final String tr;

	MatchKind(String en, String tr) {
		this.en = en;
		this.tr = tr;
	}

	public String display() {
		return com.jaranalyzer.LanguageManager.getCurrentLanguage()
				== com.jaranalyzer.LanguageManager.Language.TR ? tr : en;
	}

	public static MatchKind parse(String raw, MatchKind fallback) {
		if (raw == null) return fallback;
		for (MatchKind k : values()) {
			if (k.name().equalsIgnoreCase(raw.trim())) return k;
		}
		return fallback;
	}
}
