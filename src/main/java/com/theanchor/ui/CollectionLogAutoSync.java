package com.theanchor.ui;

import com.theanchor.collection.CollectionLogSyncService;
import com.theanchor.service.AnchorDataService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
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

	private final Client client;
	private final ClientThread clientThread;
	private final EventBus eventBus;
	private final AnchorDataService data;
	private final CollectionLogSyncService collectionLogSync;
	private boolean waitingForCapture;
	private boolean uploadInProgress;
	private long attemptedAccountHash;
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
	}

	/** Re-arms capture or immediately syncs when the in-log Anchor button is clicked. */
	void refreshRequested()
	{
		if (!data.isCurrentPlayerClanMember()) return;
		if (uploadInProgress)
		{
			message("A sync is already in progress.");
			return;
		}
		attemptedAccountHash = 0L;
		if (collectionLogSync.hasCapturedItems())
		{
			startUpload();
		}
		else
		{
			armCapture();
			message("Open the collection-log Search to load data for The Anchor...");
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (!data.isCurrentPlayerClanMember()
			|| event.getGroupId() != InterfaceID.COLLECTION || isPohLog()) return;
		long accountHash = client.getAccountHash();
		if (accountHash == 0L || waitingForCapture || uploadInProgress || attemptedAccountHash == accountHash
			|| collectionLogSync.hasSyncedCurrentAccount()) return;

		armCapture();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!waitingForCapture || uploadInProgress) return;
		if (collectionLogSync.isCaptureReadySince(openedAtTick))
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
		attemptedAccountHash = client.getAccountHash();
		if (collectionLogSync.syncCapturedItems(this::onSyncFinished))
		{
			message("Syncing your collection log with The Anchor...");
		}
		else
		{
			uploadInProgress = false;
			attemptedAccountHash = 0L;
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
