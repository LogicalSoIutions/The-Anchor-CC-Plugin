package com.theanchor.service;


import com.theanchor.AnchorConfig;
import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class BingoService
{
	private final AnchorApiClient api;
	private final AnchorDataService data;
	@Inject private AnchorConfig config;
	private volatile AnchorModels.BingoEvent event;
	private volatile AnchorModels.BingoRules rules;
	private volatile Set<Integer> itemIds = java.util.Collections.emptySet();
	private volatile Set<Integer> bossIds = java.util.Collections.emptySet();
	private volatile long lastBossSourceAt;

	@Inject
	public BingoService(AnchorApiClient api, AnchorDataService data)
	{
		this.api = api;
		this.data = data;
	}

	public AnchorModels.BingoEvent current() { return event; }
	public AnchorModels.BingoRules currentRules() { return rules; }
	public AnchorModels.BingoTeam currentTeam()
	{
		AnchorModels.BingoEvent active = event;
		AnchorModels.Profile profile = data.profile();
		if (active == null || active.teamsByDiscordId == null || profile == null || profile.member == null
			|| profile.member.discordId == null) return null;
		return active.teamsByDiscordId.get(profile.member.discordId.trim());
	}

	public void refresh()
	{
		api.getBingo(result ->
		{
			if (!result.isSuccessful())
			{
				if (result.statusCode == 401 || result.statusCode == 403) clear();
				return;
			}
			if (result.value == null || !result.value.active)
			{
				clear();
				return;
			}
			event = result.value;
		});
		api.getBingoRules(result ->
		{
			if (!result.isSuccessful())
			{
				if (result.statusCode == 401 || result.statusCode == 403) clearRules();
				return;
			}
			if (result.value == null) { clearRules(); return; }
			itemIds = immutableSet(result.value.itemIds);
			bossIds = immutableSet(result.value.bossIds);
			rules = result.value;
		});
	}

	public void clear()
	{
		event = null;
		clearRules();
	}

	private void clearRules()
	{
		rules = null;
		itemIds = java.util.Collections.emptySet();
		bossIds = java.util.Collections.emptySet();
		lastBossSourceAt = 0;
	}

	public List<AnchorModels.Item> matchingItems(Integer bossId, List<AnchorModels.Item> items)
	{
		return matchingItems(bossId, null, items);
	}

	public List<AnchorModels.Item> matchingItems(Integer bossId, String sourceName, List<AnchorModels.Item> items)
	{
		AnchorModels.BingoRules activeRules = rules;
		if (event == null || activeRules == null || activeRules.triggers == null || items == null)
			return java.util.Collections.emptyList();
		Set<AnchorModels.Item> matches = new LinkedHashSet<>();
		for (AnchorModels.Item item : items)
			if (item != null && activeRules.triggers.matchingItem && itemIds.contains(item.itemId)) matches.add(item);
		if (activeRules.triggers.ordinaryBossLoot && isBossSource(bossId, sourceName)) matches.addAll(items);
		return new ArrayList<>(matches);
	}

	public boolean shouldCaptureCollectionLog(List<AnchorModels.Item> items)
	{
		AnchorModels.BingoRules activeRules = rules;
		if (event == null || activeRules == null || activeRules.triggers == null
			|| !activeRules.triggers.bossCollectionLog || items == null) return false;
		if (hasRecentBossSource()) return true;
		for (AnchorModels.Item item : items) if (item != null && itemIds.contains(item.itemId)) return true;
		return false;
	}

	public boolean shouldCapturePet(Integer bossId, String sourceName, List<Integer> petItemIds)
	{
		AnchorModels.BingoRules activeRules = rules;
		if (event == null || activeRules == null || activeRules.triggers == null
			|| !activeRules.triggers.bossPet) return false;
		if (isBossSource(bossId, sourceName)) return true;
		if (hasRecentBossSource()) return true;
		if (petItemIds != null) for (Integer itemId : petItemIds) if (itemId != null && itemIds.contains(itemId)) return true;
		return false;
	}

	public boolean isBossSource(Integer bossId, String sourceName)
	{
		if (bossId != null && bossIds.contains(bossId)) return true;
		// Raid reward chests are emitted by RuneLite as named loot events without an NPC ID.
		return BossRegistry.isRaid(sourceName);
	}

	public void observeSource(Integer bossId, String sourceName)
	{
		if (isBossSource(bossId, sourceName)) lastBossSourceAt = System.currentTimeMillis();
	}

	private boolean hasRecentBossSource()
	{
		return lastBossSourceAt > 0 && System.currentTimeMillis() - lastBossSourceAt <= 10_000L;
	}

	public String eventType()
	{
		String type = rules == null || rules.evidence == null ? null : rules.evidence.eventType;
		return type == null || type.isBlank() ? "bingo" : type.trim();
	}

	public boolean screenshotRequired()
	{
		return rules == null || rules.evidence == null || rules.evidence.screenshotRequired;
	}

	public boolean finalizeSubmission()
	{
		return (config == null || config.autoSubmitEnabled())
			&& (rules == null || rules.evidence == null || rules.evidence.finalizeSubmission);
	}

	public String rulesVersion() { return rules == null ? null : rules.rulesVersion; }

	public void decorateDetails(Map<String, Object> details)
	{
		if (details == null) return;
		AnchorModels.BingoEvent active = event;
		if (active != null && active.eventTitle != null) details.put("eventTitle", active.eventTitle);
		if (rulesVersion() != null) details.put("bingoRulesVersion", rulesVersion());
		AnchorModels.BingoTeam team = currentTeam();
		if (team != null)
		{
			if (team.teamId != null) details.put("teamId", team.teamId);
			if (team.teamName != null) details.put("teamName", team.teamName);
		}
	}

	private static Set<Integer> immutableSet(List<Integer> values)
	{
		if (values == null || values.isEmpty()) return java.util.Collections.emptySet();
		return java.util.Collections.unmodifiableSet(new HashSet<>(values));
	}
}
