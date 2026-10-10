package me.wolfii.effectivecaveculling.engine;

import me.wolfii.effectivecaveculling.Cavefill;
import java.util.Arrays;
import java.util.BitSet;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/** Block masks captured on the client thread before the solver sees a chunk. */
public final class ChunkSnapshot {
	public final int chunkX;
	public final int chunkZ;
	public final int minY;
	public final int maxY;
	public final boolean hasSkyLight;
	public final SectionSnapshot[] sections;
	/**
	 * Absolute Y of the first air block in each column, from {@code WORLD_SURFACE}.
	 * Null when the chunk has no primed surface heightmap. Shared, not mutated.
	 */
	public final int[] firstOpenY;

	private ChunkSnapshot(int chunkX, int chunkZ, int minY, int maxY, boolean hasSkyLight, SectionSnapshot[] sections, int[] firstOpenY) {
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
		this.minY = minY;
		this.maxY = maxY;
		this.hasSkyLight = hasSkyLight;
		this.sections = sections;
		this.firstOpenY = firstOpenY;
	}

	public static ChunkSnapshot capture(LevelChunk chunk) {
		boolean hasSkyLight = chunk.getLevel().dimensionType().hasSkyLight();
		int minY = chunk.getMinY();
		int maxY = chunk.getMaxY();
		int minSection = chunk.getMinSectionY();
		int maxSection = chunk.getMaxSectionY();
		int chunkX = chunk.getPos().x();
		int chunkZ = chunk.getPos().z();
		int count = maxSection - minSection + 1;
		LevelChunkSection[] raw = chunk.getSections();
		int[] firstOpenY = surface(chunk);
		int maxOpen = Integer.MAX_VALUE;
		if (firstOpenY != null) {
			maxOpen = Integer.MIN_VALUE;
			for (int open : firstOpenY) {
				if (open > maxOpen) {
					maxOpen = open;
				}
			}
		}

		SectionSnapshot[] kept = new SectionSnapshot[count];
		int written = 0;
		for (int i = 0; i < count; i++) {
			int sy = minSection + i;
			// Entirely above every column's surface. Cave mouths stay in the section that
			// contains the terrain, and that section is still marked sky-connected.
			if ((sy << 4) >= maxOpen) {
				continue;
			}
			BitSets masks = readSection(raw[i], sy << 4, firstOpenY);
			long key = SectionPos.asLong(chunkX, sy, chunkZ);
			kept[written++] = new SectionSnapshot(key, chunkX, sy, chunkZ, masks.passable, masks.sky);
		}
		SectionSnapshot[] sections = written == count ? kept : Arrays.copyOf(kept, written);

		if (hasSkyLight) {
			paintSky(sections);
		}
		return new ChunkSnapshot(chunkX, chunkZ, minY, maxY, hasSkyLight, sections, firstOpenY);
	}

	/** First air Y per column, or null if the client chunk has no surface heightmap yet. */
	private static int[] surface(LevelChunk chunk) {
		if (!chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)) {
			return null;
		}
		int[] firstOpenY = new int[256];
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				firstOpenY[(z << 4) | x] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
			}
		}
		return firstOpenY;
	}

	/** Recompute the sky column after a single block edit. */
	public static void repaintSky(SectionSnapshot[] sections, boolean hasSkyLight) {
		for (SectionSnapshot section : sections) {
			section.sky.clear();
		}
		if (hasSkyLight) {
			paintSky(sections);
		}
	}

	private static void paintSky(SectionSnapshot[] sections) {
		SectionSnapshot[] topDown = Arrays.copyOf(sections, sections.length);
		Arrays.sort(topDown, (a, b) -> Integer.compare(b.sy, a.sy));
		boolean[] open = new boolean[256];
		Arrays.fill(open, true);
		for (SectionSnapshot section : topDown) {
			for (int y = 15; y >= 0; y--) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int column = x | (z << 4);
						int index = Grid.index(x, y, z);
						if (!section.passable.get(index)) {
							open[column] = false;
						} else if (open[column]) {
							section.sky.set(index);
						}
					}
				}
			}
		}
	}

	private static BitSets readSection(LevelChunkSection section, int originY, int[] firstOpen) {
		BitSet passable = new BitSet(Grid.VOLUME);
		if (section.hasOnlyAir()) {
			passable.set(0, Grid.VOLUME);
			return new BitSets(passable, new BitSet(Grid.VOLUME));
		}
		// Solid sections have no passable palette entry. Skip the 4096-block walk.
		if (!section.maybeHas(state -> !Cavefill.occludes(state))) {
			return new BitSets(passable, new BitSet(Grid.VOLUME));
		}

		section.acquire();
		try {
			for (int y = 0; y < 16; y++) {
				int worldY = originY + y;
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int column = x | (z << 4);
						if (firstOpen != null && worldY >= firstOpen[column]) {
							passable.set(Grid.index(x, y, z));
							continue;
						}
						if (!Cavefill.occludes(section.getBlockState(x, y, z))) {
							passable.set(Grid.index(x, y, z));
						}
					}
				}
			}
		} finally {
			section.release();
		}
		return new BitSets(passable, new BitSet(Grid.VOLUME));
	}

	private record BitSets(BitSet passable, BitSet sky) {
	}
}
