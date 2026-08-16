package com.jaranalyzer;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

public class IntegerConstantFolder {

	public interface ProgressCallback {
		void onProgress(String message);
	}

	private ProgressCallback callback;
	private int foldedCount = 0;

	public IntegerConstantFolder(ProgressCallback callback) {
		this.callback = callback;
	}

	public void fold(JarFile jarFile, File outputFile) throws Exception {
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
					if (foldMethod(mn))
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
			callback.onProgress("Integer folding complete: " + foldedCount + " constants folded");
	}

	private boolean foldMethod(MethodNode mn) {
		InsnList instructions = mn.instructions;
		boolean modified = false;

		for (int i = 0; i < instructions.size(); i++) {
			AbstractInsnNode insn = instructions.get(i);

			if (isIntPush(insn)) {
				Integer val1 = getIntValue(insn);
				if (val1 == null)
					continue;

				int nextIdx = i + 1;
				if (nextIdx >= instructions.size())
					continue;
				AbstractInsnNode insn2 = instructions.get(nextIdx);

				if (isIntPush(insn2)) {
					Integer val2 = getIntValue(insn2);
					if (val2 == null)
						continue;

					int opIdx = nextIdx + 1;
					if (opIdx >= instructions.size())
						continue;
					AbstractInsnNode opInsn = instructions.get(opIdx);

					Integer result = foldBinary(val1, val2, opInsn);
					if (result != null) {
						AbstractInsnNode replacement = createIntPush(result);
						instructions.remove(insn);
						instructions.remove(insn2);
						instructions.set(opInsn, replacement);
						modified = true;
						foldedCount++;
						i--;
						continue;
					}
				}

				Integer unaryResult = foldUnary(val1, insn2);
				if (unaryResult != null) {
					AbstractInsnNode replacement = createIntPush(unaryResult);
					instructions.remove(insn);
					instructions.set(insn2, replacement);
					modified = true;
					foldedCount++;
					i--;
					continue;
				}
			}
		}

		return modified;
	}

	private boolean isIntPush(AbstractInsnNode insn) {
		if (insn == null)
			return false;
		int op = insn.getOpcode();
		if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5)
			return true;
		if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)
			return true;
		if (insn.getType() == AbstractInsnNode.LDC_INSN) {
			LdcInsnNode ldc = (LdcInsnNode) insn;
			return ldc.cst instanceof Integer;
		}
		return false;
	}

	private Integer getIntValue(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5)
			return op - Opcodes.ICONST_0;
		if (op == Opcodes.BIPUSH)
			return ((IntInsnNode) insn).operand;
		if (op == Opcodes.SIPUSH)
			return ((IntInsnNode) insn).operand;
		if (insn.getType() == AbstractInsnNode.LDC_INSN) {
			LdcInsnNode ldc = (LdcInsnNode) insn;
			if (ldc.cst instanceof Integer)
				return (Integer) ldc.cst;
		}
		return null;
	}

	private AbstractInsnNode createIntPush(int value) {
		if (value >= -1 && value <= 5)
			return new InsnNode(Opcodes.ICONST_0 + value);
		if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE)
			return new IntInsnNode(Opcodes.BIPUSH, value);
		if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE)
			return new IntInsnNode(Opcodes.SIPUSH, value);
		return new LdcInsnNode(value);
	}

	private Integer foldBinary(int a, int b, AbstractInsnNode opInsn) {
		if (opInsn == null)
			return null;
		int op = opInsn.getOpcode();
		switch (op) {
			case Opcodes.IADD: return a + b;
			case Opcodes.ISUB: return a - b;
			case Opcodes.IMUL: return a * b;
			case Opcodes.IDIV:
				if (b == 0) return null;
				return a / b;
			case Opcodes.IREM:
				if (b == 0) return null;
				return a % b;
			case Opcodes.IAND: return a & b;
			case Opcodes.IOR: return a | b;
			case Opcodes.IXOR: return a ^ b;
			case Opcodes.ISHL: return a << b;
			case Opcodes.ISHR: return a >> b;
			case Opcodes.IUSHR: return a >>> b;
			default: return null;
		}
	}

	private Integer foldUnary(int a, AbstractInsnNode opInsn) {
		if (opInsn == null)
			return null;
		int op = opInsn.getOpcode();
		if (op == Opcodes.INEG)
			return -a;
		return null;
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
