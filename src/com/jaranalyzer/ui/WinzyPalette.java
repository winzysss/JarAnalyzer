package com.jaranalyzer.ui;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The colour system.
 *
 * <p>Built around crimson. Spending red on the chrome costs something real — it
 * is the obvious colour for "critical finding" — so the severity scale is pushed
 * off the red end of the wheel to compensate, and only the very top of that
 * scale stays red, brighter and more saturated than anything in the chrome.
 *
 * <p>The surface ramp is five steps rather than the usual two, each a small lift
 * in lightness and a small drop in saturation, and the whole ramp carries a red
 * cast rather than being neutral grey. That gradation is what stops a coloured
 * dark theme from looking flat: panels, inputs and rows separate by tone instead
 * of by borders. Every value is fixed rather than derived from the system look
 * and feel, because the point is a consistent appearance across machines.
 */
public final class WinzyPalette {

	private WinzyPalette() {
	}

	public static final class Scheme {
		public final String key;
		public final String label;
		public final Color accent;
		public final Color accent2;

		Scheme(String key, String label, Color accent, Color accent2) {
			this.key = key;
			this.label = label;
			this.accent = accent;
			this.accent2 = accent2;
		}
	}

	/** Selectable accent pairs. "crimson" is the product's identity. */
	public static final Map<String, Scheme> SCHEMES = new LinkedHashMap<>();

	static {
		// Red is the product's identity, and the pair matters as much as the
		// primary: a single flat red reads as a warning banner rather than a
		// theme. Pairing crimson with a warm ember gives the gradients somewhere
		// to travel, which is what keeps large fills from looking like plastic.
		// A touch deeper than the CRITICAL badge red on purpose — the chrome must
		// not compete with the one colour that means "look at this".
		put("crimson", "Kırmızı", 0xE8394C, 0xFF8A4C);
		put("violet", "Mor", 0xA06BFF, 0x3AD8D0);
		put("cyan", "Cyan", 0x3AD8D0, 0x7F8CFF);
		put("emerald", "Zümrüt", 0x35D6A0, 0x8FE388);
		put("amber", "Kehribar", 0xFFB238, 0xFF7A5C);
	}

	private static void put(String key, String label, int a, int b) {
		SCHEMES.put(key, new Scheme(key, label, new Color(a), new Color(b)));
	}

	// ---- surfaces ----------------------------------------------------------
	//
	// Five steps rather than the usual two or three, each a small lift in
	// lightness and a small drop in saturation. That gradation is what separates
	// a themed dark UI from a flat coloured one: panels, inputs and rows read as
	// distinct layers without a single border being drawn.
	//
	// The whole ramp carries a red cast rather than being neutral grey, so the
	// accent belongs to the same family as the chrome instead of sitting on top
	// of it.

	/** Window background — the deepest layer. */
	public static final Color BG = new Color(0x120809);
	/** Cards, sidebars, table body. */
	public static final Color PANEL = new Color(0x1D0F12);
	/** Raised elements: inputs, buttons, hovered rows. */
	public static final Color PANEL_HI = new Color(0x2A1519);
	/** Deepest inset: code viewers, evidence blocks. */
	public static final Color INSET = new Color(0x0C0506);
	/** Hairlines and dividers. */
	public static final Color LINE = new Color(0x47222A);
	public static final Color LINE_SOFT = new Color(0x33191F);

	// ---- text --------------------------------------------------------------

	public static final Color TEXT = new Color(0xF6E9EA);
	public static final Color TEXT_DIM = new Color(0xC49AA0);
	public static final Color TEXT_FAINT = new Color(0x8E6870);
	public static final Color ON_ACCENT = new Color(0x1A0608);

	// ---- semantic ----------------------------------------------------------

	// Spread deliberately wide around the colour wheel. On a red-tinted UI the
	// severity colours cannot all be reds — they would read as decoration. Only
	// the top of the scale is allowed to be red, and it is the brightest thing
	// on screen so it still wins against the chrome.
	public static final Color OK = new Color(0x3FD59E);
	public static final Color INFO = new Color(0x5BB8FF);
	public static final Color WARN = new Color(0xFFC24D);
	public static final Color BAD = new Color(0xFF7043);
	public static final Color WORST = new Color(0xFF2D55);
	public static final Color NEUTRAL = new Color(0xA89098);

	// ---- active accent -----------------------------------------------------

	private static Scheme active = SCHEMES.get("crimson");

	public static void setScheme(String key) {
		Scheme s = SCHEMES.get(key);
		if (s != null) active = s;
	}

	public static Scheme scheme() {
		return active;
	}

	public static Color accent() {
		return active.accent;
	}

	public static Color accent2() {
		return active.accent2;
	}

	/** Accent at low opacity, for selection fills and badge backgrounds. */
	public static Color accentWash(int alpha) {
		Color a = active.accent;
		return new Color(a.getRed(), a.getGreen(), a.getBlue(), alpha);
	}

	// ---- helpers -----------------------------------------------------------

	public static Color alpha(Color c, int a) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
	}

	public static Color mix(Color a, Color b, double t) {
		t = Math.max(0, Math.min(1, t));
		return new Color(
				(int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
				(int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
				(int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}

	/**
	 * Flattens a translucent colour onto a solid one.
	 *
	 * <p>Needed because several Swing renderers paint their background by filling
	 * the whole cell rectangle with an opaque colour; handing them a colour with
	 * an alpha channel silently loses the transparency and produces a hard edge.
	 */
	public static Color over(Color fg, Color bg) {
		double a = fg.getAlpha() / 255.0;
		return new Color(
				(int) Math.round(fg.getRed() * a + bg.getRed() * (1 - a)),
				(int) Math.round(fg.getGreen() * a + bg.getGreen() * (1 - a)),
				(int) Math.round(fg.getBlue() * a + bg.getBlue() * (1 - a)));
	}
}
