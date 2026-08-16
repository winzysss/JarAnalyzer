package com.jaranalyzer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HexAnalyzer {

	public static class AnalysisResult {
		public String magicNumber;
		public String fileFormat;
		public double entropy;
		public String entropyLevel;
		public byte[] xorKey;
		public int xorKeyLength;
		public List<String> decryptedStrings;
		public List<PatternFinding> patterns;
		public List<OffsetFinding> offsets;
		public String summary;

		public AnalysisResult() {
			decryptedStrings = new ArrayList<>();
			patterns = new ArrayList<>();
			offsets = new ArrayList<>();
		}
	}

	public static class PatternFinding {
		public final byte[] pattern;
		public final int count;
		public final int firstOffset;

		public PatternFinding(byte[] pattern, int count, int firstOffset) {
			this.pattern = pattern;
			this.count = count;
			this.firstOffset = firstOffset;
		}
	}

	public static class OffsetFinding {
		public final int offset;
		public final int value;
		public final int stride;
		public final String description;

		public OffsetFinding(int offset, int value, int stride, String description) {
			this.offset = offset;
			this.value = value;
			this.stride = stride;
			this.description = description;
		}
	}

	public static AnalysisResult analyze(byte[] data) {
		AnalysisResult result = new AnalysisResult();

		if (data == null || data.length < 4) {
			result.summary = "Insufficient data for analysis";
			return result;
		}

		result.magicNumber = extractMagicNumber(data);
		result.fileFormat = identifyFileFormat(result.magicNumber, data);
		result.entropy = calculateEntropy(data);
		result.entropyLevel = classifyEntropy(result.entropy);

		result.xorKey = detectXorKey(data);
		result.xorKeyLength = result.xorKey != null ? result.xorKey.length : 0;

		if (result.xorKey != null) {
			result.decryptedStrings = tryXorDecrypt(data, result.xorKey);
		}

		result.patterns = detectRepeatingPatterns(data);
		result.offsets = detectSequentialOffsets(data);

		result.summary = buildSummary(result);

		return result;
	}

	private static String extractMagicNumber(byte[] data) {
		if (data.length < 4)
			return "";
		return String.format("%02X %02X %02X %02X", data[0], data[1], data[2], data[3]);
	}

	private static String identifyFileFormat(String magic, byte[] data) {
		if (magic.startsWith("50 4B 03 04")) return "ZIP/JAR";
		if (magic.startsWith("CA FE BA BE")) return "Java Class";
		if (magic.startsWith("7F 45 4C 46")) return "ELF (Linux)";
		if (magic.startsWith("4D 5A")) return "PE/EXE (Windows)";
		if (magic.startsWith("89 50 4E 47")) return "PNG Image";
		if (magic.startsWith("FF D8 FF")) return "JPEG Image";
		if (magic.startsWith("1F 8B")) return "GZIP";
		if (magic.startsWith("42 5A 68")) return "BZIP2";
		if (magic.startsWith("52 61 72 21")) return "RAR";
		if (magic.startsWith("25 50 44 46")) return "PDF";
		if (magic.startsWith("27 05 01 00") || magic.startsWith("00 01 05 07")) return "Python Compiled";
		if (magic.startsWith("DE AD BE EF")) return "Custom Format (deadbeef marker)";
		return "Unknown / Custom Format";
	}

	private static double calculateEntropy(byte[] data) {
		int[] freq = new int[256];
		for (byte b : data) {
			freq[b & 0xFF]++;
		}

		double entropy = 0.0;
		int total = data.length;
		for (int i = 0; i < 256; i++) {
			if (freq[i] == 0)
				continue;
			double p = (double) freq[i] / total;
			entropy -= p * (Math.log(p) / Math.log(2));
		}
		return entropy;
	}

	private static String classifyEntropy(double entropy) {
		if (entropy > 7.5) return "Very High (encrypted/compressed)";
		if (entropy > 6.0) return "High (possibly encrypted or packed)";
		if (entropy > 4.0) return "Medium (code/data mix)";
		if (entropy > 2.0) return "Low (structured data)";
		return "Very Low (mostly empty/repeating)";
	}

	private static byte[] detectXorKey(byte[] data) {
		byte[] singleKey = detectSingleByteXor(data);
		if (singleKey != null)
			return singleKey;

		byte[] multiKey = detectMultiByteXor(data);
		if (multiKey != null)
			return multiKey;

		return null;
	}

	private static byte[] detectSingleByteXor(byte[] data) {
		int bestScore = 0;
		byte bestKey = 0;

		for (int key = 1; key < 256; key++) {
			int score = 0;
			int checkLen = Math.min(data.length, 256);
			for (int i = 0; i < checkLen; i++) {
				byte decrypted = (byte) (data[i] ^ key);
				if (isPrintableASCII(decrypted))
					score++;
			}
			if (score > bestScore) {
				bestScore = score;
				bestKey = (byte) key;
			}
		}

		int threshold = Math.min(data.length, 256) * 70 / 100;
		if (bestScore >= threshold) {
			return new byte[] { bestKey };
		}
		return null;
	}

	private static byte[] detectMultiByteXor(byte[] data) {
		for (int keyLen = 2; keyLen <= 8; keyLen++) {
			byte[] key = tryMultiByteKey(data, keyLen);
			if (key != null)
				return key;
		}
		return null;
	}

	private static byte[] tryMultiByteKey(byte[] data, int keyLen) {
		int checkLen = Math.min(data.length, 512);
		int[] printableCount = new int[keyLen];
		byte[] bestKey = new byte[keyLen];

		for (int pos = 0; pos < keyLen; pos++) {
			int bestScore = 0;
			byte bestByte = 0;

			for (int k = 0; k < 256; k++) {
				int score = 0;
				int count = 0;
				for (int i = pos; i < checkLen; i += keyLen) {
					byte decrypted = (byte) (data[i] ^ k);
					if (isPrintableASCII(decrypted))
						score++;
					count++;
				}
				if (score > bestScore) {
					bestScore = score;
					bestByte = (byte) k;
				}
			}
			bestKey[pos] = bestByte;
			printableCount[pos] = bestScore;
		}

		int totalPrintable = 0;
		int totalChecked = 0;
		for (int pos = 0; pos < keyLen; pos++) {
			totalPrintable += printableCount[pos];
			totalChecked += (checkLen - pos + keyLen - 1) / keyLen;
		}

		if (totalChecked > 0 && totalPrintable >= totalChecked * 70 / 100) {
			boolean allZero = true;
			for (byte b : bestKey) {
				if (b != 0) {
					allZero = false;
					break;
				}
			}
			if (!allZero)
				return bestKey;
		}

		return null;
	}

	private static List<String> tryXorDecrypt(byte[] data, byte[] key) {
		List<String> strings = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		int keyLen = key.length;

		for (int i = 0; i < data.length; i++) {
			byte decrypted = (byte) (data[i] ^ key[i % keyLen]);
			if (isPrintableASCII(decrypted)) {
				current.append((char) decrypted);
			} else {
				if (current.length() >= 4) {
					String s = current.toString();
					if (!strings.contains(s))
						strings.add(s);
				}
				current = new StringBuilder();
			}
		}
		if (current.length() >= 4) {
			String s = current.toString();
			if (!strings.contains(s))
				strings.add(s);
		}

		return strings;
	}

	private static List<PatternFinding> detectRepeatingPatterns(byte[] data) {
		List<PatternFinding> findings = new ArrayList<>();
		Map<String, int[]> patternMap = new HashMap<>();

		int maxPatternLen = Math.min(16, data.length / 2);
		for (int patLen = 4; patLen <= maxPatternLen; patLen++) {
			for (int i = 0; i <= data.length - patLen; i++) {
				StringBuilder sb = new StringBuilder();
				for (int j = 0; j < patLen; j++) {
					sb.append(String.format("%02X", data[i + j]));
				}
				String key = sb.toString();
				int[] countAndOffset = patternMap.get(key);
				if (countAndOffset == null) {
					patternMap.put(key, new int[] { 1, i });
				} else {
					countAndOffset[0]++;
				}
			}

			for (Map.Entry<String, int[]> e : patternMap.entrySet()) {
				if (e.getValue()[0] >= 3) {
					byte[] pattern = hexStringToBytes(e.getKey());
					boolean alreadyFound = false;
					for (PatternFinding pf : findings) {
						if (bytesEqual(pf.pattern, pattern)) {
							alreadyFound = true;
							break;
						}
					}
					if (!alreadyFound) {
						findings.add(new PatternFinding(pattern, e.getValue()[0], e.getValue()[1]));
					}
				}
			}
			patternMap.clear();
		}

		findings.sort((a, b) -> b.count - a.count);
		if (findings.size() > 10)
			return findings.subList(0, 10);
		return findings;
	}

	private static List<OffsetFinding> detectSequentialOffsets(byte[] data) {
		List<OffsetFinding> findings = new ArrayList<>();

		for (int i = 0; i + 8 <= data.length; i += 4) {
			int val1 = readLE32(data, i);
			if (i + 8 <= data.length) {
				int val2 = readLE32(data, i + 4);
				int diff = val2 - val1;
				if (diff > 0 && diff < 4096 && diff != 0) {
					if (i + 12 <= data.length) {
						int val3 = readLE32(data, i + 8);
						int diff2 = val3 - val2;
						if (diff2 == diff) {
							findings.add(new OffsetFinding(i, val1, diff,
									"Sequential 32-bit LE values with stride " + diff));
						}
					}
				}
			}
		}

		return findings;
	}

	private static String buildSummary(AnalysisResult r) {
		StringBuilder sb = new StringBuilder();
		sb.append("File Format: ").append(r.fileFormat).append("\n");
		sb.append("Magic Number: ").append(r.magicNumber).append("\n");
		sb.append(String.format("Entropy: %.2f bits/byte (%s)\n", r.entropy, r.entropyLevel));
		if (r.xorKey != null) {
			sb.append("XOR Key Detected (").append(r.xorKeyLength).append(" bytes): ");
			for (byte b : r.xorKey)
				sb.append(String.format("%02X ", b));
			sb.append("\n");
			sb.append("Decrypted Strings: ").append(r.decryptedStrings.size()).append("\n");
			for (String s : r.decryptedStrings) {
				if (s.length() <= 100)
					sb.append("  \"").append(s).append("\"\n");
			}
		} else {
			sb.append("No XOR key detected\n");
		}
		if (!r.patterns.isEmpty()) {
			sb.append("Repeating Patterns:\n");
			for (PatternFinding pf : r.patterns) {
				sb.append("  Pattern ");
				for (byte b : pf.pattern)
					sb.append(String.format("%02X ", b));
				sb.append("(count: ").append(pf.count).append(", offset: 0x")
						.append(Integer.toHexString(pf.firstOffset)).append(")\n");
			}
		}
		if (!r.offsets.isEmpty()) {
			sb.append("Sequential Offsets:\n");
			for (OffsetFinding of : r.offsets) {
				sb.append("  Offset 0x").append(Integer.toHexString(of.offset))
						.append(" value=0x").append(Integer.toHexString(of.value))
						.append(" ").append(of.description).append("\n");
			}
		}
		return sb.toString();
	}

	private static boolean isPrintableASCII(byte b) {
		int v = b & 0xFF;
		return (v >= 0x20 && v <= 0x7E) || v == 0x0A || v == 0x0D || v == 0x09;
	}

	private static int readLE32(byte[] data, int offset) {
		return (data[offset] & 0xFF)
				| ((data[offset + 1] & 0xFF) << 8)
				| ((data[offset + 2] & 0xFF) << 16)
				| ((data[offset + 3] & 0xFF) << 24);
	}

	private static byte[] hexStringToBytes(String hex) {
		int len = hex.length() / 2;
		byte[] bytes = new byte[len];
		for (int i = 0; i < len; i++) {
			bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
		}
		return bytes;
	}

	private static boolean bytesEqual(byte[] a, byte[] b) {
		if (a.length != b.length)
			return false;
		for (int i = 0; i < a.length; i++) {
			if (a[i] != b[i])
				return false;
		}
		return true;
	}

	public static String formatHexDump(byte[] data, int offset, int length) {
		StringBuilder sb = new StringBuilder();
		int end = Math.min(offset + length, data.length);

		for (int i = offset; i < end; i += 16) {
			sb.append(String.format("%08X  ", i));
			StringBuilder ascii = new StringBuilder();
			for (int j = 0; j < 16; j++) {
				if (i + j < end) {
					sb.append(String.format("%02X ", data[i + j]));
					int v = data[i + j] & 0xFF;
					if (v >= 0x20 && v <= 0x7E)
						ascii.append((char) v);
					else
						ascii.append('.');
				} else {
					sb.append("   ");
				}
				if (j == 7)
					sb.append(" ");
			}
			sb.append(" |").append(ascii).append("|\n");
		}
		return sb.toString();
	}
}
