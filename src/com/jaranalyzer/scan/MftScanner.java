package com.jaranalyzer.scan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;

/**
 * Enumerates an NTFS volume by reading its Master File Table directly.
 *
 * <p>This is how Everything finds every file on a machine in seconds while a
 * directory walk takes minutes. Walking directories costs one syscall per
 * directory and makes the disk seek all over the volume; the MFT is a single
 * contiguous index of every file NTFS knows about, and {@code FSCTL_ENUM_USN_DATA}
 * streams it in large sequential blocks. The whole volume comes back in a few
 * passes over one structure.
 *
 * <p>Each record carries a file's name, its own reference number and its
 * parent's — but not its path. Paths are reconstructed afterwards by walking the
 * parent chain through a map built from the directory records in the same pass.
 *
 * <p>Requires administrator rights and an NTFS volume. Both are checked by
 * attempting the call, and {@link #isAvailable()} reports the outcome so the
 * caller can fall back to a directory walk rather than failing.
 */
public final class MftScanner {

	// ---- Win32 ------------------------------------------------------------

	private interface Kernel32 extends Library {
		Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

		Pointer CreateFileW(WString name, int access, int share, Pointer security,
				int creation, int flags, Pointer template);

		boolean DeviceIoControl(Pointer device, int code, Pointer in, int inSize,
				Pointer out, int outSize, IntByReference returned, Pointer overlapped);

		boolean CloseHandle(Pointer handle);

		int GetLastError();
	}

	private static final int GENERIC_READ = 0x80000000;
	private static final int FILE_SHARE_READ = 0x00000001;
	private static final int FILE_SHARE_WRITE = 0x00000002;
	private static final int OPEN_EXISTING = 3;
	private static final int FSCTL_ENUM_USN_DATA = 0x000900B3;
	private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;
	private static final long INVALID_HANDLE = -1L;

	/** 1 MB per call keeps the syscall count low without a huge allocation. */
	private static final int BUFFER_BYTES = 1 << 20;

	private MftScanner() {
	}

	// ---- availability ------------------------------------------------------

	private static Boolean jnaUsable;

	/** Whether the native calls can be made at all in this JVM. */
	public static synchronized boolean isAvailable() {
		if (jnaUsable != null) return jnaUsable;
		try {
			Kernel32.INSTANCE.GetLastError();
			jnaUsable = true;
		} catch (Throwable t) {
			// No JNA native library, a locked-down JVM, or not Windows.
			jnaUsable = false;
		}
		return jnaUsable;
	}

	/**
	 * Opens and immediately closes a raw volume handle.
	 *
	 * <p>Reports whether the MFT route is usable without reading anything, so the
	 * UI and the CLI banner can say which discovery path a scan will take.
	 * Elevation is the usual reason this fails.
	 */
	public static boolean probeVolume(String driveLetter) {
		if (!isAvailable()) return false;
		Pointer h = null;
		try {
			h = Kernel32.INSTANCE.CreateFileW(new WString("\\\\.\\" + driveLetter + ":"),
					GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
					null, OPEN_EXISTING, 0, null);
			return h != null && Pointer.nativeValue(h) != INVALID_HANDLE;
		} catch (Throwable t) {
			return false;
		} finally {
			if (h != null && Pointer.nativeValue(h) != INVALID_HANDLE) {
				try {
					Kernel32.INSTANCE.CloseHandle(h);
				} catch (Throwable ignored) {
					// Nothing useful to do if the handle will not close.
				}
			}
		}
	}

	// ---- scanning ----------------------------------------------------------

	public interface Listener {
		void onFile(String fullPath);

		void onProgress(long recordsSeen, long matches);
	}

	/**
	 * Reads one volume's MFT and reports files whose name matches.
	 *
	 * @param driveLetter e.g. {@code "C"}
	 * @return false when the volume could not be read this way; the caller should
	 *         fall back to a directory walk
	 */
	public static boolean scanVolume(String driveLetter, ScanSettings settings, Listener listener) {
		if (!isAvailable()) return false;

		String path = "\\\\.\\" + driveLetter + ":";
		Pointer handle = Kernel32.INSTANCE.CreateFileW(
				new WString(path), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
				null, OPEN_EXISTING, 0, null);

		if (handle == null || Pointer.nativeValue(handle) == INVALID_HANDLE) {
			// Almost always "access denied": reading a raw volume needs elevation.
			return false;
		}

		try {
			return enumerate(handle, driveLetter, settings, listener);
		} catch (Throwable t) {
			return false;
		} finally {
			try {
				Kernel32.INSTANCE.CloseHandle(handle);
			} catch (Throwable ignored) {
				// Nothing useful to do if the handle will not close.
			}
		}
	}

	private static boolean enumerate(Pointer handle, String driveLetter,
			ScanSettings settings, Listener listener) {

		// Directory records, so a file's path can be rebuilt from its parent chain.
		Map<Long, String> dirName = new HashMap<>(1 << 16);
		Map<Long, Long> dirParent = new HashMap<>(1 << 16);
		// Matching files, held until the directory map is complete.
		List<long[]> pendingParents = new ArrayList<>();
		List<String> pendingNames = new ArrayList<>();

		Memory in = new Memory(24);
		Memory out = new Memory(BUFFER_BYTES);
		IntByReference returned = new IntByReference();

		long startFrn = 0;
		long records = 0;
		long matches = 0;
		boolean anyData = false;

		while (true) {
			in.setLong(0, startFrn);   // StartFileReferenceNumber
			in.setLong(8, 0L);         // LowUsn
			in.setLong(16, Long.MAX_VALUE); // HighUsn

			boolean ok = Kernel32.INSTANCE.DeviceIoControl(
					handle, FSCTL_ENUM_USN_DATA, in, 24, out, BUFFER_BYTES, returned, null);

			if (!ok) break;

			int bytes = returned.getValue();
			if (bytes <= 8) break;     // only the "next FRN" header: done
			anyData = true;

			byte[] block = out.getByteArray(0, bytes);
			ByteBuffer buf = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);

			startFrn = buf.getLong(0);
			int pos = 8;

			while (pos + 60 <= bytes) {
				int recordLength = buf.getInt(pos);
				if (recordLength <= 0 || pos + recordLength > bytes) break;

				long frn = buf.getLong(pos + 8);
				long parentFrn = buf.getLong(pos + 16);
				int attributes = buf.getInt(pos + 52);
				int nameLength = buf.getShort(pos + 56) & 0xFFFF;
				int nameOffset = buf.getShort(pos + 58) & 0xFFFF;

				if (nameOffset > 0 && nameLength > 0 && pos + nameOffset + nameLength <= bytes) {
					String name = new String(block, pos + nameOffset, nameLength,
							java.nio.charset.StandardCharsets.UTF_16LE);
					records++;

					if ((attributes & FILE_ATTRIBUTE_DIRECTORY) != 0) {
						dirName.put(frn, name);
						dirParent.put(frn, parentFrn);
					} else if (settings.matchesExtension(name.toLowerCase(Locale.ROOT))) {
						pendingNames.add(name);
						pendingParents.add(new long[] { parentFrn });
						matches++;
					}
				}

				pos += recordLength;
			}

			if (listener != null && (records & 0xFFFF) == 0) {
				listener.onProgress(records, matches);
			}
		}

		if (!anyData) return false;

		// Resolve paths now that every directory record has been seen.
		StringBuilder sb = new StringBuilder(260);
		for (int i = 0; i < pendingNames.size(); i++) {
			String full = buildPath(driveLetter, pendingParents.get(i)[0], pendingNames.get(i),
					dirName, dirParent, sb);
			if (full != null && listener != null) listener.onFile(full);
		}

		if (listener != null) listener.onProgress(records, matches);
		return true;
	}

	/**
	 * Walks the parent chain to an absolute path.
	 *
	 * @return null when the chain is broken or loops — a record can reference a
	 *         parent that was deleted between passes, and a corrupt volume can
	 *         produce a cycle, neither of which should hang the scan
	 */
	private static String buildPath(String driveLetter, long parentFrn, String name,
			Map<Long, String> dirName, Map<Long, Long> dirParent, StringBuilder sb) {

		sb.setLength(0);
		long current = parentFrn;
		int guard = 0;

		List<String> parts = new ArrayList<>(16);
		while (guard++ < 256) {
			String dn = dirName.get(current);
			if (dn == null) break;               // reached the volume root
			Long next = dirParent.get(current);
			if (next == null || next == current) break;
			parts.add(dn);
			current = next;
		}
		if (guard >= 256) return null;

		sb.append(driveLetter).append(":\\");
		for (int i = parts.size() - 1; i >= 0; i--) {
			sb.append(parts.get(i)).append('\\');
		}
		sb.append(name);
		return sb.toString();
	}

	/** Fixed NTFS volumes that can be enumerated this way. */
	public static List<String> ntfsDriveLetters() {
		List<String> out = new ArrayList<>();
		java.io.File[] roots = java.io.File.listRoots();
		if (roots == null) return out;
		for (java.io.File r : roots) {
			String p = r.getAbsolutePath();
			if (p.length() < 2 || p.charAt(1) != ':') continue;
			if (!r.canRead()) continue;
			try {
				String fsType = java.nio.file.Files.getFileStore(r.toPath()).type();
				if (fsType != null && fsType.toUpperCase(Locale.ROOT).contains("NTFS")) {
					out.add(String.valueOf(p.charAt(0)));
				}
			} catch (Exception ignored) {
				// Unreadable or removed volume; the directory walk will cover it.
			}
		}
		return out;
	}
}
