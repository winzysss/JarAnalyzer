package com.jaranalyzer.scan;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads what is searchable out of a .class file without decompiling it.
 *
 * <p>This is the tool's fast path, and it exists because decompilation is almost
 * pure overhead for detection. A decompiler's output is built <em>from</em> the
 * constant pool: every identifier, type reference and string literal a keyword
 * search can match is already sitting in the pool as a plain UTF-8 entry, so
 * reconstructing control flow around them is invaluable for a human reading the
 * code and worth nothing to a substring match. Scanning the pool directly is the
 * same search for a small fraction of the cost.
 *
 * <p>Decompilation is still available — it is what the detail pane shows and what
 * turns a hit into readable evidence — but it is now something done to the
 * handful of archives that warrant it, not to every JAR on the disk.
 */
public final class ClassScanner {

	private ClassScanner() {
	}

	// Constant pool tags.
	private static final int UTF8 = 1;
	private static final int INTEGER = 3;
	private static final int FLOAT = 4;
	private static final int LONG = 5;
	private static final int DOUBLE = 6;
	private static final int CLASS = 7;
	private static final int STRING = 8;
	private static final int FIELDREF = 9;
	private static final int METHODREF = 10;
	private static final int INTERFACE_METHODREF = 11;
	private static final int NAME_AND_TYPE = 12;
	private static final int METHOD_HANDLE = 15;
	private static final int METHOD_TYPE = 16;
	private static final int DYNAMIC = 17;
	private static final int INVOKE_DYNAMIC = 18;
	private static final int MODULE = 19;
	private static final int PACKAGE = 20;

	private static final int ACC_SYNTHETIC = 0x1000;
	private static final int ACC_BRIDGE = 0x0040;

	/** What one class contributes to detection. */
	public static final class ClassInfo {
		/** Internal name, e.g. {@code com/example/Foo}. Empty when unparseable. */
		public String internalName = "";
		/** Simple name, the part after the last slash. */
		public String simpleName = "";
		/** Every UTF-8 constant, in pool order. */
		public final List<String> constants = new ArrayList<>();
		/** Field and method names only (no descriptors). */
		public final List<String> memberNames = new ArrayList<>();
		public int memberCount;
		public int syntheticMembers;
		public boolean hasSourceFile;
		public boolean parsed;
		/**
		 * Calls to a non-JDK method that takes something small and returns a
		 * String — the shape a string-decryption stub has. Resolved through the
		 * method reference so that {@code String.substring(int)} and
		 * {@code String.concat(String)}, which have the same descriptors and occur
		 * in ordinary code constantly, are not counted.
		 */
		public int stringFactoryCalls;
	}

	/** Descriptors a string-decryption stub typically has. */
	private static final String[] DECRYPT_DESCRIPTORS = {
			"(Ljava/lang/String;)Ljava/lang/String;",
			"(I)Ljava/lang/String;",
			"([C)Ljava/lang/String;",
			"(II)Ljava/lang/String;",
			"(J)Ljava/lang/String;",
			"([B)Ljava/lang/String;",
	};

	private static boolean isDecryptDescriptor(String desc) {
		for (String d : DECRYPT_DESCRIPTORS) {
			if (d.equals(desc)) return true;
		}
		return false;
	}

	/**
	 * Parses a class file far enough to enumerate its constants and member names.
	 *
	 * <p>Deliberately not a full class parser: attribute bodies are skipped by
	 * their declared length rather than decoded, which is what keeps it at
	 * microseconds per class. Returns a result with {@code parsed == false} rather
	 * than throwing — a class that will not parse is itself a finding, reported by
	 * the caller.
	 */
	public static ClassInfo read(byte[] b) {
		ClassInfo info = new ClassInfo();
		if (b == null || b.length < 10) return info;

		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));

			if (in.readInt() != 0xCAFEBABE) return info;
			in.readUnsignedShort();  // minor
			in.readUnsignedShort();  // major

			int cpCount = in.readUnsignedShort();
			String[] utf8 = new String[cpCount];
			int[] classNameIndex = new int[cpCount];
			// Method references, kept so the owner of a call can be resolved after
			// the pool is fully read (forward references are legal).
			int[] refClass = new int[cpCount];
			int[] refNameAndType = new int[cpCount];
			int[] natDescriptor = new int[cpCount];

			for (int i = 1; i < cpCount; i++) {
				int tag = in.readUnsignedByte();
				switch (tag) {
					case UTF8: {
						String s = in.readUTF();
						utf8[i] = s;
						info.constants.add(s);
						if ("SourceFile".equals(s)) info.hasSourceFile = true;
						break;
					}
					case CLASS:
						classNameIndex[i] = in.readUnsignedShort();
						break;
					case STRING:
					case METHOD_TYPE:
					case MODULE:
					case PACKAGE:
						in.skipBytes(2);
						break;
					case METHOD_HANDLE:
						in.skipBytes(3);
						break;
					case METHODREF:
					case INTERFACE_METHODREF:
						refClass[i] = in.readUnsignedShort();
						refNameAndType[i] = in.readUnsignedShort();
						break;
					case NAME_AND_TYPE:
						in.readUnsignedShort();                 // name_index
						natDescriptor[i] = in.readUnsignedShort();
						break;
					case INTEGER:
					case FLOAT:
					case FIELDREF:
					case DYNAMIC:
					case INVOKE_DYNAMIC:
						in.skipBytes(4);
						break;
					case LONG:
					case DOUBLE:
						in.skipBytes(8);
						// Longs and doubles occupy two pool slots; the second is unusable.
						i++;
						break;
					default:
						// Unknown tag: the pool is no longer parseable from here, but
						// whatever was collected so far is still valid evidence.
						return info;
				}
			}

			// Resolve method references now that every pool entry is known.
			for (int i = 1; i < cpCount; i++) {
				if (refNameAndType[i] == 0) continue;
				int nat = refNameAndType[i];
				if (nat <= 0 || nat >= cpCount) continue;
				int di = natDescriptor[nat];
				if (di <= 0 || di >= cpCount || utf8[di] == null) continue;
				if (!isDecryptDescriptor(utf8[di])) continue;

				int ci = refClass[i];
				String owner = null;
				if (ci > 0 && ci < cpCount) {
					int ni = classNameIndex[ci];
					if (ni > 0 && ni < cpCount) owner = utf8[ni];
				}
				// java/lang/String.substring(int), concat(String) and friends share
				// these descriptors; only a call into non-JDK code is a candidate.
				if (owner == null || owner.startsWith("java/") || owner.startsWith("javax/")
						|| owner.startsWith("jdk/") || owner.startsWith("sun/")) {
					continue;
				}
				info.stringFactoryCalls++;
			}

			in.readUnsignedShort();                     // access_flags
			int thisClass = in.readUnsignedShort();
			if (thisClass > 0 && thisClass < cpCount) {
				int ni = classNameIndex[thisClass];
				if (ni > 0 && ni < cpCount && utf8[ni] != null) {
					info.internalName = utf8[ni];
					int slash = info.internalName.lastIndexOf('/');
					info.simpleName = slash >= 0
							? info.internalName.substring(slash + 1)
							: info.internalName;
				}
			}

			in.readUnsignedShort();                     // super_class
			int interfaces = in.readUnsignedShort();
			in.skipBytes(interfaces * 2);

			readMembers(in, utf8, cpCount, info);       // fields
			readMembers(in, utf8, cpCount, info);       // methods

			info.parsed = true;
		} catch (IOException | RuntimeException e) {
			// Truncated or malformed. Partial results stand; parsed stays false.
		}
		return info;
	}

	private static void readMembers(DataInputStream in, String[] utf8, int cpCount, ClassInfo info)
			throws IOException {
		int count = in.readUnsignedShort();
		for (int i = 0; i < count; i++) {
			int access = in.readUnsignedShort();
			int nameIndex = in.readUnsignedShort();
			in.readUnsignedShort();                     // descriptor_index

			info.memberCount++;
			if ((access & (ACC_SYNTHETIC | ACC_BRIDGE)) != 0) info.syntheticMembers++;

			if (nameIndex > 0 && nameIndex < cpCount && utf8[nameIndex] != null) {
				info.memberNames.add(utf8[nameIndex]);
			}

			skipAttributes(in);
		}
	}

	private static void skipAttributes(DataInputStream in) throws IOException {
		int n = in.readUnsignedShort();
		for (int i = 0; i < n; i++) {
			in.readUnsignedShort();                     // name_index
			long len = in.readInt() & 0xFFFFFFFFL;
			long skipped = 0;
			while (skipped < len) {
				long s = in.skip(len - skipped);
				if (s <= 0) throw new IOException("truncated attribute");
				skipped += s;
			}
		}
	}

	/**
	 * Joins the constants into one buffer for a single regex pass.
	 *
	 * <p>Entries are newline-separated so a blacklist term cannot match across the
	 * seam between two unrelated constants.
	 */
	public static String joinConstants(ClassInfo info, int maxChars) {
		StringBuilder sb = new StringBuilder(Math.min(maxChars, 8192));
		for (String s : info.constants) {
			if (sb.length() + s.length() + 1 > maxChars) break;
			sb.append(s).append('\n');
		}
		return sb.toString();
	}

}
