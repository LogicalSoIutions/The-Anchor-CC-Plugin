package com.theanchor.evidence;

import com.theanchor.AnchorConfig;
import com.theanchor.model.AnchorModels;
import java.lang.reflect.Field;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.Player;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.*;

public class EventPipelineTest
{
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
