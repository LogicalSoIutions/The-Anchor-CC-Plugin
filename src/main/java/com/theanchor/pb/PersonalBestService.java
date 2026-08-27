/*
 * PB profile and Adventure Log parsing adapted from the PB Tracker Sync plugin.
 * See LICENSES/pb-tracker-sync-LICENSE.txt.
 */
package com.theanchor.pb;

import com.theanchor.api.AnchorApiClient;
import com.theanchor.evidence.EventPipeline;
import com.theanchor.model.AnchorModels;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;

@Slf4j
@Singleton
public class PersonalBestService
{
	private static final String GROUP = "personalbest";
	private static final Pattern RECORD = Pattern.compile("^Fastest (?<descriptor>.+): (?<value>-|[0-9:]+(?:\\.[0-9]+)?)$");
	private static final Pattern TEAM = Pattern.compile("(\\d+)\\+? player", Pattern.CASE_INSENSITIVE);
	private static final Set<String> DUPLICATE_KEYS = new HashSet<>(java.util.Arrays.asList(
		"tztok-jad", "tzkal-zuk", "sol heredit", "hueycoatl", "gauntlet", "corrupted gauntlet", "nightmare",
		"tzhaar fight cave", "inferno", "fortis colosseum", "the gauntlet", "the corrupted gauntlet",
		"the hueycoatl", "phosani's nightmare", "phosanis nightmare"
	));

	static boolean isAmbiguous(String key)
	{
		String lower = key.toLowerCase(Locale.ROOT).trim();
		if (DUPLICATE_KEYS.contains(lower)) return true;
		return lower.startsWith("chambers of xeric")
			|| lower.startsWith("theatre of blood")
			|| lower.startsWith("tombs of amascut")
			|| lower.startsWith("nightmare");
	}
	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ConfigManager configManager;
	@Inject private AnchorApiClient api;
	@Inject private EventPipeline pipeline;
	private final Map<String, Double> known = new HashMap<>();
	private volatile String status = "Never synced";
	private String lastFingerprint;
	private boolean journalLoaded;
	private Scoreboard pendingScoreboard;

	public String status() { return status; }
	public void onLogin()
	{
		hydrateKnown();
	}

	@Subscribe public void onConfigChanged(ConfigChanged event)
	{
		if (!GROUP.equals(event.getGroup()) || event.getNewValue() == null || isAmbiguous(event.getKey())) return;
		double seconds; try { seconds = Double.parseDouble(event.getNewValue()); } catch (NumberFormatException e) { return; }
		AnchorModels.PbRecord record = fromKey(event.getKey(), seconds);
		String key = recordKey(record);
		Double previous = known.put(key, seconds);
		if (previous != null && seconds >= previous) return;
		sync(java.util.Collections.singletonList(record), false, true, previous);
	}

	@Subscribe public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.JOURNALSCROLL)
		{
			journalLoaded = true;
		}
		Scoreboard scoreboard = scoreboardFor(event.getGroupId()); if (scoreboard != null) pendingScoreboard = scoreboard;
	}

	@Subscribe public void onGameTick(GameTick event)
	{
		if (pendingScoreboard != null) { Scoreboard scoreboard = pendingScoreboard; pendingScoreboard = null; readScoreboard(scoreboard); }
		if (!journalLoaded) return; journalLoaded = false;
		Widget title = client.getWidget(InterfaceID.Journalscroll.TITLE);
		String owner = title == null ? null : Text.removeTags(title.getText()).replaceFirst("^The Exploits of ", "").trim();
		if (owner != null && !owner.isEmpty() && client.getLocalPlayer() != null && !owner.equalsIgnoreCase(client.getLocalPlayer().getName()) && !owner.equalsIgnoreCase("Counters"))
		{
			return;
		}
		Widget parent = client.getWidget(InterfaceID.Journalscroll.TEXTLAYER);
		Widget[] children = collectChildren(parent);
		if (children.length == 0) return;
		List<AnchorModels.PbRecord> records = parseAdventureLog(children);
		if (!records.isEmpty())
		{
			for (AnchorModels.PbRecord record : records)
			{
				String key = recordKey(record);
				if (record.durationMillis != null)
				{
					Double seconds = record.durationMillis / 1000.0;
					known.put(key, seconds);
				}
			}
			String fingerprint = fingerprint(records);
			if (fingerprint.equals(lastFingerprint))
			{
				return;
			}
			lastFingerprint = fingerprint;
			sync(records, true, false, null, true);
		}
	}

	static Widget[] collectChildren(Widget parent)
	{
		if (parent == null) return new Widget[0];
		List<Widget> list = new ArrayList<>();
		addNonNullChildren(list, parent.getStaticChildren());
		addNonNullChildren(list, parent.getDynamicChildren());
		addNonNullChildren(list, parent.getNestedChildren());
		addNonNullChildren(list, parent.getChildren());
		return list.toArray(new Widget[0]);
	}

	private static void addNonNullChildren(List<Widget> list, Widget[] children)
	{
		if (children != null)
		{
			for (Widget child : children)
			{
				if (child != null) list.add(child);
			}
		}
	}

	private void readScoreboard(Scoreboard scoreboard)
	{
		Widget titleWidget = client.getWidget(scoreboard.titleComponentId); Widget pbWidget = client.getWidget(scoreboard.pbComponentId);
		String title = titleWidget == null ? "" : Text.removeTags(titleWidget.getText()); String raw = pbWidget == null ? null : Text.removeTags(pbWidget.getText()).trim();
		Double seconds = raw == null ? null : parseTime(raw); if (!title.toLowerCase(Locale.ROOT).contains(scoreboard.boss.toLowerCase(Locale.ROOT)) || seconds == null) return;
		String key = scoreboard.boss + (title.toLowerCase(Locale.ROOT).contains("awakened") ? " (awakened)" : "");
		Double previous = known.put(key, seconds); if (previous != null && seconds >= previous) return;
		sync(java.util.Collections.singletonList(fromKey(key, seconds)), false, true, previous);
	}

	private static Scoreboard scoreboardFor(int groupId)
	{
		switch (groupId)
		{
			case InterfaceID.DUKE_SUCELLUS_SCOREBOARD: return new Scoreboard("Duke Sucellus", InterfaceID.DukeSucellusScoreboard.TITLE_TEXT, InterfaceID.DukeSucellusScoreboard.PBT_CONTENT);
			case InterfaceID.LEVIATHAN_SCOREBOARD: return new Scoreboard("Leviathan", InterfaceID.LeviathanScoreboard.TITLE_TEXT, InterfaceID.LeviathanScoreboard.PBT_CONTENT);
			case InterfaceID.WHISPERER_SCOREBOARD: return new Scoreboard("Whisperer", InterfaceID.WhispererScoreboard.TITLE_TEXT, InterfaceID.WhispererScoreboard.PBT_CONTENT);
			case InterfaceID.VARDORVIS_SCOREBOARD: return new Scoreboard("Vardorvis", InterfaceID.VardorvisScoreboard.TITLE_TEXT, InterfaceID.VardorvisScoreboard.PBT_CONTENT);
			default: return null;
		}
	}

	private static final class Scoreboard
	{
		final String boss; final int titleComponentId; final int pbComponentId;
		Scoreboard(String boss, int titleComponentId, int pbComponentId) { this.boss = boss; this.titleComponentId = titleComponentId; this.pbComponentId = pbComponentId; }
	}

	public void syncAll(boolean force)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) { status = "Log in before syncing PBs"; return; }
		String profile = configManager.getRSProfileKey(); if (profile == null) { status = "RuneLite profile is unavailable"; return; }
		List<AnchorModels.PbRecord> records = new ArrayList<>();
		for (String key : configManager.getRSProfileConfigurationKeys(GROUP, profile, ""))
		{
			Double seconds = configManager.getRSProfileConfiguration(GROUP, key, double.class);
			if (seconds != null && !isAmbiguous(key)) records.add(fromKey(key, seconds));
		}
		String fingerprint = fingerprint(records);
		if (!force && fingerprint.equals(lastFingerprint)) { status = "PBs already up to date"; return; }
		lastFingerprint = fingerprint; sync(records, true, false, null);
	}

	private void sync(List<AnchorModels.PbRecord> records, boolean bulk, boolean evidence, Double previous)
	{
		sync(records, bulk, evidence, previous, false);
	}

	private void sync(List<AnchorModels.PbRecord> records, boolean bulk, boolean evidence, Double previous, boolean fromAdventureLog)
	{
		if ((!bulk && records.isEmpty()) || client.getLocalPlayer() == null) return;
		if (fromAdventureLog)
		{
			message("Syncing your adventure log with The Anchor...");
		}
		AnchorModels.PbBulkRequest request = new AnchorModels.PbBulkRequest(); request.syncId = UUID.randomUUID().toString(); request.capturedAt = Instant.now().toString();
		request.player = new AnchorModels.PlayerIdentity(); request.player.name = client.getLocalPlayer().getName(); request.player.accountHash = String.valueOf(client.getAccountHash()); request.records.addAll(records);
		status = "Syncing " + records.size() + " PB" + (records.size() == 1 ? "" : "s") + "…";
		api.syncPbs(request, bulk, result ->
		{
			status = result.isSuccessful() ? "PBs synced " + Instant.now() : result.error;
			if (!result.isSuccessful()) log.error(
				"Anchor PB sync failed: player={}, syncId={}, error={}", request.player.name, request.syncId, result.error);

			if (fromAdventureLog)
			{
				clientThread.invokeLater(() ->
				{
					if (result.isSuccessful())
					{
						message("Your adventure log has been synced with The Anchor.");
					}
					else
					{
						message("The Anchor queued your adventure log and will retry it automatically.");
					}
				});
			}
		});
		if (evidence)
		{
			AnchorModels.PbRecord record = records.get(0); Map<String, Object> details = new HashMap<>(); details.put("record", record); details.put("autoSubmit", true); if (previous != null) details.put("previousDurationMillis", Math.round(previous * 1000));
			// Team size is already part of PbRecord when RuneLite provides it. PB evidence never needs loot-split metadata.
			pipeline.capture("personal_best", record.activity + '|' + record.durationMillis, null, null, details, false);
		}
	}

	private void message(String text)
	{
		client.addChatMessage(ChatMessageType.CONSOLE, "The Anchor", text, "The Anchor");
	}

	private void hydrateKnown()
	{
		known.clear(); String profile = configManager.getRSProfileKey(); if (profile == null) return;
		for (String key : configManager.getRSProfileConfigurationKeys(GROUP, profile, ""))
		{
			Double value = configManager.getRSProfileConfiguration(GROUP, key, double.class);
			if (value != null && !isAmbiguous(key))
			{
				AnchorModels.PbRecord record = fromKey(key, value);
				known.put(recordKey(record), value);
			}
		}
	}

	static String recordKey(AnchorModels.PbRecord record)
	{
		return record.activity + '|' + record.variant + '|' + record.teamSize + '|' + record.recordType + '|' + record.value;
	}

	static AnchorModels.PbRecord fromKey(String key, double seconds)
	{
		AnchorModels.PbRecord record = new AnchorModels.PbRecord(); record.activity = canonical(key); record.durationMillis = Math.round(seconds * 1000);
		Matcher team = TEAM.matcher(key);
		if (team.find()) record.teamSize = Integer.parseInt(team.group(1));
		else if (key.toLowerCase(Locale.ROOT).contains("solo")) record.teamSize = 1;
		String lower = key.toLowerCase(Locale.ROOT);
		if (lower.contains("awakened")) record.variant = "awakened";
		else if (lower.contains("challenge")) record.variant = "challenge_mode";
		else if (lower.contains("expert")) record.variant = "expert";
		else if (lower.contains("entry")) record.variant = "entry";
		else if (lower.contains("hard")) record.variant = "hard";
		return record;
	}

	private static final Set<String> SECTION_CATEGORIES = new HashSet<>(java.util.Arrays.asList("minigames", "bosses", "skilling bosses", "raids", "other"));

	static List<AnchorModels.PbRecord> parseAdventureLog(Widget[] children)
	{
		List<AnchorModels.PbRecord> result = new ArrayList<>(); String heading = null;
		List<String> lines = new ArrayList<>();
		if (children != null)
		{
			for (Widget child : children)
			{
				if (child == null || child.getText() == null) continue;
				String text = Text.removeTags(child.getText()).replace('\u00A0', ' ').trim();
				if (!text.isEmpty()) lines.add(text);
			}
		}
		for (int i = 0; i < lines.size(); i++)
		{
			String current = lines.get(i);
			if (i + 1 < lines.size())
			{
				String next = lines.get(i + 1);
				if (next.matches("-|[0-9:]+(?:\\.[0-9]+)?") && (current.endsWith(":") || current.startsWith("Fastest") || current.contains(":")))
				{
					lines.set(i, current + " " + next);
					lines.remove(i + 1);
				}
			}
		}
		for (String line : lines)
		{
			if (line == null || line.isEmpty()) { continue; }
			String lower = line.toLowerCase(Locale.ROOT);
			if (SECTION_CATEGORIES.contains(lower))
			{
				heading = null;
				continue;
			}
			if (line.endsWith(":"))
			{
				heading = line.substring(0, line.length() - 1).trim();
				continue;
			}
			Matcher matcher = RECORD.matcher(line);
			if (matcher.matches())
			{
				if (heading == null || "-".equals(matcher.group("value"))) continue;
				Double seconds = parseTime(matcher.group("value"));
				if (seconds == null) continue;
				AnchorModels.PbRecord record = new AnchorModels.PbRecord();
				record.activity = canonical(heading);
				record.durationMillis = Math.round(seconds * 1000);
				String descriptor = matcher.group("descriptor");
				record.recordType = descriptor.toLowerCase(Locale.ROOT).contains("room") ? "room"
					: descriptor.toLowerCase(Locale.ROOT).contains("wave") ? "wave" : "overall";
				Matcher team = TEAM.matcher(descriptor);
				if (team.find()) record.teamSize = Integer.parseInt(team.group(1));
				else if (descriptor.toLowerCase(Locale.ROOT).contains("solo") && hasTeamSizeRecords(heading)) record.teamSize = 1;

				String combined = (heading + " " + descriptor).toLowerCase(Locale.ROOT);
				if (combined.contains("awakened")) record.variant = "awakened";
				else if (combined.contains("challenge")) record.variant = "challenge_mode";
				else if (combined.contains("expert")) record.variant = "expert";
				else if (combined.contains("entry")) record.variant = "entry";
				else if (combined.contains("hard")) record.variant = "hard";

				result.add(record);
			}
			else if (line.contains(":") || lower.contains("kill score of"))
			{
				parseStatLine(heading, line, result);
			}
			else
			{
				heading = line.trim();
			}
		}
		return result;
	}

	/**
	 * RuneLite describes ordinary single-player boss times as "solo" too. Those
	 * profile records have no team size, so assigning {@code 1} here gives the
	 * Adventure Log upload a different identity from the login upload.
	 */
	private static boolean hasTeamSizeRecords(String activity)
	{
		if (activity == null) return false;
		String lower = activity.toLowerCase(Locale.ROOT);
		return lower.startsWith("chambers of xeric")
			|| lower.startsWith("theatre of blood")
			|| lower.startsWith("tombs of amascut")
			|| lower.contains("nightmare");
	}

	static void parseStatLine(String heading, String line, List<AnchorModels.PbRecord> result)
	{
		if (line == null || line.trim().isEmpty()) return;
		String[] parts = line.split(",\\s*(?=[A-Za-z0-9\\s-]+:|with a kill)");
		for (String part : parts)
		{
			part = part.trim();
			if (part.isEmpty()) continue;
			if (part.contains(":"))
			{
				String[] kv = part.split(":", 2);
				String key = kv[0].trim();
				String val = kv[1].trim();

				AnchorModels.PbRecord record = new AnchorModels.PbRecord();
				record.activity = canonical(heading != null ? heading : key);
				record.value = val;

				String keyLower = key.toLowerCase(Locale.ROOT);
				if (keyLower.equals("wins")) { record.recordType = "wins"; record.count = parseLongSafe(val); }
				else if (keyLower.equals("losses")) { record.recordType = "losses"; record.count = parseLongSafe(val); }
				else if (keyLower.equals("rank")) { record.recordType = "rank"; record.count = parseLongSafe(val); }
				else if (keyLower.equals("kills")) { record.recordType = "kills"; record.count = parseLongSafe(val); }
				else if (keyLower.contains("gamble")) { record.recordType = "gambles"; record.count = parseLongSafe(val); }
				else if (keyLower.contains("spirits rested")) { record.recordType = "count"; record.count = parseLongSafe(val); }
				else if (java.util.Arrays.asList("beginner", "easy", "medium", "hard", "elite", "master").contains(keyLower))
				{
					record.activity = canonical(heading != null ? heading : "Treasure Trails");
					record.variant = keyLower;
					record.recordType = "clues";
					record.count = parseLongSafe(val);
				}
				else
				{
					record.recordType = keyLower.replaceAll("[^a-z0-9_]+", "_");
					record.count = parseLongSafe(val);
				}
				result.add(record);
			}
			else if (part.toLowerCase(Locale.ROOT).contains("kill score of"))
			{
				Matcher m = Pattern.compile("kill score of\\s+([0-9,]+)", Pattern.CASE_INSENSITIVE).matcher(part);
				if (m.find())
				{
					AnchorModels.PbRecord record = new AnchorModels.PbRecord();
					record.activity = canonical(heading != null ? heading : "Order of the White Knights");
					record.recordType = "score";
					record.value = m.group(1);
					record.count = parseLongSafe(m.group(1));
					result.add(record);
				}
			}
		}
	}

	private static Long parseLongSafe(String text)
	{
		if (text == null) return null;
		try { return Long.parseLong(text.replaceAll("[^0-9]", "")); }
		catch (NumberFormatException e) { return null; }
	}

	static Double parseTime(String value)
	{
		try { String[] p = value.split(":"); if (p.length == 2) return Integer.parseInt(p[0]) * 60 + Double.parseDouble(p[1]); if (p.length == 3) return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Double.parseDouble(p[2]); return Double.parseDouble(value); }
		catch (NumberFormatException e) { return null; }
	}

	private static String canonical(String key)
	{
		String lower = key.toLowerCase(Locale.ROOT); if (lower.equals("the leviathan") || lower.equals("levi")) return "Leviathan"; if (lower.equals("duke")) return "Duke Sucellus"; if (lower.equals("the whisperer") || lower.equals("whisp")) return "Whisperer"; if (lower.equals("vard")) return "Vardorvis"; return key;
	}

	private static String fingerprint(List<AnchorModels.PbRecord> records)
	{
		TreeMap<String, Long> sorted = new TreeMap<>();
		for (AnchorModels.PbRecord r : records)
		{
			long val = r.durationMillis != null ? r.durationMillis : (r.count != null ? r.count : 0L);
			sorted.put(r.activity + '|' + r.variant + '|' + r.teamSize + '|' + r.recordType + '|' + r.value, val);
		}
		return sorted.toString();
	}
}
