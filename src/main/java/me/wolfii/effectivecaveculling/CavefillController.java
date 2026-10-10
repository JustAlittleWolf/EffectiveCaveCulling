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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
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
 * Applies solver output to the client world before any other mod is told the chunk exists.
 * A chunk that is not in the cache yet is solved off the render thread and inserted afterwards.
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
	/** Inserts and fill handoffs finished by the solver. Drained on the client thread only. */
	private static final ConcurrentLinkedQueue<Runnable> clientWork = new ConcurrentLinkedQueue<>();
	/** Bumped when the client world changes so a solve for the previous world cannot insert. */
	private static final AtomicInteger worldEpoch = new AtomicInteger();
	/** Bumped for each packet or drop of a column. An in-flight solve whose token no longer matches is discarded. */
	private static final ConcurrentHashMap<Long, Integer> generations = new ConcurrentHashMap<>();

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
	 * A chunk the client has not cached yet. The packet returns immediately; the solver fills the chunk,
	 * and a later client tick inserts it. Cancelling the vanilla insert is only safe when this returns true.
	 */
	public static boolean deferFreshChunk(LevelChunk chunk) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!CavefillConfig.allows(minecraft)) {
			catchUp = true;
			return false;
		}
		if (!prepare(chunk.getLevel())) {
			return false;
		}
		if (catchUp) {
			publishLoaded((ClientLevel) minecraft.level);
			catchUp = false;
		}
		int chunkX = chunk.getPos().x();
		int chunkZ = chunk.getPos().z();
		int generation = bump(chunkX, chunkZ);
		int epoch = worldEpoch.get();
		PlayerView view = playerView();
		ENGINE.execute(() -> solveFresh(chunk, view, generation, epoch));
		return true;
	}

	/**
	 * The packet replaced a chunk that is already in the cache. Other mods have already seen it, so this
	 * only removes fills, and it does not wait for the solver.
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
		// The packet just replaced this column. Put the previous pockets back immediately, then let the
		// solver drop any that the new blocks opened. Loaded columns never gain fills.
		int chunkX = chunk.getPos().x();
		int chunkZ = chunk.getPos().z();
		List<SectionFill> reseal = pocketFills(chunkX, chunkZ);
		forgetColumn(chunkX, chunkZ);
		if (!reseal.isEmpty()) {
			apply(reseal, chunk);
		}
		submitLoaded(chunk, true);
	}

	/**
	 * Bobby shallow-copies on {@code drop} / view moves, and Voxy ingests there when Bobby is installed.
	 * Publish solves that have already finished. Do not wait for ones that are still running.
	 */
	public static void beforeChunkSnapshotted() {
		if (level == null || !CavefillConfig.allows(Minecraft.getInstance())) {
			return;
		}
		if (!prepare(Minecraft.getInstance().level)) {
			return;
		}
		pump();
		applyDeferred();
	}

	/** The column was forgotten. An in-flight solve must not insert it afterwards. */
	public static void invalidateChunk(int chunkX, int chunkZ) {
		bump(chunkX, chunkZ);
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
			if (ENGINE.enqueueVertical(headAbove(minecraft, level), feetBelow(minecraft, level))) {
				solverDirty = true;
			}
		}
		if (solverDirty && ENGINE.startFlush(playerView())) {
			solverDirty = false;
		}
		pump();
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
		// In-flight solves hand their chunks back. Pump inserts a raw chunk when this world is still showing,
		// so turning the mod off does not leave a hole where a packet was deferred.
		worldEpoch.incrementAndGet();
		ENGINE.resetAndWait();
		ENGINE.clearClientVertical();
		pump();
		if (restore) {
			restoreAll();
		}
		applied.clear();
		originals.clear();
		blockEntities.clear();
		relight.clear();
		deferred.clear();
		clientWork.clear();
		generations.clear();
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
			submitLoaded(chunk, false);
		}
	}

	private static boolean prepare(Level current) {
		if (!(current instanceof ClientLevel client) || client.isDebug()) {
			if (level != null) {
				worldEpoch.incrementAndGet();
				ENGINE.resetAndWait();
				ENGINE.clearClientVertical();
				clientWork.clear();
				generations.clear();
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
			worldEpoch.incrementAndGet();
			ENGINE.resetAndWait();
			ENGINE.clearClientVertical();
			clientWork.clear();
			generations.clear();
			applied.clear();
			originals.clear();
			blockEntities.clear();
			relight.clear();
			deferred.clear();
			level = client;
		}
		return Minecraft.getInstance().player != null;
	}

	/** {@code bumpGeneration} invalidates a solve already queued for this column. Catch-up must not do that. */
	private static void submitLoaded(LevelChunk chunk, boolean bumpGeneration) {
		int chunkX = chunk.getPos().x();
		int chunkZ = chunk.getPos().z();
		int generation = bumpGeneration ? bump(chunkX, chunkZ) : generations.getOrDefault(chunkKey(chunkX, chunkZ), 0);
		int epoch = worldEpoch.get();
		PlayerView view = playerView();
		ChunkSnapshot snapshot = ChunkSnapshot.capture(chunk);
		ENGINE.execute(() -> {
			if (!current(chunkX, chunkZ, generation, epoch)) {
				return;
			}
			List<SectionFill> fills;
			try {
				fills = ENGINE.publish(snapshot, view, true, generation);
			} catch (RuntimeException exception) {
				Cavefill.LOGGER.error("Cave fill solver failed for chunk [{}, {}]", chunkX, chunkZ, exception);
				ENGINE.discardColumn(chunkX, chunkZ);
				return;
			}
			if (!current(chunkX, chunkZ, generation, epoch)) {
				ENGINE.discardColumn(chunkX, chunkZ);
				return;
			}
			if (fills.isEmpty()) {
				return;
			}
			clientWork.add(() -> {
				if (!current(chunkX, chunkZ, generation, epoch) || level == null) {
					return;
				}
				deferred.addAll(fills);
			});
		});
	}

	private static void solveFresh(LevelChunk chunk, PlayerView view, int generation, int epoch) {
		int chunkX = chunk.getPos().x();
		int chunkZ = chunk.getPos().z();
		if (!sameEpoch(epoch) || !sameGeneration(chunkX, chunkZ, generation)) {
			if (sameGeneration(chunkX, chunkZ, generation) && chunk.getLevel() == Minecraft.getInstance().level) {
				clientWork.add(() -> commitFresh(chunk, generation, epoch, List.of()));
			}
			return;
		}
		List<SectionFill> fills = List.of();
		try {
			fills = ENGINE.publish(ChunkSnapshot.capture(chunk), view, false, generation);
			if (!current(chunkX, chunkZ, generation, epoch)) {
				ENGINE.discardColumn(chunkX, chunkZ);
			}
		} catch (RuntimeException exception) {
			Cavefill.LOGGER.error("Cave fill solver failed for chunk [{}, {}]", chunkX, chunkZ, exception);
			ENGINE.discardColumn(chunkX, chunkZ);
			fills = List.of();
			if (!sameGeneration(chunkX, chunkZ, generation)) {
				return;
			}
		}
		List<SectionFill> handoff = fills;
		clientWork.add(() -> commitFresh(chunk, generation, epoch, handoff));
	}

	private static void commitFresh(LevelChunk chunk, int generation, int epoch, List<SectionFill> fills) {
		int chunkX = chunk.getPos().x();
		int chunkZ = chunk.getPos().z();
		if (!sameGeneration(chunkX, chunkZ, generation)) {
			// Replaced or forgotten. Neighbor unfills still stand; this column must not be inserted.
			if (sameEpoch(epoch) && level != null && chunk.getLevel() == minecraftLevel()) {
				deferOthers(chunkX, chunkZ, fills);
			}
			ENGINE.enqueueUnload(chunkX, chunkZ, generation);
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		boolean worldShowing = chunk.getLevel() == minecraft.level;
		if (!sameEpoch(epoch) || !worldShowing || level == null) {
			if (worldShowing) {
				insertChunk(chunk);
			}
			ENGINE.enqueueUnload(chunkX, chunkZ, generation);
			return;
		}
		if (!vacant(chunk)) {
			deferOthers(chunkX, chunkZ, fills);
			ENGINE.enqueueUnload(chunkX, chunkZ, generation);
			return;
		}
		List<SectionFill> mine = new ArrayList<>();
		for (SectionFill fill : fills) {
			if (fill.sectionX == chunkX && fill.sectionZ == chunkZ) {
				mine.add(fill);
			}
		}
		deferOthers(chunkX, chunkZ, fills);
		apply(mine, chunk);
		if (!sameGeneration(chunkX, chunkZ, generation) || !sameEpoch(epoch) || chunk.getLevel() != minecraft.level) {
			forgetColumn(chunkX, chunkZ);
			if (chunk.getLevel() == minecraft.level && sameGeneration(chunkX, chunkZ, generation)) {
				insertChunk(chunk);
			}
			ENGINE.enqueueUnload(chunkX, chunkZ, generation);
			return;
		}
		if (!insertChunk(chunk)) {
			forgetColumn(chunkX, chunkZ);
			ENGINE.enqueueUnload(chunkX, chunkZ, generation);
		}
	}

	/** Puts a filled chunk into the client cache and turns its light on. The server light packet may already have been stored. */
	private static boolean insertChunk(LevelChunk chunk) {
		if (!(chunk.getLevel() instanceof ClientLevel client) || client != Minecraft.getInstance().level) {
			return false;
		}
		int chunkX = chunk.getPos().x();
		int chunkZ = chunk.getPos().z();
		ClientChunkStorageAccessor slots = storage(client);
		if (!slots.cavefill$inRange(chunkX, chunkZ)) {
			return false;
		}
		int index = slots.cavefill$getIndex(chunkX, chunkZ);
		LevelChunk occupant = slots.cavefill$chunks().get(index);
		if (occupant != null) {
			Cavefill.LOGGER.warn("Skipped inserting cave-culled chunk [{}, {}] because that slot is already occupied", chunkX, chunkZ);
			return false;
		}
		slots.cavefill$replace(index, chunk);
		client.onChunkLoaded(new ChunkPos(chunkX, chunkZ));
		enableChunkLight(client, chunk);
		return true;
	}

	private static void enableChunkLight(ClientLevel client, LevelChunk chunk) {
		var lightEngine = client.getChunkSource().getLightEngine();
		LevelChunkSection[] sections = chunk.getSections();
		ChunkPos chunkPos = chunk.getPos();
		for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
			LevelChunkSection section = sections[sectionIndex];
			int sectionY = client.getSectionYFromSectionIndex(sectionIndex);
			lightEngine.updateSectionStatus(SectionPos.of(chunkPos, sectionY), section.hasOnlyAir());
		}
		int chunkX = chunkPos.x();
		int chunkZ = chunkPos.z();
		client.setSectionRangeDirty(chunkX - 1, client.getMinSectionY(), chunkZ - 1, chunkX + 1, client.getMaxSectionY(), chunkZ + 1);
	}

	private static boolean vacant(LevelChunk chunk) {
		if (!(chunk.getLevel() instanceof ClientLevel client)) {
			return false;
		}
		int chunkX = chunk.getPos().x();
		int chunkZ = chunk.getPos().z();
		ClientChunkStorageAccessor slots = storage(client);
		if (!slots.cavefill$inRange(chunkX, chunkZ)) {
			return false;
		}
		return slots.cavefill$chunks().get(slots.cavefill$getIndex(chunkX, chunkZ)) == null;
	}

	private static ClientChunkStorageAccessor storage(ClientLevel client) {
		Object slots = ((ClientChunkCacheAccessor) client.getChunkSource()).cavefill$storage();
		return (ClientChunkStorageAccessor) slots;
	}

	private static void pump() {
		Runnable task;
		while ((task = clientWork.poll()) != null) {
			try {
				task.run();
			} catch (RuntimeException exception) {
				Cavefill.LOGGER.error("Cave fill client handoff failed", exception);
			}
		}
		deferred.addAll(ENGINE.pollReady());
	}

	private static void deferOthers(int chunkX, int chunkZ, List<SectionFill> fills) {
		for (SectionFill fill : fills) {
			if (fill.sectionX != chunkX || fill.sectionZ != chunkZ) {
				deferred.add(fill);
			}
		}
	}

	/** Pockets already sealed in this column, copied so a packet replace can put them back before the solver runs. */
	private static List<SectionFill> pocketFills(int chunkX, int chunkZ) {
		List<SectionFill> fills = new ArrayList<>();
		for (var entry : applied.long2ObjectEntrySet()) {
			long key = entry.getLongKey();
			if (SectionPos.x(key) != chunkX || SectionPos.z(key) != chunkZ || entry.getValue().isEmpty()) {
				continue;
			}
			fills.add(new SectionFill(key, chunkX, SectionPos.y(key), chunkZ, (BitSet) entry.getValue().clone()));
		}
		return fills;
	}

	private static Level minecraftLevel() {
		return Minecraft.getInstance().level;
	}

	private static int bump(int chunkX, int chunkZ) {
		return generations.merge(chunkKey(chunkX, chunkZ), 1, Integer::sum);
	}

	private static boolean current(int chunkX, int chunkZ, int generation, int epoch) {
		return sameEpoch(epoch) && sameGeneration(chunkX, chunkZ, generation);
	}

	private static boolean sameEpoch(int epoch) {
		return epoch == worldEpoch.get();
	}

	private static boolean sameGeneration(int chunkX, int chunkZ, int generation) {
		return generations.getOrDefault(chunkKey(chunkX, chunkZ), 0) == generation;
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
		return new PlayerView(body, headAbove(Minecraft.getInstance(), level), feetBelow(Minecraft.getInstance(), level));
	}

	private static boolean headAbove(Minecraft minecraft, ClientLevel level) {
		return Mth.floor(minecraft.player.getBoundingBox().maxY) > level.getMaxY();
	}

	private static boolean feetBelow(Minecraft minecraft, ClientLevel level) {
		return Mth.floor(minecraft.player.getY()) < level.getMinY();
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
