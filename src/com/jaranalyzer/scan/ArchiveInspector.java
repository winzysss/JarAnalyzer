package com.jaranalyzer.scan;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a JAR's ZIP central directory by hand, before {@code java.util.zip} ever
 * touches it.
 *
 * <p>This exists because the interesting archives are precisely the ones the
 * normal API refuses to open or silently glosses over. {@code JarFile} throws on
 * a password-protected entry and reports nothing useful about why; it also
 * happily ignores a second copy of a class hidden behind a duplicate entry name,
 * a trick used to make a decompiler and the JVM disagree about what the code is.
 * Parsing the directory directly turns all of that into evidence rather than an
 * exception.
 */
public final class ArchiveInspector {

	private static final int SIG_EOCD = 0x06054b50;
	private static final int SIG_EOCD64 = 0x06064b50;
	private static final int SIG_EOCD64_LOC = 0x07064b50;
	private static final int SIG_CENTRAL = 0x02014b50;
	private static final int SIG_LOCAL = 0x04034b50;

	/** ZIP general purpose bit 0: entry is encrypted. */
	private static final int FLAG_ENCRYPTED = 1;
	/** ZIP general purpose bit 6: strong (AES-family) encryption. */
	private static final int FLAG_STRONG_ENCRYPTION = 1 << 6;

	private ArchiveInspector() {
	}

	// =====================================================================

	public static class Entry {
		public String name = "";
		public int flags;
		public int method;
		public long compressedSize;
		public long uncompressedSize;
		public long localHeaderOffset;
		public long crc;

		public boolean isEncrypted() {
			return (flags & FLAG_ENCRYPTED) != 0 || (flags & FLAG_STRONG_ENCRYPTION) != 0;
		}

		public boolean isDirectory() {
			return name.endsWith("/");
		}
	}

	public static class Report {
		public boolean isZip;
		public boolean readable;
		public String failure;

		public int declaredEntryCount;
		public final List<Entry> entries = new ArrayList<>();

		public boolean hasEncryptedEntries;
		public int encryptedEntryCount;
		public boolean zip64;

		/** Bytes sitting after the end-of-central-directory record. */
		public long trailingBytes;
		/** Bytes before the first local header (self-extracting stub, or a prepended payload). */
		public long prefixBytes;

		public final List<String> duplicateNames = new ArrayList<>();
		public final List<String> traversalNames = new ArrayList<>();
		public final List<String> nonAsciiNames = new ArrayList<>();
		public final List<String> exoticMethods = new ArrayList<>();
		public final List<String> anomalies = new ArrayList<>();

		public boolean isStructurallySuspicious() {
			return !duplicateNames.isEmpty() || !traversalNames.isEmpty()
					|| !exoticMethods.isEmpty() || prefixBytes > 0 || trailingBytes > 0
					|| !anomalies.isEmpty();
		}
	}

	// =====================================================================

	public static Report inspect(File file) {
		Report r = new Report();
		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			long len = raf.length();
			if (len < 22) {
				r.failure = Msg.t("wjf.h.fail.tooSmall", len);
				return r;
			}

			// A JAR must start with a local file header. Anything else in front is
			// a stub or a prepended blob, both of which are worth reporting.
			byte[] head = new byte[4];
			raf.seek(0);
			raf.readFully(head);
			int headSig = le32(head, 0);
			r.isZip = headSig == SIG_LOCAL;

			long eocdPos = findEocd(raf, len);
			if (eocdPos < 0) {
				r.failure = Msg.t("wjf.h.fail.noEocd");
				return r;
			}

			byte[] eocd = readAt(raf, eocdPos, 22);
			int totalEntries = le16(eocd, 10);
			long cdSize = le32u(eocd, 12);
			long cdOffset = le32u(eocd, 16);
			int commentLen = le16(eocd, 20);

			r.trailingBytes = len - (eocdPos + 22 + commentLen);
			if (r.trailingBytes < 0) r.trailingBytes = 0;

			// ZIP64 marks the 32-bit fields as saturated and puts the real values
			// in a separate record ahead of the EOCD.
			if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL || totalEntries == 0xFFFF) {
				long[] z64 = readZip64(raf, eocdPos);
				if (z64 != null) {
					r.zip64 = true;
					totalEntries = (int) Math.min(z64[0], Integer.MAX_VALUE);
					cdSize = z64[1];
					cdOffset = z64[2];
				}
			}

			r.declaredEntryCount = totalEntries;

			if (cdOffset < 0 || cdSize < 0 || cdOffset + cdSize > len) {
				r.failure = Msg.t("wjf.h.fail.cdRange");
				r.anomalies.add(Msg.t("wjf.h.anom.cdRange", cdOffset, cdSize, len));
				return r;
			}

			// Everything before the first local header referenced by the directory.
			r.prefixBytes = eocdPos - cdSize - cdOffset;
			if (r.prefixBytes < 0) r.prefixBytes = 0;

			// The directory offset in the EOCD is relative to the start of the
			// archive, not the start of the file, so a prefixed container has to
			// have that prefix added back. Reading at the bare offset used to land
			// in the middle of the prefix: the parse found no central-directory
			// signature, produced zero entries, and still set readable — so a cheat
			// JAR with junk bytes glued in front was reported as an empty archive
			// and none of its classes were ever scanned. Java itself resolves the
			// directory the same way, which is why such a file still runs.
			byte[] cd = readAt(raf, r.prefixBytes + cdOffset,
					(int) Math.min(cdSize, 64L * 1024 * 1024));
			parseCentralDirectory(cd, r);
			r.readable = true;

			classifyAnomalies(r, len);
		} catch (Exception e) {
			r.failure = e.getClass().getSimpleName() + ": " + e.getMessage();
		}
		return r;
	}

	// ---- parsing ----------------------------------------------------------

	private static void parseCentralDirectory(byte[] cd, Report r) {
		Set<String> seen = new HashSet<>();
		int p = 0;
		int guard = 0;

		while (p + 46 <= cd.length && guard++ < 500_000) {
			if (le32(cd, p) != SIG_CENTRAL) break;

			Entry e = new Entry();
			e.flags = le16(cd, p + 8);
			e.method = le16(cd, p + 10);
			e.crc = le32u(cd, p + 16);
			e.compressedSize = le32u(cd, p + 20);
			e.uncompressedSize = le32u(cd, p + 24);
			int nameLen = le16(cd, p + 28);
			int extraLen = le16(cd, p + 30);
			int commentLen = le16(cd, p + 32);
			e.localHeaderOffset = le32u(cd, p + 42);

			int nameStart = p + 46;
			if (nameStart + nameLen > cd.length) break;

			// Bit 11 says the name is UTF-8; otherwise it is CP437, but ISO-8859-1
			// preserves the bytes well enough to spot non-ASCII trickery.
			boolean utf8 = (e.flags & (1 << 11)) != 0;
			e.name = new String(cd, nameStart, nameLen,
					utf8 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);

			r.entries.add(e);

			if (!e.isDirectory() && !seen.add(e.name) && r.duplicateNames.size() < 40) {
				r.duplicateNames.add(e.name);
			}
			if (e.isEncrypted()) {
				r.hasEncryptedEntries = true;
				r.encryptedEntryCount++;
			}

			p = nameStart + nameLen + extraLen + commentLen;
		}
	}

	private static void classifyAnomalies(Report r, long fileLen) {
		for (Entry e : r.entries) {
			String n = e.name;

			if (n.contains("../") || n.contains("..\\") || n.startsWith("/") || n.contains(":\\")) {
				if (r.traversalNames.size() < 20) r.traversalNames.add(n);
			}

			// Only meaningful when the archive says its names are UTF-8. Without
			// that flag the bytes are in whatever code page the machine that
			// zipped it used, and reading them as Latin-1 turns ordinary Turkish
			// letters into control characters — an artefact of the decoding, not
			// evidence about the file.
			if ((e.flags & (1 << 11)) != 0
					&& isDeceptiveName(n) && r.nonAsciiNames.size() < 20) {
				r.nonAsciiNames.add(n);
			}

			// 0 = stored, 8 = deflate. Everything else in a JAR is unusual; 99 is
			// the WinZip AES marker and means the entry is encrypted.
			if (e.method != 0 && e.method != 8 && r.exoticMethods.size() < 20) {
				r.exoticMethods.add(n + " (method " + e.method + ")");
			}

			// Compared against the prefix-adjusted position for the same reason the
			// directory is read there: these offsets are archive-relative, so on a
			// prefixed container every one of them would otherwise look valid while
			// pointing at the wrong place — or, near the end, be flagged as running
			// past EOF when it does not.
			if (r.prefixBytes + e.localHeaderOffset >= fileLen) {
				if (r.anomalies.size() < 20) {
					r.anomalies.add(Msg.t("wjf.h.anom.pastEnd", n));
				}
			}

			// A decompression ratio this extreme is either a zip bomb or a
			// deliberately padded blob.
			if (e.compressedSize > 512 && e.uncompressedSize / Math.max(1, e.compressedSize) > 1200) {
				if (r.anomalies.size() < 20) {
					r.anomalies.add(Msg.t("wjf.h.anom.ratio",
							e.uncompressedSize / Math.max(1, e.compressedSize), n));
				}
			}
		}

		if (r.declaredEntryCount != r.entries.size()) {
			r.anomalies.add(Msg.t("wjf.h.anom.count", r.declaredEntryCount, r.entries.size()));
		}
	}

	/**
	 * Whether an entry name is built to be misread rather than merely non-English.
	 *
	 * <p>"Not ASCII" is the wrong test. This tool's users are Turkish, and an
	 * archive full of {@code Açıklama.txt} and {@code Ayarlar-şablonu.json} is an
	 * ordinary archive; flagging it teaches people to ignore the warning, which
	 * costs more than the check is worth. What actually signals trickery is a name
	 * that renders as something other than what it is:
	 *
	 * <ul>
	 * <li>invisible or direction-flipping characters — a zero-width space inside
	 *     {@code Kill<ZWSP>Aura} defeats a keyword search while looking untouched,
	 *     and a right-to-left override makes {@code exe.dahil} display as
	 *     {@code lihad.exe};
	 * <li>two alphabets in one word — the Cyrillic {@code а} and the Latin
	 *     {@code a} are separate characters that draw identically, so mixing them
	 *     is how a name is made to look like a familiar one without being it.
	 * </ul>
	 *
	 * <p>A name written entirely in one non-Latin script is just that language, and
	 * is not flagged.
	 */
	private static boolean isDeceptiveName(String s) {
		boolean latin = false;
		boolean confusableScript = false;

		for (int i = 0; i < s.length(); ) {
			int cp = s.codePointAt(i);
			i += Character.charCount(cp);

			int type = Character.getType(cp);
			if (type == Character.FORMAT || type == Character.CONTROL
					|| type == Character.SURROGATE || type == Character.UNASSIGNED) {
				return true;
			}
			// Spaces that are not the space bar: used to pad a name into looking
			// like a different one, or to break a term in two.
			if (Character.isSpaceChar(cp) && cp != ' ') return true;

			Character.UnicodeScript script;
			try {
				script = Character.UnicodeScript.of(cp);
			} catch (IllegalArgumentException e) {
				return true;   // not a valid code point at all
			}
			if (script == Character.UnicodeScript.LATIN) latin = true;
			else if (script == Character.UnicodeScript.CYRILLIC
					|| script == Character.UnicodeScript.GREEK) {
				confusableScript = true;
			}
		}

		return latin && confusableScript;
	}

	// ---- low-level helpers -------------------------------------------------

	private static long findEocd(RandomAccessFile raf, long len) throws IOException {
		// The EOCD sits at the end, possibly followed by a comment of up to 64 KB.
		int window = (int) Math.min(len, 66_000L);
		byte[] tail = readAt(raf, len - window, window);
		for (int i = tail.length - 22; i >= 0; i--) {
			if (le32(tail, i) == SIG_EOCD) {
				return len - window + i;
			}
		}
		return -1;
	}

	private static long[] readZip64(RandomAccessFile raf, long eocdPos) {
		try {
			if (eocdPos < 20) return null;
			byte[] loc = readAt(raf, eocdPos - 20, 20);
			if (le32(loc, 0) != SIG_EOCD64_LOC) return null;
			long z64Pos = le64(loc, 8);
			if (z64Pos < 0 || z64Pos + 56 > raf.length()) return null;

			byte[] z = readAt(raf, z64Pos, 56);
			if (le32(z, 0) != SIG_EOCD64) return null;
			return new long[] { le64(z, 32), le64(z, 40), le64(z, 48) };
		} catch (Exception e) {
			return null;
		}
	}

	private static byte[] readAt(RandomAccessFile raf, long pos, int len) throws IOException {
		byte[] buf = new byte[Math.max(0, len)];
		raf.seek(Math.max(0, pos));
		raf.readFully(buf);
		return buf;
	}

	private static int le16(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
	}

	private static int le32(byte[] b, int off) {
		return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
	}

	private static long le32u(byte[] b, int off) {
		return le32(b, off) & 0xFFFFFFFFL;
	}

	private static long le64(byte[] b, int off) {
		return ByteBuffer.wrap(b, off, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
	}

	// =====================================================================

	/**
	 * Turns the raw report into findings. Separated from parsing so the inspector
	 * stays a pure reader and the severity policy lives in one place.
	 */
	public static void contribute(Report r, JarAnalysis a) {
		if (r.hasEncryptedEntries) {
			a.setEncrypted(true);
			a.add(Finding.heuristic(
					Msg.t("wjf.h.encrypted"),
					Severity.CRITICAL, Finding.Source.ENCRYPTION, Msg.t("wjf.cat.encryption"),
					Msg.t("wjf.h.encrypted.loc", r.encryptedEntryCount),
					Msg.t("wjf.h.encrypted.why")));
		}

		if (!r.duplicateNames.isEmpty()) {
			a.setStructurallyBroken(true);
			a.add(Finding.heuristic(
					Msg.t("wjf.h.duplicate"),
					Severity.HIGH, Finding.Source.ARCHIVE, Msg.t("wjf.cat.structure"),
					Msg.t("wjf.h.duplicate.loc", r.duplicateNames.size()),
					Msg.t("wjf.h.duplicate.why", r.duplicateNames.get(0))));
		}

		if (!r.traversalNames.isEmpty()) {
			a.setStructurallyBroken(true);
			a.add(Finding.heuristic(
					Msg.t("wjf.h.traversal"),
					Severity.CRITICAL, Finding.Source.ARCHIVE, Msg.t("wjf.cat.structure"),
					r.traversalNames.get(0),
					Msg.t("wjf.h.traversal.why")));
		}

		if (!r.exoticMethods.isEmpty()) {
			a.setStructurallyBroken(true);
			a.add(Finding.heuristic(
					Msg.t("wjf.h.method"),
					Severity.MEDIUM, Finding.Source.ARCHIVE, Msg.t("wjf.cat.structure"),
					r.exoticMethods.get(0),
					Msg.t("wjf.h.method.why")));
		}

		if (r.prefixBytes > 0) {
			a.add(Finding.heuristic(
					Msg.t("wjf.h.prefix"),
					Severity.MEDIUM, Finding.Source.ARCHIVE, Msg.t("wjf.cat.structure"),
					Msg.t("wjf.h.bytes", r.prefixBytes),
					Msg.t("wjf.h.prefix.why")));
		}

		if (r.trailingBytes > 0) {
			a.add(Finding.heuristic(
					Msg.t("wjf.h.trailing"),
					Severity.MEDIUM, Finding.Source.ARCHIVE, Msg.t("wjf.cat.structure"),
					Msg.t("wjf.h.bytes", r.trailingBytes),
					Msg.t("wjf.h.trailing.why")));
		}

		if (!r.nonAsciiNames.isEmpty()) {
			a.add(Finding.heuristic(
					Msg.t("wjf.h.nonascii"),
					Severity.MEDIUM, Finding.Source.ARCHIVE, Msg.t("wjf.cat.obfuscation"),
					r.nonAsciiNames.get(0),
					Msg.t("wjf.h.nonascii.why")));
		}

		for (String an : r.anomalies) {
			a.setStructurallyBroken(true);
			a.add(Finding.heuristic(Msg.t("wjf.h.anomaly"), Severity.MEDIUM,
					Finding.Source.ARCHIVE, Msg.t("wjf.cat.structure"), an, an));
		}

		if (!r.readable && r.failure != null) {
			a.add(Finding.heuristic(
					Msg.t("wjf.h.unreadable"),
					Severity.HIGH, Finding.Source.ARCHIVE, Msg.t("wjf.cat.structure"),
					r.failure,
					Msg.t("wjf.h.unreadable.why")));
		}
	}
}
