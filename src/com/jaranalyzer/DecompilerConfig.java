package com.jaranalyzer;

public class DecompilerConfig {
	private boolean unicodeOutputEnabled = true;
	private boolean showSyntheticMembers = false;
	private boolean forceExplicitImports = false;
	private boolean flattenSwitchBlocks = false;
	private boolean excludeNestedTypes = false;
	private boolean forceExplicitTypeArguments = false;
	private boolean retainRedundantCasts = false;
	private boolean includeErrorDiagnostics = false;

	public boolean isUnicodeOutputEnabled() {
		return unicodeOutputEnabled;
	}

	public void setUnicodeOutputEnabled(boolean unicodeOutputEnabled) {
		this.unicodeOutputEnabled = unicodeOutputEnabled;
	}

	public boolean getShowSyntheticMembers() {
		return showSyntheticMembers;
	}

	public void setShowSyntheticMembers(boolean showSyntheticMembers) {
		this.showSyntheticMembers = showSyntheticMembers;
	}

	public boolean getForceExplicitImports() {
		return forceExplicitImports;
	}

	public void setForceExplicitImports(boolean forceExplicitImports) {
		this.forceExplicitImports = forceExplicitImports;
	}

	public boolean getFlattenSwitchBlocks() {
		return flattenSwitchBlocks;
	}

	public void setFlattenSwitchBlocks(boolean flattenSwitchBlocks) {
		this.flattenSwitchBlocks = flattenSwitchBlocks;
	}

	public boolean getExcludeNestedTypes() {
		return excludeNestedTypes;
	}

	public void setExcludeNestedTypes(boolean excludeNestedTypes) {
		this.excludeNestedTypes = excludeNestedTypes;
	}

	public boolean getForceExplicitTypeArguments() {
		return forceExplicitTypeArguments;
	}

	public void setForceExplicitTypeArguments(boolean forceExplicitTypeArguments) {
		this.forceExplicitTypeArguments = forceExplicitTypeArguments;
	}

	public boolean getRetainRedundantCasts() {
		return retainRedundantCasts;
	}

	public void setRetainRedundantCasts(boolean retainRedundantCasts) {
		this.retainRedundantCasts = retainRedundantCasts;
	}

	public boolean getIncludeErrorDiagnostics() {
		return includeErrorDiagnostics;
	}

	public void setIncludeErrorDiagnostics(boolean includeErrorDiagnostics) {
		this.includeErrorDiagnostics = includeErrorDiagnostics;
	}
}
