package com.theanchor;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(AnchorConfig.GROUP)
public interface AnchorConfig extends Config
{
	String GROUP = "theanchor";

	@ConfigSection(name = "Authentication", description = "Connect The Anchor to Discord", position = 0)
	String AUTH = "authentication";

	@ConfigSection(name = "Evidence", description = "Screenshot and upload behavior", position = 1)
	String EVIDENCE = "evidence";

	@ConfigSection(name = "Appearance", description = "Customize The Anchor side panel", position = 2)
	String APPEARANCE = "appearance";

	@ConfigItem(keyName = "authenticationCode", name = "Authentication code",
		description = "Run /connect in The Anchor Discord and paste the permanent code here",
		section = AUTH, position = 0, secret = true)
	default String authenticationCode() { return ""; }

	@ConfigItem(keyName = "apiBaseUrl", name = "API base URL",
		description = "The Anchor website origin", hidden = true, position = 1)
	default String apiBaseUrl() { return "https://the-anchor.cc"; }

	@ConfigItem(keyName = "includeSidebar", name = "Include sidebar",
		description = "Include the RuneLite sidebar in evidence screenshots",
		section = EVIDENCE, position = 0)
	default boolean includeSidebar() { return true; }

	@ConfigItem(keyName = "hidePrivateMessages", name = "Hide private messages",
		description = "Temporarily hide split private chat while capturing evidence",
		section = EVIDENCE, position = 1)
	default boolean hidePrivateMessages() { return true; }

	@ConfigItem(keyName = "retentionDays", name = "Evidence retention",
		description = "Days to keep successfully uploaded evidence locally",
		section = EVIDENCE, position = 2)
	default int retentionDays() { return 30; }

	@ConfigItem(keyName = "animatedBanner", name = "Animated banner",
		description = "Animate the side-panel banner; disable to show the static Anchor banner",
		section = APPEARANCE, position = 0)
	default boolean animatedBanner() { return true; }

}
