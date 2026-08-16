package com.jaranalyzer.engine;

import java.util.ArrayList;
import java.util.List;

public class ObfuscationDetails {
	public final String type;
	public final double score;
	public final boolean isObfuscated;
	public final boolean hasEncryptedStrings;
	public final boolean hasShortNames;
	public final int totalClassesChecked;
	public final int shortNameClasses;
	public final int shortNameMethods;
	public final int totalMethods;
	public final int encryptedStringPatterns;
	public final int charArrayPatterns;
	public final int stringBuilderPatterns;
	public final int syntheticMethods;
	public final int bridgeMethods;
	public final int gotoPatterns;
	public final int nopPatterns;
	public final int athrowPatterns;
	public final List<String> sampleClasses;

	public ObfuscationDetails(String type, double score, boolean isObfuscated, boolean hasEncryptedStrings,
			boolean hasShortNames, int totalClassesChecked, int shortNameClasses, int shortNameMethods,
			int totalMethods, int encryptedStringPatterns, int charArrayPatterns, int stringBuilderPatterns,
			int syntheticMethods, int bridgeMethods, int gotoPatterns, int nopPatterns, int athrowPatterns,
			List<String> sampleClasses) {
		this.type = type;
		this.score = score;
		this.isObfuscated = isObfuscated;
		this.hasEncryptedStrings = hasEncryptedStrings;
		this.hasShortNames = hasShortNames;
		this.totalClassesChecked = totalClassesChecked;
		this.shortNameClasses = shortNameClasses;
		this.shortNameMethods = shortNameMethods;
		this.totalMethods = totalMethods;
		this.encryptedStringPatterns = encryptedStringPatterns;
		this.charArrayPatterns = charArrayPatterns;
		this.stringBuilderPatterns = stringBuilderPatterns;
		this.syntheticMethods = syntheticMethods;
		this.bridgeMethods = bridgeMethods;
		this.gotoPatterns = gotoPatterns;
		this.nopPatterns = nopPatterns;
		this.athrowPatterns = athrowPatterns;
		this.sampleClasses = sampleClasses != null ? sampleClasses : new ArrayList<>();
	}

	public com.jaranalyzer.ObfuscationDetector.DetectionResult toDetectionResult() {
		com.jaranalyzer.ObfuscationDetector.ObfuscatorType ot = com.jaranalyzer.ObfuscationDetector.ObfuscatorType.GENERIC;
		for (com.jaranalyzer.ObfuscationDetector.ObfuscatorType t : com.jaranalyzer.ObfuscationDetector.ObfuscatorType.values()) {
			if (t.getDisplayName().equals(type)) {
				ot = t;
				break;
			}
		}
		return new com.jaranalyzer.ObfuscationDetector.DetectionResult(ot, isObfuscated, hasEncryptedStrings, hasShortNames, score);
	}
}
