package me.wolfii.effectivecaveculling.mixin;

import me.wolfii.effectivecaveculling.CavefillController;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fake concrete exists only on the client. Breaking or using it must not send a packet. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void cavefill$ignoreFakeStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> callback) {
		if (CavefillController.isFake(pos)) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void cavefill$ignoreFakeContinue(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> callback) {
		if (CavefillController.isFake(pos)) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void cavefill$ignoreFakeUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> callback) {
		if (CavefillController.isFake(hit.getBlockPos())) {
			callback.setReturnValue(InteractionResult.FAIL);
		}
	}
}
