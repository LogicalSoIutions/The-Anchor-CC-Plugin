package com.theanchor.ui;

import com.theanchor.AnchorConfig;
import com.theanchor.AnchorPlugin;
import com.theanchor.service.EventAlertService;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class EventAlertOverlay extends OverlayPanel
{
	private static final Color LIGHT_BACKGROUND = new Color(235, 242, 250, 225);
	private static final Color FLASH_BACKGROUND = new Color(175, 215, 255, 240);
	private static final Color BRIGHT_BLUE = new Color(0, 130, 235);
	private final EventAlertService alerts;
	private final AnchorConfig config;

	@Inject
	public EventAlertOverlay(AnchorPlugin plugin, EventAlertService alerts, AnchorConfig config)
	{
		super(plugin);
		this.alerts = alerts;
		this.config = config;
		setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT);
		setPriority(PRIORITY_HIGH);
		panelComponent.setBackgroundColor(LIGHT_BACKGROUND);
		panelComponent.setPreferredSize(new Dimension(360, 0));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.eventAlerts() || !config.eventAlertOverlay()) return null;
		String message = alerts.activeOverlayMessage();
		if (message == null) return null;

		boolean flash = config.eventAlertFlash() && (System.currentTimeMillis() / 400) % 2 == 0;
		Color flashColor = config.eventAlertFlashColor() != null ? config.eventAlertFlashColor() : FLASH_BACKGROUND;
		panelComponent.setBackgroundColor(flash ? flashColor : LIGHT_BACKGROUND);

		Color textColor = config.eventAlertColor();
		if (textColor == null)
		{
			textColor = BRIGHT_BLUE;
		}

		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Event Alert: " + message)
			.color(textColor)
			.build());
		return super.render(graphics);
	}
}
