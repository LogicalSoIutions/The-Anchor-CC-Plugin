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
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class BingoEventOverlay extends OverlayPanel
{
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a");
	private final BingoService bingo;
	private final AnchorConfig config;
	private final Client client;

	@Inject
	public BingoEventOverlay(AnchorPlugin plugin, BingoService bingo, AnchorConfig config, Client client)
	{
		super(plugin);
		this.bingo = bingo;
		this.config = config;
		this.client = client;
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(PRIORITY_MED);
		panelComponent.setPreferredSize(new Dimension(260, 0));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.bingoOverlayEnabled()) return null;
		AnchorModels.BingoEvent event = bingo.current();
		if (event == null || !event.active) return null;
		Color color = config.bingoOverlayTextColor() == null ? Color.WHITE : config.bingoOverlayTextColor();
		String title = event.eventTitle == null || event.eventTitle.isBlank() ? "Bingo Event" : event.eventTitle;
		AnchorModels.BingoTeam team = bingo.currentTeam();
		String teamName = team == null || team.teamName == null || team.teamName.isBlank()
			? "Unassigned" : team.teamName;
		Player localPlayer = client.getLocalPlayer();
		String rsn = localPlayer == null || localPlayer.getName() == null || localPlayer.getName().isBlank()
			? "Not logged in" : localPlayer.getName();
		LocalDateTime now = LocalDateTime.now();
		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder().text(title).color(color).build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(teamName).leftColor(color).right(rsn).rightColor(color).build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(now.format(DATE)).leftColor(color).right(now.format(TIME)).rightColor(color).build());
		return super.render(graphics);
	}
}
