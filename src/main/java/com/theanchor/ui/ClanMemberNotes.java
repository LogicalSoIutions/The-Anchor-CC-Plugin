package com.theanchor.ui;

import com.theanchor.AnchorConfig;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextInput;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.Text;

/** Personal notes, independent of the friends list and Anchor authentication. */
@Singleton
public class ClanMemberNotes extends Overlay
{
	private static final int CHARACTER_LIMIT = 255;
	private static final String KEY_PREFIX = "clanMemberNote_";
	@Inject private Client client;
	@Inject private ConfigManager configManager;
	@Inject private ChatboxPanelManager chatbox;
	@Inject private TooltipManager tooltipManager;

	public ClanMemberNotes()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (client.isMenuOpen()) return null;
		MenuEntry[] entries = client.getMenuEntries();
		for (int i = entries.length - 1; i >= 0; i--)
		{
			String name = memberName(entries[i]);
			if (name == null) continue;
			String note = configManager.getConfiguration(AnchorConfig.GROUP,
				KEY_PREFIX + name.toLowerCase(Locale.ROOT));
			if (note != null && !note.trim().isEmpty())
			{
				tooltipManager.add(new Tooltip(Text.removeTags(note)));
			}
			break;
		}
		return null;
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		for (MenuEntry entry : event.getMenuEntries())
		{
			String name = memberName(entry);
			if (name == null) continue;
			String key = KEY_PREFIX + name.toLowerCase(Locale.ROOT);
			String note = configManager.getConfiguration(AnchorConfig.GROUP, key);
			client.createMenuEntry(-1)
				.setOption(note == null ? "Add note" : "Edit note")
				.setTarget(entry.getTarget())
				.setType(MenuAction.RUNELITE)
				.onClick(e -> editNote(name, key));
			return;
		}
	}

	private static String memberName(MenuEntry entry)
	{
		if (entry.getParam1() != InterfaceID.ClansSidepanel.PLAYERLIST
			|| entry.getType() == MenuAction.RUNELITE) return null;
		String name = Text.toJagexName(Text.removeTags(entry.getTarget())).trim();
		return name.matches("[A-Za-z0-9 -]{1,12}") ? name : null;
	}

	private void editNote(String name, String key)
	{
		String saved = configManager.getConfiguration(AnchorConfig.GROUP, key);
		ChatboxTextInput input = chatbox.openTextInput(name
			+ "'s note (max 255 characters; clear to delete)")
			.value(saved == null ? "" : saved);
		input.onChanged(value -> {
			if (value.length() > CHARACTER_LIMIT) input.value(value.substring(0, CHARACTER_LIMIT));
		});
		input.onDone((String value) -> {
			if (value == null) return;
			String note = Text.removeTags(value).trim();
			if (note.length() > CHARACTER_LIMIT) note = note.substring(0, CHARACTER_LIMIT);
			if (note.isEmpty()) configManager.unsetConfiguration(AnchorConfig.GROUP, key);
			else configManager.setConfiguration(AnchorConfig.GROUP, key, note);
		}).build();
	}
}
