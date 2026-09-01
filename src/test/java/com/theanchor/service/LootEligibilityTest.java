package com.theanchor.service;

import com.theanchor.model.AnchorModels;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LootEligibilityTest
{
	@Test public void capturesQualifyingTradeableLoot()
	{
		AnchorModels.Item item = item("Expensive drop", 1_500_000L);
		assertTrue(LootEligibility.shouldCapture(item, new AnchorModels.Rules()));
		item.stackable = true;
		assertTrue(LootEligibility.shouldCapture(item, new AnchorModels.Rules()));
		item.unitGeValue = 1_499_999L;
		assertFalse(LootEligibility.shouldCapture(item, new AnchorModels.Rules()));
	}

	@Test public void capturesKnownSpecialLootWithoutCalculatingPoints()
	{
		AnchorModels.Item item = item("Metamorphic dust", 0);
		item.tradeable = false;
		assertTrue(LootEligibility.shouldCapture(item, new AnchorModels.Rules()));
	}

	@Test public void capturesCustomUntradeableLootRegardlessOfValue()
	{
		for (String name : new String[] {
			"Arcane prayer scroll", "Inquisitor's great helm", "Eldritch orb", "Mokhaiotl cloth",
			"Ultor vestige", "Magus vestige", "Bellator vestige", "Twisted ancestral colour kit",
			"Jar of darkness", "Etched elder venator fang", "Mr Mcgroot", "Aggy"
		})
		{
			AnchorModels.Item item = item(name, 0);
			item.tradeable = false;
			assertTrue(name, LootEligibility.isAlwaysSubmit(name));
			assertTrue(name, LootEligibility.shouldCapture(item, new AnchorModels.Rules()));
		}
	}

	@Test public void capturesNightmareUniqueOrbsButNotElementalOrbs()
	{
		for (String name : new String[] {"Eldritch orb", "Harmonised orb", "Volatile orb"})
		{
			AnchorModels.Item item = item(name, 0);
			item.tradeable = false;
			assertTrue(name, LootEligibility.isAlwaysSubmit(name));
		}

		assertFalse(LootEligibility.isAlwaysSubmit("Air orb"));
		assertFalse(LootEligibility.isAlwaysSubmit("Earth orb"));
	}

	@Test public void capturesRepeatableUntradeableCollectionLogLoot()
	{
		AnchorModels.Item item = item("Elder venator fang", 200_000L);
		item.tradeable = false;
		assertTrue(LootEligibility.shouldCapture(item, new AnchorModels.Rules()));
	}

	@Test public void capturesAllNoxiousHalberdPieces()
	{
		for (String name : new String[] {"Noxious point", "Noxious blade", "Noxious pommel"})
		{
			AnchorModels.Item item = item(name, 0);
			item.tradeable = false;
			assertTrue(name, LootEligibility.shouldCapture(item, new AnchorModels.Rules()));
		}
	}

	private static AnchorModels.Item item(String name, long value)
	{
		AnchorModels.Item item = new AnchorModels.Item();
		item.name = name;
		item.quantity = 1;
		item.unitGeValue = value;
		item.tradeable = true;
		return item;
	}
}
