package com.theanchor.evidence;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Paints evidence labels on a private copy, never on RuneLite's shared frame. */
final class ScreenshotOverlay
{
	private ScreenshotOverlay() { }

	static BufferedImage render(BufferedImage source, String[] lines, Color color)
	{
		BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = result.createGraphics();
		try
		{
			graphics.drawImage(source, 0, 0, null);
			graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
			graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			FontMetrics metrics = graphics.getFontMetrics();
			int width = 0;
			for (String line : lines) width = Math.max(width, metrics.stringWidth(line));
			int height = lines.length * metrics.getHeight() + 16;
			int x = 8;
			int y = Math.max(0, result.getHeight() - height - 8);
			graphics.setColor(new Color(20, 20, 20, 235));
			graphics.fillRoundRect(x, y, width + 20, height, 8, 8);
			for (int i = 0; i < lines.length; i++)
			{
				// Identity stays legible regardless of the optional Bingo text color.
				graphics.setColor(i == 0 ? Color.WHITE : color);
				graphics.drawString(lines[i], x + 10, y + 8 + metrics.getAscent() + i * metrics.getHeight());
			}
		}
		finally { graphics.dispose(); }
		return result;
	}
}
