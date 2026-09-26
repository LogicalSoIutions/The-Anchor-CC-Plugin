package com.theanchor.pb;

import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/** Maps only directly evidenced PBs to an exact, current diary activity ID. */
@Slf4j
@Singleton
public class PvmDiaryContractService
{
	private static final Pattern EXACT_PLAYERS = Pattern.compile("(?:^|\\s)([1-9][0-9]*) players?$");
	@Inject private AnchorApiClient api;
	private volatile AnchorModels.PvmDiaryContract contract;

	public void refresh()
	{
		api.getPvmDiaryContract(result ->
		{
			if (result.isSuccessful() && result.value != null && result.value.catalogueVersion != null)
				contract = result.value;
			else log.warn("Could not refresh PvM Diary contract: HTTP {}, {}", result.statusCode, result.error);
		});
	}

	/** Maps a directly observed raid completion even when it did not set a PB. */
	public java.util.Map<String, Object> detailsForRaidCompletion(AnchorModels.PbRecord record, String rawKey,
		String sourceId)
	{
		return detailsForObservedResult(record, rawKey, null, sourceId);
	}

	/** Maps a directly observed raid result, including TOA invocation-specific and completion-only entries. */
	public java.util.Map<String, Object> detailsForObservedResult(AnchorModels.PbRecord record, String rawKey,
		Integer invocation, String sourceId)
	{
		if (record == null || record.durationMillis == null || sourceId == null) return null;
		String activityId = activityId(record, rawKey, invocation);
		AnchorModels.PvmDiaryContractActivity activity = supportedActivity(activityId, record.teamSize);
		if (activity == null || (!"time".equals(activity.kind) && !"completion".equals(activity.kind))) return null;
		Long result = "completion".equals(activity.kind) ? null : record.durationMillis;
		return details(activityId, activity.kind, result, record.teamSize, invocation, "raid_completion", sourceId);
	}

	public java.util.Map<String, Object> detailsForWave(int wave, String sourceId)
	{
		if (wave <= 0 || sourceId == null || !isSupported("doom", "wave", 1)) return null;
		return details("doom", "wave", Long.valueOf(wave), 1, null, "game_varp", sourceId);
	}

	/** Whether a result reaches the lowest server-defined Doom diary target. */
	public boolean isDoomWaveEligible(int wave)
	{
		AnchorModels.PvmDiaryContractActivity activity = supportedActivity("doom", 1);
		if (wave <= 0 || activity == null || !"wave".equals(activity.kind) || activity.tiers == null) return false;
		Long minimumTarget = null;
		for (AnchorModels.PvmDiaryTier tier : activity.tiers)
			if (tier != null && tier.target != null && tier.target.longValue() > 0
				&& (minimumTarget == null || tier.target.longValue() < minimumTarget.longValue()))
				minimumTarget = tier.target;
		return minimumTarget != null && wave >= minimumTarget.longValue();
	}

	private static java.util.Map<String, Object> details(String activityId, String resultKind, Long result,
		Integer teamSize, String source, String sourceId)
	{
		return details(activityId, resultKind, result, teamSize, null, source, sourceId);
	}

	private static java.util.Map<String, Object> details(String activityId, String resultKind, Long result,
		Integer teamSize, Integer invocation, String source, String sourceId)
	{
		java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
		details.put("activityId", activityId);
		details.put("resultKind", resultKind);
		details.put("result", result);
		details.put("teamSize", teamSize);
		details.put("invocation", invocation);
		details.put("source", source);
		details.put("sourceId", sourceId);
		return details;
	}

	private String activityId(AnchorModels.PbRecord record, String rawKey, Integer invocation)
	{
		String raw = rawKey == null ? "" : rawKey.trim().toLowerCase(Locale.ROOT);
		if ("sol heredit".equals(raw) || "fortis colosseum".equals(raw)) return "colosseum";
		if ("tzkal-zuk".equals(raw) || "inferno".equals(raw)) return "inferno";
		if ("tztok-jad".equals(raw) || "tzhaar fight cave".equals(raw)) return "fight-caves";
		if ("corrupted gauntlet".equals(raw) || "the corrupted gauntlet".equals(raw)) return "cg";

		Integer exactTeamSize = exactTeamSize(raw);
		if (exactTeamSize == null) return null;
		if (raw.startsWith("chambers of xeric"))
		{
			if (raw.contains("challenge mode")) return coxId("cm", exactTeamSize);
			if (!raw.contains("challenge") && !raw.contains("entry") && !raw.contains("hard")) return coxId("cox", exactTeamSize);
		}
		if (raw.startsWith("theatre of blood"))
		{
			if (raw.contains("hard mode")) return tobId("hmt", exactTeamSize);
			if (!raw.contains("hard") && !raw.contains("entry")) return tobId("tob", exactTeamSize);
		}
		if (raw.startsWith("tombs of amascut") && exactTeamSize == 1)
		{
			if (Integer.valueOf(300).equals(invocation)) return "toa-300";
			if (Integer.valueOf(500).equals(invocation)) return "toa-500";
		}
		// TOA needs an observed invocation and completion-only activities need completion evidence.
		return null;
	}

	private static Integer exactTeamSize(String raw)
	{
		if (raw.endsWith("solo")) return 1;
		Matcher matcher = EXACT_PLAYERS.matcher(raw);
		return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
	}

	private static String coxId(String prefix, int teamSize)
	{
		if (teamSize == 1) return prefix + "-solo";
		if (teamSize == 3) return prefix + "-trio";
		return teamSize == 5 ? prefix + "-five" : null;
	}

	private static String tobId(String prefix, int teamSize)
	{
		if (teamSize == 1) return "tob".equals(prefix) ? "tob-solo" : null;
		if (teamSize == 2) return prefix + "-duo";
		if (teamSize == 3) return prefix + "-trio";
		if (teamSize == 4) return prefix + "-four";
		return teamSize == 5 ? prefix + "-five" : null;
	}

	private boolean isSupported(String id, String kind, Integer teamSize)
	{
		AnchorModels.PvmDiaryContractActivity activity = supportedActivity(id, teamSize);
		return activity != null && kind.equals(activity.kind);
	}

	private AnchorModels.PvmDiaryContractActivity supportedActivity(String id, Integer teamSize)
	{
		AnchorModels.PvmDiaryContract current = contract;
		if (id == null || current == null || current.activities == null || teamSize == null) return null;
		for (AnchorModels.PvmDiaryContractActivity activity : current.activities)
			if (id.equals(activity.id) && teamSize.intValue() == activity.teamSize) return activity;
		return null;
	}
}
