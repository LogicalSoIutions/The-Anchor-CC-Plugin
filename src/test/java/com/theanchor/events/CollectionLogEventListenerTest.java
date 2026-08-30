package com.theanchor.events;

import com.theanchor.model.AnchorModels;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CollectionLogEventListenerTest
{
	@Test public void normalizesPopupItemNameBeforeDeduplication()
	{
		String body = "New item:<br><br><col=ffffff>Hallowfell</col>";

		String item = CollectionLogEventListener.normalizeItemName(body)
			.replaceFirst("(?i)^New item:\\s*", "").trim();

		assertEquals("Hallowfell", item);
	}

	@Test public void suppliesRequiredCollectionLogSource()
	{
		assertEquals("collection_log", CollectionLogEventListener.collectionLogSource().type);
		assertEquals("Collection Log", CollectionLogEventListener.collectionLogSource().name);
	}

	@Test public void emitsOnlyCollectionLogForValuableUnlock()
	{
		AnchorModels.Item item = new AnchorModels.Item();
		item.unitGeValue = 1_500_000L;
		assertEquals(Collections.singletonList("collection_log"),
			CollectionLogEventListener.eventTypesFor(Collections.singletonList(item), new AnchorModels.Rules()));

		item.unitGeValue = 1_499_999L;
		assertEquals(Collections.singletonList("collection_log"),
			CollectionLogEventListener.eventTypesFor(Collections.singletonList(item), new AnchorModels.Rules()));
	}

	@Test public void deduplicatesChatAndPopupSignalsAcrossDelay()
	{
		CollectionLogEventListener listener = new CollectionLogEventListener();
		assertEquals(true, listener.acceptNotification("Ankou socks", 1_000L));
		assertEquals(false, listener.acceptNotification("ankou SOCKS", 10_000L));
	}
}
