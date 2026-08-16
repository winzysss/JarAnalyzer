package com.jaranalyzer.scan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a JAR has been deliberately made hard to read.
 *
 * <p>Every signal here comes out of the constant pool, so the whole judgement
 * costs a fraction of a second even on a 2500-class archive. That matters beyond
 * speed: the ratios are computed over every class in the archive rather than a
 * sample, so an obfuscator that leaves the first few hundred classes alone and
 * mangles the rest is not mistaken for clean.
 *
 * <p>Obfuscation is not itself proof of anything — ProGuard-minified libraries
 * are everywhere — but under the tool's central rule an archive whose contents
 * cannot be read honestly is reported as suspicious, so the threshold has to be
 * defensible. It therefore requires more than one independent signal instead of
 * tripping on short class names alone.
 */
public final class ObfuscationAnalyzer {

	private ObfuscationAnalyzer() {
	}

	/**
	 * Characters that render as nothing, used to make distinct names look
	 * identical. Written as numeric code points on purpose — as literal glyphs
	 * they would be invisible in this file too, and any editor or transfer that
	 * normalises whitespace would silently delete the detector.
	 */
	private static final char[] INVISIBLE = {
			0x200B, // zero width space
			0x200C, // zero width non-joiner
			0x200D, // zero width joiner
			0x2060, // word joiner
			0xFEFF, // zero width no-break space / BOM
			0x00AD, // soft hyphen
			0x180E, // mongolian vowel separator
			0x034F, // combining grapheme joiner
	};

	/** Marker strings left behind by commercial and public obfuscators. */
	private static final String[][] OBFUSCATOR_MARKERS = {
			{ "Allatori", "Allatori" },
			{ "zelix", "Zelix KlassMaster" },
			{ "Stringer", "Stringer" },
			{ "Skidfuscator", "Skidfuscator" },
			{ "Paramorphism", "Paramorphism" },
			{ "Branchlock", "Branchlock" },
			{ "Bozar", "Bozar" },
			{ "qProtect", "qProtect" },
			{ "Binscure", "Binscure" },
			{ "Sandmark", "Sandmark" },
			{ "yGuard", "yGuard" },
			{ "DashO", "DashO" },
			{ "Prestige", "Prestige" },
			{ "Caesium", "Caesium" },
			{ "proguard", "ProGuard" },
	};

	// =====================================================================

	/** Accumulates signals class by class; call {@link #finish()} when done. */
	public static final class Result {
		public int classesExamined;
		public int classesUnparseable;
		public int shortClassNames;
		public int totalMembers;
		public int shortMemberNames;
		public int barcodeNames;
		public int invisibleCharNames;
		public int nonAsciiNames;
		public int stringDecryptDescriptors;
		public int syntheticMembers;
		public int classesWithoutSource;

		public double stringEntropy;
		public double score;
		public boolean obfuscated;
		public String guess = "";
		public final List<String> sampleNames = new ArrayList<>();
		public final Set<String> markers = new LinkedHashSet<>();

		private final StringBuilder literals = new StringBuilder(8192);

		// ---- accumulation ------------------------------------------------

		public void accumulate(ClassScanner.ClassInfo info) {
			if (!info.parsed && info.constants.isEmpty()) {
				classesUnparseable++;
				return;
			}
			if (!info.parsed) classesUnparseable++;

			classesExamined++;

			String simple = info.simpleName;
			if (!simple.isEmpty()) {
				if (simple.length() <= 2) {
					shortClassNames++;
					if (sampleNames.size() < 12) sampleNames.add(info.internalName);
				}
				if (isBarcodeName(simple)) {
					barcodeNames++;
					if (sampleNames.size() < 12) sampleNames.add(info.internalName);
				}
				if (hasInvisible(info.internalName)) invisibleCharNames++;
				else if (hasNonAscii(info.internalName)) nonAsciiNames++;
			}

			if (!info.hasSourceFile) classesWithoutSource++;

			totalMembers += info.memberCount;
			syntheticMembers += info.syntheticMembers;

			for (String name : info.memberNames) {
				boolean ctor = "<init>".equals(name) || "<clinit>".equals(name);
				if (!ctor && name.length() <= 2) shortMemberNames++;
				if (hasInvisible(name)) invisibleCharNames++;
			}

			stringDecryptDescriptors += info.stringFactoryCalls;

			for (String c : info.constants) {
				// Only sample things that look like human text, so the entropy
				// figure reflects literals rather than descriptors and paths.
				if (literals.length() < 120_000 && c.length() >= 4
						&& c.indexOf('(') < 0 && c.indexOf('/') < 0 && c.indexOf(';') < 0) {
					literals.append(c).append('\n');
				}
			}

			detectMarkers(info.constants, this);
		}

		public void finish() {
			stringEntropy = shannon(literals.toString());
			score(this);
			literals.setLength(0);
		}
	}

	// ---- name shape --------------------------------------------------------

	/** Names built only from glyphs that are hard to tell apart in a monospace font. */
	private static boolean isBarcodeName(String simple) {
		if (simple.length() < 4) return false;
		for (int i = 0; i < simple.length(); i++) {
			char c = simple.charAt(i);
			if (c != 'I' && c != 'l' && c != '1' && c != 'i'
					&& c != 'O' && c != '0' && c != 'o') {
				return false;
			}
		}
		return true;
	}

	private static boolean hasInvisible(String s) {
		for (char bad : INVISIBLE) {
			if (s.indexOf(bad) >= 0) return true;
		}
		return false;
	}

	private static boolean hasNonAscii(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) > 0x7E) return true;
		}
		return false;
	}

	// ---- scoring -----------------------------------------------------------

	private static void score(Result r) {
		if (r.classesExamined == 0) return;

		double classShort = ratio(r.shortClassNames, r.classesExamined);
		double memberShort = ratio(r.shortMemberNames, r.totalMembers);
		double decryptPerClass = (double) r.stringDecryptDescriptors / r.classesExamined;
		double syntheticRatio = ratio(r.syntheticMembers, r.totalMembers);
		double noSourceRatio = ratio(r.classesWithoutSource, r.classesExamined);

		double s = 0;
		s += classShort * 0.32;
		s += memberShort * 0.28;
		s += Math.min(decryptPerClass / 2.0, 1.0) * 0.20;
		s += Math.min(syntheticRatio / 0.35, 1.0) * 0.05;
		s += noSourceRatio * 0.05;
		if (r.barcodeNames > 0) s += 0.10;
		if (r.invisibleCharNames > 0) s += 0.20;
		if (r.nonAsciiNames > r.classesExamined * 0.1) s += 0.10;
		if (r.stringEntropy > 4.8) s += 0.05;

		r.score = Math.min(1.0, s);

		// Requiring two independent signals is what keeps an ordinary
		// ProGuard-minified library out of the SUSPICIOUS bucket.
		int strong = 0;
		if (classShort > 0.45) strong++;
		if (memberShort > 0.45) strong++;
		if (decryptPerClass > 1.0) strong++;
		if (r.barcodeNames > 2) strong++;
		if (r.invisibleCharNames > 0) strong++;

		r.obfuscated = r.score >= 0.45 || strong >= 2 || r.invisibleCharNames > 0;
		r.guess = guessTool(r, classShort, decryptPerClass);
	}

	private static String guessTool(Result r, double classShort, double decrypt) {
		if (!r.markers.isEmpty()) return r.markers.iterator().next();
		if (!r.obfuscated) return "";
		if (r.invisibleCharNames > 0) return Msg.t("wjf.obf.kind.unicode");
		if (r.barcodeNames > 2) return Msg.t("wjf.obf.kind.barcode");
		if (decrypt > 1.5 && r.stringEntropy > 4.5) return Msg.t("wjf.obf.kind.strenc");
		if (classShort > 0.6 && decrypt < 0.3) return Msg.t("wjf.obf.kind.proguard");
		return Msg.t("wjf.obf.kind.generic");
	}

	private static double ratio(int part, int whole) {
		return whole <= 0 ? 0 : (double) part / whole;
	}

	// ---- entropy -----------------------------------------------------------

	/** Shannon entropy in bits per character; encrypted blobs sit high. */
	public static double shannon(String s) {
		if (s == null || s.isEmpty()) return 0;
		int[] freq = new int[256];
		int n = 0;
		for (int i = 0; i < s.length(); i++) {
			freq[s.charAt(i) & 0xFF]++;
			n++;
		}
		double h = 0;
		for (int f : freq) {
			if (f == 0) continue;
			double p = (double) f / n;
			h -= p * (Math.log(p) / Math.log(2));
		}
		return h;
	}

	/** Shannon entropy of a byte array, used for resource blobs. */
	public static double shannon(byte[] data, int len) {
		if (data == null || len <= 0) return 0;
		int[] freq = new int[256];
		for (int i = 0; i < len; i++) freq[data[i] & 0xFF]++;
		double h = 0;
		for (int f : freq) {
			if (f == 0) continue;
			double p = (double) f / len;
			h -= p * (Math.log(p) / Math.log(2));
		}
		return h;
	}

	// ---- markers -----------------------------------------------------------

	public static void detectMarkers(Iterable<String> constants, Result r) {
		if (constants == null) return;
		for (String c : constants) {
			if (c == null || c.length() < 4 || c.length() > 200) continue;
			String lower = c.toLowerCase(Locale.ROOT);
			for (String[] m : OBFUSCATOR_MARKERS) {
				if (lower.contains(m[0].toLowerCase(Locale.ROOT))) r.markers.add(m[1]);
			}
		}
	}

	public static void detectMarkers(CharSequence text, Result r) {
		if (text == null) return;
		String lower = text.toString().toLowerCase(Locale.ROOT);
		for (String[] m : OBFUSCATOR_MARKERS) {
			if (lower.contains(m[0].toLowerCase(Locale.ROOT))) r.markers.add(m[1]);
		}
	}

	// =====================================================================

	public static void contribute(Result r, JarAnalysis a) {
		a.setObfuscationScore(r.score);
		a.setObfuscatorGuess(r.guess);

		// Classes whose constant pool will not parse are a stronger signal than a
		// decompiler failing: decompilers give up on plenty of valid code, but the
		// pool is a fixed, simple structure, so malformed-but-loadable bytecode is
		// a deliberate anti-analysis choice rather than a tooling limitation.
		if (r.classesUnparseable > 0) {
			int total = r.classesExamined + r.classesUnparseable;
			double frac = total == 0 ? 0 : (double) r.classesUnparseable / total;
			a.add(Finding.heuristic(
					Msg.t("wjf.h.badclass"),
					frac > 0.25 ? Severity.HIGH : Severity.MEDIUM,
					Finding.Source.DECOMPILE_FAIL, Msg.t("wjf.cat.structure"),
					Msg.t("wjf.h.classes", r.classesUnparseable, total),
					Msg.t("wjf.h.badclass.why")));
		}

		if (!r.obfuscated) return;
		a.setObfuscated(true);

		// The score alone means nothing to a reader, so the reasons that produced
		// it are spelled out next to it.
		StringBuilder why = new StringBuilder();
		// Order matters: the pattern is "%d sınıf üzerinden skor %.2f", i.e. the
		// class count (an int) first and the score (a double) second. Passing them
		// reversed made String.format throw on "%d" against a double, and Msg.t
		// then fell back to printing the raw "%d … %.2f" pattern — which is exactly
		// what the report showed.
		why.append(Msg.t("wjf.h.obf.score", r.classesExamined, r.score));
		if (r.shortClassNames > 0) {
			why.append("; ").append(Msg.t("wjf.h.obf.shortNames", r.shortClassNames));
		}
		if (r.stringDecryptDescriptors > 0) {
			why.append("; ").append(Msg.t("wjf.h.obf.decrypt", r.stringDecryptDescriptors));
		}
		if (r.barcodeNames > 0) {
			why.append("; ").append(Msg.t("wjf.h.obf.barcode", r.barcodeNames));
		}

		a.add(Finding.heuristic(
				r.guess.isEmpty() ? Msg.t("wjf.h.obf") : Msg.t("wjf.h.obf.named", r.guess),
				Severity.HIGH, Finding.Source.OBFUSCATION, Msg.t("wjf.cat.obfuscation"),
				r.sampleNames.isEmpty() ? "" : r.sampleNames.get(0),
				why.toString()));

		if (r.invisibleCharNames > 0) {
			a.add(Finding.heuristic(
					Msg.t("wjf.h.invisible"),
					Severity.CRITICAL, Finding.Source.OBFUSCATION, Msg.t("wjf.cat.obfuscation"),
					Msg.t("wjf.h.invisible.loc", r.invisibleCharNames),
					Msg.t("wjf.h.invisible.why")));
		}

		if (r.stringDecryptDescriptors > r.classesExamined * 1.5) {
			a.add(Finding.heuristic(
					Msg.t("wjf.h.strenc"),
					Severity.HIGH, Finding.Source.OBFUSCATION, Msg.t("wjf.cat.obfuscation"),
					Msg.t("wjf.h.strenc.loc", r.stringDecryptDescriptors),
					Msg.t("wjf.h.strenc.why")));
		}

		for (String marker : r.markers) {
			a.add(Finding.heuristic(
					Msg.t("wjf.h.marker", marker),
					Severity.MEDIUM, Finding.Source.OBFUSCATION, Msg.t("wjf.cat.obfuscator"),
					marker, Msg.t("wjf.h.marker.why", marker)));
		}
	}
}
