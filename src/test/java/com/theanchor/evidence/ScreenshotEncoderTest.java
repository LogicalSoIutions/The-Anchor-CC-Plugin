/*
 * Tests cover screenshot behavior adapted from Llama Club and Dink.
 * See LICENSES/llama-LICENSE.txt and LICENSES/dink-LICENSE.txt.
 */
package com.theanchor.evidence;

import java.awt.image.BufferedImage;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenshotEncoderTest
{
	@Test public void preservesSmallImageAsPng() throws Exception
	{
		ScreenshotEncoder.Encoded encoded = ScreenshotEncoder.encode(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB));
		assertEquals("png", encoded.format); assertTrue(encoded.bytes.length > 0);
	}

	@Test public void convertsLargeArgbImageToJpegCompatibleRgb() throws Exception
	{
		BufferedImage image = new BufferedImage(900, 900, BufferedImage.TYPE_INT_ARGB);
		int[] pixels = new Random(42).ints(900 * 900).toArray();
		image.setRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());

		ScreenshotEncoder.Encoded encoded = ScreenshotEncoder.encode(image);

		assertEquals("jpg", encoded.format);
		assertTrue(encoded.bytes.length > 2);
		assertEquals(0xff, encoded.bytes[0] & 0xff);
		assertEquals(0xd8, encoded.bytes[1] & 0xff);
	}
}
