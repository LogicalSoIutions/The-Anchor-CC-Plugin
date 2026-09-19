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

	public java.util.Map<String, Object> detailsFor(AnchorModels.PbRecord record, String rawKey, String sourceId)
	{
		if (record == null || record.durationMillis == null || sourceId == null) return null;
		String activityId = activityId(record, rawKey);
		if (activityId == null || !isSupported(activityId, "time", record.teamSize)) return null;
		java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
		details.put("activityId", activityId);
		details.put("resultKind", "time");
		details.put("result", record.durationMillis);
		details.put("teamSize", record.teamSize);
		details.put("invocation", null);
		details.put("source", "personal_best");
		details.put("sourceId", sourceId);
		return details;
	}

	private String activityId(AnchorModels.PbRecord record, String rawKey)
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
		// TOA needs an observed invocation and completion-only activities need
		// completion evidence, neither of which a generic time PB proves.
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
		if (teamSize == 2) return prefix + "-duo";
		if (teamSize == 3) return prefix + "-trio";
		if (teamSize == 4) return prefix + "-four";
		return teamSize == 5 ? prefix + "-five" : null;
	}

	private boolean isSupported(String id, String kind, Integer teamSize)
	{
		AnchorModels.PvmDiaryContract current = contract;
		if (current == null || current.activities == null || teamSize == null) return false;
		for (AnchorModels.PvmDiaryContractActivity activity : current.activities)
			if (id.equals(activity.id) && kind.equals(activity.kind) && teamSize.intValue() == activity.teamSize)
				return true;
		return false;
	}
}
