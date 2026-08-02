package com.theanchor.service;

import com.theanchor.model.AnchorModels;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LootEligibilityTest
{
	@Test public void capturesQualifyingTradeableLoot()
	{
		AnchorModels.Item item = item("Expensive drop", 2_500_000L);
		assertTrue(LootEligibility.shouldCapture(item, new AnchorModels.Rules()));
		item.stackable = true;
		assertFalse(LootEligibility.shouldCapture(item, new AnchorModels.Rules()));
	}

	@Test public void capturesKnownSpecialLootWithoutCalculatingPoints()
	{
		AnchorModels.Item item = item("Metamorphic dust", 0);
		item.tradeable = false;
		assertTrue(LootEligibility.shouldCapture(item, new AnchorModels.Rules()));
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
