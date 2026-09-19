package com.theanchor.evidence;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenshotOverlayTest
{
	@Test public void stampsEvidenceWithoutChangingSharedFrame()
	{
		BufferedImage frame = new BufferedImage(765, 503, BufferedImage.TYPE_INT_RGB);
		BufferedImage evidence = ScreenshotOverlay.render(frame, new String[] { "Test Player" }, Color.WHITE);

		assertNotSame(frame, evidence);
		assertEquals(frame.getWidth(), evidence.getWidth());
		assertEquals(frame.getHeight(), evidence.getHeight());
		boolean changed = false;
		for (int y = 0; y < frame.getHeight(); y++)
			for (int x = 0; x < frame.getWidth(); x++)
			{
				assertEquals(Color.BLACK.getRGB(), frame.getRGB(x, y));
				changed |= evidence.getRGB(x, y) != frame.getRGB(x, y);
			}
		assertTrue("Evidence must contain the identity stamp", changed);
		assertEquals("Pixels outside the stamp must be preserved", frame.getRGB(400, 100), evidence.getRGB(400, 100));
	}
}
