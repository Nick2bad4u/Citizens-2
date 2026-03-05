package com.magnaboy;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class Citizens2PluginTest {
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception {
		ExternalPluginManager.loadBuiltin(Citizens2Plugin.class);
		RuneLite.main(args);
	}
}
