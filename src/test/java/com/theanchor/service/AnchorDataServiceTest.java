package com.theanchor.service;

import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import java.lang.reflect.Field;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AnchorDataServiceTest
{
	@Test public void usesFixedProfilePortraitEndpoint()
	{
		assertEquals("https://the-anchor.cc/api/profile-portrait?name=Player%20Name",
			AnchorDataService.profileImageUrl("Player Name"));
	}

	@Test public void refreshesProfileAndWeeklyCompetitions() throws Exception
	{
		AnchorDataService service = new AnchorDataService();
		AnchorApiClient api = mock(AnchorApiClient.class);
		ImageCache images = mock(ImageCache.class);
		RulesService rules = mock(RulesService.class);
		inject(service, "api", api);
		inject(service, "images", images);
		inject(service, "rules", rules);
		doAnswer(invocation ->
		{
			AnchorApiClient.ResultCallback<AnchorModels.CompetitionPanels> callback = invocation.getArgument(0);
			callback.complete(AnchorApiClient.ApiResult.ok(200, new AnchorModels.CompetitionPanels()));
			return null;
		}).when(api).getCompetitionPanels(any());
		doAnswer(invocation ->
		{
			AnchorModels.Competitions competitions = new AnchorModels.Competitions();
			competitions.botw = pairWithUpcoming(148345L, "mad_angel");
			competitions.sotw = pairWithUpcoming(147161L, "farming");
			AnchorApiClient.ResultCallback<AnchorModels.Competitions> callback = invocation.getArgument(0);
			callback.complete(AnchorApiClient.ApiResult.ok(200, competitions));
			return null;
		}).when(api).getCompetitions(any());

		service.refresh("Zach");

		verify(api).getProfile(eq("Zach"), any());
		verify(api).getCompetitionPanels(any());
		verify(api).getCompetitions(any());
		verify(images).loadWebsiteImage(eq("botw-148345"), eq("https://the-anchor.cc/botw.png"), any());
		verify(images).loadWebsiteImage(eq("sotw-147161"), eq("https://the-anchor.cc/sotw.png"), any());
		verify(rules).refresh(any());
	}

	@Test public void selectsCurrentThenUpcomingThenPrevious()
	{
		AnchorModels.CompetitionPair pair = new AnchorModels.CompetitionPair();
		pair.previous = competition(1L, "crafting");
		pair.upcoming = competition(2L, "farming");
		assertEquals(2L, AnchorDataService.selectCompetition(pair).id);
		pair.current = competition(3L, "agility");
		assertEquals(3L, AnchorDataService.selectCompetition(pair).id);
	}

	@Test public void comparesRsnUsingJagexStyleSeparators()
	{
		assertTrue(AnchorDataService.samePlayer("Clan Mate", "clan_mate"));
		assertFalse(AnchorDataService.samePlayer("Clan Mate", "Other Mate"));
	}

	@Test public void successfulRosterProfileActivatesFeaturesWithoutCanonicalMemberName() throws Exception
	{
		AnchorDataService service = new AnchorDataService();
		AnchorApiClient api = mock(AnchorApiClient.class);
		AnchorModels.Profile profile = new AnchorModels.Profile();
		profile.member = new AnchorModels.Member();
		inject(service, "api", api);
		inject(service, "images", mock(ImageCache.class));
		inject(service, "rules", mock(RulesService.class));
		doAnswer(invocation ->
		{
			AnchorApiClient.ResultCallback<AnchorModels.Profile> callback = invocation.getArgument(1);
			callback.complete(AnchorApiClient.ApiResult.ok(200, profile));
			return null;
		}).when(api).getProfile(eq("Zach"), any());

		service.refresh("Zach");

		assertTrue(service.isCurrentPlayerClanMember());
		assertEquals(profile, service.profile());
	}

	private static AnchorModels.CompetitionPair pairWithUpcoming(long id, String metric)
	{
		AnchorModels.CompetitionPair pair = new AnchorModels.CompetitionPair();
		pair.upcoming = competition(id, metric);
		return pair;
	}

	private static AnchorModels.Competition competition(long id, String metric)
	{
		AnchorModels.Competition competition = new AnchorModels.Competition();
		competition.id = id;
		competition.metric = metric;
		competition.startsAt = "2099-01-01T00:00:00Z";
		competition.endsAt = "2099-01-08T00:00:00Z";
		return competition;
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
