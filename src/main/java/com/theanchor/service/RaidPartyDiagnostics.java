package com.theanchor.service;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Temporary local diagnostics. No screenshots, evidence records, or API requests. */
final class RaidPartyDiagnostics
{
	private static final Logger log = LoggerFactory.getLogger(RaidPartyDiagnostics.class);
	private static final int SAMPLE_TICKS = 10;
	private static final int HEARTBEAT_SAMPLES = 5;
	private int ticksUntilSample;
	private int unchangedSamples;
	private String lastReport;

	void onGameTick(boolean enabled, Supplier<String> report)
	{
		if (!enabled)
		{
			ticksUntilSample = 0;
			unchangedSamples = 0;
			lastReport = null;
			return;
		}
		if (ticksUntilSample-- > 0) return;
		ticksUntilSample = SAMPLE_TICKS - 1;
		String current = report.get();
		if (!current.equals(lastReport) || ++unchangedSamples >= HEARTBEAT_SAMPLES)
		{
			log.info("Raid party debug [periodic]: {}", current);
			lastReport = current;
			unchangedSamples = 0;
		}
	}
}
