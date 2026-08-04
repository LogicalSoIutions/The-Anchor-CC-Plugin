package com.theanchor.events;

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
}
