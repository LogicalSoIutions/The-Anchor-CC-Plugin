package com.theanchor.ui;

import com.theanchor.collection.CollectionLogSyncService;
import com.theanchor.service.AnchorDataService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/** Automatically uploads an exact snapshot after the player opens the collection log. */
@Slf4j
@Singleton
public class CollectionLogAutoSync
{
	private static final int CAPTURE_TIMEOUT_TICKS = 20;
	private static final int COLLECTION_LOG_SETUP = 7797;
	private static final int COLLECTION_INIT_SCRIPT = 2240;

	private final Client client;
	private final ClientThread clientThread;
	private final EventBus eventBus;
	private final AnchorDataService data;
	private final CollectionLogSyncService collectionLogSync;
	private boolean waitingForCapture;
	private boolean uploadInProgress;
	private boolean fullCaptureRequested;
	private long captureAccountHash;
	private int openedAtTick = Integer.MAX_VALUE;
	private int captureDeadlineTick = -1;

	@Inject
	public CollectionLogAutoSync(Client client, ClientThread clientThread, EventBus eventBus,
		AnchorDataService data, CollectionLogSyncService collectionLogSync)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.eventBus = eventBus;
		this.data = data;
		this.collectionLogSync = collectionLogSync;
	}

	public void startUp()
	{
		eventBus.register(this);
	}

	public void shutDown()
	{
		eventBus.unregister(this);
		waitingForCapture = false;
		uploadInProgress = false;
		fullCaptureRequested = false;
	}

	/** Requests fresh full-log data when the in-log Anchor button is clicked. */
	void refreshRequested()
	{
		if (!data.isCurrentPlayerClanMember()) return;
		if (uploadInProgress)
		{
			message("A sync is already in progress.");
			return;
		}
		if (isPohLog()) return;
		armCapture();
		requestFullCapture();
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (!data.isCurrentPlayerClanMember()
			|| event.getGroupId() != InterfaceID.COLLECTION || isPohLog()) return;
		if (client.getAccountHash() == 0L || uploadInProgress || !collectionLogSync.isAutomaticSyncDue()) return;
		armCapture();
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != COLLECTION_LOG_SETUP || !waitingForCapture || fullCaptureRequested) return;
		requestFullCapture();
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() != InterfaceID.COLLECTION) return;
		waitingForCapture = false;
		fullCaptureRequested = false;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!waitingForCapture) return;
		if (client.getGameState() != GameState.LOGGED_IN || isPohLog()
			|| client.getAccountHash() != captureAccountHash || !data.isCurrentPlayerClanMember())
		{
			waitingForCapture = false;
			return;
		}
		if (fullCaptureRequested && !uploadInProgress
			&& data.connection() == AnchorDataService.Connection.CONNECTED
			&& collectionLogSync.isCaptureReadySince(openedAtTick))
		{
			startUpload();
		}
		else if (client.getTickCount() > captureDeadlineTick)
		{
			waitingForCapture = false;
			log.error("Automatic collection log sync timed out waiting for item rows: openedAtTick={}, currentTick={}",
				openedAtTick, client.getTickCount());
			message("Collection data did not load. Close and reopen your collection log to try again.");
		}
	}

	private void armCapture()
	{
		openedAtTick = client.getTickCount();
		captureDeadlineTick = openedAtTick + CAPTURE_TIMEOUT_TICKS;
		waitingForCapture = true;
		fullCaptureRequested = false;
		captureAccountHash = client.getAccountHash();
	}

	/**
	 * Search requests every entry from the server; init restores the player's normal view.
	 * Follows RuneProfile/WikiSync's collection-log retrieval flow (see LICENSES/reference-plugins.txt).
	 */
	private void requestFullCapture()
	{
		if (isPohLog() || client.getGameState() != GameState.LOGGED_IN
			|| client.getWidget(InterfaceID.Collection.SEARCH_TOGGLE) == null) return;
		// Set the guard before init, which fires COLLECTION_LOG_SETUP again.
		fullCaptureRequested = true;
		collectionLogSync.beginCapture();
		openedAtTick = client.getTickCount();
		captureDeadlineTick = openedAtTick + CAPTURE_TIMEOUT_TICKS;
		client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
		client.runScript(COLLECTION_INIT_SCRIPT);
	}

	private void startUpload()
	{
		if (!data.isCurrentPlayerClanMember())
		{
			waitingForCapture = false;
			return;
		}
		if (data.connection() != AnchorDataService.Connection.CONNECTED)
		{
			message("The Anchor is not connected. Check your Auth Code in plugin config.");
			return;
		}
		waitingForCapture = false;
		uploadInProgress = true;
		if (collectionLogSync.syncCapturedItems(this::onSyncFinished))
		{
			message("Syncing your collection log with The Anchor...");
		}
		else
		{
			uploadInProgress = false;
			log.error("Automatic collection log sync lost capture readiness before upload");
			message("Collection data is still loading. Wait a moment and try again.");
		}
	}

	private void onSyncFinished(boolean successful)
	{
		clientThread.invokeLater(() ->
		{
			uploadInProgress = false;
			if (successful)
			{
				message("Your collection log has been synced with The Anchor.");
			}
			else
			{
				message("The Anchor queued your collection log and will retry it automatically.");
			}
		});
	}

	private boolean isPohLog()
	{
		return client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1;
	}

	private void message(String text)
	{
		client.addChatMessage(ChatMessageType.CONSOLE, "The Anchor", text, "The Anchor");
	}

	public String overlayMessage()
	{
		if (uploadInProgress) return CollectionLogSyncOverlay.SYNCING_MESSAGE;
		if (waitingForCapture) return CollectionLogSyncOverlay.READING_MESSAGE;
		return CollectionLogSyncOverlay.MESSAGE;
	}
}
