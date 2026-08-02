/*
 * Tests adapted with the Terpinheimer collection-log synchronization flow.
 * See LICENSES/terpinheimer-LICENSE.txt.
 */
package com.theanchor.collection;

import com.google.gson.Gson;
import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.api.ScriptEvent;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

public class CollectionLogSyncServiceTest
{
	@Test public void buildsDecodedItemMapAndCategoryCounts()
	{
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		ItemManager itemManager = mock(ItemManager.class);
		ItemComposition axeComp = mock(ItemComposition.class);
		when(axeComp.getName()).thenReturn("Dragon axe");
		when(itemManager.getItemComposition(11832)).thenReturn(axeComp);

		ItemComposition blowpipeComp = mock(ItemComposition.class);
		when(blowpipeComp.getName()).thenReturn("Toxic blowpipe");
		when(itemManager.getItemComposition(12922)).thenReturn(blowpipeComp);

		when(player.getName()).thenReturn("Zach");
		when(client.getLocalPlayer()).thenReturn(player);
		when(client.getAccountHash()).thenReturn(456L);
		when(client.getVarpValue(VarPlayerID.COLLECTION_COUNT)).thenReturn(800);
		when(client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX)).thenReturn(1700);

		CollectionLogSyncService service = new CollectionLogSyncService(client, mock(ConfigManager.class),
			new Gson(), mock(AnchorApiClient.class), mock(CollectionLogPromptState.class), itemManager);
		Map<Integer, Integer> captured = new LinkedHashMap<>();
		captured.put(11832, 1);
		captured.put(12922, 27);
		AnchorModels.CollectionLogRequest request = service.collect(captured);

		assertEquals(800, request.obtainedCount);
		assertEquals(1700, request.totalCount);
		assertEquals(5, request.categories.size());
		assertEquals("collection_interface_items", request.source);
		assertEquals(2, request.items.size());
		assertEquals(Integer.valueOf(1), request.items.get("Dragon axe"));
		assertEquals(Integer.valueOf(27), request.items.get("Toxic blowpipe"));
	}

	@Test public void capturesScriptItemRowsBeforeSyncing()
	{
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		ItemManager itemManager = mock(ItemManager.class);
		ItemComposition axeComp = mock(ItemComposition.class);
		when(axeComp.getName()).thenReturn("Dragon axe");
		when(itemManager.getItemComposition(11832)).thenReturn(axeComp);

		when(player.getName()).thenReturn("Zach");
		when(client.getLocalPlayer()).thenReturn(player);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getAccountHash()).thenReturn(456L);
		when(client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN)).thenReturn(0);
		when(client.getTickCount()).thenReturn(100, 103, 103);
		AnchorApiClient api = mock(AnchorApiClient.class);
		CollectionLogPromptState promptState = mock(CollectionLogPromptState.class);
		CollectionLogSyncService service = new CollectionLogSyncService(client, mock(ConfigManager.class), new Gson(), api,
			promptState, itemManager);

		ScriptEvent scriptEvent = mock(ScriptEvent.class);
		when(scriptEvent.getArguments()).thenReturn(new Object[] {CollectionLogSyncService.COLLECTION_ITEM_SCRIPT, 11832, 4});
		ScriptPreFired fired = mock(ScriptPreFired.class);
		when(fired.getScriptId()).thenReturn(CollectionLogSyncService.COLLECTION_ITEM_SCRIPT);
		when(fired.getScriptEvent()).thenReturn(scriptEvent);
		service.onScriptPreFired(fired);

		assertTrue(service.syncCapturedItems());
		ArgumentCaptor<AnchorModels.CollectionLogRequest> payload = ArgumentCaptor.forClass(AnchorModels.CollectionLogRequest.class);
		ArgumentCaptor<AnchorApiClient.ResultCallback> callback = ArgumentCaptor.forClass(AnchorApiClient.ResultCallback.class);
		verify(api).syncCollectionLog(payload.capture(), callback.capture());
		assertEquals(Integer.valueOf(4), payload.getValue().items.get("Dragon axe"));
		verify(promptState, never()).markSyncedForAccount("456");
		callback.getValue().complete(AnchorApiClient.ApiResult.ok(200, Map.of()));
		verify(promptState).markSyncedForAccount("456");
	}

	@Test public void captureIsReadyOnlyAfterCurrentRowsSettle()
	{
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN)).thenReturn(0);
		when(client.getTickCount()).thenReturn(100, 101, 103);
		CollectionLogSyncService service = new CollectionLogSyncService(client, mock(ConfigManager.class),
			new Gson(), mock(AnchorApiClient.class), mock(CollectionLogPromptState.class), mock(ItemManager.class));

		ScriptEvent scriptEvent = mock(ScriptEvent.class);
		when(scriptEvent.getArguments()).thenReturn(new Object[] {CollectionLogSyncService.COLLECTION_ITEM_SCRIPT, 11832, 1});
		ScriptPreFired fired = mock(ScriptPreFired.class);
		when(fired.getScriptId()).thenReturn(CollectionLogSyncService.COLLECTION_ITEM_SCRIPT);
		when(fired.getScriptEvent()).thenReturn(scriptEvent);
		service.onScriptPreFired(fired);

		assertFalse(service.isCaptureReadySince(102));
		assertFalse(service.isCaptureReadySince(100));
		assertTrue(service.isCaptureReadySince(100));
	}
}
