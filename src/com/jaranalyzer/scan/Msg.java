package com.jaranalyzer.scan;

import com.jaranalyzer.LanguageManager;

/**
 * Localised text for engine findings.
 *
 * <p>Findings are the part a user actually reads, so they are localised rather
 * than built from English string literals — a Turkish window reporting "Archive
 * structure unreadable" would tell the reader nothing.
 *
 * <p>Kept deliberately thin: a key lookup plus {@link String#format}. The engine
 * has no other reason to know about the UI, and this keeps the coupling to one
 * import.
 */
public final class Msg {

	private Msg() {
	}

	public static String t(String key) {
		return LanguageManager.getString(key);
	}

	/**
	 * Formatted lookup.
	 *
	 * <p>Falls back to the raw pattern if the translation's placeholders do not
	 * match the arguments — a broken properties line should degrade to odd text,
	 * not throw in the middle of a scan.
	 */
	public static String t(String key, Object... args) {
		String pattern = LanguageManager.getString(key);
		try {
			return String.format(pattern, args);
		} catch (java.util.IllegalFormatException e) {
			return pattern;
		}
	}
}
