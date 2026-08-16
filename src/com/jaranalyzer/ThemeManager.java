package com.jaranalyzer;

import java.awt.Color;

public class ThemeManager {

	public static class ThemeColors {
		public String background;
		public String foreground;
		public String keyword;
		public String string;
		public String comment;
		public String number;
		public String type;
		public Color bgColor;
		public Color fgColor;
		public Color selectionBg;
		public Color selectionFg;

		public ThemeColors(String bg, String fg, String kw, String str, String cmt,
				String num, String type, Color selBg, Color selFg) {
			this.background = bg;
			this.foreground = fg;
			this.keyword = kw;
			this.string = str;
			this.comment = cmt;
			this.number = num;
			this.type = type;
			this.bgColor = Color.decode("#" + bg);
			this.fgColor = Color.decode("#" + fg);
			this.selectionBg = selBg;
			this.selectionFg = selFg;
		}
	}

	public static final String[] THEME_KEYS = {
		"dracula", "onedark", "githubdark", "monokai", "solarized", "light",
		"nord", "materialdark", "tokyonight", "gruvbox"
	};

	public static String[] getThemeDisplayNames() {
		return new String[] {
			LanguageManager.getString("theme.dracula"),
			LanguageManager.getString("theme.onedark"),
			LanguageManager.getString("theme.githubdark"),
			LanguageManager.getString("theme.monokai"),
			LanguageManager.getString("theme.solarized"),
			LanguageManager.getString("theme.light"),
			LanguageManager.getString("theme.nord"),
			LanguageManager.getString("theme.materialdark"),
			LanguageManager.getString("theme.tokyonight"),
			LanguageManager.getString("theme.gruvbox")
		};
	}

	public static ThemeColors getTheme(String key) {
		switch (key) {
			case "dracula":
				return new ThemeColors("282a36", "f8f8f2", "ff79c6", "f1fa8c",
						"6272a4", "bd93f9", "8be9fd",
						new Color(68, 71, 90), new Color(248, 248, 242));
			case "onedark":
				return new ThemeColors("282c34", "abb2bf", "c678dd", "98c379",
						"5c6370", "d19a66", "61afef",
						new Color(61, 66, 77), new Color(171, 178, 191));
			case "githubdark":
				return new ThemeColors("0d1117", "c9d1d9", "ff7b72", "a5d6ff",
						"8b949e", "79c0ff", "ffa657",
						new Color(56, 62, 79), new Color(201, 209, 217));
			case "monokai":
				return new ThemeColors("2d2a2e", "fcfcfa", "ff6188", "ffd866",
						"727072", "ab9df2", "78dce8",
						new Color(57, 56, 62), new Color(252, 252, 250));
			case "solarized":
				return new ThemeColors("002b36", "839496", "859900", "2aa198",
						"586e75", "d33682", "268bd2",
						new Color(7, 54, 66), new Color(131, 148, 150));
			case "light":
				return new ThemeColors("ffffff", "24292e", "d73a49", "032f62",
						"6a737d", "005cc5", "6f42c1",
						new Color(222, 230, 241), new Color(36, 41, 46));
			case "nord":
				return new ThemeColors("2e3440", "e8e8e8", "81a1c1", "a3be8c",
						"4c566a", "b48ead", "88c0d0",
						new Color(59, 66, 82), new Color(232, 232, 232));
			case "materialdark":
				return new ThemeColors("1e1e1e", "e8e8e8", "c792ea", "c3e88d",
						"546e7a", "f78c6c", "82b1ff",
						new Color(40, 40, 40), new Color(232, 232, 232));
			case "tokyonight":
				return new ThemeColors("1a1b26", "e8e8e8", "bb9af7", "9ece6a",
						"565f89", "ff9e64", "7aa2f7",
						new Color(36, 38, 54), new Color(232, 232, 232));
			case "gruvbox":
				return new ThemeColors("282828", "e8e8e8", "fb4934", "b8bb26",
						"928374", "d3869b", "fabd2f",
						new Color(40, 40, 40), new Color(232, 232, 232));
			default:
				return getTheme("dracula");
		}
	}

	public static String getDefaultTheme() {
		return "dracula";
	}

	public static int getThemeIndex(String key) {
		for (int i = 0; i < THEME_KEYS.length; i++) {
			if (THEME_KEYS[i].equals(key)) return i;
		}
		return 0;
	}
}
