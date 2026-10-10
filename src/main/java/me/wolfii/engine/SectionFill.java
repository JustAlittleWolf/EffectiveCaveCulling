package me.wolfii.engine;

import java.util.BitSet;

/** Blocks inside one section that should currently be client-side red concrete. */
public final class SectionFill {
	public final long sectionKey;
	public final int sectionX;
	public final int sectionY;
	public final int sectionZ;
	public final BitSet fill;

	public SectionFill(long sectionKey, int sectionX, int sectionY, int sectionZ, BitSet fill) {
		this.sectionKey = sectionKey;
		this.sectionX = sectionX;
		this.sectionY = sectionY;
		this.sectionZ = sectionZ;
		this.fill = fill;
	}
}
