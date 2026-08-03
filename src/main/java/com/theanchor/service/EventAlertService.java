/*
 * Event-alert polling and baseline behavior adapted from Odablock Sounds.
 * See LICENSES/odablock-sounds-LICENSE.txt.
 */
package com.theanchor.service;

import com.google.gson.Gson;
import com.theanchor.AnchorConfig;
import com.theanchor.model.EventAlert;
import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Slf4j
@Singleton
public class EventAlertService
{
	static final String EVENT_ALERTS_URL =
		"https://raw.githubusercontent.com/LogicalSoIutions/odablock-sounds-live/refs/heads/main/custom_notifications.json";
	static final int POLL_INTERVAL_TICKS = 100;
	static final long OVERLAY_DURATION_MILLIS = 15_000L;

	private final Client client;
	private final OkHttpClient http;
	private final Gson gson;
	private final ChatMessageManager chat;
	private final AnchorConfig config;
	private final ScheduledExecutorService executor;
	private final AtomicBoolean requestInFlight = new AtomicBoolean();
	private final Object stateLock = new Object();
	private final Set<String> sentMessages = new HashSet<>();
	private int lastCheckedTick = -1;
	private int generation;
	private boolean initialCheckComplete;
	private String baselineMessage;
	private volatile String overlayMessage;
	private volatile long overlayExpiresAt;

	@Inject
	public EventAlertService(Client client, OkHttpClient http, Gson gson,
		ChatMessageManager chat, AnchorConfig config, ScheduledExecutorService executor)
	{
		this.client = client;
		this.http = http;
		this.gson = gson;
		this.chat = chat;
		this.config = config;
		this.executor = executor;
	}

	public void onGameTick()
	{
		if (!config.eventAlerts()) return;
		int currentTick = client.getTickCount();
		if (lastCheckedTick != -1 && currentTick >= lastCheckedTick
			&& currentTick - lastCheckedTick <= POLL_INTERVAL_TICKS) return;
		if (!requestInFlight.compareAndSet(false, true)) return;
		lastCheckedTick = currentTick;
		final int requestGeneration;
		synchronized (stateLock) { requestGeneration = generation; }
		try
		{
			executor.execute(() -> requestAlert(requestGeneration));
		}
		catch (RuntimeException e)
		{
			requestInFlight.set(false);
			log.debug("Unable to schedule The Anchor event-alert request", e);
		}
	}

	public void resetStateForWorldHopOrLogin()
	{
		lastCheckedTick = -1;
		synchronized (stateLock)
		{
			generation++;
			initialCheckComplete = false;
			baselineMessage = null;
		}
		clearOverlay();
	}

	public String activeOverlayMessage()
	{
		if (System.currentTimeMillis() >= overlayExpiresAt)
		{
			clearOverlay();
			return null;
		}
		return overlayMessage;
	}

	private void requestAlert(int requestGeneration)
	{
		Request request = new Request.Builder()
			.url(EVENT_ALERTS_URL + "?t=" + System.currentTimeMillis())
			.header("Cache-Control", "no-cache")
			.build();
		try (Response response = http.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null) return;
			acceptAlert(gson.fromJson(response.body().string(), EventAlert.class), requestGeneration);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Unable to fetch The Anchor event alert", e);
		}
		finally
		{
			requestInFlight.set(false);
		}
	}

	void acceptAlert(EventAlert alert)
	{
		final int currentGeneration;
		synchronized (stateLock) { currentGeneration = generation; }
		acceptAlert(alert, currentGeneration);
	}

	private void acceptAlert(EventAlert alert, int requestGeneration)
	{
		String message = alert == null ? null : alert.getMessage();
		boolean shouldSend = false;
		synchronized (stateLock)
		{
			if (requestGeneration != generation) return;
			if (message == null || message.trim().isEmpty())
			{
				initialCheckComplete = true;
				return;
			}
			message = message.trim();
			if (!initialCheckComplete)
			{
				baselineMessage = message;
				initialCheckComplete = true;
				return;
			}
			if (!Objects.equals(message, baselineMessage) && sentMessages.add(message)) shouldSend = true;
		}
		if (!shouldSend) return;
		sendChatMessage(message);
		overlayMessage = message;
		overlayExpiresAt = System.currentTimeMillis() + OVERLAY_DURATION_MILLIS;
	}

	private void sendChatMessage(String alert)
	{
		String formatted = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("The Anchor Event Alert ~ ")
			.append(alert)
			.build();
		String color = String.format("%06x", config.eventAlertColor().getRGB() & 0xffffff);
		chat.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(formatted.replace("colHIGHLIGHT", "col=" + color))
			.build());
	}

	private void clearOverlay()
	{
		overlayMessage = null;
		overlayExpiresAt = 0L;
	}
}
