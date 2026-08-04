/*
 * Portions adapted from the Llama Club pet notification code.
 * See LICENSES/llama-LICENSE.txt.
 */
package com.theanchor.events;

import com.theanchor.evidence.EventPipeline;
import com.theanchor.model.AnchorModels;
import com.theanchor.service.BingoService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;
import net.runelite.http.api.item.ItemPrice;

@Singleton
public class PetEventListener
{
	private static final Pattern PATTERN = Pattern.compile("You (?:have a funny feeling like you(?:'|’)re being followed(?: by (.+?))?|have a funny feeling like you would have been followed|feel something weird sneaking into your backpack)[.!…]*$", Pattern.CASE_INSENSITIVE);
	@Inject private EventPipeline pipeline;
	@Inject private BingoService bingo;
	@Inject private Client client;
	@Inject private ItemManager itemManager;

	@Subscribe public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) return;
		String message = Text.removeTags(event.getMessage()); Matcher matcher = PATTERN.matcher(message); if (!matcher.find()) return;
		String petName = matcher.group(1) == null ? "Unknown pet" : matcher.group(1).trim();
		HashMap<String, Object> details = new HashMap<>(); details.put("message", message); details.put("petName", petName);
		details.put("obtained", !message.toLowerCase().contains("would have been followed"));
		AnchorModels.Source source = currentBossSource();
		pipeline.capture("pet", message.toLowerCase(), source, null, details, false);
		List<AnchorModels.Item> petItems = petItems(petName);
		List<Integer> petItemIds = new ArrayList<>();
		for (AnchorModels.Item item : petItems) petItemIds.add(item.itemId);
		if (bingo.shouldCapturePet(source == null ? null : source.id, source == null ? null : source.name, petItemIds))
		{
			HashMap<String, Object> bingoDetails = new HashMap<>(details);
			bingo.decorateDetails(bingoDetails);
			pipeline.capture(bingo.eventType(), message.toLowerCase(), source, petItems, bingoDetails, false,
				bingo.rulesVersion(), bingo.screenshotRequired(), bingo.finalizeSubmission());
		}
	}

	private AnchorModels.Source currentBossSource()
	{
		if (client.getLocalPlayer() == null) return null;
		Actor actor = client.getLocalPlayer().getInteracting();
		if (!(actor instanceof NPC)) return null;
		NPC npc = (NPC) actor;
		AnchorModels.Source source = new AnchorModels.Source();
		source.type = "npc"; source.id = npc.getId(); source.name = npc.getName();
		return source;
	}

	private List<AnchorModels.Item> petItems(String petName)
	{
		List<AnchorModels.Item> result = new ArrayList<>();
		if (itemManager == null || petName == null || petName.isBlank() || "Unknown pet".equals(petName)) return result;
		try
		{
			List<ItemPrice> matches = itemManager.search(petName);
			if (matches == null) return result;
			for (ItemPrice match : matches)
			{
				AnchorModels.Item item = new AnchorModels.Item(); item.itemId = match.getId(); item.name = match.getName(); item.quantity = 1;
				ItemComposition composition = itemManager.getItemComposition(item.itemId);
				if (composition != null) { item.tradeable = composition.isTradeable(); item.stackable = composition.isStackable(); }
				result.add(item);
			}
		}
		catch (RuntimeException ignored) { }
		return result;
	}
}
