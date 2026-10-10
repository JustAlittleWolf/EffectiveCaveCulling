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
 */
public final class CavefillController {
	private static final CaveEngine ENGINE = new CaveEngine();
	private static final BlockState FILL = Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState();

	private static final Long2ObjectOpenHashMap<BitSet> applied = new Long2ObjectOpenHashMap<>();
	private static final Long2ObjectOpenHashMap<OriginalColumn> originals = new Long2ObjectOpenHashMap<>();
	/** Block positions whose light must be checked again after the server light packet is applied. */
	private static final LongArrayList relight = new LongArrayList();

	private static ClientLevel level;
	private static boolean solverDirty;
	/** Chunks arrived while the mod was off, so enabling has to read what is already loaded. */
	private static boolean catchUp;
	private static boolean spectating;

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
		// The packet just replaced this column, so the previous copy and fill mask are stale.
		remember(chunk);
		forgetApplied(chunk.getPos().x(), chunk.getPos().z());
		ENGINE.publishAndWait(ChunkSnapshot.capture(chunk), playerView());
		if (hideFills()) {
			ENGINE.drain();
		} else {
			apply(ENGINE.drain(), chunk);
		}
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
		if (!solverDirty && ENGINE.idle()) {
			return;
		}
		ENGINE.flushAndWait(playerView());
		if (hideFills()) {
			ENGINE.drain();
		} else {
			apply(ENGINE.drain(), null);
		}
		solverDirty = false;
	}

	public static void afterChunkUnloaded(int chunkX, int chunkZ) {
		originals.remove(chunkKey(chunkX, chunkZ));
		forgetApplied(chunkX, chunkZ);
		ENGINE.enqueueUnload(chunkX, chunkZ);
	}

	public static void onRealBlockChanged(LevelChunk chunk, BlockPos pos, BlockState state) {
		if (level == null || chunk.getLevel() != level || !chunk.getLevel().isClientSide()) {
			return;
		}
		long packed = pos.asLong();
		OriginalColumn copy = originals.get(chunkKey(pos.getX() >> 4, pos.getZ() >> 4));
		if (copy != null) {
			int index = chunk.getSectionIndex(pos.getY());
			if (index >= 0 && index < copy.sections.length) {
				copy.sections[index].setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
			}
			if (state.hasBlockEntity()) {
				BlockEntity blockEntity = chunk.getBlockEntity(pos);
				if (blockEntity != null) {
					copy.blockEntities.put(packed, blockEntity.saveWithFullMetadata(chunk.getLevel().registryAccess()));
				} else {
					copy.blockEntities.remove(packed);
				}
			} else {
				copy.blockEntities.remove(packed);
			}
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
		syncSpectator(minecraft);
		if (catchUp || level == null) {
			publishCatchUp(minecraft);
		}
		if (ENGINE.flushFinished()) {
			ENGINE.finishFlush();
			if (hideFills()) {
				ENGINE.drain();
			} else {
				apply(ENGINE.drain(), null);
			}
		}
		if (solverDirty && ENGINE.startFlush(playerView())) {
			solverDirty = false;
		}
		flushRelight();
	}

	public static boolean isFake(BlockPos pos) {
		BitSet bits = applied.get(SectionPos.asLong(pos));
		if (bits == null) {
			return false;
		}
		return bits.get(((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15));
	}

	private static void syncSpectator(Minecraft minecraft) {
		boolean spectator = minecraft.player != null && minecraft.player.isSpectator();
		if (spectator == spectating) {
			return;
		}
		spectating = spectator;
		if (spectator) {
			restoreAll();
			return;
		}
		if (level != null) {
			ENGINE.replayAndWait();
			apply(ENGINE.drain(), null);
		}
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
		applied.clear();
		originals.clear();
		relight.clear();
		solverDirty = false;
		spectating = false;
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
			long key = chunkKey(chunk.getPos().x(), chunk.getPos().z());
			originals.computeIfAbsent(key, ignored -> OriginalColumn.capture(chunk));
			ENGINE.publishAndWait(ChunkSnapshot.capture(chunk), playerView());
		}
		if (hideFills()) {
			ENGINE.drain();
		} else {
			apply(ENGINE.drain(), null);
		}
	}

	private static boolean hideFills() {
		if (spectating) {
			return true;
		}
		var player = Minecraft.getInstance().player;
		return player != null && player.isSpectator();
	}

	private static void remember(LevelChunk chunk) {
		originals.put(chunkKey(chunk.getPos().x(), chunk.getPos().z()), OriginalColumn.capture(chunk));
	}

	private static boolean prepare(Level current) {
		if (!(current instanceof ClientLevel client) || client.isDebug()) {
			if (level != null) {
				ENGINE.resetAndWait();
				applied.clear();
				originals.clear();
				relight.clear();
				spectating = false;
				level = null;
			}
			return false;
		}
		if (client != level) {
			ENGINE.resetAndWait();
			applied.clear();
			originals.clear();
			relight.clear();
			spectating = false;
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
			OriginalColumn stored = originals.get(chunkKey(fill.sectionX, fill.sectionZ));
			if (stored == null || index >= stored.sections.length) {
				continue;
			}
			LevelChunkSection section = liveSections[index];
			LevelChunkSection copy = stored.sections[index];
			BitSet desired = (BitSet) fill.fill.clone();
			BitSet current = applied.get(fill.sectionKey);
			if (current == null) {
				current = new BitSet(4096);
			}
			boolean wasEmpty = section.hasOnlyAir();

			for (int bit = current.nextSetBit(0); bit >= 0; bit = current.nextSetBit(bit + 1)) {
				if (desired.get(bit)) {
					continue;
				}
				restore(chunk, section, copy, stored, fill, bit, pos);
			}
			for (int bit = desired.nextSetBit(0); bit >= 0; bit = desired.nextSetBit(bit + 1)) {
				if (current.get(bit)) {
					continue;
				}
				seal(chunk, section, stored, fill, bit, pos);
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
		OriginalColumn stored,
		SectionFill fill,
		int bit,
		BlockPos.MutableBlockPos pos
	) {
		int localX = bit & 15;
		int localY = (bit >> 8) & 15;
		int localZ = (bit >> 4) & 15;
		pos.set((fill.sectionX << 4) + localX, (fill.sectionY << 4) + localY, (fill.sectionZ << 4) + localZ);
		BlockState previous = section.setBlockState(localX, localY, localZ, FILL);
		BlockEntity blockEntity = chunk.getBlockEntity(pos);
		if (blockEntity != null) {
			stored.blockEntities.put(pos.asLong(), blockEntity.saveWithFullMetadata(chunk.getLevel().registryAccess()));
			chunk.removeBlockEntity(pos);
		}
		if (previous != null && previous != FILL) {
			noteLight(chunk, pos, previous, FILL);
		}
	}

	private static void restore(
		LevelChunk chunk,
		LevelChunkSection section,
		LevelChunkSection copy,
		OriginalColumn stored,
		SectionFill fill,
		int bit,
		BlockPos.MutableBlockPos pos
	) {
		int localX = bit & 15;
		int localY = (bit >> 8) & 15;
		int localZ = (bit >> 4) & 15;
		pos.set((fill.sectionX << 4) + localX, (fill.sectionY << 4) + localY, (fill.sectionZ << 4) + localZ);
		BlockState original = copy.getBlockState(localX, localY, localZ);
		BlockState previous = section.setBlockState(localX, localY, localZ, original);
		CompoundTag tag = stored.blockEntities.get(pos.asLong());
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

	private static void forgetApplied(int chunkX, int chunkZ) {
		applied.long2ObjectEntrySet().removeIf(entry -> SectionPos.x(entry.getLongKey()) == chunkX && SectionPos.z(entry.getLongKey()) == chunkZ);
	}

	private static long chunkKey(int chunkX, int chunkZ) {
		return net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ);
	}

	/** Section palette copy plus the block-entity tags needed to put the real column back. */
	private static final class OriginalColumn {
		final LevelChunkSection[] sections;
		final Long2ObjectOpenHashMap<CompoundTag> blockEntities;

		private OriginalColumn(LevelChunkSection[] sections, Long2ObjectOpenHashMap<CompoundTag> blockEntities) {
			this.sections = sections;
			this.blockEntities = blockEntities;
		}

		static OriginalColumn capture(LevelChunk chunk) {
			LevelChunkSection[] raw = chunk.getSections();
			LevelChunkSection[] sections = new LevelChunkSection[raw.length];
			for (int i = 0; i < raw.length; i++) {
				sections[i] = raw[i].copy();
			}
			Long2ObjectOpenHashMap<CompoundTag> tags = new Long2ObjectOpenHashMap<>();
			var registries = chunk.getLevel().registryAccess();
			for (var entry : chunk.getBlockEntities().entrySet()) {
				tags.put(entry.getKey().asLong(), entry.getValue().saveWithFullMetadata(registries));
			}
			return new OriginalColumn(sections, tags);
		}
	}
}
