/*
 * Party-tracking tests cover logic adapted from Terpinheimer.
 * See LICENSES/terpinheimer-LICENSE.txt.
 */
package com.theanchor.service;

import com.google.gson.Gson;
import com.theanchor.model.AnchorModels;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PartyTrackerTest
{
	@Test public void serializesNamesAndClanSplit()
	{
		AnchorModels.Party party = PartyTracker.snapshotFromData(3, Arrays.asList(
			new PartyTracker.MemberSnapshot("Anchor One", true),
			new PartyTracker.MemberSnapshot("Guest Two", false),
			new PartyTracker.MemberSnapshot("Unknown Three", null)), "raid_party", "high");
		String json = new Gson().toJson(party);
		assertEquals(3, party.detectedPartySize);
		assertEquals(1, party.detectedClanMemberCount);
		assertEquals(1, party.detectedNonClanMemberCount);
		assertEquals(3, party.members.size());
		assertTrue(json.contains("Anchor One"));
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
	}
}
