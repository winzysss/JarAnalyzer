package com.jaranalyzer;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.prefs.Preferences;

public class ConfigSaver {

	private static final String FLATTEN_SWITCH_BLOCKS_ID = "flattenSwitchBlocks";
	private static final String FORCE_EXPLICIT_IMPORTS_ID = "forceExplicitImports";
	private static final String SHOW_SYNTHETIC_MEMBERS_ID = "showSyntheticMembers";
	private static final String EXCLUDE_NESTED_TYPES_ID = "excludeNestedTypes";
	private static final String FORCE_EXPLICIT_TYPE_ARGUMENTS_ID = "forceExplicitTypeArguments";
	private static final String RETAIN_REDUNDANT_CASTS_ID = "retainRedundantCasts";
	private static final String INCLUDE_ERROR_DIAGNOSTICS_ID = "includeErrorDiagnostics";
	private static final String UNICODE_REPLACE_ENABLED_ID = "unicodeReplaceEnabled";

	private static final String MAIN_WINDOW_ID_PREFIX = "main";
	private static final String FIND_WINDOW_ID_PREFIX = "find";
	private static final String WINDOW_IS_FULL_SCREEN_ID = "WindowIsFullScreen";
	private static final String WINDOW_WIDTH_ID = "WindowWidth";
	private static final String WINDOW_HEIGHT_ID = "WindowHeight";
	private static final String WINDOW_X_ID = "WindowX";
	private static final String WINDOW_Y_ID = "WindowY";

	private DecompilerConfig decompilerConfig;
	private WindowPosition mainWindowPosition;
	private WindowPosition findWindowPosition;
	private AppPreferences AppPreferences;

	private static ConfigSaver theLoadedInstance;

	private ConfigSaver() {
	}

	public static ConfigSaver getLoadedInstance() {
		if (theLoadedInstance == null) {
			synchronized (ConfigSaver.class) {
				if (theLoadedInstance == null) {
					theLoadedInstance = new ConfigSaver();
					theLoadedInstance.loadConfig();
				}
			}
		}
		return theLoadedInstance;
	}

	private void loadConfig() {
		decompilerConfig = new DecompilerConfig();
		AppPreferences = new AppPreferences();
		mainWindowPosition = new WindowPosition();
		findWindowPosition = new WindowPosition();
		try {
			Preferences prefs = Preferences.userNodeForPackage(ConfigSaver.class);

			decompilerConfig.setFlattenSwitchBlocks(
					prefs.getBoolean(FLATTEN_SWITCH_BLOCKS_ID, decompilerConfig.getFlattenSwitchBlocks()));
			decompilerConfig.setForceExplicitImports(
					prefs.getBoolean(FORCE_EXPLICIT_IMPORTS_ID, decompilerConfig.getForceExplicitImports()));
			decompilerConfig.setShowSyntheticMembers(
					prefs.getBoolean(SHOW_SYNTHETIC_MEMBERS_ID, decompilerConfig.getShowSyntheticMembers()));
			decompilerConfig.setExcludeNestedTypes(
					prefs.getBoolean(EXCLUDE_NESTED_TYPES_ID, decompilerConfig.getExcludeNestedTypes()));
			decompilerConfig.setForceExplicitTypeArguments(prefs.getBoolean(FORCE_EXPLICIT_TYPE_ARGUMENTS_ID,
					decompilerConfig.getForceExplicitTypeArguments()));
			decompilerConfig.setRetainRedundantCasts(
					prefs.getBoolean(RETAIN_REDUNDANT_CASTS_ID, decompilerConfig.getRetainRedundantCasts()));
			decompilerConfig.setIncludeErrorDiagnostics(
					prefs.getBoolean(INCLUDE_ERROR_DIAGNOSTICS_ID, decompilerConfig.getIncludeErrorDiagnostics()));
			decompilerConfig.setUnicodeOutputEnabled(prefs.getBoolean(UNICODE_REPLACE_ENABLED_ID, true));

			mainWindowPosition = loadWindowPosition(prefs, MAIN_WINDOW_ID_PREFIX);
			findWindowPosition = loadWindowPosition(prefs, FIND_WINDOW_ID_PREFIX);
			AppPreferences = loadAppPreferences(prefs);
		} catch (Exception e) {
			JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e);
		}
	}

	private WindowPosition loadWindowPosition(Preferences prefs, String windowIdPrefix) {
		WindowPosition windowPosition = new WindowPosition();
		windowPosition.setFullScreen(prefs.getBoolean(windowIdPrefix + WINDOW_IS_FULL_SCREEN_ID, false));
		windowPosition.setWindowWidth(prefs.getInt(windowIdPrefix + WINDOW_WIDTH_ID, 0));
		windowPosition.setWindowHeight(prefs.getInt(windowIdPrefix + WINDOW_HEIGHT_ID, 0));
		windowPosition.setWindowX(prefs.getInt(windowIdPrefix + WINDOW_X_ID, 0));
		windowPosition.setWindowY(prefs.getInt(windowIdPrefix + WINDOW_Y_ID, 0));
		return windowPosition;
	}

	private AppPreferences loadAppPreferences(Preferences prefs) throws Exception {
		AppPreferences newAppPrefs = new AppPreferences();
		for (Field field : AppPreferences.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()))
				continue;
			field.setAccessible(true);
			String prefId = field.getName();
			Object defaultVal = field.get(newAppPrefs);

			if (field.getType() == String.class) {
				String defaultStr = (String) (defaultVal == null ? "" : defaultVal);
				field.set(newAppPrefs, prefs.get(prefId, defaultStr));

			} else if (field.getType() == Boolean.class || field.getType() == boolean.class) {
				Boolean defaultBool = (Boolean) (defaultVal == null ? Boolean.FALSE : defaultVal);
				field.setBoolean(newAppPrefs, prefs.getBoolean(prefId, defaultBool));

			} else if (field.getType() == Integer.class || field.getType() == int.class) {
				Integer defaultInt = (Integer) (defaultVal == null ? 0 : defaultVal);
				field.setInt(newAppPrefs, prefs.getInt(prefId, defaultInt));

			} else if (field.getType() == Double.class || field.getType() == double.class) {
				// Was missing: a double field fell through every branch and was
				// silently never restored, so the splitter position never survived
				// a restart even though it was being written to the object.
				Double defaultDouble = (Double) (defaultVal == null ? Double.valueOf(0) : defaultVal);
				field.setDouble(newAppPrefs, prefs.getDouble(prefId, defaultDouble));

			} else if (field.getType() == java.util.List.class) {
				String saved = prefs.get(prefId, "");
				java.util.List<String> list = new java.util.ArrayList<>();
				if (saved != null && !saved.isEmpty()) {
					for (String item : saved.split("\n")) {
						String trimmed = item.trim();
						if (!trimmed.isEmpty()) list.add(trimmed);
					}
				}
				field.set(newAppPrefs, list);
			}
		}
		return newAppPrefs;
	}

	public void saveConfig() {
		try {
			Preferences prefs = Preferences.userNodeForPackage(ConfigSaver.class);

			prefs.putBoolean(FLATTEN_SWITCH_BLOCKS_ID, decompilerConfig.getFlattenSwitchBlocks());
			prefs.putBoolean(FORCE_EXPLICIT_IMPORTS_ID, decompilerConfig.getForceExplicitImports());
			prefs.putBoolean(SHOW_SYNTHETIC_MEMBERS_ID, decompilerConfig.getShowSyntheticMembers());
			prefs.putBoolean(EXCLUDE_NESTED_TYPES_ID, decompilerConfig.getExcludeNestedTypes());
			prefs.putBoolean(FORCE_EXPLICIT_TYPE_ARGUMENTS_ID, decompilerConfig.getForceExplicitTypeArguments());
			prefs.putBoolean(RETAIN_REDUNDANT_CASTS_ID, decompilerConfig.getRetainRedundantCasts());
			prefs.putBoolean(INCLUDE_ERROR_DIAGNOSTICS_ID, decompilerConfig.getIncludeErrorDiagnostics());
			prefs.putBoolean(UNICODE_REPLACE_ENABLED_ID, decompilerConfig.isUnicodeOutputEnabled());

			saveWindowPosition(prefs, MAIN_WINDOW_ID_PREFIX, mainWindowPosition);
			saveWindowPosition(prefs, FIND_WINDOW_ID_PREFIX, findWindowPosition);
			saveAppPreferences(prefs);
		} catch (Exception e) {
			JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e);
		}
	}

	private void saveWindowPosition(Preferences prefs, String windowIdPrefix, WindowPosition windowPosition) {
		prefs.putBoolean(windowIdPrefix + WINDOW_IS_FULL_SCREEN_ID, windowPosition.isFullScreen());
		prefs.putInt(windowIdPrefix + WINDOW_WIDTH_ID, windowPosition.getWindowWidth());
		prefs.putInt(windowIdPrefix + WINDOW_HEIGHT_ID, windowPosition.getWindowHeight());
		prefs.putInt(windowIdPrefix + WINDOW_X_ID, windowPosition.getWindowX());
		prefs.putInt(windowIdPrefix + WINDOW_Y_ID, windowPosition.getWindowY());
	}

	private void saveAppPreferences(Preferences prefs) throws Exception {
		for (Field field : AppPreferences.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()))
				continue;
			field.setAccessible(true);
			String prefId = field.getName();
			Object value = field.get(AppPreferences);

			if (field.getType() == String.class) {
				prefs.put(prefId, (String) (value == null ? "" : value));

			} else if (field.getType() == Boolean.class || field.getType() == boolean.class) {
				prefs.putBoolean(prefId, (Boolean) (value == null ? Boolean.FALSE : value));

			} else if (field.getType() == Integer.class || field.getType() == int.class) {
				prefs.putInt(prefId, (Integer) (value == null ? 0 : value));

			} else if (field.getType() == Double.class || field.getType() == double.class) {
				prefs.putDouble(prefId, (Double) (value == null ? Double.valueOf(0) : value));

			} else if (field.getType() == java.util.List.class) {
				java.util.List<?> list = (java.util.List<?>) value;
				StringBuilder sb = new StringBuilder();
				if (list != null) {
					for (Object item : list) {
						if (item != null) {
							sb.append(item.toString()).append("\n");
						}
					}
				}
				prefs.put(prefId, sb.toString().trim());
			}
		}
	}

	public DecompilerConfig getDecompilerConfig() {
		return decompilerConfig;
	}

	public WindowPosition getMainWindowPosition() {
		return mainWindowPosition;
	}

	public WindowPosition getFindWindowPosition() {
		return findWindowPosition;
	}

	public AppPreferences getAppPreferences() {
		return AppPreferences;
	}
}
