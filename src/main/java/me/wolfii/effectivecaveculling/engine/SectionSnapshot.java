package me.wolfii.effectivecaveculling.engine;

import java.util.BitSet;

/** Immutable passable/sky masks for one 16³ section. Owned by the solver thread after publish. */
public final class SectionSnapshot {
	public final long key;
	public final int sx;
	public final int sy;
	public final int sz;
	public final int originX;
	public final int originY;
	public final int originZ;
	public final BitSet passable;
	public final BitSet sky;

	public SectionSnapshot(long key, int sx, int sy, int sz, BitSet passable, BitSet sky) {
		this.key = key;
		this.sx = sx;
		this.sy = sy;
		this.sz = sz;
		this.originX = sx << 4;
		this.originY = sy << 4;
		this.originZ = sz << 4;
		this.passable = passable;
		this.sky = sky;
	}

	public SectionSnapshot withPassable(BitSet passable, BitSet sky) {
		return new SectionSnapshot(this.key, this.sx, this.sy, this.sz, passable, sky);
	}
}
