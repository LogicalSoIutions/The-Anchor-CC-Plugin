/*
 * Portions adapted from the Terpinheimer collection-log synchronization code.
 * The item capture also follows RuneProfile's WikiSync-derived script 4100 flow.
 * See LICENSES/terpinheimer-LICENSE.txt and LICENSES/reference-plugins.txt.
 */
package com.theanchor.collection;

import com.google.gson.Gson;
import com.theanchor.AnchorConfig;
import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.ItemComposition;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

@Slf4j
@Singleton
public class CollectionLogSyncService
{
	static final int COLLECTION_ITEM_SCRIPT = 4100;
	private static final String PENDING_PREFIX = "collectionLogPending.";

	private final Client client;
	private final ConfigManager configManager;
	private final Gson gson;
	private final AnchorApiClient api;
	private final CollectionLogPromptState promptState;
	private final ItemManager itemManager;
	private final Map<Integer, Integer> items = new LinkedHashMap<>();
	private int lastItemScriptTick = -1;
	private volatile String lastSuccessfulAccountHash;

	@Inject
	public CollectionLogSyncService(Client client, ConfigManager configManager, Gson gson, AnchorApiClient api,
		CollectionLogPromptState promptState, ItemManager itemManager)
	{
		this.client = client;
		this.configManager = configManager;
		this.gson = gson;
		this.api = api;
		this.promptState = promptState;
		this.itemManager = itemManager;
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != COLLECTION_ITEM_SCRIPT
			|| client.getGameState() != GameState.LOGGED_IN
			|| client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1)
		{
			return;
		}
		Object[] args = event.getScriptEvent().getArguments();
		if (args == null || args.length < 3 || !(args[1] instanceof Number) || !(args[2] instanceof Number)) return;
		int itemId = ((Number) args[1]).intValue();
		int quantity = ((Number) args[2]).intValue();
		if (itemId <= 0 || quantity <= 0) return;
		synchronized (items) { items.put(itemId, quantity); }
		lastItemScriptTick = client.getTickCount();
		if (items.size() == 1 || items.size() % 100 == 0)
			log.debug("Collection log item capture progress: decodedItems={}, lastItemId={}", items.size(), itemId);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.HOPPING && event.getGameState() != GameState.LOGGED_IN)
		{
			synchronized (items) { items.clear(); }
			lastItemScriptTick = -1;
		}
	}

	/** Called after the open collection log has finished populating its item rows. */
	public boolean syncCapturedItems()
	{
		return syncCapturedItems(success -> { });
	}

	/** Starts a sync and reports whether the server accepted it. */
	public boolean syncCapturedItems(Consumer<Boolean> completion)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null
			|| client.getAccountHash() == 0L)
		{
			log.debug("Collection log sync skipped because account state was not ready");
			return false;
		}
		Map<Integer, Integer> captured;
		synchronized (items) { captured = new LinkedHashMap<>(items); }
		if (lastItemScriptTick != -1 && lastItemScriptTick + 2 >= client.getTickCount())
		{
			log.debug("Collection log sync requested while item rows were still settling: lastRowTick={}, currentTick={}",
				lastItemScriptTick, client.getTickCount());
			return false;
		}
		if (captured.isEmpty())
		{
			log.warn("Collection log sync requested before script {} produced any item rows; lastRowTick={}, currentTick={}",
				COLLECTION_ITEM_SCRIPT, lastItemScriptTick, client.getTickCount());
			return false;
		}
		AnchorModels.CollectionLogRequest request = collect(captured);
		configManager.setConfiguration(AnchorConfig.GROUP, pendingKey(), gson.toJson(request));
		log.info("Collection log sync starting: player={}, obtained={}/{}, decodedItems={}, lastRowTick={}",
			request.player.name, request.obtainedCount, request.totalCount, request.items.size(), lastItemScriptTick);
		send(request, completion);
		return true;
	}

	/** True once this opening of the collection log has produced and settled its item rows. */
	public boolean isCaptureReadySince(int openedAtTick)
	{
		if (client.getGameState() != GameState.LOGGED_IN || lastItemScriptTick < openedAtTick - 1
			|| lastItemScriptTick + 2 >= client.getTickCount()) return false;
		synchronized (items) { return !items.isEmpty(); }
	}

	public boolean hasCapturedItems()
	{
		synchronized (items) { return !items.isEmpty(); }
	}

	public boolean hasSyncedCurrentAccount()
	{
		return Long.toString(client.getAccountHash()).equals(lastSuccessfulAccountHash)
			|| promptState.hasSyncedForCurrentAccount();
	}

	public void retryPending()
	{
		String key = pendingKey();
		if (key == null) return;
		String json = configManager.getConfiguration(AnchorConfig.GROUP, key);
		if (json == null || json.isBlank()) return;
		try
		{
			AnchorModels.CollectionLogRequest request = gson.fromJson(json, AnchorModels.CollectionLogRequest.class);
			if (request == null || request.syncId == null || request.items == null || request.items.isEmpty())
			{
				configManager.unsetConfiguration(AnchorConfig.GROUP, key);
				log.info("Discarded legacy packed collection snapshot; reopen the collection log to create a decoded snapshot");
				return;
			}
			log.info("Retrying pending collection log sync: player={}, syncId={}", request.player.name, request.syncId);
			send(request, success -> { });
		}
		catch (RuntimeException e)
		{
			configManager.unsetConfiguration(AnchorConfig.GROUP, key);
			log.warn("Discarded unreadable pending collection log snapshot", e);
		}
	}

	AnchorModels.CollectionLogRequest collect(Map<Integer, Integer> captured)
	{
		AnchorModels.CollectionLogRequest request = new AnchorModels.CollectionLogRequest();
		request.syncId = UUID.randomUUID().toString();
		request.capturedAt = Instant.now().toString();
		request.player = new AnchorModels.PlayerIdentity();
		request.player.name = client.getLocalPlayer().getName();
		request.player.accountHash = Long.toString(client.getAccountHash());
		request.obtainedCount = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
		request.totalCount = client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX);
		addCategory(request, "bosses", VarPlayerID.COLLECTION_COUNT_BOSSES, VarPlayerID.COLLECTION_COUNT_BOSSES_MAX);
		addCategory(request, "raids", VarPlayerID.COLLECTION_COUNT_RAIDS, VarPlayerID.COLLECTION_COUNT_RAIDS_MAX);
		addCategory(request, "clues", VarPlayerID.COLLECTION_COUNT_CLUES, VarPlayerID.COLLECTION_COUNT_CLUES_MAX);
		addCategory(request, "minigames", VarPlayerID.COLLECTION_COUNT_MINIGAMES, VarPlayerID.COLLECTION_COUNT_MINIGAMES_MAX);
		addCategory(request, "other", VarPlayerID.COLLECTION_COUNT_OTHER, VarPlayerID.COLLECTION_COUNT_OTHER_MAX);
		if (captured != null)
		{
			for (Map.Entry<Integer, Integer> entry : captured.entrySet())
			{
				request.items.put(resolveItemName(entry.getKey()), entry.getValue());
			}
		}
		return request;
	}

	private String resolveItemName(int itemId)
	{
		if (itemManager != null)
		{
			try
			{
				ItemComposition comp = itemManager.getItemComposition(itemId);
				if (comp != null && comp.getName() != null && !comp.getName().isEmpty() && !"null".equalsIgnoreCase(comp.getName()))
				{
					return comp.getName();
				}
			}
			catch (Exception e)
			{
				log.debug("Failed to resolve item name for item ID {}", itemId, e);
			}
		}
		return String.valueOf(itemId);
	}

	private void addCategory(AnchorModels.CollectionLogRequest request, String name, int obtainedVarp, int totalVarp)
	{
		AnchorModels.CollectionCategoryProgress progress = new AnchorModels.CollectionCategoryProgress();
		progress.obtained = client.getVarpValue(obtainedVarp);
		progress.total = client.getVarpValue(totalVarp);
		request.categories.put(name, progress);
	}

	private void send(AnchorModels.CollectionLogRequest request, Consumer<Boolean> completion)
	{
		log.debug("Anchor collection log request payload: {}", gson.toJson(request));
		api.syncCollectionLog(request, result ->
		{
			if (result.isSuccessful())
			{
				lastSuccessfulAccountHash = request.player.accountHash;
				promptState.markSyncedForAccount(request.player.accountHash);
				configManager.unsetConfiguration(AnchorConfig.GROUP, PENDING_PREFIX + request.player.accountHash);
				log.info("Collection log sync completed: player={}, decodedItems={}, syncId={}",
					request.player.name, request.items.size(), request.syncId);
			}
			else
			{
				log.warn("Collection log sync failed and remains queued: player={}, syncId={}, error={}",
					request.player.name, request.syncId, result.error);
			}
			completion.accept(result.isSuccessful());
		});
	}

	private String pendingKey()
	{
		long hash = client.getAccountHash();
		return hash == 0L ? null : PENDING_PREFIX + hash;
	}
}
