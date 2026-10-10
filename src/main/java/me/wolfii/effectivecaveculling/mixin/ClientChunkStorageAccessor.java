package me.wolfii.effectivecaveculling.mixin;

import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public interface ClientChunkStorageAccessor {
	@Accessor("chunks")
	AtomicReferenceArray<LevelChunk> cavefill$chunks();

	@Invoker("getIndex")
	int cavefill$getIndex(int chunkX, int chunkZ);

	@Invoker("inRange")
	boolean cavefill$inRange(int chunkX, int chunkZ);

	@Invoker("replace")
	void cavefill$replace(int index, @Nullable LevelChunk chunk);
}
