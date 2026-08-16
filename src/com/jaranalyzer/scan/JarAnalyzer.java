package com.jaranalyzer.scan;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.jaranalyzer.DecompilerConfig;

/**
 * The per-JAR pipeline.
 *
 * <p>Order matters here. The raw ZIP directory is read first, because if the
 * archive is encrypted or malformed the standard {@code JarFile} API will throw
 * and the reason would otherwise be lost. The blacklist is applied to the parsed
 * constant pool rather than to raw class bytes, so an obfuscator's computed
 * strings are still covered and unrelated bytecode does not produce false hits.
 */
public final class JarAnalyzer {

	private final ScanSettings settings;
	private final Blacklist blacklist;

	public JarAnalyzer(ScanSettings settings, Blacklist blacklist) {
		this.settings = settings;
		this.blacklist = blacklist;
		this.blacklist.compile();
	}

	// =====================================================================

	public JarAnalysis analyze(File file) {
		return analyze(file, 0);
	}

	private JarAnalysis analyze(File file, int depth) {
		long started = System.nanoTime();
		JarAnalysis a = new JarAnalysis(file);

		long deadline = started + settings.perJarTimeoutSeconds * 1_000_000_000L;

		try {
			// 1. The file name is evidence in its own right.
			scanText(a, file.getName(), BlacklistEntry.ScanSurface.PATH,
					Finding.Source.FILE_NAME, "(file name)", false);

			// 2. Raw ZIP directory, before any API that might refuse to open it.
			ArchiveInspector.Report zip = ArchiveInspector.inspect(file);
			ArchiveInspector.contribute(zip, a);

			// A Java archive wearing another extension is not a mistake anyone
			// makes by accident, and it is the single cheapest way to hide one
			// from a scanner that only reads file names.
			if (zip.readable && !ArchiveSniffer.hasArchiveExtension(file.getName())) {
				a.add(Finding.heuristic(
						Msg.t("wjf.h.disguised"),
						Severity.CRITICAL, Finding.Source.FILE_NAME, Msg.t("wjf.cat.structure"),
						file.getName(),
						Msg.t("wjf.h.disguised.why")));
			}

			// The recycle bin keeps the file intact under a $R… name. Something
			// deleted is not automatically innocent — it is often the opposite.
			if (isInRecycleBin(file)) {
				a.add(Finding.heuristic(
						Msg.t("wjf.h.recycled"),
						Severity.MEDIUM, Finding.Source.FILE_NAME, Msg.t("wjf.cat.structure"),
						file.getParent(),
						Msg.t("wjf.h.recycled.why")));
			}

			if (!zip.isZip && !zip.readable) {
				a.setDecompileOutcome(DecompileOutcome.NOT_AN_ARCHIVE);
				a.setDecompileError(zip.failure);
				a.note(Msg.t("wjf.h.note.notZip", zip.failure));
				finish(a, started);
				return a;
			}

			if (settings.computeHashes) {
				a.setSha256(sha256(file));
			}

			// 3. Everything that needs the archive open.
			try (JarFile jf = new JarFile(file, false)) {
				// The detection pass: every class read once, constant pool scanned,
				// obfuscation signals accumulated. This is the whole search — no
				// decompilation is involved and none is needed, because every name
				// and literal a blacklist term can match is already in the pool.
				ObfuscationAnalyzer.Result obf = new ObfuscationAnalyzer.Result();
				inventory(jf, a, depth, deadline, obf);

				if (settings.detectObfuscation) {
					ObfuscationAnalyzer.detectMarkers(a.getManifestText(), obf);
					obf.finish();
					ObfuscationAnalyzer.contribute(obf, a);
				}

				if (a.getDecompileOutcome() == DecompileOutcome.NOT_ATTEMPTED) {
					a.setDecompileOutcome(a.getClassCount() == 0
							? DecompileOutcome.NO_CLASSES : DecompileOutcome.POOL_SCANNED);
				}

				// 4. Source reconstruction, for the archives a human will actually
				// open. Under FLAGGED only the ones with a finding or a reason to
				// distrust them are rebuilt, so a full sweep does not spend minutes
				// per game jar reconstructing code nobody reads.
				if (shouldDecompile(a)) {
					DecompilerConfig cfg = new DecompilerConfig();
					cfg.setShowSyntheticMembers(true);
					DecompileEngine.run(jf, a, settings.toDecompileOptions(deadline), cfg,
							flaggedClasses(a));

					// Source can surface a term the pool cannot: an obfuscator that
					// splits a literal across concatenations leaves the fragments in
					// the pool but the whole string only in reconstructed code.
					scanText(a, a.getDecompiledText(), BlacklistEntry.ScanSurface.CODE,
							Finding.Source.DECOMPILED, null, true);
				}
			} catch (java.util.zip.ZipException ze) {
				// JarFile refuses encrypted and structurally broken archives. The raw
				// inspector above already recorded why, so this only sets the outcome.
				a.setDecompileOutcome(zip.hasEncryptedEntries
						? DecompileOutcome.ENCRYPTED : DecompileOutcome.UNREADABLE);
				a.setDecompileError(ze.getMessage());
				a.note(Msg.t("wjf.h.note.openFail", String.valueOf(ze.getMessage())));
			} catch (SecurityException se) {
				a.setDecompileOutcome(DecompileOutcome.UNREADABLE);
				a.setDecompileError("signature verification failed: " + se.getMessage());
				a.add(Finding.heuristic(Msg.t("wjf.h.sig"), Severity.MEDIUM,
						Finding.Source.ARCHIVE, Msg.t("wjf.cat.structure"),
						"", String.valueOf(se.getMessage())));
			}
		} catch (Throwable t) {
			a.setDecompileOutcome(DecompileOutcome.UNREADABLE);
			a.setDecompileError(t.getClass().getSimpleName() + ": " + t.getMessage());
			a.note(Msg.t("wjf.h.note.aborted", String.valueOf(t)));
		}

		finish(a, started);
		return a;
	}

	private void finish(JarAnalysis a, long started) {
		a.setVerdict(Verdict.decide(a));

		// The "cannot read it, so treat it as suspicious" rule is a user setting;
		// when it is off, an opaque-but-clean archive drops back to a note.
		if (!settings.opaqueMeansSuspicious && a.getVerdict() == Verdict.SUSPICIOUS) {
			a.setVerdict(Verdict.NOTABLE);
		}

		if (!settings.keepTextForCleanJars && !a.getVerdict().needsAttention()) {
			// A full-disk sweep keeps every result object alive for the results
			// table, so anything not needed to display a clean row has to go. The
			// entry list matters as much as the source: a few thousand archives
			// holding a few thousand path strings each is hundreds of megabytes on
			// its own, and none of it is ever shown for a JAR nobody will open.
			a.setDecompiledText("");
			a.getPerClassSource().clear();
			a.getEntryNames().clear();
			a.setManifestText("");
		}

		a.setAnalysisMillis((System.nanoTime() - started) / 1_000_000L);
	}

	// =====================================================================
	//  Archive walk
	// =====================================================================

	/**
	 * The class entries that actually produced a finding.
	 *
	 * <p>Decompiling is for a person to read, and what they want to read is the
	 * code that matched — not the other thousands of classes shipped next to it.
	 * Reconstructing a whole large flagged JAR to show a handful of matched classes
	 * is most of the analysis cost for no added value.
	 *
	 * <p>An empty result means "no class in particular" — an archive flagged for
	 * being obfuscated or broken rather than for a match — and the engine falls
	 * back to its usual bounded sweep there.
	 */
	private static java.util.Set<String> flaggedClasses(JarAnalysis a) {
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		for (Finding f : a.getFindings()) {
			String loc = f.getLocation();
			if (loc == null || !loc.endsWith(".class")) continue;
			out.add(loc);
		}
		return out;
	}

	private boolean shouldDecompile(JarAnalysis a) {
		switch (settings.decompileMode) {
			case OFF:
				return false;
			case ALL:
				return a.getClassCount() > 0;
			case FLAGGED:
			default:
				if (a.getClassCount() == 0) return false;
				if (a.isObfuscated() || a.isEncrypted() || a.isStructurallyBroken()) return true;

				// Only a finding worth reading source over. The MEDIUM tier is the
				// context tier — "Step", "ProcessBuilder", "Cipher.getInstance" —
				// and vanilla Minecraft alone carries three of them. Treating any
				// finding as a trigger made the common case decompile everything,
				// which is the cost this mode exists to avoid.
				for (Finding f : a.getFindings()) {
					if (f.getSeverity().weight() >= Severity.HIGH.weight()) return true;
				}
				return false;
		}
	}

	private void inventory(JarFile jf, JarAnalysis a, int depth, long deadline,
			ObfuscationAnalyzer.Result obf) {
		int entries = 0;
		int classes = 0;
		int resources = 0;
		int nested = 0;
		int natives = 0;

		boolean truncated = false;

		Enumeration<JarEntry> en = jf.entries();
		while (en.hasMoreElements()) {
			if (System.nanoTime() > deadline) {
				a.note(Msg.t("wjf.h.note.deadline"));
				truncated = true;
				break;
			}

			JarEntry e = en.nextElement();
			if (e.isDirectory()) continue;

			entries++;
			String name = e.getName();
			String lower = name.toLowerCase(Locale.ROOT);

			// Enough to show what is in the archive without holding the full listing
			// of a 20 000-entry game jar in memory for the whole sweep.
			if (a.getEntryNames().size() < 4_000) {
				a.getEntryNames().add(name);
			}

			// Bundled third-party code is skipped on every surface, not just during
			// decompilation. Scanning it anyway is what turns a runtime library into
			// a "finding": the JDK genuinely contains defineClass and ProcessBuilder,
			// and reporting that tells the operator nothing about the mod under test.
			boolean library = settings.skipLibraryPackages && isLibraryEntry(name);

			// Entry paths are a scan surface of their own: a cheat's package
			// structure survives even when every identifier inside is mangled.
			if (!library) {
				scanText(a, name, BlacklistEntry.ScanSurface.PATH,
						lower.endsWith(".class") ? Finding.Source.CLASS_NAME : Finding.Source.ENTRY_PATH,
						name, false);
			}

			if (lower.endsWith(".class")) {
				classes++;
				if (!library) {
					scanClass(jf, e, a, obf);
				}
				continue;
			}

			resources++;

			if (lower.equals("meta-inf/manifest.mf")) {
				readManifest(jf, e, a);
				continue;
			}

			if (lower.endsWith(".jar") || lower.endsWith(".war")) {
				nested++;
				handleNestedJar(jf, e, a, depth);
				continue;
			}

			if (lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib")) {
				natives++;
				a.add(Finding.heuristic(Msg.t("wjf.h.native"), Severity.HIGH,
						Finding.Source.NATIVE_LIB, Msg.t("wjf.cat.native"), name,
						Msg.t("wjf.h.native.why")));
				continue;
			}

			handleResource(jf, e, a, name, lower);
		}

		a.setEntryCount(entries);
		a.setClassCount(classes);
		a.setResourceCount(resources);
		a.setNestedJarCount(nested);
		a.setNativeLibCount(natives);

		// Silence here would be a lie. This is the one truncation that actually
		// costs coverage: the constant-pool scan runs inside this loop, so entries
		// past the deadline were never searched at all. (A decompile limit is
		// different — detection had already covered those classes, so that one
		// only earns a log note.)
		if (truncated) {
			a.add(Finding.heuristic(
					Msg.t("wjf.h.incomplete"),
					Severity.MEDIUM, Finding.Source.DECOMPILE_FAIL, Msg.t("wjf.cat.decompile"),
					Msg.t("wjf.h.classes", classes, a.getEntryCount()),
					Msg.t("wjf.h.incomplete.why")));
		}
	}

	/**
	 * Whether an entry belongs to a bundled third-party package.
	 *
	 * <p>Shaded jars and module-style archives put classes under a wrapper
	 * directory ({@code classes/}, {@code BOOT-INF/classes/}, {@code WEB-INF/classes/}),
	 * so the package prefix is matched after stripping those.
	 */
	private static boolean isLibraryEntry(String name) {
		String n = name;
		for (String prefix : new String[] { "classes/", "BOOT-INF/classes/", "WEB-INF/classes/" }) {
			if (n.startsWith(prefix)) {
				n = n.substring(prefix.length());
				break;
			}
		}
		return DecompileEngine.isLibraryPackage(n);
	}

	// ---- resources --------------------------------------------------------

	private static final String[] TEXT_EXTENSIONS = {
			".json", ".txt", ".cfg", ".conf", ".properties", ".yml", ".yaml",
			".toml", ".xml", ".md", ".js", ".lua", ".py", ".sh", ".bat", ".ps1",
	};

	private void handleResource(JarFile jf, JarEntry e, JarAnalysis a, String name, String lower) {
		boolean isText = false;
		for (String ext : TEXT_EXTENSIONS) {
			if (lower.endsWith(ext)) { isText = true; break; }
		}

		boolean isScript = lower.endsWith(".bat") || lower.endsWith(".ps1")
				|| lower.endsWith(".sh") || lower.endsWith(".exe") || lower.endsWith(".vbs");
		if (isScript) {
			a.add(Finding.heuristic(Msg.t("wjf.h.script"), Severity.HIGH,
					Finding.Source.RESOURCE, Msg.t("wjf.cat.structure"), name,
					Msg.t("wjf.h.script.why")));
		}

		// Only read entries small enough to be worth it; a 200 MB asset blob is
		// not where a keyword hides.
		long size = e.getSize();
		if (size > 4 * 1024 * 1024) return;

		byte[] data = read(jf, e, 4 * 1024 * 1024);
		if (data == null || data.length == 0) return;

		if (isText) {
			String text = new String(data, StandardCharsets.UTF_8);
			scanText(a, text, BlacklistEntry.ScanSurface.STRING,
					Finding.Source.RESOURCE, name, true);
			detectModMetadata(a, name, lower, text);
			return;
		}

		// A high-entropy blob with no recognisable header is either compressed
		// (already normal inside a JAR) or encrypted (not normal at all).
		if (data.length >= 2048 && !looksLikeKnownFormat(data) && !isSignatureBlock(lower)) {
			double h = ObfuscationAnalyzer.shannon(data, data.length);
			if (h >= settings.encryptedEntropyThreshold) {
				// This does NOT set the encrypted flag. That flag means the archive's
				// own entries are password protected — an unambiguous condition
				// ArchiveInspector reads out of the ZIP directory. A high-entropy
				// blob is only one plausible reading of some bytes; letting it drive
				// the verdict would mark every signed JAR suspicious.
				a.add(Finding.heuristic(Msg.t("wjf.h.entropy"), Severity.MEDIUM,
						Finding.Source.ENCRYPTION, Msg.t("wjf.cat.encryption"), name,
						// "%s boyunca %.2f bit/bayt": human size first (%s), entropy
					// second (%.2f) — the order the format string expects.
					Msg.t("wjf.h.entropy.why", JarAnalysis.humanSize(data.length), h)));
			}
		}
	}

	/**
	 * JAR signing artefacts. A PKCS#7 signature block is dense binary by
	 * construction, so it always reads as maximum entropy — and every signed
	 * artifact on a developer machine has one, which made "is this archive
	 * encrypted" true for most of Maven Central.
	 */
	private static boolean isSignatureBlock(String lowerName) {
		if (!lowerName.startsWith("meta-inf/")) return false;
		return lowerName.endsWith(".rsa") || lowerName.endsWith(".dsa")
				|| lowerName.endsWith(".ec") || lowerName.endsWith(".sf");
	}

	/** Recognises headers of formats that are legitimately high-entropy. */
	private static boolean looksLikeKnownFormat(byte[] d) {
		if (d.length < 4) return false;
		int b0 = d[0] & 0xFF, b1 = d[1] & 0xFF, b2 = d[2] & 0xFF, b3 = d[3] & 0xFF;
		if (b0 == 0x89 && b1 == 'P' && b2 == 'N' && b3 == 'G') return true;          // PNG
		if (b0 == 0xFF && b1 == 0xD8) return true;                                    // JPEG
		if (b0 == 'G' && b1 == 'I' && b2 == 'F') return true;                         // GIF
		if (b0 == 'O' && b1 == 'g' && b2 == 'g') return true;                         // OGG
		if (b0 == 'R' && b1 == 'I' && b2 == 'F' && b3 == 'F') return true;            // WAV/WEBP
		if (b0 == 'P' && b1 == 'K') return true;                                      // nested zip
		if (b0 == 0x1F && b1 == 0x8B) return true;                                    // gzip
		if (b0 == 'f' && b1 == 'L' && b2 == 'a' && b3 == 'C') return true;            // FLAC
		if (b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) return true;        // TTF
		if (b0 == 'w' && b1 == 'O' && b2 == 'F' && b3 == 'F') return true;            // WOFF
		return false;
	}

	private void detectModMetadata(JarAnalysis a, String name, String lower, String text) {
		if (lower.equals("fabric.mod.json")) {
			a.setModLoader("Fabric");
		} else if (lower.equals("mcmod.info") || lower.equals("meta-inf/mods.toml")
				|| lower.equals("mods.toml")) {
			a.setModLoader("Forge");
		} else if (lower.equals("plugin.yml") || lower.equals("bungee.yml")) {
			a.setModLoader("Bukkit/Spigot");
		}
	}

	// ---- manifest ---------------------------------------------------------

	private void readManifest(JarFile jf, JarEntry e, JarAnalysis a) {
		byte[] data = read(jf, e, 512 * 1024);
		if (data == null) return;

		String text = new String(data, StandardCharsets.UTF_8);
		a.setHasManifest(true);
		a.setManifestText(text);

		scanText(a, text, BlacklistEntry.ScanSurface.STRING,
				Finding.Source.MANIFEST, "META-INF/MANIFEST.MF", true);

		a.setMainClass(manifestValue(text, "Main-Class"));
		a.setPremainClass(manifestValue(text, "Premain-Class"));
		a.setAgentClass(manifestValue(text, "Agent-Class"));
		a.setTweakClass(manifestValue(text, "TweakClass"));

		// A Java agent rewrites other classes as they load. In a mod JAR that is
		// the single most direct way to modify a game client at runtime.
		if (a.getPremainClass() != null || a.getAgentClass() != null) {
			a.add(Finding.heuristic(Msg.t("wjf.h.agent"), Severity.HIGH,
					Finding.Source.MANIFEST, Msg.t("wjf.cat.injection"),
					a.getPremainClass() != null ? "Premain-Class" : "Agent-Class",
					Msg.t("wjf.h.agent.why")));
		}
		if (a.getTweakClass() != null) {
			a.add(Finding.heuristic(Msg.t("wjf.h.tweak"), Severity.MEDIUM,
					Finding.Source.MANIFEST, Msg.t("wjf.cat.injection"),
					"TweakClass: " + a.getTweakClass(),
					Msg.t("wjf.h.tweak.why")));
		}
		if (text.contains("FMLCorePlugin")) {
			a.add(Finding.heuristic(Msg.t("wjf.h.coremod"), Severity.MEDIUM,
					Finding.Source.MANIFEST, Msg.t("wjf.cat.injection"), "FMLCorePlugin",
					Msg.t("wjf.h.coremod.why")));
		}
	}

	private static String manifestValue(String manifest, String key) {
		for (String raw : manifest.split("\\r?\\n")) {
			String line = raw.trim();
			if (line.regionMatches(true, 0, key + ":", 0, key.length() + 1)) {
				String v = line.substring(key.length() + 1).trim();
				return v.isEmpty() ? null : v;
			}
		}
		return null;
	}

	// ---- nested archives ---------------------------------------------------

	private void handleNestedJar(JarFile jf, JarEntry e, JarAnalysis a, int depth) {
		String name = e.getName();

		if (depth >= settings.maxNestedDepth) {
			a.add(Finding.heuristic(Msg.t("wjf.h.nestedSkip"), Severity.LOW,
					Finding.Source.NESTED_JAR, Msg.t("wjf.cat.structure"), name,
					Msg.t("wjf.h.nestedSkip.why")));
			return;
		}

		File temp = null;
		try {
			byte[] data = read(jf, e, 128 * 1024 * 1024);
			if (data == null || data.length == 0) return;

			temp = File.createTempFile("wjf-nested-", ".jar");
			Files.write(temp.toPath(), data);

			JarAnalysis inner = analyze(temp, depth + 1);

			// The nested archive's findings belong to the outer JAR, relabelled so
			// the operator can see they came from inside.
			for (Finding f : inner.getFindings()) {
				Finding relabelled = new Finding(
						f.getTitle() + "  (in nested " + name + ")",
						f.getSeverity(), Finding.Source.NESTED_JAR, f.getCategory(),
						f.getPattern(), name + " > " + f.getLocation(), f.getEvidence());
				relabelled.addHits(f.getHits() - 1);
				a.add(relabelled);
			}

			if (inner.isObfuscated()) a.setObfuscated(true);
			if (inner.isEncrypted()) a.setEncrypted(true);

			if (inner.getVerdict().needsAttention()) {
				a.note(Msg.t("wjf.h.note.nested", name, inner.getVerdict().display()));
			}
		} catch (Throwable t) {
			a.add(Finding.heuristic(Msg.t("wjf.h.nestedFail"), Severity.MEDIUM,
					Finding.Source.NESTED_JAR, Msg.t("wjf.cat.structure"),
					name, String.valueOf(t.getMessage())));
		} finally {
			if (temp != null && !temp.delete()) {
				temp.deleteOnExit();
			}
		}
	}

	// ---- constant pool -----------------------------------------------------

	/**
	 * The detection pass for one class: parse its constant pool, search the
	 * strings, and feed the name statistics to the obfuscation analyser.
	 *
	 * <p>Reading the pool rather than the whole file matters twice over. It is far
	 * cheaper — the pool is a fraction of a class file and needs no bytecode
	 * decoding — and it is more precise: the previous approach reinterpreted the
	 * entire class as ISO-8859-1 text and searched that, which happily matched
	 * blacklist terms against runs of raw bytecode that never spelled anything.
	 */
	private void scanClass(JarFile jf, JarEntry e, JarAnalysis a, ObfuscationAnalyzer.Result obf) {
		long size = e.getSize();
		if (size > 4 * 1024 * 1024) return;

		byte[] data = read(jf, e, 4 * 1024 * 1024);
		if (data == null || data.length < 10) return;

		ClassScanner.ClassInfo info = ClassScanner.read(data);
		obf.accumulate(info);

		if (info.constants.isEmpty()) return;

		String joined = ClassScanner.joinConstants(info, 1_000_000);
		scanText(a, joined, BlacklistEntry.ScanSurface.STRING,
				Finding.Source.CONSTANT, e.getName(), true);

		// Constants are joined with newline separators (in joinConstants) on
		// purpose: a gapless join to catch names split across constants
		// ("Kill".concat("Aura")) instead produces heavy false positives, because
		// the compound cheat names are ordinary English fragments that legitimate
		// code keeps as separate constants and pool order butts together at random
		// (Cooldown+Bypass, Key+Logger, Name+Protect). Runtime string-splitting is
		// a genuine limit of any static text scan; the obfuscation heuristic still
		// catches the XOR-decode variant, and the running-JVM scan catches it once
		// it loads.
	}

	// ---- blacklist plumbing -------------------------------------------------

	/**
	 * Runs the blacklist over one body of text and files the hits.
	 *
	 * @param lineOriented true for real source (excerpt the line), false for raw
	 *                     bytes (excerpt the surrounding printable run)
	 */
	private void scanText(final JarAnalysis a, CharSequence text,
			BlacklistEntry.ScanSurface surface, final Finding.Source source,
			final String location, final boolean lineOriented) {

		if (text == null || text.length() == 0) return;

		final CharSequence body = text;
		blacklist.scan(body, surface, new Blacklist.HitSink() {
			private int reported;

			@Override
			public boolean onHit(BlacklistEntry entry, String matched, int start, int end) {
				String excerpt = Blacklist.excerpt(body, start, end, lineOriented, 220);

				// Game data is not logic, and it has to be checked on both sides:
				// the excerpt catches an asset path quoted inside code, while the
				// location catches a hit *inside* an asset file. Vanilla Minecraft
				// trips both — forcefield.png (the world-border texture) reads as an
				// aimbot module, and the stock splashes.txt contains the literal
				// text "Phobos anomaly!", which reads as a cheat client. A cheat is
				// caught by its code, not by the name of its icon or its splash text.
				if (isAssetPath(excerpt) || isAssetPath(location)) return true;

				a.add(new Finding(
						entry.getPattern() + (entry.getDescription().isEmpty()
								? "" : " — " + entry.getDescription()),
						entry.getSeverity(), source, entry.getCategory(),
						entry.getPattern(), location, excerpt));

				// One pathological archive should not be allowed to produce tens of
				// thousands of findings; folding handles repeats of the same term,
				// this caps the number of *distinct* ones per surface.
				return ++reported < 400;
			}
		});
	}

	/**
	 * Whether the file sits in a recycle bin.
	 *
	 * <p>Windows keeps the contents under a {@code $R…} name in
	 * {@code $Recycle.Bin\<user SID>\}, so a deleted JAR is still fully readable
	 * and still worth analysing.
	 */
	private static boolean isInRecycleBin(File f) {
		String p = f.getAbsolutePath().toLowerCase(Locale.ROOT);
		return p.contains("$recycle.bin") || p.contains("recycler");
	}

	/** Asset file extensions — a match inside one of these paths is game data. */
	private static final String[] ASSET_EXTENSIONS = {
			".png", ".jpg", ".jpeg", ".gif", ".bmp", ".tga", ".webp",
			".ogg", ".wav", ".mp3", ".flac",
			".ttf", ".otf", ".woff", ".woff2",
			".vsh", ".fsh", ".glsl", ".shader", ".mcmeta", ".nbt", ".obj", ".mtl",
	};

	/**
	 * Whether a matched snippet is a reference to an asset file rather than code.
	 *
	 * <p>Kept narrow on purpose: it needs an asset-ish path <em>and</em> an asset
	 * file extension, so a class genuinely named {@code ForcefieldModule} in a
	 * package called {@code assets} is still reported.
	 */
	private static boolean isAssetPath(String snippet) {
		if (snippet == null || snippet.isEmpty()) return false;
		String s = snippet.trim().toLowerCase(Locale.ROOT);

		// "assets/minecraft/" is the reserved vanilla resource namespace, so
		// anything under it is the game's own content whatever its extension.
		// Without this, the stock splash-text file reports the unmodified game as
		// carrying the Phobos cheat client, because one of the vanilla splashes
		// is literally "Phobos anomaly!". A mod's own namespace is still scanned.
		if (s.startsWith("assets/minecraft/") || s.contains("/assets/minecraft/")) {
			return true;
		}

		// Localisation files are UI text, not logic. A mod's own menu labels live
		// here — Lunar's German lang file lists "X-RAY" for its settings toggle —
		// and a real feature's label would also appear in the mod's classes, so the
		// lang entry adds only false positives. Restricted to data extensions so a
		// class hidden at a lang/ path is still scanned as code.
		if ((s.startsWith("lang/") || s.contains("/lang/"))
				&& (s.contains(".json") || s.contains(".lang"))) {
			return true;
		}

		boolean assetish = s.startsWith("assets/") || s.contains("/assets/")
				|| s.contains("textures/") || s.contains("sounds/")
				|| s.contains("models/") || s.contains("shaders/")
				|| s.contains("/lang/") || s.contains("font/");
		if (!assetish) return false;

		for (String ext : ASSET_EXTENSIONS) {
			if (s.endsWith(ext)) return true;
			// The excerpt may carry trailing framing from the constant pool.
			int i = s.indexOf(ext);
			if (i > 0 && i + ext.length() + 3 >= s.length()) return true;
		}
		return false;
	}

	// ---- io ----------------------------------------------------------------

	private static byte[] read(JarFile jf, JarEntry e, int limit) {
		try (InputStream in = jf.getInputStream(e)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream(
					(int) Math.max(1024, Math.min(e.getSize() > 0 ? e.getSize() : 8192, limit)));
			byte[] buf = new byte[16384];
			int n;
			int total = 0;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				total += n;
				if (total >= limit) break;
			}
			return out.toByteArray();
		} catch (Throwable t) {
			return null;
		}
	}

	private static String sha256(File f) {
		try (InputStream in = Files.newInputStream(f.toPath())) {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
			StringBuilder sb = new StringBuilder(64);
			for (byte b : md.digest()) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (Exception e) {
			return "";
		}
	}
}
