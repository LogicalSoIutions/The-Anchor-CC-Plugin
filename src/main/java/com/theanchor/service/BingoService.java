package com.theanchor.service;

import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class BingoService
{
	private final AnchorApiClient api;
	private volatile AnchorModels.BingoEvent event;
	private volatile Set<Integer> itemIds = java.util.Collections.emptySet();
	private volatile Set<Integer> bossIds = java.util.Collections.emptySet();

	@Inject
	public BingoService(AnchorApiClient api) { this.api = api; }

	public AnchorModels.BingoEvent current() { return event; }

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
			itemIds = immutableSet(result.value.itemIds);
			bossIds = immutableSet(result.value.bossIds);
			event = result.value;
		});
	}

	public void clear()
	{
		event = null;
		itemIds = java.util.Collections.emptySet();
		bossIds = java.util.Collections.emptySet();
	}

	public List<AnchorModels.Item> matchingItems(Integer bossId, List<AnchorModels.Item> items)
	{
		return matchingItems(bossId, null, items);
	}

	public List<AnchorModels.Item> matchingItems(Integer bossId, String sourceName, List<AnchorModels.Item> items)
	{
		boolean eligibleSource = bossId != null && bossIds.contains(bossId);
		// Raid reward chests are emitted by RuneLite as named loot events without an NPC ID.
		if (!eligibleSource) eligibleSource = BossRegistry.isRaid(sourceName);
		if (event == null || !eligibleSource || items == null) return java.util.Collections.emptyList();
		List<AnchorModels.Item> matches = new ArrayList<>();
		for (AnchorModels.Item item : items)
			if (item != null && itemIds.contains(item.itemId)) matches.add(item);
		return matches;
	}

	private static Set<Integer> immutableSet(List<Integer> values)
	{
		if (values == null || values.isEmpty()) return java.util.Collections.emptySet();
		return java.util.Collections.unmodifiableSet(new HashSet<>(values));
	}
}
