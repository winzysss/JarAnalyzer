import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Generates the application icon as a multi-resolution .ico.
 *
 * <p>Kept in the repository rather than committing only the binary so the icon
 * can be regenerated when the accent colour changes. Each size is drawn at its
 * own resolution instead of downscaling one large bitmap — a 16 px icon made by
 * shrinking a 256 px one turns into mud, and 16 px is the size that actually
 * appears in the taskbar and title bar.
 *
 * <pre>javac -d out tools/IconGen.java &amp;&amp; java -cp out IconGen app.ico</pre>
 */
public final class IconGen {

	// The accent pair from com.jaranalyzer.ui.WinzyPalette, so the icon and the
	// window agree.
	// Deliberately duplicated rather than imported: this tool is compiled on its
	// own, outside the application's classpath.


	/** The magnifier: the brightest thing on the icon, and the only pure red. */
	private static final Color GLASS_TOP = new Color(0xFF5A66);
	private static final Color GLASS_BOTTOM = new Color(0xE8394C);

	private static final int[] SIZES = { 16, 20, 24, 32, 40, 48, 64, 128, 256 };

	public static void main(String[] args) throws IOException {
		File out = new File(args.length > 0 ? args[0] : "app.ico");

		BufferedImage[] images = new BufferedImage[SIZES.length];
		for (int i = 0; i < SIZES.length; i++) {
			images[i] = draw(SIZES[i]);
		}
		writeIco(out, images);
		System.out.println("wrote " + out.getAbsolutePath() + "  (" + out.length() + " bytes)");

		// The Swing window icon comes from the same drawing rather than a separate
		// image file. The PNG that used to fill this role was stock art with a
		// visible "pngtree" watermark, which cannot ship in something published
		// as open source.
		File png = new File(out.getParentFile(), "src/resources/appicon.png");
		if (!png.getParentFile().isDirectory()) {
			png = new File("src/resources/appicon.png");
		}
		if (png.getParentFile().isDirectory()) {
			javax.imageio.ImageIO.write(draw(256), "png", png);
			System.out.println("wrote " + png.getAbsolutePath());
		}

		if (args.length > 1) {
			writePreview(new File(args[1]), images);
			System.out.println("preview: " + args[1]);
		}
	}

	/** Contact sheet of every size, so the small ones can be eyeballed. */
	private static void writePreview(File file, BufferedImage[] images) throws IOException {
		int pad = 16;
		int width = pad;
		for (BufferedImage im : images) width += im.getWidth() + pad;
		int height = 256 + pad * 3;

		BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x120809));
		g.fillRect(0, 0, width, height);

		int x = pad;
		for (BufferedImage im : images) {
			g.drawImage(im, x, pad + (256 - im.getHeight()) / 2, null);
			g.setColor(new Color(0xC49AA0));
			g.drawString(im.getWidth() + "px", x, height - pad);
			x += im.getWidth() + pad;
		}
		g.dispose();
		javax.imageio.ImageIO.write(sheet, "png", file);
	}

	// =====================================================================

	/**
	 * The Jar Fwcker magnifier, redrawn in red.
	 *
	 * <p>Traced from the original mark rather than copied: that file is stock art
	 * carrying a visible "pngtree" watermark, so it cannot be redistributed. The
	 * proportions below are the source image's own, converted from its 1200 px
	 * grid to the 256 px grid everything here is authored on.
	 *
	 * <p>It is a pure outline glyph on transparency — no plate, no jar. Both were
	 * in the way: a filled plate fights the taskbar background, and two shapes at
	 * 16 px merge into a smudge.
	 */
	private static BufferedImage draw(int s) {
		BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

		float f = s / 256f;   // everything below is authored at 256 and scaled

		// Lens centre and radii on the 256 grid, sized so the diagonal glyph leaves
		// an even ~20 px margin and fills its square.
		float cx = 99 * f;
		float cy = 99 * f;
		float rOuter = 76 * f;
		float rInner = 60 * f;

		// The outline weights. Floored so the shape survives when 6.3 px of ideal
		// stroke becomes less than one real pixel at 16.
		float wOuter = Math.max(2.0f, 6.3f * f);
		float wInner = Math.max(1.4f, 5.0f * f);

		g.setPaint(new GradientPaint(cx - rOuter, cy - rOuter, GLASS_TOP,
				cx + rOuter, cy + rOuter * 2.4f, GLASS_BOTTOM));

		// ---- handle ----
		// Two parallel lines and a round end, drawn as one OPEN path.
		//
		// The obvious construction — union the thick line with a circle, subtract
		// the lens, stroke the result — is wrong: stroking an Area also strokes the
		// edge left by the subtraction, so a stray arc appears across the base of
		// the handle and the joint reads as a gap. An open path simply has no line
		// at the ring end, which is exactly what the original mark does.
		{
			double ang = Math.PI / 4;                       // 45°, down-right
			float ux = (float) Math.cos(ang);
			float uy = (float) Math.sin(ang);
			float nx = -uy;                                 // unit normal
			float ny = ux;
			float hw = 21f * f;
			float half = hw / 2;

			// Starts on the ring's centre line, so the ends are buried under the
			// ring stroke and no hairline can appear between the two shapes.
			float r0 = rOuter;
			float r1 = rOuter + 99 * f;

			float ax = cx + ux * r0, ay = cy + uy * r0;     // base, on the ring
			float bx = cx + ux * r1, by = cy + uy * r1;     // centre of the round end

			java.awt.geom.Path2D.Float handle = new java.awt.geom.Path2D.Float();
			handle.moveTo(ax + nx * half, ay + ny * half);
			handle.lineTo(bx + nx * half, by + ny * half);
			// Half turn around the end point, from the +normal side to the −normal
			// side, passing through the far tip.
			//
			// The arc must START where the path currently is. Arc2D angles run
			// anticlockwise from east and the handle points down-right, which puts
			// the +normal side at 225°; sweeping +180° from there passes through the
			// tip at 315° and lands on the far side. Giving the equivalent arc the
			// other way round (45°, −180°) traces the same curve but begins on the
			// opposite side, so append() joins to it with a chord and the end curls
			// into a hook.
			handle.append(new Arc2D.Float(bx - half, by - half, hw, hw, 225, 180, Arc2D.OPEN), true);
			handle.lineTo(ax - nx * half, ay - ny * half);

			g.setStroke(new BasicStroke(wOuter, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(handle);
		}

		// ---- outer ring ----
		g.setStroke(new BasicStroke(wOuter, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new Ellipse2D.Float(cx - rOuter, cy - rOuter, rOuter * 2, rOuter * 2));

		// ---- inner ring + glass highlight ----
		// Dropped below 32 px: at that size the gap between the two rings is under
		// a pixel and they merge into one fat ring anyway.
		if (s >= 32) {
			g.setStroke(new BasicStroke(wInner, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Ellipse2D.Float(cx - rInner, cy - rInner, rInner * 2, rInner * 2));

			// The little sheen inside the lens. Only at 48+: below that it lands on
			// the inner ring and just makes the lens look dirty.
			if (s >= 48) {
				float ra = rInner * 0.72f;
				g.setStroke(new BasicStroke(Math.max(1.4f, 5.0f * f),
						BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.draw(new Arc2D.Float(cx - ra, cy - ra, ra * 2, ra * 2, 38, 92, Arc2D.OPEN));
			}
		}

		g.dispose();
		return img;
	}


	// =====================================================================
	//  ICO container
	// =====================================================================

	/**
	 * Writes a classic ICO.
	 *
	 * <p>Sizes up to 64 are stored as 32-bit DIBs and larger ones as PNG. Windows
	 * has read PNG-in-ICO since Vista, but a few shell surfaces and third-party
	 * tools still only handle DIB at the small sizes, and those are exactly the
	 * sizes that show in the taskbar.
	 */
	private static void writeIco(File file, BufferedImage[] images) throws IOException {
		byte[][] payloads = new byte[images.length][];
		boolean[] isPng = new boolean[images.length];

		for (int i = 0; i < images.length; i++) {
			if (images[i].getWidth() > 64) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				javax.imageio.ImageIO.write(images[i], "png", bos);
				payloads[i] = bos.toByteArray();
				isPng[i] = true;
			} else {
				payloads[i] = dib(images[i]);
				isPng[i] = false;
			}
		}

		try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
			writeLE16(out, 0);                    // reserved
			writeLE16(out, 1);                    // type: icon
			writeLE16(out, images.length);

			int offset = 6 + 16 * images.length;
			for (int i = 0; i < images.length; i++) {
				int w = images[i].getWidth();
				int h = images[i].getHeight();
				out.writeByte(w >= 256 ? 0 : w);
				out.writeByte(h >= 256 ? 0 : h);
				out.writeByte(0);                 // palette size
				out.writeByte(0);                 // reserved
				writeLE16(out, 1);                // colour planes
				writeLE16(out, 32);               // bits per pixel
				writeLE32(out, payloads[i].length);
				writeLE32(out, offset);
				offset += payloads[i].length;
			}

			for (byte[] p : payloads) out.write(p);
		}
	}

	/** 32-bit BGRA DIB with the doubled height and trailing AND mask ICO expects. */
	private static byte[] dib(BufferedImage img) throws IOException {
		int w = img.getWidth();
		int h = img.getHeight();
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bos);

		writeLE32(out, 40);          // BITMAPINFOHEADER size
		writeLE32(out, w);
		writeLE32(out, h * 2);       // XOR bitmap + AND mask
		writeLE16(out, 1);           // planes
		writeLE16(out, 32);          // bpp
		writeLE32(out, 0);           // BI_RGB
		writeLE32(out, 0);           // image size (may be 0 for BI_RGB)
		writeLE32(out, 0);
		writeLE32(out, 0);
		writeLE32(out, 0);
		writeLE32(out, 0);

		// XOR bitmap, bottom-up, BGRA
		for (int y = h - 1; y >= 0; y--) {
			for (int x = 0; x < w; x++) {
				int argb = img.getRGB(x, y);
				out.writeByte(argb & 0xFF);            // B
				out.writeByte((argb >> 8) & 0xFF);     // G
				out.writeByte((argb >> 16) & 0xFF);    // R
				out.writeByte((argb >> 24) & 0xFF);    // A
			}
		}

		// AND mask: unused with an alpha channel, but the row padding still has to
		// be right or the shell reads the next icon's data as mask bits.
		int maskRowBytes = ((w + 31) / 32) * 4;
		for (int y = 0; y < h; y++) {
			for (int b = 0; b < maskRowBytes; b++) out.writeByte(0);
		}

		out.flush();
		return bos.toByteArray();
	}

	private static void writeLE16(DataOutputStream out, int v) throws IOException {
		out.writeByte(v & 0xFF);
		out.writeByte((v >> 8) & 0xFF);
	}

	private static void writeLE32(DataOutputStream out, int v) throws IOException {
		out.writeByte(v & 0xFF);
		out.writeByte((v >> 8) & 0xFF);
		out.writeByte((v >> 16) & 0xFF);
		out.writeByte((v >> 24) & 0xFF);
	}
}


