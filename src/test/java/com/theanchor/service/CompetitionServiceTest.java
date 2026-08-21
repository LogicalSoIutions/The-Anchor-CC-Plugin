/*
 * Competition ranking tests cover logic adapted from Terpinheimer.
 * See LICENSES/terpinheimer-LICENSE.txt.
 */
package com.theanchor.service;

import com.theanchor.model.AnchorModels;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class CompetitionServiceTest
{
	@Test public void parsesLatestValueAndSortsInsteadOfTrustingArrayOrder()
	{
		AnchorModels.Competition competition = new AnchorModels.Competition();
		competition.topHistory = Arrays.asList(entry("LowerTotal", 20, 120), entry("HigherTotal", 100, 150));
		java.util.List<CompetitionService.Leader> leaders = CompetitionService.leaders(competition, 3);
		assertEquals("HigherTotal", leaders.get(0).getName());
		assertEquals(150, leaders.get(0).getValue());
		assertEquals(120, leaders.get(1).getValue());
	}

	@Test public void convertsFinishedCompetitionToPanel()
	{
		AnchorModels.Competition competition = new AnchorModels.Competition();
		competition.id = 123L;
		competition.metric = "phosanis_nightmare";
		competition.bossName = "Phosani's Nightmare";
		competition.topHistory = Arrays.asList(entry("Winner", 10, 100));
		competition.status = "finished";
		AnchorModels.CompetitionPanel panel = CompetitionService.convertToPanel(competition, "BOTW");
		assertNotNull(panel);
		assertEquals("BOTW", panel.kind);
		assertEquals("Phosani's Nightmare", panel.metricLabel);
		assertEquals("KC", panel.unit);
		assertEquals("finished", panel.status);
		assertEquals(1, panel.leaders.size());
		assertEquals("Winner", panel.leaders.get(0).displayName);
		assertEquals(100, panel.leaders.get(0).gained);
	}

	@Test public void infersUpcomingStatusFromStartTime()
	{
		AnchorModels.Competition competition = new AnchorModels.Competition();
		competition.id = 456L;
		competition.metric = "farming";
		competition.startsAt = "2099-01-01T00:00:00Z";
		competition.endsAt = "2099-01-08T00:00:00Z";
		AnchorModels.CompetitionPanel panel = CompetitionService.convertToPanel(competition, "SOTW");
		assertEquals("upcoming", panel.status);
	}

	private static AnchorModels.TopHistory entry(String name, long first, long last)
	{
		AnchorModels.TopHistory entry = new AnchorModels.TopHistory(); entry.player = new AnchorModels.CompetitionPlayer(); entry.player.displayName = name;
		AnchorModels.HistorySample a = new AnchorModels.HistorySample(); a.value = first; AnchorModels.HistorySample b = new AnchorModels.HistorySample(); b.value = last;
		entry.history = Arrays.asList(a, b); return entry;
	}
}
