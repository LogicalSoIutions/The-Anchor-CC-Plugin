package com.theanchor.collection;

import com.theanchor.AnchorConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;

@Slf4j
@Singleton
public class CollectionLogPromptState
{
	private static final String KEY_PREFIX = "collectionLogSynced.";
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

	public boolean markSyncedForAccount(String accountHash)
	{
		String key = accountKey(accountHash);
		if (key == null)
		{
			log.debug("Collection log sync completed before an account hash was available");
			return false;
		}
		if (!Boolean.parseBoolean(configManager.getConfiguration(AnchorConfig.GROUP, key)))
		{
			configManager.setConfiguration(AnchorConfig.GROUP, key, true);
			log.info("Collection log sync prompt completed for account hash ending {}", accountHashSuffix(accountHash));
		}
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

	private static String accountHashSuffix(String accountHash)
	{
		return accountHash.length() <= 4 ? accountHash : accountHash.substring(accountHash.length() - 4);
	}
}
