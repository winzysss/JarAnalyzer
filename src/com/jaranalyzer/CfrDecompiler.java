package com.jaranalyzer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;

public final class CfrDecompiler {

	private CfrDecompiler() {
	}

	public static String decompileFromJar(JarFile jarFile, String internalClassName, DecompilerConfig config) {
		JarClassFileSource source = new JarClassFileSource(jarFile);
		return doDecompile(source, internalClassName, config);
	}

	/**
	 * Decompiles many classes through a single driver.
	 *
	 * <p>Building one {@link CfrDriver} per class makes CFR rebuild its type
	 * system and caches for every one of them; batching many classes into one
	 * driver reuses that shared type information, and the saving grows with class
	 * count.
	 *
	 * @return the concatenated source, or null when nothing was produced
	 */
	public static String decompileBatchFromJar(JarFile jarFile, List<String> internalClassNames,
			DecompilerConfig config) {
		if (internalClassNames == null || internalClassNames.isEmpty()) return null;
		StringOutputSinkFactory sink = new StringOutputSinkFactory();
		CfrDriver driver = new CfrDriver.Builder()
				.withOptions(buildOptions(config))
				.withClassFileSource(new JarClassFileSource(jarFile))
				.withOutputSink(sink)
				.build();
		driver.analyse(new java.util.ArrayList<>(internalClassNames));
		return sink.getOutput();
	}

	public static String decompileFromBytes(byte[] classBytes, String internalClassName, DecompilerConfig config) {
		BytesClassFileSource source = new BytesClassFileSource(classBytes, internalClassName);
		return doDecompile(source, internalClassName, config);
	}

	public static String decompileFromClassFile(File classFile, DecompilerConfig config) {
		String path = classFile.getAbsolutePath();
		StringOutputSinkFactory sink = new StringOutputSinkFactory();
		CfrDriver driver = new CfrDriver.Builder()
				.withOptions(buildOptions(config))
				.withOutputSink(sink)
				.build();
		driver.analyse(Collections.singletonList(path));
		return sink.getOutput();
	}

	private static String doDecompile(ClassFileSource source, String internalClassName, DecompilerConfig config) {
		StringOutputSinkFactory sink = new StringOutputSinkFactory();
		CfrDriver driver = new CfrDriver.Builder()
				.withOptions(buildOptions(config))
				.withClassFileSource(source)
				.withOutputSink(sink)
				.build();
		driver.analyse(Collections.singletonList(internalClassName));
		return sink.getOutput();
	}

	private static Map<String, String> buildOptions(DecompilerConfig config) {
		Map<String, String> options = new HashMap<>();
		options.put("silent", "true");
		options.put("comments", "false");
		options.put("decodefinally", "true");
		options.put("decodeenumswitch", "true");
		options.put("decodeinnerclassrelationships", String.valueOf(!config.getExcludeNestedTypes()));
		options.put("showhidden", String.valueOf(config.getShowSyntheticMembers()));
		options.put("clobber", "true");
		return options;
	}

	private static class StringOutputSinkFactory implements OutputSinkFactory {
		private final StringBuilder sb = new StringBuilder(8192);

		@Override
		public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
			return Collections.singletonList(SinkClass.STRING);
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
			if (sinkType == SinkType.JAVA) {
				return sinkable -> {
					if (sinkable instanceof String) {
						sb.append((String) sinkable);
					} else if (sinkable instanceof SinkReturns.Decompiled) {
						sb.append(((SinkReturns.Decompiled) sinkable).getJava());
					}
				};
			}
			return ignore -> {};
		}

		String getOutput() {
			String result = sb.toString().trim();
			if (result.isEmpty()) {
				return null;
			}
			return result;
		}
	}

	private static class JarClassFileSource implements ClassFileSource {
		private final JarFile jarFile;

		JarClassFileSource(JarFile jarFile) {
			this.jarFile = jarFile;
		}

		@Override
		public void informAnalysisRelativePathDetail(String usePath, String classFilePath) {
		}

		@Override
		public Collection<String> addJar(String jarPath) {
			return Collections.emptyList();
		}

		@Override
		public String getPossiblyRenamedPath(String path) {
			return path;
		}

		@Override
		public Pair<byte[], String> getClassFileContent(String path) throws IOException {
			String entryName = path;
			if (!entryName.endsWith(".class")) {
				entryName = entryName + ".class";
			}
			JarEntry entry = jarFile.getJarEntry(entryName);
			if (entry == null) {
				entry = jarFile.getJarEntry(path);
			}
			if (entry == null) {
				throw new IOException("Entry not found: " + path);
			}
			try (InputStream in = jarFile.getInputStream(entry)) {
				java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
				byte[] buf = new byte[4096];
				int len;
				while ((len = in.read(buf)) > 0) {
					baos.write(buf, 0, len);
				}
				return new Pair<>(baos.toByteArray(), path);
			}
		}
	}

	private static class BytesClassFileSource implements ClassFileSource {
		private final byte[] classBytes;
		private final String internalClassName;

		BytesClassFileSource(byte[] classBytes, String internalClassName) {
			this.classBytes = classBytes;
			this.internalClassName = internalClassName;
		}

		@Override
		public void informAnalysisRelativePathDetail(String usePath, String classFilePath) {
		}

		@Override
		public Collection<String> addJar(String jarPath) {
			return Collections.emptyList();
		}

		@Override
		public String getPossiblyRenamedPath(String path) {
			return path;
		}

		@Override
		public Pair<byte[], String> getClassFileContent(String path) throws IOException {
			String normalized = path;
			if (normalized.endsWith(".class")) {
				normalized = normalized.substring(0, normalized.length() - ".class".length());
			}
			if (normalized.equals(internalClassName)) {
				return new Pair<>(classBytes, path);
			}
			throw new IOException("Class not found: " + path);
		}
	}
}
