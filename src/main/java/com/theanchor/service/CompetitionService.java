/*
 * Competition ranking logic adapted from the Terpinheimer competition code.
 * See LICENSES/terpinheimer-LICENSE.txt.
 */
package com.theanchor.service;

import com.theanchor.model.AnchorModels;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
			long value = entry.history.get(entry.history.size() - 1).value;
			String name = entry.player.displayName == null ? entry.player.username : entry.player.displayName;
			result.add(new Leader(name == null ? "Unknown" : name, Math.max(0, value)));
		}
		result.sort(Comparator.comparingLong(Leader::getValue).reversed().thenComparing(Leader::getName, String.CASE_INSENSITIVE_ORDER));
		return result.subList(0, Math.min(limit, result.size()));
	}

	public static AnchorModels.CompetitionPanel convertToPanel(AnchorModels.Competition comp, String kind)
	{
		if (comp == null) return null;
		AnchorModels.CompetitionPanel panel = new AnchorModels.CompetitionPanel();
		panel.competitionId = comp.id;
		panel.kind = kind;
		panel.title = comp.title;
		panel.metric = comp.metric;
		panel.artworkUrl = comp.artworkUrl;
		boolean isBotw = "BOTW".equalsIgnoreCase(kind);
		if (isBotw)
		{
			panel.metricLabel = comp.bossName != null && !comp.bossName.isBlank() ? comp.bossName : formatMetricLabel(comp.metric);
			panel.unit = "KC";
		}
		else
		{
			panel.metricLabel = comp.skillName != null && !comp.skillName.isBlank() ? comp.skillName : formatMetricLabel(comp.metric);
			panel.unit = "XP";
		}
		panel.startsAt = comp.startsAt;
		panel.endsAt = comp.endsAt;
		panel.participantCount = comp.participantCount;
		panel.status = comp.status != null && !comp.status.isBlank() ? comp.status : inferredStatus(comp);
		List<Leader> leaders = leaders(comp, 10);
		for (int i = 0; i < leaders.size(); i++)
		{
			Leader l = leaders.get(i);
			AnchorModels.PanelLeader leader = new AnchorModels.PanelLeader();
			leader.rank = i + 1;
			leader.username = l.getName();
			leader.displayName = l.getName();
			leader.gained = l.getValue();
			leader.unit = panel.unit;
			leader.displayValue = String.format("%,d %s", l.getValue(), panel.unit);
			panel.leaders.add(leader);
		}
		return panel;
	}


	private static String inferredStatus(AnchorModels.Competition competition)
	{
		Instant now = Instant.now();
		try
		{
			if (competition.startsAt != null && !competition.startsAt.isBlank()
				&& Instant.parse(competition.startsAt).isAfter(now)) return "upcoming";
			if (competition.endsAt != null && !competition.endsAt.isBlank()
				&& Instant.parse(competition.endsAt).isBefore(now)) return "finished";
		}
		catch (RuntimeException ignored) { }
		return "active";
	}

	public static String formatMetricLabel(String metric)
	{
		if (metric == null || metric.isBlank()) return "";
		String[] words = metric.replace('_', ' ').split("\\s+");
		StringBuilder sb = new StringBuilder();
		for (String word : words)
		{
			if (word.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(java.util.Locale.ROOT));
		}
		return sb.toString();
	}

	public static final class Leader
	{
		private final String name;
		private final long value;
		public Leader(String name, long value) { this.name = name; this.value = value; }
		public String getName() { return name; }
		public long getValue() { return value; }
		public long getGain() { return value; }
	}
}
