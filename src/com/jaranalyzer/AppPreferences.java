package com.jaranalyzer;

public class AppPreferences {
	public static final String DEFAULT_THEME_XML = "light";

	private String themeXml = DEFAULT_THEME_XML;
	private String fileOpenCurrentDirectory = "";
	private String fileSaveCurrentDirectory = "";
	private int font_size = 12;

	private boolean isPackageExplorerStyle = true;
	private boolean isSingleClickOpenEnabled = true;
	private boolean isExitByEscEnabled = false;
	private boolean isDeobfuscateEnabled = true;
	private String language = "tr";
	private java.util.List<String> userKeywords = new java.util.ArrayList<>();

	// ---- scan screen layout ------------------------------------------------
	// Panel visibility and the splitter position live here rather than in
	// ScanSettings: they are window state, not scan behaviour, and this class is
	// already the one persisted for that (theme, accent, font size).

	private boolean showStatCards = true;
	private boolean showDetailPane = true;
	/** Fraction of the scan screen given to the results table. */
	private double detailSplitRatio = 0.55;

	public boolean isShowStatCards() {
		return showStatCards;
	}

	public void setShowStatCards(boolean showStatCards) {
		this.showStatCards = showStatCards;
	}

	public boolean isShowDetailPane() {
		return showDetailPane;
	}

	public void setShowDetailPane(boolean showDetailPane) {
		this.showDetailPane = showDetailPane;
	}

	public double getDetailSplitRatio() {
		// A ratio saved while the pane was collapsed would restore to a useless
		// sliver, so it is clamped back into a sane band on read.
		if (detailSplitRatio < 0.15 || detailSplitRatio > 0.9) return 0.55;
		return detailSplitRatio;
	}

	public void setDetailSplitRatio(double detailSplitRatio) {
		this.detailSplitRatio = detailSplitRatio;
	}

	public String getThemeXml() {
		return themeXml;
	}

	public void setThemeXml(String themeXml) {
		this.themeXml = themeXml;
	}

	public String getFileOpenCurrentDirectory() {
		return fileOpenCurrentDirectory;
	}

	public void setFileOpenCurrentDirectory(String fileOpenCurrentDirectory) {
		this.fileOpenCurrentDirectory = fileOpenCurrentDirectory;
	}

	public String getFileSaveCurrentDirectory() {
		return fileSaveCurrentDirectory;
	}

	public void setFileSaveCurrentDirectory(String fileSaveCurrentDirectory) {
		this.fileSaveCurrentDirectory = fileSaveCurrentDirectory;
	}

	public boolean isPackageExplorerStyle() {
		return isPackageExplorerStyle;
	}

	public void setPackageExplorerStyle(boolean isPackageExplorerStyle) {
		this.isPackageExplorerStyle = isPackageExplorerStyle;
	}

	public boolean isSingleClickOpenEnabled() {
		return isSingleClickOpenEnabled;
	}

	public void setSingleClickOpenEnabled(boolean isSingleClickOpenEnabled) {
		this.isSingleClickOpenEnabled = isSingleClickOpenEnabled;
	}

	public boolean isExitByEscEnabled() {
		return isExitByEscEnabled;
	}

	public void setExitByEscEnabled(boolean isExitByEscEnabled) {
		this.isExitByEscEnabled = isExitByEscEnabled;
	}

	public boolean isDeobfuscateEnabled() {
		return isDeobfuscateEnabled;
	}

	public void setDeobfuscateEnabled(boolean isDeobfuscateEnabled) {
		this.isDeobfuscateEnabled = isDeobfuscateEnabled;
	}

	public int getFont_size() {
		return font_size;
	}

	public void setFont_size(int font_size) {
		this.font_size = font_size;
	}

	public String getLanguage() {
		return language;
	}

	public void setLanguage(String language) {
		this.language = language;
	}

	public java.util.List<String> getUserKeywords() {
		return userKeywords;
	}

	public void setUserKeywords(java.util.List<String> userKeywords) {
		this.userKeywords = userKeywords != null ? userKeywords : new java.util.ArrayList<>();
	}
}
