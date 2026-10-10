package me.wolfii.mixin;

import me.wolfii.CavefillController;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
	private void cavefill$trackRealChange(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> callback) {
		if (callback.getReturnValue() != null) {
			CavefillController.onRealBlockChanged((LevelChunk) (Object) this, pos, state);
		}
	}
}
