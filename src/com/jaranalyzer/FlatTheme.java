package com.jaranalyzer;

import java.awt.Color;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;

public class FlatTheme {

	private static Color bg;
	private static Color panelBg;
	private static Color panelLighter;
	private static Color accent;
	private static Color accentHover;
	private static Color textPrimary;
	private static Color textSecondary;
	private static Color borderColor;
	private static Color tabSelected;
	private static Color tabUnselected;
	private static Color scrollTrack;
	private static Color tableAltRow;
	private static Color tableHeaderBg;
	private static Color buttonBg;
	private static Color buttonHover;
	private static Color buttonPressed;
	private static Color statusBarBg;
	private static Color selectionBg;
	private static Color selectionFg;

	private static boolean darkMode = false;
	private static String currentTheme = "light";

	static {
		initThemeColors("light");
	}

	private static void initThemeColors(String themeKey) {
		currentTheme = themeKey;
		switch (themeKey) {
			case "dracula":
				darkMode = true;
				bg = new Color(40, 42, 54);
				panelBg = new Color(44, 46, 60);
				panelLighter = new Color(68, 71, 90);
				accent = new Color(189, 147, 249);
				accentHover = new Color(98, 114, 164);
				textPrimary = new Color(248, 248, 242);
				textSecondary = new Color(98, 114, 164);
				borderColor = new Color(68, 71, 90);
				tabSelected = new Color(68, 71, 90);
				tabUnselected = new Color(40, 42, 54);
				scrollTrack = new Color(44, 46, 60);
				tableAltRow = new Color(48, 50, 64);
				tableHeaderBg = new Color(68, 71, 90);
				buttonBg = new Color(68, 71, 90);
				buttonHover = new Color(80, 84, 100);
				buttonPressed = new Color(55, 58, 75);
				statusBarBg = new Color(34, 36, 48);
				selectionBg = new Color(68, 71, 90);
				selectionFg = new Color(248, 248, 242);
				break;
			case "onedark":
				darkMode = true;
				bg = new Color(40, 44, 52);
				panelBg = new Color(44, 48, 56);
				panelLighter = new Color(61, 66, 77);
				accent = new Color(97, 175, 239);
				accentHover = new Color(86, 182, 139);
				textPrimary = new Color(232, 232, 232);
				textSecondary = new Color(160, 168, 180);
				borderColor = new Color(61, 66, 77);
				tabSelected = new Color(61, 66, 77);
				tabUnselected = new Color(40, 44, 52);
				scrollTrack = new Color(44, 48, 56);
				tableAltRow = new Color(48, 52, 60);
				tableHeaderBg = new Color(61, 66, 77);
				buttonBg = new Color(61, 66, 77);
				buttonHover = new Color(75, 80, 92);
				buttonPressed = new Color(52, 57, 68);
				statusBarBg = new Color(33, 37, 43);
				selectionBg = new Color(61, 66, 77);
				selectionFg = new Color(171, 178, 191);
				break;
			case "githubdark":
				darkMode = true;
				bg = new Color(13, 17, 23);
				panelBg = new Color(22, 27, 34);
				panelLighter = new Color(33, 38, 45);
				accent = new Color(88, 166, 255);
				accentHover = new Color(47, 129, 247);
				textPrimary = new Color(232, 232, 232);
				textSecondary = new Color(160, 168, 180);
				borderColor = new Color(33, 38, 45);
				tabSelected = new Color(33, 38, 45);
				tabUnselected = new Color(13, 17, 23);
				scrollTrack = new Color(22, 27, 34);
				tableAltRow = new Color(18, 22, 29);
				tableHeaderBg = new Color(33, 38, 45);
				buttonBg = new Color(33, 38, 45);
				buttonHover = new Color(45, 51, 60);
				buttonPressed = new Color(28, 33, 40);
				statusBarBg = new Color(7, 10, 15);
				selectionBg = new Color(56, 62, 79);
				selectionFg = new Color(201, 209, 217);
				break;
			case "monokai":
				darkMode = true;
				bg = new Color(45, 42, 46);
				panelBg = new Color(50, 47, 51);
				panelLighter = new Color(63, 60, 64);
				accent = new Color(255, 97, 136);
				accentHover = new Color(255, 216, 102);
				textPrimary = new Color(252, 252, 250);
				textSecondary = new Color(114, 112, 114);
				borderColor = new Color(63, 60, 64);
				tabSelected = new Color(63, 60, 64);
				tabUnselected = new Color(45, 42, 46);
				scrollTrack = new Color(50, 47, 51);
				tableAltRow = new Color(54, 51, 55);
				tableHeaderBg = new Color(63, 60, 64);
				buttonBg = new Color(63, 60, 64);
				buttonHover = new Color(75, 72, 76);
				buttonPressed = new Color(54, 51, 55);
				statusBarBg = new Color(38, 35, 39);
				selectionBg = new Color(57, 56, 62);
				selectionFg = new Color(252, 252, 250);
				break;
			case "solarized":
				darkMode = true;
				bg = new Color(0, 43, 54);
				panelBg = new Color(7, 54, 66);
				panelLighter = new Color(31, 61, 74);
				accent = new Color(38, 139, 210);
				accentHover = new Color(133, 153, 0);
				textPrimary = new Color(232, 232, 232);
				textSecondary = new Color(160, 168, 180);
				borderColor = new Color(31, 61, 74);
				tabSelected = new Color(31, 61, 74);
				tabUnselected = new Color(0, 43, 54);
				scrollTrack = new Color(7, 54, 66);
				tableAltRow = new Color(7, 54, 67);
				tableHeaderBg = new Color(31, 61, 74);
				buttonBg = new Color(31, 61, 74);
				buttonHover = new Color(43, 73, 86);
				buttonPressed = new Color(23, 53, 66);
				statusBarBg = new Color(0, 36, 46);
				selectionBg = new Color(7, 54, 66);
				selectionFg = new Color(232, 232, 232);
				break;
			case "nord":
				darkMode = true;
				bg = new Color(46, 52, 64);
				panelBg = new Color(52, 60, 76);
				panelLighter = new Color(59, 66, 82);
				accent = new Color(136, 192, 208);
				accentHover = new Color(129, 161, 193);
				textPrimary = new Color(232, 232, 232);
				textSecondary = new Color(160, 168, 180);
				borderColor = new Color(59, 66, 82);
				tabSelected = new Color(59, 66, 82);
				tabUnselected = new Color(46, 52, 64);
				scrollTrack = new Color(52, 60, 76);
				tableAltRow = new Color(54, 62, 78);
				tableHeaderBg = new Color(59, 66, 82);
				buttonBg = new Color(59, 66, 82);
				buttonHover = new Color(72, 80, 96);
				buttonPressed = new Color(50, 58, 72);
				statusBarBg = new Color(40, 46, 58);
				selectionBg = new Color(59, 66, 82);
				selectionFg = new Color(232, 232, 232);
				break;
			case "materialdark":
				darkMode = true;
				bg = new Color(30, 30, 30);
				panelBg = new Color(38, 38, 38);
				panelLighter = new Color(50, 50, 50);
				accent = new Color(130, 177, 255);
				accentHover = new Color(199, 146, 234);
				textPrimary = new Color(232, 232, 232);
				textSecondary = new Color(160, 168, 180);
				borderColor = new Color(50, 50, 50);
				tabSelected = new Color(50, 50, 50);
				tabUnselected = new Color(30, 30, 30);
				scrollTrack = new Color(38, 38, 38);
				tableAltRow = new Color(34, 34, 34);
				tableHeaderBg = new Color(50, 50, 50);
				buttonBg = new Color(50, 50, 50);
				buttonHover = new Color(64, 64, 64);
				buttonPressed = new Color(42, 42, 42);
				statusBarBg = new Color(24, 24, 24);
				selectionBg = new Color(50, 50, 50);
				selectionFg = new Color(232, 232, 232);
				break;
			case "tokyonight":
				darkMode = true;
				bg = new Color(26, 27, 38);
				panelBg = new Color(32, 34, 48);
				panelLighter = new Color(36, 38, 54);
				accent = new Color(122, 162, 247);
				accentHover = new Color(187, 154, 247);
				textPrimary = new Color(232, 232, 232);
				textSecondary = new Color(160, 168, 180);
				borderColor = new Color(36, 38, 54);
				tabSelected = new Color(36, 38, 54);
				tabUnselected = new Color(26, 27, 38);
				scrollTrack = new Color(32, 34, 48);
				tableAltRow = new Color(30, 32, 44);
				tableHeaderBg = new Color(36, 38, 54);
				buttonBg = new Color(36, 38, 54);
				buttonHover = new Color(48, 52, 70);
				buttonPressed = new Color(30, 32, 46);
				statusBarBg = new Color(20, 22, 32);
				selectionBg = new Color(36, 38, 54);
				selectionFg = new Color(232, 232, 232);
				break;
			case "gruvbox":
				darkMode = true;
				bg = new Color(40, 40, 40);
				panelBg = new Color(50, 48, 47);
				panelLighter = new Color(60, 56, 54);
				accent = new Color(250, 189, 47);
				accentHover = new Color(251, 73, 52);
				textPrimary = new Color(232, 232, 232);
				textSecondary = new Color(160, 168, 180);
				borderColor = new Color(60, 56, 54);
				tabSelected = new Color(60, 56, 54);
				tabUnselected = new Color(40, 40, 40);
				scrollTrack = new Color(50, 48, 47);
				tableAltRow = new Color(46, 44, 43);
				tableHeaderBg = new Color(60, 56, 54);
				buttonBg = new Color(60, 56, 54);
				buttonHover = new Color(74, 70, 68);
				buttonPressed = new Color(52, 50, 48);
				statusBarBg = new Color(32, 32, 32);
				selectionBg = new Color(60, 56, 54);
				selectionFg = new Color(232, 232, 232);
				break;
			case "light":
				darkMode = false;
				bg = new Color(250, 250, 250);
				panelBg = new Color(255, 255, 255);
				panelLighter = new Color(238, 238, 238);
				accent = new Color(3, 102, 214);
				accentHover = new Color(2, 89, 188);
				textPrimary = new Color(36, 41, 46);
				textSecondary = new Color(106, 115, 125);
				borderColor = new Color(208, 215, 222);
				tabSelected = new Color(255, 255, 255);
				tabUnselected = new Color(243, 246, 248);
				scrollTrack = new Color(238, 238, 238);
				tableAltRow = new Color(248, 248, 248);
				tableHeaderBg = new Color(240, 240, 240);
				buttonBg = new Color(255, 255, 255);
				buttonHover = new Color(230, 230, 230);
				buttonPressed = new Color(220, 220, 220);
				statusBarBg = new Color(238, 238, 238);
				selectionBg = new Color(222, 230, 241);
				selectionFg = new Color(36, 41, 46);
				break;
			default:
				initThemeColors("light");
				return;
		}
	}

	public static void applyTheme(String themeKey) {
		initThemeColors(themeKey);
		applyCurrentTheme();
	}

	public static void applyDarkTheme() {
		applyTheme("dracula");
	}

	public static void applyLightTheme() {
		applyTheme("light");
	}

	private static void applyCurrentTheme() {
		UIManager.put("Panel.background", new ColorUIResource(bg));
		UIManager.put("OptionPane.background", new ColorUIResource(bg));
		UIManager.put("MenuBar.background", new ColorUIResource(bg));
		UIManager.put("Menu.background", new ColorUIResource(bg));
		UIManager.put("MenuItem.background", new ColorUIResource(bg));
		UIManager.put("MenuItem.foreground", new ColorUIResource(textPrimary));
		UIManager.put("Menu.foreground", new ColorUIResource(textPrimary));
		UIManager.put("MenuBar.foreground", new ColorUIResource(textPrimary));
		UIManager.put("PopupMenu.background", new ColorUIResource(bg));
		UIManager.put("ToolBar.background", new ColorUIResource(bg));
		UIManager.put("ToolBar.foreground", new ColorUIResource(textPrimary));
		UIManager.put("Button.background", new ColorUIResource(buttonBg));
		UIManager.put("Button.foreground", new ColorUIResource(textPrimary));
		UIManager.put("Button.border", BorderFactory.createEmptyBorder(5, 12, 5, 12));
		UIManager.put("Button.focus", new ColorUIResource(new Color(0, 0, 0, 0)));
		UIManager.put("Label.foreground", new ColorUIResource(textPrimary));
		UIManager.put("TextField.background", new ColorUIResource(panelLighter));
		UIManager.put("TextField.foreground", new ColorUIResource(textPrimary));
		UIManager.put("TextField.caretForeground", new ColorUIResource(textPrimary));
		UIManager.put("TextArea.background", new ColorUIResource(panelLighter));
		UIManager.put("TextArea.foreground", new ColorUIResource(textPrimary));
		UIManager.put("TextArea.caretForeground", new ColorUIResource(textPrimary));
		UIManager.put("EditorPane.background", new ColorUIResource(panelLighter));
		UIManager.put("EditorPane.foreground", new ColorUIResource(textPrimary));
		UIManager.put("EditorPane.caretForeground", new ColorUIResource(textPrimary));
		UIManager.put("CheckBox.background", new ColorUIResource(bg));
		UIManager.put("CheckBox.foreground", new ColorUIResource(textPrimary));
		UIManager.put("RadioButton.background", new ColorUIResource(bg));
		UIManager.put("RadioButton.foreground", new ColorUIResource(textPrimary));
		UIManager.put("ComboBox.background", new ColorUIResource(buttonBg));
		UIManager.put("ComboBox.foreground", new ColorUIResource(textPrimary));
		UIManager.put("ComboBox.selectionBackground", new ColorUIResource(accent));
		UIManager.put("ComboBox.selectionForeground", new ColorUIResource(Color.WHITE));
		UIManager.put("ProgressBar.background", new ColorUIResource(panelLighter));
		UIManager.put("ProgressBar.foreground", new ColorUIResource(accent));
		UIManager.put("ProgressBar.selectionBackground", new ColorUIResource(Color.WHITE));
		UIManager.put("ProgressBar.selectionForeground", new ColorUIResource(textPrimary));
		UIManager.put("ScrollPane.background", new ColorUIResource(bg));
		UIManager.put("ScrollPane.viewportBackground", new ColorUIResource(bg));
		UIManager.put("ScrollBar.background", new ColorUIResource(bg));
		UIManager.put("ScrollBar.track", new ColorUIResource(scrollTrack));
		UIManager.put("ScrollBar.thumb", new ColorUIResource(panelLighter));
		UIManager.put("ScrollBar.thumbHighlight", new ColorUIResource(accent));
		UIManager.put("TabbedPane.background", new ColorUIResource(bg));
		UIManager.put("TabbedPane.foreground", new ColorUIResource(textPrimary));
		UIManager.put("TabbedPane.selectedBackground", new ColorUIResource(tabSelected));
		UIManager.put("TabbedPane.tabAreaBackground", new ColorUIResource(tabUnselected));
		UIManager.put("TabbedPane.contentAreaColor", new ColorUIResource(bg));
		UIManager.put("TabbedPane.focusColor", new ColorUIResource(accent));
		UIManager.put("TabbedPane.borderColor", new ColorUIResource(borderColor));
		UIManager.put("Table.background", new ColorUIResource(bg));
		UIManager.put("Table.foreground", new ColorUIResource(textPrimary));
		UIManager.put("Table.selectionBackground", new ColorUIResource(accent));
		UIManager.put("Table.selectionForeground", new ColorUIResource(Color.WHITE));
		UIManager.put("Table.gridColor", new ColorUIResource(borderColor));
		UIManager.put("TableHeader.background", new ColorUIResource(tableHeaderBg));
		UIManager.put("TableHeader.foreground", new ColorUIResource(textPrimary));
		UIManager.put("Tree.background", new ColorUIResource(bg));
		UIManager.put("Tree.foreground", new ColorUIResource(textPrimary));
		UIManager.put("Tree.selectionBackground", new ColorUIResource(accent));
		UIManager.put("Tree.selectionForeground", new ColorUIResource(Color.WHITE));
		UIManager.put("Tree.textBackground", new ColorUIResource(bg));
		UIManager.put("Tree.line", new ColorUIResource(borderColor));
		UIManager.put("SplitPane.background", new ColorUIResource(bg));
		UIManager.put("SplitPane.dividerBackground", new ColorUIResource(bg));
		UIManager.put("TitledBorder.titleColor", new ColorUIResource(textPrimary));
		UIManager.put("TitledBorder.border", new ColorUIResource(borderColor));
		UIManager.put("List.background", new ColorUIResource(bg));
		UIManager.put("List.foreground", new ColorUIResource(textPrimary));
		UIManager.put("List.selectionBackground", new ColorUIResource(accent));
		UIManager.put("List.selectionForeground", new ColorUIResource(Color.WHITE));
		UIManager.put("MenuItem.selectionBackground", new ColorUIResource(accent));
		UIManager.put("MenuItem.selectionForeground", new ColorUIResource(Color.WHITE));
		UIManager.put("Menu.selectionBackground", new ColorUIResource(accent));
		UIManager.put("Menu.selectionForeground", new ColorUIResource(Color.WHITE));
		UIManager.put("Separator.foreground", new ColorUIResource(borderColor));
		UIManager.put("ToolTip.background", new ColorUIResource(panelLighter));
		UIManager.put("ToolTip.foreground", new ColorUIResource(textPrimary));
		UIManager.put("OptionPane.messageForeground", new ColorUIResource(textPrimary));

		setDefaultFont();
	}

	private static void setDefaultFont() {
		Font font = new Font("Segoe UI", Font.PLAIN, 13);
		UIManager.put("Button.font", font);
		UIManager.put("Label.font", font);
		UIManager.put("Menu.font", font);
		UIManager.put("MenuItem.font", font);
		UIManager.put("MenuBar.font", font);
		UIManager.put("Panel.font", font);
		UIManager.put("TextField.font", font);
		UIManager.put("TextArea.font", new Font("Consolas", Font.PLAIN, 13));
		UIManager.put("EditorPane.font", new Font("Consolas", Font.PLAIN, 13));
		UIManager.put("CheckBox.font", font);
		UIManager.put("RadioButton.font", font);
		UIManager.put("ComboBox.font", font);
		UIManager.put("TabbedPane.font", font);
		UIManager.put("Table.font", font);
		UIManager.put("TableHeader.font", font);
		UIManager.put("Tree.font", font);
		UIManager.put("ToolBar.font", font);
		UIManager.put("ProgressBar.font", font);
		UIManager.put("TitledBorder.font", font);
		UIManager.put("List.font", font);
	}

	public static boolean isDarkMode() {
		return darkMode;
	}

	public static String getCurrentTheme() {
		return currentTheme;
	}

	public static Color getBgColor() {
		return bg;
	}

	public static Color getPanelBg() {
		return panelBg;
	}

	public static Color getTextColor() {
		return textPrimary;
	}

	public static Color getTextSecondary() {
		return textSecondary;
	}

	public static Color getBorderColor() {
		return borderColor;
	}

	public static Color getAccentColor() {
		return accent;
	}

	public static Color getButtonBg() {
		return buttonBg;
	}

	public static Color getButtonHover() {
		return buttonHover;
	}

	public static Color getStatusBarBg() {
		return statusBarBg;
	}

	public static Color getTableAltRow() {
		return tableAltRow;
	}

	public static Color getTableHeaderBg() {
		return tableHeaderBg;
	}

	public static Color getSelectionBg() {
		return selectionBg;
	}

	public static Color getSelectionFg() {
		return selectionFg;
	}
}
