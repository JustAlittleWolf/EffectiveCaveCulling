package dev.justalittlewolf.cavefill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Cavefill {
	public static final String MOD_ID = "cavefill";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * Pockets reached by crossing at most this many solid blocks stay real.
	 * A one-block wall is therefore never filled, and mining one block of a thicker
	 * wall restores the pocket while stone still hides it.
	 */
	public static final int SOLID_BUDGET = 2;

	/**
	 * In dimensions without sky light, a connected passable region this large that runs
	 * off the loaded edge is treated as the open cavern (nether / end void) and not filled.
	 */
	public static final int OPEN_CAVERN_BLOCKS = 80_000;

	private Cavefill() {
	}
}
