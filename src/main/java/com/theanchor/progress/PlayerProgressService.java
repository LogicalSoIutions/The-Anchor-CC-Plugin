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
import net.runelite.api.Skill;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.VarbitID;

@Slf4j
@Singleton
public class PlayerProgressService
{
	private static final Map<Integer, String> TIER_ENUMS = new LinkedHashMap<>();
	private static final int[] COMPLETION_VARPS = {
		3116, 3117, 3118, 3119, 3120, 3121, 3122, 3123, 3124, 3125,
		3126, 3127, 3128, 3387, 3718, 3773, 3774, 4204, 4496, 4721
	};
	private static final int FIELD_TASK_ID = 1306;
	private static final int FIELD_NAME = 1308;

	static
	{
		TIER_ENUMS.put(3981, "Easy");
		TIER_ENUMS.put(3982, "Medium");
		TIER_ENUMS.put(3983, "Hard");
		TIER_ENUMS.put(3984, "Elite");
		TIER_ENUMS.put(3985, "Master");
		TIER_ENUMS.put(3986, "Grandmaster");
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
			log.debug("Progress sync skipped because the player is not ready");
			return;
		}

		AnchorModels.PlayerProgressRequest request = collect();
		log.info("Anchor progress sync starting: player={}, combatTasks={}/{}, skills={}, totalXp={}",
			request.player.name, request.combatAchievements.completedTasks,
			request.combatAchievements.totalTasks, request.skills.skills.size(),
			request.skills.totalExperience);
		api.syncPlayerProgress(request, result ->
		{
			if (result.isSuccessful())
			{
				log.info("Anchor progress sync completed: player={}, syncId={}", request.player.name, request.syncId);
			}
			else
			{
				log.warn("Anchor progress sync failed: player={}, syncId={}, error={}",
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
		request.skills = collectSkills();
		return request;
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
				log.debug("Combat achievement tier enum {} ({}) was unavailable", tierEntry.getKey(), tierEntry.getValue());
				continue;
			}
			for (int structId : tierEnum.getIntVals())
			{
				AnchorModels.CombatTask task = loadTask(structId, tierEntry.getValue());
				if (task == null) continue;
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
		log.debug("Collected combat achievements: completed={}, total={}, points={}, possible={}, tier={}",
			result.completedTasks, result.totalTasks, result.totalPoints, result.totalPossiblePoints, result.currentTier);
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
		task.name = name;
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
		log.debug("Collected skills: count={}, totalLevel={}, totalXp={}",
			result.skills.size(), result.totalLevel, result.totalExperience);
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
