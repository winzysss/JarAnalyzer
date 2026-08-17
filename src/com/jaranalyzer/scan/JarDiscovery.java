package com.jaranalyzer.scan;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Finds every Java archive on the machine.
 *
 * <p>One walker per drive root, because the bottleneck is per-volume seek time
 * rather than CPU: two drives walked concurrently finish in roughly the time of
 * the slower one, while two threads on the same spindle just contend. Symlinks
 * are not followed — on Windows the junctions under a user profile
 * ({@code Documents and Settings}, {@code Application Data}) form cycles that
 * would otherwise make the walk never terminate.
 */
public final class JarDiscovery {

	public interface Listener {
		void onFound(File jar);

		void onProgress(long filesSeen, long jarsFound, String currentDirectory);
	}

	private static final int MAX_DEPTH = 40;

	private final ScanSettings settings;
	private final AtomicBoolean stop = new AtomicBoolean();
	private final AtomicLong filesSeen = new AtomicLong();
	private final AtomicLong jarsFound = new AtomicLong();

	/** Files that are not named like archives, awaiting a content probe. */
	private final java.util.Queue<File> disguisedCandidates =
			new java.util.concurrent.ConcurrentLinkedQueue<>();
	private final AtomicLong probed = new AtomicLong();

	public JarDiscovery(ScanSettings settings) {
		this.settings = settings;
	}

	public void requestStop() {
		stop.set(true);
	}

	public long filesSeen() {
		return filesSeen.get();
	}

	public long jarsFound() {
		return jarsFound.get();
	}

	// =====================================================================

	public List<File> run(Listener listener) {
		List<File> results = java.util.Collections.synchronizedList(new ArrayList<File>());

		// The Master File Table route first: it reads the volume index in a few
		// sequential passes instead of seeking through every directory, which is
		// the difference between seconds and minutes on a full disk. It needs
		// elevation and NTFS, so a failure here is expected and simply falls
		// through to the directory walk below for the volumes it could not do.
		java.util.Set<String> viaMft = new java.util.HashSet<>();
		if (settings.scanAllDrives && settings.useMftScan) {
			for (String letter : MftScanner.ntfsDriveLetters()) {
				if (stop.get()) break;

				// Collected per volume rather than pushed straight to the caller,
				// so the result can be checked before it is trusted. Paths from the
				// MFT are reconstructed from parent links; if that reconstruction is
				// wrong the paths simply will not exist, and silently returning
				// fewer JARs is a worse failure than being slow. A volume whose
				// paths mostly fail to resolve is handed back to the directory walk.
				final List<File> volumeHits = new ArrayList<>();
				final long[] reported = { 0 };

				boolean ok = MftScanner.scanVolume(letter, settings, new MftScanner.Listener() {
					@Override
					public void onFile(String fullPath) {
						if (stop.get()) return;
						reported[0]++;

						File f = new File(fullPath);
						long len = f.length();
						if (len <= 0) return;   // never existed, or deleted since
						if (settings.maxJarBytes > 0 && len > settings.maxJarBytes) return;
						volumeHits.add(f);
					}

					@Override
					public void onProgress(long recordsSeen, long matches) {
						filesSeen.set(recordsSeen);
						if (listener != null) {
							listener.onProgress(recordsSeen, jarsFound.get() + volumeHits.size(),
									letter + ":\\ (MFT)");
						}
					}
				});

				boolean trustworthy = ok && (reported[0] < 20
						|| volumeHits.size() >= reported[0] * 0.5);

				if (trustworthy) {
					viaMft.add(letter.toUpperCase(Locale.ROOT));
					for (File f : volumeHits) {
						results.add(f);
						jarsFound.incrementAndGet();
						if (listener != null) listener.onFound(f);
					}
				}
			}
		}

		List<Path> roots = resolveRoots();
		// Skip the walk for volumes the MFT already covered.
		roots.removeIf(p -> {
			String s = p.toString();
			return s.length() >= 2 && s.charAt(1) == ':'
					&& viaMft.contains(String.valueOf(s.charAt(0)).toUpperCase(Locale.ROOT));
		});

		if (roots.isEmpty()) return results;
		ExecutorService pool = Executors.newFixedThreadPool(
				Math.min(roots.size(), Math.max(2, Runtime.getRuntime().availableProcessors())),
				r -> {
					Thread t = new Thread(r, "wjf-discovery");
					t.setDaemon(true);
					return t;
				});

		List<Future<?>> futures = new ArrayList<>();
		for (Path root : roots) {
			futures.add(pool.submit(() -> walk(root, results, listener)));
		}

		for (Future<?> f : futures) {
			try {
				f.get();
			} catch (Exception ignored) {
				// A drive that disappears mid-walk (removable media) must not take
				// the whole discovery pass down with it.
			}
		}

		pool.shutdown();
		try {
			pool.awaitTermination(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		probeDisguised(results, listener);
		return results;
	}

	/**
	 * Second discovery pass: files that are not named like archives but are one.
	 *
	 * <p>Run after the walk rather than during it. The walk is bound by directory
	 * metadata and should not stall on per-file opens; the probes are independent
	 * of each other and saturate the disk queue far better in a batch.
	 */
	private void probeDisguised(List<File> results, Listener listener) {
		if (!settings.detectDisguisedArchives || disguisedCandidates.isEmpty()) return;
		if (stop.get()) return;

		// Sized for I/O, not for CPU. Each probe opens a file, reads four bytes and
		// closes it — the thread spends nearly all of that waiting on the kernel,
		// so one thread per core would leave the disk queue mostly idle. Overridable
		// via a system property.
		int threads = Integer.getInteger("wjf.sniffThreads",
				Math.min(64, Math.max(8, Runtime.getRuntime().availableProcessors() * 4)));
		ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
			Thread t = new Thread(r, "wjf-sniff");
			t.setDaemon(true);
			return t;
		});

		final long total = disguisedCandidates.size();
		List<Future<?>> futures = new ArrayList<>();

		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				File f;
				while ((f = disguisedCandidates.poll()) != null) {
					if (stop.get()) return;
					long n = probed.incrementAndGet();

					if (listener != null && (n & 0x1FFF) == 0) {
						listener.onProgress(filesSeen.get(), jarsFound.get(),
								Msg.t("wjf.disc.probing", n, total));
					}

					// The single entry point, so the "not wearing an archive
					// extension" rule is always applied rather than the two stages
					// being called separately.
					if (!ArchiveSniffer.isDisguisedJavaArchive(f)) continue;

					results.add(f);
					jarsFound.incrementAndGet();
					if (listener != null) listener.onFound(f);
				}
			}));
		}

		for (Future<?> f : futures) {
			try {
				f.get();
			} catch (Exception ignored) {
				// One worker failing must not abandon the rest of the queue.
			}
		}
		pool.shutdown();
	}

	/** Number of non-archive-named files queued for a content probe. */
	public long disguisedCandidateCount() {
		return disguisedCandidates.size();
	}

	private List<Path> resolveRoots() {
		List<Path> out = new ArrayList<>();

		if (settings.scanAllDrives) {
			File[] fsRoots = File.listRoots();
			if (fsRoots != null) {
				for (File r : fsRoots) {
					// listRoots() reports mapped drives that are offline; touching
					// one of those blocks for the full network timeout.
					if (r.canRead()) out.add(r.toPath());
				}
			}
		}

		for (String s : settings.roots) {
			if (s == null || s.trim().isEmpty()) continue;
			try {
				Path p = Paths.get(s.trim());
				if (Files.isDirectory(p)) out.add(p);
			} catch (Exception ignored) {
				// A malformed path in the settings file is skipped, not fatal.
			}
		}

		return out;
	}

	// ---- walking ----------------------------------------------------------

	private void walk(Path root, List<File> results, Listener listener) {
		try {
			Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
					new SimpleFileVisitor<Path>() {

				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					if (stop.get()) return FileVisitResult.TERMINATE;
					if (attrs.isSymbolicLink()) return FileVisitResult.SKIP_SUBTREE;

					if (listener != null && (filesSeen.get() & 0x3FF) == 0) {
						listener.onProgress(filesSeen.get(), jarsFound.get(), dir.toString());
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (stop.get()) return FileVisitResult.TERMINATE;
					if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;

					filesSeen.incrementAndGet();

					String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
					long size = attrs.size();

					if (settings.maxJarBytes > 0 && size > settings.maxJarBytes) {
						return FileVisitResult.CONTINUE;
					}

					if (!settings.matchesExtension(name)) {
						// Not named like an archive. Queue it for a content probe
						// rather than probing here — the walk should not block on
						// per-file reads, and the probes parallelise well.
						//
						// Files that name a known archive format are excluded: a
						// .jmod is not in disguise, it is simply a format the scan
						// settings leave alone, and a JDK ships ~150 of them.
						if (settings.detectDisguisedArchives
								&& size >= settings.disguisedMinBytes
								&& !ArchiveSniffer.hasArchiveExtension(name)) {
							disguisedCandidates.add(file.toFile());
						}
						return FileVisitResult.CONTINUE;
					}

					File f = file.toFile();
					results.add(f);
					jarsFound.incrementAndGet();
					if (listener != null) listener.onFound(f);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) {
					// Permission denied on a system directory is expected and common;
					// the sweep continues rather than aborting the drive.
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
					return stop.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
				}
			});
		} catch (Throwable ignored) {
			// Whole-root failure (drive removed, access revoked): other roots go on.
		}
	}

}
