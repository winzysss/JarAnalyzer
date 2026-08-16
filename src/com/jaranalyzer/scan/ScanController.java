package com.jaranalyzer.scan;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a whole scan: discover every JAR, then analyse them in parallel.
 *
 * <p>Discovery and analysis overlap deliberately. Walking a multi-terabyte set
 * of drives can take minutes, and there is no reason for the CPU to sit idle
 * during it — JARs are queued to the analysis pool the moment they are found, so
 * the first results appear seconds after the scan starts instead of after the
 * walk completes.
 */
public class ScanController {

	public interface Listener {
		void onPhase(String phase);

		void onDiscovery(long filesSeen, long jarsFound, String currentDir);

		void onJarStarted(File jar);

		void onJarAnalyzed(JarAnalysis analysis, int done, int total);

		void onFinished(Summary summary);
	}

	public static class Summary {
		public int totalFound;
		public int analyzed;
		public int failed;
		public long elapsedMillis;
		public long filesSeen;
		public final Map<Verdict, Integer> byVerdict = new EnumMap<>(Verdict.class);
		public final List<JarAnalysis> results = new ArrayList<>();

		public int count(Verdict v) {
			Integer n = byVerdict.get(v);
			return n == null ? 0 : n;
		}

		public int attentionCount() {
			return count(Verdict.SUSPICIOUS) + count(Verdict.DETECTED)
					+ count(Verdict.CRITICAL) + count(Verdict.UNREADABLE);
		}
	}

	private final ScanSettings settings;
	private final Blacklist blacklist;
	private final AtomicBoolean stopping = new AtomicBoolean();

	private volatile JarDiscovery discovery;
	private volatile ExecutorService pool;
	private volatile Thread watchdog;

	/** Worker thread -> when it started its current JAR, for the hang watchdog. */
	private final Map<Thread, long[]> inFlight = new ConcurrentHashMap<>();

	public ScanController(ScanSettings settings, Blacklist blacklist) {
		this.settings = settings;
		this.blacklist = blacklist;
	}

	public void requestStop() {
		stopping.set(true);
		JarDiscovery d = discovery;
		if (d != null) d.requestStop();
		ExecutorService p = pool;
		if (p != null) p.shutdownNow();
	}

	public boolean isStopping() {
		return stopping.get();
	}

	// =====================================================================

	/** Analyses an explicit list of files, skipping discovery. */
	public Summary runOn(List<File> jars, Listener listener) {
		long started = System.currentTimeMillis();
		Summary summary = new Summary();
		summary.totalFound = jars.size();

		listener.onPhase("analyze");
		analyzeAll(jars, summary, listener, jars.size());

		summary.elapsedMillis = System.currentTimeMillis() - started;
		listener.onFinished(summary);
		return summary;
	}

	/** Full sweep: discover, then analyse. */
	public Summary run(Listener listener) {
		long started = System.currentTimeMillis();
		final Summary summary = new Summary();

		// ---- discovery, feeding the analysis queue as it goes ----------
		listener.onPhase("discover");

		final LinkedBlockingQueue<File> queue = new LinkedBlockingQueue<>();
		final AtomicBoolean discoveryDone = new AtomicBoolean();
		final AtomicInteger totalFound = new AtomicInteger();

		discovery = new JarDiscovery(settings);
		final JarDiscovery disc = discovery;

		Thread discoveryThread = new Thread(() -> {
			try {
				disc.run(new JarDiscovery.Listener() {
					@Override
					public void onFound(File jar) {
						totalFound.incrementAndGet();
						queue.offer(jar);
					}

					@Override
					public void onProgress(long files, long jarsFound, String dir) {
						listener.onDiscovery(files, jarsFound, dir);
					}
				});
			} finally {
				discoveryDone.set(true);
			}
		}, "wjf-discovery-main");
		discoveryThread.setDaemon(true);
		discoveryThread.start();

		// ---- analysis --------------------------------------------------
		listener.onPhase("analyze");

		int workers = settings.effectiveWorkers();
		pool = Executors.newFixedThreadPool(workers, r -> {
			Thread t = new Thread(r, "wjf-analyze");
			t.setDaemon(true);
			return t;
		});
		startWatchdog();

		final JarAnalyzer analyzer = new JarAnalyzer(settings, blacklist);
		final AtomicInteger done = new AtomicInteger();
		final AtomicInteger failed = new AtomicInteger();
		final List<JarAnalysis> results = Collections.synchronizedList(new ArrayList<JarAnalysis>());
		final CountDownLatch workersDone = new CountDownLatch(workers);

		for (int i = 0; i < workers; i++) {
			pool.submit(() -> {
				try {
					while (!stopping.get()) {
						File jar = queue.poll(250, TimeUnit.MILLISECONDS);
						if (jar == null) {
							if (discoveryDone.get() && queue.isEmpty()) break;
							continue;
						}
						processOne(analyzer, jar, results, done, failed, totalFound, listener);
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					workersDone.countDown();
				}
			});
		}

		try {
			discoveryThread.join();
			workersDone.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		stopWatchdog();
		pool.shutdownNow();

		summary.totalFound = totalFound.get();
		summary.analyzed = done.get();
		summary.failed = failed.get();
		summary.filesSeen = disc.filesSeen();
		summary.results.addAll(results);
		tally(summary);
		summary.elapsedMillis = System.currentTimeMillis() - started;

		listener.onFinished(summary);
		return summary;
	}

	// ---- shared analysis path ---------------------------------------------

	private void analyzeAll(List<File> jars, Summary summary, Listener listener, int total) {
		int workers = settings.effectiveWorkers();
		pool = Executors.newFixedThreadPool(workers, r -> {
			Thread t = new Thread(r, "wjf-analyze");
			t.setDaemon(true);
			return t;
		});
		startWatchdog();

		JarAnalyzer analyzer = new JarAnalyzer(settings, blacklist);
		AtomicInteger done = new AtomicInteger();
		AtomicInteger failed = new AtomicInteger();
		List<JarAnalysis> results = Collections.synchronizedList(new ArrayList<JarAnalysis>());
		AtomicInteger totalRef = new AtomicInteger(total);
		CountDownLatch latch = new CountDownLatch(jars.size());

		for (File jar : jars) {
			pool.submit(() -> {
				try {
					if (!stopping.get()) {
						processOne(analyzer, jar, results, done, failed, totalRef, listener);
					}
				} finally {
					latch.countDown();
				}
			});
		}

		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		stopWatchdog();
		pool.shutdownNow();

		summary.analyzed = done.get();
		summary.failed = failed.get();
		summary.results.addAll(results);
		tally(summary);
	}

	private void processOne(JarAnalyzer analyzer, File jar, List<JarAnalysis> results,
			AtomicInteger done, AtomicInteger failed, AtomicInteger total, Listener listener) {

		listener.onJarStarted(jar);
		inFlight.put(Thread.currentThread(), new long[] { System.currentTimeMillis() });
		try {
			JarAnalysis a = analyzer.analyze(jar);
			results.add(a);
			listener.onJarAnalyzed(a, done.incrementAndGet(), total.get());
		} catch (Throwable t) {
			failed.incrementAndGet();
			// A crash on one archive must not end the sweep; record it as an
			// unreadable result so the operator still sees the file.
			JarAnalysis a = new JarAnalysis(jar);
			a.setDecompileOutcome(DecompileOutcome.UNREADABLE);
			a.setDecompileError(t.getClass().getSimpleName() + ": " + t.getMessage());
			a.setVerdict(Verdict.UNREADABLE);
			results.add(a);
			listener.onJarAnalyzed(a, done.incrementAndGet(), total.get());
		} finally {
			inFlight.remove(Thread.currentThread());
			// The interrupt flag may be set by the watchdog; clear it so the worker
			// can pick up the next JAR.
			Thread.interrupted();
		}
	}

	private void tally(Summary s) {
		for (JarAnalysis a : s.results) {
			s.byVerdict.merge(a.getVerdict(), 1, Integer::sum);
		}
	}

	// ---- hang watchdog -----------------------------------------------------

	/**
	 * Interrupts a worker that has spent far longer on one archive than its
	 * budget allows.
	 *
	 * <p>The decompiler checks the deadline between classes, which covers the
	 * normal case of many small classes. It cannot cover a single class that sends
	 * the decompiler into a pathological loop — a real hazard, since bytecode
	 * crafted to do exactly that is a known anti-analysis trick. The watchdog is
	 * the backstop for that case.
	 */
	private void startWatchdog() {
		final long limitMillis = settings.perJarTimeoutSeconds * 1000L * 2;
		watchdog = new Thread(() -> {
			while (!Thread.currentThread().isInterrupted()) {
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					return;
				}
				long now = System.currentTimeMillis();
				for (Map.Entry<Thread, long[]> e : inFlight.entrySet()) {
					if (now - e.getValue()[0] > limitMillis) {
						e.getKey().interrupt();
					}
				}
			}
		}, "wjf-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
	}

	private void stopWatchdog() {
		Thread w = watchdog;
		if (w != null) {
			w.interrupt();
			watchdog = null;
		}
		inFlight.clear();
	}
}
