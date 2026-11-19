package com.theanchor.collection;

import com.theanchor.AnchorConfig;
import java.time.Duration;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;

@Singleton
public class CollectionLogPromptState
{
	private static final String KEY_PREFIX = "collectionLogSynced.";
	private static final String LAST_SYNC_PREFIX = "collectionLogLastSyncedAt.";
	private static final long AUTO_SYNC_INTERVAL_MILLIS = Duration.ofDays(1).toMillis();
	private final Client client;
	private final ConfigManager configManager;

	@Inject
	public CollectionLogPromptState(Client client, ConfigManager configManager)
	{
		this.client = client;
		this.configManager = configManager;
	}

	public boolean hasSyncedForCurrentAccount()
	{
		String key = currentAccountKey();
		return key != null && Boolean.parseBoolean(configManager.getConfiguration(AnchorConfig.GROUP, key));
	}

	public boolean markSyncedForCurrentAccount()
	{
		return markSyncedForAccount(Long.toString(client.getAccountHash()));
	}

	/** Persists the daily limit per account so restarting RuneLite does not reset it. */
	public boolean isAutomaticSyncDue()
	{
		String accountHash = Long.toString(client.getAccountHash());
		if (accountKey(accountHash) == null) return false;
		String value = configManager.getConfiguration(AnchorConfig.GROUP, LAST_SYNC_PREFIX + accountHash);
		if (value == null) return true;
		try
		{
			long lastSync = Long.parseLong(value);
			long now = System.currentTimeMillis();
			return lastSync <= 0L || lastSync > now || now - lastSync >= AUTO_SYNC_INTERVAL_MILLIS;
		}
		catch (NumberFormatException e)
		{
			return true;
		}
	}

	public boolean markSyncedForAccount(String accountHash)
	{
		String key = accountKey(accountHash);
		if (key == null)
		{
			return false;
		}
		if (!Boolean.parseBoolean(configManager.getConfiguration(AnchorConfig.GROUP, key)))
		{
			configManager.setConfiguration(AnchorConfig.GROUP, key, true);
		}
		configManager.setConfiguration(AnchorConfig.GROUP, LAST_SYNC_PREFIX + accountHash, System.currentTimeMillis());
		return true;
	}

	private String currentAccountKey()
	{
		return accountKey(Long.toString(client.getAccountHash()));
	}

	private static String accountKey(String accountHash)
	{
		return accountHash == null || accountHash.isBlank() || "0".equals(accountHash) ? null : KEY_PREFIX + accountHash;
	}

}
