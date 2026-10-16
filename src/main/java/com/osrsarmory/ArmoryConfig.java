package com.osrsarmory;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ArmoryConfig.GROUP)
public interface ArmoryConfig extends Config
{
	String GROUP = "osrsarmory";

	@ConfigItem(
		// Renamed from "endpoint": RuneLite stores defaults, so old installs kept the localhost URL.
		keyName = "serverUrl",
		name = "Server URL",
		description = "Where equipment updates are POSTed. Point this at your own server if you self-host.",
		position = 1
	)
	default String endpoint()
	{
		return "https://osrsarmory.com/api/update-equipment";
	}
}
