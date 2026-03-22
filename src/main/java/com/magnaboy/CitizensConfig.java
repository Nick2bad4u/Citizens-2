package com.magnaboy;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("citizens2")
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

	@ConfigItem(
		keyName = "debugVerboseLogging",
		name = "DEBUG: Verbose logging",
		description = "Emit periodic detailed Citizens 2 state snapshots to the log.",
		position = 3
	)
	default boolean debugVerboseLogging() {
		return false;
	}

	@Range(
		min = 1,
		max = 200
	)
	@ConfigItem(
		keyName = "debugVerboseLogIntervalTicks",
		name = "DEBUG: Snapshot interval (ticks)",
		description = "How often verbose snapshots are logged while DEBUG: Verbose logging is enabled.",
		position = 4
	)
	default int debugVerboseLogIntervalTicks() {
		return 20;
	}

	@ConfigItem(
		keyName = "debugEntityFilter",
		name = "DEBUG: Entity filter",
		description = "Optional name or UUID substring filter used by verbose logging.",
		position = 5
	)
	default String debugEntityFilter() {
		return "";
	}

	@ConfigItem(
		keyName = "debugTraceEntityTransitions",
		name = "DEBUG: Trace entity transitions",
		description = "Logs per-entity state transitions (active/animation/target and optional movement).",
		position = 6
	)
	default boolean debugTraceEntityTransitions() {
		return false;
	}

	@Range(
		min = 1,
		max = 200
	)
	@ConfigItem(
		keyName = "debugTraceIntervalTicks",
		name = "DEBUG: Trace interval (ticks)",
		description = "How often transition traces are evaluated while DEBUG: Trace entity transitions is enabled.",
		position = 7
	)
	default int debugTraceIntervalTicks() {
		return 5;
	}

	@Range(
		min = 1,
		max = 100
	)
	@ConfigItem(
		keyName = "debugTraceMaxEventsPerInterval",
		name = "DEBUG: Max trace events/interval",
		description = "Hard cap on number of transition logs emitted each trace interval.",
		position = 8
	)
	default int debugTraceMaxEventsPerInterval() {
		return 8;
	}

	@ConfigItem(
		keyName = "debugTraceIncludeMovement",
		name = "DEBUG: Trace movement",
		description = "Include world-location deltas in transition signatures (can be noisy for wanderers).",
		position = 9
	)
	default boolean debugTraceIncludeMovement() {
		return false;
	}

}
