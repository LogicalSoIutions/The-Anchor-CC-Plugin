package com.theanchor.evidence;

import com.google.gson.Gson;
import com.theanchor.model.AnchorModels;
import com.theanchor.service.ImageCache;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import net.runelite.client.util.Filepath;

@Singleton
public class EvidenceStore
{
	private final Filepath root;
	private final Gson gson;

	@Inject public EvidenceStore(Gson gson, @Named("anchorDirectory") Filepath root) { this.gson = gson; this.root = root; }

	public Record save(AnchorModels.EventEnvelope envelope, BufferedImage image) throws IOException
	{
		ScreenshotEncoder.Encoded encoded = ScreenshotEncoder.encode(image);
		LocalDate date = LocalDate.now();
		Filepath dir = root.joinSegment("evidence").joinSegment(ImageCache.safeName(envelope.player.name)).joinSegment(date.toString());
		dir.createDirectories();
		Filepath imagePath = dir.joinSegment(envelope.eventId + "." + encoded.format);
		imagePath.write(encoded.bytes);
		Record record = new Record(); record.metadata = envelope; record.screenshotPath = "evidence/" + ImageCache.safeName(envelope.player.name) + "/" + date + "/" + imagePath.getFileName(); record.format = encoded.format;
		record.status = AnchorModels.EventStatus.PENDING; record.updatedAt = Instant.now().toString();
		writeRecord(record);
		return record;
	}

	public synchronized Record saveMetadata(AnchorModels.EventEnvelope envelope) throws IOException
	{
		Record record = new Record();
		record.metadata = envelope;
		record.status = AnchorModels.EventStatus.PENDING;
		record.updatedAt = Instant.now().toString();
		writeRecord(record);
		return record;
	}

	public synchronized void writeRecord(Record record) throws IOException
	{
		Filepath outbox = root.joinSegment("outbox"); outbox.createDirectories();
		Filepath target = outbox.joinSegment(record.metadata.eventId + ".json"); Filepath temp = outbox.joinSegment(target.getFileName() + ".tmp");
		String json = "diary".equals(record.metadata.eventType)
			? gson.newBuilder().serializeNulls().create().toJson(record) : gson.toJson(record);
		temp.write(json);
		temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
	}

	public synchronized List<Record> records()
	{
		Filepath outbox = root.joinSegment("outbox"); if (!outbox.isDirectory()) return new ArrayList<>();
		try (java.util.stream.Stream<Filepath> paths = outbox.walk(1))
		{
			return paths.filter(p -> p.isFile() && p.getFileName().endsWith(".json")).map(this::read).filter(java.util.Objects::nonNull)
				.sorted(Comparator.comparing((Record r) -> r.metadata.capturedAt).reversed()).collect(Collectors.toList());
		}
		catch (IOException e) { return new ArrayList<>(); }
	}

	private Record read(Filepath path)
	{
		try (java.io.Reader reader = path.openReader()) { return gson.fromJson(reader, Record.class); } catch (IOException | RuntimeException e) { return null; }
	}

	public synchronized void deleteLocal(String eventId)
	{
		Record record = read(root.joinSegment("outbox").joinSegment(eventId + ".json"));
		try
		{
			Filepath screenshot = record == null ? null : screenshot(record);
			if (screenshot != null) screenshot.deleteIfExists();
			root.joinSegment("outbox").joinSegment(eventId + ".json").deleteIfExists();
		}
		catch (IOException ignored) { }
	}

	/** Resolve both relative references and absolute references saved before directory migration. */
	public Filepath screenshot(Record record) throws IOException
	{
		if (record.screenshotPath == null || record.screenshotPath.isBlank()) return null;
		String relative = record.screenshotPath.replace('\\', '/');
		if (!relative.startsWith("evidence/"))
		{
			String legacyMarker = "/the-anchor/evidence/";
			int marker = relative.lastIndexOf(legacyMarker);
			if (marker < 0) throw new IOException("Invalid evidence reference");
			relative = relative.substring(marker + "/the-anchor/".length());
		}
		String[] segments = relative.split("/", -1);
		if (segments.length != 4) throw new IOException("Invalid evidence reference");
		Filepath path = root.joinSegment("evidence").rooted();
		for (int i = 1; i < segments.length; i++)
		{
			String segment = segments[i];
			if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.contains(":"))
				throw new IOException("Invalid evidence reference");
			path = path.joinSegment(segment);
		}
		return path;
	}

	public synchronized void pruneUploaded(int retentionDays)
	{
		long cutoff = System.currentTimeMillis() - Math.max(0, retentionDays) * 86_400_000L;
		for (Record record : records())
		{
			if ((record.status == AnchorModels.EventStatus.DRAFT || record.status == AnchorModels.EventStatus.SUBMITTED || record.status == AnchorModels.EventStatus.APPROVED)
				&& parseTime(record.updatedAt) < cutoff) deleteLocal(record.metadata.eventId);
		}
	}

	private static long parseTime(String value) { try { return Instant.parse(value).toEpochMilli(); } catch (RuntimeException e) { return Long.MAX_VALUE; } }

	public static final class Record
	{
		public AnchorModels.EventEnvelope metadata;
		public String screenshotPath;
		public String format;
		public AnchorModels.EventStatus status;
		public String submissionId;
		public String error;
		public int retryCount;
		public String updatedAt;
	}
}
