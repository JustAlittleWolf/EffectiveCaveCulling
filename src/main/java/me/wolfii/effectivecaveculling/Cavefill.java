package me.wolfii.effectivecaveculling;

import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Cavefill {
	public static final String MOD_ID = "effectivecaveculling";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * In dimensions without sky light, a connected passable region this large that runs
	 * off the loaded edge is treated as the open cavern (nether / end void) and not filled.
	 */
	public static final int OPEN_CAVERN_BLOCKS = 80_000;

	private Cavefill() {
	}

	/**
	 * Opaque full cubes are walls. Everything else can be filled, including transparent solids
	 * (glass, leaves) and non-full or non-solid blocks (fences, fire, glow berries).
	 */
	public static boolean occludes(BlockState state) {
		return state.isSolidRender() && !state.propagatesSkylightDown();
	}
}
