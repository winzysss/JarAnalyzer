package com.jaranalyzer.scan;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Reads what a <em>running</em> Java process actually loaded.
 *
 * <p>The disk sweep answers "what is on this machine". This answers the question
 * that matters more during a screenshare: "what is the game running right now" —
 * and those differ in exactly the case worth catching. A cheat can be launched,
 * injected, and its file deleted; the JVM keeps running from memory and the disk
 * scan finds nothing. The classpath entry survives in the live process even when
 * the file behind it is gone, so a path that no longer exists on disk is the
 * loudest signal this tool can produce.
 *
 * <p>Uses the JDK Attach API rather than a custom agent. Attaching and reading
 * the target's system properties needs no code injected into the game, which
 * keeps this side-effect free — nothing is modified in the process being
 * examined, which matters when the person being checked is watching.
 *
 * <p>Everything is reflective. {@code jdk.attach} is not present in every
 * runtime, and a hard reference would stop the whole application from starting
 * where it is missing rather than disabling one feature.
 */
public final class JvmScanner {

	private JvmScanner() {
	}

	public static final class JvmInfo {
		public String pid = "";
		public String displayName = "";
		public String command = "";
		public String classPath = "";
		public final List<String> jvmArgs = new ArrayList<>();
		/** Classpath entries and agent jars, de-duplicated. */
		public final Set<File> jars = new LinkedHashSet<>();
		/** Entries the process has loaded whose file is no longer on disk. */
		public final List<String> missingFromDisk = new ArrayList<>();
		public final List<String> agents = new ArrayList<>();
		public String error;

		public boolean looksLikeMinecraft() {
			String all = (displayName + " " + command + " " + classPath)
					.toLowerCase(Locale.ROOT);
			return all.contains("minecraft") || all.contains("net.minecraft")
					|| all.contains("fabric") || all.contains("forge")
					|| all.contains("lunar") || all.contains("badlion")
					|| all.contains("prismlauncher") || all.contains("multimc");
		}
	}

	// =====================================================================

	private static Boolean available;

	/** Whether the Attach API is present in this runtime. */
	public static synchronized boolean isAvailable() {
		if (available != null) return available;
		try {
			Class.forName("com.sun.tools.attach.VirtualMachine");
			available = true;
		} catch (Throwable t) {
			available = false;
		}
		return available;
	}

	/**
	 * Lists every attachable JVM and what it has loaded.
	 *
	 * <p>Never throws: a process that exits mid-scan, or refuses attachment, is
	 * reported with its {@code error} set rather than aborting the sweep.
	 */
	public static List<JvmInfo> scan() {
		List<JvmInfo> out = new ArrayList<>();
		if (!isAvailable()) return out;

		try {
			Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
			Object descriptors = vmClass.getMethod("list").invoke(null);

			for (Object vd : (List<?>) descriptors) {
				JvmInfo info = new JvmInfo();
				try {
					info.pid = String.valueOf(vd.getClass().getMethod("id").invoke(vd));
					Object dn = vd.getClass().getMethod("displayName").invoke(vd);
					info.displayName = dn == null ? "" : dn.toString();
				} catch (Throwable ignored) {
					// Descriptor became unusable; keep whatever was read.
				}

				// Skip this process itself — it is a Java process too.
				if (info.pid.equals(ownPid())) continue;

				try {
					Object vm = vmClass.getMethod("attach", String.class).invoke(null, info.pid);
					try {
						readFrom(vmClass, vm, info);
					} finally {
						vmClass.getMethod("detach").invoke(vm);
					}
				} catch (Throwable t) {
					// Most often the target is a different user or already gone.
					info.error = t.getClass().getSimpleName()
							+ (t.getMessage() == null ? "" : ": " + t.getMessage());
				}

				out.add(info);
			}
		} catch (Throwable t) {
			// Attach API unusable as a whole; callers see an empty list.
			return out;
		}
		return out;
	}

	/**
	 * @param vmClass must be the public {@code VirtualMachine} class, not
	 *        {@code vm.getClass()}. The object attach() hands back is a
	 *        {@code sun.tools.attach.HotSpotVirtualMachine}, whose package
	 *        {@code jdk.attach} does not export — invoking a method looked up on
	 *        the implementation class throws IllegalAccessException even though
	 *        the method itself is public.
	 */
	private static void readFrom(Class<?> vmClass, Object vm, JvmInfo info) throws Exception {
		Properties sys = (Properties) vmClass.getMethod("getSystemProperties").invoke(vm);
		Properties agentProps;
		try {
			agentProps = (Properties) vmClass.getMethod("getAgentProperties").invoke(vm);
		} catch (Throwable t) {
			agentProps = new Properties();
		}

		info.classPath = sys.getProperty("java.class.path", "");
		info.command = agentProps.getProperty("sun.java.command",
				sys.getProperty("sun.java.command", ""));

		String args = agentProps.getProperty("sun.jvm.args", "");
		for (String a : args.split("\\s+")) {
			if (!a.isEmpty()) info.jvmArgs.add(a);
		}

		// A -javaagent on a game process is the direct route to rewriting its
		// classes, so the jar behind it is collected for analysis like any other.
		for (String a : info.jvmArgs) {
			String lower = a.toLowerCase(Locale.ROOT);
			if (lower.startsWith("-javaagent:") || lower.startsWith("-agentpath:")
					|| lower.startsWith("-agentlib:")) {
				info.agents.add(a);
				int colon = a.indexOf(':');
				if (colon > 0) {
					String path = a.substring(colon + 1);
					int eq = path.indexOf('=');
					if (eq > 0) path = path.substring(0, eq);
					addPath(info, path);
				}
			}
		}

		for (String entry : info.classPath.split(java.io.File.pathSeparator)) {
			addPath(info, entry);
		}
	}

	private static void addPath(JvmInfo info, String raw) {
		if (raw == null) return;
		String p = raw.trim();
		if (p.isEmpty()) return;

		File f = new File(p);
		String lower = p.toLowerCase(Locale.ROOT);
		boolean archive = lower.endsWith(".jar") || lower.endsWith(".zip")
				|| lower.endsWith(".war");

		if (f.isFile()) {
			if (archive || ArchiveSniffer.looksLikeZip(f)) info.jars.add(f);
			return;
		}
		if (f.isDirectory()) return;   // exploded classes dir, nothing to analyse

		// Referenced but not present. This is the case the whole feature exists
		// for: loaded from disk, then deleted while the JVM kept running.
		if (archive) info.missingFromDisk.add(p);
	}

	private static String ownPid() {
		try {
			return String.valueOf(ProcessHandle.current().pid());
		} catch (Throwable t) {
			return "-1";
		}
	}
}
