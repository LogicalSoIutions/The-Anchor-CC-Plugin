/*
 * Personal-best parsing tests adapted from PB Tracker Sync behavior.
 * See LICENSES/pb-tracker-sync-LICENSE.txt.
 */
package com.theanchor.pb;

import com.theanchor.model.AnchorModels;
import org.junit.Test;
import static org.junit.Assert.*;

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
