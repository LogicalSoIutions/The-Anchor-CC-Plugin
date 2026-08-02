package com.theanchor.collection;

import com.theanchor.AnchorConfig;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CollectionLogPromptStateTest
{
	@Test public void persistsCompletionPerAccountHash()
	{
		Client client = mock(Client.class);
		ConfigManager config = mock(ConfigManager.class);
		when(client.getAccountHash()).thenReturn(12345L);
		CollectionLogPromptState state = new CollectionLogPromptState(client, config);

		assertFalse(state.hasSyncedForCurrentAccount());
		assertTrue(state.markSyncedForCurrentAccount());
		verify(config).setConfiguration(AnchorConfig.GROUP, "collectionLogSynced.12345", true);

		when(config.getConfiguration(AnchorConfig.GROUP, "collectionLogSynced.12345")).thenReturn("true");
		assertTrue(state.hasSyncedForCurrentAccount());
	}

	@Test public void doesNotPersistBeforeAccountHashExists()
	{
		CollectionLogPromptState state = new CollectionLogPromptState(mock(Client.class), mock(ConfigManager.class));
		assertFalse(state.markSyncedForCurrentAccount());
	}
}
