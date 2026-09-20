/*
 * Personal-best parsing tests adapted from PB Tracker Sync behavior.
 * See LICENSES/pb-tracker-sync-LICENSE.txt.
 */
package com.theanchor.pb;

import com.theanchor.api.AnchorApiClient;
import com.theanchor.evidence.EventPipeline;
import com.theanchor.model.AnchorModels;
import com.theanchor.service.PartyTracker;
import java.lang.reflect.Field;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class PersonalBestServiceTest
{
	@Test public void rejectsAmbiguousRaidKeys()
	{
		assertTrue(PersonalBestService.isAmbiguous("chambers of xeric 3 players"));
		assertTrue(PersonalBestService.isAmbiguous("nightmare 5 players"));
		assertTrue(PersonalBestService.isAmbiguous("theatre of blood"));
		assertTrue(PersonalBestService.isAmbiguous("tombs of amascut"));
		assertTrue(PersonalBestService.isAmbiguous("chambers of xeric"));
		assertTrue(PersonalBestService.isAmbiguous("nightmare"));
		assertFalse(PersonalBestService.isAmbiguous("Vorkath"));
	}

	@Test public void normalizesDurationToIntegerMilliseconds()
	{
		AnchorModels.PbRecord record = PersonalBestService.fromKey("Vorkath", 54.321);
		assertEquals("Vorkath", record.activity); assertEquals(Long.valueOf(54321L), record.durationMillis);
	}

	@Test public void parsesTimeShapes()
	{
		assertEquals(90.5, PersonalBestService.parseTime("1:30.5"), 0.001);
		assertEquals(3690.0, PersonalBestService.parseTime("1:01:30"), 0.001);
		assertNull(PersonalBestService.parseTime("bad"));
	}

	@Test public void normalizesPreviouslyDeferredDiaryActivities()
	{
		AnchorModels.PbRecord fortis = PersonalBestService.diaryRecordFromKey("sol heredit", 1_234.5);
		assertEquals("Fortis Colosseum", fortis.activity);
		assertEquals(Integer.valueOf(1), fortis.teamSize);

		AnchorModels.PbRecord raid = PersonalBestService.diaryRecordFromKey(
			"chambers of xeric challenge mode 3 players", 1_234.5);
		assertEquals("Chambers of Xeric", raid.activity);
		assertEquals("challenge_mode", raid.variant);
		assertEquals(Integer.valueOf(3), raid.teamSize);
	}

	@Test public void detectsFightCavesAndInfernoKillCountMessages()
	{
		assertEquals("TzHaar Fight Cave", PersonalBestService.specialActivityFromKillCount(
			"Your TzTok-Jad kill count is: 18."));
		assertEquals("Inferno", PersonalBestService.specialActivityFromKillCount(
			"Your TzKal-Zuk kill count is: 7."));
		assertNull(PersonalBestService.specialActivityFromKillCount(
			"Your Vorkath kill count is: 100."));
	}

	@Test public void detectsSpecialActivityNewPersonalBestDuration()
	{
		assertEquals(1818.6, PersonalBestService.newPersonalBestDuration(
			"Duration: 30:18.60 (new personal best)"), 0.001);
		assertEquals(3690.2, PersonalBestService.newPersonalBestDuration(
			"Duration: 1:01:30.20 (new personal best)."), 0.001);
		assertNull(PersonalBestService.newPersonalBestDuration("Duration: 30:18.60"));
	}

	@Test public void parsesNonPbCoxCompletionForDiaryEvidence()
	{
		AnchorModels.PbRecord record = PersonalBestService.coxCompletionRecord(
			"Team size: 5 players Duration: 11:15.00 Personal best: 9:52.80 Olm duration: 5:13.2");
		assertNotNull(record);
		assertEquals("Chambers of Xeric", record.activity);
		assertEquals(Integer.valueOf(5), record.teamSize);
		assertEquals(Long.valueOf(675000L), record.durationMillis);
	}

	@Test public void parsesCombinedCoxCompletionAndOtherRaidSignals()
	{
		AnchorModels.PbRecord cox = PersonalBestService.coxCompletionRecord(
			"Congratulations - your raid is complete! Team size: 3 players Duration: 12:00.00 (new personal best)");
		assertNotNull(cox);
		assertEquals(Integer.valueOf(3), cox.teamSize);
		assertEquals(Long.valueOf(720000L), cox.durationMillis);
		assertEquals(Long.valueOf(990000L), PersonalBestService.raidCompletionDurationMillis(
			"Completion time: 16:30.00. Personal best: 15:42.00"));
		assertEquals("Theatre of Blood Hard Mode", PersonalBestService.raidActivityFromCompletionMessage(
			"Your Theatre of Blood: Hard Mode completion count is: 12."));
		assertEquals("Tombs of Amascut Expert Mode", PersonalBestService.raidActivityFromCompletionMessage(
			"Your Tombs of Amascut: Expert Mode completion count is: 42."));
	}

	@Test public void capturesNonPbCoxCompletionAsDiarySubmission() throws Exception
	{
		PersonalBestService service = new PersonalBestService();
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		EventPipeline pipeline = mock(EventPipeline.class);
		PvmDiaryContractService diaryContract = mock(PvmDiaryContractService.class);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("LogicalMash");
		when(diaryContract.detailsForObservedResult(any(), eq("chambers of xeric 5 players"), isNull(), anyString()))
			.thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of("activityId", "cox-five")));
		inject(service, "client", client);
		inject(service, "pipeline", pipeline);
		inject(service, "diaryContract", diaryContract);

		service.onChatMessage(chat("Congratulations - your raid is complete!"));
		service.onChatMessage(chat(
			"Team size: 5 players Duration: 11:15.00 Personal best: 9:52.80 Olm duration: 5:13.2"));

		org.mockito.ArgumentCaptor<AnchorModels.Source> source =
			org.mockito.ArgumentCaptor.forClass(AnchorModels.Source.class);
		verify(pipeline).capture(eq("diary"), eq("Chambers of Xeric|null|5|overall|null|675000"),
			source.capture(), isNull(), argThat(details -> Boolean.TRUE.equals(details.get("autoSubmit"))), eq(true));
		assertEquals("Chambers of Xeric", source.getValue().name);
	}

	@Test public void capturesNonPbTobAndInvocationSpecificToaCompletions() throws Exception
	{
		PersonalBestService service = new PersonalBestService();
		Client client = mock(Client.class);
		EventPipeline pipeline = mock(EventPipeline.class);
		PvmDiaryContractService diaryContract = mock(PvmDiaryContractService.class);
		PartyTracker parties = mock(PartyTracker.class);
		AnchorModels.Party tobParty = new AnchorModels.Party(); tobParty.detectedPartySize = 3;
		AnchorModels.Party toaParty = new AnchorModels.Party(); toaParty.detectedPartySize = 1;
		when(parties.snapshot("Theatre of Blood Hard Mode")).thenReturn(tobParty);
		when(parties.snapshot("Tombs of Amascut Expert Mode")).thenReturn(toaParty);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.TOA_CLIENT_RAID_LEVEL)).thenReturn(500);
		when(diaryContract.detailsForObservedResult(any(), anyString(), nullable(Integer.class), anyString()))
			.thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of("activityId", "supported")));
		inject(service, "client", client);
		inject(service, "pipeline", pipeline);
		inject(service, "diaryContract", diaryContract);
		inject(service, "parties", parties);

		service.onChatMessage(chat("Completion time: 20:00.00. Personal best: 19:00.00"));
		service.onChatMessage(chat("Your Theatre of Blood: Hard Mode completion count is: 12."));
		service.onChatMessage(chat("Total completion time: 26:00.00. Personal best: 25:00.00"));
		service.onChatMessage(chat("Your Tombs of Amascut: Expert Mode completion count is: 42."));

		verify(diaryContract).detailsForObservedResult(any(),
			eq("theatre of blood hard mode 3 players"), isNull(), anyString());
		verify(diaryContract).detailsForObservedResult(any(),
			eq("tombs of amascut expert mode 1 players"), eq(500), anyString());
		verify(pipeline, times(2)).capture(eq("diary"), anyString(), any(AnchorModels.Source.class),
			isNull(), anyMap(), eq(true));
	}

	@Test public void capturesFightCavesAndInfernoPbsAsSubmissions() throws Exception
	{
		PersonalBestService service = new PersonalBestService();
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		AnchorApiClient api = mock(AnchorApiClient.class);
		EventPipeline pipeline = mock(EventPipeline.class);
		PvmDiaryContractService diaryContract = mock(PvmDiaryContractService.class);
		when(diaryContract.detailsFor(any(), anyString(), anyString())).thenReturn(null);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("LogicalMash");
		when(client.getAccountHash()).thenReturn(123L);
		inject(service, "client", client);
		inject(service, "api", api);
		inject(service, "pipeline", pipeline);
		inject(service, "diaryContract", diaryContract);

		service.onChatMessage(chat("Your TzTok-Jad kill count is: 18."));
		service.onChatMessage(chat("Duration: 30:18.60 (new personal best)"));
		service.onChatMessage(chat("Your TzKal-Zuk kill count is: 7."));
		service.onChatMessage(chat("Duration: 1:01:30.20 (new personal best)"));

		verify(pipeline).capture(eq("personal_best"), eq("TzHaar Fight Cave|null|1|overall|null|1818600"),
			isNull(), isNull(), anyMap(), eq(false));
		verify(pipeline).capture(eq("personal_best"), eq("Inferno|null|1|overall|null|3690200"),
			isNull(), isNull(), anyMap(), eq(false));
		verify(api, times(2)).syncPbs(any(AnchorModels.PbBulkRequest.class), eq(false), any());
	}

	private static ChatMessage chat(String text)
	{
		ChatMessage event = mock(ChatMessage.class);
		when(event.getType()).thenReturn(ChatMessageType.GAMEMESSAGE);
		when(event.getMessage()).thenReturn(text);
		return event;
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	@Test public void parsesAdventureLogWidgets()
	{
		net.runelite.api.widgets.Widget w1 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(w1.getText()).thenReturn("Abyssal Sire:");
		net.runelite.api.widgets.Widget w2 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(w2.getText()).thenReturn("Fastest solo kill: 0:54.20");

		net.runelite.api.widgets.Widget w3 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(w3.getText()).thenReturn("Chambers of Xeric");
		net.runelite.api.widgets.Widget w4 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(w4.getText()).thenReturn("Fastest 3 players kill:");
		net.runelite.api.widgets.Widget w5 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(w5.getText()).thenReturn("12:34.50");

		java.util.List<AnchorModels.PbRecord> records = PersonalBestService.parseAdventureLog(
			new net.runelite.api.widgets.Widget[] {w1, w2, w3, w4, w5});

		assertEquals(2, records.size());
		AnchorModels.PbRecord r1 = records.get(0);
		assertEquals("Abyssal Sire", r1.activity);
		assertEquals(Long.valueOf(54200L), r1.durationMillis);
		assertNull(r1.teamSize);

		AnchorModels.PbRecord r2 = records.get(1);
		assertEquals("Chambers of Xeric", r2.activity);
		assertEquals(Long.valueOf(754500L), r2.durationMillis);
		assertEquals(Integer.valueOf(3), r2.teamSize);
	}

	@Test public void ordinarySoloAdventurePbMatchesLoginIdentity()
	{
		net.runelite.api.widgets.Widget heading = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(heading.getText()).thenReturn("Vorkath:");
		net.runelite.api.widgets.Widget time = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(time.getText()).thenReturn("Fastest solo kill: 0:54.20");

		AnchorModels.PbRecord adventure = PersonalBestService.parseAdventureLog(
			new net.runelite.api.widgets.Widget[] {heading, time}).get(0);
		AnchorModels.PbRecord login = PersonalBestService.fromKey("Vorkath", 54.2);

		assertEquals(PersonalBestService.recordKey(login), PersonalBestService.recordKey(adventure));
		assertNull(adventure.teamSize);
	}

	@Test public void scalableSoloAdventurePbKeepsTeamSize()
	{
		net.runelite.api.widgets.Widget heading = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(heading.getText()).thenReturn("Chambers of Xeric:");
		net.runelite.api.widgets.Widget time = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(time.getText()).thenReturn("Fastest solo raid: 18:32.40");

		AnchorModels.PbRecord adventure = PersonalBestService.parseAdventureLog(
			new net.runelite.api.widgets.Widget[] {heading, time}).get(0);

		assertEquals(Integer.valueOf(1), adventure.teamSize);
	}

	@Test public void collectsWidgetChildrenFromMultipleArrays()
	{
		net.runelite.api.widgets.Widget parent = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		net.runelite.api.widgets.Widget c1 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		net.runelite.api.widgets.Widget c2 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(parent.getStaticChildren()).thenReturn(new net.runelite.api.widgets.Widget[] {c1});
		org.mockito.Mockito.when(parent.getDynamicChildren()).thenReturn(new net.runelite.api.widgets.Widget[] {c2});

		net.runelite.api.widgets.Widget[] collected = PersonalBestService.collectChildren(parent);
		assertEquals(2, collected.length);
	}

	@Test public void parsesSplitWidgetLinesAndVariants()
	{
		net.runelite.api.widgets.Widget tobHeading = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(tobHeading.getText()).thenReturn("Theatre of Blood - Entry");

		net.runelite.api.widgets.Widget tobW1 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(tobW1.getText()).thenReturn("Fastest Room time - (Team size: 1 player entry mode):\u00A0");

		net.runelite.api.widgets.Widget tobW2 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(tobW2.getText()).thenReturn("19:44.40");

		net.runelite.api.widgets.Widget toaHeading = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(toaHeading.getText()).thenReturn("Tombs of Amascut - Expert");

		net.runelite.api.widgets.Widget toaW1 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(toaW1.getText()).thenReturn("Fastest Room time - (Team size: (2 player): 30:16.20");

		java.util.List<AnchorModels.PbRecord> records = PersonalBestService.parseAdventureLog(
			new net.runelite.api.widgets.Widget[] {tobHeading, tobW1, tobW2, toaHeading, toaW1});

		assertEquals(2, records.size());
		AnchorModels.PbRecord r1 = records.get(0);
		assertEquals("Theatre of Blood - Entry", r1.activity);
		assertEquals(Long.valueOf(1184400L), r1.durationMillis);
		assertEquals(Integer.valueOf(1), r1.teamSize);
		assertEquals("room", r1.recordType);
		assertEquals("entry", r1.variant);

		AnchorModels.PbRecord r2 = records.get(1);
		assertEquals("Tombs of Amascut - Expert", r2.activity);
		assertEquals(Long.valueOf(1816200L), r2.durationMillis);
		assertEquals(Integer.valueOf(2), r2.teamSize);
		assertEquals("room", r2.recordType);
		assertEquals("expert", r2.variant);
	}

	@Test public void parsesNonTimeAdventureLogStats()
	{
		net.runelite.api.widgets.Widget w1 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(w1.getText()).thenReturn("Chompy Hunting");

		net.runelite.api.widgets.Widget w2 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(w2.getText()).thenReturn("Kills: 1,001, Rank: Ogre Expert");

		net.runelite.api.widgets.Widget w3 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(w3.getText()).thenReturn("Last Man Standing");

		net.runelite.api.widgets.Widget w4 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(w4.getText()).thenReturn("Rank: 481");

		net.runelite.api.widgets.Widget w5 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(w5.getText()).thenReturn("Order of the White Knights");

		net.runelite.api.widgets.Widget w6 = org.mockito.Mockito.mock(net.runelite.api.widgets.Widget.class);
		org.mockito.Mockito.when(w6.getText()).thenReturn("Rank: Master, with a kill score of 1,300");

		java.util.List<AnchorModels.PbRecord> records = PersonalBestService.parseAdventureLog(
			new net.runelite.api.widgets.Widget[] {w1, w2, w3, w4, w5, w6});

		assertEquals(5, records.size());

		AnchorModels.PbRecord r1 = records.get(0);
		assertEquals("Chompy Hunting", r1.activity);
		assertEquals("kills", r1.recordType);
		assertEquals(Long.valueOf(1001L), r1.count);

		AnchorModels.PbRecord r2 = records.get(1);
		assertEquals("Chompy Hunting", r2.activity);
		assertEquals("rank", r2.recordType);
		assertEquals("Ogre Expert", r2.value);

		AnchorModels.PbRecord r3 = records.get(2);
		assertEquals("Last Man Standing", r3.activity);
		assertEquals("rank", r3.recordType);
		assertEquals(Long.valueOf(481), r3.count);

		AnchorModels.PbRecord r4 = records.get(3);
		assertEquals("Order of the White Knights", r4.activity);
		assertEquals("rank", r4.recordType);
		assertEquals("Master", r4.value);

		AnchorModels.PbRecord r5 = records.get(4);
		assertEquals("Order of the White Knights", r5.activity);
		assertEquals("score", r5.recordType);
		assertEquals(Long.valueOf(1300), r5.count);
	}
}
