package com.theanchor.api;

import com.google.gson.Gson;
import com.theanchor.AnchorConfig;
import com.theanchor.model.AnchorModels;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import javax.imageio.ImageIO;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AnchorApiClientTest
{
	@Test public void parsesStructuredValidationMessagesFromConflictResponse() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json")
				.setBody("{\"eventId\":\"event-1\",\"submissionId\":\"submission-1\",\"status\":\"draft\",\"validationMessages\":[{\"code\":\"required\",\"field\":\"source.name\",\"message\":\"source.name is required\",\"severity\":\"error\"}]}"));
			server.start();
			AnchorConfig config = mock(AnchorConfig.class); when(config.apiBaseUrl()).thenReturn(server.url("/").toString());
			when(config.authenticationCode()).thenReturn("CODE");
			AnchorApiClient api = new AnchorApiClient(new OkHttpClient(), new Gson(), config);
			Path screenshot = Files.createTempFile("anchor-api-validation", ".png");
			ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", screenshot.toFile());
			CountDownLatch latch = new CountDownLatch(1);
			@SuppressWarnings("unchecked")
			final AnchorApiClient.ApiResult<AnchorModels.EventResponse>[] response = new AnchorApiClient.ApiResult[1];

			api.uploadEvent(new AnchorModels.EventEnvelope(), screenshot, "png", result -> { response[0] = result; latch.countDown(); });

			assertTrue(latch.await(3, TimeUnit.SECONDS));
			assertTrue(response[0].isSuccessful());
			assertEquals("submission-1", response[0].value.submissionId);
			assertEquals("source.name", response[0].value.validationMessages.get(0).field);
			assertEquals("source.name is required", response[0].value.validationMessages.get(0).message);
		}
	}

	@Test public void sendsPermanentCodeOnlyAsBearerHeader() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setResponseCode(200).setBody("{}")); server.start();
			AnchorConfig config = mock(AnchorConfig.class); when(config.apiBaseUrl()).thenReturn(server.url("/").toString()); when(config.authenticationCode()).thenReturn("FOREVER-CODE");
			AnchorApiClient api = new AnchorApiClient(new OkHttpClient(), new Gson(), config); CountDownLatch latch = new CountDownLatch(1);
			api.validateSession(result -> latch.countDown()); assertTrue(latch.await(3, TimeUnit.SECONDS));
			RecordedRequest request = server.takeRequest(); assertEquals("Bearer FOREVER-CODE", request.getHeader("Authorization")); assertFalse(request.getBody().readUtf8().contains("FOREVER-CODE"));
		}
	}

	@Test public void publicProfileCallDoesNotSendCode() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setResponseCode(200).setBody("{}")); server.start();
			AnchorConfig config = mock(AnchorConfig.class); when(config.apiBaseUrl()).thenReturn(server.url("/").toString()); when(config.authenticationCode()).thenReturn("SECRET");
			AnchorApiClient api = new AnchorApiClient(new OkHttpClient(), new Gson(), config); CountDownLatch latch = new CountDownLatch(1);
			api.getProfile("Player Name", result -> latch.countDown()); assertTrue(latch.await(3, TimeUnit.SECONDS));
			RecordedRequest request = server.takeRequest(); assertNull(request.getHeader("Authorization")); assertTrue(request.getPath().contains("Player%20Name"));
		}
	}

	@Test public void readsProfileCompetitionRanks() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"member\":{\"name\":\"LogicalHash\"},\"standings\":{\"botwRank\":{\"rank\":36,\"gained\":11,\"unit\":\"KC\",\"displayValue\":\"11 KC\",\"metric\":\"phosanis_nightmare\"},\"sotwRank\":{\"rank\":96,\"gained\":10461,\"unit\":\"XP\",\"displayValue\":\"10K XP\",\"metric\":\"crafting\"}}}"));
			server.start();
			AnchorConfig config = mock(AnchorConfig.class); when(config.apiBaseUrl()).thenReturn(server.url("/").toString());
			AnchorApiClient api = new AnchorApiClient(new OkHttpClient(), new Gson(), config); CountDownLatch latch = new CountDownLatch(1);
			final com.theanchor.model.AnchorModels.Profile[] profile = new com.theanchor.model.AnchorModels.Profile[1];
			api.getProfile("LogicalHash", result -> { profile[0] = result.value; latch.countDown(); });
			assertTrue(latch.await(3, TimeUnit.SECONDS));
			assertEquals(Integer.valueOf(36), profile[0].standings.botwRank.rank);
			assertEquals("11 KC", profile[0].standings.botwRank.displayValue);
			assertEquals(Integer.valueOf(96), profile[0].standings.sotwRank.rank);
			assertEquals("10K XP", profile[0].standings.sotwRank.displayValue);
		}
	}

	@Test public void readsPreformattedCompetitionPanels() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"botw\":{\"metricLabel\":\"Phosani's Nightmare\",\"leaders\":[{\"rank\":1,\"displayName\":\"firstiron\",\"gained\":276,\"unit\":\"KC\",\"displayValue\":\"276 KC\"}]}}"));
			server.start(); AnchorConfig config = mock(AnchorConfig.class); when(config.apiBaseUrl()).thenReturn(server.url("/").toString());
			AnchorApiClient api = new AnchorApiClient(new OkHttpClient(), new Gson(), config); CountDownLatch latch = new CountDownLatch(1);
			final com.theanchor.model.AnchorModels.CompetitionPanels[] panels = new com.theanchor.model.AnchorModels.CompetitionPanels[1];
			api.getCompetitionPanels(result -> { panels[0] = result.value; latch.countDown(); }); assertTrue(latch.await(3, TimeUnit.SECONDS));
			assertEquals("Phosani's Nightmare", panels[0].botw.metricLabel); assertEquals("276 KC", panels[0].botw.leaders.get(0).displayValue);
			assertEquals("/api/competitions/panels", server.takeRequest().getPath());
		}
	}

	@Test public void postsAuthenticatedPlayerProgressSnapshot() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setResponseCode(200).setBody("{}")); server.start();
			AnchorConfig config = mock(AnchorConfig.class); when(config.apiBaseUrl()).thenReturn(server.url("/").toString());
			when(config.authenticationCode()).thenReturn("CODE");
			AnchorApiClient api = new AnchorApiClient(new OkHttpClient(), new Gson(), config);
			AnchorModels.PlayerProgressRequest payload = new AnchorModels.PlayerProgressRequest();
			payload.syncId = "progress-1"; payload.combatAchievements = new AnchorModels.CombatAchievementProgress();
			payload.skills = new AnchorModels.SkillProgress();
			CountDownLatch latch = new CountDownLatch(1);
			api.syncPlayerProgress(payload, result -> latch.countDown());
			assertTrue(latch.await(3, TimeUnit.SECONDS));
			RecordedRequest request = server.takeRequest();
			assertEquals("/api/runelite/player-progress", request.getPath());
			assertEquals("Bearer CODE", request.getHeader("Authorization"));
			assertTrue(request.getBody().readUtf8().contains("progress-1"));
		}
	}
}
