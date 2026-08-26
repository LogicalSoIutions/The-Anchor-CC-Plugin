/*
 * Boss registry cases adapted from PB Tracker Sync.
 * See LICENSES/pb-tracker-sync-LICENSE.txt.
 */
package com.theanchor.service;

import org.junit.Test;
import static org.junit.Assert.*;

public class BossRegistryTest
{
	@Test public void multiIsCapabilityNotDefaultForEveryBoss()
	{
		assertTrue(BossRegistry.canBeMulti("Theatre of Blood: Hard Mode"));
		assertTrue(BossRegistry.canBeMulti("Kree'Arra"));
		assertFalse(BossRegistry.canBeMulti("Vorkath"));
		assertFalse(BossRegistry.canBeMulti("Venenatis"));
	}

	@Test public void acceptsRuneLiteSourceAliases()
	{
		assertTrue(BossRegistry.isSupported("Barrows"));
		assertTrue(BossRegistry.isSupported("Lunar Chest"));
		assertTrue(BossRegistry.isSupported("Chambers of Xeric Challenge Mode"));
		assertTrue(BossRegistry.isSupported("Dusk"));
		assertTrue(BossRegistry.isSupported("Corrupted Hunllef"));
		assertTrue(BossRegistry.canBeMulti("Branda the Fire Queen"));
	}

	@Test public void listedSharedBossesAllowMultiPartyTracking()
	{
		for (String boss : new String[] {
			"Nex", "General Graardor", "Commander Zilyana", "K'ril Tsutsaroth", "Kree'Arra",
			"The Royal Titans", "Scurrius", "Yama", "The Nightmare", "The Hueycoatl"
		})
		{
			assertTrue(boss, BossRegistry.canBeMulti(boss));
		}
	}
}
