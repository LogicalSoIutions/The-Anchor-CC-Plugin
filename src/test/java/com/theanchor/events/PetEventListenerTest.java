package com.theanchor.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PetEventListenerTest
{
	@Test public void extractsPetNameFromCollectionLogChat()
	{
		assertEquals("Phoenix", PetEventListener.extractPetName(
			"New item added to your collection log: Phoenix"));
	}

	@Test public void extractsPetNameFromNamedFollowedChat()
	{
		assertEquals("Aggi", PetEventListener.extractPetName(
			"This Whip has a funny feeling like he's being followed: Aggi at 90 kills from Mad Angel."));
	}

	@Test public void ignoresNormalCollectionLogItems()
	{
		assertNull(PetEventListener.extractPetName(
			"New item added to your collection log: Abyssal whip"));
	}
}
