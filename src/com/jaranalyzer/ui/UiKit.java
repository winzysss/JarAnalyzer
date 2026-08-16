package com.jaranalyzer.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.border.Border;

/** Small reusable widgets, so the panels stay about layout rather than painting. */
public final class UiKit {

	private UiKit() {
	}

	public static void aa(Graphics2D g) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
	}

	// =====================================================================
	//  Buttons
	// =====================================================================

	/** Solid accent button, for the primary action on a screen. */
	public static JButton primary(String text, ActionListener onClick) {
		return new PillButton(text, true, onClick);
	}

	/** Outlined button, for everything else. */
	public static JButton ghost(String text, ActionListener onClick) {
		return new PillButton(text, false, onClick);
	}

	public static class PillButton extends JButton {
		private static final long serialVersionUID = 1L;

		private final boolean filled;
		private boolean hovered;
		private boolean active;
		private Color tint;

		public PillButton(String text, boolean filled, ActionListener onClick) {
			super(text);
			this.filled = filled;
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setFont(WinzyTheme.ui(Font.BOLD, 12.5f));
			setBorder(BorderFactory.createEmptyBorder(9, 18, 9, 18));
			setForeground(filled ? WinzyPalette.ON_ACCENT : WinzyPalette.TEXT);
			if (onClick != null) addActionListener(onClick);

			addMouseListener(new MouseAdapter() {
				@Override
				public void mouseEntered(MouseEvent e) {
					hovered = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e) {
					hovered = false;
					repaint();
				}
			});
		}

		/** Overrides the accent for this one button (used by the Stop action). */
		public PillButton tint(Color c) {
			this.tint = c;
			if (filled) setForeground(WinzyPalette.ON_ACCENT);
			repaint();
			return this;
		}

		/**
		 * Marks a ghost button as "on".
		 *
		 * <p>Used by the view toggles, which need a visible on/off state at rest —
		 * a plain ghost button only shows its accent while hovered, so without
		 * this there is no way to tell a hidden panel from a shown one.
		 */
		public PillButton setActive(boolean on) {
			if (active != on) {
				active = on;
				repaint();
			}
			return this;
		}

		public boolean isActive() {
			return active;
		}

		private Color base() {
			return tint != null ? tint : WinzyPalette.accent();
		}

		@Override
		protected void paintComponent(Graphics g0) {
			Graphics2D g = (Graphics2D) g0.create();
			aa(g);
			int w = getWidth();
			int h = getHeight();
			int arc = h;

			boolean off = !isEnabled();
			Color b = base();

			if (off) {
				g.setColor(WinzyPalette.PANEL_HI);
				g.fillRoundRect(0, 0, w, h, arc, arc);
				setForeground(WinzyPalette.TEXT_FAINT);
			} else if (filled) {
				g.setPaint(new GradientPaint(0, 0, b, 0, h,
						WinzyPalette.mix(b, Color.BLACK, hovered ? 0.10 : 0.22)));
				g.fillRoundRect(0, 0, w, h, arc, arc);
				setForeground(WinzyPalette.ON_ACCENT);
			} else {
				if (hovered || active) {
					g.setColor(WinzyPalette.over(
							WinzyPalette.alpha(b, active ? 46 : 34), WinzyPalette.PANEL));
					g.fillRoundRect(0, 0, w, h, arc, arc);
				}
				g.setColor(hovered || active ? b : WinzyPalette.LINE);
				g.setStroke(new BasicStroke(1.2f));
				g.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
				setForeground(hovered || active ? b : WinzyPalette.TEXT_DIM);
			}

			g.dispose();
			super.paintComponent(g0);
		}
	}

	// =====================================================================
	//  Cards
	// =====================================================================

	/** Rounded panel used for every raised surface. */
	public static class Card extends JPanel {
		private static final long serialVersionUID = 1L;

		private Color fill = WinzyPalette.PANEL;
		private Color stroke = WinzyPalette.LINE;
		private int arc = 12;

		public Card() {
			super(new BorderLayout());
			setOpaque(false);
		}

		public Card fill(Color c) {
			fill = c;
			return this;
		}

		public Card stroke(Color c) {
			stroke = c;
			return this;
		}

		public Card arc(int a) {
			arc = a;
			return this;
		}

		@Override
		protected void paintComponent(Graphics g0) {
			Graphics2D g = (Graphics2D) g0.create();
			aa(g);
			g.setColor(fill);
			g.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
			if (stroke != null) {
				g.setColor(stroke);
				g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
			}
			g.dispose();
			super.paintComponent(g0);
		}
	}

	/** A number with a caption; the row of these forms the dashboard. */
	public static class StatCard extends Card {
		private static final long serialVersionUID = 1L;

		private final JLabel value = new JLabel("0");
		private final JLabel caption = new JLabel("");
		private Color accentBar;

		public StatCard(String captionText, Color accent) {
			this.accentBar = accent;
			setBorder(BorderFactory.createEmptyBorder(12, 15, 12, 15));

			value.setFont(WinzyTheme.ui(Font.BOLD, 23f));
			value.setForeground(accent == null ? WinzyPalette.TEXT : accent);

			caption.setText(captionText);
			caption.setFont(WinzyTheme.ui(Font.BOLD, 10f));
			caption.setForeground(WinzyPalette.TEXT_DIM);

			JPanel stack = new JPanel();
			stack.setOpaque(false);
			stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
			value.setAlignmentX(LEFT_ALIGNMENT);
			caption.setAlignmentX(LEFT_ALIGNMENT);
			stack.add(value);
			stack.add(Box.createVerticalStrut(3));
			stack.add(caption);

			add(stack, BorderLayout.CENTER);
			setPreferredSize(new Dimension(120, 74));
			setMinimumSize(new Dimension(96, 74));
		}

		public void setValue(String v) {
			value.setText(v);
		}

		public void setCaption(String c) {
			caption.setText(c);
		}

		@Override
		protected void paintComponent(Graphics g0) {
			super.paintComponent(g0);
			if (accentBar == null) return;
			Graphics2D g = (Graphics2D) g0.create();
			aa(g);
			// A short accent rule along the top edge, so the cards read as a set
			// without each one being a block of saturated colour.
			g.setColor(WinzyPalette.alpha(accentBar, 190));
			g.fillRoundRect(14, 0, 30, 3, 3, 3);
			g.dispose();
		}
	}

	// =====================================================================
	//  Text bits
	// =====================================================================

	public static JLabel title(String text) {
		JLabel l = new JLabel(text);
		l.setFont(WinzyTheme.ui(Font.BOLD, 15f));
		l.setForeground(WinzyPalette.TEXT);
		return l;
	}

	public static JLabel caption(String text) {
		JLabel l = new JLabel(text);
		l.setFont(WinzyTheme.ui(Font.BOLD, 10f));
		l.setForeground(WinzyPalette.TEXT_DIM);
		return l;
	}

	public static JLabel body(String text) {
		JLabel l = new JLabel(text);
		l.setFont(WinzyTheme.ui(Font.PLAIN, 12.5f));
		l.setForeground(WinzyPalette.TEXT_DIM);
		return l;
	}

	/** Read-only monospaced viewer used by every detail tab. */
	public static JTextArea codeArea() {
		JTextArea a = new JTextArea();
		a.setEditable(false);
		a.setLineWrap(false);
		a.setFont(WinzyTheme.mono());
		a.setBackground(WinzyPalette.INSET);
		a.setForeground(new Color(0xC9C2EE));
		a.setCaretColor(WinzyPalette.accent());
		a.setSelectionColor(WinzyPalette.over(WinzyPalette.accentWash(110), WinzyPalette.INSET));
		a.setSelectedTextColor(WinzyPalette.TEXT);
		a.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		a.setTabSize(4);
		return a;
	}

	public static JScrollPane scroll(JComponent inner) {
		JScrollPane sp = new JScrollPane(inner,
				ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
				ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		sp.setBorder(BorderFactory.createLineBorder(WinzyPalette.LINE));
		sp.getViewport().setBackground(WinzyPalette.INSET);
		sp.getVerticalScrollBar().setUnitIncrement(18);
		sp.getHorizontalScrollBar().setUnitIncrement(18);
		return sp;
	}

	public static JPanel row() {
		JPanel p = new JPanel();
		p.setOpaque(false);
		p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
		return p;
	}

	public static JPanel column() {
		JPanel p = new JPanel();
		p.setOpaque(false);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		return p;
	}

	public static Border pad(int t, int l, int b, int r) {
		return BorderFactory.createEmptyBorder(t, l, b, r);
	}

	// =====================================================================
	//  Badge
	// =====================================================================

	/** Pill-shaped coloured label; the results table draws verdicts with this. */
	/** @return the badge's width, so a caller can place something after it. */
	public static int paintBadge(Graphics2D g, String text, Color fg, Color backdrop,
			int x, int y, int h, Font font) {
		aa(g);
		g.setFont(font);
		int textW = g.getFontMetrics().stringWidth(text);
		int w = textW + 18;

		g.setColor(WinzyPalette.over(WinzyPalette.alpha(fg, 38), backdrop));
		g.fillRoundRect(x, y, w, h, h, h);
		g.setColor(WinzyPalette.alpha(fg, 110));
		g.drawRoundRect(x, y, w - 1, h - 1, h, h);

		g.setColor(fg);
		int baseline = y + (h - g.getFontMetrics().getHeight()) / 2
				+ g.getFontMetrics().getAscent();
		g.drawString(text, x + 9, baseline);
		return w;
	}

	// =====================================================================
	//  Header
	// =====================================================================

	/** The gradient banner at the top of the window. */
	public static class Header extends JPanel {
		private static final long serialVersionUID = 1L;

		private final JLabel titleLabel = new JLabel("Jar Analyzer");
		private final JLabel subLabel = new JLabel();
		private final JPanel actions = new JPanel();

		public Header(String subtitle) {
			super(new BorderLayout());
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(16, 22, 16, 22));

			titleLabel.setFont(WinzyTheme.ui(Font.BOLD, 20f));
			titleLabel.setForeground(WinzyPalette.TEXT);
			// Antialiased bold text overhangs the width FontMetrics reports by a
			// pixel or two, which clips the final glyph; the trailing pad absorbs it.
			titleLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));

			subLabel.setText(subtitle);
			subLabel.setFont(WinzyTheme.ui(Font.PLAIN, 11.5f));
			subLabel.setForeground(WinzyPalette.TEXT_DIM);
			subLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));

			JPanel left = new JPanel();
			left.setOpaque(false);
			left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
			titleLabel.setAlignmentX(LEFT_ALIGNMENT);
			subLabel.setAlignmentX(LEFT_ALIGNMENT);
			// BoxLayout caps a child at its maximum size, and a JLabel reports its
			// preferred width as the maximum — a width measured with whatever font
			// was current at construction. When the themed font turns out wider the
			// label is stuck at the stale width and the tail of the text is cut off
			// ("…run the blacklist over i"). Letting both labels stretch to the row
			// removes the dependency on when the measurement happened.
			titleLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE,
					titleLabel.getMaximumSize().height));
			subLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE,
					subLabel.getMaximumSize().height));

			left.add(titleLabel);
			left.add(Box.createVerticalStrut(3));
			left.add(subLabel);

			actions.setOpaque(false);
			actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));

			// CENTER, not WEST: in WEST the panel is pinned to its preferred width,
			// and a bold display font measured before the theme's font is installed
			// comes out a few pixels short — enough to clip the last glyph.
			add(left, BorderLayout.CENTER);
			add(actions, BorderLayout.EAST);
		}

		public void setSubtitle(String s) {
			subLabel.setText(s);
		}

		public JPanel actions() {
			return actions;
		}

		@Override
		protected void paintComponent(Graphics g0) {
			Graphics2D g = (Graphics2D) g0.create();
			aa(g);
			int w = getWidth();
			int h = getHeight();

			// Three stops, not two: a single linear fade from tinted to background
			// looks like a flat wash at banner height. Carrying the ember through
			// the middle gives the surface somewhere to turn, which is what makes
			// it read as depth rather than paint.
			Color warm = WinzyPalette.over(
					WinzyPalette.alpha(WinzyPalette.accent(), 58), WinzyPalette.BG);
			Color mid = WinzyPalette.over(
					WinzyPalette.alpha(WinzyPalette.accent2(), 26), WinzyPalette.BG);
			g.setPaint(new java.awt.LinearGradientPaint(
					new java.awt.geom.Point2D.Float(0, 0),
					new java.awt.geom.Point2D.Float(w * 0.85f, h),
					new float[] { 0f, 0.45f, 1f },
					new Color[] { warm, mid, WinzyPalette.BG }));
			g.fillRect(0, 0, w, h);

			// A two-tone rule under the banner ties the accent pair together.
			g.setPaint(new GradientPaint(0, 0, WinzyPalette.accent(), w, 0, WinzyPalette.accent2()));
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f));
			g.fillRect(0, h - 2, w, 2);

			g.dispose();
			super.paintComponent(g0);
		}
	}

	/** Thin horizontal rule. */
	public static JComponent divider() {
		JPanel p = new JPanel();
		p.setBackground(WinzyPalette.LINE);
		p.setPreferredSize(new Dimension(1, 1));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
		return p;
	}

	/** Vertical rule for separating toolbar groups. */
	public static JComponent vDivider(int height) {
		JPanel p = new JPanel();
		p.setBackground(WinzyPalette.LINE);
		p.setPreferredSize(new Dimension(1, height));
		p.setMaximumSize(new Dimension(1, height));
		return p;
	}

	public static JLabel iconDot(Color c) {
		JLabel l = new JLabel("●");
		l.setForeground(c);
		l.setFont(WinzyTheme.ui(Font.PLAIN, 11f));
		l.setHorizontalAlignment(SwingConstants.CENTER);
		return l;
	}
}
