package com.theanchor.service;

import com.theanchor.AnchorConfig;
import com.theanchor.api.AnchorApiClient;
import java.lang.reflect.Field;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AnchorDataServiceTest
{
	@Test public void resolvesRelativeProfileImageUrl()
	{
		assertEquals("https://the-anchor.cc/images/player-picture.png",
			AnchorDataService.profileImageUrl("https://the-anchor.cc", "Player", "images/player-picture.png"));
	}

	@Test public void buildsDefaultRsnPictureUrl()
	{
		assertEquals("https://the-anchor.cc/Player%20Name-picture.png",
			AnchorDataService.profileImageUrl("https://the-anchor.cc", "Player Name", null));
	}

	@Test public void encodesSpacesInSuppliedProfileImageUrl()
	{
		assertEquals("https://the-anchor.cc/Player%20Name-picture.png",
			AnchorDataService.profileImageUrl("https://the-anchor.cc", "ignored", "/Player Name-picture.png"));
	}

	@Test public void refreshesProfileAndWeeklyCompetitions() throws Exception
	{
		AnchorDataService service = new AnchorDataService();
		AnchorApiClient api = mock(AnchorApiClient.class);
		AnchorConfig config = mock(AnchorConfig.class);
		ImageCache images = mock(ImageCache.class);
		RulesService rules = mock(RulesService.class);
		inject(service, "api", api);
		inject(service, "config", config);
		inject(service, "images", images);
		inject(service, "rules", rules);
		when(config.apiBaseUrl()).thenReturn("https://the-anchor.cc/");

		service.refresh("Zach");

		verify(api).getProfile(eq("Zach"), any());
		verify(api).getCompetitionPanels(any());
		verify(images).loadWebsiteImage(eq("botw"), eq("https://the-anchor.cc/botw.png"), any());
		verify(images).loadWebsiteImage(eq("sotw"), eq("https://the-anchor.cc/sotw.png"), any());
		verify(rules).refresh(any());
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
