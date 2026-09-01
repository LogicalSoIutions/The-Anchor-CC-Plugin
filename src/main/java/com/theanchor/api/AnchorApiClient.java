/*
 * Portions adapted from the Llama Club and PB Tracker Sync HTTP clients.
 * See LICENSES/llama-LICENSE.txt and LICENSES/pb-tracker-sync-LICENSE.txt.
 */
package com.theanchor.api;

import com.google.gson.Gson;
import com.theanchor.AnchorConfig;
import com.theanchor.model.AnchorModels;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import net.runelite.client.RuneLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class AnchorApiClient
{
	public static final String ANCHOR_ORIGIN = "https://the-anchor.cc";
	private static final Logger log = LoggerFactory.getLogger(AnchorApiClient.class);
	public static final String PLUGIN_VERSION = "1.0.0";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private final OkHttpClient http;
	private final Gson gson;
	private final AnchorConfig config;
	private final String origin;
	private final ScheduledExecutorService executor;
	private final Path requestJournal;
	private final Object requestJournalLock = new Object();

	@Inject
	public AnchorApiClient(OkHttpClient http, Gson gson, AnchorConfig config, ScheduledExecutorService executor)
	{
		this(http, gson, config, ANCHOR_ORIGIN, executor,
			RuneLite.RUNELITE_DIR.toPath().resolve("the-anchor").resolve("debug").resolve("api-requests.jsonl"));
	}

	// Visible to package tests only; production requests always use ANCHOR_ORIGIN.
	AnchorApiClient(OkHttpClient http, Gson gson, AnchorConfig config, String origin)
	{
		this(http, gson, config, origin, null, null);
	}

	AnchorApiClient(OkHttpClient http, Gson gson, AnchorConfig config, String origin,
		ScheduledExecutorService executor, Path requestJournal)
	{
		this.http = http.newBuilder().followRedirects(false).followSslRedirects(false).build();
		this.gson = gson;
		this.config = config;
		this.origin = origin.replaceAll("/+$", "");
		this.executor = executor;
		this.requestJournal = requestJournal;
	}

	public interface ResultCallback<T> { void complete(ApiResult<T> result); }

	public static final class ApiResult<T>
	{
		public final int statusCode;
		public final T value;
		public final String error;
		private ApiResult(int statusCode, T value, String error) { this.statusCode = statusCode; this.value = value; this.error = error; }
		public static <T> ApiResult<T> ok(int code, T value) { return new ApiResult<>(code, value, null); }
		public static <T> ApiResult<T> error(int code, String error) { return new ApiResult<>(code, null, error); }
		public boolean isSuccessful() { return statusCode >= 200 && statusCode < 300; }
		public boolean isRetryable() { return statusCode == 0 || statusCode == 429 || statusCode >= 500; }
	}

	public void getProfile(String name, ResultCallback<AnchorModels.Profile> callback)
	{
		get("/api/clan/profile?name=" + encode(name), false, AnchorModels.Profile.class, callback);
	}

	public void getCompetitions(ResultCallback<AnchorModels.Competitions> callback)
	{
		get("/api/competitions", false, AnchorModels.Competitions.class, callback);
	}

	public void getCompetitionPanels(ResultCallback<AnchorModels.CompetitionPanels> callback)
	{
		get("/api/competitions/panels", false, AnchorModels.CompetitionPanels.class, callback);
	}

	public void validateSession(ResultCallback<Map> callback) { get("/api/runelite/session", true, Map.class, callback); }
	public void getRules(ResultCallback<AnchorModels.Rules> callback) { get("/api/runelite/rules", true, AnchorModels.Rules.class, callback); }
	public void getBingo(ResultCallback<AnchorModels.BingoEvent> callback)
	{
		get("/api/runelite/bingo", true, AnchorModels.BingoEvent.class, callback);
	}
	public void getBingoRules(ResultCallback<AnchorModels.BingoRules> callback)
	{
		get("/api/runelite/bingo/rules", true, AnchorModels.BingoRules.class, callback);
	}

	public void uploadEvent(AnchorModels.EventEnvelope metadata, Path screenshot, String format,
		ResultCallback<AnchorModels.EventResponse> callback)
	{
		try
		{
			String metadataJson = gson.toJson(metadata);
			MultipartBody.Builder bodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM)
				.addFormDataPart("metadata", null, RequestBody.create(JSON, metadataJson));
			long screenshotBytes = 0;
			if (screenshot != null)
			{
				byte[] bytes = Files.readAllBytes(screenshot);
				screenshotBytes = bytes.length;
				MediaType imageType = MediaType.parse("image/" + ("jpg".equals(format) ? "jpeg" : format));
				bodyBuilder.addFormDataPart("screenshot", screenshot.getFileName().toString(), RequestBody.create(imageType, bytes));
			}
			MultipartBody body = bodyBuilder.build();
			Map<String, Object> journalPayload = new LinkedHashMap<>();
			journalPayload.put("metadata", metadataJson);
			journalPayload.put("screenshotFile", screenshot == null ? null : screenshot.toString());
			journalPayload.put("screenshotBytes", screenshotBytes);
			journalPayload.put("format", format);
			execute(newRequest("/api/runelite/events", true).post(body).build(), AnchorModels.EventResponse.class,
				callback, gson.toJson(journalPayload));
		}
		catch (IOException e)
		{
			log.error("Could not read evidence screenshot {}", screenshot, e);
			callback.complete(ApiResult.error(0, "Could not read evidence screenshot"));
		}
	}

	public void syncPbs(AnchorModels.PbBulkRequest request, boolean bulk, ResultCallback<Map> callback)
	{
		postJson(bulk ? "/api/runelite/personal-bests/bulk" : "/api/runelite/personal-bests", request, Map.class, callback);
	}

	public void syncPlayerProgress(AnchorModels.PlayerProgressRequest request, ResultCallback<Map> callback)
	{
		Runnable send = () -> postJson("/api/runelite/player-progress", request, Map.class, callback);
		if (executor == null) send.run();
		else executor.execute(send);
	}

	public void syncCollectionLog(AnchorModels.CollectionLogRequest request, ResultCallback<Map> callback)
	{
		postJson("/api/runelite/collection-log", request, Map.class, callback);
	}

	public void updateSubmission(String id, int partySize, int clanMembers, int nonClanMembers, String notes, ResultCallback<Map> callback)
	{
		postJsonWithMethod("/api/runelite/submissions/" + encode(id), Map.of("submittedPartySize", partySize,
			"submittedClanMemberCount", clanMembers, "submittedNonClanMemberCount", nonClanMembers, "notes", notes),
			"PATCH", Map.class, callback);
	}

	public void submit(String id, ResultCallback<Map> callback)
	{
		postJson("/api/runelite/submissions/submit", Map.of("submissionId", id), Map.class, callback);
	}

	public void getSubmissions(String player, ResultCallback<AnchorModels.SubmissionSummary[]> callback)
	{
		get("/api/runelite/submissions?player=" + encode(player), true, AnchorModels.SubmissionSummary[].class, callback);
	}

	private <T> void get(String path, boolean authenticated, Class<T> type, ResultCallback<T> callback)
	{
		execute(newRequest(path, authenticated).get().build(), type, callback, null);
	}

	private <T> void postJson(String path, Object value, Class<T> type, ResultCallback<T> callback)
	{
		postJsonWithMethod(path, value, "POST", type, callback);
	}

	private <T> void postJsonWithMethod(String path, Object value, String method, Class<T> type, ResultCallback<T> callback)
	{
		String json = gson.toJson(value);
		if ("/api/runelite/player-progress".equals(path))
		{
			if (json.contains("\"name\":null") || json.contains("\"name\":\"\""))
			{
				log.error("Player-progress payload contains a null or blank name before POST");
			}
		}
		RequestBody body = RequestBody.create(JSON, json);
		execute(newRequest(path, true).method(method, body).build(), type, callback, json);
	}

	private Request.Builder newRequest(String path, boolean authenticated)
	{
		Request.Builder builder = new Request.Builder().url(baseUrl() + path)
			.header("Accept", "application/json")
			.header("X-Anchor-Plugin-Version", PLUGIN_VERSION);
		if (authenticated)
		{
			String code = config.authenticationCode() == null ? "" : config.authenticationCode().trim();
			if (!code.isEmpty()) { builder.header("Authorization", "Bearer " + code); }
		}
		return builder;
	}

	private <T> void execute(Request request, Class<T> type, ResultCallback<T> callback, String journalPayload)
	{
		String requestId = journalRequest(request, journalPayload);
		long startedAt = System.nanoTime();
		http.newCall(request).enqueue(new Callback()
		{
			@Override public void onFailure(Call call, IOException e)
			{
				journalResponse(requestId, request, startedAt, 0, null, e.getMessage());
				log.error("Anchor API request failed: {} {}", request.method(), request.url().encodedPath(), e);
				callback.complete(ApiResult.error(0, "Network unavailable"));
			}
			@Override public void onResponse(Call call, Response response)
			{
				String body = "";
				try (Response ignored = response)
				{
					body = response.body() == null ? "" : response.body().string();
					if (!response.isSuccessful() && response.code() != 409)
					{
						journalResponse(requestId, request, startedAt, response.code(), body, safeError(response.code()));
						log.error("Anchor API returned HTTP {} for {} {}: {}", response.code(), request.method(),
							request.url().encodedPath(), responsePreview(body));
						callback.complete(ApiResult.error(response.code(), safeError(response.code())));
						return;
					}
					T parsed = body.isBlank() ? null : gson.fromJson(body, type);
					journalResponse(requestId, request, startedAt, response.code() == 409 ? 200 : response.code(), body, null);
					callback.complete(ApiResult.ok(response.code() == 409 ? 200 : response.code(), parsed));
				}
				catch (IOException | RuntimeException e)
				{
					journalResponse(requestId, request, startedAt, response.code(), body, "Invalid server response");
					log.error("Invalid Anchor API response for {} {} (HTTP {}, content-type {}): {}", request.method(),
						request.url().encodedPath(), response.code(), response.header("Content-Type"), responsePreview(body), e);
					callback.complete(ApiResult.error(0, "Invalid server response"));
				}
			}
		});
	}

	private String journalRequest(Request request, String payload)
	{
		if (!debugJournalEnabled()) return null;
		String requestId = UUID.randomUUID().toString();
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("event", "request");
		entry.put("requestId", requestId);
		entry.put("timestamp", Instant.now().toString());
		entry.put("thread", Thread.currentThread().getName());
		entry.put("method", request.method());
		entry.put("url", request.url().toString());
		entry.put("authenticated", request.header("Authorization") != null);
		entry.put("payload", payload);
		entry.put("payloadBytes", payload == null ? 0 : payload.getBytes(StandardCharsets.UTF_8).length);
		writeJournal(entry);
		return requestId;
	}

	private void journalResponse(String requestId, Request request, long startedAt, int statusCode,
		String responseBody, String error)
	{
		if (requestId == null) return;
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("event", "response");
		entry.put("requestId", requestId);
		entry.put("timestamp", Instant.now().toString());
		entry.put("thread", Thread.currentThread().getName());
		entry.put("method", request.method());
		entry.put("url", request.url().toString());
		entry.put("statusCode", statusCode);
		entry.put("durationMillis", Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
		entry.put("responsePreview", responseBody == null ? null : responsePreview(responseBody));
		entry.put("error", error);
		writeJournal(entry);
	}

	private boolean debugJournalEnabled()
	{
		return executor != null && requestJournal != null && config.debugRequestJournal();
	}

	private void writeJournal(Map<String, Object> entry)
	{
		try
		{
			executor.execute(() ->
			{
				try
				{
					synchronized (requestJournalLock)
					{
						Files.createDirectories(requestJournal.getParent());
						Files.writeString(requestJournal, gson.toJson(entry) + System.lineSeparator(), StandardCharsets.UTF_8,
							java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
					}
				}
				catch (IOException e)
				{
					log.warn("Could not write The Anchor API debug journal {}", requestJournal, e);
				}
			});
		}
		catch (RuntimeException e)
		{
			log.warn("Could not schedule The Anchor API debug journal write", e);
		}
	}

	private String responsePreview(String body)
	{
		if (body == null || body.isBlank()) return "<empty body>";
		String safe = body.replace('\r', ' ').replace('\n', ' ');
		String code = config.authenticationCode() == null ? "" : config.authenticationCode().trim();
		if (!code.isEmpty()) safe = safe.replace(code, "[redacted]");
		return safe.length() <= 2_000 ? safe : safe.substring(0, 2_000) + "… [truncated]";
	}

	private String baseUrl()
	{
		return origin;
	}

	private static String encode(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20"); }
	private static String safeError(int status)
	{
		if (status == 401 || status == 403) return "Authentication code is invalid";
		if (status == 422) return "The server rejected this draft";
		if (status == 429) return "The server is busy; retry queued";
		return status >= 500 ? "The Anchor is temporarily unavailable" : "Request failed (HTTP " + status + ")";
	}
}
