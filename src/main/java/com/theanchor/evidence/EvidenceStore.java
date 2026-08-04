package com.theanchor.evidence;

import com.google.gson.Gson;
import com.theanchor.model.AnchorModels;
import com.theanchor.service.ImageCache;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;

@Singleton
public class EvidenceStore
{
	private final Path root;
	private final Gson gson;

	@Inject public EvidenceStore(Gson gson) { this(gson, RuneLite.RUNELITE_DIR.toPath().resolve("the-anchor")); }
	EvidenceStore(Gson gson, Path root) { this.gson = gson; this.root = root; }

	public synchronized Record save(AnchorModels.EventEnvelope envelope, BufferedImage image) throws IOException
	{
		ScreenshotEncoder.Encoded encoded = ScreenshotEncoder.encode(image);
		LocalDate date = LocalDate.now();
		Path dir = root.resolve("evidence").resolve(ImageCache.safeName(envelope.player.name)).resolve(date.toString());
		Files.createDirectories(dir);
		Path imagePath = dir.resolve(envelope.eventId + "." + encoded.format);
		Files.write(imagePath, encoded.bytes);
		Record record = new Record(); record.metadata = envelope; record.screenshotPath = imagePath.toString(); record.format = encoded.format;
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
		Path outbox = root.resolve("outbox"); Files.createDirectories(outbox);
		Path target = outbox.resolve(record.metadata.eventId + ".json"); Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(temp, gson.toJson(record), StandardCharsets.UTF_8);
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
	}

	public synchronized List<Record> records()
	{
		Path outbox = root.resolve("outbox"); if (!Files.isDirectory(outbox)) return new ArrayList<>();
		try (java.util.stream.Stream<Path> paths = Files.list(outbox))
		{
			return paths.filter(p -> p.getFileName().toString().endsWith(".json")).map(this::read).filter(java.util.Objects::nonNull)
				.sorted(Comparator.comparing((Record r) -> r.metadata.capturedAt).reversed()).collect(Collectors.toList());
		}
		catch (IOException e) { return new ArrayList<>(); }
	}

	private Record read(Path path)
	{
		try { return gson.fromJson(Files.readString(path), Record.class); } catch (IOException | RuntimeException e) { return null; }
	}

	public synchronized void deleteLocal(String eventId)
	{
		Record record = records().stream().filter(r -> eventId.equals(r.metadata.eventId)).findFirst().orElse(null);
		try
		{
			if (record != null && record.screenshotPath != null) Files.deleteIfExists(Path.of(record.screenshotPath));
			Files.deleteIfExists(root.resolve("outbox").resolve(eventId + ".json"));
		}
		catch (IOException ignored) { }
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
