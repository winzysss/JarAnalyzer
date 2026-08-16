package com.jaranalyzer.scan;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Writes scan results as a standalone HTML page, JSON, or plain text. */
public final class ReportWriter {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
			.disableHtmlEscaping().create();

	private ReportWriter() {
	}

	/**
	 * Label/value line for the text report.
	 *
	 * <p>Pads by display width rather than a fixed format string: the labels are
	 * translated, and Turkish ones are longer than the English they replaced, so a
	 * hardcoded column would have collided with the values.
	 */
	private static void row(StringBuilder sb, String label, String value) {
		sb.append(label);
		for (int i = label.length(); i < 15; i++) sb.append(' ');
		sb.append(": ").append(value).append('\n');
	}

	private static List<JarAnalysis> sorted(List<JarAnalysis> in) {
		List<JarAnalysis> out = new ArrayList<>(in);
		out.sort(Comparator
				.comparingInt((JarAnalysis a) -> -a.getVerdict().ordinal())
				.thenComparingInt(a -> -a.getRiskScore())
				.thenComparing(JarAnalysis::getFileName, String.CASE_INSENSITIVE_ORDER));
		return out;
	}

	// =====================================================================
	//  Text
	// =====================================================================

	public static void writeText(File target, ScanController.Summary s) throws IOException {
		StringBuilder sb = new StringBuilder(1 << 16);
		String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

		sb.append(Msg.t("wjf.r.title")).append('\n');
		sb.append("=================================\n");
		row(sb, Msg.t("wjf.r.generated"), stamp);
		row(sb, Msg.t("wjf.r.filesSeen"), String.valueOf(s.filesSeen));
		row(sb, Msg.t("wjf.r.jarsFound"), String.valueOf(s.totalFound));
		row(sb, Msg.t("wjf.r.analyzed"), String.valueOf(s.analyzed));
		row(sb, Msg.t("wjf.r.elapsed"), (s.elapsedMillis / 1000) + " s");
		sb.append('\n');

		for (Verdict v : Verdict.values()) {
			sb.append(String.format("  %-12s %d%n", v.display(), s.count(v)));
		}
		sb.append('\n');

		for (JarAnalysis a : sorted(s.results)) {
			if (!a.getVerdict().needsAttention()) continue;

			sb.append("---------------------------------------------------------------\n");
			// Reason on the verdict line too, matching the table: "[Şüpheli — Şifreli]".
			String reason = a.suspicionReason();
			sb.append('[').append(a.getVerdict().display())
					.append(reason.isEmpty() ? "" : " — " + reason).append("]  ")
					.append(a.getFileName()).append('\n');
			row(sb, "  " + Msg.t("wjf.r.path"), a.getPath());
			row(sb, "  " + Msg.t("wjf.r.size"),
					a.getSizeDisplay() + "   " + Msg.t("wjf.r.score") + ": " + a.getRiskScore());
			if (!a.getSha256().isEmpty()) {
				row(sb, "  SHA-256", a.getSha256());
			}
			row(sb, "  " + Msg.t("wjf.r.decompiled"),
					a.getDecompileOutcome().display()
					+ "  (" + a.getClassesDecompiled() + " " + Msg.t("wjf.r.ok") + ", "
					+ a.getClassesFailed() + " " + Msg.t("wjf.r.failed") + ")");
			if (a.isObfuscated()) {
				row(sb, "  " + Msg.t("wjf.r.obfuscated"), Msg.t("wjf.r.yes")
						+ (a.getObfuscatorGuess().isEmpty() ? "" : " — " + a.getObfuscatorGuess()));
			}
			if (a.isEncrypted()) row(sb, "  " + Msg.t("wjf.r.encrypted"), Msg.t("wjf.r.yes"));

			row(sb, "  " + Msg.t("wjf.r.findings"), String.valueOf(a.getFindingCount()));
			for (Finding f : a.getFindings()) {
				sb.append("    ").append(f.toString().replace("\n", "\n    ")).append('\n');
			}
			sb.append('\n');
		}

		write(target, sb.toString());
	}

	// =====================================================================
	//  JSON
	// =====================================================================

	public static void writeJson(File target, ScanController.Summary s) throws IOException {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("tool", "Jar Analyzer");
		root.put("generated", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date()));
		root.put("filesSeen", s.filesSeen);
		root.put("jarsFound", s.totalFound);
		root.put("analyzed", s.analyzed);
		root.put("elapsedMillis", s.elapsedMillis);

		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Verdict v : Verdict.values()) counts.put(v.en(), s.count(v));
		root.put("verdictCounts", counts);

		List<Map<String, Object>> jars = new ArrayList<>();
		for (JarAnalysis a : sorted(s.results)) {
			Map<String, Object> j = new LinkedHashMap<>();
			j.put("path", a.getPath());
			j.put("name", a.getFileName());
			j.put("sizeBytes", a.getSizeBytes());
			j.put("sha256", a.getSha256());
			j.put("verdict", a.getVerdict().en());
			j.put("riskScore", a.getRiskScore());
			j.put("decompile", a.getDecompileOutcome().en());
			j.put("classesTotal", a.getClassCount());
			j.put("classesDecompiled", a.getClassesDecompiled());
			j.put("classesFailed", a.getClassesFailed());
			j.put("obfuscated", a.isObfuscated());
			j.put("obfuscator", a.getObfuscatorGuess());
			j.put("encrypted", a.isEncrypted());
			j.put("structurallyBroken", a.isStructurallyBroken());
			j.put("mainClass", a.getMainClass());
			j.put("premainClass", a.getPremainClass());
			j.put("agentClass", a.getAgentClass());
			j.put("modLoader", a.getModLoader());

			List<Map<String, Object>> fs = new ArrayList<>();
			for (Finding f : a.getFindings()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("title", f.getTitle());
				m.put("severity", f.getSeverity().en());
				m.put("source", f.getSource().name());
				m.put("category", f.getCategory());
				m.put("pattern", f.getPattern());
				m.put("location", f.getLocation());
				m.put("evidence", f.getEvidence());
				m.put("hits", f.getHits());
				fs.add(m);
			}
			j.put("findings", fs);
			jars.add(j);
		}
		root.put("jars", jars);

		write(target, GSON.toJson(root));
	}

	// =====================================================================
	//  HTML
	// =====================================================================

	public static void writeHtml(File target, ScanController.Summary s) throws IOException {
		StringBuilder h = new StringBuilder(1 << 18);
		String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

		String lang = com.jaranalyzer.LanguageManager.getCurrentLanguage().getCode();
		h.append("<!doctype html><html lang=\"").append(lang).append("\"><head><meta charset=\"utf-8\">");
		h.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
		h.append("<title>").append(esc(Msg.t("wjf.r.title"))).append("</title><style>");
		h.append(css());
		h.append("</style></head><body>");

		h.append("<header><h1>Jar Analyzer</h1>")
				.append("<p class=\"sub\">").append(esc(Msg.t("wjf.r.sub")))
				.append(" — ").append(esc(stamp)).append("</p></header>");

		h.append("<section class=\"cards\">");
		card(h, Msg.t("wjf.r.filesSeen"), String.valueOf(s.filesSeen), "muted");
		card(h, Msg.t("wjf.r.jarsFound"), String.valueOf(s.totalFound), "muted");
		card(h, Msg.t("wjf.r.analyzed"), String.valueOf(s.analyzed), "muted");
		card(h, Msg.t("wjf.r.elapsed"), (s.elapsedMillis / 1000) + " s", "muted");
		card(h, Verdict.CRITICAL.display(), String.valueOf(s.count(Verdict.CRITICAL)), "v-critical");
		card(h, Verdict.DETECTED.display(), String.valueOf(s.count(Verdict.DETECTED)), "v-detected");
		card(h, Verdict.SUSPICIOUS.display(), String.valueOf(s.count(Verdict.SUSPICIOUS)), "v-suspicious");
		card(h, Verdict.CLEAN.display(), String.valueOf(s.count(Verdict.CLEAN)), "v-clean");
		h.append("</section>");

		h.append("<section class=\"tablewrap\"><table><thead><tr>")
				.append("<th>").append(esc(Msg.t("wjf.r.col.verdict"))).append("</th>")
				.append("<th>").append(esc(Msg.t("wjf.r.col.file"))).append("</th>")
				.append("<th>").append(esc(Msg.t("wjf.r.size"))).append("</th>")
				.append("<th>").append(esc(Msg.t("wjf.r.decompiled"))).append("</th>")
				.append("<th>").append(esc(Msg.t("wjf.r.score"))).append("</th>")
				.append("<th>").append(esc(Msg.t("wjf.r.findings"))).append("</th>")
				.append("</tr></thead><tbody>");

		for (JarAnalysis a : sorted(s.results)) {
			h.append("<tr><td><span class=\"badge ").append(cssClass(a.getVerdict())).append("\">")
					.append(esc(a.getVerdict().display())).append("</span></td>");
			h.append("<td><div class=\"fname\">").append(esc(a.getFileName())).append("</div>")
					.append("<div class=\"fpath\">").append(esc(a.getDirectory())).append("</div></td>");
			h.append("<td>").append(esc(a.getSizeDisplay())).append("</td>");
			h.append("<td>").append(esc(a.getDecompileSummary())).append("</td>");
			h.append("<td>").append(a.getRiskScore()).append("</td>");
			h.append("<td>").append(a.getFindingCount()).append("</td></tr>");

			if (a.getFindingCount() > 0) {
				h.append("<tr class=\"detail\"><td colspan=\"6\"><ul>");
				for (Finding f : a.getFindings()) {
					h.append("<li><span class=\"sev s-").append(f.getSeverity().name().toLowerCase(Locale.ROOT))
							.append("\">").append(esc(f.getSeverity().display())).append("</span> ")
							.append(esc(f.getTitle()));
					if (f.getHits() > 1) h.append(" <em>&times;").append(f.getHits()).append("</em>");
					if (!f.getLocation().isEmpty()) {
						h.append("<div class=\"loc\">").append(esc(f.getLocation())).append("</div>");
					}
					if (!f.getEvidence().isEmpty()) {
						h.append("<pre>").append(esc(f.getEvidence())).append("</pre>");
					}
					h.append("</li>");
				}
				h.append("</ul></td></tr>");
			}
		}

		h.append("</tbody></table></section>");
		h.append("<footer>").append(esc(Msg.t("wjf.r.footer"))).append("</footer>");
		h.append("</body></html>");

		write(target, h.toString());
	}

	private static void card(StringBuilder h, String label, String value, String cls) {
		h.append("<div class=\"card ").append(cls).append("\"><div class=\"v\">")
				.append(esc(value)).append("</div><div class=\"l\">")
				.append(esc(label)).append("</div></div>");
	}

	private static String cssClass(Verdict v) {
		return "v-" + v.name().toLowerCase(Locale.ROOT);
	}

	private static String css() {
		return ""
			// Mirrors WinzyPalette's crimson ramp so a report opened next to the
			// application does not look like a different product.
			+ ":root{--bg:#120809;--panel:#1d0f12;--panel2:#2a1519;--line:#47222a;"
			+ "--text:#f6e9ea;--muted:#c49aa0;--accent:#e8394c;--accent2:#ff8a4c;}"
			+ "*{box-sizing:border-box}"
			+ "body{margin:0;background:var(--bg);color:var(--text);"
			+ "font:14px/1.55 'Segoe UI',system-ui,sans-serif}"
			+ "header{padding:28px 32px;border-bottom:1px solid var(--line);"
			+ "background:linear-gradient(120deg,#2a1015,#120809 60%)}"
			+ "h1{margin:0;font-size:26px;letter-spacing:.5px;"
			+ "background:linear-gradient(90deg,var(--accent),var(--accent2));"
			+ "-webkit-background-clip:text;background-clip:text;color:transparent}"
			+ ".sub{margin:6px 0 0;color:var(--muted);font-size:13px}"
			+ ".cards{display:flex;flex-wrap:wrap;gap:12px;padding:20px 32px}"
			+ ".card{flex:1 1 130px;background:var(--panel);border:1px solid var(--line);"
			+ "border-radius:10px;padding:14px 16px}"
			+ ".card .v{font-size:24px;font-weight:600}"
			+ ".card .l{font-size:11px;text-transform:uppercase;letter-spacing:.9px;color:var(--muted);margin-top:4px}"
			+ ".tablewrap{padding:0 32px 32px;overflow-x:auto}"
			+ "table{width:100%;border-collapse:collapse;font-size:13px}"
			+ "th{text-align:left;padding:10px 12px;border-bottom:1px solid var(--line);"
			+ "color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.8px}"
			+ "td{padding:10px 12px;border-bottom:1px solid rgba(71,34,42,.55);vertical-align:top}"
			+ ".fname{font-weight:600}.fpath{color:var(--muted);font-size:11px;word-break:break-all}"
			+ ".badge{display:inline-block;padding:3px 9px;border-radius:20px;font-size:11px;"
			+ "font-weight:700;letter-spacing:.6px}"
			+ ".v-clean{color:#3fd59e;background:rgba(63,213,158,.14)}"
			+ ".v-notable{color:#5bb8ff;background:rgba(91,184,255,.14)}"
			+ ".v-suspicious{color:#ffc24d;background:rgba(255,194,77,.14)}"
			+ ".v-detected{color:#ff7043;background:rgba(255,112,67,.16)}"
			+ ".v-critical{color:#ff2d55;background:rgba(255,45,85,.18)}"
			+ ".v-unreadable{color:#a89098;background:rgba(168,144,152,.14)}"
			+ ".muted .v{color:var(--text)}"
			+ "tr.detail td{background:rgba(42,21,25,.5)}"
			+ "tr.detail ul{margin:0;padding-left:18px}"
			+ "tr.detail li{margin:8px 0}"
			+ ".sev{font-size:10px;font-weight:700;padding:2px 6px;border-radius:4px;margin-right:6px}"
			+ ".s-critical{background:rgba(255,45,85,.2);color:#ff2d55}"
			+ ".s-high{background:rgba(255,112,67,.18);color:#ff7043}"
			+ ".s-medium{background:rgba(255,194,77,.18);color:#ffc24d}"
			+ ".s-low{background:rgba(91,184,255,.16);color:#5bb8ff}"
			+ ".s-info{background:rgba(168,144,152,.16);color:#a89098}"
			+ ".loc{color:var(--muted);font-size:11px;margin:2px 0;word-break:break-all}"
			+ "pre{margin:4px 0 0;padding:8px 10px;background:#0c0506;border:1px solid var(--line);"
			+ "border-radius:6px;font-size:11.5px;white-space:pre-wrap;word-break:break-word;color:#e3c9cc}"
			+ "footer{padding:20px 32px;color:var(--muted);font-size:12px;border-top:1px solid var(--line)}";
	}

	// =====================================================================

	private static void write(File target, String content) throws IOException {
		File parent = target.getParentFile();
		if (parent != null && !parent.exists()) parent.mkdirs();
		try (Writer w = Files.newBufferedWriter(target.toPath(), StandardCharsets.UTF_8)) {
			w.write(content);
		}
	}

	private static String esc(String s) {
		if (s == null) return "";
		StringBuilder sb = new StringBuilder(s.length() + 16);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '&': sb.append("&amp;"); break;
				case '<': sb.append("&lt;"); break;
				case '>': sb.append("&gt;"); break;
				case '"': sb.append("&quot;"); break;
				case '\'': sb.append("&#39;"); break;
				default:
					// Control characters from raw class bytes would corrupt the page.
					if (c < 0x20 && c != '\n' && c != '\t') sb.append('.');
					else sb.append(c);
			}
		}
		return sb.toString();
	}
}
