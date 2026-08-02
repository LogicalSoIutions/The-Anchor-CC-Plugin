/*
 * Portions adapted from the Llama Club pet notification code.
 * See LICENSES/llama-LICENSE.txt.
 */
package com.theanchor.events;

import com.theanchor.evidence.EventPipeline;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

@Singleton
public class PetEventListener
{
	private static final Pattern PATTERN = Pattern.compile("You (?:have a funny feeling like you(?:'|’)re being followed(?: by (.+?))?|have a funny feeling like you would have been followed|feel something weird sneaking into your backpack)[.!…]*$", Pattern.CASE_INSENSITIVE);
	@Inject private EventPipeline pipeline;

	@Subscribe public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) return;
		String message = Text.removeTags(event.getMessage()); Matcher matcher = PATTERN.matcher(message); if (!matcher.find()) return;
		HashMap<String, Object> details = new HashMap<>(); details.put("message", message); details.put("petName", matcher.group(1) == null ? "Unknown pet" : matcher.group(1).trim());
		details.put("obtained", !message.toLowerCase().contains("would have been followed"));
		pipeline.capture("pet", message.toLowerCase(), null, null, details, false);
	}
}
