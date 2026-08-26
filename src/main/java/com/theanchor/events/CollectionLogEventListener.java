/*
 * Portions adapted from the Llama Club and Terpinheimer collection-log notifiers.
 * See LICENSES/llama-LICENSE.txt and LICENSES/terpinheimer-LICENSE.txt.
 */
package com.theanchor.events;

import com.theanchor.evidence.EventPipeline;
import com.theanchor.model.AnchorModels;
import com.theanchor.service.LootEligibility;
import com.theanchor.service.RulesService;
import com.theanchor.service.BingoService;
import com.theanchor.service.PartyTracker;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.Text;
import net.runelite.http.api.item.ItemPrice;

@Singleton
public class CollectionLogEventListener
{
	private static final Pattern PATTERN = Pattern.compile("New item added to your collection log: (.+)", Pattern.CASE_INSENSITIVE);
	@Inject private EventPipeline pipeline;
	@Inject private Client client;
	@Inject private ItemManager itemManager;
	@Inject private RulesService rules;
	@Inject private BingoService bingo;
	@Inject private PartyTracker parties;
	@Inject private ScheduledExecutorService executor;
	@Inject private ClientThread clientThread;
	private final AtomicBoolean popupStarted = new AtomicBoolean();

	@Subscribe public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) return;
		Matcher matcher = PATTERN.matcher(Text.removeTags(event.getMessage())); if (!matcher.find()) return;
		scheduleCapture(normalizeItemName(matcher.group(1)), "chat");
	}

	@Subscribe public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.NOTIFICATION_START) { popupStarted.set(true); return; }
		if (event.getScriptId() != ScriptID.NOTIFICATION_DELAY || !popupStarted.getAndSet(false)) return;
		String title = client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE);
		String body = client.getVarcStrValue(VarClientID.NOTIFICATION_MAIN);
		if (!"Collection log".equalsIgnoreCase(title) || body == null) return;
		String item = normalizeItemName(body).replaceFirst("(?i)^New item:\\s*", "").trim();
		if (item.isEmpty()) return;
		scheduleCapture(item, "popup");
	}

	private void scheduleCapture(String itemName, String detectionSource)
	{
		if (itemName.isEmpty()) return;
		// RuneLite can publish the collection-log message before its raid LootReceived event.
		// Waiting one game tick lets that event establish the real encounter category and win
		// loot deduplication while the collection-log notification is still visible.
		executor.schedule(() -> clientThread.invokeLater(() -> capture(itemName, detectionSource)), 600, TimeUnit.MILLISECONDS);
	}

	private void capture(String itemName, String detectionSource)
	{
		if (itemName.isEmpty()) return;
		List<AnchorModels.Item> items = collectionLogItems(itemName);
		HashMap<String, Object> details = new HashMap<>();
		details.put("itemName", itemName);
		details.put("detectionSource", detectionSource);
		details.put("autoSubmit", true);
		String dedupeKey = itemName.toLowerCase(java.util.Locale.ROOT);
		AnchorModels.Source source = parties.resolveCollectionLogSource(collectionLogSource());
		for (String eventType : eventTypesFor(items, rules.current()))
			pipeline.capture(eventType, dedupeKey, source, items, details, "loot".equals(eventType));
		if (bingo.shouldCaptureCollectionLog(items))
		{
			HashMap<String, Object> bingoDetails = new HashMap<>(details);
			bingo.decorateDetails(bingoDetails);
			pipeline.capture(bingo.eventType(), dedupeKey, source, items, bingoDetails, true,
				bingo.rulesVersion(), bingo.screenshotRequired(), bingo.finalizeSubmission());
		}
	}

	static List<String> eventTypesFor(List<AnchorModels.Item> items, AnchorModels.Rules rules)
	{
		if (items != null)
			for (AnchorModels.Item item : items)
				if (LootEligibility.meetsMinimumValue(item, rules))
					return java.util.Arrays.asList("collection_log", "loot");
		return Collections.singletonList("collection_log");
	}

	static String normalizeItemName(String value)
	{
		return value == null ? "" : Text.removeTags(value).trim();
	}

	static AnchorModels.Source collectionLogSource()
	{
		AnchorModels.Source source = new AnchorModels.Source();
		source.type = "collection_log";
		source.name = "Collection Log";
		return source;
	}

	private List<AnchorModels.Item> collectionLogItems(String itemName)
	{
		try
		{
			int itemId = 0;
			List<ItemPrice> matches = itemManager != null ? itemManager.search(itemName) : null;
			if (matches != null && !matches.isEmpty())
			{
				itemId = matches.get(0).getId();
				for (ItemPrice match : matches)
				{
					ItemComposition composition = itemManager.getItemComposition(match.getId());
					if (composition != null && itemName.equalsIgnoreCase(composition.getName())) { itemId = match.getId(); break; }
				}
			}

			AnchorModels.Item item = new AnchorModels.Item();
			item.itemId = itemId;
			item.name = itemName;
			item.quantity = 1;

			if (itemId > 0 && itemManager != null)
			{
				long price = itemManager.getItemPrice(itemId);
				if (price <= 0)
				{
					try { price = itemManager.getItemPriceWithSource(itemId, true); } catch (Throwable ignored) {}
				}
				item.unitGeValue = price;
				item.totalGeValue = price;
				ItemComposition composition = itemManager.getItemComposition(itemId);
				if (composition != null) { item.tradeable = composition.isTradeable(); item.stackable = composition.isStackable(); }
			}

			return Collections.singletonList(item);
		}
		catch (RuntimeException ignored)
		{
			AnchorModels.Item fallback = new AnchorModels.Item();
			fallback.name = itemName;
			fallback.quantity = 1;
			return Collections.singletonList(fallback);
		}
	}
}
