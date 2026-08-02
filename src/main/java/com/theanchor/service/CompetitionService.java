/*
 * Competition ranking logic adapted from the Terpinheimer competition code.
 * See LICENSES/terpinheimer-LICENSE.txt.
 */
package com.theanchor.service;

import com.theanchor.model.AnchorModels;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CompetitionService
{
	private CompetitionService() {}

	public static List<Leader> leaders(AnchorModels.Competition competition, int limit)
	{
		List<Leader> result = new ArrayList<>();
		if (competition == null || competition.topHistory == null) return result;
		for (AnchorModels.TopHistory entry : competition.topHistory)
		{
			if (entry == null || entry.player == null || entry.history == null || entry.history.isEmpty()) continue;
			long first = entry.history.get(0).value;
			long last = entry.history.get(entry.history.size() - 1).value;
			String name = entry.player.displayName == null ? entry.player.username : entry.player.displayName;
			result.add(new Leader(name == null ? "Unknown" : name, Math.max(0, last - first)));
		}
		result.sort(Comparator.comparingLong(Leader::getGain).reversed().thenComparing(Leader::getName, String.CASE_INSENSITIVE_ORDER));
		return result.subList(0, Math.min(limit, result.size()));
	}

	public static final class Leader
	{
		private final String name;
		private final long gain;
		public Leader(String name, long gain) { this.name = name; this.gain = gain; }
		public String getName() { return name; }
		public long getGain() { return gain; }
	}
}
