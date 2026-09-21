/*
 * PB profile and Adventure Log parsing adapted from the PB Tracker Sync plugin.
 * See LICENSES/pb-tracker-sync-LICENSE.txt.
 */
package com.theanchor.pb;

import com.theanchor.api.AnchorApiClient;
import com.theanchor.evidence.EventPipeline;
import com.theanchor.model.AnchorModels;
import com.theanchor.service.PartyTracker;
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
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.ScriptID;
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
	private static final Pattern SPECIAL_ACTIVITY_KILL_COUNT = Pattern.compile(
		"^Your (?<boss>TzTok-Jad|TzKal-Zuk|Sol Heredit|(?:The )?Corrupted Gauntlet) "
			+ "(?:kill |completion )?count is: [0-9,]+\\.?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern NEW_PB_DURATION = Pattern.compile(
		"^Duration:?\\s*(?<time>[0-9:]+(?:\\.[0-9]+)?)\\.?\\s*\\(new personal best\\)\\.?$",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern COX_COMPLETION = Pattern.compile(
		"Team size:\\s*(?<team>[1-9][0-9]*|Solo)\\s*(?:players?)?\\s+Duration:\\s*(?<time>[0-9:]+(?:\\.[0-9]+)?)"
			+ "(?:\\s+Personal best:\\s*[0-9:]+(?:\\.[0-9]+)?|\\s*\\(new personal best\\))"
			+ "(?:\\s+Olm duration:.*)?$",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern RAID_COMPLETION_DURATION = Pattern.compile(
		"(?<!total\\s)completion time:\\s*(?<time>[0-9:]+(?:\\.[0-9]+)?)(?:\\.|\\s)",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern TOTAL_RAID_COMPLETION_DURATION = Pattern.compile(
		"total\\s+completion time:\\s*(?<time>[0-9:]+(?:\\.[0-9]+)?)(?:\\.|\\s)",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern TOB_HARD_MODE_COMPLETION = Pattern.compile(
		"^Wave 'The Final Challenge' \\(Hard Mode\\) complete!?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern OBSERVED_ACTIVITY_DURATION = Pattern.compile(
		"(?:Fight |Challenge |Corrupted challenge )?duration:?\\s*(?<time>[0-9:]+(?:\\.[0-9]+)?)"
			+ "(?:\\.\\s*Personal best:|\\s*\\(new personal best\\))",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern DOOM_COMPLETION_TITLE = Pattern.compile("^Level\\s+(?<level>[1-9][0-9]*)\\s+Complete!$",
		Pattern.CASE_INSENSITIVE);
	private static final long SPECIAL_ACTIVITY_TIMEOUT_MILLIS = 15_000L;
	private static final long RAID_COMPLETION_TIMEOUT_MILLIS = 15_000L;
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
	@Inject private PvmDiaryContractService diaryContract;
	@Inject private PartyTracker parties;
	private final Map<String, Double> known = new HashMap<>();
	private volatile String status = "Never synced";
	private String lastFingerprint;
	private boolean journalLoaded;
	private Scoreboard pendingScoreboard;
	private String pendingSpecialActivity;
	private long pendingSpecialActivityAt;
	private String pendingRaidActivity;
	/** ToB diary entries use the raid completion time, not the total completion time. */
	private Long pendingRaidCompletionDurationMillis;
	private Long pendingRaidTotalDurationMillis;
	private Integer pendingRaidTeamSize;
	private Integer pendingRaidInvocation;
	private long pendingRaidAt;

	public String status() { return status; }
	public void onLogin()
	{
		pendingSpecialActivity = null;
		pendingRaidActivity = null;
		pendingRaidCompletionDurationMillis = null;
		pendingRaidTotalDurationMillis = null;
		pendingRaidAt = 0;
		hydrateKnown();
		diaryContract.refresh();
	}

	@Subscribe public void onConfigChanged(ConfigChanged event)
	{
		if (!GROUP.equals(event.getGroup()) || event.getNewValue() == null) return;
		// Do not defer raw raid or internally-named activity keys to the
		// Adventure Log: that widget only loads when a player opens it, which
		// left their new diary result undetected.
		double seconds; try { seconds = Double.parseDouble(event.getNewValue()); } catch (NumberFormatException e) { return; }
		AnchorModels.PbRecord record = diaryRecordFromKey(event.getKey(), seconds);
		String key = recordKey(record);
		Double previous = known.put(key, seconds);
		if (previous != null && seconds >= previous) return;
		sync(java.util.Collections.singletonList(record), false, true, previous, false, event.getKey());
	}

	@Subscribe public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) return;
		String rawMessage = event.getMessage().replaceAll("(?i)<br\\s*/?>", " ");
		String message = Text.removeTags(rawMessage).replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
		AnchorModels.PbRecord completion = coxCompletionRecord(message);
		if (completion != null)
		{
			if (client.getVarbitValue(VarbitID.RAIDS_CHALLENGE_MODE) > 0)
				completion.variant = "challenge_mode";
			captureRaidCompletion(completion, null);
			return;
		}

		String raidActivity = raidActivityFromCompletionMessage(message);
		if (raidActivity != null)
		{
			pendingRaidActivity = raidActivity;
			AnchorModels.Party party = parties.snapshot(raidActivity);
			pendingRaidTeamSize = party == null ? null : party.detectedPartySize;
			pendingRaidInvocation = raidActivity.startsWith("Tombs of Amascut")
				? client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL) : null;
			pendingRaidAt = System.currentTimeMillis();
		}
		Long raidCompletionDuration = completionTimeMillis(message);
		if (raidCompletionDuration != null)
		{
			pendingRaidCompletionDurationMillis = raidCompletionDuration;
			pendingRaidAt = System.currentTimeMillis();
		}
		Long totalRaidDuration = totalCompletionTimeMillis(message);
		if (totalRaidDuration != null)
		{
			pendingRaidTotalDurationMillis = totalRaidDuration;
			pendingRaidAt = System.currentTimeMillis();
		}
		if (tryCapturePendingRaid()) return;
		String activity = specialActivityFromKillCount(message);
		if (activity != null)
		{
			pendingSpecialActivity = activity;
			pendingSpecialActivityAt = System.currentTimeMillis();
			return;
		}

		Double observedSeconds = observedActivityDuration(message);
		Double seconds = newPersonalBestDuration(message);
		String pending = pendingSpecialActivity;
		long age = System.currentTimeMillis() - pendingSpecialActivityAt;
		if (observedSeconds != null && pending != null && age >= 0 && age <= SPECIAL_ACTIVITY_TIMEOUT_MILLIS)
			captureObservedSoloActivity(pending, observedSeconds);
		if (seconds == null) { if (observedSeconds != null) pendingSpecialActivity = null; return; }
		pendingSpecialActivity = null;
		if (pending == null || age < 0 || age > SPECIAL_ACTIVITY_TIMEOUT_MILLIS) return;

		AnchorModels.PbRecord record = diaryRecordFromKey(pending, seconds);
		String key = recordKey(record);
		Double previous = known.put(key, seconds);
		if (previous != null && seconds >= previous) return;
		sync(java.util.Collections.singletonList(record), false, true, previous, false, pending);
	}

	static String specialActivityFromKillCount(String message)
	{
		if (message == null) return null;
		Matcher matcher = SPECIAL_ACTIVITY_KILL_COUNT.matcher(message.trim());
		if (!matcher.matches()) return null;
		String boss = matcher.group("boss");
		if (boss.equalsIgnoreCase("TzTok-Jad")) return "TzHaar Fight Cave";
		if (boss.equalsIgnoreCase("TzKal-Zuk")) return "Inferno";
		if (boss.equalsIgnoreCase("Sol Heredit")) return "Fortis Colosseum";
		return "Corrupted Gauntlet";
	}

	static Double newPersonalBestDuration(String message)
	{
		if (message == null) return null;
		Matcher matcher = NEW_PB_DURATION.matcher(message.trim());
		return matcher.matches() ? parseTime(matcher.group("time")) : null;
	}

	static Double observedActivityDuration(String message)
	{
		if (message == null) return null;
		Matcher matcher = OBSERVED_ACTIVITY_DURATION.matcher(message.trim());
		return matcher.find() ? parseTime(matcher.group("time")) : null;
	}

	private void captureObservedSoloActivity(String activity, double seconds)
	{
		AnchorModels.PbRecord record = diaryRecordFromKey(activity, seconds);
		AnchorModels.Party party = verifiedDiaryParty(record);
		if (party == null) return;
		String sourceId = UUID.randomUUID().toString();
		Map<String, Object> details = diaryContract.detailsForObservedResult(record, activity, null, sourceId);
		if (details == null) return;
		details.put("record", record);
		details.put("autoSubmit", true);
		AnchorModels.Source source = new AnchorModels.Source(); source.type = "activity"; source.name = record.activity;
		pipeline.capture("diary", recordKey(record) + '|' + record.durationMillis, source, null, details, party);
	}

	static AnchorModels.PbRecord coxCompletionRecord(String message)
	{
		if (message == null) return null;
		Matcher matcher = COX_COMPLETION.matcher(message.trim());
		if (!matcher.find()) return null;
		Double seconds = parseTime(matcher.group("time"));
		if (seconds == null) return null;
		AnchorModels.PbRecord record = new AnchorModels.PbRecord();
		record.activity = "Chambers of Xeric";
		record.teamSize = "Solo".equalsIgnoreCase(matcher.group("team")) ? 1 : Integer.valueOf(matcher.group("team"));
		record.durationMillis = Math.round(seconds * 1000);
		return record;
	}

	static Long raidCompletionDurationMillis(String message)
	{
		Long completion = completionTimeMillis(message);
		return completion != null ? completion : totalCompletionTimeMillis(message);
	}

	private static Long completionTimeMillis(String message)
	{
		if (message == null) return null;
		Matcher matcher = RAID_COMPLETION_DURATION.matcher(message);
		if (!matcher.find()) return null;
		Double seconds = parseTime(matcher.group("time"));
		return seconds == null ? null : Math.round(seconds * 1000);
	}

	private static Long totalCompletionTimeMillis(String message)
	{
		if (message == null) return null;
		Matcher matcher = TOTAL_RAID_COMPLETION_DURATION.matcher(message);
		if (!matcher.find()) return null;
		Double seconds = parseTime(matcher.group("time"));
		return seconds == null ? null : Math.round(seconds * 1000);
	}

	static String raidActivityFromCompletionMessage(String message)
	{
		if (message == null) return null;
		if (TOB_HARD_MODE_COMPLETION.matcher(message.trim()).matches()) return "Theatre of Blood Hard Mode";
		if (!message.toLowerCase(Locale.ROOT).contains("count is:")) return null;
		String lower = message.toLowerCase(Locale.ROOT);
		if (lower.contains("theatre of blood"))
			return lower.contains("hard mode") ? "Theatre of Blood Hard Mode" : "Theatre of Blood";
		if (lower.contains("tombs of amascut"))
			return lower.contains("expert mode") ? "Tombs of Amascut Expert Mode" : "Tombs of Amascut";
		return null;
	}

	private boolean tryCapturePendingRaid()
	{
		long age = System.currentTimeMillis() - pendingRaidAt;
		if (pendingRaidActivity == null || pendingRaidTeamSize == null
			|| age < 0 || age > RAID_COMPLETION_TIMEOUT_MILLIS) return false;
		Long duration = pendingRaidActivity.startsWith("Theatre of Blood")
			? pendingRaidCompletionDurationMillis
			: pendingRaidTotalDurationMillis != null ? pendingRaidTotalDurationMillis : pendingRaidCompletionDurationMillis;
		if (duration == null) return false;
		AnchorModels.PbRecord record = new AnchorModels.PbRecord();
		record.activity = pendingRaidActivity;
		record.teamSize = pendingRaidTeamSize;
		record.durationMillis = duration;
		if (pendingRaidActivity.contains("Hard Mode")) record.variant = "hard";
		else if (pendingRaidActivity.contains("Expert Mode")) record.variant = "expert";
		Integer invocation = pendingRaidInvocation;
		pendingRaidActivity = null;
		pendingRaidCompletionDurationMillis = null;
		pendingRaidTotalDurationMillis = null;
		pendingRaidTeamSize = null;
		pendingRaidInvocation = null;
		captureRaidCompletion(record, invocation);
		return true;
	}

	private void captureRaidCompletion(AnchorModels.PbRecord record, Integer invocation)
	{
		AnchorModels.Party party = verifiedDiaryParty(record);
		if (party == null) return;
		String sourceId = UUID.randomUUID().toString();
		String rawKey = record.activity.toLowerCase(Locale.ROOT)
			+ ("challenge_mode".equals(record.variant) ? " challenge mode" : "")
			+ ' ' + record.teamSize + " players";
		Map<String, Object> details = diaryContract.detailsForObservedResult(record, rawKey, invocation, sourceId);
		if (details == null) return;
		details.put("record", record);
		details.put("autoSubmit", true);
		AnchorModels.Source source = new AnchorModels.Source();
		source.type = "raid";
		source.name = record.activity;
		pipeline.capture("diary", recordKey(record) + '|' + record.durationMillis,
			source, null, details, party);
	}

	/**
	 * Matches RuneLite's Loot Tracker: this script fires after the player claims
	 * Doom loot, while the completion dialog still contains the completed level.
	 */
	@Subscribe public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != ScriptID.DOM_LOOT_CLAIM) return;
		Widget frame = client.getWidget(InterfaceID.DomEndLevelUi.FRAME);
		Widget title = frame == null ? null : frame.getChild(1);
		String text = title == null ? null : Text.removeTags(title.getText()).trim();
		Matcher matcher = text == null ? null : DOOM_COMPLETION_TITLE.matcher(text);
		if (matcher == null || !matcher.matches()) return;
		captureCompletedDoomDelve(Integer.parseInt(matcher.group("level")));
	}

	private void captureCompletedDoomDelve(int completedDoomDelve)
	{
		if (!diaryContract.isDoomWaveEligible(completedDoomDelve)) return;
		Map<String, Object> details = diaryContract.detailsForWave(completedDoomDelve, UUID.randomUUID().toString());
		if (details == null) return;
		AnchorModels.PbRecord record = new AnchorModels.PbRecord(); record.activity = "Doom of Mokhaiotl"; record.teamSize = 1;
		AnchorModels.Party party = verifiedDiaryParty(record);
		if (party == null) return;
		details.put("autoSubmit", true);
		AnchorModels.Source source = new AnchorModels.Source(); source.type = "activity"; source.name = "Doom of Mokhaiotl";
		pipeline.capture("diary", "doom|wave|" + completedDoomDelve, source, null, details, party);
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
		sync(java.util.Collections.singletonList(fromKey(key, seconds)), false, true, previous, false, key);
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
			if (seconds != null) records.add(diaryRecordFromKey(key, seconds));
		}
		String fingerprint = fingerprint(records);
		if (!force && fingerprint.equals(lastFingerprint)) { status = "PBs already up to date"; return; }
		lastFingerprint = fingerprint; sync(records, true, false, null);
	}

	private void sync(List<AnchorModels.PbRecord> records, boolean bulk, boolean evidence, Double previous)
	{
		sync(records, bulk, evidence, previous, false, null);
	}

	private void sync(List<AnchorModels.PbRecord> records, boolean bulk, boolean evidence, Double previous, boolean fromAdventureLog)
	{
		sync(records, bulk, evidence, previous, fromAdventureLog, null);
	}

	private void sync(List<AnchorModels.PbRecord> records, boolean bulk, boolean evidence, Double previous,
		boolean fromAdventureLog, String rawKey)
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
			AnchorModels.PbRecord record = records.get(0);
			Map<String, Object> details = new HashMap<>();
			details.put("record", record);
			details.put("autoSubmit", true);
			if (previous != null) details.put("previousDurationMillis", Math.round(previous * 1000));
			String sourceId = UUID.randomUUID().toString();
			Map<String, Object> diaryDetails = diaryContract.detailsFor(record, rawKey, sourceId);
			if (diaryDetails != null)
			{
				AnchorModels.Party party = verifiedDiaryParty(record);
				if (party == null)
				{
					pipeline.capture("personal_best", recordKey(record) + '|' + record.durationMillis,
						null, null, details, false);
					return;
				}
				details.putAll(diaryDetails);
				AnchorModels.Source source = new AnchorModels.Source();
				source.type = "activity";
				source.name = record.activity;
				pipeline.capture("diary", recordKey(record) + '|' + record.durationMillis, source, null, details, party);
			}
			else
			{
				// A generic PB remains a normal PB when mode, exact party size,
				// invocation, or result type cannot be proven from the capture.
				pipeline.capture("personal_best", recordKey(record) + '|' + record.durationMillis, null, null, details, false);
			}
		}
	}

	/** A diary team must be the roster observed by the party tracker, not a guessed PB bucket. */
	private AnchorModels.Party verifiedDiaryParty(AnchorModels.PbRecord record)
	{
		if (record == null || record.teamSize == null) return null;
		AnchorModels.Party party = parties.snapshot(record.activity);
		if (party == null || party.detectedPartySize != record.teamSize.intValue()) return null;
		if (record.teamSize > 1 && !"high".equals(party.confidence)) return null;
		return party;
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
			if (value != null)
			{
				AnchorModels.PbRecord record = diaryRecordFromKey(key, value);
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

	/**
	 * The raw personalbest config is updated immediately when a run completes.
	 * Unlike the Adventure Log, it uses a few internal labels, so normalize
	 * those labels before sending a diary event. This also preserves the raid
	 * mode and team-size detail needed to match a diary activity.
	 */
	static AnchorModels.PbRecord diaryRecordFromKey(String key, double seconds)
	{
		AnchorModels.PbRecord record = fromKey(key, seconds);
		String lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
		if (lower.equals("sol heredit")) record.activity = "Fortis Colosseum";
		else if (lower.equals("tztok-jad")) record.activity = "TzHaar Fight Cave";
		else if (lower.equals("tzkal-zuk")) record.activity = "Inferno";
		else if (lower.equals("corrupted gauntlet")) record.activity = "Corrupted Gauntlet";
		else if (lower.equals("gauntlet")) record.activity = "Gauntlet";
		else if (lower.equals("hueycoatl")) record.activity = "Hueycoatl";
		if (lower.equals("sol heredit") || lower.equals("fortis colosseum")
			|| lower.equals("tztok-jad") || lower.equals("tzhaar fight cave")
			|| lower.equals("tzkal-zuk") || lower.equals("inferno")
			|| lower.equals("corrupted gauntlet") || lower.equals("the corrupted gauntlet"))
			record.teamSize = 1;

		if (lower.startsWith("chambers of xeric")) record.activity = "Chambers of Xeric";
		else if (lower.startsWith("theatre of blood")) record.activity = "Theatre of Blood";
		else if (lower.startsWith("tombs of amascut")) record.activity = "Tombs of Amascut";
		if (lower.contains("challenge mode")) record.variant = "challenge_mode";
		else if (lower.contains("hard mode")) record.variant = "hard";
		else if (lower.contains("entry mode")) record.variant = "entry";
		if (record.teamSize == null && lower.matches(".*\\bsolo$")) record.teamSize = 1;
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
