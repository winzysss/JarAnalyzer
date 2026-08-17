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
		applySwingStrings();
	}

	/**
	 * Translates the strings Swing supplies itself.
	 *
	 * <p>Dialog buttons and the file chooser are not built from application text —
	 * a look and feel ships its own words for "Yes", "Cancel", "Look in", and picks
	 * them by the JVM's default locale, which has nothing to do with the language
	 * the user chose in this window. Left alone, a Turkish confirmation ends in
	 * English Yes/No buttons. Setting them here rather than at each of the several
	 * dozen call sites means a dialog added later is translated by default.
	 */
	public static void applySwingStrings() {
		boolean tr = currentLanguage == Language.TR;
		javax.swing.UIManager.put("OptionPane.yesButtonText", tr ? "Evet" : "Yes");
		javax.swing.UIManager.put("OptionPane.noButtonText", tr ? "Hayır" : "No");
		javax.swing.UIManager.put("OptionPane.cancelButtonText", tr ? "İptal" : "Cancel");
		javax.swing.UIManager.put("OptionPane.okButtonText", tr ? "Tamam" : "OK");
		javax.swing.UIManager.put("OptionPane.titleText", tr ? "Mesaj" : "Message");
		javax.swing.UIManager.put("OptionPane.messageDialogTitle", tr ? "Mesaj" : "Message");
		javax.swing.UIManager.put("OptionPane.inputDialogTitle", tr ? "Giriş" : "Input");

		javax.swing.UIManager.put("FileChooser.openDialogTitleText", tr ? "Aç" : "Open");
		javax.swing.UIManager.put("FileChooser.saveDialogTitleText", tr ? "Kaydet" : "Save");
		javax.swing.UIManager.put("FileChooser.openButtonText", tr ? "Aç" : "Open");
		javax.swing.UIManager.put("FileChooser.saveButtonText", tr ? "Kaydet" : "Save");
		javax.swing.UIManager.put("FileChooser.cancelButtonText", tr ? "İptal" : "Cancel");
		javax.swing.UIManager.put("FileChooser.updateButtonText", tr ? "Güncelle" : "Update");
		javax.swing.UIManager.put("FileChooser.helpButtonText", tr ? "Yardım" : "Help");
		javax.swing.UIManager.put("FileChooser.directoryOpenButtonText", tr ? "Aç" : "Open");
		javax.swing.UIManager.put("FileChooser.lookInLabelText", tr ? "Konum:" : "Look in:");
		javax.swing.UIManager.put("FileChooser.saveInLabelText", tr ? "Konum:" : "Save in:");
		javax.swing.UIManager.put("FileChooser.fileNameLabelText", tr ? "Dosya adı:" : "File name:");
		javax.swing.UIManager.put("FileChooser.filesOfTypeLabelText", tr ? "Dosya türü:" : "Files of type:");
		javax.swing.UIManager.put("FileChooser.acceptAllFileFilterText",
				tr ? "Tüm dosyalar" : "All files");
		javax.swing.UIManager.put("FileChooser.upFolderToolTipText",
				tr ? "Bir üst klasör" : "Up one level");
		javax.swing.UIManager.put("FileChooser.homeFolderToolTipText",
				tr ? "Masaüstü" : "Home");
		javax.swing.UIManager.put("FileChooser.newFolderToolTipText",
				tr ? "Yeni klasör" : "Create new folder");
		javax.swing.UIManager.put("FileChooser.listViewButtonToolTipText",
				tr ? "Liste" : "List");
		javax.swing.UIManager.put("FileChooser.detailsViewButtonToolTipText",
				tr ? "Ayrıntılar" : "Details");
		javax.swing.UIManager.put("FileChooser.newFolderButtonText",
				tr ? "Yeni klasör" : "New folder");
		javax.swing.UIManager.put("FileChooser.renameFileButtonText",
				tr ? "Yeniden adlandır" : "Rename");
		javax.swing.UIManager.put("FileChooser.deleteFileButtonText", tr ? "Sil" : "Delete");
		javax.swing.UIManager.put("FileChooser.filterLabelText", tr ? "Dosya türü:" : "Files of type:");
		javax.swing.UIManager.put("FileChooser.fileNameHeaderText", tr ? "Ad" : "Name");
		javax.swing.UIManager.put("FileChooser.fileSizeHeaderText", tr ? "Boyut" : "Size");
		javax.swing.UIManager.put("FileChooser.fileTypeHeaderText", tr ? "Tür" : "Type");
		javax.swing.UIManager.put("FileChooser.fileDateHeaderText", tr ? "Değiştirilme" : "Modified");
		javax.swing.UIManager.put("FileChooser.fileAttrHeaderText", tr ? "Öznitelik" : "Attributes");
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
