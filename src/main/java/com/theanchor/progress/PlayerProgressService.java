/*
 * Combat-achievement enumeration is adapted from the provided Llama Club plugin,
 * which credits reval-cc. Both are distributed under the BSD 2-Clause License.
 * See LICENSES/llama-LICENSE.txt and the bundled transitive notices.
 */
package com.theanchor.progress;

import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;

@Slf4j
@Singleton
public class PlayerProgressService
{
	private static final Map<Integer, String> TIER_ENUMS = new LinkedHashMap<>();
	private static final int[] COMPLETION_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0,
		VarPlayerID.CA_TASK_COMPLETED_1,
		VarPlayerID.CA_TASK_COMPLETED_2,
		VarPlayerID.CA_TASK_COMPLETED_3,
		VarPlayerID.CA_TASK_COMPLETED_4,
		VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6,
		VarPlayerID.CA_TASK_COMPLETED_7,
		VarPlayerID.CA_TASK_COMPLETED_8,
		VarPlayerID.CA_TASK_COMPLETED_9,
		VarPlayerID.CA_TASK_COMPLETED_10,
		VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12,
		VarPlayerID.CA_TASK_COMPLETED_13,
		VarPlayerID.CA_TASK_COMPLETED_14,
		VarPlayerID.CA_TASK_COMPLETED_15,
		VarPlayerID.CA_TASK_COMPLETED_16,
		VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18,
		VarPlayerID.CA_TASK_COMPLETED_19,
		VarPlayerID.CA_TASK_COMPLETED_20
	};
	private static final int FIELD_TASK_ID = 1306;
	private static final int FIELD_NAME = 1308;
	private static final Map<String, Integer> CA_COMPLETED_VARBITS = new LinkedHashMap<>();
	private static final String[] DIARY_AREAS = {
		"Karamja", "Ardougne", "Falador", "Fremennik", "Kandarin", "Desert",
		"Lumbridge & Draynor", "Morytania", "Varrock", "Wilderness",
		"Western Provinces", "Kourend & Kebos"
	};
	private static final int[][] DIARY_TOTALS = {
		{10, 19, 10, 5}, {10, 12, 12, 8}, {11, 14, 11, 6}, {10, 9, 9, 6},
		{11, 14, 11, 7}, {11, 12, 10, 6}, {12, 12, 10, 6}, {11, 11, 10, 6},
		{14, 13, 10, 5}, {12, 11, 10, 7}, {11, 13, 13, 7}, {12, 13, 10, 8}
	};
	private static final String[] DIARY_TIERS = {"Easy", "Medium", "Hard", "Elite"};
	private static final int[][] DIARY_COMPLETION_VARBITS = {
		{net.runelite.api.Varbits.DIARY_KARAMJA_EASY, net.runelite.api.Varbits.DIARY_KARAMJA_MEDIUM,
			net.runelite.api.Varbits.DIARY_KARAMJA_HARD, net.runelite.api.Varbits.DIARY_KARAMJA_ELITE},
		{net.runelite.api.Varbits.DIARY_ARDOUGNE_EASY, net.runelite.api.Varbits.DIARY_ARDOUGNE_MEDIUM,
			net.runelite.api.Varbits.DIARY_ARDOUGNE_HARD, net.runelite.api.Varbits.DIARY_ARDOUGNE_ELITE},
		{net.runelite.api.Varbits.DIARY_FALADOR_EASY, net.runelite.api.Varbits.DIARY_FALADOR_MEDIUM,
			net.runelite.api.Varbits.DIARY_FALADOR_HARD, net.runelite.api.Varbits.DIARY_FALADOR_ELITE},
		{net.runelite.api.Varbits.DIARY_FREMENNIK_EASY, net.runelite.api.Varbits.DIARY_FREMENNIK_MEDIUM,
			net.runelite.api.Varbits.DIARY_FREMENNIK_HARD, net.runelite.api.Varbits.DIARY_FREMENNIK_ELITE},
		{net.runelite.api.Varbits.DIARY_KANDARIN_EASY, net.runelite.api.Varbits.DIARY_KANDARIN_MEDIUM,
			net.runelite.api.Varbits.DIARY_KANDARIN_HARD, net.runelite.api.Varbits.DIARY_KANDARIN_ELITE},
		{net.runelite.api.Varbits.DIARY_DESERT_EASY, net.runelite.api.Varbits.DIARY_DESERT_MEDIUM,
			net.runelite.api.Varbits.DIARY_DESERT_HARD, net.runelite.api.Varbits.DIARY_DESERT_ELITE},
		{net.runelite.api.Varbits.DIARY_LUMBRIDGE_EASY, net.runelite.api.Varbits.DIARY_LUMBRIDGE_MEDIUM,
			net.runelite.api.Varbits.DIARY_LUMBRIDGE_HARD, net.runelite.api.Varbits.DIARY_LUMBRIDGE_ELITE},
		{net.runelite.api.Varbits.DIARY_MORYTANIA_EASY, net.runelite.api.Varbits.DIARY_MORYTANIA_MEDIUM,
			net.runelite.api.Varbits.DIARY_MORYTANIA_HARD, net.runelite.api.Varbits.DIARY_MORYTANIA_ELITE},
		{net.runelite.api.Varbits.DIARY_VARROCK_EASY, net.runelite.api.Varbits.DIARY_VARROCK_MEDIUM,
			net.runelite.api.Varbits.DIARY_VARROCK_HARD, net.runelite.api.Varbits.DIARY_VARROCK_ELITE},
		{net.runelite.api.Varbits.DIARY_WILDERNESS_EASY, net.runelite.api.Varbits.DIARY_WILDERNESS_MEDIUM,
			net.runelite.api.Varbits.DIARY_WILDERNESS_HARD, net.runelite.api.Varbits.DIARY_WILDERNESS_ELITE},
		{net.runelite.api.Varbits.DIARY_WESTERN_EASY, net.runelite.api.Varbits.DIARY_WESTERN_MEDIUM,
			net.runelite.api.Varbits.DIARY_WESTERN_HARD, net.runelite.api.Varbits.DIARY_WESTERN_ELITE},
		{net.runelite.api.Varbits.DIARY_KOUREND_EASY, net.runelite.api.Varbits.DIARY_KOUREND_MEDIUM,
			net.runelite.api.Varbits.DIARY_KOUREND_HARD, net.runelite.api.Varbits.DIARY_KOUREND_ELITE}
	};
	private static final int[][] DIARY_COUNT_VARBITS = {
		{net.runelite.api.gameval.VarbitID.KARAMJA_EASY_COUNT, net.runelite.api.gameval.VarbitID.KARAMJA_MED_COUNT,
			net.runelite.api.gameval.VarbitID.KARAMJA_HARD_COUNT, net.runelite.api.gameval.VarbitID.KARAMJA_ELITE_COUNT},
		{net.runelite.api.gameval.VarbitID.ARDOUGNE_EASY_COUNT, net.runelite.api.gameval.VarbitID.ARDOUGNE_MED_COUNT,
			net.runelite.api.gameval.VarbitID.ARDOUGNE_HARD_COUNT, net.runelite.api.gameval.VarbitID.ARDOUGNE_ELITE_COUNT},
		{net.runelite.api.gameval.VarbitID.FALADOR_EASY_COUNT, net.runelite.api.gameval.VarbitID.FALADOR_MED_COUNT,
			net.runelite.api.gameval.VarbitID.FALADOR_HARD_COUNT, net.runelite.api.gameval.VarbitID.FALADOR_ELITE_COUNT},
		{net.runelite.api.gameval.VarbitID.FREMENNIK_EASY_COUNT, net.runelite.api.gameval.VarbitID.FREMENNIK_MED_COUNT,
			net.runelite.api.gameval.VarbitID.FREMENNIK_HARD_COUNT, net.runelite.api.gameval.VarbitID.FREMENNIK_ELITE_COUNT},
		{net.runelite.api.gameval.VarbitID.KANDARIN_EASY_COUNT, net.runelite.api.gameval.VarbitID.KANDARIN_MED_COUNT,
			net.runelite.api.gameval.VarbitID.KANDARIN_HARD_COUNT, net.runelite.api.gameval.VarbitID.KANDARIN_ELITE_COUNT},
		{net.runelite.api.gameval.VarbitID.DESERT_EASY_COUNT, net.runelite.api.gameval.VarbitID.DESERT_MED_COUNT,
			net.runelite.api.gameval.VarbitID.DESERT_HARD_COUNT, net.runelite.api.gameval.VarbitID.DESERT_ELITE_COUNT},
		{net.runelite.api.gameval.VarbitID.LUMBRIDGE_EASY_COUNT, net.runelite.api.gameval.VarbitID.LUMBRIDGE_MED_COUNT,
			net.runelite.api.gameval.VarbitID.LUMBRIDGE_HARD_COUNT, net.runelite.api.gameval.VarbitID.LUMBRIDGE_ELITE_COUNT},
		{net.runelite.api.gameval.VarbitID.MORYTANIA_EASY_COUNT, net.runelite.api.gameval.VarbitID.MORYTANIA_MED_COUNT,
			net.runelite.api.gameval.VarbitID.MORYTANIA_HARD_COUNT, net.runelite.api.gameval.VarbitID.MORYTANIA_ELITE_COUNT},
		{net.runelite.api.gameval.VarbitID.VARROCK_EASY_COUNT, net.runelite.api.gameval.VarbitID.VARROCK_MED_COUNT,
			net.runelite.api.gameval.VarbitID.VARROCK_HARD_COUNT, net.runelite.api.gameval.VarbitID.VARROCK_ELITE_COUNT},
		{net.runelite.api.gameval.VarbitID.WILDERNESS_EASY_COUNT, net.runelite.api.gameval.VarbitID.WILDERNESS_MED_COUNT,
			net.runelite.api.gameval.VarbitID.WILDERNESS_HARD_COUNT, net.runelite.api.gameval.VarbitID.WILDERNESS_ELITE_COUNT},
		{net.runelite.api.gameval.VarbitID.WESTERN_EASY_COUNT, net.runelite.api.gameval.VarbitID.WESTERN_MED_COUNT,
			net.runelite.api.gameval.VarbitID.WESTERN_HARD_COUNT, net.runelite.api.gameval.VarbitID.WESTERN_ELITE_COUNT},
		{net.runelite.api.gameval.VarbitID.KOUREND_EASY_COUNT, net.runelite.api.gameval.VarbitID.KOUREND_MED_COUNT,
			net.runelite.api.gameval.VarbitID.KOUREND_HARD_COUNT, net.runelite.api.gameval.VarbitID.KOUREND_ELITE_COUNT}
	};

	static
	{
		TIER_ENUMS.put(3981, "Easy");
		TIER_ENUMS.put(3982, "Medium");
		TIER_ENUMS.put(3983, "Hard");
		TIER_ENUMS.put(3984, "Elite");
		TIER_ENUMS.put(3985, "Master");
		TIER_ENUMS.put(3986, "Grandmaster");
		CA_COMPLETED_VARBITS.put("Easy", VarbitID.CA_TOTAL_TASKS_COMPLETED_EASY);
		CA_COMPLETED_VARBITS.put("Medium", VarbitID.CA_TOTAL_TASKS_COMPLETED_MEDIUM);
		CA_COMPLETED_VARBITS.put("Hard", VarbitID.CA_TOTAL_TASKS_COMPLETED_HARD);
		CA_COMPLETED_VARBITS.put("Elite", VarbitID.CA_TOTAL_TASKS_COMPLETED_ELITE);
		CA_COMPLETED_VARBITS.put("Master", VarbitID.CA_TOTAL_TASKS_COMPLETED_MASTER);
		CA_COMPLETED_VARBITS.put("Grandmaster", VarbitID.CA_TOTAL_TASKS_COMPLETED_GRANDMASTER);
	}

	private final Client client;
	private final AnchorApiClient api;
	private final ClientThread clientThread;
	private volatile IncrementalSync incrementalSync;

	@Inject
	public PlayerProgressService(Client client, AnchorApiClient api, ClientThread clientThread)
	{
		this.client = client;
		this.api = api;
		this.clientThread = clientThread;
	}

	/** Keeps the lightweight unit-test constructor while production uses ClientThread. */
	public PlayerProgressService(Client client, AnchorApiClient api)
	{
		this(client, api, null);
	}

	public void syncAll()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}

		if (clientThread == null)
		{
			send(collect());
			return;
		}
		if (incrementalSync != null) return;
		incrementalSync = new IncrementalSync();
		clientThread.invokeLater(this::advanceIncrementalSync);
	}

	private void advanceIncrementalSync()
	{
		IncrementalSync sync = incrementalSync;
		if (sync == null) return;
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			incrementalSync = null;
			return;
		}

		try
		{
			switch (sync.stage)
			{
				case 0:
					sync.request.syncId = UUID.randomUUID().toString();
					sync.request.capturedAt = Instant.now().toString();
					sync.request.player = new AnchorModels.PlayerIdentity();
					sync.request.player.name = client.getLocalPlayer().getName();
					sync.request.player.accountHash = Long.toString(client.getAccountHash());
					sync.request.combatAchievements = newCombatAchievementProgress();
					sync.request.quests = new AnchorModels.QuestProgress();
					sync.stage = 1;
					break;
				case 1:
					if (sync.questIndex < Quest.values().length)
					{
						appendQuest(sync.request.quests, Quest.values()[sync.questIndex++]);
						break;
					}
					sync.request.achievementDiaries = new AnchorModels.AchievementDiaryProgress();
					sync.stage = 2;
					break;
				case 2:
					if (sync.diaryArea < DIARY_AREAS.length)
					{
						AnchorModels.AchievementDiaryArea area = collectDiaryArea(sync.diaryArea++);
						sync.request.achievementDiaries.areas.add(area);
						for (AnchorModels.AchievementDiaryTier tier : area.tiers)
						{
							sync.request.achievementDiaries.completedTasks += tier.completedCount;
							sync.request.achievementDiaries.totalTasks += tier.totalCount;
						}
						break;
					}
					sync.stage = 3;
					break;
				case 3:
					if (advanceCombatAchievements(sync)) break;
					sync.stage = 4;
					break;
				case 4:
					sync.request.skills = collectSkills();
					sync.stage = 5;
					break;
				case 5:
					incrementalSync = null;
					send(sync.request);
					return;
				default:
					incrementalSync = null;
					return;
			}
		}
		catch (RuntimeException e)
		{
			incrementalSync = null;
			log.error("Unable to collect Anchor player progress", e);
			return;
		}

		clientThread.invokeLater(this::advanceIncrementalSync);
	}

	/** Processes at most one diary area or one combat-achievement task per client loop. */
	private boolean advanceCombatAchievements(IncrementalSync sync)
	{
		if (sync.tierIndex >= TIER_ENUMS.size())
		{
			finishCombatAchievementProgress(sync.request.combatAchievements);
			return false;
		}

		if (sync.structIds == null)
		{
			Map.Entry<Integer, String> tier = new java.util.ArrayList<>(TIER_ENUMS.entrySet()).get(sync.tierIndex);
			EnumComposition composition = client.getEnum(tier.getKey());
			sync.tierName = tier.getValue();
			sync.structIds = composition == null ? new int[0] : composition.getIntVals();
			if (sync.structIds == null) sync.structIds = new int[0];
		}
		if (sync.structIndex < sync.structIds.length)
		{
			AnchorModels.CombatTask task = loadTask(sync.structIds[sync.structIndex++], sync.tierName);
			if (task != null)
			{
				sync.request.combatAchievements.tasks.add(task);
				sync.request.combatAchievements.totalPossiblePoints += task.points;
				AnchorModels.TierProgress tier = sync.request.combatAchievements.tierProgress.get(task.tier.toLowerCase());
				tier.total++;
				if (task.completed) { sync.request.combatAchievements.completedTasks++; tier.obtained++; }
			}
			return true;
		}

		sync.tierIndex++;
		sync.structIds = null;
		sync.structIndex = 0;
		return true;
	}

	private void send(AnchorModels.PlayerProgressRequest request)
	{
		api.syncPlayerProgress(request, result ->
		{
			if (!result.isSuccessful())
			{
				log.error("Anchor progress sync failed: player={}, syncId={}, error={}",
					request.player.name, request.syncId, result.error);
			}
		});
	}

	private AnchorModels.CombatAchievementProgress newCombatAchievementProgress()
	{
		AnchorModels.CombatAchievementProgress result = new AnchorModels.CombatAchievementProgress();
		for (String tier : TIER_ENUMS.values())
			result.tierProgress.put(tier.toLowerCase(), new AnchorModels.TierProgress());
		return result;
	}

	private void finishCombatAchievementProgress(AnchorModels.CombatAchievementProgress result)
	{
		result.totalTasks = result.tasks.size();
		result.totalPoints = client.getVarbitValue(VarbitID.CA_POINTS);
		result.currentTier = currentTier(result.totalPoints);
		int identifiedCompleted = result.completedTasks;
		int authoritativeCompleted = 0;
		for (String tier : TIER_ENUMS.values())
		{
			int reported = Math.max(0, client.getVarbitValue(CA_COMPLETED_VARBITS.get(tier)));
			AnchorModels.TierProgress progress = result.tierProgress.get(tier.toLowerCase());
			progress.obtained = Math.min(reported, progress.total);
			authoritativeCompleted += progress.obtained;
		}
		result.completedTasks = authoritativeCompleted;
		if (identifiedCompleted != authoritativeCompleted)
		{
			log.error("Combat achievement bitmap coverage mismatch: identified={}, authoritative={}, highestSupportedTaskId={}",
				identifiedCompleted, authoritativeCompleted, COMPLETION_VARPS.length * 32 - 1);
		}
	}

	private AnchorModels.AchievementDiaryArea collectDiaryArea(int areaId)
	{
		AnchorModels.AchievementDiaryArea area = new AnchorModels.AchievementDiaryArea();
		area.id = areaId;
		area.name = DIARY_AREAS[areaId];
		for (int tierIndex = 0; tierIndex < DIARY_TIERS.length; tierIndex++)
		{
			AnchorModels.AchievementDiaryTier tier = new AnchorModels.AchievementDiaryTier();
			tier.name = DIARY_TIERS[tierIndex];
			tier.totalCount = DIARY_TOTALS[areaId][tierIndex];
			int completionValue = client.getVarbitValue(DIARY_COMPLETION_VARBITS[areaId][tierIndex]);
			int completed = client.getVarbitValue(DIARY_COUNT_VARBITS[areaId][tierIndex]);
			if (isDiaryComplete(areaId, tierIndex, completionValue)) completed = tier.totalCount;
			tier.completedCount = completed;
			tier.completedCount = Math.max(0, Math.min(tier.completedCount, tier.totalCount));
			tier.completed = tier.completedCount >= tier.totalCount;
			area.tiers.add(tier);
		}
		return area;
	}

	private static boolean isDiaryComplete(int areaId, int tierIndex, int value)
	{
		// Karamja's easy/medium/hard completion varbits use 2 as the completed value.
		return areaId == 0 && tierIndex < 3 ? value > 1 : value > 0;
	}

	private void appendQuest(AnchorModels.QuestProgress result, Quest quest)
	{
		String name = quest.getName();
		if (name == null || name.isBlank())
		{
			log.error("Skipping quest with missing name: enum={}, id={}", quest.name(), quest.getId());
			return;
		}
		AnchorModels.QuestSnapshot snapshot = new AnchorModels.QuestSnapshot();
		snapshot.id = quest.getId();
		snapshot.name = name.trim();
		QuestState state = quest.getState(client);
		snapshot.state = state == null ? QuestState.NOT_STARTED.name() : state.name();
		snapshot.completed = state == QuestState.FINISHED;
		result.quests.add(snapshot);
		result.totalCount++;
		if (snapshot.completed) result.completedCount++;
	}

	private final class IncrementalSync
	{
		private final AnchorModels.PlayerProgressRequest request = new AnchorModels.PlayerProgressRequest();
		private int stage;
		private int diaryArea;
		private int questIndex;
		private int tierIndex;
		private String tierName;
		private int[] structIds;
		private int structIndex;
	}

	AnchorModels.PlayerProgressRequest collect()
	{
		AnchorModels.PlayerProgressRequest request = new AnchorModels.PlayerProgressRequest();
		request.syncId = UUID.randomUUID().toString();
		request.capturedAt = Instant.now().toString();
		request.player = new AnchorModels.PlayerIdentity();
		request.player.name = client.getLocalPlayer().getName();
		request.player.accountHash = Long.toString(client.getAccountHash());
		request.combatAchievements = collectCombatAchievements();
		request.quests = collectQuests();
		request.achievementDiaries = collectAchievementDiaries();
		request.skills = collectSkills();
		return request;
	}

	private AnchorModels.QuestProgress collectQuests()
	{
		AnchorModels.QuestProgress result = new AnchorModels.QuestProgress();
		for (Quest quest : Quest.values())
		{
			String name = quest.getName();
			if (name == null || name.isBlank())
			{
				log.error("Skipping quest with missing name: enum={}, id={}", quest.name(), quest.getId());
				continue;
			}
			AnchorModels.QuestSnapshot snapshot = new AnchorModels.QuestSnapshot();
			snapshot.id = quest.getId();
			snapshot.name = name.trim();
			QuestState state = quest.getState(client);
			// A client that has not populated quest varbits yet can return null.
			snapshot.state = state == null ? QuestState.NOT_STARTED.name() : state.name();
			snapshot.completed = state == QuestState.FINISHED;
			result.quests.add(snapshot);
			result.totalCount++;
			if (snapshot.completed) result.completedCount++;
		}
		return result;
	}

	private AnchorModels.AchievementDiaryProgress collectAchievementDiaries()
	{
		AnchorModels.AchievementDiaryProgress result = new AnchorModels.AchievementDiaryProgress();
		for (int areaId = 0; areaId < DIARY_AREAS.length; areaId++)
		{
			AnchorModels.AchievementDiaryArea area = collectDiaryArea(areaId);
			for (AnchorModels.AchievementDiaryTier tier : area.tiers)
			{
				result.completedTasks += tier.completedCount;
				result.totalTasks += tier.totalCount;
			}
			result.areas.add(area);
		}
		return result;
	}

	private AnchorModels.CombatAchievementProgress collectCombatAchievements()
	{
		AnchorModels.CombatAchievementProgress result = new AnchorModels.CombatAchievementProgress();
		for (String tier : TIER_ENUMS.values())
		{
			result.tierProgress.put(tier.toLowerCase(), new AnchorModels.TierProgress());
		}

		for (Map.Entry<Integer, String> tierEntry : TIER_ENUMS.entrySet())
		{
			EnumComposition tierEnum = client.getEnum(tierEntry.getKey());
			if (tierEnum == null || tierEnum.getIntVals() == null)
			{
				continue;
			}
			for (int structId : tierEnum.getIntVals())
			{
				AnchorModels.CombatTask task = loadTask(structId, tierEntry.getValue());
				if (task == null)
				{
					continue;
				}
				result.tasks.add(task);
				result.totalPossiblePoints += task.points;
				AnchorModels.TierProgress tier = result.tierProgress.get(task.tier.toLowerCase());
				tier.total++;
				if (task.completed)
				{
					result.completedTasks++;
					tier.obtained++;
				}
			}
		}

		result.totalTasks = result.tasks.size();
		result.totalPoints = client.getVarbitValue(VarbitID.CA_POINTS);
		result.currentTier = currentTier(result.totalPoints);
		int bitmaskCompleted = result.completedTasks;
		int authoritativeCompleted = 0;
		for (String tier : TIER_ENUMS.values())
		{
			int reported = Math.max(0, client.getVarbitValue(CA_COMPLETED_VARBITS.get(tier)));
			AnchorModels.TierProgress progress = result.tierProgress.get(tier.toLowerCase());
			progress.obtained = Math.min(reported, progress.total);
			authoritativeCompleted += progress.obtained;
		}
		result.completedTasks = authoritativeCompleted;
		if (bitmaskCompleted != authoritativeCompleted)
		{
			log.error("Combat achievement bitmap coverage mismatch: identified={}, authoritative={}, highestSupportedTaskId={}",
				bitmaskCompleted, authoritativeCompleted, COMPLETION_VARPS.length * 32 - 1);
		}
		return result;
	}

	private AnchorModels.CombatTask loadTask(int structId, String tier)
	{
		StructComposition struct = client.getStructComposition(structId);
		if (struct == null) return null;
		String name = struct.getStringValue(FIELD_NAME);
		if (name == null || name.isBlank()) return null;
		AnchorModels.CombatTask task = new AnchorModels.CombatTask();
		task.id = struct.getIntValue(FIELD_TASK_ID);
		task.name = name.trim();
		task.tier = tier;
		task.points = pointsForTier(tier);
		task.completed = isTaskCompleted(task.id);
		return task;
	}

	private boolean isTaskCompleted(int taskId)
	{
		if (taskId < 0 || taskId >= COMPLETION_VARPS.length * 32) return false;
		int value = client.getVarpValue(COMPLETION_VARPS[taskId / 32]);
		return (value & (1 << (taskId % 32))) != 0;
	}

	private AnchorModels.SkillProgress collectSkills()
	{
		AnchorModels.SkillProgress result = new AnchorModels.SkillProgress();
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL) continue;
			AnchorModels.SkillSnapshot snapshot = new AnchorModels.SkillSnapshot();
			snapshot.name = skill.getName();
			snapshot.level = client.getRealSkillLevel(skill);
			snapshot.experience = client.getSkillExperience(skill);
			result.skills.add(snapshot);
			result.totalLevel += snapshot.level;
			result.totalExperience += snapshot.experience;
		}
		return result;
	}

	private String currentTier(int points)
	{
		String current = "None";
		int[] thresholdVarbits = {
			VarbitID.CA_THRESHOLD_EASY, VarbitID.CA_THRESHOLD_MEDIUM, VarbitID.CA_THRESHOLD_HARD,
			VarbitID.CA_THRESHOLD_ELITE, VarbitID.CA_THRESHOLD_MASTER, VarbitID.CA_THRESHOLD_GRANDMASTER
		};
		int index = 0;
		for (String tier : TIER_ENUMS.values())
		{
			int threshold = client.getVarbitValue(thresholdVarbits[index++]);
			if (threshold > 0 && points >= threshold) current = tier;
		}
		return current;
	}

	private static int pointsForTier(String tier)
	{
		switch (tier.toLowerCase())
		{
			case "easy": return 1;
			case "medium": return 2;
			case "hard": return 3;
			case "elite": return 4;
			case "master": return 5;
			case "grandmaster": return 6;
			default: return 0;
		}
	}
}
