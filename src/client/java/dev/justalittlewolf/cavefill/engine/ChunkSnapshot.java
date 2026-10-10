package dev.justalittlewolf.cavefill.engine;

import java.util.Arrays;
import java.util.BitSet;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Block masks captured on the client thread before the solver sees a chunk. */
public final class ChunkSnapshot {
	public final int chunkX;
	public final int chunkZ;
	public final int minY;
	public final int maxY;
	public final boolean hasSkyLight;
	public final SectionSnapshot[] sections;

	private ChunkSnapshot(int chunkX, int chunkZ, int minY, int maxY, boolean hasSkyLight, SectionSnapshot[] sections) {
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
		this.minY = minY;
		this.maxY = maxY;
		this.hasSkyLight = hasSkyLight;
		this.sections = sections;
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
		SectionSnapshot[] sections = new SectionSnapshot[count];
		LevelChunkSection[] raw = chunk.getSections();

		for (int i = 0; i < count; i++) {
			int sy = minSection + i;
			BitSets masks = readSection(raw[i]);
			long key = SectionPos.asLong(chunkX, sy, chunkZ);
			sections[i] = new SectionSnapshot(key, chunkX, sy, chunkZ, masks.passable, masks.sky);
		}

		if (hasSkyLight) {
			paintSky(sections);
		}
		return new ChunkSnapshot(chunkX, chunkZ, minY, maxY, hasSkyLight, sections);
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

	private static BitSets readSection(LevelChunkSection section) {
		BitSet passable = new BitSet(Grid.VOLUME);
		if (section.hasOnlyAir()) {
			passable.set(0, Grid.VOLUME);
			return new BitSets(passable, new BitSet(Grid.VOLUME));
		}

		section.acquire();
		try {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (!section.getBlockState(x, y, z).isSolidRender()) {
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
