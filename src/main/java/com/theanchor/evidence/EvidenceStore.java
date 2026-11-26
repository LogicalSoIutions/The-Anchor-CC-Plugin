package com.theanchor.evidence;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.theanchor.model.AnchorModels;
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
		Filepath dir = root.joinSegment("evidence").joinSegment(envelope.player.accountHash).joinSegment(date.toString());
		dir.createDirectories();
		Filepath imagePath = dir.joinSegment(envelope.eventId + "." + encoded.format);
		imagePath.write(encoded.bytes);
		Record record = new Record(); record.metadata = envelope; record.screenshotPath = "evidence/" + envelope.player.accountHash + "/" + date + "/" + imagePath.getFileName(); record.format = encoded.format;
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
		JsonObject saved = metadataGson(record).toJsonTree(record).getAsJsonObject();
		if (record.rawMetadata != null) saved.add("metadata", record.rawMetadata);
		temp.write(saved.toString());
		temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
	}

	public synchronized List<Record> records()
	{
		Filepath outbox = root.joinSegment("outbox"); if (!outbox.isDirectory()) return new ArrayList<>();
		try (java.util.stream.Stream<Filepath> paths = outbox.walk(1))
		{
			return paths.filter(p -> p.isFile() && p.getFileName().endsWith(".json")).map(this::read).filter(record -> record != null && record.metadata != null && record.metadata.capturedAt != null)
				.sorted(Comparator.comparing((Record r) -> r.metadata.capturedAt).reversed()).collect(Collectors.toList());
		}
		catch (IOException e) { return new ArrayList<>(); }
	}

	public synchronized List<Record> records(String account)
	{
		if (account == null) return List.of();
		return records().stream().filter(record -> belongsTo(record, account)).collect(Collectors.toList());
	}

	public synchronized Record record(String eventId)
	{
		return read(root.joinSegment("outbox").joinSegment(eventId + ".json"));
	}

	public static boolean belongsTo(Record record, String account)
	{
		return account != null && record != null && record.metadata != null
			&& record.metadata.player != null && account.equals(record.metadata.player.accountHash);
	}

	String uploadMetadata(Record record)
	{
		return record.rawMetadata == null ? metadataGson(record).toJson(record.metadata) : record.rawMetadata.toString();
	}

	private Gson metadataGson(Record record)
	{
		// Completion-only diary requirements explicitly require result: null.
		return "diary".equals(record.metadata.eventType) ? gson.newBuilder().serializeNulls().create() : gson;
	}

	void setSubmissionTypes(Record record, List<String> types)
	{
		record.metadata.context.put("submissionTypes", types);
		if (record.rawMetadata != null)
			record.rawMetadata.getAsJsonObject("context").add("submissionTypes", gson.toJsonTree(types));
	}

	void updateParty(Record record)
	{
		if (record.rawMetadata == null) return;
		if (record.metadata.party == null) record.rawMetadata.remove("party");
		else record.rawMetadata.add("party", metadataGson(record).toJsonTree(record.metadata.party));
	}

	private Record read(Filepath path)
	{
		try (java.io.Reader reader = path.openReader())
		{
			JsonObject saved = new JsonParser().parse(reader).getAsJsonObject();
			Record record = gson.fromJson(saved, Record.class);
			// Retain the original JSON for uploads, including exact numbers and explicit nulls.
			record.rawMetadata = saved.getAsJsonObject("metadata");
			return record;
		}
		catch (IOException | RuntimeException e) { return null; }
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
		private transient JsonObject rawMetadata;
		public String screenshotPath;
		public String format;
		public AnchorModels.EventStatus status;
		public String submissionId;
		public String error;
		public int retryCount;
		public String updatedAt;
	}
}
