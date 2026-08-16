package com.jaranalyzer.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * Reads the command line of every running process straight from Windows.
 *
 * <p>Exists because {@link JvmScanner} can be switched off by the thing it is
 * meant to inspect. A JVM started with {@code -XX:+DisableAttachMechanism}
 * refuses every attach, and the Attach API then reports no JVMs at all — the
 * cheat is running, its jar is on the classpath, and the scan comes back empty.
 *
 * <p>This asks the operating system instead, so the target's cooperation is not
 * required. It is also the only place the launch command itself is visible —
 * {@code javaw.exe -cp yks1233.dll FakeGame} names the disguised file even when
 * nothing about the process looks like Java from the outside.
 *
 * <p>Windows exposes no API that simply returns another process's command line.
 * The route is the documented-but-awkward one: ask for the process's PEB
 * address, then read the command line out of that process's memory. Java's own
 * {@code ProcessHandle} does not help here — on Windows it returns the
 * executable path and leaves {@code commandLine()} and {@code arguments()} empty.
 */
public final class ProcessScanner {

	private ProcessScanner() {
	}

	/** One process, with whatever could be read about it. */
	public static final class ProcInfo {
		public long pid;
		public String exe = "";
		public String commandLine = "";
		/** Paths named on the command line that exist on disk. */
		public final List<java.io.File> referencedFiles = new ArrayList<>();
		/** Paths named on the command line that no longer exist. */
		public final List<String> missingFiles = new ArrayList<>();
		/** True when the process asked the JVM to refuse debugger attachment. */
		public boolean attachDisabled;

		/**
		 * Whether this really is a JVM.
		 *
		 * <p>Judged on the executable alone, not on the command line: a shell or
		 * script that merely <em>mentions</em> a java command (a "-jar" somewhere in
		 * its arguments) is not a JVM, and matching on that would flag things like a
		 * PowerShell window and try to analyse {@code powershell.exe} as an archive.
		 * The JVM a launcher starts shows up as its own process anyway.
		 */
		public boolean isJava() {
			String name = exe;
			int slash = Math.max(name.lastIndexOf('\\'), name.lastIndexOf('/'));
			if (slash >= 0) name = name.substring(slash + 1);
			name = name.toLowerCase(Locale.ROOT);
			return name.equals("java.exe") || name.equals("javaw.exe");
		}
	}

	// =====================================================================
	//  Native
	// =====================================================================

	private interface Kernel32 extends Library {
		Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

		Pointer OpenProcess(int access, boolean inherit, int pid);

		boolean ReadProcessMemory(Pointer process, Pointer address, Pointer buffer,
				int size, IntByReference read);

		boolean CloseHandle(Pointer handle);

		int GetLastError();
	}

	private interface NtDll extends Library {
		NtDll INSTANCE = Native.load("ntdll", NtDll.class);

		int NtQueryInformationProcess(Pointer process, int infoClass, Pointer info,
				int infoLength, IntByReference returnLength);
	}

	private static final int PROCESS_QUERY_INFORMATION = 0x0400;
	private static final int PROCESS_VM_READ = 0x0010;
	private static final int PROCESS_BASIC_INFORMATION = 0;

	/**
	 * Offsets into the 64-bit PEB and RTL_USER_PROCESS_PARAMETERS.
	 *
	 * <p>Undocumented but stable across every 64-bit Windows since Vista, and the
	 * layout debuggers rely on. Guarded by a pointer-size check rather than
	 * assumed: on a 32-bit JVM the offsets differ and the whole path is skipped
	 * instead of reading nonsense.
	 */
	private static final int PEB_OFFSET_IN_PBI = 0x08;
	private static final int PROCESS_PARAMS_OFFSET_IN_PEB = 0x20;
	private static final int COMMANDLINE_OFFSET_IN_PARAMS = 0x70;

	private static Boolean supported;

	public static synchronized boolean isSupported() {
		if (supported != null) return supported;
		supported = false;
		try {
			if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
				return false;
			}
			// The offsets above are the 64-bit layout only.
			if (Native.POINTER_SIZE != 8) return false;
			Kernel32.INSTANCE.GetLastError();
			NtDll.INSTANCE.hashCode();
			supported = true;
		} catch (Throwable t) {
			supported = false;
		}
		return supported;
	}

	// =====================================================================

	/**
	 * Every process whose command line could be read.
	 *
	 * <p>Never throws. A process that exits mid-scan, or one owned by another user
	 * that this process may not open, is skipped — being unable to read one
	 * process is not a reason to abandon the rest.
	 */
	public static List<ProcInfo> scan() {
		List<ProcInfo> out = new ArrayList<>();
		if (!isSupported()) return out;

		long self = ProcessHandle.current().pid();
		java.util.List<ProcessHandle> all;
		try {
			all = ProcessHandle.allProcesses().collect(java.util.stream.Collectors.toList());
		} catch (Throwable t) {
			return out;
		}

		for (ProcessHandle ph : all) {
			long pid = ph.pid();
			if (pid == self || pid <= 4) continue;

			String exe = ph.info().command().orElse("");
			String cmd = readCommandLine(pid);
			if (cmd.isEmpty() && exe.isEmpty()) continue;

			ProcInfo p = new ProcInfo();
			p.pid = pid;
			p.exe = exe;
			p.commandLine = cmd;
			p.attachDisabled = cmd.toLowerCase(Locale.ROOT).contains("disableattachmechanism");
			if (p.isJava()) {
				collectPaths(p);
				out.add(p);
			}
		}
		return out;
	}

	/** Reads one process's command line, or "" when it cannot be read. */
	public static String readCommandLine(long pid) {
		Pointer proc = null;
		try {
			proc = Kernel32.INSTANCE.OpenProcess(
					PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, false, (int) pid);
			if (proc == null) return "";

			// PROCESS_BASIC_INFORMATION: the PEB address is the second pointer.
			Memory pbi = new Memory(48);
			IntByReference len = new IntByReference();
			if (NtDll.INSTANCE.NtQueryInformationProcess(
					proc, PROCESS_BASIC_INFORMATION, pbi, 48, len) != 0) {
				return "";
			}
			long pebAddress = pbi.getLong(PEB_OFFSET_IN_PBI);
			if (pebAddress == 0) return "";

			long paramsAddress = readPointer(proc, pebAddress + PROCESS_PARAMS_OFFSET_IN_PEB);
			if (paramsAddress == 0) return "";

			// UNICODE_STRING { USHORT Length; USHORT MaximumLength; PWSTR Buffer; }
			Memory us = new Memory(16);
			if (!read(proc, paramsAddress + COMMANDLINE_OFFSET_IN_PARAMS, us, 16)) return "";
			int byteLen = us.getShort(0) & 0xFFFF;
			long bufferAddress = us.getLong(8);
			if (byteLen <= 0 || bufferAddress == 0) return "";
			// A command line longer than this is not a command line.
			if (byteLen > 64 * 1024) byteLen = 64 * 1024;

			Memory buf = new Memory(byteLen);
			if (!read(proc, bufferAddress, buf, byteLen)) return "";
			return new String(buf.getByteArray(0, byteLen),
					java.nio.charset.StandardCharsets.UTF_16LE).trim();
		} catch (Throwable t) {
			return "";
		} finally {
			if (proc != null) {
				try {
					Kernel32.INSTANCE.CloseHandle(proc);
				} catch (Throwable ignored) {
					// Handle already gone; nothing to release.
				}
			}
		}
	}

	private static long readPointer(Pointer proc, long address) {
		Memory m = new Memory(8);
		return read(proc, address, m, 8) ? m.getLong(0) : 0;
	}

	private static boolean read(Pointer proc, long address, Memory into, int size) {
		IntByReference got = new IntByReference();
		return Kernel32.INSTANCE.ReadProcessMemory(
				proc, new Pointer(address), into, size, got) && got.getValue() == size;
	}

	// =====================================================================

	/**
	 * Pulls file paths out of a command line and sorts them into present and
	 * missing.
	 *
	 * <p>Both halves matter. A present file is something to analyse no matter what
	 * it is called; a missing one means the process is running from a file that
	 * has since been deleted, which is the loudest thing this scan can report.
	 */
	private static void collectPaths(ProcInfo p) {
		List<String> args = splitArguments(p.commandLine);
		for (int i = 0; i < args.size(); i++) {
			// Argument 0 is the launcher itself. Reporting java.exe as a find is
			// noise, and analysing it as an archive produced an "unreadable" row.
			if (i == 0) continue;

			// Classpath entries arrive as one argument separated by ';'.
			for (String part : args.get(i).split(";")) {
				String s = part.trim();
				if (!looksLikePath(s)) continue;

				java.io.File f = new java.io.File(s);
				if (f.isDirectory()) continue;

				if (f.isFile()) {
					// Only archives are worth queueing. Every other existing file a
					// JVM is handed — a config, a log, a native library — analyses to
					// "not an archive" and adds a row saying nothing.
					if (ArchiveSniffer.looksLikeZip(f) && !p.referencedFiles.contains(f)) {
						p.referencedFiles.add(f);
					}
				} else if (!p.missingFiles.contains(s)) {
					p.missingFiles.add(s);
				}
			}
		}
	}

	/**
	 * Whether a token is plausibly a filesystem path.
	 *
	 * <p>Deliberately strict. Command lines contain plenty of text with a slash in
	 * it, and a loose test turned fragments of a PowerShell one-liner into
	 * "missing file" findings. An absolute path with an extension and no shell
	 * punctuation is the shape worth acting on.
	 */
	private static boolean looksLikePath(String s) {
		if (s.length() < 6 || s.startsWith("-")) return false;

		boolean absolute = (s.length() > 2 && s.charAt(1) == ':'
					&& (s.charAt(2) == '\\' || s.charAt(2) == '/'))
				|| s.startsWith("\\\\");
		if (!absolute) return false;

		for (int i = 0; i < s.length(); i++) {
			switch (s.charAt(i)) {
				case '$': case '|': case '>': case '<': case '&':
				case '*': case '?': case '"': case '\'': case '`':
					return false;
				default:
					break;
			}
		}

		int dot = s.lastIndexOf('.');
		int sep = Math.max(s.lastIndexOf('\\'), s.lastIndexOf('/'));
		return dot > sep && dot < s.length() - 1;
	}

	/** Splits a Windows command line, honouring double quotes. */
	static List<String> splitArguments(String cmd) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < cmd.length(); i++) {
			char c = cmd.charAt(i);
			if (c == '"') {
				quoted = !quoted;
			} else if (c == ' ' && !quoted) {
				if (cur.length() > 0) out.add(cur.toString());
				cur.setLength(0);
			} else {
				cur.append(c);
			}
		}
		if (cur.length() > 0) out.add(cur.toString());
		return out;
	}
}
