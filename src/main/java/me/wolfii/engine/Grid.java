package me.wolfii.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

final class Grid {
	static final int VOLUME = 4096;
	static final int[] DX = {0, 0, 0, 0, -1, 1};
	static final int[] DY = {-1, 1, 0, 0, 0, 0};
	static final int[] DZ = {0, 0, -1, 1, 0, 0};

	private Grid() {
	}

	static int index(int x, int y, int z) {
		return (y << 8) | (z << 4) | (x & 15);
	}

	static int localX(int index) {
		return index & 15;
	}

	static int localZ(int index) {
		return (index >> 4) & 15;
	}

	static int localY(int index) {
		return (index >> 8) & 15;
	}

	static long sectionKey(int blockX, int blockY, int blockZ) {
		return SectionPos.asLong(blockX >> 4, blockY >> 4, blockZ >> 4);
	}

	static int chebyshev(int x, int y, int z, int ox, int oy, int oz) {
		return Math.max(Math.abs(x - ox), Math.max(Math.abs(y - oy), Math.abs(z - oz)));
	}

	static long block(int x, int y, int z) {
		return BlockPos.asLong(x, y, z);
	}
}
