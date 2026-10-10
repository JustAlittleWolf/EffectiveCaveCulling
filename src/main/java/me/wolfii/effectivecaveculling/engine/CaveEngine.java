package me.wolfii.effectivecaveculling.engine;

import me.wolfii.effectivecaveculling.Cavefill;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
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
 * Incremental reachability for loaded chunks. Runs only on its own thread.
 * Unloaded sections and the volume outside the inclusive build height are solid.
 * Sections entirely above the surface heightmap are open sky, not solid and not scanned.
 * A pocket is isolated when no face-connected path of non-occluding blocks reaches the player or the open sky.
 */
public final class CaveEngine {
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "effectivecaveculling-solver");
		thread.setDaemon(true);
		return thread;
	});
	private final ConcurrentLinkedQueue<SectionFill> pending = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<BlockEdit> blockEdits = new ConcurrentLinkedQueue<>();
	private Future<?> async;

	private final Long2ObjectOpenHashMap<SectionSnapshot> snapshots = new Long2ObjectOpenHashMap<>();
	/** Chunk columns already indexed. Republishing one replaces its sections instead of merging. */
	private final LongOpenHashSet columns = new LongOpenHashSet();
	/** First open-air Y per column of a loaded chunk. Missing sections at or above this are sky, not solid. */
	private final Long2ObjectOpenHashMap<int[]> ceilings = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<SectionModel> models = new Long2ObjectOpenHashMap<>();
	/** Passable blocks already decided to stay real. Later solves never fill these, and never fill anything new in that section. */
	private final Long2ObjectOpenHashMap<BitSet> anchors = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet settled = new LongOpenHashSet();
	private final Long2ObjectOpenHashMap<BitSet> lastEmitted = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet dirty = new LongOpenHashSet();
	/** Sections whose live blocks diverged from the last fill and must be written again. */
	private final LongOpenHashSet reemit = new LongOpenHashSet();

	private final ArrayList<Comp> comps = new ArrayList<>();
	private final Int2ObjectOpenHashMap<IntArrayList> compsByRoot = new Int2ObjectOpenHashMap<>();
	/** Roots the player can walk to, or that are open sky / an exempt cavern. Passable blocks outside these are filled. */
	private final IntOpenHashSet open = new IntOpenHashSet();
	private final Int2IntOpenHashMap sizeByRoot = new Int2IntOpenHashMap();
	private final IntOpenHashSet unloadedRoots = new IntOpenHashSet();
	private int[] parent = new int[1024];
	private Comp[] byId = new Comp[1024];
	private int nextId = 1;

	private int minY = Integer.MIN_VALUE;
	private int maxY = Integer.MAX_VALUE;
	private boolean hasSkyLight = true;
	private boolean needsRebuild;
	private PlayerView player = new PlayerView(new int[] {0, 64, 0});

	public void publishAndWait(ChunkSnapshot chunk, PlayerView view) {
		this.finishFlush();
		this.join(this.worker.submit(() -> this.publish0(chunk, view)));
	}

	public void flushAndWait(PlayerView view) {
		this.finishFlush();
		this.join(this.worker.submit(() -> this.flush0(view)));
	}

	public void enqueueBlock(int x, int y, int z, boolean passable) {
		this.blockEdits.add(new BlockEdit(x, y, z, passable, false));
	}

	/** A real block change replaced a fill. The section has to be written again even if the mask is unchanged. */
	public void enqueueReveal(int x, int y, int z, boolean passable) {
		this.blockEdits.add(new BlockEdit(x, y, z, passable, true));
	}

	/** No queued edits and no flush in flight. View moves can skip the client-thread wait. */
	public boolean idle() {
		return this.blockEdits.isEmpty() && (this.async == null || this.async.isDone());
	}

	/** Queue the fills already decided, so leaving spectator can seal again without a new search. */
	public void replayAndWait() {
		this.finishFlush();
		this.join(this.worker.submit(this::replay0));
	}

	/** Start a background catch-up if one is not already running. */
	public boolean startFlush(PlayerView view) {
		if (this.async != null && !this.async.isDone()) {
			return false;
		}
		this.async = null;
		this.async = this.worker.submit(() -> this.flush0(view));
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

	public List<SectionFill> drain() {
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

	private void publish0(ChunkSnapshot chunk, PlayerView view) {
		this.player = view;
		this.minY = chunk.minY;
		this.maxY = chunk.maxY;
		this.hasSkyLight = chunk.hasSkyLight;
		this.applyQueuedEdits();

		long column = ChunkPos.pack(chunk.chunkX, chunk.chunkZ);
		if (this.columns.contains(column)) {
			this.dropChunk(chunk.chunkX, chunk.chunkZ);
			this.needsRebuild = true;
		}
		this.columns.add(column);
		if (chunk.firstOpenY != null) {
			this.ceilings.put(column, chunk.firstOpenY);
		}
		for (SectionSnapshot section : chunk.sections) {
			this.snapshots.put(section.key, section);
		}

		if (this.needsRebuild) {
			this.rebuildAll();
		} else {
			this.integrateNewChunk(chunk);
			this.seedPlayer();
			this.exemptOpenCaverns(chunk);
		}
		this.emit();
	}

	private void flush0(PlayerView view) {
		this.player = view;
		this.applyQueuedEdits();
		if (this.needsRebuild) {
			this.rebuildAll();
		}
		this.emit();
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
		int cx = section.sx;
		int cz = section.sz;
		LongArrayList keys = new LongArrayList();
		for (SectionSnapshot existing : this.snapshots.values()) {
			if (existing.sx == cx && existing.sz == cz) {
				keys.add(existing.key);
			}
		}
		SectionSnapshot[] column = new SectionSnapshot[keys.size()];
		for (int i = 0; i < keys.size(); i++) {
			SectionSnapshot existing = this.snapshots.get(keys.getLong(i));
			BitSet nextPassable = existing.key == key ? passable : existing.passable;
			BitSet sky = new BitSet(Grid.VOLUME);
			column[i] = new SectionSnapshot(existing.key, existing.sx, existing.sy, existing.sz, nextPassable, sky);
		}
		ChunkSnapshot.repaintSky(column, this.hasSkyLight);
		for (SectionSnapshot updated : column) {
			this.snapshots.put(updated.key, updated);
		}
		this.needsRebuild = true;
	}

	private void dropChunk(int chunkX, int chunkZ) {
		long column = ChunkPos.pack(chunkX, chunkZ);
		this.columns.remove(column);
		this.ceilings.remove(column);
		LongArrayList remove = new LongArrayList();
		for (SectionSnapshot section : this.snapshots.values()) {
			if (section.sx == chunkX && section.sz == chunkZ) {
				remove.add(section.key);
			}
		}
		for (int i = 0; i < remove.size(); i++) {
			long key = remove.getLong(i);
			this.snapshots.remove(key);
			this.models.remove(key);
			this.anchors.remove(key);
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

	private void reset0() {
		this.snapshots.clear();
		this.columns.clear();
		this.ceilings.clear();
		this.models.clear();
		this.anchors.clear();
		this.settled.clear();
		this.lastEmitted.clear();
		this.dirty.clear();
		this.reemit.clear();
		this.comps.clear();
		this.compsByRoot.clear();
		this.open.clear();
		this.sizeByRoot.clear();
		this.unloadedRoots.clear();
		this.parent = new int[1024];
		this.byId = new Comp[1024];
		this.nextId = 1;
		this.needsRebuild = false;
		this.pending.clear();
		this.blockEdits.clear();
	}

	private void rebuildAll() {
		this.needsRebuild = false;
		this.models.clear();
		this.comps.clear();
		this.compsByRoot.clear();
		this.open.clear();
		this.sizeByRoot.clear();
		this.unloadedRoots.clear();
		this.parent = new int[Math.max(1024, this.snapshots.size() * 4)];
		this.byId = new Comp[this.parent.length];
		this.nextId = 1;

		for (SectionSnapshot snapshot : this.snapshots.values()) {
			SectionModel model = this.buildModel(snapshot);
			this.models.put(snapshot.key, model);
			for (Comp comp : model.comps) {
				this.register(comp);
				this.dirty.add(snapshot.key);
			}
			if (model.comps.length == 0) {
				this.dirty.add(snapshot.key);
			}
		}
		for (SectionModel model : this.models.values()) {
			this.linkPositiveFaces(model);
		}
		this.seedSky();
		this.seedPlayer();
		this.seedAnchors();
		this.exemptAllOpenCaverns();
	}

	private void integrateNewChunk(ChunkSnapshot chunk) {
		for (SectionSnapshot snapshot : chunk.sections) {
			SectionModel model = this.buildModel(snapshot);
			this.models.put(snapshot.key, model);
			this.dirty.add(snapshot.key);
			for (Comp comp : model.comps) {
				this.register(comp);
				if (comp.sky) {
					this.markOpen(comp);
				}
			}
			this.linkPositiveFaces(model);
			this.linkIncomingFaces(model);
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
		if (comp.touchesUnloaded) {
			this.unloadedRoots.add(id);
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

	private void seedSky() {
		for (Comp comp : this.comps) {
			if (comp.sky && !comp.dead) {
				this.markOpen(comp);
			}
		}
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

	/** Blocks that were left real stay reachable, so a later edit can open a filled pocket but cannot fill a new one. */
	private void seedAnchors() {
		IntOpenHashSet seeded = new IntOpenHashSet();
		for (var entry : this.anchors.long2ObjectEntrySet()) {
			SectionModel model = this.models.get(entry.getLongKey());
			if (model == null) {
				continue;
			}
			BitSet anchor = entry.getValue();
			for (int index = anchor.nextSetBit(0); index >= 0; index = anchor.nextSetBit(index + 1)) {
				Comp comp = model.compAt(index);
				if (comp == null || comp.dead) {
					continue;
				}
				int root = this.find(comp.id);
				if (!seeded.add(root)) {
					continue;
				}
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

	private void exemptAllOpenCaverns() {
		if (this.hasSkyLight) {
			return;
		}
		IntOpenHashSet seen = new IntOpenHashSet();
		for (Comp comp : this.comps) {
			if (!comp.dead) {
				seen.add(this.find(comp.id));
			}
		}
		for (int root : seen) {
			this.exemptRoot(root);
		}
	}

	private void exemptRoot(int root) {
		if (!this.unloadedRoots.contains(root)) {
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
		if (this.unloadedRoots.remove(rb)) {
			this.unloadedRoots.add(ra);
		}
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
			boolean unloaded = false;
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
						int wx = snapshot.originX + nx;
						int wy = snapshot.originY + ny;
						int wz = snapshot.originZ + nz;
						if (!this.passableWorld(wx, wy, wz)) {
							edge = true;
							if (wy >= this.minY && wy <= this.maxY && this.snapshotAt(wx, wy, wz) == null) {
								unloaded = true;
							}
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
			built.add(this.track(new Comp(this.allocateId(), snapshot.key, snapshot.originX, snapshot.originY, snapshot.originZ, boundary.toIntArray(), size, sky, unloaded)));
		}
		return new SectionModel(snapshot, built.toArray(Comp[]::new), local);
	}

	private Comp makeUniform(SectionSnapshot snapshot) {
		IntArrayList boundary = new IntArrayList();
		boolean unloaded = false;
		boolean sky = snapshot.sky.cardinality() > 0;
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					boolean edge = x == 0 || y == 0 || z == 0 || x == 15 || y == 15 || z == 15;
					if (!edge) {
						continue;
					}
					boolean solidNeighbor = false;
					if (x == 0 && !this.passableWorld(snapshot.originX - 1, snapshot.originY + y, snapshot.originZ + z)) {
						solidNeighbor = true;
						unloaded |= this.missingLoaded(snapshot.originX - 1, snapshot.originY + y, snapshot.originZ + z);
					}
					if (x == 15 && !this.passableWorld(snapshot.originX + 16, snapshot.originY + y, snapshot.originZ + z)) {
						solidNeighbor = true;
						unloaded |= this.missingLoaded(snapshot.originX + 16, snapshot.originY + y, snapshot.originZ + z);
					}
					if (y == 0 && !this.passableWorld(snapshot.originX + x, snapshot.originY - 1, snapshot.originZ + z)) {
						solidNeighbor = true;
						unloaded |= this.missingLoaded(snapshot.originX + x, snapshot.originY - 1, snapshot.originZ + z);
					}
					if (y == 15 && !this.passableWorld(snapshot.originX + x, snapshot.originY + 16, snapshot.originZ + z)) {
						solidNeighbor = true;
						unloaded |= this.missingLoaded(snapshot.originX + x, snapshot.originY + 16, snapshot.originZ + z);
					}
					if (z == 0 && !this.passableWorld(snapshot.originX + x, snapshot.originY + y, snapshot.originZ - 1)) {
						solidNeighbor = true;
						unloaded |= this.missingLoaded(snapshot.originX + x, snapshot.originY + y, snapshot.originZ - 1);
					}
					if (z == 15 && !this.passableWorld(snapshot.originX + x, snapshot.originY + y, snapshot.originZ + 16)) {
						solidNeighbor = true;
						unloaded |= this.missingLoaded(snapshot.originX + x, snapshot.originY + y, snapshot.originZ + 16);
					}
					if (solidNeighbor) {
						boundary.add(Grid.index(x, y, z));
					}
				}
			}
		}
		return this.track(new Comp(this.allocateId(), snapshot.key, snapshot.originX, snapshot.originY, snapshot.originZ, boundary.toIntArray(), Grid.VOLUME, sky, unloaded));
	}

	private boolean missingLoaded(int x, int y, int z) {
		return y >= this.minY && y <= this.maxY && this.snapshotAt(x, y, z) == null;
	}

	private boolean passableWorld(int x, int y, int z) {
		if (y < this.minY || y > this.maxY) {
			return false;
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

	private void replay0() {
		for (var entry : this.lastEmitted.long2ObjectEntrySet()) {
			SectionSnapshot snapshot = this.snapshots.get(entry.getLongKey());
			BitSet fill = entry.getValue();
			if (snapshot == null || fill.isEmpty()) {
				continue;
			}
			this.pending.add(new SectionFill(snapshot.key, snapshot.sx, snapshot.sy, snapshot.sz, (BitSet) fill.clone()));
		}
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
			this.rememberAnchors(snapshot, fill);
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

	private void rememberAnchors(SectionSnapshot snapshot, BitSet fill) {
		SectionModel model = this.models.get(snapshot.key);
		BitSet anchor = new BitSet(Grid.VOLUME);
		if (model != null) {
			for (int index = snapshot.passable.nextSetBit(0); index >= 0; index = snapshot.passable.nextSetBit(index + 1)) {
				if (fill.get(index)) {
					continue;
				}
				Comp comp = model.compAt(index);
				if (comp == null) {
					continue;
				}
				if (this.isOpen(comp.id)) {
					anchor.set(index);
				}
			}
		}
		if (anchor.isEmpty()) {
			this.anchors.remove(snapshot.key);
		} else {
			this.anchors.put(snapshot.key, anchor);
		}
		this.settled.add(snapshot.key);
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
		final boolean touchesUnloaded;
		boolean dead;

		Comp(int id, long sectionKey, int originX, int originY, int originZ, int[] boundary, int size, boolean sky, boolean touchesUnloaded) {
			this.id = id;
			this.sectionKey = sectionKey;
			this.originX = originX;
			this.originY = originY;
			this.originZ = originZ;
			this.boundary = boundary;
			this.size = size;
			this.sky = sky;
			this.touchesUnloaded = touchesUnloaded;
		}
	}

	private static final class SectionModel {
		final SectionSnapshot snapshot;
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
}
