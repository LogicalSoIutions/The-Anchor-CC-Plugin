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

	private static AnchorModels.TopHistory entry(String name, long first, long last)
	{
		AnchorModels.TopHistory entry = new AnchorModels.TopHistory(); entry.player = new AnchorModels.CompetitionPlayer(); entry.player.displayName = name;
		AnchorModels.HistorySample a = new AnchorModels.HistorySample(); a.value = first; AnchorModels.HistorySample b = new AnchorModels.HistorySample(); b.value = last;
		entry.history = Arrays.asList(a, b); return entry;
	}
}
