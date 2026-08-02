package com.theanchor.evidence;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class EventDeduplicator
{
	private final long windowMillis;
	private final Map<String, Long> seen = new HashMap<>();
	public EventDeduplicator(long windowMillis) { this.windowMillis = windowMillis; }

	public synchronized boolean accept(String key, long now)
	{
		Iterator<Map.Entry<String, Long>> iterator = seen.entrySet().iterator();
		while (iterator.hasNext()) if (now - iterator.next().getValue() >= windowMillis) iterator.remove();
		if (seen.containsKey(key)) return false;
		seen.put(key, now); return true;
	}
}

