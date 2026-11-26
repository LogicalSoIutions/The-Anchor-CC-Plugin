package com.theanchor.evidence;

import com.theanchor.AnchorConfig;
import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import com.theanchor.model.CharacterIdentity;
import com.theanchor.service.BossRegistry;
import com.theanchor.service.PartyTracker;
import com.theanchor.service.RulesService;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class EventPipeline
{
	private static final Logger log = LoggerFactory.getLogger(EventPipeline.class);
	private static final long RAID_GROUP_SETTLE_MILLIS = 1_500L;
	private final EventDeduplicator deduplicator = new EventDeduplicator(8_000L);
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private final Map<String, CaptureGroup> captureGroups = new java.util.HashMap<>();
	private final Set<String> raidGroupsBeingSubmitted = ConcurrentHashMap.newKeySet();
	private CaptureGroup recentTriggerGroup;
	private volatile String activeAccount;
	private final Set<String> uploadsInFlight = ConcurrentHashMap.newKeySet();
	@Inject private Client client;
	@Inject private AnchorConfig config;
	@Inject private ScreenshotService screenshots;
	@Inject private EvidenceStore store;
	@Inject private AnchorApiClient api;
	@Inject private RulesService rules;
	@Inject private com.theanchor.pb.PvmDiaryContractService diaryContract;
	@Inject private PartyTracker parties;
	@Inject private ScheduledExecutorService executor;

	public void addListener(Runnable listener) { listeners.add(listener); }
	public void removeListener(Runnable listener) { listeners.remove(listener); }

	public String activeAccount() { return activeAccount; }

	public void setActiveAccount(String account)
	{
		if (java.util.Objects.equals(activeAccount, account)) return;
		activeAccount = account;
		notifyListeners();
	}

	public void capture(String type, String dedupeKey, AnchorModels.Source source, List<AnchorModels.Item> items,
		java.util.Map<String, Object> details, boolean includeParty)
	{
		capture(type, dedupeKey, source, items, details, includeParty, null, true, true, null);
	}

	/** Captures an event with the party snapshot taken at the observed completion. */
	public void capture(String type, String dedupeKey, AnchorModels.Source source, List<AnchorModels.Item> items,
		java.util.Map<String, Object> details, AnchorModels.Party party)
	{
		capture(type, dedupeKey, source, items, details, party != null, null, true, true, party);
	}

	public void capture(String type, String dedupeKey, AnchorModels.Source source, List<AnchorModels.Item> items,
		java.util.Map<String, Object> details, boolean includeParty, String rulesVersionOverride,
		boolean screenshotRequired, boolean finalizeSubmission)
	{
		capture(type, dedupeKey, source, items, details, includeParty, rulesVersionOverride,
			screenshotRequired, finalizeSubmission, null);
	}

	private void capture(String type, String dedupeKey, AnchorModels.Source source, List<AnchorModels.Item> items,
		java.util.Map<String, Object> details, boolean includeParty, String rulesVersionOverride,
		boolean screenshotRequired, boolean finalizeSubmission, AnchorModels.Party partyOverride)
	{
		if ("diary".equals(type) && !diaryContract.isEligible(details)) return;
		AnchorModels.PlayerIdentity player = CharacterIdentity.capture(client);
		long now = System.currentTimeMillis();
		if (player == null || !player.accountHash.equals(activeAccount)
			|| !deduplicator.accept(player.accountHash + '|' + type + '|' + dedupeKey, now)) return;
		CaptureGroup group = groupFor(player.accountHash, type, source, details, now);
		boolean coordinatedRaidEvidence = group.coordinatesRaidEvidence(type);
		group.captureStarted(now);
		AnchorModels.EventEnvelope envelope = new AnchorModels.EventEnvelope();
		envelope.eventId = UUID.randomUUID().toString(); envelope.eventType = type; envelope.capturedAt = Instant.now().toString();
		envelope.player = player;
		envelope.source = source; if (items != null) envelope.items.addAll(items); if (details != null) envelope.details.putAll(details);
		String partySource = source == null ? null : source.name;
		if (partySource == null && details != null && details.get("record") instanceof AnchorModels.PbRecord)
			partySource = ((AnchorModels.PbRecord) details.get("record")).activity;
		boolean individualAward = isIndividualAward(type, partySource, details)
			|| ("loot".equals(type) && source != null && "creation".equals(source.type));
		envelope.party = partyOverride != null ? partyOverride
			: includeParty ? parties.snapshot(partySource, individualAward) : null;
		// Collection-log notifications normally have no party metadata. Keep a
		// provisional raid roster for a clog-only raid; prepareRaidGroup removes it
		// when normal raid loot supplies the authoritative roster.
		if (envelope.party == null && "collection_log".equals(type) && isRaidSource(partySource))
			envelope.party = parties.snapshot(partySource, false);
		if (individualAward && envelope.party == null)
			envelope.party = parties.snapshot(partySource, true);
		envelope.rulesVersion = rulesVersionOverride == null || rulesVersionOverride.isBlank()
			? rules.current().version : rulesVersionOverride;
		envelope.pluginVersion = AnchorApiClient.PLUGIN_VERSION;
		envelope.context.put("world", client.getWorld()); envelope.context.put("gameCycle", client.getGameCycle());
		envelope.context.put("submissionGroupId", group.id);
		envelope.context.put("submissionTypes", group.types());
		envelope.context.put("finalizeSubmission", finalizeSubmission);
		if (!screenshotRequired)
		{
			executor.execute(() ->
			{
				try
				{
					if ("diary".equals(type) && !diaryContract.isEligible(envelope.details))
					{
						group.captureFinished();
						return;
					}
					refreshStoredGroup(group);
					envelope.context.put("submissionTypes", group.types());
					EvidenceStore.Record record = store.saveMetadata(envelope); notifyListeners();
					group.captureFinished();
					if (coordinatedRaidEvidence) queueRaidGroup(group);
					else upload(record, false);
				}
				catch (Exception e)
				{
					group.captureFinished();
					log.error("Could not save evidence metadata for event {} ({})", envelope.eventId, envelope.eventType, e);
				}
			});
			return;
		}
		screenshots.captureNextFrame(player, image ->
		{
			if (image == null)
			{
				group.captureFinished();
				log.error("Evidence capture returned no screenshot for event {} ({})", envelope.eventId, envelope.eventType);
				return;
			}
			try
			{
				if ("diary".equals(type) && !diaryContract.isEligible(envelope.details))
				{
					group.captureFinished();
					return;
				}
				refreshStoredGroup(group);
				envelope.context.put("submissionTypes", group.types());
				EvidenceStore.Record record = store.save(envelope, image); notifyListeners();
				group.captureFinished();
				if (coordinatedRaidEvidence) queueRaidGroup(group);
				else upload(record, false);
			}
			catch (Exception e)
			{
				group.captureFinished();
				log.error("Could not save evidence for event {} ({})", envelope.eventId, envelope.eventType, e);
			}
		});
	}

	public void retryAll(String account, boolean manual)
	{
		executor.execute(() ->
		{
			for (EvidenceStore.Record record : store.records(account))
				if (canUpload(record, manual)) upload(record, manual);
		});
	}

	private static boolean canUpload(EvidenceStore.Record record, boolean manual)
	{
		return record.status == AnchorModels.EventStatus.PENDING || record.status == AnchorModels.EventStatus.FAILED
			|| (record.status == AnchorModels.EventStatus.UPLOADING && record.submissionId == null)
			|| (manual && record.status == AnchorModels.EventStatus.IDENTITY_REVIEW_REQUIRED);
	}

	public List<EvidenceStore.Record> groupRecords(EvidenceStore.Record record)
	{
		String groupId = submissionGroupId(record);
		if (groupId == null) return record == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(record);
		return groupRecords(groupId, record.metadata.player.accountHash);
	}

	public synchronized void discardLocal(EvidenceStore.Record record)
	{
		if (EvidenceStore.belongsTo(record, activeAccount) && !uploadsInFlight.contains(eventId(record)))
			store.deleteLocal(eventId(record));
	}

	public void retryGroup(EvidenceStore.Record record)
	{
		for (EvidenceStore.Record member : groupRecords(record))
			if (canUpload(member, true)) upload(member, true);
	}

	public void updateAndSubmitGroup(EvidenceStore.Record record, int partySize, int clanMembers, int nonClanMembers,
		List<AnchorModels.PartyMember> partyMembers, String notes)
	{
		for (EvidenceStore.Record member : groupRecords(record))
			if (member.status == AnchorModels.EventStatus.DRAFT)
				updateAndSubmit(member, partySize, clanMembers, nonClanMembers, partyMembers, notes);
	}

	public synchronized void upload(EvidenceStore.Record candidate, boolean manual)
	{
		if (!EvidenceStore.belongsTo(candidate, activeAccount)) return;
		EvidenceStore.Record record = store.record(candidate.metadata.eventId);
		if (!EvidenceStore.belongsTo(record, activeAccount) || !canUpload(record, manual)) return;
		String id = eventId(record);
		if (uploadsInFlight.contains(id)) return;
		String code = config.authenticationCode();
		if (code == null || code.isBlank()) return;

		boolean held = record.status == AnchorModels.EventStatus.IDENTITY_REVIEW_REQUIRED;
		if (record.status == AnchorModels.EventStatus.PENDING && !diaryEligible(record)) return;
		Filepath screenshot;
		try { screenshot = store.screenshot(record); }
		catch (java.io.IOException | IllegalArgumentException e)
		{
			if (!held)
			{
				record.status = AnchorModels.EventStatus.FAILED;
				record.error = "Invalid evidence reference";
				persist(record);
			}
			return;
		}
		uploadsInFlight.add(id);
		if (!held)
		{
			record.status = AnchorModels.EventStatus.UPLOADING;
			record.error = null;
			record.updatedAt = Instant.now().toString();
			persist(record);
		}
		api.uploadEventJson(store.uploadMetadata(record), screenshot, record.format, result -> finishUpload(record, manual, held, result));
	}

	private synchronized void finishUpload(EvidenceStore.Record record, boolean manual, boolean held,
		AnchorApiClient.ApiResult<AnchorModels.EventResponse> result)
	{
		AnchorModels.EventResponse value = result.value;
		boolean replay = result.idempotentReplay && value != null
			&& (value.eventId == null || record.metadata.eventId.equals(value.eventId));
		if ((result.isSuccessful() || replay) && value != null)
		{
			record.submissionId = value.submissionId;
			record.status = replay && value.status == null && value.submissionId == null
				? AnchorModels.EventStatus.UPLOADED : parseStatus(value.status);
			record.error = validationSummary(value.validationMessages);
			record.retryCount = 0;
		}
		else if (result.needsIdentityReview())
		{
			record.status = AnchorModels.EventStatus.IDENTITY_REVIEW_REQUIRED;
			record.error = result.error;
		}
		else if (!held)
		{
			record.status = AnchorModels.EventStatus.FAILED;
			record.error = result.error != null ? result.error
				: result.idempotentReplay ? "Replay response did not match this event" : "Empty server response";
			record.retryCount++;
			if (!manual && result.isRetryable() && record.retryCount <= 5)
			{
				long delay = Math.min(300, 5L << Math.min(6, record.retryCount));
				executor.schedule(() -> retryFailedUpload(record), delay, TimeUnit.SECONDS);
			}
		}
		record.updatedAt = Instant.now().toString();
		persist(record);
		uploadsInFlight.remove(eventId(record));
		if (EvidenceStore.belongsTo(record, activeAccount))
		{
			if (isCoordinatedRaidRecord(record)) maybeAutoSubmitRaidGroup(record);
			else if (record.status == AnchorModels.EventStatus.DRAFT && shouldAutoSubmit(record)) autoSubmit(record);
		}
	}

	private synchronized void retryFailedUpload(EvidenceStore.Record failed)
	{
		EvidenceStore.Record record = store.record(eventId(failed));
		if (record != null && record.status == AnchorModels.EventStatus.FAILED
			&& record.retryCount == failed.retryCount) upload(record, false);
	}

	private boolean isCoordinatedRaidRecord(EvidenceStore.Record record)
	{
		if (record == null || record.metadata == null) return false;
		String type = record.metadata.eventType;
		return ("loot".equals(type) || "collection_log".equals(type))
			&& isRaidSource(record.metadata.source == null ? null : record.metadata.source.name);
	}

	private void queueRaidGroup(CaptureGroup group)
	{
		if (group == null) return;
		synchronized (group)
		{
			if (group.uploadTask != null) group.uploadTask.cancel(false);
			group.uploadTask = executor.schedule(() -> uploadRaidGroupWhenReady(group),
				RAID_GROUP_SETTLE_MILLIS, TimeUnit.MILLISECONDS);
		}
	}

	private void uploadRaidGroupWhenReady(CaptureGroup group)
	{
		if (group == null) return;
		synchronized (group)
		{
			if (group.pendingCaptures > 0)
			{
				group.uploadTask = executor.schedule(() -> uploadRaidGroupWhenReady(group),
					RAID_GROUP_SETTLE_MILLIS, TimeUnit.MILLISECONDS);
				return;
			}
			if (group.uploadStarted) return;
			group.uploadStarted = true;
		}

		List<EvidenceStore.Record> records = groupRecords(group.id, group.account);
		if (records.isEmpty()) return;
		prepareRaidGroup(records);
		for (EvidenceStore.Record member : records)
			if (member.status == AnchorModels.EventStatus.PENDING || member.status == AnchorModels.EventStatus.FAILED)
				upload(member, false);
	}

	private List<EvidenceStore.Record> groupRecords(String groupId, String account)
	{
		List<EvidenceStore.Record> records = new ArrayList<>();
		if (groupId == null) return records;
		for (EvidenceStore.Record record : store.records(account))
			if (groupId.equals(submissionGroupId(record))) records.add(record);
		return records;
	}

	private synchronized void prepareRaidGroup(List<EvidenceStore.Record> records)
	{
		boolean hasLoot = records.stream().anyMatch(record -> record.metadata != null
			&& "loot".equals(record.metadata.eventType));
		if (!hasLoot) return;
		for (EvidenceStore.Record candidate : records)
		{
			EvidenceStore.Record record = store.record(eventId(candidate));
			if (record == null || record.status != AnchorModels.EventStatus.PENDING || !"collection_log".equals(record.metadata.eventType)
				|| !isRaidSource(record.metadata.source == null ? null : record.metadata.source.name)) continue;
			// The normal raid-loot envelope is authoritative for the roster.
			record.metadata.party = null;
			store.updateParty(record);
			try { store.writeRecord(record); }
			catch (Exception e) { log.error("Could not prepare raid evidence group {}", submissionGroupId(record), e); }
		}
	}

	private void maybeAutoSubmitRaidGroup(EvidenceStore.Record trigger)
	{
		String groupId = trigger == null ? null : submissionGroupId(trigger);
		List<EvidenceStore.Record> records = groupRecords(groupId, activeAccount);
		if (records.isEmpty()) return;
		for (EvidenceStore.Record record : records)
			if (record.status != AnchorModels.EventStatus.DRAFT
				&& record.status != AnchorModels.EventStatus.SUBMITTED
				&& record.status != AnchorModels.EventStatus.APPROVED
				&& record.status != AnchorModels.EventStatus.REJECTED
				&& record.status != AnchorModels.EventStatus.UPLOADED) return;

		AnchorModels.Party party = null;
		for (EvidenceStore.Record record : records)
			if (record.metadata != null && "loot".equals(record.metadata.eventType) && record.metadata.party != null)
			{
				party = record.metadata.party;
				break;
			}
		if (party == null)
			for (EvidenceStore.Record record : records)
				if (record.metadata != null && record.metadata.party != null)
				{
					party = record.metadata.party;
					break;
				}

		List<EvidenceStore.Record> eligible = new ArrayList<>();
		for (EvidenceStore.Record record : records)
			if (record.status == AnchorModels.EventStatus.DRAFT && shouldAutoSubmit(record)) eligible.add(record);
		if (eligible.isEmpty() || groupId == null || !raidGroupsBeingSubmitted.add(groupId)) return;
		AtomicInteger remaining = new AtomicInteger(eligible.size());
		Runnable release = () ->
		{
			if (remaining.decrementAndGet() == 0) raidGroupsBeingSubmitted.remove(groupId);
		};
		for (EvidenceStore.Record record : eligible)
			autoSubmit(record, "diary".equals(record.metadata.eventType) ? record.metadata.party : party, release);
	}

	private boolean shouldAutoSubmit(EvidenceStore.Record record)
	{
		if (!config.autoSubmitEnabled() || record == null || record.metadata == null
			|| record.metadata.context == null) return false;
		String type = record.metadata.eventType;
		if ("diary".equals(type) && (record.metadata.party == null
			|| record.metadata.party.detectedPartySize != 1 || record.metadata.party.submittedPartySize != 1)) return false;
		if ("loot".equals(type))
		{
			// Loot is automatic only when explicitly marked as valuable and the
			// captured encounter was unambiguously solo. Do not let the legacy
			// finalizeSubmission flag auto-submit team loot.
			if (record.metadata.details == null
				|| !Boolean.TRUE.equals(record.metadata.details.get("autoSubmit"))
				|| record.metadata.party == null) return false;
			return record.metadata.party.detectedPartySize == 1
				&& record.metadata.party.submittedPartySize == 1
				&& "high".equals(record.metadata.party.confidence);
		}
		if ("personal_best".equals(type) || "diary".equals(type) || "collection_log".equals(type))
			return record.metadata.details != null
				&& Boolean.TRUE.equals(record.metadata.details.get("autoSubmit"));
		Object finalizeSubmission = record.metadata.context.get("finalizeSubmission");
		return Boolean.TRUE.equals(finalizeSubmission)
			|| "true".equalsIgnoreCase(String.valueOf(finalizeSubmission));
	}

	private static boolean isIndividualAward(String type, String sourceName, Map<String, Object> details)
	{
		// Every pet is awarded to the individual player, regardless of activity.
		if ("pet".equals(type) || ("bingo".equals(type) && value(details, "petName") != null))
			return true;
		if (!("loot".equals(type) || "bingo".equals(type)) || details == null) return false;
		return PartyTracker.isIndividualRaidReward(sourceName, value(details, "itemName"));
	}

	private static String value(Map<String, Object> details, String key)
	{
		Object value = details == null ? null : details.get(key);
		return value == null ? null : String.valueOf(value);
	}

	private void autoSubmit(EvidenceStore.Record record)
	{
		AnchorModels.Party party = record.metadata.party;
		autoSubmit(record, party);
	}

	private void autoSubmit(EvidenceStore.Record record, AnchorModels.Party party)
	{
		autoSubmit(record, party, () -> { });
	}

	private void autoSubmit(EvidenceStore.Record record, AnchorModels.Party party, Runnable completion)
	{
		int partySize = party == null ? 1 : party.submittedPartySize;
		int clanMembers = party == null ? 0 : party.submittedClanMemberCount;
		int nonClanMembers = party == null ? 0 : party.submittedNonClanMemberCount;
		updateAndSubmit(record, partySize, clanMembers, nonClanMembers,
			party == null ? List.of() : party.submittedMembers, "", completion);
	}

	public void updateAndSubmit(EvidenceStore.Record record, int partySize, int clanMembers, int nonClanMembers,
		List<AnchorModels.PartyMember> partyMembers, String notes)
	{
		updateAndSubmit(record, partySize, clanMembers, nonClanMembers, partyMembers, notes, () -> { });
	}

	private void updateAndSubmit(EvidenceStore.Record record, int partySize, int clanMembers, int nonClanMembers,
		List<AnchorModels.PartyMember> partyMembers, String notes, Runnable completion)
	{
		if (!EvidenceStore.belongsTo(record, activeAccount) || !diaryEligible(record))
		{
			completion.run();
			return;
		}
		if ("diary".equals(eventType(record))
			&& ((Number) record.metadata.details.get("teamSize")).intValue() != partySize)
		{
			record.error = "Party size must match the observed diary activity";
			persist(record);
			completion.run();
			return;
		}
		if (record.submissionId == null)
		{
			record.error = "Submission ID is unavailable; retry the upload";
			log.error("Could not submit event {}: submission ID is unavailable", eventId(record));
			persist(record);
			completion.run();
			return;
		}
		record.status = AnchorModels.EventStatus.UPLOADING;
		record.error = null;
		persist(record);
		int size = Math.max(1, partySize);
		int clan = Math.max(0, Math.min(size, clanMembers));
		int nonClan = Math.max(0, Math.min(size - clan, nonClanMembers));
		List<AnchorModels.PartyMember> members = copyPartyMembers(partyMembers);
		if (record.metadata.party != null)
		{
			record.metadata.party.submittedPartySize = size;
			record.metadata.party.submittedClanMemberCount = clan;
			record.metadata.party.submittedNonClanMemberCount = nonClan;
			record.metadata.party.submittedMembers = members;
			store.updateParty(record);
		}
		api.updateSubmission(record.submissionId, size, clan, nonClan, members, notes == null ? "" : notes, patched ->
		{
			if (!patched.isSuccessful())
			{
				record.status = AnchorModels.EventStatus.DRAFT;
				record.error = patched.error;
				log.error("Submission update failed for event {} (submission {}): HTTP {}, error={}",
					eventId(record), record.submissionId, patched.statusCode, patched.error);
				persist(record);
				completion.run();
				return;
			}
			if (!EvidenceStore.belongsTo(record, activeAccount) || !diaryEligible(record))
			{
				if (!EvidenceStore.belongsTo(record, activeAccount))
				{
					record.status = AnchorModels.EventStatus.DRAFT;
					persist(record);
				}
				completion.run();
				return;
			}
			api.submit(record.submissionId, submitted ->
			{
				if (submitted.isSuccessful()) { record.status = AnchorModels.EventStatus.SUBMITTED; record.error = null; }
				else
				{
					record.status = AnchorModels.EventStatus.DRAFT;
					record.error = submitted.error;
					log.error("Submission failed for event {} (submission {}): HTTP {}, error={}",
						eventId(record), record.submissionId, submitted.statusCode, submitted.error);
				}
				persist(record);
				completion.run();
			});
		});
	}

	public void refreshStatuses(String playerName, String account)
	{
		if (account == null || playerName == null || playerName.isBlank()) return;
		api.getSubmissions(playerName, result ->
		{
			if (!result.isSuccessful() || result.value == null)
			{
				log.error("Could not refresh submissions: HTTP {}, error={}", result.statusCode,
					result.error == null ? "empty server response" : result.error);
				return;
			}
			applyStatuses(account, result.value);
		});
	}

	private synchronized void applyStatuses(String account, AnchorModels.SubmissionSummary[] summaries)
	{
		if (!account.equals(activeAccount)) return;
		List<EvidenceStore.Record> records = store.records(account);
		for (AnchorModels.SubmissionSummary summary : summaries)
		{
			for (EvidenceStore.Record record : records)
			{
				if (uploadsInFlight.contains(eventId(record)) || record.status == AnchorModels.EventStatus.IDENTITY_REVIEW_REQUIRED
					|| (record.submissionId == null && record.status != AnchorModels.EventStatus.UPLOADED)) continue;
				if ((summary.eventId != null && summary.eventId.equals(record.metadata.eventId))
					|| (summary.submissionId != null && summary.submissionId.equals(record.submissionId)))
				{
					record.submissionId = summary.submissionId;
					record.status = parseStatus(summary.status);
					persist(record);
				}
			}
		}
	}

	private boolean diaryEligible(EvidenceStore.Record record)
	{
		if (!"diary".equals(eventType(record)) || diaryContract.isEligible(record.metadata.details)) return true;
		record.status = record.submissionId == null ? AnchorModels.EventStatus.FAILED : AnchorModels.EventStatus.DRAFT;
		record.error = "Diary result does not meet the current requirements, or the diary contract is unavailable";
		persist(record);
		return false;
	}

	private void persist(EvidenceStore.Record record)
	{
		try { store.writeRecord(record); }
		catch (Exception e) { log.error("Could not persist event {} ({})", eventId(record), eventType(record), e); }
		notifyListeners();
	}
	private void notifyListeners() { for (Runnable listener : listeners) listener.run(); }

	private CaptureGroup groupFor(String account, String type, AnchorModels.Source source,
		java.util.Map<String, Object> details, long now)
	{
		String encounter = encounterKey(source, details);
		String key = encounter == null ? null : account + "|" + encounter;
		CaptureGroup group = key == null ? null : captureGroups.get(key);
		if (group == null || now - group.lastSeen > 8_000L || group.contains(type))
		{
			CaptureGroup recent = recentTriggerGroup;
			boolean triggerPair = recent != null && account.equals(recent.account) && now - recent.lastSeen <= 4_000L
				&& (("bingo".equals(type) && recent.contains("personal_best"))
					|| ("personal_best".equals(type) && recent.contains("bingo")));
			group = triggerPair ? recent : new CaptureGroup(UUID.randomUUID().toString(), account, now,
				isRaidSource(source == null ? null : source.name));
			if (key != null) captureGroups.put(key, group);
		}
		group.add(type, now);
		if ("bingo".equals(type) || "personal_best".equals(type) || group.contains("bingo")) recentTriggerGroup = group;
		return group;
	}

	private synchronized void refreshStoredGroup(CaptureGroup group)
	{
		for (EvidenceStore.Record record : store.records(group.account))
		{
			if (!group.id.equals(submissionGroupId(record)) || record.status != AnchorModels.EventStatus.PENDING) continue;
			store.setSubmissionTypes(record, group.types());
			try { store.writeRecord(record); }
			catch (Exception e) { log.error("Could not update submission group {}", group.id, e); }
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
		String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
		// Loot and collection-log sources can use different raid variants (for
		// example, normal versus hard mode). They still belong to one evidence
		// group for this completion.
		if (isRaidSource(value))
		{
			if (normalized.startsWith("chambersofxeric")) return "chambersofxeric";
			if (normalized.startsWith("theatreofblood")) return "theatreofblood";
			if (normalized.startsWith("tombsofamascut")) return "tombsofamascut";
			if (normalized.equals("thefracturedarchive") || normalized.equals("fracturedarchive")) return "fracturedarchive";
		}
		return normalized;
	}

	private static List<AnchorModels.PartyMember> copyPartyMembers(List<AnchorModels.PartyMember> source)
	{
		List<AnchorModels.PartyMember> copy = new ArrayList<>();
		if (source == null) return copy;
		for (AnchorModels.PartyMember member : source)
		{
			if (member == null) continue;
			AnchorModels.PartyMember value = new AnchorModels.PartyMember();
			value.name = member.name == null ? "" : member.name.trim();
			value.clanMember = member.clanMember;
			copy.add(value);
		}
		return copy;
	}

	private static boolean isRaidSource(String source)
	{
		return source != null && BossRegistry.isRaid(source);
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
		private final String account;
		private final boolean raid;
		private final Set<String> eventTypes = new LinkedHashSet<>();
		private long lastSeen;
		private int pendingCaptures;
		private boolean uploadStarted;
		private ScheduledFuture<?> uploadTask;
		private CaptureGroup(String id, String account, long lastSeen, boolean raid)
		{
			this.id = id;
			this.account = account;
			this.lastSeen = lastSeen;
			this.raid = raid;
		}
		private synchronized void add(String type, long seen) { eventTypes.add(type); lastSeen = seen; }
		private synchronized boolean contains(String type) { return eventTypes.contains(type); }
		private synchronized List<String> types() { return new ArrayList<>(eventTypes); }
		private synchronized boolean coordinatesRaidEvidence(String type)
		{
			return raid && ("loot".equals(type) || "collection_log".equals(type));
		}
		private synchronized void captureStarted(long started) { pendingCaptures++; lastSeen = started; }
		private synchronized void captureFinished() { pendingCaptures = Math.max(0, pendingCaptures - 1); }
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
