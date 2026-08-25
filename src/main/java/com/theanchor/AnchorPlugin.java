/*
 * Portions adapted from the Llama Club and PB Tracker Sync plugins.
 * See LICENSES/llama-LICENSE.txt and LICENSES/pb-tracker-sync-LICENSE.txt.
 */
package com.theanchor;

import com.google.inject.Provides;
import com.theanchor.evidence.EventPipeline;
import com.theanchor.evidence.EvidenceStore;
import com.theanchor.collection.CollectionLogSyncService;
import com.theanchor.events.CollectionLogEventListener;
import com.theanchor.events.CombatTierEventListener;
import com.theanchor.events.LootEventListener;
import com.theanchor.events.PetEventListener;
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
import com.theanchor.ui.EventAlertOverlay;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
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
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "The Anchor",
	description = "The Anchor clan dashboard, evidence capture, loot submissions, and PB sync",
	tags = {"clan", "loot", "pvm", "personal best", "collection log", "pet"}
)
public class AnchorPlugin extends Plugin
{
	static final long PROFILE_AND_COMPETITIONS_REFRESH_MINUTES = 15;

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
	@Inject private ImageCache imageCache;
	@Inject private PersonalBestService pbs;
	@Inject private PlayerProgressService progress;
	@Inject private CollectionLogSyncService collectionLogSync;
	@Inject private CollectionLogSyncOverlay collectionLogSyncOverlay;
	@Inject private CollectionLogAutoSync collectionLogAutoSync;
	@Inject private CollectionLogRefreshButton collectionLogRefreshButton;
	@Inject private EventAlertService eventAlerts;
	@Inject private EventAlertOverlay eventAlertOverlay;
	@Inject private BingoEventOverlay bingoEventOverlay;
	@Inject private PartyTracker parties;
	@Inject private LootEventListener loot;
	@Inject private CollectionLogEventListener collectionLog;
	@Inject private PetEventListener pets;
	@Inject private CombatTierEventListener combatTiers;
	private NavigationButton navigation;
	private ScheduledFuture<?> refreshTask;
	private List<Object> registered;
	private volatile boolean featuresActive;
	private String firstConnectionSyncedAccount;
	private String activePlayerName;
	private final Runnable connectionListener = () -> clientThread.invokeLater(this::updateFeatureActivation);

	@Provides AnchorConfig provideConfig(ConfigManager manager) { return manager.getConfig(AnchorConfig.class); }

	@Override protected void startUp()
	{
		navigation = NavigationButton.builder().tooltip("The Anchor").icon(loadIcon()).priority(5).panel(panel).build(); toolbar.addNavigation(navigation);
		overlayManager.add(collectionLogSyncOverlay);
		overlayManager.add(eventAlertOverlay);
		overlayManager.add(bingoEventOverlay);
		registered = Arrays.asList(parties, loot, collectionLog, collectionLogSync, pets, combatTiers, pbs);
		data.addListener(connectionListener);
		data.validate();
		evidence.pruneUploaded(config.retentionDays());
		if (client.getGameState() == GameState.LOGGED_IN) onLoggedIn();
		refreshTask = executor.scheduleAtFixedRate(this::runScheduledRefresh,
			PROFILE_AND_COMPETITIONS_REFRESH_MINUTES,
			PROFILE_AND_COMPETITIONS_REFRESH_MINUTES,
			TimeUnit.MINUTES);
		log.info("The Anchor plugin started");
	}

	@Override protected void shutDown()
	{
		if (refreshTask != null) { refreshTask.cancel(false); refreshTask = null; }
		deactivateFeatures();
		registered = null;
		data.removeListener(connectionListener);
		if (navigation != null) { toolbar.removeNavigation(navigation); navigation = null; }
		overlayManager.remove(collectionLogSyncOverlay);
		overlayManager.remove(eventAlertOverlay);
		overlayManager.remove(bingoEventOverlay);
		eventAlerts.resetStateForWorldHopOrLogin();
		bingo.clear();
		activePlayerName = null;
		firstConnectionSyncedAccount = null;
		data.loggedOut(); log.info("The Anchor plugin stopped");
	}

	@Subscribe public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING
			|| event.getGameState() == GameState.CONNECTION_LOST)
		{
			eventAlerts.resetStateForWorldHopOrLogin();
		}
		if (event.getGameState() == GameState.LOGGED_IN) tryOnLoggedIn();
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			deactivateFeatures();
			activePlayerName = null;
			firstConnectionSyncedAccount = null;
			bingo.clear();
			data.loggedOut();
		}
	}

	@Subscribe public void onGameTick(GameTick event)
	{
		tryOnLoggedIn();
		if (featuresActive) eventAlerts.onGameTick();
	}

	@Subscribe public void onConfigChanged(ConfigChanged event)
	{
		if (!AnchorConfig.GROUP.equals(event.getGroup())) return;
		switch (event.getKey())
		{
			case "authenticationCode": firstConnectionSyncedAccount = null; data.validate(); updateFeatureActivation(); break;
			case "animatedBanner": panel.refreshBanner(); break;
			case "eventAlerts": eventAlerts.resetStateForWorldHopOrLogin(); break;
			case "refreshProfile": if (Boolean.parseBoolean(event.getNewValue())) { refreshCurrentPlayer(); resetAction(event.getKey()); } break;
			case "retryOutbox": if (Boolean.parseBoolean(event.getNewValue())) { if (featuresActive) pipeline.retryAll(); resetAction(event.getKey()); } break;
			case "clearImageCache": if (Boolean.parseBoolean(event.getNewValue())) { imageCache.clear(); refreshCurrentPlayer(); resetAction(event.getKey()); } break;
			default: break;
		}
	}

	/** Keeps legacy action config items usable without leaving a checked state behind. */
	private void resetAction(String key) { configManager.setConfiguration(AnchorConfig.GROUP, key, false); }

	private void onLoggedIn() { tryOnLoggedIn(); }
	private void tryOnLoggedIn()
	{
		if (client.getGameState() != GameState.LOGGED_IN) return;
		String name = currentName();
		if (name == null || name.isBlank()) return;
		if (name.equals(activePlayerName)) { updateFeatureActivation(); return; }
		activePlayerName = name;
		firstConnectionSyncedAccount = null;
		data.refresh(name);
		updateFeatureActivation();
	}

	private void updateFeatureActivation()
	{
		String name = currentName();
		boolean shouldBeActive = client.getGameState() == GameState.LOGGED_IN
			&& name != null && !name.isBlank()
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
			for (Object listener : registered) eventBus.register(listener);
			collectionLogAutoSync.startUp();
			collectionLogRefreshButton.startUp();
			bingo.refresh();
			pbs.onLogin();
			pipeline.retryAll();
			pipeline.refreshStatuses(name);
			log.info("The Anchor features activated for clan member {}", name);
		}
		syncFirstConnectionData();
	}

	private void deactivateFeatures()
	{
		if (!featuresActive) return;
		featuresActive = false;
		if (registered != null) for (Object listener : registered) eventBus.unregister(listener);
		collectionLogRefreshButton.shutDown();
		collectionLogAutoSync.shutDown();
		firstConnectionSyncedAccount = null;
		eventAlerts.resetStateForWorldHopOrLogin();
		bingo.clear();
		log.info("The Anchor features deactivated because the current RSN is not an authenticated clan member");
	}

	private void syncFirstConnectionData()
	{
		if (!featuresActive || !data.isCurrentPlayerClanMember()
			|| data.connection() != AnchorDataService.Connection.CONNECTED
			|| client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) return;
		String account = Long.toString(client.getAccountHash());
		if ("0".equals(account)) { log.debug("First connection sync is waiting for the account hash"); return; }
		if (account.equals(firstConnectionSyncedAccount)) return;
		firstConnectionSyncedAccount = account;
		log.info("First Anchor connection ready for {}; scheduling CA, skill/XP, and PB sync", currentName());
		clientThread.invokeLater(progress::syncAll);
		collectionLogSync.retryPending();
		executor.execute(() -> pbs.syncAll(true));
	}
	private void refreshCurrentPlayer()
	{
		String name = currentName();
		if (name == null || name.isBlank()) return;
		data.refresh(name);
		if (featuresActive) { bingo.refresh(); pipeline.refreshStatuses(name); }
	}
	private void runScheduledRefresh()
	{
		try { refreshCurrentPlayer(); }
		catch (RuntimeException e) { log.warn("Unable to refresh Anchor profile and competitions", e); }
	}
	private String currentName() { return client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName(); }

	private BufferedImage loadIcon()
	{
		try { return ImageUtil.loadImageResource(getClass(), "anchor_icon.png"); }
		catch (IllegalArgumentException e)
		{
			BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB); Graphics2D g = image.createGraphics();
			g.setColor(new Color(28, 38, 52)); g.fillOval(0, 0, 16, 16); g.setColor(new Color(207, 164, 76)); g.drawLine(8, 2, 8, 13); g.drawArc(3, 7, 10, 7, 180, 180); g.dispose(); return image;
		}
	}
}
