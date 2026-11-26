/*
 * Portions adapted from the Terpinheimer collection-log synchronization code.
 * The item capture also follows RuneProfile's WikiSync-derived script 4100 flow.
 * See LICENSES/terpinheimer-LICENSE.txt and LICENSES/reference-plugins.txt.
 */
package com.theanchor.collection;

import com.theanchor.api.AnchorApiClient;
import com.theanchor.api.SyncOutbox;
import com.theanchor.model.CharacterIdentity;
import com.theanchor.model.AnchorModels;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
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
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

@Slf4j
@Singleton
public class CollectionLogSyncService
{
	static final int COLLECTION_ITEM_SCRIPT = 4100;

	private final Client client;
	private final SyncOutbox outbox;
	private final CollectionLogPromptState promptState;
	private final ItemManager itemManager;
	private final ScheduledExecutorService executor;
	private final Map<Integer, Integer> items = new LinkedHashMap<>();
	private int lastItemScriptTick = -1;
	private String itemAccountHash;

	@Inject
	public CollectionLogSyncService(Client client, SyncOutbox outbox, CollectionLogPromptState promptState,
		ItemManager itemManager, ScheduledExecutorService executor)
	{
		this.client = client;
		this.outbox = outbox;
		this.promptState = promptState;
		this.itemManager = itemManager;
		this.executor = executor;
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
		AnchorModels.PlayerIdentity player = CharacterIdentity.capture(client);
		if (player == null) return;
		synchronized (items)
		{
			if (!player.accountHash.equals(itemAccountHash)) items.clear();
			itemAccountHash = player.accountHash;
			items.put(itemId, quantity);
			lastItemScriptTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.HOPPING && event.getGameState() != GameState.LOGGED_IN)
		{
			beginCapture();
		}
	}

	/** Discards the previous opening's rows before requesting a fresh full log. */
	public void beginCapture()
	{
		synchronized (items)
		{
			items.clear();
			itemAccountHash = null;
			lastItemScriptTick = -1;
		}
	}

	/** Called after the open collection log has finished populating its item rows. */
	public boolean syncCapturedItems()
	{
		return syncCapturedItems(result -> { });
	}

	/** Starts a sync and reports whether the server accepted it. */
	public boolean syncCapturedItems(Consumer<AnchorApiClient.ApiResult<Map>> completion)
	{
		AnchorModels.PlayerIdentity player = CharacterIdentity.capture(client);
		if (player == null) return false;
		Map<Integer, Integer> captured;
		synchronized (items)
		{
			if (!player.accountHash.equals(itemAccountHash) || lastItemScriptTick + 2 >= client.getTickCount())
				return false;
			captured = new LinkedHashMap<>(items);
		}
		if (captured.isEmpty())
		{
			log.error("Collection log sync requested before script {} produced any item rows; lastRowTick={}, currentTick={}",
				COLLECTION_ITEM_SCRIPT, lastItemScriptTick, client.getTickCount());
			return false;
		}
		// Capture only the small client-thread-safe metadata here. A full collection
		// log can contain thousands of rows, so request assembly and serialisation are
		// deliberately deferred to the worker executor.
		AnchorModels.CollectionLogRequest request = collectMetadata(player);
		executor.execute(() ->
		{
			addCapturedItems(request, captured, false);
			outbox.enqueue(SyncOutbox.Kind.COLLECTION_LOG, request, completion::accept);
		});
		return true;
	}

	/** True once this opening of the collection log has produced and settled its item rows. */
	public boolean isCaptureReadySince(int openedAtTick)
	{
		AnchorModels.PlayerIdentity player = CharacterIdentity.capture(client);
		synchronized (items)
		{
			return player != null && player.accountHash.equals(itemAccountHash)
				&& lastItemScriptTick >= openedAtTick - 1 && lastItemScriptTick + 2 < client.getTickCount()
				&& !items.isEmpty();
		}
	}

	public boolean hasCapturedItems()
	{
		synchronized (items) { return !items.isEmpty(); }
	}

	public boolean hasSyncedCurrentAccount()
	{
		return promptState.hasSyncedForCurrentAccount();
	}

	public boolean needsIdentityReview()
	{
		return outbox.hasReview(SyncOutbox.Kind.COLLECTION_LOG, CharacterIdentity.capture(client));
	}

	public boolean isAutomaticSyncDue()
	{
		AnchorModels.PlayerIdentity player = CharacterIdentity.capture(client);
		return player != null && outbox.isReady(player.accountHash)
			&& !outbox.hasReview(SyncOutbox.Kind.COLLECTION_LOG, player) && promptState.isAutomaticSyncDue();
	}

	AnchorModels.CollectionLogRequest collect(Map<Integer, Integer> captured)
	{
		AnchorModels.PlayerIdentity player = CharacterIdentity.capture(client);
		if (player == null) return null;
		AnchorModels.CollectionLogRequest request = collectMetadata(player);
		addCapturedItems(request, captured, true);
		return request;
	}

	private AnchorModels.CollectionLogRequest collectMetadata(AnchorModels.PlayerIdentity player)
	{
		AnchorModels.CollectionLogRequest request = new AnchorModels.CollectionLogRequest();
		request.syncId = UUID.randomUUID().toString();
		request.capturedAt = Instant.now().toString();
		request.player = player;
		request.obtainedCount = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
		request.totalCount = client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX);
		addCategory(request, "bosses", VarPlayerID.COLLECTION_COUNT_BOSSES, VarPlayerID.COLLECTION_COUNT_BOSSES_MAX);
		addCategory(request, "raids", VarPlayerID.COLLECTION_COUNT_RAIDS, VarPlayerID.COLLECTION_COUNT_RAIDS_MAX);
		addCategory(request, "clues", VarPlayerID.COLLECTION_COUNT_CLUES, VarPlayerID.COLLECTION_COUNT_CLUES_MAX);
		addCategory(request, "minigames", VarPlayerID.COLLECTION_COUNT_MINIGAMES, VarPlayerID.COLLECTION_COUNT_MINIGAMES_MAX);
		addCategory(request, "other", VarPlayerID.COLLECTION_COUNT_OTHER, VarPlayerID.COLLECTION_COUNT_OTHER_MAX);
		return request;
	}

	private void addCapturedItems(AnchorModels.CollectionLogRequest request, Map<Integer, Integer> captured,
		boolean includeNames)
	{
		if (captured != null)
		{
			for (Map.Entry<Integer, Integer> entry : captured.entrySet())
			{
				String itemId = String.valueOf(entry.getKey());
				request.items.put(itemId, entry.getValue());
				if (includeNames) request.itemNames.put(itemId, resolveItemName(entry.getKey()));
			}
		}
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
				log.error("Failed to resolve item name for item ID {}", itemId, e);
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

}
