/*
 * Portions adapted from the Llama Club loot notification and tracking code.
 * See LICENSES/llama-LICENSE.txt.
 */
package com.theanchor.events;

import com.theanchor.evidence.EventPipeline;
import com.theanchor.model.AnchorModels;
import com.theanchor.service.LootEligibility;
import com.theanchor.service.BossRegistry;
import com.theanchor.service.RulesService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

	@Subscribe public void onNpcLootReceived(NpcLootReceived event)
	{
		NPC npc = event.getNpc();
		dispatch(event.getItems(), npc == null ? "Unknown" : npc.getName(), npc == null ? null : npc.getId(), "npc");
	}

	@Subscribe public void onLootReceived(LootReceived event)
	{
		if (event.getType() != LootRecordType.NPC && event.getType() != LootRecordType.EVENT) return;
		dispatch(event.getItems(), event.getName(), null, event.getType().name().toLowerCase());
	}

	private void dispatch(Collection<ItemStack> stacks, String sourceName, Integer sourceId, String sourceType)
	{
		if (!BossRegistry.isSupported(sourceName)) return;
		List<AnchorModels.Item> eligible = new ArrayList<>();
		for (ItemStack stack : stacks)
		{
			ItemComposition composition = itemManager.getItemComposition(stack.getId());
			if (composition == null) continue;
			AnchorModels.Item item = new AnchorModels.Item(); item.itemId = stack.getId(); item.name = composition.getName();
			item.quantity = stack.getQuantity(); item.unitGeValue = itemManager.getItemPrice(stack.getId());
			item.totalGeValue = item.unitGeValue * item.quantity; item.tradeable = composition.isTradeable(); item.stackable = composition.isStackable();
			if (LootEligibility.shouldCapture(item, rules.current())) eligible.add(item);
		}
		if (eligible.isEmpty()) return;
		AnchorModels.Source source = new AnchorModels.Source(); source.type = sourceType; source.id = sourceId; source.name = sourceName;
		String key = (sourceName == null ? "" : sourceName) + '|' + eligible.get(0).itemId;
		pipeline.capture("loot", key, source, eligible, null, true);
	}
}
