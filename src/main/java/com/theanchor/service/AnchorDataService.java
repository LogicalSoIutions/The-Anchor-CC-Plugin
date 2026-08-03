package com.theanchor.service;

import com.theanchor.AnchorConfig;
import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class AnchorDataService
{
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
	private volatile long botwImageCompetitionId = Long.MIN_VALUE;
	private volatile long sotwImageCompetitionId = Long.MIN_VALUE;
	private volatile Connection connection = Connection.NOT_CONFIGURED;
	private volatile String message = "Log in to load your Anchor profile";

	public void addListener(Runnable listener) { listeners.add(listener); }
	public void removeListener(Runnable listener) { listeners.remove(listener); }
	public AnchorModels.Profile profile() { return profile; }
	public AnchorModels.CompetitionPanels competitionPanels() { return competitionPanels; }
	public BufferedImage profileImage() { return profileImage; }
	public BufferedImage botwImage() { return botwImage; }
	public BufferedImage sotwImage() { return sotwImage; }
	public Connection connection() { return connection; }
	public String message() { return message; }

	public void loggedOut() { profile = null; profileImage = null; message = "Log in to load your Anchor profile"; notifyListeners(); }

	public void refresh(String playerName)
	{
		if (playerName == null || playerName.isBlank()) { loggedOut(); return; }
		message = "Loading " + playerName + "…"; notifyListeners();
		api.getProfile(playerName, result ->
		{
			if (result.isSuccessful() && result.value != null)
			{
				profile = result.value; message = "Profile loaded";
				String supplied = profile.member == null ? null : profile.member.profileImageUrl;
				String url = profileImageUrl(config.apiBaseUrl(), playerName, supplied);
				images.loadProfile(playerName, url, image -> { profileImage = image; notifyListeners(); });
			}
			else { profile = null; message = result.statusCode == 404 ? "Not found in The Anchor roster" : result.error; }
			notifyListeners();
		});
		String base = config.apiBaseUrl() == null ? "" : config.apiBaseUrl().replaceAll("/+$", "");
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
				loadCompetitionImages(base, resolved);
				notifyListeners();
			});
		});
		rules.refresh(this::notifyListeners);
	}

	private static AnchorModels.CompetitionPanel resolvePanel(AnchorModels.CompetitionPanel panel,
		AnchorModels.CompetitionPair pair, String kind)
	{
		AnchorModels.Competition selected = selectCompetition(pair);
		if (selected == null) return panel;
		if (panel != null && panel.competitionId == selected.id)
		{
			panel.artworkUrl = selected.artworkUrl;
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

	private void loadCompetitionImages(String base, AnchorModels.CompetitionPanels panels)
	{
		loadCompetitionImage(base, "botw", panels == null ? null : panels.botw);
		loadCompetitionImage(base, "sotw", panels == null ? null : panels.sotw);
	}

	private void loadCompetitionImage(String base, String kind, AnchorModels.CompetitionPanel panel)
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
		String genericUrl = base + "/" + kind + ".png";
		if (competitionId <= 0)
		{
			images.loadWebsiteImage(kind, genericUrl, image -> setCompetitionImage(kind, competitionId, image));
			return;
		}
		String key = kind + "-" + competitionId;
		String artworkUrl = resolveUrl(base, panel.artworkUrl);
		if (artworkUrl == null)
		{
			images.loadWebsiteImage(key, genericUrl, image -> setCompetitionImage(kind, competitionId, image));
			return;
		}
		images.loadWebsiteImage(key, artworkUrl, genericUrl,
			image -> setCompetitionImage(kind, competitionId, image));
	}

	static String resolveUrl(String baseUrl, String supplied)
	{
		if (supplied == null || supplied.isBlank()) return null;
		try
		{
			URI uri = URI.create(supplied.trim().replace(" ", "%20"));
			return uri.isAbsolute() ? uri.toString() : URI.create(baseUrl.replaceAll("/+$", "") + "/").resolve(uri).toString();
		}
		catch (IllegalArgumentException e) { return null; }
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

	static String profileImageUrl(String baseUrl, String playerName, String supplied)
	{
		String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
		String path = supplied == null || supplied.isBlank()
			? URLEncoder.encode(playerName == null ? "" : playerName, StandardCharsets.UTF_8).replace("+", "%20") + "-picture.png"
			: supplied.trim();
		path = path.replace(" ", "%20");
		try
		{
			URI uri = URI.create(path);
			return uri.isAbsolute() ? uri.toString() : URI.create(base + "/").resolve(path).toString();
		}
		catch (IllegalArgumentException e) { return null; }
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
