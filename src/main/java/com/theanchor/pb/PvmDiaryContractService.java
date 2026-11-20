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
			else
			{
				contract = null;
				log.warn("Could not refresh PvM Diary contract: HTTP {}, {}", result.statusCode, result.error);
			}
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
		if (!meetsTarget(activity, result) || !matchesInvocation(activity, invocation)) return null;
		return details(activityId, activity.kind, result, record.teamSize, invocation, "raid_completion", sourceId);
	}

	public java.util.Map<String, Object> detailsForWave(int wave, String sourceId)
	{
		if (sourceId == null || !isDoomWaveEligible(wave)) return null;
		return details("doom", "wave", Long.valueOf(wave), 1, null, "game_varp", sourceId);
	}

	/** Whether a result reaches the lowest server-defined Doom diary target. */
	public boolean isDoomWaveEligible(int wave)
	{
		AnchorModels.PvmDiaryContractActivity activity = supportedActivity("doom", 1);
		return activity != null && "wave".equals(activity.kind) && meetsTarget(activity, Long.valueOf(wave));
	}

	/** Shared gate for capture, persisted retries, and manual submission. */
	public boolean isEligible(java.util.Map<String, Object> details)
	{
		if (details == null || !(details.get("activityId") instanceof String)) return false;
		Long size = integer(details.get("teamSize"));
		if (size == null || size <= 0 || size > Integer.MAX_VALUE) return false;
		AnchorModels.PvmDiaryContractActivity activity = supportedActivity((String) details.get("activityId"), size.intValue());
		if (activity == null || activity.kind == null || !activity.kind.equals(details.get("resultKind"))) return false;
		Long invocation = integer(details.get("invocation"));
		if (details.get("invocation") != null && invocation == null) return false;
		if (invocation != null && (invocation < 0 || invocation > Integer.MAX_VALUE)) return false;
		if (!matchesInvocation(activity, invocation == null ? null : invocation.intValue())) return false;
		Object rawResult = details.get("result");
		if ("completion".equals(activity.kind)) return details.containsKey("result") && rawResult == null && meetsTarget(activity, null);
		return meetsTarget(activity, integer(rawResult));
	}

	private static Long integer(Object value)
	{
		if (!(value instanceof Number)) return null;
		try { return new java.math.BigDecimal(value.toString()).longValueExact(); }
		catch (NumberFormatException | ArithmeticException e) { return null; }
	}

	private static boolean matchesInvocation(AnchorModels.PvmDiaryContractActivity activity, Integer invocation)
	{
		Integer required = activity.invocation;
		if (required == null && "toa-300".equals(activity.id)) required = 300;
		if (required == null && "toa-500".equals(activity.id)) required = 500;
		return required == null || required.equals(invocation);
	}

	private static boolean meetsTarget(AnchorModels.PvmDiaryContractActivity activity, Long result)
	{
		if (activity.tiers == null) return false;
		for (AnchorModels.PvmDiaryTier tier : activity.tiers)
		{
			if (tier == null) continue;
			if ("completion".equals(activity.kind))
			{
				if (result == null && tier.target == null) return true;
			}
			else if (result != null && result > 0 && tier.target != null && tier.target > 0)
			{
				if ("time".equals(activity.kind) && result <= tier.target) return true;
				if ("wave".equals(activity.kind) && result >= tier.target) return true;
			}
		}
		return false;
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
	private AnchorModels.PvmDiaryContractActivity supportedActivity(String id, Integer teamSize)
	{
		AnchorModels.PvmDiaryContract current = contract;
		if (id == null || current == null || current.activities == null || teamSize == null) return null;
		for (AnchorModels.PvmDiaryContractActivity activity : current.activities)
			if (activity != null && id.equals(activity.id) && teamSize.intValue() == activity.teamSize) return activity;
		return null;
	}
}
