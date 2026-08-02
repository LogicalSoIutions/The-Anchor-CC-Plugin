package com.theanchor.evidence;

import com.google.gson.Gson;
import com.theanchor.model.AnchorModels;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

public class EvidenceStoreTest
{
	@Test public void persistsEventWithoutAuthenticationSecret() throws Exception
	{
		Path root = Files.createTempDirectory("anchor-evidence-test"); EvidenceStore store = new EvidenceStore(new Gson(), root);
		AnchorModels.EventEnvelope event = new AnchorModels.EventEnvelope(); event.eventId = "event-1"; event.eventType = "pet"; event.capturedAt = "2026-08-01T00:00:00Z";
		event.player = new AnchorModels.PlayerIdentity(); event.player.name = "Player"; event.player.accountHash = "42";
		store.save(event, new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB));
		String json = Files.readString(root.resolve("outbox").resolve("event-1.json"));
		assertFalse(json.contains("authenticationCode")); assertFalse(json.contains("Authorization")); assertEquals(1, store.records().size());
	}
}

