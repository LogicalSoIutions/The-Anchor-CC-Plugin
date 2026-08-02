/*
 * Portions adapted from the Terpinheimer remote-image loading code.
 * See LICENSES/terpinheimer-LICENSE.txt.
 */
package com.theanchor.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Singleton
public class ImageCache
{
	private static final long PROFILE_TTL = Duration.ofHours(24).toMillis();
	private final Path root = RuneLite.RUNELITE_DIR.toPath().resolve("the-anchor").resolve("cache");
	private final OkHttpClient http;
	private final ScheduledExecutorService executor;

	@Inject
	public ImageCache(OkHttpClient http, ScheduledExecutorService executor) { this.http = http; this.executor = executor; }

	public void loadProfile(String rsn, String url, Consumer<BufferedImage> callback)
	{
		String urlKey = url == null ? "none" : Integer.toHexString(url.hashCode());
		load(root.resolve("profiles").resolve(safeName(rsn) + "-" + urlKey + ".img"), url, PROFILE_TTL, false, callback);
	}

	public void loadWebsiteImage(String key, String url, Consumer<BufferedImage> callback)
	{
		load(root.resolve("website-images").resolve(safeName(key) + ".img"), url, 0, true, callback);
	}

	private void load(Path target, String url, long ttl, boolean revalidate, Consumer<BufferedImage> callback)
	{
		BufferedImage cached = read(target);
		if (cached != null) callback.accept(cached);
		boolean fresh = false;
		try { fresh = Files.exists(target) && System.currentTimeMillis() - Files.getLastModifiedTime(target).toMillis() < ttl; }
		catch (IOException ignored) { }
		if (fresh || url == null || url.isBlank()) { if (cached == null) callback.accept(null); return; }
		Request request;
		try
		{
			Request.Builder builder = new Request.Builder().url(url).get();
			if (revalidate) builder.header("Cache-Control", "no-cache, no-store").header("Pragma", "no-cache");
			request = builder.build();
		}
		catch (IllegalArgumentException e) { if (cached == null) callback.accept(null); return; }
		http.newCall(request).enqueue(new Callback()
		{
			@Override public void onFailure(Call call, IOException e) { if (cached == null) callback.accept(null); }
			@Override public void onResponse(Call call, Response response)
			{
				try (Response ignored = response)
				{
					if (!response.isSuccessful() || response.body() == null) { if (cached == null) callback.accept(null); return; }
					Files.createDirectories(target.getParent());
					Path temp = target.resolveSibling(target.getFileName() + ".tmp");
					Files.write(temp, response.body().bytes());
					BufferedImage downloaded = read(temp);
					if (downloaded == null) { Files.deleteIfExists(temp); if (cached == null) callback.accept(null); return; }
					Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
					callback.accept(downloaded);
				}
				catch (IOException e) { if (cached == null) callback.accept(null); }
			}
		});
	}

	public void clear()
	{
		executor.execute(() -> deleteChildren(root));
	}

	private static void deleteChildren(Path path)
	{
		if (!Files.exists(path)) return;
		try (java.util.stream.Stream<Path> stream = Files.walk(path))
		{
			stream.sorted(java.util.Comparator.reverseOrder()).filter(p -> !p.equals(path)).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
		}
		catch (IOException ignored) { }
	}

	private static BufferedImage read(Path path)
	{
		if (!Files.isRegularFile(path)) return null;
		try { return ImageIO.read(path.toFile()); } catch (IOException e) { return null; }
	}

	public static String safeName(String value)
	{
		String normalized = value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
		return normalized.isEmpty() ? "unknown" : normalized;
	}
}
