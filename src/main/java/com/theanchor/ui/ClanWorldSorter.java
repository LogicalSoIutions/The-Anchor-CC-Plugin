package com.theanchor.ui;

import com.theanchor.AnchorConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/** Repositions whole member rows without changing their contents or menu callbacks. */
@Singleton
public class ClanWorldSorter
{
	private static final Pattern WORLD = Pattern.compile("W([0-9]+)");
	@Inject private Client client;
	@Inject private ConfigManager configManager;
	private Widget toggle;
	private Widget toggleParent;
	private enum SortOrder { OFF, ASCENDING, DESCENDING }
	private final Map<Widget, Integer> originalY = new IdentityHashMap<>();

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() == ScriptID.CLAN_SIDEPANEL_DRAW) restore();
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.CLAN_SIDEPANEL_DRAW) refresh();
	}

	public void restore()
	{
		originalY.forEach((widget, y) -> {
			widget.setOriginalY(y);
			widget.revalidate();
		});
		originalY.clear();
	}

	public void refresh()
	{
		restore();
		SortOrder order = sortOrder();
		setupToggle(order);
		if (order == SortOrder.OFF) return;
		Widget list = client.getWidget(InterfaceID.ClansSidepanel.PLAYERLIST);
		if (list == null || list.getChildren() == null) return;
		Widget[] children = list.getChildren();
		List<Widget> worlds = new ArrayList<>();
		for (Widget child : children)
		{
			if (child != null && WORLD.matcher(plainText(child)).matches()) worlds.add(child);
		}
		if (worlds.size() < 2) return;
		worlds.sort(Comparator.comparingInt(Widget::getOriginalY));
		List<Integer> slots = new ArrayList<>();
		for (Widget world : worlds) slots.add(world.getOriginalY());
		// Refuse an unfamiliar layout rather than moving unrelated controls.
		int spacing = slots.get(1) - slots.get(0);
		if (spacing <= 0) return;
		for (int i = 1; i < slots.size(); i++)
		{
			if (slots.get(i) - slots.get(i - 1) != spacing) return;
		}
		Map<Widget, List<Widget>> rows = new IdentityHashMap<>();
		for (Widget world : worlds) rows.put(world, new ArrayList<>());
		for (Widget child : children)
		{
			if (child == null) continue;
			Widget nearest = null;
			int distance = Integer.MAX_VALUE;
			for (Widget world : worlds)
			{
				int delta = Math.abs(child.getOriginalY() - world.getOriginalY());
				if (delta < distance) { distance = delta; nearest = world; }
			}
			if (distance < spacing / 2) rows.get(nearest).add(child);
		}
		// Stable sort keeps the game's existing order among members on the same world.
		Comparator<Widget> byWorld = Comparator.comparingInt(world -> {
			Matcher matcher = WORLD.matcher(plainText(world));
			matcher.matches();
			return Integer.parseInt(matcher.group(1));
		});
		worlds.sort(order == SortOrder.DESCENDING ? byWorld.reversed() : byWorld);
		for (int i = 0; i < worlds.size(); i++)
		{
			Widget world = worlds.get(i);
			int delta = slots.get(i) - world.getOriginalY();
			for (Widget child : rows.get(world))
			{
				originalY.put(child, child.getOriginalY());
				child.setOriginalY(child.getOriginalY() + delta);
				child.revalidate();
			}
		}
	}

	private SortOrder sortOrder()
	{
		String saved = configManager.getConfiguration(AnchorConfig.GROUP, "clanWorldSort");
		if (saved == null) return SortOrder.OFF;
		try { return SortOrder.valueOf(saved); }
		catch (IllegalArgumentException ignored) { return SortOrder.OFF; }
	}

	private void setupToggle(SortOrder order)
	{
		Widget parent = client.getWidget(InterfaceID.ClansSidepanel.UNIVERSE);
		if (parent == null) return;
		if (toggle == null || parent != toggleParent || !contains(parent.getChildren(), toggle))
		{
			if (toggle != null) toggle.setHidden(true);
			toggleParent = parent;
			toggle = parent.createChild(-1, WidgetType.TEXT)
				.setPos(2, 2).setSize(42, 16)
				.setFontId(494).setTextShadowed(true)
				.setXTextAlignment(1).setYTextAlignment(1).setHasListener(true);
			toggle.setOnOpListener((JavaScriptCallback) event -> {
				SortOrder current = sortOrder();
				SortOrder next = current == SortOrder.OFF ? SortOrder.ASCENDING
					: current == SortOrder.ASCENDING ? SortOrder.DESCENDING : SortOrder.OFF;
				configManager.setConfiguration(AnchorConfig.GROUP, "clanWorldSort", next.name());
				refresh();
			});
		}
		toggle.setText(order == SortOrder.ASCENDING ? "World ^"
			: order == SortOrder.DESCENDING ? "World v" : "World");
		toggle.setTextColor(order == SortOrder.OFF ? 0xff981f : 0x00ff00);
		toggle.setAction(0, order == SortOrder.OFF ? "Sort by world ascending"
			: order == SortOrder.ASCENDING ? "Sort by world descending" : "Restore normal order");
		toggle.revalidate();
	}

	private static boolean contains(Widget[] children, Widget target)
	{
		if (children == null) return false;
		for (Widget child : children) if (child == target) return true;
		return false;
	}

	public void shutDown()
	{
		restore();
		if (toggle != null) toggle.setHidden(true);
		toggle = null;
		toggleParent = null;
	}

	private static String plainText(Widget widget)
	{
		String text = widget.getText();
		return text == null ? "" : text.replaceAll("<[^>]*>", "").trim();
	}
}
