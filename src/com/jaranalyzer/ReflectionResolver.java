package com.jaranalyzer;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public class ReflectionResolver {

	public interface ProgressCallback {
		void onProgress(String message);
	}

	public static class ReflectionFinding {
		public final String className;
		public final String methodName;
		public final String type;
		public final String detail;

		public ReflectionFinding(String className, String methodName, String type, String detail) {
			this.className = className;
			this.methodName = methodName;
			this.type = type;
			this.detail = detail;
		}
	}

	private ProgressCallback callback;
	private List<ReflectionFinding> findings = new ArrayList<>();
	private int resolvedCount = 0;

	public ReflectionResolver(ProgressCallback callback) {
		this.callback = callback;
	}

	public List<ReflectionFinding> getFindings() {
		return findings;
	}

	public void resolve(JarFile jarFile, File outputFile) throws Exception {
		Map<String, byte[]> results = new HashMap<>();
		Enumeration<JarEntry> entries = jarFile.entries();

		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();
			if (!entry.getName().endsWith(".class"))
				continue;

			try {
				ClassReader cr = new ClassReader(jarFile.getInputStream(entry));
				ClassNode cn = new ClassNode();
				cr.accept(cn, ClassReader.SKIP_FRAMES);

				boolean modified = false;
				for (Object o : cn.methods) {
					MethodNode mn = (MethodNode) o;
					if (mn.instructions == null)
						continue;
					if (resolveMethod(cn, mn))
						modified = true;
				}

				if (modified) {
					ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
					cn.accept(cw);
					results.put(entry.getName(), cw.toByteArray());
				}
			} catch (Exception e) {
			}
		}

		writeOutput(jarFile, results, outputFile);
		if (callback != null)
			callback.onProgress("Reflection resolution complete: " + resolvedCount
					+ " calls resolved, " + findings.size() + " findings");
	}

	private boolean resolveMethod(ClassNode cn, MethodNode mn) {
		InsnList instructions = mn.instructions;
		boolean modified = false;

		for (int i = 0; i < instructions.size(); i++) {
			AbstractInsnNode insn = instructions.get(i);

			if (insn.getType() == AbstractInsnNode.INVOKE_DYNAMIC_INSN) {
				InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
				ReflectionFinding finding = analyzeInvokeDynamic(cn, mn, indy);
				if (finding != null) {
					findings.add(finding);
					if (canResolveIndy(indy)) {
						resolvedCount++;
					}
				}
			}

			if (insn.getOpcode() == Opcodes.INVOKESTATIC
					|| insn.getOpcode() == Opcodes.INVOKEVIRTUAL
					|| insn.getOpcode() == Opcodes.INVOKESPECIAL) {
				MethodInsnNode min = (MethodInsnNode) insn;
				ReflectionFinding finding = analyzeReflectionCall(cn, mn, min, instructions, i);
				if (finding != null) {
					findings.add(finding);
				}
			}

			if (insn.getType() == AbstractInsnNode.LDC_INSN) {
				LdcInsnNode ldc = (LdcInsnNode) insn;
				if (ldc.cst instanceof String) {
					String str = (String) ldc.cst;
					if (looksLikeClassName(str)) {
						int nextIdx = i + 1;
						if (nextIdx < instructions.size()) {
							AbstractInsnNode next = instructions.get(nextIdx);
							if (next.getOpcode() == Opcodes.INVOKESTATIC) {
								MethodInsnNode min = (MethodInsnNode) next;
								if (min.owner.equals("java/lang/Class") && min.name.equals("forName")) {
									findings.add(new ReflectionFinding(cn.name, mn.name,
											"Class.forName", str));
								}
							}
						}
					}
				}
			}
		}

		return modified;
	}

	private ReflectionFinding analyzeInvokeDynamic(ClassNode cn, MethodNode mn, InvokeDynamicInsnNode indy) {
		if (indy.bsmArgs == null || indy.bsmArgs.length == 0)
			return null;

		StringBuilder detail = new StringBuilder();
		detail.append("bootstrap: ").append(indy.bsm.getOwner()).append(".").append(indy.bsm.getName());

		for (Object arg : indy.bsmArgs) {
			if (arg instanceof Handle) {
				Handle h = (Handle) arg;
				detail.append(" -> ").append(h.getOwner()).append(".").append(h.getName()).append(h.getDesc());
			} else if (arg instanceof Type) {
				detail.append(" type:").append(((Type) arg).getDescriptor());
			} else if (arg instanceof String) {
				detail.append(" str:\"").append(arg).append("\"");
			}
		}

		String type = "InvokeDynamic";
		if (indy.bsm.getName().contains("bootstrap") || indy.bsm.getName().equals("metafactory")) {
			type = "INDY-Lambda";
		}

		return new ReflectionFinding(cn.name, mn.name, type, detail.toString());
	}

	private boolean canResolveIndy(InvokeDynamicInsnNode indy) {
		if (indy.bsmArgs == null)
			return false;
		for (Object arg : indy.bsmArgs) {
			if (arg instanceof Handle) {
				Handle h = (Handle) arg;
				if (h.getOwner() != null && !h.getOwner().startsWith("java/"))
					return true;
			}
		}
		return false;
	}

	private ReflectionFinding analyzeReflectionCall(ClassNode cn, MethodNode mn,
			MethodInsnNode min, InsnList instructions, int index) {
		if (min.owner == null)
			return null;

		if (min.owner.equals("java/lang/reflect/Method")) {
			if (min.name.equals("invoke")) {
				return new ReflectionFinding(cn.name, mn.name, "Method.invoke", min.desc);
			}
		}

		if (min.owner.equals("java/lang/Class")) {
			if (min.name.equals("forName")) {
				return new ReflectionFinding(cn.name, mn.name, "Class.forName", min.desc);
			}
			if (min.name.equals("getDeclaredMethod") || min.name.equals("getMethod")) {
				return new ReflectionFinding(cn.name, mn.name, "Class.getMethod", min.desc);
			}
			if (min.name.equals("getDeclaredField") || min.name.equals("getField")) {
				return new ReflectionFinding(cn.name, mn.name, "Class.getField", min.desc);
			}
		}

		if (min.owner.equals("java/lang/reflect/Field")) {
			if (min.name.equals("set") || min.name.equals("get")) {
				return new ReflectionFinding(cn.name, mn.name, "Field." + min.name, min.desc);
			}
		}

		if (min.owner.equals("java/lang/invoke/MethodHandles")) {
			if (min.name.equals("lookup") || min.name.equals("invoke") || min.name.equals("invokeExact")) {
				return new ReflectionFinding(cn.name, mn.name, "MethodHandles." + min.name, min.desc);
			}
		}

		return null;
	}

	private boolean looksLikeClassName(String str) {
		if (str == null || str.length() < 3)
			return false;
		if (str.contains("/") || str.contains("\\"))
			return false;
		if (str.matches("^[A-Z][a-zA-Z0-9_]*(\\.[A-Z][a-zA-Z0-9_]*)+$"))
			return true;
		return false;
	}

	private void writeOutput(JarFile jarFile, Map<String, byte[]> modifiedClasses, File outputFile) throws Exception {
		try (ZipOutputStream zos = new ZipOutputStream(new java.io.FileOutputStream(outputFile))) {
			Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				if (entry.getName().endsWith(".class") && modifiedClasses.containsKey(entry.getName())) {
					ZipEntry ze = new ZipEntry(entry.getName());
					zos.putNextEntry(ze);
					zos.write(modifiedClasses.get(entry.getName()));
					zos.closeEntry();
				} else if (!entry.isDirectory()) {
					ZipEntry ze = new ZipEntry(entry.getName());
					zos.putNextEntry(ze);
					byte[] buf = new byte[4096];
					java.io.InputStream is = jarFile.getInputStream(entry);
					int len;
					while ((len = is.read(buf)) > 0) {
						zos.write(buf, 0, len);
					}
					is.close();
					zos.closeEntry();
				}
			}
		}
	}
}
