package me.wolfii.engine;

/** Blocks the player occupies, captured on the client thread for a solver pass. */
public final class PlayerView {
	public final int[] body;

	public PlayerView(int[] body) {
		this.body = body;
	}
}
