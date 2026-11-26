/*
 * Portions adapted from the Llama Club and PB Tracker Sync HTTP clients.
 * See LICENSES/llama-LICENSE.txt and LICENSES/pb-tracker-sync-LICENSE.txt.
 */
package com.theanchor.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.theanchor.AnchorConfig;
import com.theanchor.model.AnchorModels;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import net.runelite.client.util.Filepath;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class AnchorApiClient
{
	public static final String ANCHOR_ORIGIN = "https://the-anchor.cc";
	private static final Logger log = LoggerFactory.getLogger(AnchorApiClient.class);
	public static final String PLUGIN_VERSION = "1.2.8";
	public static final String IDENTITY_REVIEW_MESSAGE = "Account name change needs clan admin review. Your upload is saved.";
	private static final String IDENTITY_REVIEW_CODE = "account_identity_review_required";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	static final String COLLECTION_LOG_PATH = "/api/runelite/collection-log";
	static final String PLAYER_PROGRESS_PATH = "/api/runelite/player-progress";
	static final String PERSONAL_BEST_PATH = "/api/runelite/personal-bests";
	static final String PERSONAL_BEST_BULK_PATH = "/api/runelite/personal-bests/bulk";
	private final OkHttpClient http;
	private final Gson gson;
	private final AnchorConfig config;
	private final String origin;
	private final ScheduledExecutorService executor;
	private final Filepath requestJournal;
	private final Object requestJournalLock = new Object();

	@Inject
	public AnchorApiClient(OkHttpClient http, Gson gson, AnchorConfig config, ScheduledExecutorService executor,
		@Named("anchorDirectory") Filepath directory)
	{
		this(http, gson, config, ANCHOR_ORIGIN, executor,
			directory.joinSegment("debug").joinSegment("api-requests.jsonl"));
	}

	// Visible to package tests only; production requests always use ANCHOR_ORIGIN.
	AnchorApiClient(OkHttpClient http, Gson gson, AnchorConfig config, String origin)
	{
		this(http, gson, config, origin, null, null);
	}

	AnchorApiClient(OkHttpClient http, Gson gson, AnchorConfig config, String origin,
		ScheduledExecutorService executor, Filepath requestJournal)
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
		public final String code;
		public final String action;
		public final boolean idempotentReplay;
		private final Boolean retryable;

		private ApiResult(int statusCode, T value, String error, String code, String action,
			Boolean retryable, boolean idempotentReplay)
		{
			this.statusCode = statusCode;
			this.value = value;
			this.error = error;
			this.code = code;
			this.action = action;
			this.retryable = retryable;
			this.idempotentReplay = idempotentReplay;
		}

		public static <T> ApiResult<T> ok(int code, T value)
		{
			return new ApiResult<>(code, value, null, null, null, null, false);
		}

		public static <T> ApiResult<T> error(int code, String error)
		{
			return new ApiResult<>(code, null, error, null, null, null, false);
		}

		public boolean isSuccessful() { return statusCode >= 200 && statusCode < 300 && error == null; }

		public boolean needsIdentityReview()
		{
			return statusCode == 403 && IDENTITY_REVIEW_CODE.equals(code);
		}

		public boolean isRetryable()
		{
			return !needsIdentityReview() && !Boolean.FALSE.equals(retryable)
				&& (statusCode == 0 || statusCode == 429 || statusCode >= 500
					|| (statusCode >= 200 && statusCode < 300 && error != null));
		}
	}

	public void getProfile(String name, ResultCallback<AnchorModels.Profile> callback)
	{
		get("/api/clan/profile?name=" + encode(name), false, AnchorModels.Profile.class, callback);
	}

	public void getMemberProgress(String name, ResultCallback<AnchorModels.MemberProgress> callback)
	{
		get("/api/clan/progress?summary=true&name=" + encode(name), false, AnchorModels.MemberProgress.class, callback);
	}

	public void getLeaderboards(ResultCallback<AnchorModels.Leaderboards> callback)
	{
		get("/api/clan/leaderboards", false, AnchorModels.Leaderboards.class, callback);
	}

	public void getCompetitions(ResultCallback<AnchorModels.Competitions> callback)
	{
		get("/api/competitions", false, AnchorModels.Competitions.class, callback);
	}

	public void getCompetitionPanels(ResultCallback<AnchorModels.CompetitionPanels> callback)
	{
		get("/api/competitions/panels", false, AnchorModels.CompetitionPanels.class, callback);
	}

	public void getPvmDiaryStatus(String rsn, ResultCallback<AnchorModels.PvmDiaryStatus> callback)
	{
		get("/api/pvm-diary/status?name=" + encode(rsn), false, AnchorModels.PvmDiaryStatus.class, callback);
	}

	public void getPvmDiaryContract(ResultCallback<AnchorModels.PvmDiaryContract> callback)
	{
		get("/api/runelite/pvm-diary/contract", true, AnchorModels.PvmDiaryContract.class, callback);
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

	public void uploadEvent(AnchorModels.EventEnvelope metadata, Filepath screenshot, String format,
		ResultCallback<AnchorModels.EventResponse> callback)
	{
		String json = "diary".equals(metadata.eventType)
			? gson.newBuilder().serializeNulls().create().toJson(metadata) : gson.toJson(metadata);
		uploadEventJson(json, screenshot, format, callback);
	}

	public void uploadEventJson(String metadataJson, Filepath screenshot, String format,
		ResultCallback<AnchorModels.EventResponse> callback)
	{
		try
		{
			MultipartBody.Builder bodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM)
				.addFormDataPart("metadata", null, RequestBody.create(JSON, metadataJson));
			long screenshotBytes = 0;
			if (screenshot != null)
			{
				screenshotBytes = screenshot.size();
				MediaType imageType = MediaType.parse("image/" + ("jpg".equals(format) ? "jpeg" : format));
				bodyBuilder.addFormDataPart("screenshot", screenshot.getFileName(), new RequestBody()
				{
					@Override public MediaType contentType() { return imageType; }
					@Override public long contentLength() throws IOException { return screenshot.size(); }
					@Override public void writeTo(okio.BufferedSink sink) throws IOException
					{
						try (okio.Source source = okio.Okio.source(screenshot.openInputStream())) { sink.writeAll(source); }
					}
				});
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
		postJson(bulk ? PERSONAL_BEST_BULK_PATH : PERSONAL_BEST_PATH, request, Map.class, callback);
	}

	public void syncPlayerProgress(AnchorModels.PlayerProgressRequest request, ResultCallback<Map> callback)
	{
		Runnable send = () -> postJson(PLAYER_PROGRESS_PATH, request, Map.class, callback);
		if (executor == null) send.run();
		else executor.execute(send);
	}

	public void syncCollectionLog(AnchorModels.CollectionLogRequest request, ResultCallback<Map> callback)
	{
		postJson(COLLECTION_LOG_PATH, request, Map.class, callback);
	}

	void syncSnapshot(String path, JsonObject request, ResultCallback<Map> callback)
	{
		postJson(path, request, Map.class, callback);
	}

	public void updateSubmission(String id, int partySize, int clanMembers, int nonClanMembers,
		List<AnchorModels.PartyMember> partyMembers, String notes, ResultCallback<Map> callback)
	{
		postJsonWithMethod("/api/runelite/submissions/" + encode(id), Map.of("submittedPartySize", partySize,
			"submittedClanMemberCount", clanMembers, "submittedNonClanMemberCount", nonClanMembers,
			"submittedPartyMembers", partyMembers == null ? List.of() : partyMembers, "notes", notes),
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
		String json = value instanceof JsonObject ? value.toString() : gson.toJson(value);
		if (PLAYER_PROGRESS_PATH.equals(path))
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
				ApiResult<T> result;
				try (Response ignored = response)
				{
					body = response.body() == null ? "" : response.body().string();
					result = response.isSuccessful()
						? ApiResult.ok(response.code(), body.isBlank() ? null : gson.fromJson(body, type))
						: parseError(response.code(), body, type);
				}
				catch (IOException | RuntimeException e)
				{
					log.error("Invalid Anchor API response for {} {} (HTTP {}, content-type {}): {}", request.method(),
						request.url().encodedPath(), response.code(), response.header("Content-Type"), responsePreview(body), e);
					result = ApiResult.error(response.code(),
						response.isSuccessful() ? "Invalid server response" : safeError(response.code()));
				}
				journalResponse(requestId, request, startedAt, response.code(), body, result.error);
				if (!result.isSuccessful() && !result.idempotentReplay)
				{
					log.error("Anchor API returned HTTP {} for {} {}: {}", response.code(), request.method(),
						request.url().encodedPath(), responsePreview(body));
				}
				callback.complete(result);
			}
		});
	}

	private <T> ApiResult<T> parseError(int status, String body, Class<T> type)
	{
		JsonObject fields;
		try
		{
			JsonElement parsed = new JsonParser().parse(body);
			if (!parsed.isJsonObject()) return ApiResult.error(status, safeError(status));
			fields = parsed.getAsJsonObject();
		}
		catch (RuntimeException e)
		{
			return ApiResult.error(status, safeError(status));
		}

		String code = stringField(fields, "code");
		String action = stringField(fields, "action");
		Boolean retryable = booleanField(fields, "retryable");
		if (status == 409 && type == AnchorModels.EventResponse.class
			&& Boolean.TRUE.equals(booleanField(fields, "idempotentReplay")))
		{
			return new ApiResult<>(status, gson.fromJson(fields, type), null, code, action, retryable, true);
		}
		String error = safeError(status);
		if (status == 403 && IDENTITY_REVIEW_CODE.equals(code))
		{
			String detail = stringField(fields, "error");
			error = detail == null || detail.isBlank() ? "Contact a clan admin to review this character link." : detail;
		}
		return new ApiResult<>(status, null, error, code, action, retryable, false);
	}

	private static String stringField(JsonObject fields, String name)
	{
		JsonElement value = fields.get(name);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
			? value.getAsString() : null;
	}

	private static Boolean booleanField(JsonObject fields, String name)
	{
		JsonElement value = fields.get(name);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
			? value.getAsBoolean() : null;
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
						requestJournal.getParent().createDirectories();
						requestJournal.write(gson.toJson(entry) + System.lineSeparator(),
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
