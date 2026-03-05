package com.magnaboy;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("citizens")
public interface CitizensConfig extends Config {
	@Range(
		min = -128,
		max = 128
	)
	@ConfigItem(
		keyName = "citizenHeightOffset",
		name = "Citizen height offset",
		description = "Adjust citizen vertical position. Positive values raise citizens; negative values lower them.",
		position = 0
	)
	default int citizenHeightOffset() {
		return 12;
	}

	@ConfigItem(
		keyName = "debugAirliftCitizens",
		name = "DEBUG: Airlift citizens",
		description = "Temporarily lifts citizens high into the air for clipping diagnostics.",
		position = 1
	)
	default boolean debugAirliftCitizens() {
		return false;
	}

	@Range(
		min = 0,
		max = 512
	)
	@ConfigItem(
		keyName = "debugAirliftOffset",
		name = "DEBUG: Airlift offset",
		description = "Additional upward lift in units when DEBUG: Airlift citizens is enabled.",
		position = 2
	)
	default int debugAirliftOffset() {
		return 220;
	}

}
