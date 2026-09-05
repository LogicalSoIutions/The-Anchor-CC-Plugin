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
	private static final Pattern PATTERN = Pattern.compile("^You (?:have a funny feeling like you(?:'|’)re being followed(?: by (.+?))?|have a funny feeling like you would have been followed|feel something weird sneaking into your backpack)(?:\\s*:\\s*(.+?))?[.!…]*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern NAMED_FOLLOWED_PATTERN = Pattern.compile("^(?:[^\\w\\s]*)?(?<user>[\\w\\s]+?) has a funny feeling like .+? (?:would have been followed|being followed):\\s*(?<pet>.+?)(?:\\s+at\\s+.+?)?(?:\\s+from\\s+.+?)?[.!…]*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern UNTRADEABLE_DROP_PATTERN = Pattern.compile("^Untradeable drop:\\s*(.+?)(?:\\s+(?:\\(\\d+\\)|\\[\\d+\\]))?[.!…]*$", Pattern.CASE_INSENSITIVE);
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
		boolean gameMessage = event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.SPAM;
		boolean clanMessage = isClanNotification(event.getType());
		if (!gameMessage && !clanMessage) return;
		String message = Text.removeTags(event.getMessage());
		Matcher namedMatcher = NAMED_FOLLOWED_PATTERN.matcher(message);
		if (clanMessage)
		{
			if (!namedMatcher.find() || !isLocalPlayer(namedMatcher.group("user"))) return;
			String petName = normalizePetName(namedMatcher.group("pet"));
			long now = System.currentTimeMillis();
			if (pendingPetMessage != null && now - pendingPetAt <= 5000L)
			{
				capturePet(pendingPetMessage, petName);
				pendingPetMessage = null;
			}
			else
			{
				// The clan notification is the only signal available for some clients.
				// Capture it directly; the pet-name key prevents a later game signal
				// from creating a second pet submission.
				capturePet(message, petName);
			}
			return;
		}
		Matcher untradeableMatcher = UNTRADEABLE_DROP_PATTERN.matcher(message);
		if (untradeableMatcher.find())
		{
			String itemName = normalizePetName(untradeableMatcher.group(1));
			if (PetItems.isPet(itemName))
			{
				long now = System.currentTimeMillis();
				if (pendingPetMessage != null && now - pendingPetAt <= 5000L)
				{
					capturePet(pendingPetMessage, itemName);
					pendingPetMessage = null;
				}
				else
				{
					capturePet(message, itemName);
				}
			}
			return;
		}
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
		String petName = standardNotification
			? normalizePetName(firstNonBlank(matcher.group(1), matcher.group(2)))
			: namedFollowedPet(message);
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
		if (matcher.find())
		{
			String petName = firstNonBlank(matcher.group(1), matcher.group(2));
			if (petName != null) return normalizePetName(petName);
		}
		matcher = NAMED_FOLLOWED_PATTERN.matcher(message);
		if (matcher.find()) return normalizePetName(matcher.group("pet"));
		matcher = UNTRADEABLE_DROP_PATTERN.matcher(message);
		if (matcher.find())
		{
			String itemName = normalizePetName(matcher.group(1));
			if (PetItems.isPet(itemName)) return itemName;
		}
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
		return matcher.find() ? normalizePetName(matcher.group("pet")) : null;
	}

	private void capturePet(String message, String petName)
	{
		HashMap<String, Object> details = new HashMap<>(); details.put("message", message); details.put("petName", petName);
		boolean duplicate = isDuplicatePetMessage(message);
		details.put("duplicate", duplicate);
		details.put("obtained", !duplicate);
		AnchorModels.Source source = currentBossSource();
		String dedupeKey = petName == null ? message.toLowerCase(java.util.Locale.ROOT) : petName.toLowerCase(java.util.Locale.ROOT);
		List<AnchorModels.Item> petItems = petItems(petName);
		List<Integer> petItemIds = new ArrayList<>();
		for (AnchorModels.Item item : petItems) petItemIds.add(item.itemId);
		if (bingo.shouldCapturePet(source == null ? null : source.id, source == null ? null : source.name, petItemIds))
		{
			HashMap<String, Object> bingoDetails = new HashMap<>(details);
			bingo.decorateDetails(bingoDetails);
			pipeline.capture(bingo.eventType(), dedupeKey, source, petItems, bingoDetails, false,
				bingo.rulesVersion(), bingo.screenshotRequired(), bingo.finalizeSubmission());
		}
		else
		{
			pipeline.capture("pet", dedupeKey, source, null, details, false);
		}
	}

	static boolean isDuplicatePetMessage(String message)
	{
		return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("would have been followed");
	}

	private static String firstNonBlank(String first, String second)
	{
		if (first != null && !first.isBlank()) return first.trim();
		return second == null || second.isBlank() ? null : second.trim();
	}

	private static String normalizePetName(String value)
	{
		if (value == null || value.isBlank()) return value;
		return value.trim()
			.replaceFirst("(?i)\\s+at\\s+.+$", "")
			.replaceFirst("(?i)\\s+from\\s+.+$", "")
			.replaceAll("[.!…]+$", "")
			.trim();
	}

	private boolean isLocalPlayer(String username)
	{
		if (username == null || client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null) return false;
		String cleaned = username.replaceFirst("^[^\\w\\s]+", "").trim();
		return cleaned.equalsIgnoreCase(client.getLocalPlayer().getName());
	}

	static boolean isClanNotification(ChatMessageType type)
	{
		return type == ChatMessageType.FRIENDSCHATNOTIFICATION
			|| type == ChatMessageType.CLAN_MESSAGE
			|| type == ChatMessageType.CLAN_GUEST_MESSAGE
			|| type == ChatMessageType.CLAN_GIM_MESSAGE;
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
