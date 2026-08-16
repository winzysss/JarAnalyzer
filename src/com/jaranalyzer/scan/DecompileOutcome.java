package com.jaranalyzer.scan;

/** How far decompilation got on one JAR. */
public enum DecompileOutcome {

	NOT_ATTEMPTED("Not attempted", "Denenmedi"),

	/**
	 * Every class was read and searched through its constant pool, but no source
	 * was reconstructed. This is <em>not</em> an opaque result — the contents were
	 * examined; they were simply not turned back into Java, which costs two
	 * minutes per large archive and adds nothing a keyword search can use.
	 */
	POOL_SCANNED("Scanned", "Tarandı"),

	/** CFR produced Java source for every class. */
	FULL_SOURCE("Source", "Kaynak"),

	/** Source for some classes; the rest fell back to a bytecode listing. */
	PARTIAL_SOURCE("Partial", "Kısmi"),

	/** No class yielded source; only bytecode listings were recoverable. */
	BYTECODE_ONLY("Bytecode", "Bytecode"),

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
				|| this == NOT_AN_ARCHIVE || this == BYTECODE_ONLY;
	}
}
