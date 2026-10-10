package dev.justalittlewolf.cavefill;

import dev.justalittlewolf.cavefill.engine.CaveEngine;
import dev.justalittlewolf.cavefill.engine.ChunkSnapshot;
import dev.justalittlewolf.cavefill.engine.PlayerView;
import dev.justalittlewolf.cavefill.engine.SectionFill;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.BitSet;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

/**
 * Applies solver output to the client world before any other mod is told the chunk exists,
 * and again immediately before a chunk is snapshotted for unload.
 */
public final class CavefillController {
	private static final CaveEngine ENGINE = new CaveEngine();
	private static final BlockState FILL = Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState();

	private static final Long2ObjectOpenHashMap<BitSet> applied = new Long2ObjectOpenHashMap<>();
	private static final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<BlockState>> originals = new Long2ObjectOpenHashMap<>();
	private static final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<CompoundTag>> blockEntities = new Long2ObjectOpenHashMap<>();

	private static ClientLevel level;
	private static boolean solverDirty;

	private CavefillController() {
	}

	public static void init() {
		Cavefill.LOGGER.info("Cave Fill will seal unreachable client chunks before they are cached");
	}

	/**
	 * Chunk packet is decoded, but the cache has not published it yet, so Sodium, Voxy, and Bobby
	 * have not ingested it.
	 */
	public static void beforeChunkPublished(LevelChunk chunk) {
		if (!prepare(chunk.getLevel())) {
			return;
		}
		ENGINE.publishAndWait(ChunkSnapshot.capture(chunk), playerView());
		apply(ENGINE.drain(), chunk);
	}

	/**
	 * Bobby shallow-copies on {@code drop} / view moves, and Voxy ingests there when Bobby is installed.
	 * Both hook HEAD, so this has to run first.
	 */
	public static void beforeChunkSnapshotted() {
		if (!prepare(Minecraft.getInstance().level)) {
			return;
		}
		ENGINE.flushAndWait(playerView());
		apply(ENGINE.drain(), null);
		solverDirty = false;
	}

	public static void afterChunkUnloaded(int chunkX, int chunkZ) {
		long chunkKey = chunkKey(chunkX, chunkZ);
		originals.remove(chunkKey);
		blockEntities.remove(chunkKey);
		applied.long2ObjectEntrySet().removeIf(entry -> SectionPos.x(entry.getLongKey()) == chunkX && SectionPos.z(entry.getLongKey()) == chunkZ);
		ENGINE.enqueueUnload(chunkX, chunkZ);
	}

	public static void onRealBlockChanged(LevelChunk chunk, BlockPos pos, BlockState state) {
		if (level == null || chunk.getLevel() != level || !chunk.getLevel().isClientSide()) {
			return;
		}
		long packed = pos.asLong();
		long chunkKey = chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
		Long2ObjectOpenHashMap<BlockState> blocks = originals.get(chunkKey);
		if (blocks != null && blocks.remove(packed) != null) {
			Long2ObjectOpenHashMap<CompoundTag> tags = blockEntities.get(chunkKey);
			if (tags != null) {
				tags.remove(packed);
			}
			clearAppliedBit(pos);
		}
		ENGINE.enqueueBlock(pos.getX(), pos.getY(), pos.getZ(), !state.isSolidRender());
		solverDirty = true;
	}

	public static void onClientTick(Minecraft minecraft) {
		if (!prepare(minecraft.level)) {
			return;
		}
		if (ENGINE.flushFinished()) {
			ENGINE.finishFlush();
			apply(ENGINE.drain(), null);
		}
		if (solverDirty && ENGINE.startFlush(playerView())) {
			solverDirty = false;
		}
	}

	public static boolean isFake(BlockPos pos) {
		Long2ObjectOpenHashMap<BlockState> blocks = originals.get(chunkKey(pos.getX() >> 4, pos.getZ() >> 4));
		return blocks != null && blocks.containsKey(pos.asLong());
	}

	private static boolean prepare(Level current) {
		if (!(current instanceof ClientLevel client) || client.isDebug()) {
			if (level != null) {
				ENGINE.resetAndWait();
				applied.clear();
				originals.clear();
				blockEntities.clear();
				level = null;
			}
			return false;
		}
		if (client != level) {
			ENGINE.resetAndWait();
			applied.clear();
			originals.clear();
			blockEntities.clear();
			level = client;
		}
		return Minecraft.getInstance().player != null;
	}

	private static PlayerView playerView() {
		var player = Minecraft.getInstance().player;
		AABB box = player.getBoundingBox();
		int x0 = Mth.floor(box.minX);
		int y0 = Mth.floor(box.minY);
		int z0 = Mth.floor(box.minZ);
		int x1 = Mth.floor(box.maxX);
		int y1 = Mth.floor(box.maxY);
		int z1 = Mth.floor(box.maxZ);
		int[] body = new int[(x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) * 3];
		int cursor = 0;
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					body[cursor++] = x;
					body[cursor++] = y;
					body[cursor++] = z;
				}
			}
		}
		return new PlayerView(body);
	}

	private static void apply(List<SectionFill> fills, LevelChunk incoming) {
		if (level == null || fills.isEmpty()) {
			return;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (SectionFill fill : fills) {
			LevelChunk chunk;
			if (incoming != null && incoming.getPos().x() == fill.sectionX && incoming.getPos().z() == fill.sectionZ) {
				chunk = incoming;
			} else {
				chunk = level.getChunkSource().getChunk(fill.sectionX, fill.sectionZ, ChunkStatus.FULL, false);
			}
			if (chunk == null) {
				continue;
			}
			int index = chunk.getSectionIndexFromSectionY(fill.sectionY);
			if (index < 0 || index >= chunk.getSections().length) {
				continue;
			}
			LevelChunkSection section = chunk.getSection(index);
			BitSet desired = (BitSet) fill.fill.clone();
			BitSet current = applied.get(fill.sectionKey);
			if (current == null) {
				current = new BitSet(4096);
			}
			boolean wasEmpty = section.hasOnlyAir();
			long chunkKey = chunkKey(fill.sectionX, fill.sectionZ);
			Long2ObjectOpenHashMap<BlockState> stored = originals.computeIfAbsent(chunkKey, key -> new Long2ObjectOpenHashMap<>());
			Long2ObjectOpenHashMap<CompoundTag> tags = blockEntities.computeIfAbsent(chunkKey, key -> new Long2ObjectOpenHashMap<>());

			for (int bit = current.nextSetBit(0); bit >= 0; bit = current.nextSetBit(bit + 1)) {
				if (desired.get(bit)) {
					continue;
				}
				restore(chunk, section, fill, bit, pos, stored, tags);
			}
			for (int bit = desired.nextSetBit(0); bit >= 0; bit = desired.nextSetBit(bit + 1)) {
				if (current.get(bit)) {
					continue;
				}
				seal(level, chunk, section, fill, bit, pos, stored, tags);
			}
			if (desired.isEmpty()) {
				applied.remove(fill.sectionKey);
			} else {
				applied.put(fill.sectionKey, desired);
			}
			if (wasEmpty != section.hasOnlyAir()) {
				level.getChunkSource().onSectionEmptinessChanged(fill.sectionX, fill.sectionY, fill.sectionZ, section.hasOnlyAir());
			}
			level.setSectionDirtyWithNeighbors(fill.sectionX, fill.sectionY, fill.sectionZ);
		}
	}

	private static void seal(
		ClientLevel level,
		LevelChunk chunk,
		LevelChunkSection section,
		SectionFill fill,
		int bit,
		BlockPos.MutableBlockPos pos,
		Long2ObjectOpenHashMap<BlockState> stored,
		Long2ObjectOpenHashMap<CompoundTag> tags
	) {
		int x = (fill.sectionX << 4) + (bit & 15);
		int y = (fill.sectionY << 4) + ((bit >> 8) & 15);
		int z = (fill.sectionZ << 4) + ((bit >> 4) & 15);
		pos.set(x, y, z);
		long packed = pos.asLong();
		if (!stored.containsKey(packed)) {
			BlockState current = chunk.getBlockState(pos);
			stored.put(packed, current);
			BlockEntity blockEntity = chunk.getBlockEntity(pos);
			if (blockEntity != null) {
				tags.put(packed, blockEntity.saveWithFullMetadata(level.registryAccess()));
				chunk.removeBlockEntity(pos);
			}
		}
		section.setBlockState(x & 15, y & 15, z & 15, FILL);
	}

	private static void restore(
		LevelChunk chunk,
		LevelChunkSection section,
		SectionFill fill,
		int bit,
		BlockPos.MutableBlockPos pos,
		Long2ObjectOpenHashMap<BlockState> stored,
		Long2ObjectOpenHashMap<CompoundTag> tags
	) {
		int x = (fill.sectionX << 4) + (bit & 15);
		int y = (fill.sectionY << 4) + ((bit >> 8) & 15);
		int z = (fill.sectionZ << 4) + ((bit >> 4) & 15);
		pos.set(x, y, z);
		long packed = pos.asLong();
		BlockState original = stored.remove(packed);
		CompoundTag tag = tags.remove(packed);
		if (original == null) {
			return;
		}
		section.setBlockState(x & 15, y & 15, z & 15, original);
		if (tag != null && original.hasBlockEntity()) {
			BlockPos immutable = new BlockPos(x, y, z);
			BlockEntity blockEntity = BlockEntity.loadStatic(immutable, original, tag, chunk.getLevel().registryAccess());
			if (blockEntity != null) {
				chunk.addAndRegisterBlockEntity(blockEntity);
			}
		}
	}

	private static void clearAppliedBit(BlockPos pos) {
		long key = SectionPos.asLong(pos);
		BitSet bits = applied.get(key);
		if (bits != null) {
			bits.clear(((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15));
		}
	}

	private static long chunkKey(int chunkX, int chunkZ) {
		return ChunkPosOf(chunkX, chunkZ);
	}

	private static long ChunkPosOf(int chunkX, int chunkZ) {
		return net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ);
	}
}
