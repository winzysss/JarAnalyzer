package com.jaranalyzer.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.HashSet;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;

/**
 * Applies the dark theme to Swing's shared defaults.
 *
 * <p>Metal is used rather than the Windows look and feel because the Windows LAF
 * paints most controls with native, uncolourable bitmaps — a dark theme on top of
 * it produces dark panels with stubbornly light buttons. Metal draws everything
 * itself and honours the colour keys, which is what makes a consistent dark UI
 * possible without shipping a third-party look and feel.
 */
public final class WinzyTheme {

	private WinzyTheme() {
	}

	private static Font uiFont;
	private static Font monoFont;

	public static Font ui() {
		return uiFont;
	}

	public static Font ui(int style, float size) {
		return uiFont.deriveFont(style, size);
	}

	public static Font mono() {
		return monoFont;
	}

	public static Font mono(int style, float size) {
		return monoFont.deriveFont(style, size);
	}

	// =====================================================================

	public static void apply() {
		resolveFonts();

		try {
			// A Metal theme that reports our colours; the LAF then derives the
			// dozens of shades it paints borders and gradients from.
			MetalLookAndFeel.setCurrentTheme(new DefaultMetalTheme() {
				@Override
				public ColorUIResource getPrimaryControl() {
					return res(WinzyPalette.PANEL_HI);
				}

				@Override
				public ColorUIResource getPrimaryControlHighlight() {
					return res(WinzyPalette.LINE);
				}

				@Override
				public ColorUIResource getPrimaryControlDarkShadow() {
					return res(WinzyPalette.LINE);
				}

				@Override
				public ColorUIResource getPrimaryControlShadow() {
					return res(WinzyPalette.LINE_SOFT);
				}

				@Override
				public ColorUIResource getControl() {
					return res(WinzyPalette.PANEL);
				}

				@Override
				public ColorUIResource getControlHighlight() {
					return res(WinzyPalette.LINE);
				}

				@Override
				public ColorUIResource getControlDarkShadow() {
					return res(WinzyPalette.LINE);
				}

				@Override
				public ColorUIResource getControlShadow() {
					return res(WinzyPalette.LINE_SOFT);
				}

				@Override
				public ColorUIResource getControlInfo() {
					return res(WinzyPalette.TEXT);
				}

				@Override
				public ColorUIResource getMenuBackground() {
					return res(WinzyPalette.PANEL);
				}

				@Override
				public ColorUIResource getMenuForeground() {
					return res(WinzyPalette.TEXT);
				}

				@Override
				public ColorUIResource getMenuSelectedBackground() {
					return res(WinzyPalette.accent());
				}

				@Override
				public ColorUIResource getMenuSelectedForeground() {
					return res(WinzyPalette.ON_ACCENT);
				}

				@Override
				public ColorUIResource getWindowBackground() {
					return res(WinzyPalette.BG);
				}

				@Override
				public ColorUIResource getUserTextColor() {
					return res(WinzyPalette.TEXT);
				}

				@Override
				public ColorUIResource getSystemTextColor() {
					return res(WinzyPalette.TEXT);
				}

				@Override
				public ColorUIResource getControlTextColor() {
					return res(WinzyPalette.TEXT);
				}

				@Override
				public ColorUIResource getInactiveControlTextColor() {
					return res(WinzyPalette.TEXT_FAINT);
				}

				@Override
				public ColorUIResource getInactiveSystemTextColor() {
					return res(WinzyPalette.TEXT_FAINT);
				}

				@Override
				public ColorUIResource getTextHighlightColor() {
					return res(WinzyPalette.accentWash(90));
				}

				@Override
				public ColorUIResource getHighlightedTextColor() {
					return res(WinzyPalette.TEXT);
				}

				@Override
				public FontUIResource getControlTextFont() {
					return new FontUIResource(uiFont);
				}

				@Override
				public FontUIResource getSystemTextFont() {
					return new FontUIResource(uiFont);
				}

				@Override
				public FontUIResource getUserTextFont() {
					return new FontUIResource(uiFont);
				}

				@Override
				public FontUIResource getMenuTextFont() {
					return new FontUIResource(uiFont);
				}

				@Override
				public FontUIResource getWindowTitleFont() {
					return new FontUIResource(uiFont.deriveFont(Font.BOLD));
				}

				@Override
				public FontUIResource getSubTextFont() {
					return new FontUIResource(uiFont.deriveFont(11f));
				}
			});
			UIManager.setLookAndFeel(new MetalLookAndFeel());
		} catch (Exception ignored) {
			// If Metal cannot be installed the explicit keys below still give a
			// mostly-correct dark UI on whatever look and feel is active.
		}

		applyKeys();

		// Installing a look and feel replaces its defaults table, so the localised
		// dialog and file-chooser strings have to be written back after it.
		com.jaranalyzer.LanguageManager.applySwingStrings();
	}

	private static ColorUIResource res(Color c) {
		return new ColorUIResource(c);
	}

	// ---- fonts -------------------------------------------------------------

	private static void resolveFonts() {
		Set<String> available = new HashSet<>();
		try {
			for (String n : GraphicsEnvironment.getLocalGraphicsEnvironment()
					.getAvailableFontFamilyNames()) {
				available.add(n);
			}
		} catch (Throwable ignored) {
			// Headless or a broken font config; the fallbacks below still work.
		}

		uiFont = pick(available, 13f, Font.SANS_SERIF,
				"Segoe UI Variable Text", "Segoe UI", "Inter", "Noto Sans", "Dialog");
		monoFont = pick(available, 12.5f, Font.MONOSPACED,
				"Cascadia Mono", "Cascadia Code", "JetBrains Mono", "Consolas",
				"DejaVu Sans Mono", "Monospaced");
	}

	private static Font pick(Set<String> available, float size, String fallback, String... names) {
		for (String n : names) {
			if (available.contains(n)) return new Font(n, Font.PLAIN, 13).deriveFont(size);
		}
		return new Font(fallback, Font.PLAIN, 13).deriveFont(size);
	}

	// ---- explicit component keys -------------------------------------------

	private static void applyKeys() {
		Color bg = WinzyPalette.BG;
		Color panel = WinzyPalette.PANEL;
		Color panelHi = WinzyPalette.PANEL_HI;
		Color inset = WinzyPalette.INSET;
		Color line = WinzyPalette.LINE;
		Color text = WinzyPalette.TEXT;
		Color dim = WinzyPalette.TEXT_DIM;
		Color accent = WinzyPalette.accent();
		Color onAccent = WinzyPalette.ON_ACCENT;

		put("Panel.background", panel);
		put("Viewport.background", panel);
		put("RootPane.background", bg);
		put("OptionPane.background", panel);
		put("OptionPane.messageForeground", text);
		put("Label.foreground", text);
		put("Label.background", panel);

		put("Button.background", panelHi);
		put("Button.foreground", text);
		put("Button.select", accent);
		put("Button.focus", new Color(0, 0, 0, 0));
		UIManager.put("Button.border", BorderFactory.createEmptyBorder(6, 14, 6, 14));

		put("ToggleButton.background", panelHi);
		put("ToggleButton.foreground", text);
		put("ToggleButton.select", accent);

		put("TextField.background", inset);
		put("TextField.foreground", text);
		put("TextField.caretForeground", accent);
		put("TextField.inactiveForeground", WinzyPalette.TEXT_FAINT);
		put("TextField.selectionBackground", WinzyPalette.over(WinzyPalette.accentWash(110), inset));
		put("TextField.selectionForeground", text);
		UIManager.put("TextField.border", BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(line), BorderFactory.createEmptyBorder(4, 8, 4, 8)));

		put("TextArea.background", inset);
		put("TextArea.foreground", text);
		put("TextArea.caretForeground", accent);
		put("TextArea.selectionBackground", WinzyPalette.over(WinzyPalette.accentWash(110), inset));
		put("TextArea.selectionForeground", text);

		put("EditorPane.background", inset);
		put("EditorPane.foreground", text);
		put("TextPane.background", inset);
		put("TextPane.foreground", text);

		put("CheckBox.background", panel);
		put("CheckBox.foreground", text);
		put("RadioButton.background", panel);
		put("RadioButton.foreground", text);

		put("ComboBox.background", panelHi);
		put("ComboBox.foreground", text);
		put("ComboBox.selectionBackground", accent);
		put("ComboBox.selectionForeground", onAccent);
		put("ComboBox.buttonBackground", panelHi);

		put("Spinner.background", panelHi);
		put("Spinner.foreground", text);
		put("FormattedTextField.background", inset);
		put("FormattedTextField.foreground", text);

		put("ProgressBar.background", inset);
		put("ProgressBar.foreground", accent);
		put("ProgressBar.selectionBackground", text);
		put("ProgressBar.selectionForeground", onAccent);
		UIManager.put("ProgressBar.border", BorderFactory.createEmptyBorder());

		put("ScrollPane.background", panel);
		put("ScrollBar.background", panel);
		put("ScrollBar.track", WinzyPalette.PANEL);
		put("ScrollBar.thumb", WinzyPalette.LINE);
		put("ScrollBar.thumbShadow", WinzyPalette.LINE);
		put("ScrollBar.thumbHighlight", WinzyPalette.LINE);
		UIManager.put("ScrollBar.width", 12);

		put("TabbedPane.background", panel);
		put("TabbedPane.foreground", dim);
		put("TabbedPane.selectedForeground", text);
		put("TabbedPane.contentAreaColor", panel);
		put("TabbedPane.selected", panelHi);
		put("TabbedPane.tabAreaBackground", panel);
		put("TabbedPane.focus", accent);
		put("TabbedPane.darkShadow", line);
		put("TabbedPane.light", line);
		put("TabbedPane.highlight", line);
		put("TabbedPane.borderHightlightColor", line);
		UIManager.put("TabbedPane.tabInsets", new java.awt.Insets(7, 16, 7, 16));
		UIManager.put("TabbedPane.selectedTabPadInsets", new java.awt.Insets(2, 2, 2, 1));
		UIManager.put("TabbedPane.tabAreaInsets", new java.awt.Insets(2, 6, 0, 6));

		put("Table.background", panel);
		put("Table.foreground", text);
		put("Table.selectionBackground", WinzyPalette.over(WinzyPalette.accentWash(60), panel));
		put("Table.selectionForeground", text);
		put("Table.gridColor", WinzyPalette.LINE_SOFT);
		put("Table.focusCellHighlightBorder", line);
		UIManager.put("Table.rowHeight", 30);

		put("TableHeader.background", WinzyPalette.PANEL_HI);
		put("TableHeader.foreground", dim);
		UIManager.put("TableHeader.cellBorder",
				BorderFactory.createCompoundBorder(
						BorderFactory.createMatteBorder(0, 0, 1, 0, line),
						BorderFactory.createEmptyBorder(6, 10, 6, 10)));

		put("List.background", panel);
		put("List.foreground", text);
		put("List.selectionBackground", WinzyPalette.over(WinzyPalette.accentWash(60), panel));
		put("List.selectionForeground", text);

		put("Tree.background", panel);
		put("Tree.foreground", text);
		put("Tree.textBackground", panel);
		put("Tree.textForeground", text);
		put("Tree.selectionBackground", WinzyPalette.over(WinzyPalette.accentWash(60), panel));
		put("Tree.selectionForeground", text);
		put("Tree.line", line);
		put("Tree.hash", WinzyPalette.LINE_SOFT);

		put("SplitPane.background", bg);
		put("SplitPane.darkShadow", bg);
		put("SplitPane.shadow", bg);
		put("SplitPane.highlight", bg);
		UIManager.put("SplitPaneDivider.border", BorderFactory.createEmptyBorder());
		UIManager.put("SplitPane.dividerSize", 6);

		put("MenuBar.background", panel);
		put("MenuBar.foreground", text);
		put("Menu.background", panel);
		put("Menu.foreground", text);
		put("Menu.selectionBackground", accent);
		put("Menu.selectionForeground", onAccent);
		put("MenuItem.background", panel);
		put("MenuItem.foreground", text);
		put("MenuItem.selectionBackground", accent);
		put("MenuItem.selectionForeground", onAccent);
		put("MenuItem.acceleratorForeground", WinzyPalette.TEXT_FAINT);
		put("PopupMenu.background", panelHi);
		put("PopupMenu.foreground", text);
		put("CheckBoxMenuItem.background", panel);
		put("CheckBoxMenuItem.foreground", text);
		put("CheckBoxMenuItem.selectionBackground", accent);
		put("CheckBoxMenuItem.selectionForeground", onAccent);

		put("ToolBar.background", panel);
		put("ToolBar.foreground", text);
		put("Separator.foreground", line);
		put("Separator.background", panel);

		put("ToolTip.background", panelHi);
		put("ToolTip.foreground", text);
		UIManager.put("ToolTip.border", BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(line), BorderFactory.createEmptyBorder(4, 8, 4, 8)));

		put("TitledBorder.titleColor", dim);
		put("FileChooser.background", panel);
		put("FileChooser.foreground", text);

		// Fonts, applied last so nothing above resets them.
		FontUIResource f = new FontUIResource(uiFont);
		for (String key : new String[] {
				"Button.font", "ToggleButton.font", "Label.font", "Panel.font",
				"CheckBox.font", "RadioButton.font", "ComboBox.font", "Spinner.font",
				"TextField.font", "FormattedTextField.font", "PasswordField.font",
				"TabbedPane.font", "Table.font", "TableHeader.font", "List.font",
				"Tree.font", "Menu.font", "MenuBar.font", "MenuItem.font",
				"CheckBoxMenuItem.font", "PopupMenu.font", "ToolBar.font",
				"ProgressBar.font", "TitledBorder.font", "ToolTip.font",
				"OptionPane.font", "OptionPane.messageFont", "OptionPane.buttonFont",
				"FileChooser.font", "Viewport.font" }) {
			UIManager.put(key, f);
		}
		FontUIResource m = new FontUIResource(monoFont);
		UIManager.put("TextArea.font", m);
		UIManager.put("EditorPane.font", m);
		UIManager.put("TextPane.font", m);
	}

	private static void put(String key, Color c) {
		UIManager.put(key, new ColorUIResource(c));
	}
}
