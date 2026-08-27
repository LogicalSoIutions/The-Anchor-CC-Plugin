/*
 * Combat-achievement enumeration is adapted from the provided Llama Club plugin,
 * which credits reval-cc. Both are distributed under the BSD 2-Clause License.
 * See LICENSES/llama-LICENSE.txt and the bundled transitive notices.
 */
package com.theanchor.progress;

import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import java.time.Instant;
import java.util.Arrays;
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
	private static final int DIARY_PROGRESS_SCRIPT_ID = 2200;
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

	@Inject
	public PlayerProgressService(Client client, AnchorApiClient api)
	{
		this.client = client;
		this.api = api;
	}

	public void syncAll()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}

		AnchorModels.PlayerProgressRequest request = collect();
		api.syncPlayerProgress(request, result ->
		{
			if (!result.isSuccessful())
			{
				log.error("Anchor progress sync failed: player={}, syncId={}, error={}",
					request.player.name, request.syncId, result.error);
			}
		});
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
		int skipped = 0;
		for (Quest quest : Quest.values())
		{
			String name = quest.getName();
			if (name == null || name.isBlank())
			{
				skipped++;
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
			client.runScript(DIARY_PROGRESS_SCRIPT_ID, areaId);
			int[] stack = client.getIntStack();
			AnchorModels.AchievementDiaryArea area = new AnchorModels.AchievementDiaryArea();
			area.id = areaId;
			area.name = DIARY_AREAS[areaId];
			for (int tierIndex = 0; tierIndex < DIARY_TIERS.length; tierIndex++)
			{
				AnchorModels.AchievementDiaryTier tier = new AnchorModels.AchievementDiaryTier();
				tier.name = DIARY_TIERS[tierIndex];
				tier.totalCount = DIARY_TOTALS[areaId][tierIndex];
				int stackIndex = tierIndex * 3;
				tier.completedCount = stack != null && stack.length > stackIndex ? stack[stackIndex] : 0;
				if (stack == null || stack.length < 10)
				{
					tier.completedCount = 0;
				}
				tier.completedCount = Math.max(0, Math.min(tier.completedCount, tier.totalCount));
				tier.completed = tier.completedCount >= tier.totalCount;
				area.tiers.add(tier);
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
		int enumCount = 0;
		int skippedTasks = 0;
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
			enumCount += tierEnum.getIntVals().length;
			for (int structId : tierEnum.getIntVals())
			{
				AnchorModels.CombatTask task = loadTask(structId, tierEntry.getValue());
				if (task == null)
				{
					skippedTasks++;
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
