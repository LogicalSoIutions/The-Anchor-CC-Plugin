package com.theanchor.ui;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.theanchor.collection.CollectionLogSyncService;
import com.theanchor.service.AnchorDataService;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import org.junit.Test;

public class CollectionLogAutoSyncTest
{
	@Test
	public void manualRefreshWithNoCapturedItemsRequestsCollectionData()
	{
		Client client = mock(Client.class);
		CollectionLogSyncService sync = mock(CollectionLogSyncService.class);
		when(client.getTickCount()).thenReturn(100);
		when(sync.hasCapturedItems()).thenReturn(false);
		CollectionLogAutoSync autoSync = new CollectionLogAutoSync(client, mock(ClientThread.class),
			mock(EventBus.class), mock(AnchorDataService.class), sync);

		autoSync.refreshRequested();

		assertEquals(CollectionLogSyncOverlay.READING_MESSAGE, autoSync.overlayMessage());
		verify(client).menuAction(-1, net.runelite.api.gameval.InterfaceID.Collection.SEARCH_TOGGLE,
			net.runelite.api.MenuAction.CC_OP, 1, -1, "Search", null);
		verify(client).runScript(2240);
	}

	@Test
	public void manualRefreshWithCapturedItemsStartsUpload()
	{
		Client client = mock(Client.class);
		CollectionLogSyncService sync = mock(CollectionLogSyncService.class);
		AnchorDataService data = mock(AnchorDataService.class);
		when(client.getTickCount()).thenReturn(100);
		when(sync.hasCapturedItems()).thenReturn(true);
		when(data.connection()).thenReturn(AnchorDataService.Connection.CONNECTED);
		when(sync.syncCapturedItems(org.mockito.ArgumentMatchers.any())).thenReturn(true);
		CollectionLogAutoSync autoSync = new CollectionLogAutoSync(client, mock(ClientThread.class),
			mock(EventBus.class), data, sync);

		autoSync.refreshRequested();

		assertEquals(CollectionLogSyncOverlay.SYNCING_MESSAGE, autoSync.overlayMessage());
	}

	@Test
	public void refreshButtonPositionIsClampedInsideCollectionFrame()
	{
		assertEquals(0, CollectionLogRefreshButton.clamp(-27, 0, 480));
		assertEquals(320, CollectionLogRefreshButton.clamp(320, 0, 480));
		assertEquals(480, CollectionLogRefreshButton.clamp(510, 0, 480));
	}
}
