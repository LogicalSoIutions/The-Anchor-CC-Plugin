/*
 * Portions adapted from the Llama Club combat-achievement synchronization code.
 * See LICENSES/llama-LICENSE.txt.
 */
package com.theanchor.events;

import com.theanchor.evidence.EventPipeline;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

@Singleton
public class CombatTierEventListener
{
	private static final Pattern TASK = Pattern.compile("Congratulations, you've completed an? (\\w+) combat task: (.+)\\.", Pattern.CASE_INSENSITIVE);
	private static final Map<String, Integer> THRESHOLDS = new LinkedHashMap<>();
	static { THRESHOLDS.put("Easy", VarbitID.CA_THRESHOLD_EASY); THRESHOLDS.put("Medium", VarbitID.CA_THRESHOLD_MEDIUM); THRESHOLDS.put("Hard", VarbitID.CA_THRESHOLD_HARD); THRESHOLDS.put("Elite", VarbitID.CA_THRESHOLD_ELITE); THRESHOLDS.put("Master", VarbitID.CA_THRESHOLD_MASTER); THRESHOLDS.put("Grandmaster", VarbitID.CA_THRESHOLD_GRANDMASTER); }
	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private EventPipeline pipeline;
	private final NavigableMap<Integer, String> tierByPoints = new TreeMap<>();

	@Subscribe public void onGameTick(GameTick event) { if (tierByPoints.size() < THRESHOLDS.size()) hydrate(); }

	@Subscribe public void onChatMessage(ChatMessage event)
	{
		Matcher matcher = TASK.matcher(Text.removeTags(event.getMessage())); if (!matcher.find()) return;
		String taskTier = matcher.group(1); String taskName = matcher.group(2);
		clientThread.invokeAtTickEnd(() ->
		{
			hydrate(); int total = client.getVarbitValue(VarbitID.CA_POINTS);
			Map.Entry<Integer, String> completed = tierByPoints.floorEntry(total);
			if (completed == null || total - pointsForTaskTier(taskTier) >= completed.getKey()) return;
			HashMap<String, Object> details = new HashMap<>(); details.put("completedTier", completed.getValue()); details.put("taskName", taskName); details.put("totalPoints", total);
			pipeline.capture("combat_achievement_tier", completed.getValue() + '|' + total, null, null, details, false);
		});
	}

	private void hydrate() { for (Map.Entry<String, Integer> entry : THRESHOLDS.entrySet()) { int value = client.getVarbitValue(entry.getValue()); if (value > 0) tierByPoints.put(value, entry.getKey()); } }
	private static int pointsForTaskTier(String tier)
	{
		switch (tier.toLowerCase()) { case "easy": return 1; case "medium": return 2; case "hard": return 3; case "elite": return 4; case "master": return 5; case "grandmaster": return 6; default: return 0; }
	}
}
