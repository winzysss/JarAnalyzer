package com.jaranalyzer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

public class LanguageManager {

	public enum Language {
		TR("tr"), EN("en");

		private final String code;

		Language(String code) {
			this.code = code;
		}

		public String getCode() {
			return code;
		}

		public static Language fromCode(String code) {
			if (code == null) return TR;
			for (Language lang : values()) {
				if (lang.code.equalsIgnoreCase(code)) return lang;
			}
			return TR;
		}

		public String getDisplayName() {
			switch (this) {
				case TR: return "Türkçe";
				case EN: return "English";
				default: return "English";
			}
		}
	}

	private static Language currentLanguage = Language.TR;
	private static ResourceBundle bundle;

	static {
		setLanguage(Language.TR);
	}

	public static void setLanguage(Language language) {
		currentLanguage = language;
		loadBundle();
	}

	private static void loadBundle() {
		String resourceName = "/resources/messages_" + currentLanguage.getCode() + ".properties";
		try (InputStream is = LanguageManager.class.getResourceAsStream(resourceName)) {
			if (is != null) {
				Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
				bundle = new PropertyResourceBundle(reader);
			} else {
				bundle = null;
			}
		} catch (Exception e) {
			bundle = null;
		}
	}

	public static String getString(String key) {
		if (bundle != null && bundle.containsKey(key)) {
			return bundle.getString(key);
		}
		return key;
	}

	public static Language getCurrentLanguage() {
		return currentLanguage;
	}

	public static void toggleLanguage() {
		if (currentLanguage == Language.TR) {
			setLanguage(Language.EN);
		} else {
			setLanguage(Language.TR);
		}
	}
}
