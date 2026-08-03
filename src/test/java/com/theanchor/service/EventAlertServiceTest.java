package com.theanchor.service;

import com.google.gson.Gson;
import com.theanchor.AnchorConfig;
import com.theanchor.model.EventAlert;
import java.awt.Color;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.client.chat.ChatMessageManager;
import okhttp3.OkHttpClient;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class EventAlertServiceTest
{
	@Test
	public void treatsFirstMessageAsBaselineAndSendsOnlyNewMessages()
	{
		ChatMessageManager chat = mock(ChatMessageManager.class);
		EventAlertService service = service(chat);

		service.acceptAlert(new EventAlert("Existing event", "1"));
		verify(chat, never()).queue(any());
		assertNull(service.activeOverlayMessage());

		service.acceptAlert(new EventAlert("New event", "2"));
		verify(chat).queue(any());
		assertEquals("New event", service.activeOverlayMessage());

		service.acceptAlert(new EventAlert("New event", "2"));
		verify(chat, times(1)).queue(any());
	}

	@Test
	public void resetRequiresANewBaselineWithoutReplayingSentAlerts()
	{
		ChatMessageManager chat = mock(ChatMessageManager.class);
		EventAlertService service = service(chat);
		service.acceptAlert(new EventAlert("Original", "1"));
		service.acceptAlert(new EventAlert("Second", "2"));
		verify(chat).queue(any());

		service.resetStateForWorldHopOrLogin();
		assertNull(service.activeOverlayMessage());
		service.acceptAlert(new EventAlert("Second", "2"));
		verify(chat, times(1)).queue(any());
	}

	private static EventAlertService service(ChatMessageManager chat)
	{
		AnchorConfig config = mock(AnchorConfig.class);
		when(config.eventAlertColor()).thenReturn(Color.RED);
		return new EventAlertService(mock(Client.class), new OkHttpClient(), new Gson(), chat,
			config, mock(ScheduledExecutorService.class));
	}
}
