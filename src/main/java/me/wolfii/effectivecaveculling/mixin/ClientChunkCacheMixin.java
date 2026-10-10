package me.wolfii.effectivecaveculling.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import me.wolfii.effectivecaveculling.CavefillController;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Priority is below the default so these HEAD hooks run before Bobby's save and Voxy's ingest,
 * both of which also inject at HEAD of the same methods.
 */
@Mixin(value = ClientChunkCache.class, priority = 500)
public abstract class ClientChunkCacheMixin {
	@Inject(
		method = "replaceWithPacketData",
		cancellable = true,
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/chunk/LevelChunk;replaceWithPacketData(Lnet/minecraft/network/FriendlyByteBuf;Ljava/util/Map;Ljava/util/function/Consumer;)V",
			shift = At.Shift.AFTER
		)
	)
	private void cavefill$beforePublish(
		int chunkX,
		int chunkZ,
		FriendlyByteBuf buf,
		Map<Heightmap.Types, long[]> heightmaps,
		Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities,
		CallbackInfoReturnable<LevelChunk> callback,
		@Local LevelChunk chunk
	) {
		if (chunk == null) {
			return;
		}
		ClientChunkCache cache = (ClientChunkCache) (Object) this;
		Object storage = ((ClientChunkCacheAccessor) cache).cavefill$storage();
		ClientChunkStorageAccessor slots = (ClientChunkStorageAccessor) storage;
		LevelChunk current = slots.cavefill$chunks().get(slots.cavefill$getIndex(chunk.getPos().x(), chunk.getPos().z()));
		if (current != chunk && CavefillController.deferFreshChunk(chunk)) {
			// The packet thread is the render thread. Return before the cache publishes this chunk;
			// the solver fills it and a client tick inserts it.
			callback.setReturnValue(chunk);
			callback.cancel();
			return;
		}
		CavefillController.beforeChunkPublished(chunk);
	}

	@Inject(method = "drop", at = @At("HEAD"))
	private void cavefill$beforeDrop(ChunkPos pos, CallbackInfo callback) {
		CavefillController.invalidateChunk(pos.x(), pos.z());
		CavefillController.beforeChunkSnapshotted();
	}

	@Inject(method = "drop", at = @At("RETURN"))
	private void cavefill$afterDrop(ChunkPos pos, CallbackInfo callback) {
		CavefillController.afterChunkUnloaded(pos.x(), pos.z());
	}

	@Inject(method = "updateViewCenter", at = @At("HEAD"))
	private void cavefill$beforeViewMove(int x, int z, CallbackInfo callback) {
		CavefillController.beforeChunkSnapshotted();
	}

	@Inject(method = "updateViewRadius", at = @At("HEAD"))
	private void cavefill$beforeViewResize(int viewRange, CallbackInfo callback) {
		CavefillController.beforeChunkSnapshotted();
	}
}
