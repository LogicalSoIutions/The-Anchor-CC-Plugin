/*
 * Portions adapted from the Llama Club loot notification and tracking code.
 * See LICENSES/llama-LICENSE.txt.
 */
package com.theanchor.events;

import com.theanchor.evidence.EventPipeline;
import com.theanchor.model.AnchorModels;
import com.theanchor.service.BingoService;
import com.theanchor.service.LootEligibility;
import com.theanchor.service.PartyTracker;
import com.theanchor.service.RulesService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;

@Singleton
public class LootEventListener
{
	@Inject private ItemManager itemManager;
	@Inject private RulesService rules;
	@Inject private EventPipeline pipeline;
	@Inject private BingoService bingo;
	@Inject private PartyTracker parties;

	@Subscribe public void onNpcLootReceived(NpcLootReceived event)
	{
		NPC npc = event.getNpc();
		dispatch(event.getItems(), npc == null ? "Unknown" : npc.getName(), npc == null ? null : npc.getId(), "npc");
	}

	@Subscribe public void onLootReceived(LootReceived event)
	{
		if (event.getType() == LootRecordType.PLAYER) return;
		dispatch(event.getItems(), event.getName(), null, event.getType().name().toLowerCase());
	}

	private void dispatch(Collection<ItemStack> stacks, String sourceName, Integer sourceId, String sourceType)
	{
		if (parties != null) parties.observeSource(sourceName);
		bingo.observeSource(sourceId, sourceName);
		List<AnchorModels.Item> allItems = new ArrayList<>();
		List<AnchorModels.Item> eligible = new ArrayList<>();
		for (ItemStack stack : stacks)
		{
			ItemComposition composition = itemManager.getItemComposition(stack.getId());
			if (composition == null) continue;
			AnchorModels.Item item = new AnchorModels.Item(); item.itemId = stack.getId(); item.name = composition.getName();
			item.quantity = stack.getQuantity(); item.unitGeValue = itemManager.getItemPrice(stack.getId());
			item.totalGeValue = item.unitGeValue * item.quantity; item.tradeable = composition.isTradeable(); item.stackable = composition.isStackable();
			allItems.add(item);
			if (LootEligibility.shouldCapture(item, rules.current())) eligible.add(item);
		}
		List<AnchorModels.Item> bingoItems = bingo.matchingItems(sourceId, sourceName, allItems);
		if (eligible.isEmpty() && bingoItems.isEmpty()) return;
		AnchorModels.Source source = new AnchorModels.Source(); source.type = sourceType; source.id = sourceId; source.name = sourceName;
		// Use the item as the key so a collection-log notification for the same drop does not
		// create a second loot submission moments after RuneLite's loot event.
		if (!eligible.isEmpty()) capture("loot", eligible, source);
		if (!bingoItems.isEmpty()) captureBingo(bingoItems, source);
	}

	private void capture(String type, List<AnchorModels.Item> items, AnchorModels.Source source)
	{
		AnchorModels.Item first = items.get(0);
		String key = first.name == null ? String.valueOf(first.itemId)
			: first.name.trim().toLowerCase(java.util.Locale.ROOT);
		Map<String, Object> details = new LinkedHashMap<>();
		if (first.name != null) details.put("itemName", first.name);
		pipeline.capture(type, key, source, items, details, true);
	}

	private void captureBingo(List<AnchorModels.Item> items, AnchorModels.Source source)
	{
		AnchorModels.Item first = items.get(0);
		String key = first.name == null ? String.valueOf(first.itemId)
			: first.name.trim().toLowerCase(java.util.Locale.ROOT);
		Map<String, Object> details = new LinkedHashMap<>();
		if (first.name != null) details.put("itemName", first.name);
		bingo.decorateDetails(details);
		pipeline.capture(bingo.eventType(), key, source, items, details, true, bingo.rulesVersion(),
			bingo.screenshotRequired(), bingo.finalizeSubmission());
	}
}
