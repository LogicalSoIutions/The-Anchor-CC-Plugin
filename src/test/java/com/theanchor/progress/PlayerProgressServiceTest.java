/*
 * Tests cover combat-achievement logic adapted from Llama Club.
 * See LICENSES/llama-LICENSE.txt.
 */
package com.theanchor.progress;

import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PlayerProgressServiceTest
{
	@Test public void collectsFullCombatTaskAndSkillSnapshot()
	{
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		when(player.getName()).thenReturn("Zach");
		when(client.getLocalPlayer()).thenReturn(player);
		when(client.getAccountHash()).thenReturn(12345L);

		EnumComposition easy = mock(EnumComposition.class);
		when(easy.getIntVals()).thenReturn(new int[] {9001});
		when(client.getEnum(3981)).thenReturn(easy);
		StructComposition taskStruct = mock(StructComposition.class);
		when(taskStruct.getIntValue(1306)).thenReturn(0);
		when(taskStruct.getStringValue(1308)).thenReturn("Sample task");
		when(client.getStructComposition(9001)).thenReturn(taskStruct);
		when(client.getVarpValue(3116)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.CA_POINTS)).thenReturn(41);
		when(client.getVarbitValue(VarbitID.CA_THRESHOLD_EASY)).thenReturn(41);
		when(client.getRealSkillLevel(any(Skill.class))).thenReturn(99);
		when(client.getSkillExperience(any(Skill.class))).thenReturn(13_034_431);

		PlayerProgressService service = new PlayerProgressService(client, mock(AnchorApiClient.class));
		AnchorModels.PlayerProgressRequest request = service.collect();

		assertEquals("Zach", request.player.name);
		assertEquals("12345", request.player.accountHash);
		assertEquals(1, request.combatAchievements.totalTasks);
		assertEquals(1, request.combatAchievements.completedTasks);
		assertEquals("Sample task", request.combatAchievements.tasks.get(0).name);
		assertTrue(request.combatAchievements.tasks.get(0).completed);
		assertEquals("Easy", request.combatAchievements.currentTier);
		assertEquals(1, request.combatAchievements.tierProgress.get("easy").obtained);
		assertEquals(Skill.values().length, request.skills.skills.size());
		assertEquals(99 * Skill.values().length, request.skills.totalLevel);
		assertEquals(13_034_431L * Skill.values().length, request.skills.totalExperience);
	}
}
