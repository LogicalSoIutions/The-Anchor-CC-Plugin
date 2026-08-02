/*
 * Test structure adapted from PB Tracker Sync.
 * See LICENSES/pb-tracker-sync-LICENSE.txt.
 */
package com.theanchor;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AnchorPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AnchorPlugin.class);
		RuneLite.main(args);
	}
}
