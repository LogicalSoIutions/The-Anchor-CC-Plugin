package com.theanchor.events;

import com.theanchor.evidence.EventPipeline;
import com.theanchor.model.AnchorModels;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/** Detect assembly only when the finished item replaces all of its components. */
@Singleton
public class WeaponCreationEventListener
{
	// Each finished item is followed by its components in the reusable count buffers.
	private static final int HALBERD = 0;
	private static final int AXE = 4;
	private static final int RING = 9;
	private static final int STAFF = 13;
	private static final int TRACKED_ITEMS = 17;
	@Inject private Client client;
	@Inject private ItemManager itemManager;
	@Inject private EventPipeline pipeline;
	private int[] previous = new int[TRACKED_ITEMS];
	private int[] current = new int[TRACKED_ITEMS];
	private boolean hasBaseline;

	public void reset() { hasBaseline = false; }

	@Subscribe public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN) reset();
	}

	@Subscribe public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.INVENTORY.getId()) return;
		if (client.getGameState() != GameState.LOGGED_IN) { reset(); return; }
		Arrays.fill(current, 0);
		for (Item item : event.getItemContainer().getItems())
		{
			int index = trackedIndex(item.getId());
			if (index >= 0 && item.getQuantity() > 0) current[index] += item.getQuantity();
		}
		int halberds = hasBaseline ? createdQuantity(HALBERD, AXE, previous, current) : 0;
		int axes = hasBaseline ? createdQuantity(AXE, RING, previous, current) : 0;
		int rings = hasBaseline ? createdQuantity(RING, STAFF, previous, current) : 0;
		int staves = hasBaseline ? createdQuantity(STAFF, TRACKED_ITEMS, previous, current) : 0;
		// Swap buffers before capture; login/activation only establishes a baseline.
		int[] spare = previous;
		previous = current;
		current = spare;
		hasBaseline = true;
		if (halberds > 0) capture(ItemID.NOXIOUS_HALBERD, halberds);
		if (axes > 0) capture(ItemID.SOULREAPER, axes);
		if (rings > 0) capture(ItemID.BRIMSTONE_RING, rings);
		if (staves > 0) capture(ItemID.TWINFLAME_STAFF, staves);
	}

	private static int trackedIndex(int itemId)
	{
		switch (itemId)
		{
			case ItemID.NOXIOUS_HALBERD: return HALBERD;
			case ItemID.NOXIOUS_HALBERD_PART_1: return 1;
			case ItemID.NOXIOUS_HALBERD_PART_2: return 2;
			case ItemID.NOXIOUS_HALBERD_PART_3: return 3;
			case ItemID.SOULREAPER: return AXE;
			case ItemID.SOULREAPER_AXE_HEAD: return 5;
			case ItemID.SOULREAPER_AXE_EYE: return 6;
			case ItemID.SOULREAPER_AXE_STAFF: return 7;
			case ItemID.SOULREAPER_AXE_LURE: return 8;
			case ItemID.BRIMSTONE_RING: return RING;
			case ItemID.HYDRA_HEART: return 10;
			case ItemID.HYDRA_EYE: return 11;
			case ItemID.HYDRA_FANG: return 12;
			case ItemID.TWINFLAME_STAFF: return STAFF;
			case ItemID.TWINFLAME_PIECE_1: return 14;
			case ItemID.TWINFLAME_PIECE_2: return 15;
			case ItemID.BATTLESTAFF: return 16;
			default: return -1;
		}
	}

	private static int createdQuantity(int finishedItem, int end, int[] before, int[] after)
	{
		int gained = after[finishedItem] - before[finishedItem];
		if (gained <= 0) return 0;
		for (int component = finishedItem + 1; component < end; component++)
			if (before[component] - after[component] < gained) return 0;
		return gained;
	}

	private void capture(int itemId, int quantity)
	{
		ItemComposition composition = itemManager.getItemComposition(itemId);
		AnchorModels.Item item = new AnchorModels.Item();
		item.itemId = itemId;
		item.name = composition.getName();
		item.quantity = quantity;
		item.unitGeValue = itemManager.getItemPrice(itemId);
		item.totalGeValue = item.unitGeValue * quantity;
		item.tradeable = composition.isTradeable();
		item.stackable = composition.isStackable();
		AnchorModels.Source source = new AnchorModels.Source();
		source.type = "creation";
		source.name = "Item Creation";
		Map<String, Object> details = new HashMap<>();
		details.put("itemName", item.name);
		details.put("detectionSource", "creation");
		details.put("autoSubmit", true);
		pipeline.capture("loot", item.name.toLowerCase(Locale.ROOT), source,
			Collections.singletonList(item), details, true);
	}
}
