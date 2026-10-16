package com.osrsarmory;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

// Launches a RuneLite client with this plugin side-loaded (development only).
public class ArmoryPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ArmoryPlugin.class);
		RuneLite.main(args);
	}
}
