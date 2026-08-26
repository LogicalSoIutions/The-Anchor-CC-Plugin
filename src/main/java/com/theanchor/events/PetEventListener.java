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
	private static final Pattern NAMED_FOLLOWED_PATTERN = Pattern.compile("^.+? has a funny feeling like .+?being followed:\\s*(.+?)(?:\\s+at\\s+.+?)?(?:\\s+from\\s+.+?)?[.!…]*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern COLLECTION_LOG_PATTERN = Pattern.compile("New item added to your collection log:\\s*(.+)", Pattern.CASE_INSENSITIVE);
	@Inject private EventPipeline pipeline;
	@Inject private BingoService bingo;
	@Inject private Client client;
	@Inject private ItemManager itemManager;
	private String pendingPetMessage;
	private long pendingPetAt;
	private String recentCollectionItem;
	private long recentCollectionAt;

	@Subscribe public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) return;
		String message = Text.removeTags(event.getMessage());
		Matcher collectionMatcher = COLLECTION_LOG_PATTERN.matcher(message);
		if (collectionMatcher.find())
		{
			recentCollectionItem = collectionMatcher.group(1).trim();
			recentCollectionAt = System.currentTimeMillis();
			if (pendingPetMessage != null && recentCollectionAt - pendingPetAt <= 5000L)
			{
				capturePet(pendingPetMessage, recentCollectionItem);
				pendingPetMessage = null;
			}
			return;
		}

		Matcher matcher = PATTERN.matcher(message);
		boolean standardNotification = matcher.find();
		String petName = standardNotification && matcher.group(1) != null ? matcher.group(1).trim() : namedFollowedPet(message);
		if (petName != null && !petName.isBlank())
		{
			capturePet(message, petName);
			return;
		}
		if (standardNotification || NAMED_FOLLOWED_PATTERN.matcher(message).find())
		{
			if (recentCollectionItem != null && System.currentTimeMillis() - recentCollectionAt <= 5000L)
			{
				capturePet(message, recentCollectionItem);
				recentCollectionItem = null;
			}
			else
			{
				pendingPetMessage = message;
				pendingPetAt = System.currentTimeMillis();
			}
		}
	}

	static String extractPetName(String message)
	{
		if (message == null) return null;
		Matcher matcher = PATTERN.matcher(message);
		if (matcher.find() && matcher.group(1) != null && !matcher.group(1).isBlank()) return matcher.group(1).trim();
		matcher = NAMED_FOLLOWED_PATTERN.matcher(message);
		if (matcher.find()) return matcher.group(1).trim();
		matcher = COLLECTION_LOG_PATTERN.matcher(message);
		if (matcher.find())
		{
			String itemName = matcher.group(1).trim();
			if (PetItems.isPet(itemName)) return itemName;
		}
		return null;
	}

	private String namedFollowedPet(String message)
	{
		Matcher matcher = NAMED_FOLLOWED_PATTERN.matcher(message);
		return matcher.find() ? matcher.group(1).trim() : null;
	}

	private void capturePet(String message, String petName)
	{
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
