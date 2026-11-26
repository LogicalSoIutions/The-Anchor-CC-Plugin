package com.theanchor.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.theanchor.AnchorConfig;
import com.theanchor.collection.CollectionLogPromptState;
import com.theanchor.model.AnchorModels;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Durable queue for JSON snapshots. Event uploads keep their evidence in EvidenceStore. */
@Singleton
public class SyncOutbox
{
	private static final Logger log = LoggerFactory.getLogger(SyncOutbox.class);
	private static final String LEGACY_COLLECTION_PREFIX = "collectionLogPending.";

	public enum Kind
	{
		COLLECTION_LOG(AnchorApiClient.COLLECTION_LOG_PATH, "Collection log"),
		PLAYER_PROGRESS(AnchorApiClient.PLAYER_PROGRESS_PATH, "Player progress"),
		PERSONAL_BEST(AnchorApiClient.PERSONAL_BEST_PATH, "Personal bests"),
		PERSONAL_BEST_BULK(AnchorApiClient.PERSONAL_BEST_BULK_PATH, "Personal bests");

		private final String endpoint;
		public final String label;

		Kind(String endpoint, String label)
		{
			this.endpoint = endpoint;
			this.label = label;
		}
	}

	public static final class Review
	{
		public final Kind kind;
		public final String name;
		public final String detail;

		private Review(Entry entry)
		{
			kind = entry.kind;
			name = entry.name();
			detail = entry.reviewDetail;
		}
	}

	private static final class Entry
	{
		private Kind kind;
		private JsonObject request;
		private String reviewDetail;

		private String syncId() { return request.get("syncId").getAsString(); }
		private String account() { return request.getAsJsonObject("player").get("accountHash").getAsString(); }
		private String name() { return request.getAsJsonObject("player").get("name").getAsString(); }
		private String capturedAt() { return request.get("capturedAt").getAsString(); }
	}

	private static final class Attempt
	{
		private final Entry entry;
		private final AnchorApiClient.ResultCallback<Map> completion;

		private Attempt(Entry entry, AnchorApiClient.ResultCallback<Map> completion)
		{
			this.entry = entry;
			this.completion = completion;
		}
	}

	private final Filepath root;
	private final Gson gson;
	private final AnchorApiClient api;
	private final AnchorConfig config;
	private final ConfigManager configManager;
	private final CollectionLogPromptState collectionLogState;
	private final ScheduledExecutorService executor;
	// The bulk and single PB endpoints share a lane. A failure in one lane does not stop another.
	private final Map<String, ArrayDeque<Attempt>> lanes = new HashMap<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private final Object reviewLock = new Object();
	private volatile String activeAccount;
	private String reviewedAccount;
	private List<Review> reviews = List.of();

	@Inject
	public SyncOutbox(@Named("anchorDirectory") Filepath directory, Gson gson, AnchorApiClient api,
		AnchorConfig config, ConfigManager configManager, CollectionLogPromptState collectionLogState,
		ScheduledExecutorService executor)
	{
		this.root = directory.joinSegment("sync-outbox");
		this.gson = gson;
		this.api = api;
		this.config = config;
		this.configManager = configManager;
		this.collectionLogState = collectionLogState;
		this.executor = executor;
	}

	public void addListener(Runnable listener) { listeners.add(listener); }
	public List<Review> reviews(String account)
	{
		synchronized (reviewLock) { return account != null && account.equals(reviewedAccount) ? reviews : List.of(); }
	}

	public boolean isReady(String account)
	{
		synchronized (reviewLock) { return account != null && account.equals(reviewedAccount); }
	}

	public void setActiveAccount(String account)
	{
		synchronized (reviewLock)
		{
			if (Objects.equals(activeAccount, account)) return;
			activeAccount = account;
			reviewedAccount = null;
			reviews = List.of();
		}
		executor.execute(() -> refreshReviews(account));
	}

	public boolean hasReview(Kind kind, AnchorModels.PlayerIdentity player)
	{
		synchronized (reviewLock)
		{
			return player != null && player.accountHash.equals(reviewedAccount)
				&& reviews.stream().anyMatch(review -> review.kind == kind && review.name.equalsIgnoreCase(player.name));
		}
	}

	public void enqueue(Kind kind, Object request, AnchorApiClient.ResultCallback<Map> completion)
	{
		executor.execute(() -> saveAndSchedule(kind, gson.toJsonTree(request).getAsJsonObject(), completion));
	}

	private synchronized void saveAndSchedule(Kind kind, JsonObject request, AnchorApiClient.ResultCallback<Map> completion)
	{
		Entry entry = new Entry();
		entry.kind = kind;
		entry.request = request;
		try
		{
			write(entry);
		}
		catch (IOException e)
		{
			log.error("Could not save Anchor {} upload {}", kind.label, entry.syncId(), e);
			if (completion != null) completion.complete(AnchorApiClient.ApiResult.error(0, "Could not save upload locally"));
			return;
		}
		schedule(entry, completion);
	}

	public void retry(String account, boolean manual)
	{
		if (account != null) executor.execute(() -> retrySaved(account, manual));
	}

	private synchronized void retrySaved(String account, boolean manual)
	{
		if (!account.equals(activeAccount)) return;
		importLegacyCollectionLog(account);
		List<Entry> entries = readEntries(account);
		entries.sort(Comparator.comparing(Entry::capturedAt).thenComparing(Entry::syncId));
		for (Entry entry : entries)
			if (manual || entry.reviewDetail == null) schedule(entry, null);
	}

	private void schedule(Entry entry, AnchorApiClient.ResultCallback<Map> completion)
	{
		String code = config.authenticationCode();
		if (!entry.account().equals(activeAccount) || code == null || code.isBlank())
		{
			if (completion != null) completion.complete(AnchorApiClient.ApiResult.error(0, "Upload saved for this character"));
			return;
		}

		String lane = entry.account() + ":" + (entry.kind == Kind.PERSONAL_BEST_BULK ? Kind.PERSONAL_BEST : entry.kind);
		ArrayDeque<Attempt> queue = lanes.computeIfAbsent(lane, key -> new ArrayDeque<>());
		if (queue.stream().anyMatch(attempt -> attempt.entry.syncId().equals(entry.syncId()))) return;
		queue.addLast(new Attempt(entry, completion));
		if (queue.size() == 1) sendNext(lane, queue);
	}

	private void sendNext(String lane, ArrayDeque<Attempt> queue)
	{
		Attempt attempt = queue.peekFirst();
		String code = config.authenticationCode();
		if (attempt == null || !attempt.entry.account().equals(activeAccount) || code == null || code.isBlank())
		{
			lanes.remove(lane);
			for (Attempt pending : queue)
				if (pending.completion != null)
					pending.completion.complete(AnchorApiClient.ApiResult.error(0, "Upload saved for this character"));
			return;
		}

		api.syncSnapshot(attempt.entry.kind.endpoint, attempt.entry.request,
			result -> executor.execute(() -> finish(lane, queue, attempt, result)));
	}

	private synchronized void finish(String lane, ArrayDeque<Attempt> queue, Attempt attempt,
		AnchorApiClient.ApiResult<Map> result)
	{
		Entry entry = attempt.entry;
		boolean hadReview = entry.reviewDetail != null;
		try
		{
			if (result.isSuccessful())
			{
				if (entry.kind == Kind.COLLECTION_LOG) collectionLogState.markSyncedForAccount(entry.account());
				path(entry).deleteIfExists();
			}
			else
			{
				if (result.needsIdentityReview())
				{
					entry.reviewDetail = result.error;
					write(entry);
				}
				log.warn("Anchor {} upload {} remains queued: HTTP {}, {}", entry.kind.label,
					entry.syncId(), result.statusCode, result.error);
			}
		}
		catch (IOException e)
		{
			log.error("Could not update Anchor queue for upload {}", entry.syncId(), e);
		}
		if (hadReview || result.needsIdentityReview()) refreshReviews(entry.account());
		queue.removeFirst();
		sendNext(lane, queue);
		if (attempt.completion != null) attempt.completion.complete(result);
	}

	private synchronized void refreshReviews(String account)
	{
		if (!Objects.equals(account, activeAccount)) return;
		List<Review> current = readEntries(account).stream().filter(entry -> entry.reviewDetail != null)
			.map(Review::new).collect(Collectors.toUnmodifiableList());
		synchronized (reviewLock)
		{
			if (!Objects.equals(account, activeAccount)) return;
			reviewedAccount = account;
			reviews = current;
		}
		for (Runnable listener : listeners) listener.run();
	}

	private List<Entry> readEntries(String account)
	{
		List<Entry> entries = new ArrayList<>();
		if (account == null) return entries;
		Filepath dir = root.joinSegment(account);
		if (!dir.isDirectory()) return entries;
		try (Stream<Filepath> paths = dir.walk(1))
		{
			paths.filter(path -> path.isFile() && path.getFileName().endsWith(".json")).forEach(path ->
			{
				try (Reader reader = path.openReader())
				{
					Entry entry = gson.fromJson(reader, Entry.class);
					if (entry != null && entry.kind != null && account.equals(entry.account())
						&& !entry.name().isBlank() && !entry.capturedAt().isBlank()
						&& entry.syncId().matches("[A-Za-z0-9_-]+") && path.getFileName().equals(entry.syncId() + ".json"))
						entries.add(entry);
				}
				catch (IOException | RuntimeException e)
				{
					log.warn("Could not read pending Anchor upload {}", path, e);
				}
			});
		}
		catch (IOException e)
		{
			log.warn("Could not read Anchor sync queue", e);
		}
		return entries;
	}

	private void importLegacyCollectionLog(String account)
	{
		String key = LEGACY_COLLECTION_PREFIX + account;
		String json = configManager.getConfiguration(AnchorConfig.GROUP, key);
		if (json == null || json.isBlank()) return;
		try
		{
			Entry entry = new Entry();
			entry.kind = Kind.COLLECTION_LOG;
			entry.request = new JsonParser().parse(json).getAsJsonObject();
			if (!account.equals(entry.account()) || entry.name().isBlank() || entry.capturedAt().isBlank()) return;
			if (!path(entry).isFile()) write(entry);
			configManager.unsetConfiguration(AnchorConfig.GROUP, key);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not import pending collection log snapshot", e);
		}
	}

	private Filepath path(Entry entry)
	{
		String syncId = entry.syncId();
		if (!syncId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid sync ID");
		return root.joinSegment(entry.account()).joinSegment(syncId + ".json");
	}

	private synchronized void write(Entry entry) throws IOException
	{
		Filepath target = path(entry);
		Filepath dir = target.getParent();
		dir.createDirectories();
		Filepath temp = dir.joinSegment(target.getFileName() + ".tmp");
		JsonObject saved = gson.toJsonTree(entry).getAsJsonObject();
		saved.add("request", entry.request);
		temp.write(saved.toString());
		temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
	}
}
