package com.osrsarmory;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import net.runelite.api.Model;

/**
 * A small software renderer that draws a game model (front view, orthographic, transparent
 * background) into an image. Used to picture the player without opening any game screen.
 *
 * Model space: x = east, y = down, z = north. A player model faces south (-z), so the
 * camera looks from -z towards +z: screen x = model x, screen y = model y.
 */
final class ModelRenderer
{
	private static final int SUPERSAMPLE = 2;
	// Same curve the game applies for its "brightness" setting; 0.8 is a middle setting.
	private static final double BRIGHTNESS = 0.8;
	private static final int[] HSL_TO_RGB = buildPalette(BRIGHTNESS);

	private ModelRenderer()
	{
	}

	/** Copy of the model data needed to render, so rendering can run off the client thread. */
	static final class Snapshot
	{
		final float[] x, y, z;
		final int[] i1, i2, i3;
		final int[] c1, c2, c3;
		final byte[] alpha;   // face transparency (0 = opaque), may be null
		final short[] texture; // face texture id (-1 = none), may be null
		final int faceCount;

		Snapshot(Model m)
		{
			int vertices = m.getVerticesCount();
			faceCount = m.getFaceCount();
			x = Arrays.copyOf(m.getVerticesX(), vertices);
			y = Arrays.copyOf(m.getVerticesY(), vertices);
			z = Arrays.copyOf(m.getVerticesZ(), vertices);
			i1 = Arrays.copyOf(m.getFaceIndices1(), faceCount);
			i2 = Arrays.copyOf(m.getFaceIndices2(), faceCount);
			i3 = Arrays.copyOf(m.getFaceIndices3(), faceCount);
			c1 = Arrays.copyOf(m.getFaceColors1(), faceCount);
			c2 = Arrays.copyOf(m.getFaceColors2(), faceCount);
			c3 = Arrays.copyOf(m.getFaceColors3(), faceCount);
			alpha = m.getFaceTransparencies() == null ? null : Arrays.copyOf(m.getFaceTransparencies(), faceCount);
			texture = m.getFaceTextures() == null ? null : Arrays.copyOf(m.getFaceTextures(), faceCount);
		}
	}

	/** Renders the snapshot to fit inside width x height, keeping its aspect ratio. */
	static BufferedImage render(Snapshot s, int width, int height)
	{
		int w = width * SUPERSAMPLE;
		int h = height * SUPERSAMPLE;

		// Fit the visible vertices into the image with a small margin.
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int f = 0; f < s.faceCount; f++)
		{
			if (s.c3[f] == -2)
			{
				continue; // hidden face
			}
			for (int v : new int[]{s.i1[f], s.i2[f], s.i3[f]})
			{
				minX = Math.min(minX, s.x[v]);
				maxX = Math.max(maxX, s.x[v]);
				minY = Math.min(minY, s.y[v]);
				maxY = Math.max(maxY, s.y[v]);
			}
		}
		if (minX > maxX)
		{
			return null;
		}
		float margin = 0.06f;
		float scale = Math.min(w * (1 - 2 * margin) / (maxX - minX), h * (1 - 2 * margin) / (maxY - minY));
		float offX = w / 2f - (minX + maxX) / 2f * scale;
		float offY = h / 2f - (minY + maxY) / 2f * scale;

		int[] pixels = new int[w * h];          // ARGB, 0 = transparent
		float[] depth = new float[w * h];
		Arrays.fill(depth, Float.MAX_VALUE);    // smaller z = closer to the camera

		// Opaque faces first, then translucent ones blended on top.
		for (int pass = 0; pass < 2; pass++)
		{
			for (int f = 0; f < s.faceCount; f++)
			{
				if (s.c3[f] == -2)
				{
					continue;
				}
				int a = s.alpha == null ? 0 : s.alpha[f] & 0xFF;
				if ((pass == 0) != (a == 0) || a >= 250)
				{
					continue;
				}

				int va = s.i1[f], vb = s.i2[f], vc = s.i3[f];
				int[] rgb = faceColors(s, f);
				drawTriangle(pixels, depth, w, h, 255 - a,
					s.x[va] * scale + offX, s.y[va] * scale + offY, s.z[va], rgb[0],
					s.x[vb] * scale + offX, s.y[vb] * scale + offY, s.z[vb], rgb[1],
					s.x[vc] * scale + offX, s.y[vc] * scale + offY, s.z[vc], rgb[2]);
			}
		}

		BufferedImage big = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		big.setRGB(0, 0, w, h, pixels, 0, w);

		// Downscale for smooth edges.
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(big, 0, 0, width, height, null);
		g.dispose();
		return out;
	}

	// Per-vertex RGB for a face. faceColors3 == -1 means flat shading (one colour).
	private static int[] faceColors(Snapshot s, int f)
	{
		boolean textured = s.texture != null && s.texture[f] != -1;
		int a = toRgb(s.c1[f], textured);
		if (s.c3[f] == -1)
		{
			return new int[]{a, a, a};
		}
		return new int[]{a, toRgb(s.c2[f], textured), toRgb(s.c3[f], textured)};
	}

	private static int toRgb(int hsl, boolean textured)
	{
		if (textured)
		{
			// Textured faces only carry lightness; draw them as neutral grey.
			int l = Math.max(0, Math.min(127, hsl & 127));
			return HSL_TO_RGB[l];
		}
		return HSL_TO_RGB[hsl & 0xFFFF];
	}

	private static void drawTriangle(int[] px, float[] depth, int w, int h, int alpha,
		float x0, float y0, float z0, int c0,
		float x1, float y1, float z1, int c1,
		float x2, float y2, float z2, int c2)
	{
		float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
		if (Math.abs(area) < 1e-6f)
		{
			return;
		}

		int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
		int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
		int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
		int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));

		for (int py = minY; py <= maxY; py++)
		{
			float sy = py + 0.5f;
			for (int pxX = minX; pxX <= maxX; pxX++)
			{
				float sx = pxX + 0.5f;
				// Barycentric weights; works for either winding by dividing by the signed area.
				float w0 = ((x1 - sx) * (y2 - sy) - (x2 - sx) * (y1 - sy)) / area;
				float w1 = ((x2 - sx) * (y0 - sy) - (x0 - sx) * (y2 - sy)) / area;
				float w2 = 1 - w0 - w1;
				if (w0 < 0 || w1 < 0 || w2 < 0)
				{
					continue;
				}

				int i = py * w + pxX;
				float z = w0 * z0 + w1 * z1 + w2 * z2;
				if (z >= depth[i])
				{
					continue;
				}

				int r = (int) (w0 * ((c0 >> 16) & 0xFF) + w1 * ((c1 >> 16) & 0xFF) + w2 * ((c2 >> 16) & 0xFF));
				int g = (int) (w0 * ((c0 >> 8) & 0xFF) + w1 * ((c1 >> 8) & 0xFF) + w2 * ((c2 >> 8) & 0xFF));
				int b = (int) (w0 * (c0 & 0xFF) + w1 * (c1 & 0xFF) + w2 * (c2 & 0xFF));

				if (alpha >= 255)
				{
					depth[i] = z;
					px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
				}
				else
				{
					// Translucent: blend over what's there, don't write depth.
					int dst = px[i];
					int da = dst >>> 24;
					float k = alpha / 255f;
					int nr = (int) (r * k + ((dst >> 16) & 0xFF) * (1 - k));
					int ng = (int) (g * k + ((dst >> 8) & 0xFF) * (1 - k));
					int nb = (int) (b * k + (dst & 0xFF) * (1 - k));
					int na = Math.max(da, alpha);
					px[i] = (na << 24) | (nr << 16) | (ng << 8) | nb;
				}
			}
		}
	}

	// The game's 16-bit HSL colour palette: 6 bits hue, 3 bits saturation, 7 bits lightness.
	private static int[] buildPalette(double brightness)
	{
		int[] palette = new int[65536];
		for (int i = 0; i < palette.length; i++)
		{
			double hue = ((i >> 10) & 63) / 64.0 + 0.0078125;
			double sat = ((i >> 7) & 7) / 8.0 + 0.0625;
			double lum = (i & 127) / 128.0;

			double r = lum, g = lum, b = lum;
			if (sat != 0)
			{
				double q = lum < 0.5 ? lum * (1 + sat) : lum + sat - lum * sat;
				double p = 2 * lum - q;
				r = hueToChannel(p, q, hue + 1 / 3.0);
				g = hueToChannel(p, q, hue);
				b = hueToChannel(p, q, hue - 1 / 3.0);
			}
			int ri = (int) (Math.pow(r, brightness) * 256);
			int gi = (int) (Math.pow(g, brightness) * 256);
			int bi = (int) (Math.pow(b, brightness) * 256);
			palette[i] = (Math.min(255, ri) << 16) | (Math.min(255, gi) << 8) | Math.min(255, bi);
		}
		return palette;
	}

	private static double hueToChannel(double p, double q, double t)
	{
		if (t < 0)
		{
			t += 1;
		}
		if (t > 1)
		{
			t -= 1;
		}
		if (6 * t < 1)
		{
			return p + (q - p) * 6 * t;
		}
		if (2 * t < 1)
		{
			return q;
		}
		if (3 * t < 2)
		{
			return p + (q - p) * (2 / 3.0 - t) * 6;
		}
		return p;
	}
}
