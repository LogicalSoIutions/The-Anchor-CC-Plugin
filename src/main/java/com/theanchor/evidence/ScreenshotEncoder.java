/*
 * Portions adapted from the Llama Club screenshot encoder and Dink.
 * See LICENSES/llama-LICENSE.txt and LICENSES/dink-LICENSE.txt.
 */
package com.theanchor.evidence;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

public final class ScreenshotEncoder
{
	private static final int TARGET_BYTES = 2_000_000;
	private ScreenshotEncoder() {}

	public static Encoded encode(BufferedImage image) throws IOException
	{
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "png", png)) throw new IOException("PNG writer unavailable");
		if (png.size() <= TARGET_BYTES) return new Encoded("png", png.toByteArray());
		double[] scales = {1.0, .85, .7, .55};
		float[] qualities = {.9f, .86f, .82f, .78f};
		byte[] last = null;
		for (int i = 0; i < scales.length; i++)
		{
			last = jpeg(scale(image, scales[i]), qualities[i]);
			if (last.length <= TARGET_BYTES) break;
		}
		return new Encoded("jpg", last);
	}

	private static BufferedImage scale(BufferedImage input, double scale)
	{
		int width = scale >= 1 ? input.getWidth() : Math.max(1, (int) (input.getWidth() * scale));
		int height = scale >= 1 ? input.getHeight() : Math.max(1, (int) (input.getHeight() * scale));
		// The standard JPEG writer rejects images with an alpha channel (including RuneLite's
		// TYPE_INT_ARGB screenshots) with "Bogus input colorspace". Always render into RGB,
		// even when no resize is needed, before handing the image to that writer.
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(input, 0, 0, out.getWidth(), out.getHeight(), null);
		}
		finally
		{
			g.dispose();
		}
		return out;
	}

	private static byte[] jpeg(BufferedImage image, float quality) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ImageWriteParam param = writer.getDefaultWriteParam(); param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); param.setCompressionQuality(quality);
		try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) { writer.setOutput(stream); writer.write(null, new IIOImage(image, null, null), param); }
		finally { writer.dispose(); }
		return out.toByteArray();
	}

	public static final class Encoded
	{
		public final String format; public final byte[] bytes;
		Encoded(String format, byte[] bytes) { this.format = format; this.bytes = bytes; }
	}
}
