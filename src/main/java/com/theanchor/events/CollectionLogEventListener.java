/*
 * Portions adapted from the Llama Club and Terpinheimer collection-log notifiers.
 * See LICENSES/llama-LICENSE.txt and LICENSES/terpinheimer-LICENSE.txt.
 */
package com.theanchor.events;

import com.theanchor.evidence.EventPipeline;
import com.theanchor.model.AnchorModels;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

@Singleton
public class CollectionLogEventListener
{
	private static final Pattern PATTERN = Pattern.compile("New item added to your collection log: (.+)", Pattern.CASE_INSENSITIVE);
	@Inject private EventPipeline pipeline;
	@Inject private Client client;
	private final AtomicBoolean popupStarted = new AtomicBoolean();

	@Subscribe public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) return;
		Matcher matcher = PATTERN.matcher(Text.removeTags(event.getMessage())); if (!matcher.find()) return;
		String item = matcher.group(1).trim(); HashMap<String, Object> details = new HashMap<>(); details.put("itemName", item); details.put("detectionSource", "chat");
		pipeline.capture("collection_log", item.toLowerCase(), null, null, details, false);
	}

	@Subscribe public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.NOTIFICATION_START) { popupStarted.set(true); return; }
		if (event.getScriptId() != ScriptID.NOTIFICATION_DELAY || !popupStarted.getAndSet(false)) return;
		String title = client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE);
		String body = client.getVarcStrValue(VarClientID.NOTIFICATION_MAIN);
		if (!"Collection log".equalsIgnoreCase(title) || body == null) return;
		String item = body.replaceFirst("(?i)^New item:\\s*", "").trim();
		if (item.isEmpty()) return;
		HashMap<String, Object> details = new HashMap<>(); details.put("itemName", item); details.put("detectionSource", "popup");
		pipeline.capture("collection_log", item.toLowerCase(), null, null, details, false);
	}
}
