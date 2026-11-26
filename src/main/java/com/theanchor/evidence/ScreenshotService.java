/*
 * Portions adapted from the Llama Club rendered-frame capture code and Dink.
 * See LICENSES/llama-LICENSE.txt and LICENSES/dink-LICENSE.txt.
 */
package com.theanchor.evidence;

import com.theanchor.AnchorConfig;
import com.theanchor.model.AnchorModels;
import com.theanchor.service.BingoService;
import java.awt.Color;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageCapture;
import net.runelite.client.util.ImageUtil;

@Singleton
public class ScreenshotService
{
	@Inject private Client client;
	@Inject private AnchorConfig config;
	@Inject private BingoService bingo;
	@Inject private ClientUI clientUi;
	@Inject private ClientThread clientThread;
	@Inject private DrawManager drawManager;
	@Inject private ImageCapture imageCapture;
	@Inject private ScheduledExecutorService executor;

	public void captureNextFrame(Consumer<BufferedImage> callback)
	{
		// Snapshot identity and event data on the client thread, before asynchronous capture.
		Player player = client.getLocalPlayer();
		String rsn = player == null || player.getName() == null ? "Unknown player" : player.getName();
		String[] lines = { rsn };
		Color color = Color.WHITE;
		AnchorModels.BingoEvent event = bingo.current();
		if (config.bingoOverlayEnabled() && config.bingoOverlayScreenshotOnly() && event != null && event.active)
		{
			AnchorModels.BingoTeam team = bingo.currentTeam();
			String title = event.eventTitle == null || event.eventTitle.isBlank() ? "Bingo Event" : event.eventTitle;
			String teamName = team == null || team.teamName == null || team.teamName.isBlank() ? "Unassigned" : team.teamName;
			lines = new String[] { rsn, title, teamName,
				LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy  h:mm a")) };
			if (config.bingoOverlayTextColor() != null) color = config.bingoOverlayTextColor();
		}
		String[] capturedLines = lines;
		Color capturedColor = color;
		boolean hidden = hidePrivateMessages();
		drawManager.requestNextFrameListener(frame ->
		{
			try
			{
				executor.execute(() -> callback.accept(ScreenshotOverlay.render(frame(frame), capturedLines, capturedColor)));
			}
			finally { restorePrivateMessages(hidden); }
		});
	}

	private BufferedImage frame(Image frame)
	{
		try
		{
			if (!config.includeSidebar()) return ImageUtil.bufferedImageFromImage(frame);
			Image withClient = imageCapture.addClientFrame(frame);
			return cropTitleBar(ImageUtil.bufferedImageFromImage(withClient == null ? frame : withClient));
		}
		catch (RuntimeException e) { return ImageUtil.bufferedImageFromImage(frame); }
	}

	private BufferedImage cropTitleBar(BufferedImage image)
	{
		try
		{
			AffineTransform transform = clientUi.getGraphicsConfiguration().getDefaultTransform();
			Insets insets = clientUi.getInsets(); Point offset = clientUi.getCanvasOffset();
			int y = (int) ((offset.y - insets.top) * transform.getScaleY());
			return y > 0 && y < image.getHeight() ? image.getSubimage(0, y, image.getWidth(), image.getHeight() - y) : image;
		}
		catch (RuntimeException e) { return image; }
	}

	private boolean hidePrivateMessages()
	{
		if (!config.hidePrivateMessages()) return false;
		Widget widget = client.getWidget(InterfaceID.PmChat.CONTAINER);
		if (widget == null || widget.isHidden()) return false;
		widget.setHidden(true); return true;
	}

	private void restorePrivateMessages(boolean restore)
	{
		if (!restore) return;
		clientThread.invoke(() -> { Widget widget = client.getWidget(InterfaceID.PmChat.CONTAINER); if (widget != null) widget.setHidden(false); });
	}
}
