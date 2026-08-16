package com.jaranalyzer;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public class ControlFlowFlattener {

	public interface ProgressCallback {
		void onProgress(String message);
	}

	private ProgressCallback callback;
	private int flattenedCount = 0;
	private int opaquePredicateCount = 0;

	public ControlFlowFlattener(ProgressCallback callback) {
		this.callback = callback;
	}

	public void unflatten(JarFile jarFile, File outputFile) throws Exception {
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

					if (removeOpaquePredicates(mn))
						modified = true;
					if (unflattenSwitchState(mn))
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
			callback.onProgress("CFF unflattening complete: " + flattenedCount
					+ " state machines unflattened, " + opaquePredicateCount + " opaque predicates removed");
	}

	private boolean removeOpaquePredicates(MethodNode mn) {
		InsnList instructions = mn.instructions;
		boolean modified = false;

		for (int i = 0; i < instructions.size() - 2; i++) {
			AbstractInsnNode insn = instructions.get(i);

			if (isConstantInt(insn, 0) || isConstantInt(insn, 1)) {
				int val1 = getConstantInt(insn);
				int nextIdx = i + 1;
				if (nextIdx >= instructions.size())
					continue;
				AbstractInsnNode insn2 = instructions.get(nextIdx);

				if (isConstantInt(insn2, 0) || isConstantInt(insn2, 1)) {
					int val2 = getConstantInt(insn2);
					int opIdx = nextIdx + 1;
					if (opIdx >= instructions.size())
						continue;
					AbstractInsnNode cmpInsn = instructions.get(opIdx);

					Integer cmpResult = evaluateComparison(val1, val2, cmpInsn);
					if (cmpResult != null) {
						int jumpIdx = opIdx + 1;
						if (jumpIdx >= instructions.size())
							continue;
						AbstractInsnNode jumpInsn = instructions.get(jumpIdx);

						if (jumpInsn.getType() == AbstractInsnNode.JUMP_INSN) {
							JumpInsnNode jin = (JumpInsnNode) jumpInsn;
							boolean shouldJump = (cmpResult == 1);
							if (jin.getOpcode() == Opcodes.IFEQ)
								shouldJump = (cmpResult == 0);
							else if (jin.getOpcode() == Opcodes.IFNE)
								shouldJump = (cmpResult != 0);
							else if (jin.getOpcode() == Opcodes.IFLE)
								shouldJump = (cmpResult <= 0);
							else if (jin.getOpcode() == Opcodes.IFGE)
								shouldJump = (cmpResult >= 0);
							else if (jin.getOpcode() == Opcodes.IFGT)
								shouldJump = (cmpResult > 0);
							else if (jin.getOpcode() == Opcodes.IFLT)
								shouldJump = (cmpResult < 0);

							if (!shouldJump) {
								instructions.remove(insn);
								instructions.remove(insn2);
								instructions.remove(cmpInsn);
								instructions.remove(jumpInsn);
								modified = true;
								opaquePredicateCount++;
								i--;
								continue;
							} else {
								instructions.remove(insn);
								instructions.remove(insn2);
								instructions.remove(cmpInsn);
								instructions.set(jumpInsn, new JumpInsnNode(Opcodes.GOTO, jin.label));
								modified = true;
								opaquePredicateCount++;
								i--;
								continue;
							}
						}
					}
				}
			}

			if (insn.getOpcode() == Opcodes.ICONST_0 || insn.getOpcode() == Opcodes.ICONST_1) {
				int nextIdx = i + 1;
				if (nextIdx >= instructions.size())
					continue;
				AbstractInsnNode next = instructions.get(nextIdx);

				if (next.getOpcode() == Opcodes.IFNE || next.getOpcode() == Opcodes.IFEQ) {
					JumpInsnNode jin = (JumpInsnNode) next;
					int val = insn.getOpcode() - Opcodes.ICONST_0;
					boolean shouldJump = (next.getOpcode() == Opcodes.IFNE) ? (val != 0) : (val == 0);

					if (!shouldJump) {
						instructions.remove(insn);
						instructions.remove(next);
						modified = true;
						opaquePredicateCount++;
						i--;
					} else {
						instructions.remove(insn);
						instructions.set(next, new JumpInsnNode(Opcodes.GOTO, jin.label));
						modified = true;
						opaquePredicateCount++;
						i--;
					}
					continue;
				}
			}
		}

		return modified;
	}

	private boolean unflattenSwitchState(MethodNode mn) {
		InsnList instructions = mn.instructions;

		int stateVar = findStateVariable(mn);
		if (stateVar == -1)
			return false;

		AbstractInsnNode switchInsn = findSwitchOnVariable(mn, stateVar);
		if (switchInsn == null)
			return false;

		List<LabelNode> caseLabels = extractSwitchCases(switchInsn);
		if (caseLabels == null || caseLabels.size() < 2)
			return false;

		List<List<AbstractInsnNode>> linearBlocks = new ArrayList<>();
		Set<LabelNode> switchLabels = new HashSet<>(caseLabels);

		for (LabelNode caseLabel : caseLabels) {
			List<AbstractInsnNode> block = extractBlock(instructions, caseLabel, switchLabels, stateVar);
			if (block != null && !block.isEmpty()) {
				linearBlocks.add(block);
			}
		}

		if (linearBlocks.size() < 2)
			return false;

		InsnList newInstructions = new InsnList();
		Set<AbstractInsnNode> consumed = new HashSet<>();

		int switchIdx = instructions.indexOf(switchInsn);
		for (int i = 0; i < switchIdx; i++) {
			AbstractInsnNode insn = instructions.get(i);
			if (insn.getOpcode() != -1 || insn.getType() != AbstractInsnNode.LABEL) {
				newInstructions.add(insn);
				consumed.add(insn);
			} else if (!isLabelReferencedLater(instructions, (LabelNode) insn, i)) {
				newInstructions.add(insn);
				consumed.add(insn);
			}
		}

		for (List<AbstractInsnNode> block : linearBlocks) {
			for (AbstractInsnNode insn : block) {
				if (consumed.contains(insn))
					continue;
				if (insn.getOpcode() == Opcodes.GOTO)
					continue;
				if (isStateAssignment(insn, stateVar))
					continue;
				newInstructions.add(insn.clone(null));
				consumed.add(insn);
			}
		}

		for (int i = switchIdx; i < instructions.size(); i++) {
			AbstractInsnNode insn = instructions.get(i);
			if (consumed.contains(insn))
				continue;
			if (insn.getOpcode() == Opcodes.GOTO)
				continue;
			if (isStateAssignment(insn, stateVar))
				continue;
			newInstructions.add(insn.clone(null));
		}

		mn.instructions = newInstructions;
		flattenedCount++;
		return true;
	}

	private int findStateVariable(MethodNode mn) {
		InsnList instructions = mn.instructions;
		int stateVar = -1;

		for (int i = 0; i < instructions.size(); i++) {
			AbstractInsnNode insn = instructions.get(i);
			if (insn.getType() == AbstractInsnNode.VAR_INSN && insn.getOpcode() == Opcodes.ILOAD) {
				VarInsnNode vin = (VarInsnNode) insn;
				int nextIdx = i + 1;
				if (nextIdx < instructions.size()) {
					AbstractInsnNode next = instructions.get(nextIdx);
					if (next.getType() == AbstractInsnNode.TABLESWITCH_INSN
							|| next.getType() == AbstractInsnNode.LOOKUPSWITCH_INSN) {
						stateVar = vin.var;
						break;
					}
				}
			}
		}

		if (stateVar == -1) {
			for (int i = 0; i < instructions.size(); i++) {
				AbstractInsnNode insn = instructions.get(i);
				if (insn.getType() == AbstractInsnNode.IINC_INSN) {
					IincInsnNode iinc = (IincInsnNode) insn;
					if (Math.abs(iinc.incr) <= 5) {
						stateVar = iinc.var;
						break;
					}
				}
			}
		}

		return stateVar;
	}

	private AbstractInsnNode findSwitchOnVariable(MethodNode mn, int var) {
		InsnList instructions = mn.instructions;
		for (int i = 0; i < instructions.size() - 1; i++) {
			AbstractInsnNode insn = instructions.get(i);
			if (insn.getType() == AbstractInsnNode.VAR_INSN && insn.getOpcode() == Opcodes.ILOAD) {
				VarInsnNode vin = (VarInsnNode) insn;
				if (vin.var == var) {
					AbstractInsnNode next = instructions.get(i + 1);
					if (next.getType() == AbstractInsnNode.TABLESWITCH_INSN
							|| next.getType() == AbstractInsnNode.LOOKUPSWITCH_INSN) {
						return next;
					}
				}
			}
		}
		return null;
	}

	private List<LabelNode> extractSwitchCases(AbstractInsnNode switchInsn) {
		List<LabelNode> labels = new ArrayList<>();
		if (switchInsn.getType() == AbstractInsnNode.TABLESWITCH_INSN) {
			TableSwitchInsnNode tsn = (TableSwitchInsnNode) switchInsn;
			labels.add(tsn.dflt);
			for (Object o : tsn.labels) {
				LabelNode ln = (LabelNode) o;
				if (!labels.contains(ln))
					labels.add(ln);
			}
		} else if (switchInsn.getType() == AbstractInsnNode.LOOKUPSWITCH_INSN) {
			LookupSwitchInsnNode lsn = (LookupSwitchInsnNode) switchInsn;
			labels.add(lsn.dflt);
			for (Object o : lsn.labels) {
				LabelNode ln = (LabelNode) o;
				if (!labels.contains(ln))
					labels.add(ln);
			}
		}
		return labels;
	}

	private List<AbstractInsnNode> extractBlock(InsnList instructions, LabelNode start,
			Set<LabelNode> switchLabels, int stateVar) {
		List<AbstractInsnNode> block = new ArrayList<>();
		int startIdx = instructions.indexOf(start);
		if (startIdx < 0)
			return null;

		for (int i = startIdx; i < instructions.size(); i++) {
			AbstractInsnNode insn = instructions.get(i);

			if (insn.getOpcode() == Opcodes.GOTO) {
				JumpInsnNode jin = (JumpInsnNode) insn;
				if (switchLabels.contains(jin.label))
					break;
			}

			if (isStateAssignment(insn, stateVar))
				continue;

			if (insn.getOpcode() == Opcodes.ILOAD) {
				VarInsnNode vin = (VarInsnNode) insn;
				if (vin.var == stateVar) {
					int nextIdx = i + 1;
					if (nextIdx < instructions.size()) {
						AbstractInsnNode next = instructions.get(nextIdx);
						if (next.getType() == AbstractInsnNode.TABLESWITCH_INSN
								|| next.getType() == AbstractInsnNode.LOOKUPSWITCH_INSN)
							break;
					}
				}
			}

			block.add(insn);
		}

		return block;
	}

	private boolean isStateAssignment(AbstractInsnNode insn, int stateVar) {
		if (insn == null)
			return false;
		if (insn.getType() == AbstractInsnNode.VAR_INSN && insn.getOpcode() == Opcodes.ISTORE) {
			VarInsnNode vin = (VarInsnNode) insn;
			return vin.var == stateVar;
		}
		if (insn.getType() == AbstractInsnNode.IINC_INSN) {
			IincInsnNode iinc = (IincInsnNode) insn;
			return iinc.var == stateVar;
		}
		return false;
	}

	private boolean isLabelReferencedLater(InsnList instructions, LabelNode label, int fromIdx) {
		for (int i = fromIdx + 1; i < instructions.size(); i++) {
			AbstractInsnNode insn = instructions.get(i);
			if (insn.getType() == AbstractInsnNode.JUMP_INSN) {
				if (((JumpInsnNode) insn).label == label)
					return true;
			}
		}
		return false;
	}

	private boolean isConstantInt(AbstractInsnNode insn, int value) {
		if (insn == null)
			return false;
		int op = insn.getOpcode();
		if (value >= -1 && value <= 5 && op == Opcodes.ICONST_0 + value)
			return true;
		if (op == Opcodes.BIPUSH && ((IntInsnNode) insn).operand == value)
			return true;
		if (op == Opcodes.SIPUSH && ((IntInsnNode) insn).operand == value)
			return true;
		if (insn.getType() == AbstractInsnNode.LDC_INSN) {
			LdcInsnNode ldc = (LdcInsnNode) insn;
			return ldc.cst instanceof Integer && (Integer) ldc.cst == value;
		}
		return false;
	}

	private int getConstantInt(AbstractInsnNode insn) {
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
		return 0;
	}

	private Integer evaluateComparison(int a, int b, AbstractInsnNode cmpInsn) {
		if (cmpInsn == null)
			return null;
		int op = cmpInsn.getOpcode();
		switch (op) {
			case Opcodes.IF_ICMPEQ: return a == b ? 1 : 0;
			case Opcodes.IF_ICMPNE: return a != b ? 1 : 0;
			case Opcodes.IF_ICMPLT: return a < b ? 1 : 0;
			case Opcodes.IF_ICMPGE: return a >= b ? 1 : 0;
			case Opcodes.IF_ICMPGT: return a > b ? 1 : 0;
			case Opcodes.IF_ICMPLE: return a <= b ? 1 : 0;
			default: return null;
		}
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
