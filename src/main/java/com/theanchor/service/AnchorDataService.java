package com.theanchor.service;

import com.theanchor.AnchorConfig;
import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import java.awt.image.BufferedImage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
	private volatile AnchorModels.Profile profile;
	private volatile AnchorModels.CompetitionPanels competitionPanels;
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
	public AnchorModels.Profile profile() { return profile; }
	public AnchorModels.CompetitionPanels competitionPanels() { return competitionPanels; }
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
