/*
 * Portions adapted from the Terpinheimer remote-image loading code.
 * See LICENSES/terpinheimer-LICENSE.txt.
 */
package com.theanchor.service;

import java.awt.MediaTracker;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.ImageIcon;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Singleton
@Slf4j
public class ImageCache
{
	private static final long PROFILE_TTL = Duration.ofHours(24).toMillis();
	private static final String BANNER_URL = "https://the-anchor.cc/banner.gif";
	private static final int MAX_BANNER_BYTES = 8 * 1024 * 1024;
	private final Filepath root;
	private final OkHttpClient http;
	private final ScheduledExecutorService executor;

	@Inject
	public ImageCache(OkHttpClient http, ScheduledExecutorService executor, @Named("anchorDirectory") Filepath directory)
	{
		this.http = http.newBuilder().followRedirects(false).followSslRedirects(false).build();
		this.executor = executor;
		this.root = directory.joinSegment("cache");
	}

	public void loadProfile(String rsn, String url, Consumer<BufferedImage> callback)
	{
		String urlKey = url == null ? "none" : Integer.toHexString(url.hashCode());
		load(root.joinSegment("profiles").joinSegment(safeName(rsn) + "-" + urlKey + ".img"), url, PROFILE_TTL, false, callback);
	}

	/** Loads the full GIF, preserving animation instead of decoding only its first frame. */
	public void loadAnimatedBanner(Consumer<ImageIcon> callback)
	{
		executor.execute(() ->
		{
			Filepath target = root.joinSegment("banner.gif");
			ImageIcon cached = readBanner(target);
			if (cached != null)
			{
				log.info("Loaded animated Anchor banner from {}", target);
				callback.accept(cached);
				return;
			}
			Filepath temp = root.joinSegment("banner.gif.tmp");
			ImageIcon downloaded = null;
			log.info("Downloading animated Anchor banner from {}", BANNER_URL);
			try (Response response = http.newCall(new Request.Builder().url(BANNER_URL).get().build()).execute())
			{
				if (response.isSuccessful() && response.body() != null)
				{
					byte[] bytes;
					try (java.io.InputStream input = response.body().byteStream())
					{
						bytes = input.readNBytes(MAX_BANNER_BYTES + 1);
					}
					if (bytes.length > MAX_BANNER_BYTES || !isGif(bytes))
						throw new IOException("Banner response is not a GIF within the 8 MiB limit");
					root.createDirectories();
					temp.write(bytes);
					// Save independently of Swing decoding so a display failure cannot discard the download.
					temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
					log.info("Cached animated Anchor banner: {} bytes at {}", bytes.length, target);
					downloaded = readBanner(target);
				}
				else log.warn("Animated Anchor banner download failed: HTTP {} from {}", response.code(), BANNER_URL);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Unable to download or cache animated Anchor banner at {}", target, e);
				if (downloaded != null) downloaded.getImage().flush();
				downloaded = null;
			}
			finally
			{
				try { temp.deleteIfExists(); } catch (IOException ignored) { }
			}
			callback.accept(downloaded);
		});
	}

	private static ImageIcon readBanner(Filepath path)
	{
		if (!path.isFile()) return null;
		try (java.io.InputStream input = path.openInputStream())
		{
			byte[] bytes = input.readNBytes(MAX_BANNER_BYTES + 1);
			if (bytes.length > MAX_BANNER_BYTES || !isGif(bytes)) {
				log.warn("Invalid cached Anchor banner at {}", path);
				return null;
			}
			ImageIcon icon = new ImageIcon(bytes);
			// A GIF can report ABORTED after its initial load loses its temporary
			// MediaTracker observer. Painting it with the label's observer restarts
			// animation, so only reject actual errors or missing dimensions.
			if ((icon.getImageLoadStatus() & MediaTracker.ERRORED) == 0 && icon.getIconWidth() > 0 && icon.getIconHeight() > 0)
				return icon;
			log.warn("Unable to decode animated Anchor banner at {}: status={}, size={}x{}",
				path, icon.getImageLoadStatus(), icon.getIconWidth(), icon.getIconHeight());
			icon.getImage().flush();
		}
		catch (IOException | RuntimeException e) { log.warn("Unable to read animated Anchor banner at {}", path, e); }
		return null;
	}

	private static boolean isGif(byte[] bytes)
	{
		return bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
			&& bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a';
	}

	public void loadWebsiteImage(String key, String url, Consumer<BufferedImage> callback)
	{
		load(root.joinSegment("website-images").joinSegment(safeName(key) + ".img"), url, 0, true, callback);
	}

	public void loadWebsiteImage(String key, String url, String fallbackUrl, Consumer<BufferedImage> callback)
	{
		Filepath target = root.joinSegment("website-images").joinSegment(safeName(key) + ".img");
		load(target, url, 0, true, image ->
		{
			if (image != null) callback.accept(image);
			else load(target, fallbackUrl, 0, true, callback);
		});
	}

	private void load(Filepath target, String url, long ttl, boolean revalidate, Consumer<BufferedImage> callback)
	{
		executor.execute(() -> loadInBackground(target, url, ttl, revalidate, callback));
	}

	private void loadInBackground(Filepath target, String url, long ttl, boolean revalidate, Consumer<BufferedImage> callback)
	{
		BufferedImage cached = read(target);
		if (cached != null) callback.accept(cached);
		boolean fresh = false;
		try { fresh = target.exists() && System.currentTimeMillis() - target.getLastModifiedTime().toMillis() < ttl; }
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
					target.getParent().createDirectories();
					Filepath temp = target.getParent().joinSegment(target.getFileName() + ".tmp");
					temp.write(response.body().bytes());
					BufferedImage downloaded = read(temp);
					if (downloaded == null) { temp.deleteIfExists(); if (cached == null) callback.accept(null); return; }
					temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
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

	private static void deleteChildren(Filepath path)
	{
		if (!path.exists()) return;
		try (java.util.stream.Stream<Filepath> stream = path.walk())
		{
			stream.sorted(java.util.Comparator.reverseOrder()).filter(p -> !p.equals(path)).forEach(p -> { try { p.deleteIfExists(); } catch (IOException ignored) { } });
		}
		catch (IOException ignored) { }
	}

	private static BufferedImage read(Filepath path)
	{
		if (!path.isFile()) return null;
		try (java.io.InputStream input = path.openInputStream()) { return ImageIO.read(input); } catch (IOException e) { return null; }
	}

	public static String safeName(String value)
	{
		String normalized = value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
		return normalized.isEmpty() ? "unknown" : normalized;
	}
}
