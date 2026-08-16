package com.jaranalyzer.scan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Aho-Corasick automaton: finds every blacklist literal in one pass over the text.
 *
 * <p>The alternative — a single fused regex of a few hundred alternatives — is
 * far slower, because {@code java.util.regex} does not compile an alternation
 * into a state machine: it backtracks, trying each branch at each input position,
 * so the cost is text length times pattern count.
 *
 * <p>Aho-Corasick walks the input once and follows failure links, so the cost is
 * the text length and nothing else: adding another thousand terms to the
 * blacklist does not make a scan slower. That property is the point — the whole
 * design invites the operator to keep adding terms.
 *
 * <p>Matching is done case-folded; entries that asked for case sensitivity are
 * verified against the original text when they hit, as are word boundaries.
 */
final class LiteralMatcher {

	/** ASCII-only transition table. Patterns outside it fall back to regex. */
	private static final int ALPHABET = 128;

	private int[][] next = new int[64][];
	private int[] fail = new int[64];
	/** Head of the output chain for each node; -1 when the node emits nothing. */
	private int[] outputHead = new int[64];
	private int nodeCount = 1;

	// Output chain: parallel arrays, linked by outputNext.
	private final List<BlacklistEntry> outEntry = new ArrayList<>();
	private final List<Integer> outLength = new ArrayList<>();
	private final List<Integer> outNext = new ArrayList<>();

	private boolean built;

	LiteralMatcher() {
		next[0] = newRow();
		Arrays.fill(fail, 0);
		Arrays.fill(outputHead, -1);
	}

	private static int[] newRow() {
		int[] row = new int[ALPHABET];
		Arrays.fill(row, -1);
		return row;
	}

	private void ensureCapacity(int n) {
		if (n < next.length) return;
		int cap = Math.max(n + 1, next.length * 2);
		next = Arrays.copyOf(next, cap);
		fail = Arrays.copyOf(fail, cap);
		int[] oh = Arrays.copyOf(outputHead, cap);
		for (int i = outputHead.length; i < cap; i++) oh[i] = -1;
		outputHead = oh;
	}

	/** True when the pattern can be represented in this automaton. */
	static boolean isSupported(String pattern) {
		if (pattern == null || pattern.isEmpty()) return false;
		for (int i = 0; i < pattern.length(); i++) {
			if (pattern.charAt(i) >= ALPHABET) return false;
		}
		return true;
	}

	void add(String pattern, BlacklistEntry entry) {
		if (built) throw new IllegalStateException("already built");
		String p = pattern.toLowerCase(Locale.ROOT);

		int node = 0;
		for (int i = 0; i < p.length(); i++) {
			int c = p.charAt(i);
			if (c >= ALPHABET) return;
			if (next[node][c] == -1) {
				ensureCapacity(nodeCount);
				next[nodeCount] = newRow();
				fail[nodeCount] = 0;
				outputHead[nodeCount] = -1;
				next[node][c] = nodeCount++;
			}
			node = next[node][c];
		}

		outEntry.add(entry);
		outLength.add(p.length());
		outNext.add(outputHead[node]);
		outputHead[node] = outEntry.size() - 1;
	}

	/** Builds failure links. Must be called before {@link #scan}. */
	void build() {
		if (built) return;
		built = true;

		Deque<Integer> queue = new ArrayDeque<>();
		int[] root = next[0];
		for (int c = 0; c < ALPHABET; c++) {
			int child = root[c];
			if (child == -1) {
				root[c] = 0;
			} else {
				fail[child] = 0;
				queue.add(child);
			}
		}

		while (!queue.isEmpty()) {
			int node = queue.poll();
			int[] row = next[node];

			// Merge the failure node's outputs so a scan never has to walk the
			// failure chain looking for shorter matches.
			int f = fail[node];
			if (outputHead[node] == -1) {
				outputHead[node] = outputHead[f];
			} else if (outputHead[f] != -1) {
				int tail = outputHead[node];
				while (outNext.get(tail) != -1) tail = outNext.get(tail);
				outNext.set(tail, outputHead[f]);
			}

			for (int c = 0; c < ALPHABET; c++) {
				int child = row[c];
				if (child == -1) {
					row[c] = next[fail[node]][c];
				} else {
					fail[child] = next[fail[node]][c];
					queue.add(child);
				}
			}
		}
	}

	interface Sink {
		/** @return false to stop scanning this text */
		boolean onMatch(BlacklistEntry entry, int start, int end);
	}

	/**
	 * Walks the text once, reporting every literal that ends at each position.
	 *
	 * <p>Word-boundary and case-sensitivity checks happen here rather than in the
	 * automaton: both depend on the original text, and folding them into the trie
	 * would mean a separate automaton per combination of flags.
	 */
	void scan(CharSequence text, BlacklistEntry.ScanSurface surface, Sink sink) {
		if (!built) build();
		if (outEntry.isEmpty()) return;

		int node = 0;
		int n = text.length();

		for (int i = 0; i < n; i++) {
			char raw = text.charAt(i);
			int c = raw < ALPHABET
					? (raw >= 'A' && raw <= 'Z' ? raw + 32 : raw)
					: -1;

			if (c < 0) {
				// A character the automaton cannot represent breaks any partial
				// match, exactly as a mismatch would.
				node = 0;
				continue;
			}

			node = next[node][c];
			if (node == 0) continue;

			for (int o = outputHead[node]; o != -1; o = outNext.get(o)) {
				BlacklistEntry entry = outEntry.get(o);
				if (!entry.appliesTo(surface)) continue;

				int len = outLength.get(o);
				int start = i - len + 1;
				int end = i + 1;

				if (entry.getKind() == MatchKind.WORD && !isWordBounded(text, start, end)) {
					continue;
				}
				if (entry.isCaseSensitive() && !matchesExactly(text, start, entry.getPattern())) {
					continue;
				}
				if (!sink.onMatch(entry, start, end)) return;
			}
		}
	}

	/** Java identifiers may contain '_' and '$', so those count as inside a word. */
	private static boolean isWordBounded(CharSequence text, int start, int end) {
		if (start > 0 && isWordChar(text.charAt(start - 1))) return false;
		if (end < text.length() && isWordChar(text.charAt(end))) return false;
		return true;
	}

	private static boolean isWordChar(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
				|| (c >= '0' && c <= '9') || c == '_' || c == '$';
	}

	private static boolean matchesExactly(CharSequence text, int start, String pattern) {
		if (start + pattern.length() > text.length()) return false;
		for (int i = 0; i < pattern.length(); i++) {
			if (text.charAt(start + i) != pattern.charAt(i)) return false;
		}
		return true;
	}

	int patternCount() {
		return outEntry.size();
	}
}
