package com.theanchor.service;

import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

public class BingoServiceTest
{
	@Test public void requiresBothConfiguredBossAndItem()
	{
		AnchorApiClient api = mock(AnchorApiClient.class);
		AnchorDataService data = mock(AnchorDataService.class);
		AnchorModels.BingoEvent event = new AnchorModels.BingoEvent();
		event.active = true;
		event.eventTitle = "The Anchor Bingo";
		AnchorModels.BingoRules bingoRules = new AnchorModels.BingoRules();
		bingoRules.rulesVersion = "2026-08-04.3";
		bingoRules.itemIds = Collections.singletonList(20997);
		bingoRules.bossIds = Collections.singletonList(5886);
		bingoRules.triggers.matchingItem = true;
		bingoRules.triggers.bossCollectionLog = true;
		bingoRules.triggers.bossPet = true;
		AnchorModels.BingoTeam team = new AnchorModels.BingoTeam();
		team.teamId = "team-1";
		team.teamName = "Team 1";
		event.teamsByDiscordId.put("284148696017403905", team);
		AnchorModels.Profile profile = new AnchorModels.Profile();
		profile.member = new AnchorModels.Member();
		profile.member.discordId = "284148696017403905";
		org.mockito.Mockito.when(data.profile()).thenReturn(profile);
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked") AnchorApiClient.ResultCallback<AnchorModels.BingoEvent> callback = invocation.getArgument(0);
			callback.complete(AnchorApiClient.ApiResult.ok(200, event));
			return null;
		}).when(api).getBingo(any());
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked") AnchorApiClient.ResultCallback<AnchorModels.BingoRules> callback = invocation.getArgument(0);
			callback.complete(AnchorApiClient.ApiResult.ok(200, bingoRules));
			return null;
		}).when(api).getBingoRules(any());

		BingoService service = new BingoService(api, data);
		service.refresh();
		AnchorModels.Item matching = item(20997);
		AnchorModels.Item other = item(21003);

		assertEquals(Collections.singletonList(matching), service.matchingItems(5886, Arrays.asList(matching, other)));
		assertEquals(Collections.singletonList(matching), service.matchingItems(9999, Collections.singletonList(matching)));
		assertTrue(service.matchingItems(5886, Collections.singletonList(other)).isEmpty());
		assertEquals(Collections.singletonList(matching),
			service.matchingItems(null, "Chambers of Xeric", Collections.singletonList(matching)));
		assertEquals("team-1", service.currentTeam().teamId);
		assertEquals("Team 1", service.currentTeam().teamName);
		assertEquals("2026-08-04.3", service.rulesVersion());
		assertTrue(service.shouldCaptureCollectionLog(Collections.singletonList(matching)));
		assertTrue(service.shouldCapturePet(5886, "Abyssal Sire", Collections.emptyList()));
		service.observeSource(5886, "Abyssal Sire");
		assertTrue(service.shouldCaptureCollectionLog(Collections.singletonList(other)));
		bingoRules.triggers.ordinaryBossLoot = true;
		assertEquals(Collections.singletonList(other), service.matchingItems(5886, Collections.singletonList(other)));
	}

	private static AnchorModels.Item item(int id)
	{
		AnchorModels.Item item = new AnchorModels.Item();
		item.itemId = id;
		return item;
	}
}
