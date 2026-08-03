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
		api.getCompetitionPanels(result ->
		{
			if (result.isSuccessful() && result.value != null)
			{
				competitionPanels = result.value;
			}
			if (competitionPanels == null || competitionPanels.botw == null || competitionPanels.sotw == null)
			{
				api.getCompetitions(compResult ->
				{
					if (compResult.isSuccessful() && compResult.value != null)
					{
						if (competitionPanels == null) competitionPanels = new AnchorModels.CompetitionPanels();
						if (competitionPanels.botw == null && compResult.value.botw != null)
						{
							AnchorModels.Competition comp = compResult.value.botw.current != null ? compResult.value.botw.current : compResult.value.botw.previous;
							competitionPanels.botw = CompetitionService.convertToPanel(comp, "BOTW");
						}
						if (competitionPanels.sotw == null && compResult.value.sotw != null)
						{
							AnchorModels.Competition comp = compResult.value.sotw.current != null ? compResult.value.sotw.current : compResult.value.sotw.previous;
							competitionPanels.sotw = CompetitionService.convertToPanel(comp, "SOTW");
						}
					}
					notifyListeners();
				});
			}
			else
			{
				notifyListeners();
			}
		});
		images.loadWebsiteImage("botw", base + "/botw.png", image -> { botwImage = image; notifyListeners(); });
		images.loadWebsiteImage("sotw", base + "/sotw.png", image -> { sotwImage = image; notifyListeners(); });
		rules.refresh(this::notifyListeners);
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
