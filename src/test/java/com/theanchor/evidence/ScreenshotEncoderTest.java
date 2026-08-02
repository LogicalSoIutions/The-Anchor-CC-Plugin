/*
 * Tests cover screenshot behavior adapted from Llama Club and Dink.
 * See LICENSES/llama-LICENSE.txt and LICENSES/dink-LICENSE.txt.
 */
package com.theanchor.evidence;

import java.awt.image.BufferedImage;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenshotEncoderTest
{
	@Test public void preservesSmallImageAsPng() throws Exception
	{
		ScreenshotEncoder.Encoded encoded = ScreenshotEncoder.encode(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB));
		assertEquals("png", encoded.format); assertTrue(encoded.bytes.length > 0);
	}
}
