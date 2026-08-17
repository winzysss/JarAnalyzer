package com.jaranalyzer.ui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;

import com.jaranalyzer.LanguageManager;
import com.jaranalyzer.scan.Finding;
import com.jaranalyzer.scan.JarAnalysis;

/**
 * Everything known about the selected JAR, in tabs.
 *
 * <p>The findings tab is the one that matters: it is the difference between a
 * tool that asserts a verdict and one that shows the operator the evidence it
 * read — the matched term, where it sat, and the surrounding text — which is
 * what makes a finding arguable rather than an accusation to be taken on faith.
 * Reading the actual source is the Decompile tab's job, one window over.
 */
public class DetailPane extends JPanel {

	private static final long serialVersionUID = 1L;

	private final JTabbedPane tabs = new JTabbedPane();

	private final JTextArea overview = UiKit.codeArea();
	private final JTextArea findings = UiKit.codeArea();

	/**
	 * Archive listing, manifest and per-JAR log, concatenated under headings.
	 *
	 * <p>These were three separate tabs. Each is reference material a user opens
	 * rarely and reads by scrolling, so three tabs bought nothing and pushed the
	 * two that matter — findings and code — into a crowded strip.
	 */
	private final JTextArea details = UiKit.codeArea();

	private JarAnalysis current;

	public DetailPane() {
		super(new BorderLayout());
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

		overview.setLineWrap(true);
		overview.setWrapStyleWord(true);
		findings.setLineWrap(true);
		findings.setWrapStyleWord(true);

		tabs.setFont(WinzyTheme.ui(Font.BOLD, 11.5f));
		rebuildTabs();
		add(tabs, BorderLayout.CENTER);

		showEmpty();
	}

	private void rebuildTabs() {
		int selected = tabs.getTabCount() > 0 ? tabs.getSelectedIndex() : 0;
		tabs.removeAll();
		tabs.addTab(t("wjf.tab.overview"), UiKit.scroll(overview));
		tabs.addTab(t("wjf.tab.findings"), UiKit.scroll(findings));
		tabs.addTab(t("wjf.tab.details"), UiKit.scroll(details));
		if (selected >= 0 && selected < tabs.getTabCount()) tabs.setSelectedIndex(selected);
	}

	private static String t(String key) {
		return LanguageManager.getString(key);
	}

	public void updateLanguage() {
		rebuildTabs();
		if (current != null) show(current);
		else showEmpty();
	}

	// =====================================================================

	public void showEmpty() {
		current = null;
		overview.setText("\n  " + t("wjf.detail.empty"));
		findings.setText("");
		details.setText("");
		top(overview);
	}

	public void show(JarAnalysis a) {
		current = a;
		buildOverview(a);
		buildFindings(a);
		buildDetails(a);

		top(overview);
		top(findings);
		top(details);
	}

	private static void top(JTextArea a) {
		a.setCaretPosition(0);
	}

	// ---- tabs --------------------------------------------------------------

	private void buildOverview(JarAnalysis a) {
		StringBuilder s = new StringBuilder(2048);
		String reason = a.suspicionReason();
		line(s, t("wjf.f.verdict"), a.getVerdict().display()
				+ (reason.isEmpty() ? "" : " — " + reason)
				+ "   (" + t("wjf.f.score") + " " + a.getRiskScore() + ")");
		line(s, t("wjf.f.file"), a.getFileName());
		line(s, t("wjf.f.path"), a.getDirectory());
		line(s, t("wjf.f.size"), a.getSizeDisplay());
		if (a.getLastModified() > 0) {
			line(s, t("wjf.f.modified"), new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
					.format(new java.util.Date(a.getLastModified())));
		}
		if (!a.getSha256().isEmpty()) line(s, "SHA-256", a.getSha256());
		s.append('\n');

		line(s, t("wjf.f.decompile"), a.getDecompileOutcome().display());
		line(s, t("wjf.f.classes"), a.getClassCount() + " "
				+ t("wjf.f.total") + ",  " + a.getClassesRead() + " " + t("wjf.f.read")
				+ ",  " + a.getClassesUnreadable() + " " + t("wjf.f.failed"));
		if (a.getDecompileError() != null) line(s, t("wjf.f.error"), a.getDecompileError());
		s.append('\n');

		line(s, t("wjf.f.entries"), String.valueOf(a.getEntryCount()));
		line(s, t("wjf.f.resources"), String.valueOf(a.getResourceCount()));
		if (a.getNestedJarCount() > 0) line(s, t("wjf.f.nested"), String.valueOf(a.getNestedJarCount()));
		if (a.getNativeLibCount() > 0) line(s, t("wjf.f.native"), String.valueOf(a.getNativeLibCount()));
		s.append('\n');

		line(s, t("wjf.f.obfuscated"), a.isObfuscated()
				? yes() + (a.getObfuscatorGuess().isEmpty() ? "" : "  —  " + a.getObfuscatorGuess())
					+ String.format(Locale.ROOT, "   (%.2f)", a.getObfuscationScore())
				: no());
		line(s, t("wjf.f.encrypted"), a.isEncrypted() ? yes() : no());
		line(s, t("wjf.f.broken"), a.isStructurallyBroken() ? yes() : no());
		s.append('\n');

		if (a.getModLoader() != null) line(s, t("wjf.f.loader"), a.getModLoader());
		if (a.getMainClass() != null) line(s, "Main-Class", a.getMainClass());
		if (a.getPremainClass() != null) line(s, "Premain-Class", a.getPremainClass());
		if (a.getAgentClass() != null) line(s, "Agent-Class", a.getAgentClass());
		if (a.getTweakClass() != null) line(s, "TweakClass", a.getTweakClass());

		s.append('\n');
		line(s, t("wjf.f.findings"), String.valueOf(a.getFindingCount()));
		line(s, t("wjf.f.blacklisthits"), String.valueOf(a.countBlacklistHits()));
		line(s, t("wjf.f.duration"), a.getAnalysisMillis() + " ms");

		overview.setText(s.toString());
	}

	private String yes() {
		return t("wjf.yes");
	}

	private String no() {
		return t("wjf.no");
	}

	private static void line(StringBuilder s, String label, String value) {
		s.append("  ").append(pad(label, 20)).append("  ").append(value == null ? "-" : value).append('\n');
	}

	private static String pad(String s, int n) {
		StringBuilder b = new StringBuilder(s);
		while (b.length() < n) b.append(' ');
		return b.toString();
	}

	private void buildFindings(JarAnalysis a) {
		if (a.getFindingCount() == 0) {
			findings.setText("\n  " + t("wjf.detail.nofindings"));
			return;
		}
		StringBuilder s = new StringBuilder(4096);
		java.util.List<Finding> list = new java.util.ArrayList<>(a.getFindings());
		list.sort((x, y) -> y.getSeverity().weight() - x.getSeverity().weight());

		for (Finding f : list) {
			s.append("  [").append(f.getSeverity().display().toUpperCase(Locale.ROOT)).append("]  ")
					.append(f.getTitle());
			if (f.getHits() > 1) s.append("   x").append(f.getHits());
			s.append('\n');
			s.append("      ").append(t("wjf.f.source")).append(": ")
					.append(f.getSource().display())
					.append("    ").append(t("wjf.f.category")).append(": ")
					.append(f.getCategory()).append('\n');
			if (!f.getLocation().isEmpty()) {
				s.append("      ").append(t("wjf.f.location")).append(": ")
						.append(f.getLocation()).append('\n');
			}
			if (!f.getEvidence().isEmpty()) {
				s.append("      > ").append(f.getEvidence().replace("\n", "\n        ")).append('\n');
			}
			s.append('\n');
		}
		findings.setText(s.toString());
	}

	/** Archive listing + manifest + log, in one scrollable document. */
	private void buildDetails(JarAnalysis a) {
		StringBuilder s = new StringBuilder(16384);

		heading(s, t("wjf.details.entries") + "  (" + a.getEntryCount() + ")");
		if (a.getEntryNames().isEmpty()) {
			s.append("  ").append(t("wjf.detail.noentries")).append('\n');
		} else {
			for (String n : a.getEntryNames()) s.append("  ").append(n).append('\n');
			if (a.getEntryNames().size() < a.getEntryCount()) {
				s.append("  ... (")
						.append(a.getEntryCount() - a.getEntryNames().size())
						.append(" +)\n");
			}
		}

		heading(s, t("wjf.details.manifest"));
		s.append(a.getManifestText().isEmpty()
				? "  " + t("wjf.detail.nomanifest") + "\n"
				: a.getManifestText().trim() + "\n");

		heading(s, t("wjf.details.log"));
		if (a.getLog().isEmpty()) {
			s.append("  ").append(t("wjf.detail.nolog")).append('\n');
		} else {
			for (String l : a.getLog()) s.append("  ").append(l).append('\n');
		}

		details.setText(s.toString());
	}

	private static void heading(StringBuilder s, String title) {
		if (s.length() > 0) s.append('\n');
		s.append("──── ").append(title).append(' ');
		for (int i = title.length(); i < 64; i++) s.append('─');
		s.append("\n\n");
	}

}
