/*
 * Portions adapted from the Llama Club and Terpinheimer collection-log notifiers.
 * See LICENSES/llama-LICENSE.txt and LICENSES/terpinheimer-LICENSE.txt.
 */
package com.theanchor.events;

import com.theanchor.evidence.EventPipeline;
import com.theanchor.model.AnchorModels;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;
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
import net.runelite.client.util.Text;
import net.runelite.http.api.item.ItemPrice;

@Singleton
public class CollectionLogEventListener
{
	private static final Pattern PATTERN = Pattern.compile("New item added to your collection log: (.+)", Pattern.CASE_INSENSITIVE);
	@Inject private EventPipeline pipeline;
	@Inject private Client client;
	@Inject private ItemManager itemManager;
	private final AtomicBoolean popupStarted = new AtomicBoolean();

	@Subscribe public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) return;
		Matcher matcher = PATTERN.matcher(Text.removeTags(event.getMessage())); if (!matcher.find()) return;
		String item = normalizeItemName(matcher.group(1)); HashMap<String, Object> details = new HashMap<>(); details.put("itemName", item); details.put("detectionSource", "chat");
		pipeline.capture("collection_log", item.toLowerCase(), collectionLogSource(), collectionLogItems(item), details, false);
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
		HashMap<String, Object> details = new HashMap<>(); details.put("itemName", item); details.put("detectionSource", "popup");
		pipeline.capture("collection_log", item.toLowerCase(), collectionLogSource(), collectionLogItems(item), details, false);
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
			List<ItemPrice> matches = itemManager.search(itemName);
			if (matches == null || matches.isEmpty()) return Collections.emptyList();
			int itemId = matches.get(0).getId();
			for (ItemPrice match : matches)
			{
				ItemComposition composition = itemManager.getItemComposition(match.getId());
				if (composition != null && itemName.equalsIgnoreCase(composition.getName())) { itemId = match.getId(); break; }
			}
			ItemComposition composition = itemManager.getItemComposition(itemId);
			AnchorModels.Item item = new AnchorModels.Item();
			item.itemId = itemId; item.name = itemName; item.quantity = 1;
			item.unitGeValue = itemManager.getItemPrice(itemId); item.totalGeValue = item.unitGeValue;
			if (composition != null) { item.tradeable = composition.isTradeable(); item.stackable = composition.isStackable(); }
			return Collections.singletonList(item);
		}
		catch (RuntimeException ignored)
		{
			return Collections.emptyList();
		}
	}
}
