package com.jaranalyzer.scan;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Decides whether a file is a Java archive by looking inside it, not at its name.
 *
 * <p>Renaming {@code killaura.jar} to {@code d3d9.dll} defeats an extension-only
 * scan completely, so this is what closes that hole. It runs in two stages,
 * because the cheap test alone is far too broad:
 *
 * <ol>
 * <li>Read four bytes and check for the ZIP signature. Cheap enough to run on
 *     every file across a whole disk.
 * <li>For the survivors, read the ZIP central directory and require a
 *     {@code .class} entry or a manifest. Without this second stage the first
 *     one reports every {@code .docx}, {@code .xlsx}, {@code .apk} and browser
 *     extension, essentially all of them noise.
 * </ol>
 */
public final class ArchiveSniffer {

	private ArchiveSniffer() {
	}

	/** Extensions already covered by the name-based sweep. */
	private static final String[] KNOWN_ARCHIVE_EXT = {
			".jar", ".war", ".ear", ".aar", ".zip", ".jmod",
	};

	public static boolean hasArchiveExtension(String fileName) {
		String n = fileName.toLowerCase(Locale.ROOT);
		for (String e : KNOWN_ARCHIVE_EXT) {
			if (n.endsWith(e)) return true;
		}
		return false;
	}

	/** Bytes of the tail searched for the end-of-central-directory record. */
	private static final int TAIL_WINDOW = 1024;

	/**
	 * Stage one: is this a ZIP container at all?
	 *
	 * <p>Two checks, cheapest first. Nearly every archive starts with the local
	 * file header {@code PK\x03\x04} and is settled in four bytes. The fallback
	 * exists because that header is <em>not</em> where a ZIP is really defined:
	 * readers locate the central directory from the end of the file, so arbitrary
	 * bytes may sit in front and the archive still works — the JVM loads classes
	 * from it and {@code jar tf} lists it, while an offset-zero check sees nothing.
	 * That is also how every launch4j-wrapped executable is built, so the shape is
	 * common and real.
	 */
	public static boolean looksLikeZip(File f) {
		try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
			long len = raf.length();
			if (len < 22) return false;   // shorter than an empty ZIP's EOCD

			byte[] head = new byte[4];
			raf.readFully(head);
			if (head[0] == 0x50 && head[1] == 0x4B && head[2] == 0x03 && head[3] == 0x04) {
				return true;
			}
			return hasEndOfCentralDirectory(raf, len);
		} catch (IOException | RuntimeException e) {
			// Locked, vanished, or permission denied. Not a candidate.
			return false;
		}
	}

	/**
	 * Looks for {@code PK\x05\x06} in the last {@link #TAIL_WINDOW} bytes.
	 *
	 * <p>The record may be followed by a comment of up to 64 KB, but a comment on
	 * a disguised archive is vanishingly rare and scanning 64 KB per candidate
	 * across a whole disk is not. A kilobyte covers the uncommented case, which is
	 * every archive a build tool produces.
	 */
	private static boolean hasEndOfCentralDirectory(java.io.RandomAccessFile raf, long len)
			throws IOException {
		int window = (int) Math.min(TAIL_WINDOW, len);
		byte[] tail = new byte[window];
		raf.seek(len - window);
		raf.readFully(tail);

		for (int i = window - 4; i >= 0; i--) {
			if (tail[i] == 0x50 && tail[i + 1] == 0x4B
					&& tail[i + 2] == 0x05 && tail[i + 3] == 0x06) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Stage two: does the archive actually carry Java code?
	 *
	 * <p>Reuses {@link ArchiveInspector}, which reads only the tail and the
	 * central directory rather than decompressing anything.
	 */
	public static boolean containsJavaCode(File f) {
		try {
			ArchiveInspector.Report r = ArchiveInspector.inspect(f);
			if (!r.readable) return false;

			boolean manifest = false;
			for (ArchiveInspector.Entry e : r.entries) {
				String n = e.name.toLowerCase(Locale.ROOT);
				if (n.endsWith(".class")) return true;
				if (n.equals("meta-inf/manifest.mf")) manifest = true;
			}

			// A manifest on its own is weaker evidence — a plain ZIP will not have
			// one, but neither will a resource-only mod. Accepted, and the analysis
			// that follows decides what it actually is.
			return manifest;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Both stages, plus the definition: disguised means <em>not wearing an archive
	 * extension</em>.
	 *
	 * <p>The extension check is not redundant with the caller's. A {@code .jmod} is
	 * a recognised archive format that the scan extensions deliberately leave
	 * alone; a JDK ships around 150 of them, and without this guard the tail-based
	 * ZIP check would pull the whole set into the scan as notable files. Something
	 * that plainly announces its format is not in disguise.
	 */
	public static boolean isDisguisedJavaArchive(File f) {
		if (hasArchiveExtension(f.getName())) return false;
		return looksLikeZip(f) && containsJavaCode(f);
	}
}
