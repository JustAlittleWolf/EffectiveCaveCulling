package me.wolfii.effectivecaveculling.engine;

/** Blocks the player occupies, captured on the client thread for a solver pass. */
public final class PlayerView {
	public final int[] body;
	/** Feet are above the inclusive max build height, so the top of the world is open void. */
	public final boolean above;
	/** Feet are below the inclusive min build height, so the bottom of the world is open void. */
	public final boolean below;

	public PlayerView(int[] body, boolean above, boolean below) {
		this.body = body;
		this.above = above;
		this.below = below;
	}
}
