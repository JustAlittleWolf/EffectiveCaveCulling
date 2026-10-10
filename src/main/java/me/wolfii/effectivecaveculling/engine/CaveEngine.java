package me.wolfii.effectivecaveculling.engine;

import me.wolfii.effectivecaveculling.Cavefill;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

/**
 * Cached air volumes for loaded chunks. Each connected non-solid component stores its block count,
 * whether it is open, and which chunk columns it still touches that are not loaded.
 * A new chunk looks those roots up across its faces. It does not rescan the world.
 * Volumes outside the build height are solid, except the top while the player is above the world
 * and the bottom while the player is below it.
 * Chunks that have already been published only lose fills.
 */
public final class CaveEngine {
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "effectivecaveculling-solver");
		thread.setDaemon(true);
		return thread;
	});
	private static final long[] NO_PENDING = new long[0];

	private final ConcurrentLinkedQueue<SectionFill> pending = new ConcurrentLinkedQueue<>();
	/** Fills drained on the solver thread and waiting for the client thread. The client thread never polls {@link #pending}. */
	private final ConcurrentLinkedQueue<List<SectionFill>> ready = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<BlockEdit> blockEdits = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Vertical> vertical = new ConcurrentLinkedQueue<>();
	private Future<?> async;

	private final Long2ObjectOpenHashMap<SectionSnapshot> snapshots = new Long2ObjectOpenHashMap<>();
	/** Section keys in one chunk column, so a drop does not scan every loaded section. */
	private final Long2ObjectOpenHashMap<LongArrayList> sectionKeys = new Long2ObjectOpenHashMap<>();
	/** Chunk columns already indexed. Republishing one replaces its sections instead of merging. */
	private final LongOpenHashSet columns = new LongOpenHashSet();
	/** Generation that last integrated each column. Zero means the column has no recorded generation. */
	private final Long2IntOpenHashMap integrated = new Long2IntOpenHashMap();
	/** First open-air Y per column of a loaded chunk. Missing sections at or above this are sky, not solid. */
	private final Long2ObjectOpenHashMap<int[]> ceilings = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<SectionModel> models = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet settled = new LongOpenHashSet();
	private final Long2ObjectOpenHashMap<BitSet> lastEmitted = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet dirty = new LongOpenHashSet();
	/** Sections whose live blocks diverged from the last fill and must be written again. */
	private final LongOpenHashSet reemit = new LongOpenHashSet();
	/** Root id to chunk columns that volume still touches which are not loaded. */
	private final Int2ObjectOpenHashMap<LongOpenHashSet> pendingByRoot = new Int2ObjectOpenHashMap<>();
	/** Chunk column to the volume roots waiting on it. */
	private final Long2ObjectOpenHashMap<IntOpenHashSet> pendingRoots = new Long2ObjectOpenHashMap<>();

	private final ArrayList<Comp> comps = new ArrayList<>();
	private final Int2ObjectOpenHashMap<IntArrayList> compsByRoot = new Int2ObjectOpenHashMap<>();
	/** Roots the player can walk to, or that are open sky / an exempt cavern. Passable blocks outside these are filled. */
	private final IntOpenHashSet open = new IntOpenHashSet();
	private final Int2IntOpenHashMap sizeByRoot = new Int2IntOpenHashMap();
	private int[] parent = new int[1024];
	private Comp[] byId = new Comp[1024];
	private int nextId = 1;

	private int minY = Integer.MIN_VALUE;
	private int maxY = Integer.MAX_VALUE;
	private boolean hasSkyLight = true;
	private boolean playerAbove;
	private boolean playerBelow;
	private PlayerView player = new PlayerView(new int[] {0, 64, 0}, false, false);
	/** Client thread only. Skips duplicate vertical hints. */
	private boolean clientSawVertical;
	private boolean clientAbove;
	private boolean clientBelow;

	/** Runs {@code task} on the solver thread. The task must not wait for this executor. */
	public void execute(Runnable task) {
		this.worker.execute(() -> {
			try {
				task.run();
			} catch (Exception exception) {
				Cavefill.LOGGER.error("Cave fill solver failed", exception);
			}
		});
	}

	/**
	 * Integrates a chunk on the solver thread and returns the fills that integration emitted.
	 * {@code alreadyInWorld} indexes a chunk that other mods have already seen, and does not add fills.
	 * A generation other than the one already stored for this column drops that column first, so the new chunk can be filled.
	 */
	public List<SectionFill> publish(ChunkSnapshot chunk, PlayerView view, boolean alreadyInWorld, int generation) {
		RuntimeException failure = null;
		try {
			this.publish0(chunk, view, alreadyInWorld, generation);
		} catch (RuntimeException exception) {
			failure = exception;
		}
		List<SectionFill> fills = this.drain();
		if (failure != null) {
			throw failure;
		}
		return fills;
	}

	/** Drops a column immediately. Solver thread only. */
	public void discardColumn(int chunkX, int chunkZ) {
		this.dropChunk(chunkX, chunkZ);
	}

	public void enqueueBlock(int x, int y, int z, boolean passable) {
		this.blockEdits.add(new BlockEdit(x, y, z, passable, false));
	}

	/** A real block change replaced a fill. The section has to be written again even if the mask is unchanged. */
	public void enqueueReveal(int x, int y, int z, boolean passable) {
		this.blockEdits.add(new BlockEdit(x, y, z, passable, true));
	}

	/**
	 * The top of the world is open while {@code above} is set, and the bottom while {@code below} is set.
	 * Returns whether this changed the queued state. Call from the client thread only.
	 */
	public boolean enqueueVertical(boolean above, boolean below) {
		if (this.clientSawVertical && above == this.clientAbove && below == this.clientBelow) {
			return false;
		}
		this.clientSawVertical = true;
		this.clientAbove = above;
		this.clientBelow = below;
		this.vertical.add(new Vertical(above, below));
		return true;
	}

	public void clearClientVertical() {
		this.clientSawVertical = false;
	}

	/** No queued edits and no flush in flight. View moves can skip the client-thread wait. */
	public boolean idle() {
		return this.blockEdits.isEmpty() && this.vertical.isEmpty() && (this.async == null || this.async.isDone());
	}

	/** Start a background catch-up if one is not already running. Fills are published through {@link #pollReady()}. */
	public boolean startFlush(PlayerView view) {
		if (this.async != null && !this.async.isDone()) {
			return false;
		}
		this.async = this.worker.submit(() -> {
			try {
				this.flush0(view);
			} catch (Exception exception) {
				Cavefill.LOGGER.error("Cave fill solver failed", exception);
			} finally {
				this.ready.add(this.drain());
			}
		});
		return true;
	}

	public boolean flushFinished() {
		return this.async != null && this.async.isDone();
	}

	public void finishFlush() {
		if (this.async == null) {
			return;
		}
		this.join(this.async);
		this.async = null;
	}

	public void resetAndWait() {
		this.finishFlush();
		this.join(this.worker.submit(this::reset0));
	}

	/** Client thread. Fills the solver has already detached from the live queue. */
	public List<SectionFill> pollReady() {
		List<SectionFill> fills = new ArrayList<>();
		List<SectionFill> batch;
		while ((batch = this.ready.poll()) != null) {
			fills.addAll(batch);
		}
		return fills;
	}

	private List<SectionFill> drain() {
		List<SectionFill> fills = new ArrayList<>();
		SectionFill fill;
		while ((fill = this.pending.poll()) != null) {
			fills.add(fill);
		}
		return fills;
	}

	public void shutdown() {
		this.worker.shutdownNow();
	}

	private void join(Future<?> future) {
		try {
			future.get();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			Cavefill.LOGGER.warn("Cave fill solver interrupted");
		} catch (Exception exception) {
			Cavefill.LOGGER.error("Cave fill solver failed", exception);
		}
	}

	private void publish0(ChunkSnapshot chunk, PlayerView view, boolean alreadyInWorld, int generation) {
		this.player = view;
		this.minY = chunk.minY;
		this.maxY = chunk.maxY;
		this.hasSkyLight = chunk.hasSkyLight;
		this.applyVertical();
		this.applyOutside(view.above, view.below);
		this.applyQueuedEdits();

		long column = ChunkPos.pack(chunk.chunkX, chunk.chunkZ);
		int previous = this.integrated.get(column);
		// A newer copy of a chunk that was never published to the client replaces the old integration outright.
		if (!alreadyInWorld && previous != 0 && previous != generation) {
			this.dropChunk(chunk.chunkX, chunk.chunkZ);
		}
		boolean loaded = alreadyInWorld || this.columns.contains(column);
		Long2ObjectOpenHashMap<BitSet> kept = loaded ? this.takeEmitted(column) : null;
		if (loaded) {
			this.dropChunk(chunk.chunkX, chunk.chunkZ);
		}
		this.columns.add(column);
		if (chunk.firstOpenY != null) {
			this.ceilings.put(column, chunk.firstOpenY);
		}
		LongArrayList keys = new LongArrayList(chunk.sections.length);
		for (SectionSnapshot section : chunk.sections) {
			this.snapshots.put(section.key, section);
			keys.add(section.key);
		}
		this.sectionKeys.put(column, keys);

		this.integrateNewChunk(chunk);
		this.seedPlayer();
		this.resolvePending(column);
		this.exemptOpenCaverns(chunk);
		if (loaded) {
			// Already published once. Re-seal only pockets that were already filled.
			for (SectionSnapshot section : chunk.sections) {
				this.settled.add(section.key);
			}
			if (kept != null) {
				for (var entry : kept.long2ObjectEntrySet()) {
					this.lastEmitted.put(entry.getLongKey(), entry.getValue());
				}
			}
		}
		if (generation != 0) {
			this.integrated.put(column, generation);
		}
		this.emit();
	}

	private void flush0(PlayerView view) {
		this.player = view;
		this.applyVertical();
		this.applyOutside(view.above, view.below);
		this.applyQueuedEdits();
		this.emit();
	}

	private Long2ObjectOpenHashMap<BitSet> takeEmitted(long column) {
		Long2ObjectOpenHashMap<BitSet> kept = new Long2ObjectOpenHashMap<>();
		LongArrayList keys = this.sectionKeys.get(column);
		if (keys == null) {
			return kept;
		}
		for (int i = 0; i < keys.size(); i++) {
			long key = keys.getLong(i);
			BitSet fill = this.lastEmitted.get(key);
			if (fill != null && !fill.isEmpty()) {
				kept.put(key, fill);
			}
		}
		return kept;
	}

	private void applyQueuedEdits() {
		BlockEdit edit;
		while ((edit = this.blockEdits.poll()) != null) {
			this.applyEdit(edit);
		}
	}

	private void applyEdit(BlockEdit edit) {
		if (edit.y < this.minY || edit.y > this.maxY) {
			return;
		}
		long key = Grid.sectionKey(edit.x, edit.y, edit.z);
		if (edit.reveal) {
			this.reemit.add(key);
			this.dirty.add(key);
		}
		SectionSnapshot section = this.snapshots.get(key);
		if (section == null) {
			return;
		}
		int local = Grid.index(edit.x & 15, edit.y & 15, edit.z & 15);
		if (section.passable.get(local) == edit.passable) {
			return;
		}
		BitSet passable = (BitSet) section.passable.clone();
		passable.set(local, edit.passable);
		this.snapshots.put(key, section.withPassable(passable, section.sky));
		this.refreshSky(section.sx, section.sz);
		this.rebuildSection(key);
	}

	private void dropChunk(int chunkX, int chunkZ) {
		long column = ChunkPos.pack(chunkX, chunkZ);
		this.columns.remove(column);
		this.integrated.remove(column);
		this.ceilings.remove(column);
		LongArrayList keys = this.sectionKeys.remove(column);
		if (keys == null) {
			return;
		}
		for (int i = 0; i < keys.size(); i++) {
			long key = keys.getLong(i);
			this.retire(this.models.remove(key));
			this.snapshots.remove(key);
			this.settled.remove(key);
			this.lastEmitted.remove(key);
			this.dirty.remove(key);
			this.reemit.remove(key);
		}
	}

	public void enqueueUnload(int chunkX, int chunkZ) {
		// Dropping a chunk must not rebuild. A pocket that only looked open through that column stays real:
		// a later edit may open a filled pocket, but it must not fill something that was reachable.
		this.worker.execute(() -> this.dropChunk(chunkX, chunkZ));
	}

	/** Drops the column only if it is still the integration from {@code generation}, so a newer solve is left alone. */
	public void enqueueUnload(int chunkX, int chunkZ, int generation) {
		this.worker.execute(() -> {
			if (this.integrated.get(ChunkPos.pack(chunkX, chunkZ)) == generation) {
				this.dropChunk(chunkX, chunkZ);
			}
		});
	}

	private void reset0() {
		this.snapshots.clear();
		this.sectionKeys.clear();
		this.columns.clear();
		this.integrated.clear();
		this.ceilings.clear();
		this.models.clear();
		this.settled.clear();
		this.lastEmitted.clear();
		this.dirty.clear();
		this.reemit.clear();
		this.pendingByRoot.clear();
		this.pendingRoots.clear();
		this.comps.clear();
		this.compsByRoot.clear();
		this.open.clear();
		this.sizeByRoot.clear();
		this.parent = new int[1024];
		this.byId = new Comp[1024];
		this.nextId = 1;
		this.playerAbove = false;
		this.playerBelow = false;
		this.pending.clear();
		this.ready.clear();
		this.blockEdits.clear();
		this.vertical.clear();
	}

	private void applyVertical() {
		Vertical last = null;
		Vertical next;
		while ((next = this.vertical.poll()) != null) {
			last = next;
		}
		if (last != null) {
			this.applyOutside(last.above, last.below);
		}
	}

	private void applyOutside(boolean above, boolean below) {
		boolean openTop = above && !this.playerAbove;
		boolean openBottom = below && !this.playerBelow;
		this.playerAbove = above;
		this.playerBelow = below;
		if (openTop) {
			this.openBorder(true);
		}
		if (openBottom) {
			this.openBorder(false);
		}
	}

	/** Volumes that touch the void the player just entered become open. Already loaded chunks only lose fills. */
	private void openBorder(boolean above) {
		for (Comp comp : this.comps) {
			if (comp.dead) {
				continue;
			}
			if (above ? comp.bordersAbove : comp.bordersBelow) {
				this.markOpen(comp);
			}
		}
	}

	private void integrateNewChunk(ChunkSnapshot chunk) {
		for (SectionSnapshot snapshot : chunk.sections) {
			this.rebuildSection(snapshot.key);
		}
	}

	/** Replace one section's components and union them with the cached neighbor volumes. */
	private void rebuildSection(long key) {
		SectionSnapshot snapshot = this.snapshots.get(key);
		if (snapshot == null) {
			return;
		}
		this.retire(this.models.remove(key));
		SectionModel model = this.buildModel(snapshot);
		this.models.put(key, model);
		this.dirty.add(key);
		for (Comp comp : model.comps) {
			this.register(comp);
			if (comp.sky || comp.exterior) {
				this.markOpen(comp);
			}
		}
		this.linkPositiveFaces(model);
		this.linkIncomingFaces(model);
	}

	private void retire(SectionModel model) {
		if (model == null) {
			return;
		}
		for (Comp comp : model.comps) {
			if (comp.dead) {
				continue;
			}
			comp.dead = true;
			int root = this.find(comp.id);
			int left = this.sizeByRoot.get(root) - comp.size;
			if (left > 0) {
				this.sizeByRoot.put(root, left);
			} else {
				this.sizeByRoot.remove(root);
			}
		}
	}

	private void register(Comp comp) {
		int id = comp.id;
		this.ensureParent(id);
		this.parent[id] = id;
		this.comps.add(comp);
		int index = this.comps.size() - 1;
		IntArrayList members = new IntArrayList(1);
		members.add(index);
		this.compsByRoot.put(id, members);
		this.sizeByRoot.put(id, comp.size);
		for (long column : comp.pending) {
			this.watchPending(id, column);
		}
	}

	private void watchPending(int root, long column) {
		IntOpenHashSet roots = this.pendingRoots.get(column);
		if (roots == null) {
			roots = new IntOpenHashSet();
			this.pendingRoots.put(column, roots);
		}
		roots.add(root);
		LongOpenHashSet columns = this.pendingByRoot.get(root);
		if (columns == null) {
			columns = new LongOpenHashSet();
			this.pendingByRoot.put(root, columns);
		}
		columns.add(column);
	}

	private void resolvePending(long column) {
		IntOpenHashSet roots = this.pendingRoots.remove(column);
		if (roots == null) {
			return;
		}
		for (int root : roots) {
			LongOpenHashSet columns = this.pendingByRoot.get(this.find(root));
			if (columns != null) {
				columns.remove(column);
			}
		}
	}

	private void linkPositiveFaces(SectionModel model) {
		this.linkFace(model, 1);
		this.linkFace(model, 3);
		this.linkFace(model, 5);
	}

	/** Faces that point back into an already indexed neighbor (the negative side). */
	private void linkIncomingFaces(SectionModel model) {
		this.linkFace(model, 0);
		this.linkFace(model, 2);
		this.linkFace(model, 4);
	}

	private void linkFace(SectionModel model, int direction) {
		int nsx = model.snapshot.sx + Grid.DX[direction];
		int nsy = model.snapshot.sy + Grid.DY[direction];
		int nsz = model.snapshot.sz + Grid.DZ[direction];
		SectionModel neighbor = this.models.get(SectionPos.asLong(nsx, nsy, nsz));
		if (neighbor == null || neighbor.comps.length == 0) {
			return;
		}
		for (int u = 0; u < 16; u++) {
			for (int v = 0; v < 16; v++) {
				int local = faceIndex(direction, u, v, true);
				int other = faceIndex(direction, u, v, false);
				if (!model.snapshot.passable.get(local) || !neighbor.snapshot.passable.get(other)) {
					continue;
				}
				this.union(model.compAt(local), neighbor.compAt(other));
			}
		}
	}

	private static int faceIndex(int direction, int u, int v, boolean ownSide) {
		int x;
		int y;
		int z;
		switch (direction) {
			case 0 -> {
				x = u;
				z = v;
				y = ownSide ? 0 : 15;
			}
			case 1 -> {
				x = u;
				z = v;
				y = ownSide ? 15 : 0;
			}
			case 2 -> {
				x = u;
				y = v;
				z = ownSide ? 0 : 15;
			}
			case 3 -> {
				x = u;
				y = v;
				z = ownSide ? 15 : 0;
			}
			case 4 -> {
				y = u;
				z = v;
				x = ownSide ? 0 : 15;
			}
			default -> {
				y = u;
				z = v;
				x = ownSide ? 15 : 0;
			}
		}
		return Grid.index(x, y, z);
	}

	private void seedPlayer() {
		int[] body = this.player.body;
		for (int i = 0; i + 2 < body.length; i += 3) {
			Comp comp = this.compAt(body[i], body[i + 1], body[i + 2]);
			if (comp != null) {
				this.markOpen(comp);
			}
		}
	}

	private void exemptOpenCaverns(ChunkSnapshot chunk) {
		if (this.hasSkyLight) {
			return;
		}
		for (SectionSnapshot section : chunk.sections) {
			SectionModel model = this.models.get(section.key);
			if (model == null) {
				continue;
			}
			for (Comp comp : model.comps) {
				this.exemptRoot(this.find(comp.id));
			}
		}
	}

	private void exemptRoot(int root) {
		LongOpenHashSet columns = this.pendingByRoot.get(root);
		if (columns == null || columns.isEmpty()) {
			return;
		}
		if (this.sizeByRoot.get(root) < Cavefill.OPEN_CAVERN_BLOCKS) {
			return;
		}
		if (this.isOpen(root)) {
			return;
		}
		Comp any = this.firstLive(root);
		if (any != null) {
			this.markOpen(any);
		}
	}

	private Comp firstLive(int root) {
		IntArrayList members = this.compsByRoot.get(root);
		if (members == null) {
			return null;
		}
		for (int i = 0; i < members.size(); i++) {
			Comp comp = this.comps.get(members.getInt(i));
			if (!comp.dead && this.find(comp.id) == root) {
				return comp;
			}
		}
		return null;
	}

	private void markOpen(Comp comp) {
		if (comp == null || comp.dead) {
			return;
		}
		int root = this.find(comp.id);
		if (!this.open.add(root)) {
			return;
		}
		this.dirtyMembers(root);
	}

	private boolean isOpen(int root) {
		return this.open.contains(this.find(root));
	}

	private void dirtyMembers(int root) {
		IntArrayList members = this.compsByRoot.get(root);
		if (members == null) {
			return;
		}
		for (int i = 0; i < members.size(); i++) {
			Comp comp = this.comps.get(members.getInt(i));
			if (!comp.dead && this.find(comp.id) == root) {
				this.dirty.add(comp.sectionKey);
			}
		}
	}

	private void mergePending(int ra, int rb) {
		LongOpenHashSet moved = this.pendingByRoot.remove(rb);
		if (moved == null) {
			return;
		}
		for (long column : moved) {
			IntOpenHashSet roots = this.pendingRoots.get(column);
			if (roots != null) {
				roots.remove(rb);
				roots.add(ra);
			}
		}
		LongOpenHashSet dest = this.pendingByRoot.get(ra);
		if (dest == null) {
			this.pendingByRoot.put(ra, moved);
		} else {
			dest.addAll(moved);
		}
	}

	private void union(Comp left, Comp right) {
		if (left == null || right == null || left == right) {
			return;
		}
		int ra = this.find(left.id);
		int rb = this.find(right.id);
		if (ra == rb) {
			return;
		}
		this.parent[rb] = ra;
		this.sizeByRoot.put(ra, this.sizeByRoot.get(ra) + this.sizeByRoot.get(rb));
		this.sizeByRoot.remove(rb);
		this.mergePending(ra, rb);
		IntArrayList membersA = this.compsByRoot.get(ra);
		IntArrayList membersB = this.compsByRoot.get(rb);
		if (membersA == null) {
			membersA = new IntArrayList();
			this.compsByRoot.put(ra, membersA);
		}
		int kept = membersA.size();
		if (membersB != null) {
			membersA.addAll(membersB);
			this.compsByRoot.remove(rb);
		}
		boolean openA = this.open.contains(ra);
		boolean openB = this.open.contains(rb);
		if (openA || openB) {
			this.open.add(ra);
			this.open.remove(rb);
		}
		if (openB && !openA) {
			this.dirtyRange(membersA, 0, kept);
		}
		if (openA && !openB && membersB != null) {
			this.dirtyRange(membersB, 0, membersB.size());
		}
	}

	private void dirtyRange(IntArrayList members, int from, int to) {
		for (int i = from; i < to; i++) {
			Comp comp = this.comps.get(members.getInt(i));
			if (!comp.dead) {
				this.dirty.add(comp.sectionKey);
			}
		}
	}

	private int find(int id) {
		int root = id;
		while (this.parent[root] != root) {
			root = this.parent[root];
		}
		int cursor = id;
		while (this.parent[cursor] != root) {
			int next = this.parent[cursor];
			this.parent[cursor] = root;
			cursor = next;
		}
		return root;
	}

	private void ensureParent(int id) {
		if (id >= this.parent.length) {
			int size = Math.max(this.parent.length * 2, id + 16);
			this.parent = Arrays.copyOf(this.parent, size);
			this.byId = Arrays.copyOf(this.byId, size);
		}
	}

	private SectionModel buildModel(SectionSnapshot snapshot) {
		int count = snapshot.passable.cardinality();
		if (count == 0) {
			return new SectionModel(snapshot, new Comp[0], null);
		}
		if (count == Grid.VOLUME) {
			Comp comp = this.makeUniform(snapshot);
			return new SectionModel(snapshot, new Comp[] {comp}, null);
		}

		short[] local = new short[Grid.VOLUME];
		Arrays.fill(local, (short) -1);
		List<Comp> built = new ArrayList<>();
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		for (int start = snapshot.passable.nextSetBit(0); start >= 0; start = snapshot.passable.nextSetBit(start + 1)) {
			if (local[start] != -1) {
				continue;
			}
			short id = (short) built.size();
			IntArrayList boundary = new IntArrayList();
			int size = 0;
			boolean sky = false;
			Border border = new Border();
			local[start] = id;
			queue.add(start);
			while (!queue.isEmpty()) {
				int index = queue.poll();
				size++;
				if (snapshot.sky.get(index)) {
					sky = true;
				}
				int x = Grid.localX(index);
				int y = Grid.localY(index);
				int z = Grid.localZ(index);
				boolean edge = false;
				for (int direction = 0; direction < 6; direction++) {
					int nx = x + Grid.DX[direction];
					int ny = y + Grid.DY[direction];
					int nz = z + Grid.DZ[direction];
					if (nx < 0 || ny < 0 || nz < 0 || nx > 15 || ny > 15 || nz > 15) {
						if (border.blocked(this, snapshot.originX + nx, snapshot.originY + ny, snapshot.originZ + nz)) {
							edge = true;
						}
						continue;
					}
					int next = Grid.index(nx, ny, nz);
					if (!snapshot.passable.get(next)) {
						edge = true;
					} else if (local[next] == -1) {
						local[next] = id;
						queue.add(next);
					}
				}
				if (edge) {
					boundary.add(index);
				}
			}
			built.add(this.track(this.component(snapshot, boundary.toIntArray(), size, sky, border)));
		}
		return new SectionModel(snapshot, built.toArray(Comp[]::new), local);
	}

	private Comp makeUniform(SectionSnapshot snapshot) {
		IntArrayList boundary = new IntArrayList();
		boolean sky = snapshot.sky.cardinality() > 0;
		Border border = new Border();
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					boolean edge = x == 0 || y == 0 || z == 0 || x == 15 || y == 15 || z == 15;
					if (!edge) {
						continue;
					}
					boolean solidNeighbor = false;
					if (x == 0 && border.blocked(this, snapshot.originX - 1, snapshot.originY + y, snapshot.originZ + z)) {
						solidNeighbor = true;
					}
					if (x == 15 && border.blocked(this, snapshot.originX + 16, snapshot.originY + y, snapshot.originZ + z)) {
						solidNeighbor = true;
					}
					if (y == 0 && border.blocked(this, snapshot.originX + x, snapshot.originY - 1, snapshot.originZ + z)) {
						solidNeighbor = true;
					}
					if (y == 15 && border.blocked(this, snapshot.originX + x, snapshot.originY + 16, snapshot.originZ + z)) {
						solidNeighbor = true;
					}
					if (z == 0 && border.blocked(this, snapshot.originX + x, snapshot.originY + y, snapshot.originZ - 1)) {
						solidNeighbor = true;
					}
					if (z == 15 && border.blocked(this, snapshot.originX + x, snapshot.originY + y, snapshot.originZ + 16)) {
						solidNeighbor = true;
					}
					if (solidNeighbor) {
						boundary.add(Grid.index(x, y, z));
					}
				}
			}
		}
		return this.track(this.component(snapshot, boundary.toIntArray(), Grid.VOLUME, sky, border));
	}

	private Comp component(SectionSnapshot snapshot, int[] boundary, int size, boolean sky, Border border) {
		return new Comp(
			this.allocateId(),
			snapshot.key,
			snapshot.originX,
			snapshot.originY,
			snapshot.originZ,
			boundary,
			size,
			sky,
			border.exterior,
			border.above,
			border.below,
			border.columns()
		);
	}

	private void refreshSky(int chunkX, int chunkZ) {
		LongArrayList keys = this.sectionKeys.get(ChunkPos.pack(chunkX, chunkZ));
		if (keys == null || keys.isEmpty()) {
			return;
		}
		SectionSnapshot[] column = new SectionSnapshot[keys.size()];
		for (int i = 0; i < keys.size(); i++) {
			SectionSnapshot existing = this.snapshots.get(keys.getLong(i));
			column[i] = new SectionSnapshot(existing.key, existing.sx, existing.sy, existing.sz, existing.passable, new BitSet(Grid.VOLUME));
		}
		ChunkSnapshot.repaintSky(column, this.hasSkyLight);
		for (SectionSnapshot updated : column) {
			this.snapshots.put(updated.key, updated);
			SectionModel model = this.models.get(updated.key);
			if (model == null) {
				continue;
			}
			model.snapshot = updated;
			for (int index = updated.sky.nextSetBit(0); index >= 0; index = updated.sky.nextSetBit(index + 1)) {
				this.markOpen(model.compAt(index));
			}
		}
	}

	private boolean missingLoaded(int x, int y, int z) {
		return y >= this.minY && y <= this.maxY && this.snapshotAt(x, y, z) == null;
	}

	private boolean passableWorld(int x, int y, int z) {
		if (y > this.maxY) {
			return this.playerAbove;
		}
		if (y < this.minY) {
			return this.playerBelow;
		}
		SectionSnapshot snapshot = this.snapshotAt(x, y, z);
		if (snapshot != null) {
			return snapshot.passable.get(Grid.index(x & 15, y & 15, z & 15));
		}
		if (!this.hasSkyLight) {
			return false;
		}
		int[] ceiling = this.ceilings.get(ChunkPos.pack(x >> 4, z >> 4));
		if (ceiling == null) {
			return false;
		}
		return y >= ceiling[((z & 15) << 4) | (x & 15)];
	}

	private SectionSnapshot snapshotAt(int x, int y, int z) {
		return this.snapshots.get(Grid.sectionKey(x, y, z));
	}

	private Comp compAt(int x, int y, int z) {
		if (y < this.minY || y > this.maxY) {
			return null;
		}
		SectionModel model = this.models.get(Grid.sectionKey(x, y, z));
		if (model == null) {
			return null;
		}
		return model.compAt(Grid.index(x & 15, y & 15, z & 15));
	}

	private int allocateId() {
		int id = this.nextId++;
		this.ensureParent(id);
		this.parent[id] = id;
		return id;
	}

	private Comp track(Comp comp) {
		this.ensureParent(comp.id);
		this.byId[comp.id] = comp;
		return comp;
	}

	private void emit() {
		if (this.dirty.isEmpty()) {
			return;
		}
		long[] keys = this.dirty.toLongArray();
		this.dirty.clear();
		for (long key : keys) {
			SectionSnapshot snapshot = this.snapshots.get(key);
			if (snapshot == null) {
				continue;
			}
			boolean fresh = !this.settled.contains(key);
			BitSet fill = this.fillFor(snapshot);
			BitSet previous = this.lastEmitted.get(key);
			if (!fresh) {
				if (previous == null) {
					fill.clear();
				} else {
					fill.and(previous);
				}
			}
			this.settled.add(snapshot.key);
			if ((previous == null || previous.isEmpty()) && fill.isEmpty()) {
				continue;
			}
			if (previous != null && previous.equals(fill) && !this.reemit.remove(key)) {
				continue;
			}
			if (fill.isEmpty()) {
				this.lastEmitted.remove(key);
			} else {
				this.lastEmitted.put(key, (BitSet) fill.clone());
			}
			this.pending.add(new SectionFill(key, snapshot.sx, snapshot.sy, snapshot.sz, fill));
		}
	}

	private BitSet fillFor(SectionSnapshot snapshot) {
		BitSet fill = new BitSet(Grid.VOLUME);
		SectionModel model = this.models.get(snapshot.key);
		if (model == null || model.comps.length == 0) {
			return fill;
		}
		for (int index = snapshot.passable.nextSetBit(0); index >= 0; index = snapshot.passable.nextSetBit(index + 1)) {
			Comp comp = model.compAt(index);
			if (comp == null) {
				continue;
			}
			int root = this.find(comp.id);
			if (!this.isOpen(root)) {
				fill.set(index);
			}
		}
		return fill;
	}

	private static final class Comp {
		final int id;
		final long sectionKey;
		final int originX;
		final int originY;
		final int originZ;
		final int[] boundary;
		final int size;
		final boolean sky;
		/** Connected to the void outside the build height, which is open only while the player is out there. */
		final boolean exterior;
		final boolean bordersAbove;
		final boolean bordersBelow;
		/** Chunk columns on the other side of a face that are not loaded yet. */
		final long[] pending;
		boolean dead;

		Comp(
			int id,
			long sectionKey,
			int originX,
			int originY,
			int originZ,
			int[] boundary,
			int size,
			boolean sky,
			boolean exterior,
			boolean bordersAbove,
			boolean bordersBelow,
			long[] pending
		) {
			this.id = id;
			this.sectionKey = sectionKey;
			this.originX = originX;
			this.originY = originY;
			this.originZ = originZ;
			this.boundary = boundary;
			this.size = size;
			this.sky = sky;
			this.exterior = exterior;
			this.bordersAbove = bordersAbove;
			this.bordersBelow = bordersBelow;
			this.pending = pending;
		}
	}

	private static final class Border {
		boolean above;
		boolean below;
		boolean exterior;
		LongOpenHashSet pending;

		boolean blocked(CaveEngine engine, int x, int y, int z) {
			if (y > engine.maxY) {
				this.above = true;
			} else if (y < engine.minY) {
				this.below = true;
			}
			if (!engine.passableWorld(x, y, z)) {
				if (engine.missingLoaded(x, y, z)) {
					if (this.pending == null) {
						this.pending = new LongOpenHashSet();
					}
					this.pending.add(ChunkPos.pack(x >> 4, z >> 4));
				}
				return true;
			}
			if (y > engine.maxY || y < engine.minY) {
				this.exterior = true;
			}
			return false;
		}

		long[] columns() {
			return this.pending == null ? NO_PENDING : this.pending.toLongArray();
		}
	}

	private static final class SectionModel {
		SectionSnapshot snapshot;
		final Comp[] comps;
		final short[] local;

		SectionModel(SectionSnapshot snapshot, Comp[] comps, short[] local) {
			this.snapshot = snapshot;
			this.comps = comps;
			this.local = local;
		}

		Comp compAt(int index) {
			if (!this.snapshot.passable.get(index) || this.comps.length == 0) {
				return null;
			}
			if (this.local == null) {
				return this.comps[0];
			}
			short id = this.local[index];
			if (id < 0 || id >= this.comps.length) {
				return null;
			}
			return this.comps[id];
		}
	}

	private record BlockEdit(int x, int y, int z, boolean passable, boolean reveal) {
	}

	private record Vertical(boolean above, boolean below) {
	}
}
