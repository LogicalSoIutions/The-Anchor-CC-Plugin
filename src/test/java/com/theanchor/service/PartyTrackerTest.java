/*
 * Party-tracking tests cover logic adapted from Terpinheimer.
 * See LICENSES/terpinheimer-LICENSE.txt.
 */
package com.theanchor.service;

import com.google.gson.Gson;
import com.theanchor.model.AnchorModels;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.Assert.*;

public class PartyTrackerTest
{
	@Test public void recognizesRaidEntryAndFinalBossNames()
	{
		assertEquals("Theatre of Blood", PartyTracker.finalBossRaid("Verzik Vitur"));
		assertEquals("Theatre of Blood", PartyTracker.finalBossRaid("<col=ff0000>Verzik Vitur</col>"));
		assertEquals("Chambers of Xeric", PartyTracker.finalBossRaid("Great Olm"));
		assertEquals("Tombs of Amascut", PartyTracker.finalBossRaid("Tumeken's Warden"));
		assertNull(PartyTracker.finalBossRaid("Lady Verzik"));
		assertEquals("Theatre of Blood", PartyTracker.firstBossRaid("The Maiden of Sugadinti"));
		assertEquals("Tombs of Amascut", PartyTracker.firstBossRaid("Zebak"));
		assertEquals("Tombs of Amascut", PartyTracker.firstBossRaid("Ba-Ba"));
		assertNull(PartyTracker.firstBossRaid("Pestilent Bloat"));
	}

	@Test public void serializesOnlyClanNamesAndClanSplit()
	{
		AnchorModels.Party party = PartyTracker.snapshotFromData(3, Arrays.asList(
			new PartyTracker.MemberSnapshot("Anchor One", true),
			new PartyTracker.MemberSnapshot("Guest Two", false),
			new PartyTracker.MemberSnapshot("Unknown Three", null)), "raid_party", "high");
		String json = new Gson().toJson(party);
		assertEquals(3, party.detectedPartySize);
		assertEquals(1, party.detectedClanMemberCount);
		assertEquals(1, party.detectedNonClanMemberCount);
		assertEquals(1, party.members.size());
		assertEquals(List.of("Anchor One"), party.clanMemberNames);
		assertTrue(json.contains("Anchor One"));
		assertFalse(json.contains("Guest Two"));
		assertFalse(json.contains("Unknown Three"));
	}

	@Test public void partyCountCanExceedNames()
	{
		AnchorModels.Party party = PartyTracker.snapshotFromData(8,
			List.of(new PartyTracker.MemberSnapshot("Visible", true)), "raid_party", "high");
		assertEquals(8, party.detectedPartySize);
		assertEquals(1, party.detectedClanMemberCount);
		assertEquals(0, party.detectedNonClanMemberCount);
	}

	@Test public void emptyDetectionDefaultsToOne()
	{
		AnchorModels.Party party = PartyTracker.snapshotFromData(0, List.of(), "unknown", "low");
		assertEquals(1, party.detectedPartySize);
		assertEquals("unknown", party.method);
	}

	@Test public void fixedSoloOwnerIsTheClanParticipant()
	{
		AnchorModels.Party party = PartyTracker.snapshotFromData(1,
			List.of(new PartyTracker.MemberSnapshot("Solo Anchor", true)), "fixed_solo", "high");
		assertEquals(1, party.detectedClanMemberCount);
		assertEquals(1, party.submittedClanMemberCount);
		assertEquals(0, party.detectedNonClanMemberCount);
		assertTrue(party.clanMemberNames.isEmpty());
	}

	@Test public void chambersRosterReadsDisplayedNamesAndIgnoresStats()
	{
		Widget list = mock(Widget.class);
		Widget namedByAction = mock(Widget.class);
		Widget namedByText = mock(Widget.class);
		Widget combatLevel = mock(Widget.class);
		when(list.getChildren()).thenReturn(new Widget[] {namedByAction, namedByText, combatLevel});
		when(namedByAction.getName()).thenReturn("<col=ffffff>Anchor One</col>");
		when(namedByText.getText()).thenReturn("Guest Two");
		when(combatLevel.getText()).thenReturn("126");

		List<String> names = new ArrayList<>();
		PartyTracker.collectChambersNames(list, names);

		assertEquals(List.of("Anchor One", "Guest Two"), names);
	}

	@Test public void chambersCompletionKeepsRosterCapturedBeforeFinalTick() throws Exception
	{
		PartyTracker tracker = new PartyTracker();
		Client client = mock(Client.class);
		Player local = mock(Player.class);
		NPC olm = mock(NPC.class);
		ActorDeath death = mock(ActorDeath.class);
		when(local.getName()).thenReturn("Anchor One");
		when(olm.getName()).thenReturn("Great Olm");
		when(death.getActor()).thenReturn(olm);
		when(client.getPlayers()).thenReturn(List.of(local));
		setField(tracker, "client", client);
		setField(tracker, "activeRaidSource", "Chambers of Xeric");
		setField(tracker, "activeRaidNames", List.of("Anchor One", "Anchor Two", "Guest Three"));
		setField(tracker, "activeRaidPartySize", 3);

		tracker.onActorDeath(death);

		AnchorModels.Party party = tracker.snapshot("Chambers of Xeric");
		assertEquals(3, party.detectedPartySize);
		assertEquals("raid_party_final_boss", party.method);
	}

	@Test public void tracksVisiblePlayersWhenLocalPlayerIsNotTargeting() throws Exception
	{
		PartyTracker tracker = new PartyTracker();
		Client client = mock(Client.class);
		Player local = mock(Player.class);
		Player teammate = mock(Player.class);
		NPC nex = mock(NPC.class);
		when(local.getName()).thenReturn("Anchor One");
		when(teammate.getName()).thenReturn("Anchor Two");
		when(local.getInteracting()).thenReturn(null);
		when(teammate.getInteracting()).thenReturn(nex);
		when(client.getLocalPlayer()).thenReturn(local);
		when(client.getPlayers()).thenReturn(List.of(local, teammate));
		setField(tracker, "client", client);

		tracker.onGameTick(mock(net.runelite.api.events.GameTick.class));

		AnchorModels.Party party = tracker.snapshot("Nex");
		assertEquals(2, party.detectedPartySize);
		assertEquals("boss_interaction", party.method);
	}

	private static void setField(Object target, String name, Object value) throws Exception
	{
		java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
