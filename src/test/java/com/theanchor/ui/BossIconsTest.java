/*
 * Boss icon tests adapted from PB Tracker Sync.
 * See LICENSES/pb-tracker-sync-LICENSE.txt.
 */
package com.theanchor.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class BossIconsTest
{
	@Test public void resolvesAmoxliatlAndNormalizedBossNames()
	{
		assertNotNull(BossIcons.spriteIdFor("Amoxliatl"));
		assertEquals(BossIcons.spriteIdFor("The Nightmare"), BossIcons.spriteIdFor("Phosani's Nightmare"));
		assertEquals(BossIcons.spriteIdFor("Leviathan"), BossIcons.spriteIdFor("Leviathan (awakened)"));
	}
}
