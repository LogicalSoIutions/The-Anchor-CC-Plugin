/*
 * Login synchronization scenarios adapted from PB Tracker Sync.
 * See LICENSES/pb-tracker-sync-LICENSE.txt.
 */
package com.theanchor;

import com.theanchor.evidence.EventPipeline;
import com.theanchor.pb.PersonalBestService;
import com.theanchor.service.AnchorDataService;
import com.theanchor.service.EventAlertService;
import java.lang.reflect.Field;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AnchorPluginLoginTest
{
	@Test public void refreshesProfileAndCompetitionsEveryFifteenMinutes()
	{
		assertEquals(15, AnchorPlugin.PROFILE_AND_COMPETITIONS_REFRESH_MINUTES);
	}

	@Test public void refreshesWhenPlayerAppearsAfterLoggedInState() throws Exception
	{
		AnchorPlugin plugin = new AnchorPlugin();
		Client client = mock(Client.class);
		AnchorDataService data = mock(AnchorDataService.class);
		PersonalBestService pbs = mock(PersonalBestService.class);
		EventPipeline pipeline = mock(EventPipeline.class);
		EventAlertService eventAlerts = mock(EventAlertService.class);
		inject(plugin, "client", client);
		inject(plugin, "data", data);
		inject(plugin, "pbs", pbs);
		inject(plugin, "pipeline", pipeline);
		inject(plugin, "eventAlerts", eventAlerts);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		GameStateChanged loggedIn = mock(GameStateChanged.class);
		when(loggedIn.getGameState()).thenReturn(GameState.LOGGED_IN);
		plugin.onGameStateChanged(loggedIn);
		verify(data, never()).refresh(org.mockito.ArgumentMatchers.anyString());

		Player player = mock(Player.class);
		when(player.getName()).thenReturn("Zach");
		when(client.getLocalPlayer()).thenReturn(player);
		plugin.onGameTick(mock(GameTick.class));

		verify(data).refresh("Zach");
		verify(pbs).onLogin();
		verify(pipeline).refreshStatuses("Zach");

		plugin.onGameTick(mock(GameTick.class));
		verify(data).refresh("Zach");
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
