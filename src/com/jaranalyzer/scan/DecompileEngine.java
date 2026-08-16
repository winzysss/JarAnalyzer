package com.jaranalyzer.scan;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import com.jaranalyzer.CfrDecompiler;
import com.jaranalyzer.DecompilerConfig;

/**
 * Reconstructs Java source for a JAR.
 *
 * <p>This runs to give a human something to read, not to find keywords — the
 * constant-pool scan in {@link ClassScanner} already covers detection at a
 * fraction of the cost. It is therefore reached only for archives that produced
 * a finding or resisted analysis, unless the operator asks for more.
 *
 * <p>Two tiers. CFR produces real source; anything CFR refuses is disassembled
 * with ASM instead, because "the decompiler gave up here" is itself worth showing
 * and a bytecode listing still carries every name and literal.
 */
public final class DecompileEngine {

	private DecompileEngine() {
	}

	public static class Options {
		/** Hard cap on classes decompiled per JAR. 0 = unlimited. */
		public int maxClasses = 4000;
		/**
		 * Cap when no particular class was matched — an archive distrusted for being
		 * obfuscated or broken rather than for containing a term.
		 *
		 * <p>Much lower than {@link #maxClasses} because the output here is a sample
		 * for a person to glance at, not evidence. Decompiled source produces no
		 * verdict the constant-pool scan has not already made — a decompiler builds
		 * its source out of that same pool — so reconstructing these archives in
		 * full is cost without benefit, and the Decompile tab rebuilds a JAR on
		 * demand anyway when somebody opens it.
		 */
		public int maxClassesUnfocused = 200;
		/** Stop once the combined text reaches this many characters. 0 = unlimited. */
		public int maxTextChars = 12_000_000;
		/** Wall-clock deadline for this JAR, as {@code System.nanoTime()}. 0 = none. */
		public long deadlineNanos;
		/** Skip well-known third-party packages that are never the mod under test. */
		public boolean skipKnownLibraryPackages = true;
		/** Fall back to an ASM bytecode listing when CFR produces nothing. */
		public boolean bytecodeFallback = true;
		/** Classes larger than this are skipped outright. */
		public int maxClassBytes = 8 * 1024 * 1024;
		/**
		 * Classes handed to CFR per driver invocation.
		 *
		 * <p>A batch is the deadline's granularity — the clock is only checked
		 * between batches, so an oversized batch overshoots the time budget by
		 * however long it takes. 60 keeps the shared-type saving of one CFR driver
		 * per batch without the overshoot getting coarse.
		 */
		public int batchSize = 60;
	}

	/**
	 * Package roots that are shipped dependencies rather than the mod under test.
	 * Skipping them is a large speed win and costs nothing for detection: the
	 * constant-pool pass already read them.
	 */
	private static final String[] LIBRARY_PACKAGES = {
			"java/", "javax/", "jdk/", "sun/", "com/sun/", "sunw/",
			"org/apache/", "org/slf4j/", "org/eclipse/", "org/gradle/", "org/junit/",
			"org/hamcrest/", "org/mockito/", "org/objectweb/asm/", "org/ow2/",
			"org/bouncycastle/", "org/jetbrains/", "org/intellij/", "com/intellij/",
			"com/google/gson/", "com/google/common/", "com/google/protobuf/",
			"com/fasterxml/", "kotlin/", "kotlinx/", "scala/", "groovy/",
			"io/netty/", "it/unimi/dsi/", "org/joml/", "org/lwjgl/",
			"com/mojang/", "net/minecraft/", "org/spongepowered/",
	};

	public static boolean isLibraryPackage(String entryName) {
		for (String p : LIBRARY_PACKAGES) {
			if (entryName.startsWith(p)) return true;
		}
		return false;
	}

	// =====================================================================

	/** Decompiles the JAR into {@code analysis}. */
	public static void run(JarFile jf, JarAnalysis analysis, Options opt, DecompilerConfig cfg) {
		run(jf, analysis, opt, cfg, java.util.Collections.emptySet());
	}

	/**
	 * @param wanted class entries the scan already matched. When non-empty only
	 *        these are reconstructed — the rest of the archive is code nobody
	 *        asked to read, and on a large flagged JAR it is minutes of work. When
	 *        empty (an archive distrusted for its structure rather than for a
	 *        match) every class is a candidate, as before.
	 */
	public static void run(JarFile jf, JarAnalysis analysis, Options opt, DecompilerConfig cfg,
			java.util.Set<String> wanted) {
		List<JarEntry> targets = new ArrayList<>();
		int skipped = 0;
		boolean focused = wanted != null && !wanted.isEmpty();

		Enumeration<JarEntry> en = jf.entries();
		while (en.hasMoreElements()) {
			JarEntry e = en.nextElement();
			if (e.isDirectory() || !e.getName().endsWith(".class")) continue;

			if (focused) {
				// The size cap still applies: a matched class can itself be the
				// pathological one, and a 10 MB class is what stalls the decompiler.
				if (!wanted.contains(e.getName())) {
					skipped++;
					continue;
				}
				if (e.getSize() > opt.maxClassBytes) {
					skipped++;
					continue;
				}
				targets.add(e);
				continue;
			}

			if (opt.skipKnownLibraryPackages && isLibraryPackage(e.getName())) {
				skipped++;
				continue;
			}
			if (e.getSize() > opt.maxClassBytes) {
				skipped++;
				continue;
			}
			targets.add(e);
		}

		if (targets.isEmpty()) {
			analysis.setClassesSkipped(skipped);
			analysis.setDecompileOutcome(skipped > 0
					? DecompileOutcome.POOL_SCANNED : DecompileOutcome.NO_CLASSES);
			return;
		}

		int cap = focused ? opt.maxClasses : Math.min(opt.maxClasses, opt.maxClassesUnfocused);
		if (cap > 0 && targets.size() > cap) {
			targets = targets.subList(0, cap);
		}

		StringBuilder all = new StringBuilder(1 << 20);
		int decompiled = 0;
		int viaBytecode = 0;
		int failed = 0;
		int processed = 0;
		boolean hitLimit = false;

		// CFR is driven in batches: one driver per class makes it rebuild its type
		// system every time, and one driver for thousands of classes gives no way
		// to honour the deadline partway through.
		for (int start = 0; start < targets.size(); start += opt.batchSize) {
			if (Thread.currentThread().isInterrupted()
					|| (opt.deadlineNanos != 0 && System.nanoTime() > opt.deadlineNanos)
					|| (opt.maxTextChars > 0 && all.length() >= opt.maxTextChars)) {
				hitLimit = true;
				break;
			}

			int end = Math.min(start + opt.batchSize, targets.size());
			List<JarEntry> batch = targets.subList(start, end);

			List<String> internalNames = new ArrayList<>(batch.size());
			for (JarEntry e : batch) {
				String n = e.getName();
				internalNames.add(n.substring(0, n.length() - ".class".length()));
			}

			String source = null;
			try {
				source = CfrDecompiler.decompileBatchFromJar(jf, internalNames, cfg);
			} catch (Throwable t) {
				source = null;
			}

			processed += batch.size();

			if (source != null && !source.trim().isEmpty()) {
				decompiled += batch.size();
				all.append(source).append('\n');
				continue;
			}

			// The batch produced nothing. Fall back per class so one poisonous
			// class does not cost the readable source of the rest of the batch.
			for (JarEntry e : batch) {
				// The per-class fallback is the slowest path there is, so it checks
				// the clock too rather than only between batches.
				if (opt.deadlineNanos != 0 && System.nanoTime() > opt.deadlineNanos) {
					hitLimit = true;
					break;
				}
				String one = null;
				try {
					one = CfrDecompiler.decompileFromJar(jf, e.getName(), cfg);
				} catch (Throwable t) {
					one = null;
				}
				boolean fromSource = one != null && !one.trim().isEmpty();

				if (!fromSource && opt.bytecodeFallback) {
					one = disassemble(jf, e);
					if (one != null) viaBytecode++;
				}

				if (one == null || one.trim().isEmpty()) {
					failed++;
					continue;
				}
				decompiled++;
				all.append("\n// ===== ")
						.append(e.getName().replace('/', '.'))
						.append(fromSource ? "  [source]" : "  [bytecode]")
						.append(" =====\n")
						.append(one).append('\n');
			}
		}

		analysis.setClassesDecompiled(decompiled);
		analysis.setClassesFailed(failed);
		analysis.setClassesSkipped(skipped);
		analysis.setDecompiledText(all.toString());

		if (decompiled == 0) {
			analysis.setDecompileOutcome(DecompileOutcome.FAILED);
		} else if (viaBytecode == 0 && failed == 0 && !hitLimit) {
			analysis.setDecompileOutcome(DecompileOutcome.FULL_SOURCE);
		} else if (viaBytecode >= decompiled) {
			analysis.setDecompileOutcome(DecompileOutcome.BYTECODE_ONLY);
		} else {
			analysis.setDecompileOutcome(DecompileOutcome.PARTIAL_SOURCE);
		}

		if (hitLimit) {
			analysis.note(Msg.t("wjf.h.note.limit", targets.size() - processed));
		}

		contributeFindings(analysis, viaBytecode, failed, decompiled + failed);
	}

	// ---- ASM fallback ------------------------------------------------------

	private static String disassemble(JarFile jf, JarEntry entry) {
		try (java.io.InputStream in = jf.getInputStream(entry)) {
			ClassReader cr = new ClassReader(in);
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			cr.accept(new TraceClassVisitor(null, new Textifier(), pw), ClassReader.SKIP_FRAMES);
			pw.flush();
			String out = sw.toString();
			return out.trim().isEmpty() ? null : out;
		} catch (Throwable t) {
			return null;
		}
	}

	// ---- findings ----------------------------------------------------------

	private static void contributeFindings(JarAnalysis a, int viaBytecode, int failed, int attempted) {
		if (attempted == 0) return;

		if (failed > 0) {
			double ratio = (double) failed / attempted;
			a.add(Finding.heuristic(
					Msg.t("wjf.h.render"),
					ratio > 0.5 ? Severity.HIGH : Severity.MEDIUM,
					Finding.Source.DECOMPILE_FAIL, Msg.t("wjf.cat.decompile"),
					Msg.t("wjf.h.classes", failed, attempted),
					Msg.t("wjf.h.render.why")));
		}

		if (viaBytecode > 0) {
			double ratio = (double) viaBytecode / attempted;
			if (ratio > 0.6) {
				a.add(Finding.heuristic(
						Msg.t("wjf.h.defeated"),
						Severity.HIGH, Finding.Source.DECOMPILE_FAIL, Msg.t("wjf.cat.decompile"),
						Msg.t("wjf.h.classes", viaBytecode, attempted),
						Msg.t("wjf.h.defeated.why")));
			} else if (ratio > 0.15) {
				a.add(Finding.heuristic(
						Msg.t("wjf.h.partial"),
						Severity.MEDIUM, Finding.Source.DECOMPILE_FAIL, Msg.t("wjf.cat.decompile"),
						Msg.t("wjf.h.classes", viaBytecode, attempted),
						Msg.t("wjf.h.partial.why")));
			}
		}
	}
}
