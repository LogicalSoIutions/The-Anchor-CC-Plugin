package com.theanchor.evidence;

import org.junit.Test;
import static org.junit.Assert.*;

public class EventDeduplicatorTest
{
	@Test public void suppressesWithinWindowAndAllowsAfterExpiry()
	{
		EventDeduplicator dedupe = new EventDeduplicator(1000);
		assertTrue(dedupe.accept("loot|1", 100)); assertFalse(dedupe.accept("loot|1", 500)); assertTrue(dedupe.accept("loot|1", 1100));
	}
}

