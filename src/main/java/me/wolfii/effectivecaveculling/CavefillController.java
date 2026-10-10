package me.wolfii.effectivecaveculling;

import me.wolfii.effectivecaveculling.config.CavefillConfig;
import me.wolfii.effectivecaveculling.engine.CaveEngine;
import me.wolfii.effectivecaveculling.engine.ChunkSnapshot;
import me.wolfii.effectivecaveculling.engine.PlayerView;
import me.wolfii.effectivecaveculling.engine.SectionFill;
import me.wolfii.effectivecaveculling.mixin.ClientChunkCacheAccessor;
import me.wolfii.effectivecaveculling.mixin.ClientChunkStorageAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
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
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.AABB;

/**
 * Applies solver output to the client world before any other mod is told the chunk exists,
 * and again immediately before a chunk is snapshotted for unload.
 * Only the pockets that were filled are remembered, and a chunk that is already loaded only loses fills.
 */
public final class CavefillController {
	private static final CaveEngine ENGINE = new CaveEngine();
	private static final BlockState FILL = Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState();

	private static final Long2ObjectOpenHashMap<BitSet> applied = new Long2ObjectOpenHashMap<>();
	private static final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<BlockState>> originals = new Long2ObjectOpenHashMap<>();
	private static final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<CompoundTag>> blockEntities = new Long2ObjectOpenHashMap<>();
	/** Block positions whose light must be checked again after the server light packet is applied. */
	private static final LongArrayList relight = new LongArrayList();
	/** Unfills of chunks that are already loaded. Applied off the chunk-packet path. */
	private static final List<SectionFill> deferred = new ArrayList<>();

	private static ClientLevel level;
	private static boolean solverDirty;
	/** Chunks arrived while the mod was off, so enabling has to read what is already loaded. */
	private static boolean catchUp;

	private CavefillController() {
	}

	public static void init() {
		Cavefill.LOGGER.info("Cave Fill will seal unreachable client chunks before they are cached");
	}

	/**
	 * Chunk packet is decoded, but the cache has not published it yet, so Sodium, Voxy, and Bobby
	 * have not ingested it. Only this column is written here. Neighbor unfills wait for the client tick.
	 */
	public static void beforeChunkPublished(LevelChunk chunk) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!CavefillConfig.allows(minecraft)) {
			catchUp = true;
			return;
		}
		if (!prepare(chunk.getLevel())) {
			return;
		}
		if (catchUp) {
			publishLoaded((ClientLevel) minecraft.level);
			catchUp = false;
		}
		// The packet just replaced this column, so previous pocket saves no longer match it.
		forgetColumn(chunk.getPos().x(), chunk.getPos().z());
		ENGINE.publishAndWait(ChunkSnapshot.capture(chunk), playerView());
		List<SectionFill> now = new ArrayList<>();
		for (SectionFill fill : ENGINE.drain()) {
			if (fill.sectionX == chunk.getPos().x() && fill.sectionZ == chunk.getPos().z()) {
				now.add(fill);
			} else {
				deferred.add(fill);
			}
		}
		apply(now, chunk);
	}

	/**
	 * Bobby shallow-copies on {@code drop} / view moves, and Voxy ingests there when Bobby is installed.
	 * Both hook HEAD, so this has to run first. An idle solver has nothing new to publish.
	 */
	public static void beforeChunkSnapshotted() {
		if (level == null || !CavefillConfig.allows(Minecraft.getInstance())) {
			return;
		}
		if (!prepare(Minecraft.getInstance().level)) {
			return;
		}
		if (solverDirty || !ENGINE.idle()) {
			ENGINE.flushAndWait(playerView());
			deferred.addAll(ENGINE.drain());
			solverDirty = false;
		}
		applyDeferred();
	}

	public static void afterChunkUnloaded(int chunkX, int chunkZ) {
		forgetColumn(chunkX, chunkZ);
		ENGINE.enqueueUnload(chunkX, chunkZ);
	}

	public static void onRealBlockChanged(LevelChunk chunk, BlockPos pos, BlockState state) {
		if (level == null || chunk.getLevel() != level || !chunk.getLevel().isClientSide()) {
			return;
		}
		long packed = pos.asLong();
		long chunkKey = chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
		Long2ObjectOpenHashMap<BlockState> blocks = originals.get(chunkKey);
		if (blocks != null) {
			blocks.remove(packed);
		}
		Long2ObjectOpenHashMap<CompoundTag> tags = blockEntities.get(chunkKey);
		if (tags != null) {
			tags.remove(packed);
		}
		boolean revealed = clearAppliedBit(pos);
		if (revealed) {
			ENGINE.enqueueReveal(pos.getX(), pos.getY(), pos.getZ(), !Cavefill.occludes(state));
		} else {
			ENGINE.enqueueBlock(pos.getX(), pos.getY(), pos.getZ(), !Cavefill.occludes(state));
		}
		solverDirty = true;
	}

	public static void onConfigChanged() {
		Minecraft minecraft = Minecraft.getInstance();
		if (!CavefillConfig.allows(minecraft)) {
			noteInactive(minecraft);
			return;
		}
		if (catchUp || level == null) {
			publishCatchUp(minecraft);
		}
	}

	public static void onClientTick(Minecraft minecraft) {
		if (!CavefillConfig.allows(minecraft)) {
			noteInactive(minecraft);
			return;
		}
		if (catchUp || level == null) {
			publishCatchUp(minecraft);
		}
		if (level != null && minecraft.player != null) {
			int feet = Mth.floor(minecraft.player.getY());
			if (ENGINE.enqueueVertical(feet > level.getMaxY(), feet < level.getMinY())) {
				solverDirty = true;
			}
		}
		if (ENGINE.flushFinished()) {
			ENGINE.finishFlush();
			deferred.addAll(ENGINE.drain());
		}
		if (solverDirty && ENGINE.startFlush(playerView())) {
			solverDirty = false;
		}
		applyDeferred();
		flushRelight();
	}

	public static boolean isFake(BlockPos pos) {
		BitSet bits = applied.get(SectionPos.asLong(pos));
		if (bits == null) {
			return false;
		}
		return bits.get(((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15));
	}

	private static void noteInactive(Minecraft minecraft) {
		if (level != null) {
			// Quitting nulls the client level and the section tracker before this tick. Restoring then crashes,
			// and the world is already going away, so the fake blocks can be dropped.
			deactivate(minecraft.level == level);
		}
		if (minecraft.level instanceof ClientLevel client && !client.isDebug() && minecraft.player != null) {
			catchUp = true;
		} else if (minecraft.level == null) {
			catchUp = false;
		}
	}

	private static void deactivate(boolean restore) {
		if (restore) {
			restoreAll();
		}
		ENGINE.resetAndWait();
		ENGINE.clearClientVertical();
		applied.clear();
		originals.clear();
		blockEntities.clear();
		relight.clear();
		deferred.clear();
		solverDirty = false;
		level = null;
	}

	private static void restoreAll() {
		if (level == null || applied.isEmpty()) {
			return;
		}
		long[] keys = applied.keySet().toLongArray();
		List<SectionFill> clears = new ArrayList<>(keys.length);
		for (long key : keys) {
			clears.add(new SectionFill(key, SectionPos.x(key), SectionPos.y(key), SectionPos.z(key), new BitSet(4096)));
		}
		apply(clears, null);
	}

	private static void publishCatchUp(Minecraft minecraft) {
		if (!prepare(minecraft.level)) {
			return;
		}
		catchUp = false;
		publishLoaded((ClientLevel) minecraft.level);
	}

	private static void publishLoaded(ClientLevel client) {
		Object storage = ((ClientChunkCacheAccessor) client.getChunkSource()).cavefill$storage();
		AtomicReferenceArray<LevelChunk> chunks = ((ClientChunkStorageAccessor) storage).cavefill$chunks();
		for (int index = 0; index < chunks.length(); index++) {
			LevelChunk chunk = chunks.get(index);
			if (chunk == null) {
				continue;
			}
			ENGINE.publishAndWait(ChunkSnapshot.capture(chunk), playerView(), true);
			deferred.addAll(ENGINE.drain());
		}
	}

	private static boolean prepare(Level current) {
		if (!(current instanceof ClientLevel client) || client.isDebug()) {
			if (level != null) {
				ENGINE.resetAndWait();
				ENGINE.clearClientVertical();
				applied.clear();
				originals.clear();
				blockEntities.clear();
				relight.clear();
				deferred.clear();
				level = null;
			}
			return false;
		}
		if (client != level) {
			ENGINE.resetAndWait();
			ENGINE.clearClientVertical();
			applied.clear();
			originals.clear();
			blockEntities.clear();
			relight.clear();
			deferred.clear();
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
		int feet = Mth.floor(player.getY());
		return new PlayerView(body, feet > level.getMaxY(), feet < level.getMinY());
	}

	private static void applyDeferred() {
		if (deferred.isEmpty()) {
			return;
		}
		List<SectionFill> batch = new ArrayList<>(deferred);
		deferred.clear();
		apply(batch, null);
	}

	private static void apply(List<SectionFill> fills, LevelChunk incoming) {
		if (level == null || fills.isEmpty()) {
			return;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (SectionFill fill : fills) {
			LevelChunk chunk;
			boolean justPublished = incoming != null && incoming.getPos().x() == fill.sectionX && incoming.getPos().z() == fill.sectionZ;
			if (justPublished) {
				chunk = incoming;
			} else {
				chunk = level.getChunkSource().getChunk(fill.sectionX, fill.sectionZ, ChunkStatus.FULL, false);
			}
			if (chunk == null) {
				continue;
			}
			int index = chunk.getSectionIndexFromSectionY(fill.sectionY);
			LevelChunkSection[] liveSections = chunk.getSections();
			if (index < 0 || index >= liveSections.length) {
				continue;
			}
			LevelChunkSection section = liveSections[index];
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
				seal(chunk, section, fill, bit, pos, stored, tags);
			}
			if (desired.isEmpty()) {
				applied.remove(fill.sectionKey);
			} else {
				applied.put(fill.sectionKey, desired);
			}
			// A chunk that has not been published yet is meshed from the filled section on load.
			// Dirtying neighbors here remeshes the whole border on the client thread.
			if (!justPublished && Minecraft.getInstance().level == level) {
				if (wasEmpty != section.hasOnlyAir()) {
					level.getChunkSource().onSectionEmptinessChanged(fill.sectionX, fill.sectionY, fill.sectionZ, section.hasOnlyAir());
				}
				level.setSectionDirtyWithNeighbors(fill.sectionX, fill.sectionY, fill.sectionZ);
			}
		}
	}

	private static void seal(
		LevelChunk chunk,
		LevelChunkSection section,
		SectionFill fill,
		int bit,
		BlockPos.MutableBlockPos pos,
		Long2ObjectOpenHashMap<BlockState> stored,
		Long2ObjectOpenHashMap<CompoundTag> tags
	) {
		int localX = bit & 15;
		int localY = (bit >> 8) & 15;
		int localZ = (bit >> 4) & 15;
		pos.set((fill.sectionX << 4) + localX, (fill.sectionY << 4) + localY, (fill.sectionZ << 4) + localZ);
		long packed = pos.asLong();
		BlockState previous = section.getBlockState(localX, localY, localZ);
		if (!stored.containsKey(packed)) {
			stored.put(packed, previous);
			BlockEntity blockEntity = chunk.getBlockEntity(pos);
			if (blockEntity != null) {
				tags.put(packed, blockEntity.saveWithFullMetadata(chunk.getLevel().registryAccess()));
				chunk.removeBlockEntity(pos);
			}
		}
		section.setBlockState(localX, localY, localZ, FILL);
		if (previous != FILL) {
			noteLight(chunk, pos, previous, FILL);
		}
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
		int localX = bit & 15;
		int localY = (bit >> 8) & 15;
		int localZ = (bit >> 4) & 15;
		pos.set((fill.sectionX << 4) + localX, (fill.sectionY << 4) + localY, (fill.sectionZ << 4) + localZ);
		long packed = pos.asLong();
		BlockState original = stored.remove(packed);
		CompoundTag tag = tags.remove(packed);
		if (original == null) {
			return;
		}
		BlockState previous = section.setBlockState(localX, localY, localZ, original);
		if (tag != null && original.hasBlockEntity()) {
			BlockPos immutable = pos.immutable();
			BlockEntity blockEntity = BlockEntity.loadStatic(immutable, original, tag, chunk.getLevel().registryAccess());
			if (blockEntity != null) {
				chunk.addAndRegisterBlockEntity(blockEntity);
			}
		}
		if (previous != null) {
			noteLight(chunk, pos, previous, original);
		}
	}

	private static void noteLight(LevelChunk chunk, BlockPos pos, BlockState before, BlockState after) {
		if (!LightEngine.hasDifferentLightProperties(before, after)) {
			return;
		}
		if (chunk.getLevel().dimensionType().hasSkyLight()) {
			chunk.getSkyLightSources().update(chunk, pos.getX() & 15, pos.getY(), pos.getZ() & 15);
		}
		chunk.getLevel().getChunkSource().getLightEngine().checkBlock(pos);
		relight.add(pos.asLong());
	}

	private static void flushRelight() {
		if (relight.isEmpty() || level == null || Minecraft.getInstance().level != level) {
			relight.clear();
			return;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		var engine = level.getChunkSource().getLightEngine();
		for (int i = 0; i < relight.size(); i++) {
			pos.set(relight.getLong(i));
			engine.checkBlock(pos);
		}
		relight.clear();
	}

	private static boolean clearAppliedBit(BlockPos pos) {
		long key = SectionPos.asLong(pos);
		BitSet bits = applied.get(key);
		if (bits == null) {
			return false;
		}
		int bit = ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
		boolean wasFilled = bits.get(bit);
		bits.clear(bit);
		return wasFilled;
	}

	private static void forgetColumn(int chunkX, int chunkZ) {
		long column = chunkKey(chunkX, chunkZ);
		originals.remove(column);
		blockEntities.remove(column);
		applied.long2ObjectEntrySet().removeIf(entry -> SectionPos.x(entry.getLongKey()) == chunkX && SectionPos.z(entry.getLongKey()) == chunkZ);
	}

	private static long chunkKey(int chunkX, int chunkZ) {
		return net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ);
	}
}
