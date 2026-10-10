package me.wolfii.effectivecaveculling.mixin;

import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public interface ClientChunkStorageAccessor {
	@Accessor("chunks")
	AtomicReferenceArray<LevelChunk> cavefill$chunks();
}
