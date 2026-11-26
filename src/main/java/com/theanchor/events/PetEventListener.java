/*
 * Portions adapted from the Llama Club pet notification code.
 * See LICENSES/llama-LICENSE.txt.
 */
package com.theanchor.events;

import com.theanchor.evidence.EventPipeline;
import com.theanchor.model.AnchorModels;
import com.theanchor.service.BingoService;
import com.theanchor.service.BossRegistry;
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
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;
import net.runelite.http.api.item.ItemPrice;

@Singleton
public class PetEventListener
{
	private static final Pattern PATTERN = Pattern.compile("^You (?:have a funny feeling like you(?:'|’)re being followed(?: by (.+?))?|have a funny feeling like you would have been followed|feel something weird sneaking into your backpack)(?:\\s*:\\s*(.+?))?[.!…]*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern NAMED_PET_PATTERN = Pattern.compile("^(?:[^\\w\\s]*)?(?<user>[\\w\\s]+?) (?:has a funny feeling like .+? (?:would have been followed|being followed)|feels something weird sneaking into .+? backpack):\\s*(?<pet>.+?)(?:\\s+at\\s+.+?)?(?:\\s+from\\s+.+?)?[.!…]*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern UNTRADEABLE_DROP_PATTERN = Pattern.compile("^Untradeable drop:\\s*(.+?)(?:\\s+(?:\\(\\d+\\)|\\[\\d+\\]))?[.!…]*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern COLLECTION_LOG_PATTERN = Pattern.compile("New item added to your collection log:\\s*(.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern KILL_COUNT_PATTERN = Pattern.compile("^Your (.+?) (?:kill|chest|completion) count is: [\\d,]+[.!]?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern CLAN_SOURCE_PATTERN = Pattern.compile("\\s+from\\s+(.+?)[.!…]*$", Pattern.CASE_INSENSITIVE);
	@Inject private EventPipeline pipeline;
	@Inject private BingoService bingo;
	@Inject private Client client;
	@Inject private ItemManager itemManager;
	private String pendingPetMessage;
	private long pendingPetAt;
	private String recentCollectionItem;
	private long recentCollectionAt;
	private AnchorModels.Source recentBossSource;
	private long recentBossAt;
	private String recentNamedPet;
	private long recentNamedPetAt;

	@Subscribe public void onGameTick(GameTick event)
	{
		if (pendingPetMessage != null && System.currentTimeMillis() - pendingPetAt > 5000L)
		{
			// Duplicate rolls may never produce a collection-log or named clan message.
			String message = pendingPetMessage;
			pendingPetMessage = null;
			AnchorModels.Source source = currentBossSource();
			String petName = PetItems.forBoss(source == null ? null : source.name);
			capturePet(message, petName == null ? "Unknown pet" : normalizePetName(petName));
		}
	}

	@Subscribe public void onChatMessage(ChatMessage event)
	{
		boolean gameMessage = event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.SPAM;
		boolean clanMessage = isClanNotification(event.getType());
		if (!gameMessage && !clanMessage) return;
		String message = Text.removeTags(event.getMessage()).replace('\u00a0', ' ').trim();
		Matcher killMatcher = KILL_COUNT_PATTERN.matcher(message);
		if (gameMessage && killMatcher.matches())
		{
			rememberBoss(killMatcher.group(1));
			return;
		}
		Matcher namedMatcher = NAMED_PET_PATTERN.matcher(message);
		if (clanMessage)
		{
			if (!namedMatcher.find() || !isLocalPlayer(namedMatcher.group("user"))) return;
			String petName = normalizePetName(namedMatcher.group("pet"));
			Matcher sourceMatcher = CLAN_SOURCE_PATTERN.matcher(message);
			if (sourceMatcher.find()) rememberBoss(sourceMatcher.group(1));
			long now = System.currentTimeMillis();
			recentNamedPet = petName;
			recentNamedPetAt = now;
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
			pendingPetMessage = null;
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
			String itemName = collectionMatcher.group(1).trim();
			if (!PetItems.isPet(itemName)) return;
			recentCollectionItem = itemName;
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
			: namedPet(message);
		if (petName != null && !petName.isBlank())
		{
			capturePet(message, petName);
			return;
		}
		if (standardNotification || NAMED_PET_PATTERN.matcher(message).find())
		{
			if (recentNamedPet != null && System.currentTimeMillis() - recentNamedPetAt <= 5000L)
			{
				// The local player's named clan notification already captured this roll.
				return;
			}
			else if (recentCollectionItem != null && System.currentTimeMillis() - recentCollectionAt <= 5000L)
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
		matcher = NAMED_PET_PATTERN.matcher(message);
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

	private String namedPet(String message)
	{
		Matcher matcher = NAMED_PET_PATTERN.matcher(message);
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
		return cleaned.equalsIgnoreCase(client.getLocalPlayer().getName().replace('\u00a0', ' ').trim());
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
		if (recentBossSource != null && System.currentTimeMillis() - recentBossAt <= 10_000L)
			return recentBossSource;
		if (client.getLocalPlayer() == null) return null;
		Actor actor = client.getLocalPlayer().getInteracting();
		if (!(actor instanceof NPC)) return null;
		NPC npc = (NPC) actor;
		AnchorModels.Source source = new AnchorModels.Source();
		source.type = "npc"; source.id = npc.getId(); source.name = npc.getName();
		return source;
	}

	private void rememberBoss(String name)
	{
		recentBossSource = new AnchorModels.Source();
		recentBossSource.type = BossRegistry.isRaid(name) ? "event" : "npc";
		recentBossSource.name = name;
		recentBossAt = System.currentTimeMillis();
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
