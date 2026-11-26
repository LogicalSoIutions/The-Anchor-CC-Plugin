/*
 * Portions adapted from the Llama Club and PB Tracker Sync plugins.
 * See LICENSES/llama-LICENSE.txt and LICENSES/pb-tracker-sync-LICENSE.txt.
 */
package com.theanchor;

import com.google.inject.Provides;
import com.theanchor.evidence.EventPipeline;
import com.theanchor.evidence.EvidenceStore;
import com.theanchor.api.SyncOutbox;
import com.theanchor.collection.CollectionLogSyncService;
import com.theanchor.events.CollectionLogEventListener;
import com.theanchor.events.CombatTierEventListener;
import com.theanchor.events.LootEventListener;
import com.theanchor.events.WeaponCreationEventListener;
import com.theanchor.events.PetEventListener;
import com.theanchor.model.AnchorModels;
import com.theanchor.model.CharacterIdentity;
import com.theanchor.pb.PersonalBestService;
import com.theanchor.progress.PlayerProgressService;
import com.theanchor.service.AnchorDataService;
import com.theanchor.service.BingoService;
import com.theanchor.service.EventAlertService;
import com.theanchor.service.ImageCache;
import com.theanchor.service.PartyTracker;
import com.theanchor.ui.AnchorPanel;
import com.theanchor.ui.BingoEventOverlay;
import com.theanchor.ui.CollectionLogSyncOverlay;
import com.theanchor.ui.CollectionLogAutoSync;
import com.theanchor.ui.CollectionLogRefreshButton;
import com.theanchor.ui.ClanWorldSorter;
import com.theanchor.ui.ClanMemberNotes;
import com.theanchor.ui.EventAlertOverlay;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Filepath;

@Slf4j
@PluginDescriptor(
	name = "The Anchor",
	internalName = "the-anchor-cc",
	legacyDataDirectory = "the-anchor",
	description = "The Anchor clan dashboard, evidence capture, loot submissions, and PB sync",
	tags = {"clan", "loot", "pvm", "personal best", "collection log", "pet"}
)
public class AnchorPlugin extends Plugin
{
	static final long PROFILE_AND_COMPETITIONS_REFRESH_MINUTES = 15;
	static final long PLAYER_PROGRESS_REFRESH_MINUTES = 30;

	@Inject private Client client;
	@Inject private ClientToolbar toolbar;
	@Inject private EventBus eventBus;
	@Inject private ScheduledExecutorService executor;
	@Inject private ClientThread clientThread;
	@Inject private OverlayManager overlayManager;
	@Inject private AnchorConfig config;
	@Inject private ConfigManager configManager;
	@Inject private AnchorPanel panel;
	@Inject private AnchorDataService data;
	@Inject private BingoService bingo;
	@Inject private EvidenceStore evidence;
	@Inject private EventPipeline pipeline;
	@Inject private SyncOutbox syncOutbox;
	@Inject private ImageCache imageCache;
	@Inject private PersonalBestService pbs;
	@Inject private com.theanchor.pb.PvmDiaryContractService diaryContract;
	@Inject private PlayerProgressService progress;
	@Inject private CollectionLogSyncService collectionLogSync;
	@Inject private CollectionLogSyncOverlay collectionLogSyncOverlay;
	@Inject private CollectionLogAutoSync collectionLogAutoSync;
	@Inject private CollectionLogRefreshButton collectionLogRefreshButton;
	@Inject private ClanWorldSorter clanWorldSorter;
	@Inject private ClanMemberNotes clanMemberNotes;
	@Inject private EventAlertService eventAlerts;
	@Inject private EventAlertOverlay eventAlertOverlay;
	@Inject private BingoEventOverlay bingoEventOverlay;
	@Inject private PartyTracker parties;
	@Inject private LootEventListener loot;
	@Inject private WeaponCreationEventListener weaponCreation;
	@Inject private CollectionLogEventListener collectionLog;
	@Inject private PetEventListener pets;
	@Inject private CombatTierEventListener combatTiers;
	private NavigationButton navigation;
	private ScheduledFuture<?> refreshTask;
	private ScheduledFuture<?> progressTask;
	private List<Object> registered;
	private volatile boolean featuresActive;
	private String firstConnectionSyncedAccount;
	private String firstConnectionPbsAccount;
	private String activeAccount;
	private String activePlayerName;
	private final Runnable connectionListener = () -> clientThread.invokeLater(this::updateFeatureActivation);

	@Provides AnchorConfig provideConfig(ConfigManager manager) { return manager.getConfig(AnchorConfig.class); }

	@Provides
	@Singleton
	@Named("anchorDirectory")
	Filepath provideDataDirectory() throws IOException
	{
		return getPluginDirectory();
	}

	@Override protected void startUp()
	{
		eventBus.register(clanWorldSorter);
		eventBus.register(clanMemberNotes);
		overlayManager.add(clanMemberNotes);
		clientThread.invokeLater(clanWorldSorter::refresh);
		navigation = NavigationButton.builder().tooltip("The Anchor").icon(loadIcon()).priority(5).panel(panel).build(); toolbar.addNavigation(navigation);
		overlayManager.add(collectionLogSyncOverlay);
		overlayManager.add(eventAlertOverlay);
		overlayManager.add(bingoEventOverlay);
		registered = Arrays.asList(parties, loot, weaponCreation, collectionLog, collectionLogSync, pets, combatTiers, pbs);
		data.addListener(connectionListener);
		data.validate();
		executor.execute(() -> evidence.pruneUploaded(config.retentionDays()));
		if (client.getGameState() == GameState.LOGGED_IN) onLoggedIn();
		refreshTask = executor.scheduleAtFixedRate(this::runScheduledRefresh,
			PROFILE_AND_COMPETITIONS_REFRESH_MINUTES,
			PROFILE_AND_COMPETITIONS_REFRESH_MINUTES,
			TimeUnit.MINUTES);
		progressTask = executor.scheduleAtFixedRate(this::runScheduledProgressSync,
			PLAYER_PROGRESS_REFRESH_MINUTES,
			PLAYER_PROGRESS_REFRESH_MINUTES,
			TimeUnit.MINUTES);
	}

	@Override protected void shutDown()
	{
		eventBus.unregister(clanWorldSorter);
		eventBus.unregister(clanMemberNotes);
		overlayManager.remove(clanMemberNotes);
		clientThread.invokeLater(clanWorldSorter::shutDown);
		if (refreshTask != null) { refreshTask.cancel(false); refreshTask = null; }
		if (progressTask != null) { progressTask.cancel(false); progressTask = null; }
		deactivateFeatures();
		registered = null;
		data.removeListener(connectionListener);
		if (navigation != null) { toolbar.removeNavigation(navigation); navigation = null; }
		overlayManager.remove(collectionLogSyncOverlay);
		overlayManager.remove(eventAlertOverlay);
		overlayManager.remove(bingoEventOverlay);
		eventAlerts.resetStateForWorldHopOrLogin();
		bingo.clear();
		clearCharacter();
		data.loggedOut();
	}

	@Subscribe public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING
			|| event.getGameState() == GameState.CONNECTION_LOST)
		{
			eventAlerts.resetStateForWorldHopOrLogin();
			progress.cancelCapture();
			pipeline.setActiveAccount(null);
			syncOutbox.setActiveAccount(null);
		}
		if (event.getGameState() == GameState.LOGGED_IN) tryOnLoggedIn();
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			deactivateFeatures();
			clearCharacter();
			bingo.clear();
			data.loggedOut();
		}
	}

	@Subscribe public void onGameTick(GameTick event)
	{
		tryOnLoggedIn();
		parties.debugRaidTick(featuresActive);
		if (featuresActive) eventAlerts.onGameTick();
	}

	@Subscribe public void onConfigChanged(ConfigChanged event)
	{
		if (!AnchorConfig.GROUP.equals(event.getGroup())) return;
		switch (event.getKey())
		{
			case "authenticationCode":
				clientThread.invoke(() ->
				{
					firstConnectionSyncedAccount = null;
					firstConnectionPbsAccount = null;
					data.validate();
					updateFeatureActivation();
				});
				break;
			case "animatedBanner": panel.refreshBanner(); break;
			case "eventAlerts": eventAlerts.resetStateForWorldHopOrLogin(); break;
			case "refreshProfile": if (Boolean.parseBoolean(event.getNewValue())) { clientThread.invoke(this::refreshCurrentPlayer); resetAction(event.getKey()); } break;
			case "retryOutbox": if (Boolean.parseBoolean(event.getNewValue())) { clientThread.invoke(this::retryUploads); resetAction(event.getKey()); } break;
			case "clearImageCache": if (Boolean.parseBoolean(event.getNewValue())) { imageCache.clear(); clientThread.invoke(this::refreshCurrentPlayer); resetAction(event.getKey()); } break;
			default: break;
		}
	}

	/** Keeps legacy action config items usable without leaving a checked state behind. */
	private void resetAction(String key) { configManager.setConfiguration(AnchorConfig.GROUP, key, false); }

	private void onLoggedIn() { tryOnLoggedIn(); }
	private void tryOnLoggedIn()
	{
		AnchorModels.PlayerIdentity player = CharacterIdentity.capture(client);
		if (player == null) return;
		boolean accountChanged = !player.accountHash.equals(activeAccount);
		if (accountChanged)
		{
			deactivateFeatures();
			parties.resetEncounter();
			data.loggedOut();
		}
		pipeline.setActiveAccount(player.accountHash);
		syncOutbox.setActiveAccount(player.accountHash);
		if (accountChanged || !player.name.equals(activePlayerName))
		{
			activeAccount = player.accountHash;
			activePlayerName = player.name;
			firstConnectionSyncedAccount = null;
			firstConnectionPbsAccount = null;
			data.refresh(player.name);
		}
		updateFeatureActivation();
	}

	private void clearCharacter()
	{
		progress.cancelCapture();
		activePlayerName = null;
		activeAccount = null;
		firstConnectionSyncedAccount = null;
		firstConnectionPbsAccount = null;
		pipeline.setActiveAccount(null);
		syncOutbox.setActiveAccount(null);
	}

	private void retryUploads()
	{
		AnchorModels.PlayerIdentity player = CharacterIdentity.capture(client);
		if (player == null || !player.accountHash.equals(activeAccount)
			|| data.connection() != AnchorDataService.Connection.CONNECTED) return;
		pipeline.retryAll(player.accountHash, true);
		syncOutbox.retry(player.accountHash, true);
	}

	private void updateFeatureActivation()
	{
		// Region loading can temporarily remove the local player. Preserve feature
		// subscriptions and raid state through that transition; login/hop still reset normally.
		if (client.getGameState() == GameState.LOADING) return;
		AnchorModels.PlayerIdentity player = CharacterIdentity.capture(client);
		if (client.getGameState() == GameState.LOGGED_IN && player == null) return;
		boolean shouldBeActive = player != null && player.accountHash.equals(activeAccount)
			&& player.name.equals(activePlayerName)
			&& data.connection() == AnchorDataService.Connection.CONNECTED
			&& data.isCurrentPlayerClanMember();
		if (!shouldBeActive)
		{
			deactivateFeatures();
			return;
		}
		if (!featuresActive)
		{
			featuresActive = true;
			weaponCreation.reset();
			for (Object listener : registered) eventBus.register(listener);
			collectionLogSync.beginCapture();
			collectionLogAutoSync.startUp();
			collectionLogRefreshButton.startUp();
			bingo.refresh();
			pbs.onLogin();
			pipeline.refreshStatuses(player.name, player.accountHash);
		}
		syncFirstConnectionData(player);
	}

	private void deactivateFeatures()
	{
		if (!featuresActive) return;
		featuresActive = false;
		weaponCreation.reset();
		collectionLog.reset();
		pets.reset();
		if (registered != null) for (Object listener : registered) eventBus.unregister(listener);
		collectionLogRefreshButton.shutDown();
		collectionLogAutoSync.shutDown();
		firstConnectionSyncedAccount = null;
		firstConnectionPbsAccount = null;
		progress.cancelCapture();
		eventAlerts.resetStateForWorldHopOrLogin();
		bingo.clear();
	}

	private void syncFirstConnectionData(AnchorModels.PlayerIdentity player)
	{
		String account = player.accountHash;
		if (!syncOutbox.isReady(account)) return;
		if (!account.equals(firstConnectionSyncedAccount))
		{
			firstConnectionSyncedAccount = account;
			pipeline.retryAll(account, false);
			syncOutbox.retry(account, false);
			progress.syncAll();
		}
		if (!account.equals(firstConnectionPbsAccount) && pbs.syncAll()) firstConnectionPbsAccount = account;
	}

	private void refreshCurrentPlayer()
	{
		AnchorModels.PlayerIdentity player = CharacterIdentity.capture(client);
		if (player == null || !player.accountHash.equals(activeAccount)) return;
		data.refresh(player.name);
		if (featuresActive)
		{
			bingo.refresh();
			diaryContract.refresh();
			pipeline.refreshStatuses(player.name, player.accountHash);
		}
	}
	private void runScheduledRefresh()
	{
		try
		{
			clientThread.invokeLater(this::refreshCurrentPlayer);
			data.refreshLeaderboards();
		}
		catch (RuntimeException e) { log.error("Unable to refresh Anchor data", e); }
	}
	private void runScheduledProgressSync()
	{
		if (!featuresActive) return;
		try
		{
			clientThread.invokeLater(() ->
			{
				AnchorModels.PlayerIdentity player = CharacterIdentity.capture(client);
				if (!featuresActive || player == null || !player.accountHash.equals(activeAccount)) return;
				syncOutbox.retry(player.accountHash, false);
				progress.syncAll();
			});
		}
		catch (RuntimeException e) { log.error("Unable to schedule Anchor player progress sync", e); }
	}

	private BufferedImage loadIcon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(28, 38, 52));
		g.fillOval(0, 0, 16, 16);
		g.setColor(new Color(207, 164, 76));
		g.drawLine(8, 2, 8, 13);
		g.drawArc(3, 7, 10, 7, 180, 180);
		g.dispose();
		return image;
	}
}
