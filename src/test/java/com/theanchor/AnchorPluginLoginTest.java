/*
 * Login synchronization scenarios adapted from PB Tracker Sync.
 * See LICENSES/pb-tracker-sync-LICENSE.txt.
 */
package com.theanchor;

import com.theanchor.evidence.EventPipeline;
import com.theanchor.pb.PersonalBestService;
import com.theanchor.service.AnchorDataService;
import com.theanchor.service.BingoService;
import com.theanchor.service.EventAlertService;
import com.theanchor.progress.PlayerProgressService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AnchorPluginLoginTest
{
	@Test public void refreshesProfileAndCompetitionsEveryFifteenMinutesAndProgressEveryThirtyMinutes()
	{
		assertEquals(15, AnchorPlugin.PROFILE_AND_COMPETITIONS_REFRESH_MINUTES);
		assertEquals(30, AnchorPlugin.PLAYER_PROGRESS_REFRESH_MINUTES);
	}

	@Test public void scheduledProgressSyncDispatchesToClientThread() throws Exception
	{
		AnchorPlugin plugin = new AnchorPlugin();
		ClientThread clientThread = mock(ClientThread.class);
		PlayerProgressService progress = mock(PlayerProgressService.class);
		inject(plugin, "clientThread", clientThread);
		inject(plugin, "progress", progress);
		inject(plugin, "featuresActive", true);

		invoke(plugin, "runScheduledProgressSync");

		org.mockito.ArgumentCaptor<Runnable> callback = forClass(Runnable.class);
		verify(clientThread).invokeLater(callback.capture());
		callback.getValue().run();
		verify(progress).syncAll();
	}

	@Test public void refreshesWhenPlayerAppearsAfterLoggedInState() throws Exception
	{
		AnchorPlugin plugin = new AnchorPlugin();
		Client client = mock(Client.class);
		AnchorDataService data = mock(AnchorDataService.class);
		PersonalBestService pbs = mock(PersonalBestService.class);
		EventPipeline pipeline = mock(EventPipeline.class);
		EventAlertService eventAlerts = mock(EventAlertService.class);
		BingoService bingo = mock(BingoService.class);
		inject(plugin, "client", client);
		inject(plugin, "data", data);
		inject(plugin, "pbs", pbs);
		inject(plugin, "pipeline", pipeline);
		inject(plugin, "eventAlerts", eventAlerts);
		inject(plugin, "bingo", bingo);
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
		// Account-specific features wait for the profile lookup to confirm that
		// this exact RSN is on the clan roster.
		verify(pbs, never()).onLogin();
		verify(bingo, never()).refresh();
		verify(pipeline, never()).refreshStatuses("Zach");

		plugin.onGameTick(mock(GameTick.class));
		verify(data).refresh("Zach");
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static void invoke(Object target, String methodName) throws Exception
	{
		Method method = target.getClass().getDeclaredMethod(methodName);
		method.setAccessible(true);
		method.invoke(target);
	}
}
