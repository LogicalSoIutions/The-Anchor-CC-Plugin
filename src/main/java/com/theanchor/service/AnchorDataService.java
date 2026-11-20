package com.theanchor.service;

import com.theanchor.AnchorConfig;
import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import java.awt.image.BufferedImage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class AnchorDataService
{
	static final String ANCHOR_ORIGIN = AnchorApiClient.ANCHOR_ORIGIN;
	// Replace these with the public images shown when authentication is missing or invalid.
	static final String UNAUTHENTICATED_BOTW_IMAGE_URL = "";
	static final String UNAUTHENTICATED_SOTW_IMAGE_URL = "";

	public enum Connection { NOT_CONFIGURED, CHECKING, CONNECTED, INVALID, UNAVAILABLE }
	@Inject private AnchorApiClient api;
	@Inject private AnchorConfig config;
	@Inject private ImageCache images;
	@Inject private RulesService rules;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private static final long MEMBER_CACHE_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
	private final java.util.Map<String, CachedMemberProgress> memberProgressCache = new java.util.LinkedHashMap<>();
	private final java.util.Map<String, List<AnchorApiClient.ResultCallback<AnchorModels.MemberProgress>>> memberProgressRequests = new java.util.HashMap<>();

	private static final class CachedMemberProgress {
		private final AnchorModels.MemberProgress progress;
		private final long cachedAt = System.nanoTime();
		private CachedMemberProgress(AnchorModels.MemberProgress progress) { this.progress = progress; }
	}

	/** Public member summaries only; deduplicate concurrent clicks and retain at most 50 profiles. */
	public void loadMemberProgress(String name, AnchorApiClient.ResultCallback<AnchorModels.MemberProgress> callback) {
		String key = name.trim().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
		CachedMemberProgress cached;
		synchronized (memberProgressCache) {
			cached = memberProgressCache.get(key);
			if (cached != null && System.nanoTime() - cached.cachedAt >= MEMBER_CACHE_NANOS) {
				memberProgressCache.remove(key);
				cached = null;
			}
			if (cached == null) {
				List<AnchorApiClient.ResultCallback<AnchorModels.MemberProgress>> pending = memberProgressRequests.get(key);
				if (pending != null) { pending.add(callback); return; }
				pending = new java.util.ArrayList<>();
				pending.add(callback);
				memberProgressRequests.put(key, pending);
			}
		}
		if (cached != null) { callback.complete(AnchorApiClient.ApiResult.ok(200, cached.progress)); return; }
		api.getMemberProgress(name, result -> {
			List<AnchorApiClient.ResultCallback<AnchorModels.MemberProgress>> waiting;
			synchronized (memberProgressCache) {
				if (result.isSuccessful() && result.value != null) {
					memberProgressCache.put(key, new CachedMemberProgress(result.value));
					while (memberProgressCache.size() > 50) memberProgressCache.remove(memberProgressCache.keySet().iterator().next());
				}
				waiting = memberProgressRequests.remove(key);
			}
			if (waiting != null) for (AnchorApiClient.ResultCallback<AnchorModels.MemberProgress> listener : waiting) listener.complete(result);
		});
	}

	/** PvM Diary progress belongs to the linked Discord member and is shared across their accounts. */
	public void loadMemberDiary(List<String> names, AnchorApiClient.ResultCallback<AnchorModels.PvmDiaryStatus> callback) {
		loadMemberDiary(names.stream().filter(name -> name.matches("[A-Za-z0-9 _-]{1,12}")).iterator(), callback);
	}

	private void loadMemberDiary(Iterator<String> names, AnchorApiClient.ResultCallback<AnchorModels.PvmDiaryStatus> callback) {
		if (!names.hasNext()) {
			callback.complete(AnchorApiClient.ApiResult.error(404, "No linked PvM Diary profile"));
			return;
		}
		api.getPvmDiaryStatus(names.next(), result -> {
			if (result.statusCode == 404 && names.hasNext()) loadMemberDiary(names, callback);
			else callback.complete(result);
		});
	}

	private volatile AnchorModels.Profile profile;
	private volatile AnchorModels.Leaderboards leaderboards;
	private volatile String leaderboardMessage = "Rankings have not loaded yet";
	private boolean leaderboardsLoading;
	private volatile AnchorModels.CompetitionPanels competitionPanels;
	private volatile AnchorModels.PvmDiaryStatus pvmDiaryStatus;
	private volatile BufferedImage profileImage;
	private volatile BufferedImage botwImage;
	private volatile BufferedImage sotwImage;
	private volatile BufferedImage unauthenticatedBotwImage;
	private volatile BufferedImage unauthenticatedSotwImage;
	private volatile long botwImageCompetitionId = Long.MIN_VALUE;
	private volatile long sotwImageCompetitionId = Long.MIN_VALUE;
	private volatile Connection connection = Connection.NOT_CONFIGURED;
	private volatile String message = "Log in to load your Anchor profile";
	private volatile String requestedPlayerName;
	private volatile boolean currentPlayerClanMember;

	public void addListener(Runnable listener) { listeners.add(listener); }
	public void removeListener(Runnable listener) { listeners.remove(listener); }
	public AnchorModels.Leaderboards leaderboards() { return leaderboards; }
	public String leaderboardMessage() { return leaderboardMessage; }

	public synchronized void refreshLeaderboards()
	{
		if (leaderboardsLoading) return;
		leaderboardsLoading = true;
		if (leaderboards == null) leaderboardMessage = "Loading clan rankings…";
		notifyListeners();
		api.getLeaderboards(result -> {
			synchronized (this) { leaderboardsLoading = false; }
			if (result.isSuccessful() && result.value != null) {
				leaderboards = result.value;
				leaderboardMessage = "";
			} else {
				leaderboardMessage = leaderboards == null ? "Rankings are currently unavailable. Refresh will retry automatically."
					: "Refresh failed. Showing previously loaded rankings.";
			}
			notifyListeners();
		});
	}

	public AnchorModels.Profile profile() { return profile; }
	public AnchorModels.CompetitionPanels competitionPanels() { return competitionPanels; }
	public AnchorModels.PvmDiaryStatus pvmDiaryStatus() { return pvmDiaryStatus; }
	public BufferedImage profileImage() { return profileImage; }
	public BufferedImage botwImage() { return botwImage; }
	public BufferedImage sotwImage() { return sotwImage; }
	public BufferedImage unauthenticatedBotwImage() { return unauthenticatedBotwImage; }
	public BufferedImage unauthenticatedSotwImage() { return unauthenticatedSotwImage; }
	public Connection connection() { return connection; }
	public String message() { return message; }
	public boolean isCurrentPlayerClanMember() { return currentPlayerClanMember; }

	public void loggedOut()
	{
		requestedPlayerName = null;
		currentPlayerClanMember = false;
		profile = null;
		pvmDiaryStatus = null;
		profileImage = null;
		message = "Log in to load your Anchor profile";
		notifyListeners();
	}

	public void refresh(String playerName)
	{
		if (playerName == null || playerName.isBlank()) { loggedOut(); return; }
		String requestedName = playerName.trim();
		if (!samePlayer(requestedPlayerName, requestedName))
		{
			profile = null;
			profileImage = null;
			pvmDiaryStatus = null;
			currentPlayerClanMember = false;
		}
		requestedPlayerName = requestedName;
		message = "Loading " + playerName + "…"; notifyListeners();
		api.getProfile(playerName, result ->
		{
			// An account switch can complete before the previous profile request. Never
			// let that stale response activate the plugin for the newly logged-in RSN.
			if (!samePlayer(requestedPlayerName, requestedName)) return;
			if (result.isSuccessful() && result.value != null)
			{
				profile = result.value;
				// The profile endpoint is queried for requestedName and only returns a
				// member profile for rostered players. Do not make feature activation
				// depend on the optional/canonical member.name response field.
				currentPlayerClanMember = profile.member != null;
				message = currentPlayerClanMember ? "Profile loaded" : "Not found in The Anchor roster";
				if (currentPlayerClanMember)
				{
					loadPvmDiaryStatus(requestedName);
					String url = profileImageUrl(playerName);
					images.loadProfile(playerName, url, image ->
					{
						if (samePlayer(requestedPlayerName, requestedName)) profileImage = image;
						notifyListeners();
					});
				}
				else profile = null;
			}
			else
			{
				profile = null;
				currentPlayerClanMember = false;
				message = result.statusCode == 404 ? "Not found in The Anchor roster" : result.error;
			}
			notifyListeners();
		});
		api.getCompetitionPanels(panelResult ->
		{
			AnchorModels.CompetitionPanels panels = panelResult.isSuccessful() ? panelResult.value : null;
			api.getCompetitions(compResult ->
			{
				AnchorModels.CompetitionPanels resolved = panels;
				if (compResult.isSuccessful() && compResult.value != null)
				{
					if (resolved == null) resolved = new AnchorModels.CompetitionPanels();
					resolved.botw = resolvePanel(resolved.botw, compResult.value.botw, "BOTW");
					resolved.sotw = resolvePanel(resolved.sotw, compResult.value.sotw, "SOTW");
					resolved.rotm = resolvePanel(resolved.rotm, compResult.value.rotm, "ROTM");
				}
				competitionPanels = resolved;
				loadCompetitionImages(resolved);
				notifyListeners();
			});
		});
		loadUnauthenticatedCompetitionImages();
		rules.refresh(this::notifyListeners);
	}

	private void loadUnauthenticatedCompetitionImages()
	{
		if (!UNAUTHENTICATED_BOTW_IMAGE_URL.isBlank())
		{
			images.loadWebsiteImage("unauthenticated-botw", UNAUTHENTICATED_BOTW_IMAGE_URL,
				image -> { unauthenticatedBotwImage = image; notifyListeners(); });
		}
		if (!UNAUTHENTICATED_SOTW_IMAGE_URL.isBlank())
		{
			images.loadWebsiteImage("unauthenticated-sotw", UNAUTHENTICATED_SOTW_IMAGE_URL,
				image -> { unauthenticatedSotwImage = image; notifyListeners(); });
		}
	}

	private static AnchorModels.CompetitionPanel resolvePanel(AnchorModels.CompetitionPanel panel,
		AnchorModels.CompetitionPair pair, String kind)
	{
		AnchorModels.Competition selected = selectCompetition(pair);
		if (selected == null) return panel;
		if (panel != null && panel.competitionId == selected.id)
		{
			return panel;
		}
		return CompetitionService.convertToPanel(selected, kind);
	}

	static AnchorModels.Competition selectCompetition(AnchorModels.CompetitionPair pair)
	{
		if (pair == null) return null;
		if (pair.current != null) return pair.current;
		if (pair.upcoming != null) return pair.upcoming;
		return pair.previous;
	}

	private void loadCompetitionImages(AnchorModels.CompetitionPanels panels)
	{
		loadCompetitionImage("botw", panels == null ? null : panels.botw);
		loadCompetitionImage("sotw", panels == null ? null : panels.sotw);
	}

	private void loadPvmDiaryStatus(String playerName)
	{
		if (playerName == null || playerName.isBlank()) { pvmDiaryStatus = null; return; }
		api.getPvmDiaryStatus(playerName, result ->
		{
			if (!samePlayer(requestedPlayerName, playerName)) return;
			pvmDiaryStatus = result.isSuccessful() ? result.value : null;
			notifyListeners();
		});
	}

	static boolean samePlayer(String first, String second)
	{
		return normalizePlayerName(first).equals(normalizePlayerName(second));
	}

	private static String normalizePlayerName(String name)
	{
		return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT)
			.replace(" ", "").replace("_", "").replace("-", "");
	}

	private void loadCompetitionImage(String kind, AnchorModels.CompetitionPanel panel)
	{
		long competitionId = panel == null ? 0 : panel.competitionId;
		if ("botw".equals(kind))
		{
			if (botwImageCompetitionId != competitionId) botwImage = null;
			botwImageCompetitionId = competitionId;
		}
		else
		{
			if (sotwImageCompetitionId != competitionId) sotwImage = null;
			sotwImageCompetitionId = competitionId;
		}
		String url = ANCHOR_ORIGIN + "/" + kind + ".png";
		if (competitionId <= 0) { images.loadWebsiteImage(kind, url, image -> setCompetitionImage(kind, competitionId, image)); return; }
		String key = kind + "-" + competitionId;
		images.loadWebsiteImage(key, url, image -> setCompetitionImage(kind, competitionId, image));
	}

	private void setCompetitionImage(String kind, long competitionId, BufferedImage image)
	{
		if ("botw".equals(kind))
		{
			if (botwImageCompetitionId == competitionId) botwImage = image;
		}
		else if (sotwImageCompetitionId == competitionId) sotwImage = image;
		notifyListeners();
	}

	static String profileImageUrl(String playerName)
	{
		return ANCHOR_ORIGIN + "/api/profile-portrait?name="
			+ URLEncoder.encode(playerName == null ? "" : playerName, StandardCharsets.UTF_8).replace("+", "%20");
	}

	public void validate()
	{
		String code = config.authenticationCode() == null ? "" : config.authenticationCode().trim();
		if (code.isEmpty()) { connection = Connection.NOT_CONFIGURED; notifyListeners(); return; }
		connection = Connection.CHECKING; notifyListeners();
		api.validateSession(result ->
		{
			connection = result.isSuccessful() ? Connection.CONNECTED
				: result.statusCode == 401 || result.statusCode == 403 ? Connection.INVALID : Connection.UNAVAILABLE;
			notifyListeners();
		});
	}

	private void notifyListeners() { for (Runnable listener : listeners) listener.run(); }
}
