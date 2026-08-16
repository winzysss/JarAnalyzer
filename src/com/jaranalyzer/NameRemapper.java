package com.jaranalyzer;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

public class NameRemapper {

	public interface ProgressCallback {
		void onProgress(String message);
	}

	private ProgressCallback callback;
	private Map<String, String> classMappings = new HashMap<>();
	private Map<String, String> methodMappings = new HashMap<>();
	private Map<String, String> fieldMappings = new HashMap<>();
	private int classCounter = 0;
	private int methodCounter = 0;
	private int fieldCounter = 0;

	private int stableId(String key) {
		int h = key.hashCode();
		return Math.abs(h) % 100000;
	}

	public NameRemapper(ProgressCallback callback) {
		this.callback = callback;
	}

	public void remap(JarFile jarFile, File outputFile) throws Exception {
		analyzeAndBuildMappings(jarFile);
		applyMappings(jarFile, outputFile);
	}

	private void analyzeAndBuildMappings(JarFile jarFile) {
		Enumeration<JarEntry> entries = jarFile.entries();

		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();
			if (!entry.getName().endsWith(".class"))
				continue;

			try {
				ClassReader cr = new ClassReader(jarFile.getInputStream(entry));
				ClassNode cn = new ClassNode();
				cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

				String className = cn.name;
				String simpleName = className;
				if (simpleName.contains("/"))
					simpleName = simpleName.substring(simpleName.lastIndexOf('/') + 1);

				if (isObfuscatedName(simpleName) && !isPreservedName(simpleName)) {
					String newName = generateClassName(cn);
					classMappings.put(className, newName);
				}

				for (Object o : cn.methods) {
					MethodNode mn = (MethodNode) o;
					if (isObfuscatedName(mn.name) && !isPreservedMethod(mn.name)) {
						String key = className + "." + mn.name + mn.desc;
						String newName = generateMethodName(mn, cn);
						methodMappings.put(key, newName);
					}
				}

				for (Object o : cn.fields) {
					FieldNode fn = (FieldNode) o;
					if (isObfuscatedName(fn.name) && !isPreservedName(fn.name)) {
						String key = className + "." + fn.name;
						String newName = generateFieldName(fn, className);
						fieldMappings.put(key, newName);
					}
				}
			} catch (Exception e) {
			}
		}

		if (callback != null) {
			callback.onProgress("Name remapping: " + classMappings.size() + " classes, "
					+ methodMappings.size() + " methods, " + fieldMappings.size() + " fields");
		}
	}

	private boolean isObfuscatedName(String name) {
		if (name == null || name.isEmpty()) return false;
		if (name.length() > 3) return false;
		if (name.equals("<init>") || name.equals("<clinit>")) return false;
		for (char c : name.toCharArray()) {
			if (!Character.isLetterOrDigit(c) && c != '_' && c != '$') return false;
		}
		return name.length() <= 2;
	}

	private boolean isPreservedName(String name) {
		return name.equals("main") || name.equals("toString") || name.equals("equals")
				|| name.equals("hashCode") || name.equals("clone") || name.equals("getClass")
				|| name.equals("notify") || name.equals("notifyAll") || name.equals("wait")
				|| name.equals("finalize") || name.equals("valueOf") || name.equals("values")
				|| name.equals("ordinal") || name.equals("name") || name.equals("compareTo")
				|| name.equals("run") || name.equals("start") || name.equals("stop")
				|| name.equals("init") || name.equals("close") || name.equals("open")
				|| name.equals("read") || name.equals("write") || name.equals("size")
				|| name.equals("get") || name.equals("set") || name.equals("add")
				|| name.equals("remove") || name.equals("put") || name.equals("next")
				|| name.equals("hasNext") || name.equals("iterator");
	}

	private boolean isPreservedMethod(String name) {
		return isPreservedName(name) || name.equals("<init>") || name.equals("<clinit>");
	}

	private String generateClassName(ClassNode cn) {
		int id = stableId(cn.name);
		String prefix = "Class";

		if (cn.interfaces != null && !cn.interfaces.isEmpty()) {
			String iface = (String) cn.interfaces.get(0);
			String simple = iface;
			if (simple.contains("/"))
				simple = simple.substring(simple.lastIndexOf('/') + 1);
			if (!isObfuscatedName(simple)) {
				prefix = simple;
			}
		}

		if (cn.superName != null && !cn.superName.equals("java/lang/Object")) {
			String sup = cn.superName;
			if (sup.contains("/"))
				sup = sup.substring(sup.lastIndexOf('/') + 1);
			if (!isObfuscatedName(sup)) {
				prefix = sup;
			}
		}

		if (prefix.length() > 20) {
			prefix = prefix.substring(0, 20);
		}
		return "deobf/" + prefix + id;
	}

	private String generateMethodName(MethodNode mn, ClassNode cn) {
		int id = stableId(cn.name + "." + mn.name + mn.desc);

		if (mn.desc != null) {
			String desc = mn.desc;
			if (desc.contains("Player") || desc.contains("EntityPlayer")) {
				return "handlePlayer" + id;
			}
			if (desc.contains("Entity")) {
				return "handleEntity" + id;
			}
			if (desc.contains("World")) {
				return "handleWorld" + id;
			}
			if (desc.contains("Packet")) {
				return "handlePacket" + id;
			}
			if (desc.contains("Event")) {
				return "onEvent" + id;
			}
			if (desc.contains("ItemStack")) {
				return "handleItem" + id;
			}
			if (desc.contains("Block")) {
				return "handleBlock" + id;
			}
			if (desc.contains("Chat") || desc.contains("Message")) {
				return "handleChat" + id;
			}
		}

		if ((mn.access & Opcodes.ACC_PUBLIC) != 0 && (mn.access & Opcodes.ACC_STATIC) == 0) {
			return "publicMethod" + id;
		}
		if ((mn.access & Opcodes.ACC_PRIVATE) != 0) {
			return "privateMethod" + id;
		}
		if ((mn.access & Opcodes.ACC_PROTECTED) != 0) {
			return "protectedMethod" + id;
		}
		return "method" + id;
	}

	private String generateFieldName(FieldNode fn, String className) {
		int id = stableId(className + "." + fn.name + fn.desc);

		String typePrefix = "field";
		if (fn.desc != null) {
			if (fn.desc.equals("Z")) typePrefix = "flag";
			else if (fn.desc.equals("I")) typePrefix = "intField";
			else if (fn.desc.equals("J")) typePrefix = "longField";
			else if (fn.desc.equals("D")) typePrefix = "doubleField";
			else if (fn.desc.equals("F")) typePrefix = "floatField";
			else if (fn.desc.equals("Ljava/lang/String;")) typePrefix = "stringField";
			else if (fn.desc.startsWith("Ljava/util/List")) typePrefix = "listField";
			else if (fn.desc.startsWith("Ljava/util/Map")) typePrefix = "mapField";
			else if (fn.desc.startsWith("Ljava/util/Set")) typePrefix = "setField";
			else if (fn.desc.startsWith("L")) typePrefix = "objectField";
		}

		return typePrefix + id;
	}

	private void applyMappings(JarFile jarFile, File outputFile) throws Exception {
		Map<String, String> allMappings = new HashMap<>();
		allMappings.putAll(classMappings);
		for (Map.Entry<String, String> e : methodMappings.entrySet()) {
			allMappings.put(e.getKey(), e.getValue());
		}
		for (Map.Entry<String, String> e : fieldMappings.entrySet()) {
			allMappings.put(e.getKey(), e.getValue());
		}

		Remapper remapper = new SimpleRemapper(allMappings);

		try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputFile))) {
			Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				if (!entry.getName().endsWith(".class")) {
					if (!entry.isDirectory()) {
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
					continue;
				}

				try {
					ClassReader cr = new ClassReader(jarFile.getInputStream(entry));
					ClassNode cn = new ClassNode();
					cr.accept(cn, ClassReader.SKIP_DEBUG);

					ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
					ClassRemapper remappedCn = new ClassRemapper(cw, remapper);
					cn.accept(remappedCn);

					String outputName = entry.getName();
					if (classMappings.containsKey(cn.name)) {
						outputName = classMappings.get(cn.name) + ".class";
					}

					ZipEntry ze = new ZipEntry(outputName);
					zos.putNextEntry(ze);
					zos.write(cw.toByteArray());
					zos.closeEntry();
				} catch (Exception e) {
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

		if (callback != null) {
			callback.onProgress("Name remapping complete");
		}
	}
}
