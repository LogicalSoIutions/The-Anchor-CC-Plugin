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
	private static final Color BACKGROUND = new Color(24, 38, 52, 235);
	private final EventAlertService alerts;
	private final AnchorConfig config;

	@Inject
	public EventAlertOverlay(AnchorPlugin plugin, EventAlertService alerts, AnchorConfig config)
	{
		super(plugin);
		this.alerts = alerts;
		this.config = config;
		setPosition(OverlayPosition.TOP_CENTER);
		setPriority(PRIORITY_HIGH);
		panelComponent.setBackgroundColor(BACKGROUND);
		panelComponent.setPreferredSize(new Dimension(360, 0));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.eventAlerts() || !config.eventAlertOverlay()) return null;
		String message = alerts.activeOverlayMessage();
		if (message == null) return null;
		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Event Alert: " + message)
			.color(config.eventAlertColor())
			.build());
		return super.render(graphics);
	}
}
