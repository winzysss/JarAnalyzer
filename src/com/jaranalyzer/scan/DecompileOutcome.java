package com.jaranalyzer.scan;

/** How far the read got on one JAR. */
public enum DecompileOutcome {

	NOT_ATTEMPTED("Not attempted", "Denenmedi"),

	/**
	 * The classes were read and searched through their constant pools. This is the
	 * ordinary successful result: no source was reconstructed, because every name
	 * and literal a blacklist term can match is already in the pool, and rebuilding
	 * Java out of it costs minutes per large archive without finding anything more.
	 */
	POOL_SCANNED("Scanned", "Tarandı"),

	/** Classes are present but nothing could be read out of any of them. */
	FAILED("Failed", "Başarısız"),

	/** Entries are password protected. */
	ENCRYPTED("Encrypted", "Şifreli"),

	/** A valid archive that simply contains no classes (resource pack, data jar). */
	NO_CLASSES("No classes", "Sınıf yok"),

	/** The archive could not be opened at all. */
	UNREADABLE("Unreadable", "Okunamadı"),

	/** The file is not a ZIP container despite its extension. */
	NOT_AN_ARCHIVE("Not an archive", "Arşiv değil");

	private final String en;
	private final String tr;

	DecompileOutcome(String en, String tr) {
		this.en = en;
		this.tr = tr;
	}

	public String display() {
		return com.jaranalyzer.LanguageManager.getCurrentLanguage()
				== com.jaranalyzer.LanguageManager.Language.TR ? tr : en;
	}

	/** Language-independent label, for reports that must stay diffable. */
	public String en() {
		return en;
	}

	/**
	 * True when the operator did not get to read the code. Under the tool's core
	 * rule this is what makes a JAR suspicious on its own.
	 */
	public boolean isOpaque() {
		return this == FAILED || this == ENCRYPTED || this == UNREADABLE
				|| this == NOT_AN_ARCHIVE;
	}
}
