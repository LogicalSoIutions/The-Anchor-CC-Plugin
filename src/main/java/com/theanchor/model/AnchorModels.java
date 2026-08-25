package com.theanchor.model;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AnchorModels
{
	private AnchorModels() {}

	public static final class Profile
	{
		public Member member;
		public Standings standings;
		public String updatedAt;
	}

	public static final class Member
	{
		public String name;
		public String discordId;
		public String rosterRank;
		public String status;
	}

	public static final class Standings
	{
		public Integer pvmRank;
		public Integer clanRank;
		public long pvmPoints;
		public long clanPoints;
		public String currentRank;
		public NextRank nextRank;
		public CompetitionRank botwRank;
		public CompetitionRank sotwRank;
	}

	public static final class CompetitionRank
	{
		public Integer rank;
		public long gained;
		public String unit;
		public String displayValue;
		public String metric;
		public String metricLabel;
	}

	public static final class NextRank
	{
		public String name;
		public long pvmPointsRemaining;
		public long clanPointsRemaining;
	}

	public static final class Competitions
	{
		public CompetitionPair sotw;
		public CompetitionPair botw;
	}

	public static final class CompetitionPanels
	{
		public CompetitionPanel botw;
		public CompetitionPanel sotw;
		public String updatedAt;
	}

	public static final class CompetitionPanel
	{
		public long competitionId;
		public String kind;
		public String title;
		public String metric;
		public String metricLabel;
		public String startsAt;
		public String endsAt;
		public int participantCount;
		public String unit;
		public String status;
		public List<PanelLeader> leaders = new ArrayList<>();
	}

	public static final class PanelLeader
	{
		public int rank;
		public String username;
		public String displayName;
		public long gained;
		public String unit;
		public String displayValue;
	}

	public static final class CompetitionPair
	{
		public Competition current;
		public Competition upcoming;
		public Competition previous;
	}

	public static final class Competition
	{
		public long id;
		public String title;
		public String metric;
		@SerializedName(value = "bossName", alternate = {"boss_name"})
		public String bossName;
		@SerializedName(value = "skillName", alternate = {"skill_name"})
		public String skillName;
		public String startsAt;
		public String endsAt;
		public int participantCount;
		public String status;
		public List<TopHistory> topHistory = new ArrayList<>();
	}

	public static final class TopHistory
	{
		public CompetitionPlayer player;
		public List<HistorySample> history = new ArrayList<>();
	}

	public static final class CompetitionPlayer
	{
		public String username;
		public String displayName;
	}

	public static final class HistorySample
	{
		public long value;
		public String date;
	}

	public static final class Rules
	{
		public static final long BASE_MINIMUM_LOOT_VALUE = 1_500_000L;
		public String version = "fallback-1";
		public long minimumLootValue = BASE_MINIMUM_LOOT_VALUE;
	}

	public static final class BingoEvent
	{
		public boolean active;
		public String eventTitle;
		public List<Integer> itemIds = new ArrayList<>();
		public List<Integer> bossIds = new ArrayList<>();
		public Map<String, BingoTeam> teamsByDiscordId = new LinkedHashMap<>();
	}
	public static final class BingoTeam
	{
		public String teamId;
		public String teamName;
	}
	public static final class BingoRules
	{
		public int schemaVersion = 1;
		public String rulesVersion;
		public List<Integer> itemIds = new ArrayList<>();
		public List<Integer> bossIds = new ArrayList<>();
		public BingoTriggers triggers = new BingoTriggers();
		public BingoEvidence evidence = new BingoEvidence();
	}
	public static final class BingoTriggers
	{
		public boolean matchingItem;
		public boolean bossCollectionLog;
		public boolean bossPet;
		public boolean ordinaryBossLoot;
	}
	public static final class BingoEvidence
	{
		public String eventType = "bingo";
		public boolean screenshotRequired = true;
		public boolean finalizeSubmission = true;
	}

	public enum EventStatus { PENDING, UPLOADING, DRAFT, SUBMITTED, APPROVED, REJECTED, FAILED }

	public static final class EventEnvelope
	{
		public int schemaVersion = 1;
		public String eventId;
		public String eventType;
		public String capturedAt;
		public PlayerIdentity player;
		public Source source;
		public List<Item> items = new ArrayList<>();
		public Party party;
		public Map<String, Object> details = new LinkedHashMap<>();
		public Map<String, Object> context = new LinkedHashMap<>();
		public String rulesVersion;
		public String pluginVersion;
	}

	public static final class PlayerIdentity { public String name; public String accountHash; }
	public static final class Source { public String type; public Integer id; public String name; }
	public static final class Item
	{
		public int itemId;
		public String name;
		public int quantity;
		public long unitGeValue;
		public long totalGeValue;
		public boolean tradeable;
		public boolean stackable;
	}
	public static final class Party
	{
		public int detectedPartySize = 1;
		public int submittedPartySize = 1;
		public int detectedClanMemberCount;
		public int submittedClanMemberCount;
		public int detectedNonClanMemberCount;
		public int submittedNonClanMemberCount;
		/** Same-clan RSNs involved in a multi-player encounter; guest RSNs are never included. */
		public List<String> clanMemberNames = new ArrayList<>();
		public List<PartyMember> members = new ArrayList<>();
		public String method = "unknown";
		public String confidence = "low";
	}
	public static final class PartyMember
	{
		public String name;
		public Boolean clanMember;
	}

	public static final class EventResponse
	{
		public String eventId;
		public String submissionId;
		public String status;
		public List<ValidationMessage> validationMessages;
	}
	public static final class ValidationMessage
	{
		public String code;
		public String field;
		public String message;
		public String severity;
	}
	public static final class SubmissionSummary
	{
		public String eventId;
		public String submissionId;
		public String status;
	}

	public static final class PbRecord
	{
		public String activity;
		public String variant;
		public Integer teamSize;
		public String recordType = "overall";
		public Long durationMillis;
		public Long count;
		public String value;
	}

	public static final class PbBulkRequest
	{
		public int schemaVersion = 1;
		public String syncId;
		public PlayerIdentity player;
		public String capturedAt;
		public List<PbRecord> records = new ArrayList<>();
	}

	public static final class PlayerProgressRequest
	{
		public int schemaVersion = 1;
		public String syncId;
		public PlayerIdentity player;
		public String capturedAt;
		public CombatAchievementProgress combatAchievements;
		public SkillProgress skills;
	}

	public static final class CombatAchievementProgress
	{
		public int completedTasks;
		public int totalTasks;
		public int totalPoints;
		public int totalPossiblePoints;
		public String currentTier;
		public Map<String, TierProgress> tierProgress = new LinkedHashMap<>();
		public List<CombatTask> tasks = new ArrayList<>();
	}

	public static final class CombatTask
	{
		public int id;
		public String name;
		public String tier;
		public int points;
		public boolean completed;
	}

	public static final class TierProgress
	{
		public int obtained;
		public int total;
	}

	public static final class SkillProgress
	{
		public int totalLevel;
		public long totalExperience;
		public List<SkillSnapshot> skills = new ArrayList<>();
	}

	public static final class SkillSnapshot
	{
		public String name;
		public int level;
		public int experience;
	}

	public static final class CollectionLogRequest
	{
		public int schemaVersion = 2;
		public String syncId;
		public PlayerIdentity player;
		public String capturedAt;
		public int obtainedCount;
		public int totalCount;
		public String source = "collection_interface_items";
		public Map<String, CollectionCategoryProgress> categories = new LinkedHashMap<>();
		/** OSRS item ID string to collection-log quantity, captured directly from client script 4100. */
		public Map<String, Integer> items = new LinkedHashMap<>();
		/** Supplemental display names keyed by the same item ID strings as {@link #items}. */
		public Map<String, String> itemNames = new LinkedHashMap<>();
	}

	public static final class CollectionCategoryProgress
	{
		public int obtained;
		public int total;
	}
}
