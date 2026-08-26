package com.theanchor.evidence;

import com.theanchor.AnchorConfig;
import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import com.theanchor.service.PartyTracker;
import com.theanchor.service.RulesService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class EventPipeline
{
	private static final Logger log = LoggerFactory.getLogger(EventPipeline.class);
	private final EventDeduplicator deduplicator = new EventDeduplicator(8_000L);
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private final Map<String, CaptureGroup> captureGroups = new ConcurrentHashMap<>();
	private volatile CaptureGroup recentTriggerGroup;
	@Inject private Client client;
	@Inject private AnchorConfig config;
	@Inject private ScreenshotService screenshots;
	@Inject private EvidenceStore store;
	@Inject private AnchorApiClient api;
	@Inject private RulesService rules;
	@Inject private PartyTracker parties;
	@Inject private ScheduledExecutorService executor;

	public void addListener(Runnable listener) { listeners.add(listener); }
	public void removeListener(Runnable listener) { listeners.remove(listener); }

	public void capture(String type, String dedupeKey, AnchorModels.Source source, List<AnchorModels.Item> items,
		java.util.Map<String, Object> details, boolean includeParty)
	{
		capture(type, dedupeKey, source, items, details, includeParty, null, true, true);
	}

	public void capture(String type, String dedupeKey, AnchorModels.Source source, List<AnchorModels.Item> items,
		java.util.Map<String, Object> details, boolean includeParty, String rulesVersionOverride,
		boolean screenshotRequired, boolean finalizeSubmission)
	{
		Player local = client.getLocalPlayer();
		long now = System.currentTimeMillis();
		if (local == null || local.getName() == null || !deduplicator.accept(type + '|' + dedupeKey, now)) return;
		CaptureGroup group = groupFor(type, source, details, now);
		AnchorModels.EventEnvelope envelope = new AnchorModels.EventEnvelope();
		envelope.eventId = UUID.randomUUID().toString(); envelope.eventType = type; envelope.capturedAt = Instant.now().toString();
		envelope.player = new AnchorModels.PlayerIdentity(); envelope.player.name = local.getName(); envelope.player.accountHash = String.valueOf(client.getAccountHash());
		envelope.source = source; if (items != null) envelope.items.addAll(items); if (details != null) envelope.details.putAll(details);
		String partySource = source == null ? null : source.name;
		if (partySource == null && details != null && details.get("record") instanceof AnchorModels.PbRecord)
			partySource = ((AnchorModels.PbRecord) details.get("record")).activity;
		envelope.party = includeParty ? parties.snapshot(partySource) : null;
		envelope.rulesVersion = rulesVersionOverride == null || rulesVersionOverride.isBlank()
			? rules.current().version : rulesVersionOverride;
		envelope.pluginVersion = AnchorApiClient.PLUGIN_VERSION;
		envelope.context.put("world", client.getWorld()); envelope.context.put("gameCycle", client.getGameCycle());
		envelope.context.put("submissionGroupId", group.id);
		envelope.context.put("submissionTypes", group.types());
		envelope.context.put("finalizeSubmission", finalizeSubmission);
		refreshStoredGroup(group);
		if (!screenshotRequired)
		{
			try
			{
				EvidenceStore.Record record = store.saveMetadata(envelope); notifyListeners();
				upload(record, false);
			}
			catch (Exception e)
			{
				log.warn("Could not save evidence metadata for event {} ({})", envelope.eventId, envelope.eventType, e);
			}
			return;
		}
		screenshots.captureNextFrame(image ->
		{
			if (image == null)
			{
				log.warn("Evidence capture returned no screenshot for event {} ({})", envelope.eventId, envelope.eventType);
				return;
			}
			try
			{
				envelope.context.put("submissionTypes", group.types());
				EvidenceStore.Record record = store.save(envelope, image); notifyListeners();
				upload(record, false);
			}
			catch (Exception e)
			{
				log.warn("Could not save evidence for event {} ({})", envelope.eventId, envelope.eventType, e);
			}
		});
	}

	public void retryAll()
	{
		for (EvidenceStore.Record record : store.records())
			if (record.status == AnchorModels.EventStatus.PENDING || record.status == AnchorModels.EventStatus.FAILED) upload(record, true);
	}

	public List<EvidenceStore.Record> groupRecords(EvidenceStore.Record record)
	{
		String groupId = submissionGroupId(record);
		if (groupId == null) return record == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(record);
		List<EvidenceStore.Record> matches = new ArrayList<>();
		for (EvidenceStore.Record candidate : store.records())
			if (groupId.equals(submissionGroupId(candidate))) matches.add(candidate);
		return matches;
	}

	public void retryGroup(EvidenceStore.Record record)
	{
		for (EvidenceStore.Record member : groupRecords(record))
			if (member.status == AnchorModels.EventStatus.PENDING || member.status == AnchorModels.EventStatus.FAILED)
				upload(member, true);
	}

	public void updateAndSubmitGroup(EvidenceStore.Record record, int partySize, int clanMembers, int nonClanMembers, String notes)
	{
		for (EvidenceStore.Record member : groupRecords(record))
			if (member.status == AnchorModels.EventStatus.DRAFT)
				updateAndSubmit(member, partySize, clanMembers, nonClanMembers, notes);
	}

	public void upload(EvidenceStore.Record record, boolean manual)
	{
		String code = config.authenticationCode() == null ? "" : config.authenticationCode().trim();
		if (code.isEmpty())
		{
			log.warn("Skipped upload for event {} ({}): no authentication code is configured", eventId(record), eventType(record));
			return;
		}
		record.status = AnchorModels.EventStatus.UPLOADING; record.error = null; record.updatedAt = Instant.now().toString(); persist(record);
		Path screenshot = record.screenshotPath == null || record.screenshotPath.isBlank()
			? null : Path.of(record.screenshotPath);
		api.uploadEvent(record.metadata, screenshot, record.format, result ->
		{
			boolean autoSubmit = shouldAutoSubmit(record);
			if (result.isSuccessful() && result.value != null)
			{
				record.submissionId = result.value.submissionId; record.status = parseStatus(result.value.status);
				record.error = validationSummary(result.value.validationMessages);
				record.retryCount = 0;
			}
			else
			{
				record.status = AnchorModels.EventStatus.FAILED;
				record.error = result.error == null ? "Empty server response" : result.error;
				record.retryCount++;
				log.warn("Evidence upload failed for event {} ({}): HTTP {}, error={}, retry={}, manual={}",
					eventId(record), eventType(record), result.statusCode, record.error, record.retryCount, manual);
				if (!manual && result.isRetryable() && record.retryCount <= 5)
				{
					long delay = Math.min(300, 5L << Math.min(6, record.retryCount));
					executor.schedule(() -> upload(record, false), delay, TimeUnit.SECONDS);
				}
			}
			record.updatedAt = Instant.now().toString(); persist(record);
			if (autoSubmit && record.status == AnchorModels.EventStatus.DRAFT)
				autoSubmit(record);
		});
	}

	private static boolean shouldAutoSubmit(EvidenceStore.Record record)
	{
		if (record == null || record.metadata == null || record.metadata.details == null
			|| !Boolean.TRUE.equals(record.metadata.details.get("autoSubmit"))) return false;
		String type = record.metadata.eventType;
		if ("personal_best".equals(type) || "collection_log".equals(type)) return true;
		if (!"loot".equals(type) || record.metadata.party == null) return false;
		return record.metadata.party.detectedPartySize == 1;
	}

	private void autoSubmit(EvidenceStore.Record record)
	{
		AnchorModels.Party party = record.metadata.party;
		int partySize = party == null ? 1 : party.submittedPartySize;
		int clanMembers = party == null ? 0 : party.submittedClanMemberCount;
		int nonClanMembers = party == null ? 0 : party.submittedNonClanMemberCount;
		log.info("Automatically submitting event {} ({})", eventId(record), eventType(record));
		updateAndSubmit(record, partySize, clanMembers, nonClanMembers, "");
	}

	public void updateAndSubmit(EvidenceStore.Record record, int partySize, int clanMembers, int nonClanMembers, String notes)
	{
		if (record.submissionId == null)
		{
			record.error = "Submission ID is unavailable; retry the upload";
			log.warn("Could not submit event {}: submission ID is unavailable", eventId(record));
			persist(record);
			return;
		}
		record.status = AnchorModels.EventStatus.UPLOADING;
		record.error = null;
		persist(record);
		int size = Math.max(1, partySize);
		int clan = Math.max(0, Math.min(size, clanMembers));
		int nonClan = Math.max(0, Math.min(size - clan, nonClanMembers));
		api.updateSubmission(record.submissionId, size, clan, nonClan, notes == null ? "" : notes, patched ->
		{
			if (!patched.isSuccessful())
			{
				record.status = AnchorModels.EventStatus.DRAFT;
				record.error = patched.error;
				log.warn("Submission update failed for event {} (submission {}): HTTP {}, error={}",
					eventId(record), record.submissionId, patched.statusCode, patched.error);
				persist(record);
				return;
			}
			api.submit(record.submissionId, submitted ->
			{
				if (submitted.isSuccessful()) { record.status = AnchorModels.EventStatus.SUBMITTED; record.error = null; }
				else
				{
					record.status = AnchorModels.EventStatus.DRAFT;
					record.error = submitted.error;
					log.warn("Submission failed for event {} (submission {}): HTTP {}, error={}",
						eventId(record), record.submissionId, submitted.statusCode, submitted.error);
				}
				persist(record);
			});
		});
	}

	public void refreshStatuses(String playerName)
	{
		if (playerName == null || playerName.isBlank()) return;
		api.getSubmissions(playerName, result ->
		{
			if (!result.isSuccessful() || result.value == null)
			{
				log.warn("Could not refresh submissions: HTTP {}, error={}", result.statusCode,
					result.error == null ? "empty server response" : result.error);
				return;
			}
			for (AnchorModels.SubmissionSummary summary : result.value)
			{
				for (EvidenceStore.Record record : store.records())
				{
					if ((summary.eventId != null && summary.eventId.equals(record.metadata.eventId))
						|| (summary.submissionId != null && summary.submissionId.equals(record.submissionId)))
					{
						record.submissionId = summary.submissionId; record.status = parseStatus(summary.status);
						persist(record);
					}
				}
			}
		});
	}

	private void persist(EvidenceStore.Record record)
	{
		try { store.writeRecord(record); }
		catch (Exception e) { log.warn("Could not persist event {} ({})", eventId(record), eventType(record), e); }
		notifyListeners();
	}
	private void notifyListeners() { for (Runnable listener : listeners) listener.run(); }

	private synchronized CaptureGroup groupFor(String type, AnchorModels.Source source,
		java.util.Map<String, Object> details, long now)
	{
		String key = encounterKey(source, details);
		CaptureGroup group = key == null ? null : captureGroups.get(key);
		if (group == null || now - group.lastSeen > 8_000L || group.contains(type))
		{
			CaptureGroup recent = recentTriggerGroup;
			boolean triggerPair = recent != null && now - recent.lastSeen <= 4_000L
				&& (("bingo".equals(type) && recent.contains("personal_best"))
					|| ("personal_best".equals(type) && recent.contains("bingo")));
			group = triggerPair ? recent : new CaptureGroup(UUID.randomUUID().toString(), now);
			if (key != null) captureGroups.put(key, group);
		}
		group.add(type, now);
		if ("bingo".equals(type) || "personal_best".equals(type) || group.contains("bingo")) recentTriggerGroup = group;
		return group;
	}

	private void refreshStoredGroup(CaptureGroup group)
	{
		for (EvidenceStore.Record record : store.records())
		{
			if (!group.id.equals(submissionGroupId(record)) || record.metadata == null) continue;
			record.metadata.context.put("submissionTypes", group.types());
			try { store.writeRecord(record); }
			catch (Exception e) { log.warn("Could not update submission group {}", group.id, e); }
		}
	}

	private static String encounterKey(AnchorModels.Source source, java.util.Map<String, Object> details)
	{
		String value = source == null ? null : source.name;
		if ((value == null || value.isBlank()) && details != null)
		{
			Object record = details.get("record");
			if (record instanceof AnchorModels.PbRecord) value = ((AnchorModels.PbRecord) record).activity;
			else if (record instanceof java.util.Map) value = String.valueOf(((java.util.Map<?, ?>) record).get("activity"));
		}
		if (value == null || value.isBlank() || "null".equals(value)) return null;
		return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
	}

	public static String submissionGroupId(EvidenceStore.Record record)
	{
		if (record == null || record.metadata == null || record.metadata.context == null) return null;
		Object value = record.metadata.context.get("submissionGroupId");
		return value == null ? null : String.valueOf(value);
	}

	private static final class CaptureGroup
	{
		private final String id;
		private final Set<String> eventTypes = new LinkedHashSet<>();
		private long lastSeen;
		private CaptureGroup(String id, long lastSeen) { this.id = id; this.lastSeen = lastSeen; }
		private synchronized void add(String type, long seen) { eventTypes.add(type); lastSeen = seen; }
		private synchronized boolean contains(String type) { return eventTypes.contains(type); }
		private synchronized List<String> types() { return new ArrayList<>(eventTypes); }
	}

	private static String eventId(EvidenceStore.Record record) { return record == null || record.metadata == null ? "unknown" : record.metadata.eventId; }
	private static String eventType(EvidenceStore.Record record) { return record == null || record.metadata == null ? "unknown" : record.metadata.eventType; }
	private static String validationSummary(List<AnchorModels.ValidationMessage> messages)
	{
		if (messages == null || messages.isEmpty()) return null;
		return messages.stream().filter(java.util.Objects::nonNull)
			.map(message -> (message.field == null || message.field.isBlank() ? "" : message.field + ": ")
				+ (message.message == null ? "Validation failed" : message.message))
			.collect(java.util.stream.Collectors.joining(" "));
	}
	private static AnchorModels.EventStatus parseStatus(String status)
	{
		if (status == null) return AnchorModels.EventStatus.DRAFT;
		try { return AnchorModels.EventStatus.valueOf(status.toUpperCase(java.util.Locale.ROOT)); }
		catch (IllegalArgumentException e) { return AnchorModels.EventStatus.DRAFT; }
	}
}
