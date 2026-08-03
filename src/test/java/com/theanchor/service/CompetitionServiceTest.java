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
	@Test public void calculatesAndSortsGainInsteadOfTrustingArrayOrder()
	{
		AnchorModels.Competition competition = new AnchorModels.Competition();
		competition.topHistory = Arrays.asList(entry("Second", 100, 150), entry("First", 20, 120));
		java.util.List<CompetitionService.Leader> leaders = CompetitionService.leaders(competition, 3);
		assertEquals("First", leaders.get(0).getName()); assertEquals(100, leaders.get(0).getGain()); assertEquals(50, leaders.get(1).getGain());
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
		assertEquals(90, panel.leaders.get(0).gained);
	}

	private static AnchorModels.TopHistory entry(String name, long first, long last)
	{
		AnchorModels.TopHistory entry = new AnchorModels.TopHistory(); entry.player = new AnchorModels.CompetitionPlayer(); entry.player.displayName = name;
		AnchorModels.HistorySample a = new AnchorModels.HistorySample(); a.value = first; AnchorModels.HistorySample b = new AnchorModels.HistorySample(); b.value = last;
		entry.history = Arrays.asList(a, b); return entry;
	}
}
