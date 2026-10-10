package me.wolfii.effectivecaveculling.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientChunkCache.class)
public interface ClientChunkCacheAccessor {
	/**
	 * The field is {@code ClientChunkCache.Storage}, not {@code Object}.
	 * Mixin matches the accessor descriptor exactly, so a wider return type does not apply.
	 */
	@Accessor("storage")
	ClientChunkCache.Storage cavefill$storage();
}
