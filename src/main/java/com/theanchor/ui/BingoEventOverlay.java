package com.theanchor.ui;

import com.theanchor.AnchorConfig;
import com.theanchor.AnchorPlugin;
import com.theanchor.model.AnchorModels;
import com.theanchor.service.BingoService;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class BingoEventOverlay extends OverlayPanel
{
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy  h:mm a");
	private final BingoService bingo;
	private final AnchorConfig config;

	@Inject
	public BingoEventOverlay(AnchorPlugin plugin, BingoService bingo, AnchorConfig config)
	{
		super(plugin);
		this.bingo = bingo;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(PRIORITY_MED);
		panelComponent.setPreferredSize(new Dimension(240, 0));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		AnchorModels.BingoEvent event = bingo.current();
		if (event == null || !event.active) return null;
		Color color = config.eventOverlayTextColor() == null ? Color.WHITE : config.eventOverlayTextColor();
		String title = event.eventTitle == null || event.eventTitle.isBlank() ? "Bingo Event" : event.eventTitle;
		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder().text(title).color(color).build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(LocalDateTime.now().format(DATE_TIME)).leftColor(color).build());
		return super.render(graphics);
	}
}
