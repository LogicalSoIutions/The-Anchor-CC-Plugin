package com.theanchor.evidence;

import com.theanchor.AnchorConfig;
import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.Player;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.*;

public class EventPipelineTest
{
	@Test public void collectionLogSubmitsWhenTeamLootFinishesLast() throws Exception
	{
		assertRaidUploadOrder(false, "draft", true);
	}

	@Test public void collectionLogSubmitsWhenTeamLootFinishesFirst() throws Exception
	{
		assertRaidUploadOrder(true, "draft", true);
	}

	@Test public void submittedLootDoesNotBlockCollectionLog() throws Exception
	{
		assertRaidUploadOrder(true, "submitted", true);
		assertRaidUploadOrder(false, "submitted", true);
	}

	@Test public void approvedLootDoesNotBlockCollectionLog() throws Exception
	{
		assertRaidUploadOrder(true, "approved", true);
	}

	@Test public void failedLootUploadStillBlocksGroupSubmission() throws Exception
	{
		assertRaidUploadOrder(false, "failed", true);
	}

	@Test public void disabledAutoSubmitKeepsCollectionLogInDraft() throws Exception
	{
		assertRaidUploadOrder(false, "draft", false);
	}

	private static void assertRaidUploadOrder(boolean lootFirst, String lootStatus, boolean enabled) throws Exception
	{
		EventPipeline pipeline = new EventPipeline();
		EvidenceStore store = mock(EvidenceStore.class);
		ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
		configure(pipeline, store, executor);
		AnchorConfig config = mock(AnchorConfig.class);
		when(config.authenticationCode()).thenReturn("test-code");
		when(config.autoSubmitEnabled()).thenReturn(enabled);
		inject(pipeline, "config", config);
		AnchorApiClient api = mock(AnchorApiClient.class);
		inject(pipeline, "api", api);
		doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
			.when(executor).execute(any(Runnable.class));

		EvidenceStore.Record loot = raidRecord("loot");
		loot.metadata.party = new AnchorModels.Party();
		loot.metadata.party.detectedPartySize = 3;
		loot.metadata.party.submittedPartySize = 3;
		loot.metadata.party.submittedClanMemberCount = 2;
		loot.metadata.party.submittedNonClanMemberCount = 1;
		loot.metadata.party.confidence = "high";
		EvidenceStore.Record clog = raidRecord("collection_log");
		when(store.records()).thenReturn(Arrays.asList(loot, clog));
		Map<String, AnchorApiClient.ResultCallback<AnchorModels.EventResponse>> callbacks = new HashMap<>();
		doAnswer(call -> {
			AnchorModels.EventEnvelope envelope = call.getArgument(0);
			callbacks.put(envelope.eventType, call.getArgument(3));
			return null;
		}).when(api).uploadEvent(any(), any(), any(), any());
		doAnswer(call -> {
			AnchorApiClient.ResultCallback<Map> callback = call.getArgument(5);
			callback.complete(AnchorApiClient.ApiResult.ok(200, new HashMap<>()));
			return null;
		}).when(api).updateSubmission(anyString(), anyInt(), anyInt(), anyInt(), anyString(), any());

		pipeline.upload(loot, true);
		pipeline.upload(clog, true);
		String first = lootFirst ? "loot" : "collection_log";
		String second = lootFirst ? "collection_log" : "loot";
		completeUpload(callbacks.get(first), first, lootFirst ? lootStatus : "draft");
		verify(api, never()).submit(anyString(), any());
		completeUpload(callbacks.get(second), second, lootFirst ? "draft" : lootStatus);
		if (enabled && !"failed".equals(lootStatus))
		{
			verify(api).updateSubmission(eq("collection_log-id"), eq(3), eq(2), eq(1), eq(""), any());
			verify(api).submit(eq("collection_log-id"), any());
		}
		else verify(api, never()).submit(anyString(), any());
		verify(api, never()).updateSubmission(eq("loot-id"), anyInt(), anyInt(), anyInt(), anyString(), any());
	}

	private static EvidenceStore.Record raidRecord(String type)
	{
		EvidenceStore.Record record = new EvidenceStore.Record();
		record.metadata = new AnchorModels.EventEnvelope();
		record.metadata.eventId = type;
		record.metadata.eventType = type;
		record.metadata.source = new AnchorModels.Source();
		record.metadata.source.name = "Theatre of Blood";
		record.metadata.context.put("submissionGroupId", "raid-group");
		record.metadata.details.put("autoSubmit", true);
		record.status = AnchorModels.EventStatus.PENDING;
		return record;
	}

	private static void completeUpload(AnchorApiClient.ResultCallback<AnchorModels.EventResponse> callback,
		String type, String status)
	{
		if ("failed".equals(status))
		{
			callback.complete(AnchorApiClient.ApiResult.error(400, "Upload failed"));
			return;
		}
		AnchorModels.EventResponse response = new AnchorModels.EventResponse();
		response.submissionId = type + "-id";
		response.status = status;
		callback.complete(AnchorApiClient.ApiResult.ok(200, response));
	}

	@Test public void metadataCaptureDefersOutboxAccessUntilBackgroundWorkRuns() throws Exception
	{
		EventPipeline pipeline = new EventPipeline();
		EvidenceStore store = mock(EvidenceStore.class);
		ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
		configure(pipeline, store, executor);
		EvidenceStore.Record record = new EvidenceStore.Record();
		record.metadata = new AnchorModels.EventEnvelope();
		when(store.saveMetadata(any())).thenReturn(record);

		pipeline.capture("bingo", "metadata", null, null, null, false, "rules", false, false);

		verifyNoInteractions(store);
		ArgumentCaptor<Runnable> work = ArgumentCaptor.forClass(Runnable.class);
		verify(executor).execute(work.capture());
		work.getValue().run();
		verify(store).saveMetadata(any());
	}

	@Test public void screenshotCaptureRequestsFrameWithoutAccessingOutbox() throws Exception
	{
		EventPipeline pipeline = new EventPipeline();
		EvidenceStore store = mock(EvidenceStore.class);
		ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
		configure(pipeline, store, executor);
		ScreenshotService screenshots = mock(ScreenshotService.class);
		inject(pipeline, "screenshots", screenshots);

		pipeline.capture("bingo", "screenshot", null, null, null, false, "rules", true, false);

		verifyNoInteractions(store);
		verify(screenshots).captureNextFrame(any());
	}

	private static void configure(EventPipeline pipeline, EvidenceStore store,
		ScheduledExecutorService executor) throws Exception
	{
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("Player");
		AnchorConfig config = mock(AnchorConfig.class);
		when(config.authenticationCode()).thenReturn("");
		inject(pipeline, "client", client);
		inject(pipeline, "config", config);
		inject(pipeline, "store", store);
		inject(pipeline, "executor", executor);
	}

	private static void inject(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
