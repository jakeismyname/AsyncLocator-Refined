package brightspark.asynclocator.mixins;

import brightspark.asynclocator.ALConstants;
import brightspark.asynclocator.AsyncLocator;
import brightspark.asynclocator.AsyncLocator.LocateTask;
import brightspark.asynclocator.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.animal.Dolphin;
import net.minecraft.world.level.levelgen.structure.Structure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.entity.animal.Dolphin$DolphinSwimToTreasureGoal", priority = 800)
public class DolphinSwimToTreasureGoalMixin {
	private LocateTask<BlockPos> locateTask = null;
	private BlockPos asyncFoundPos = null;

	@WrapOperation(method = "start", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;findNearestMapStructure(Lnet/minecraft/tags/TagKey;Lnet/minecraft/core/BlockPos;IZ)Lnet/minecraft/core/BlockPos;"))
	private BlockPos redirectFindNearestMapStructure(ServerLevel level, TagKey<Structure> structureTag, BlockPos pos, int searchRadius, boolean skipKnownStructures, Operation<BlockPos> original) {
		if (!Services.CONFIG.dolphinTreasureEnabled()) {
			return original.call(level, structureTag, pos, searchRadius, skipKnownStructures);
		}

		ALConstants.logDebug("Intercepted DolphinSwimToTreasureGoal findNearestMapStructure call");

		// Start async task and reset any stale position
		asyncFoundPos = null;
		handleFindTreasureAsync(level, pos);
		return null;
	}

    // Keep goal alive while an async locating task is ongoing
	@Inject(method = "canContinueToUse", at = @At(value = "HEAD"), cancellable = true)
	private void continueToUseIfLocatingTreasure(CallbackInfoReturnable<Boolean> cir) {
		if (locateTask != null || asyncFoundPos != null) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "stop", at = @At(value = "HEAD"))
	private void stopLocatingTreasure(CallbackInfo ci) {
		if (locateTask != null) {
			ALConstants.logDebug("Locating task ongoing - cancelling during stop()");
			locateTask.cancel();
			locateTask = null;
		}
        asyncFoundPos = null;
	}

    /*
     * Skip ticking while a locate task is active so dolphin 
     * doesn't try to go towards an old treasure position
     */
	@Inject(method = "tick", at = @At(value = "HEAD"), cancellable = true)
	private void skipTickingIfLocatingTreasure(CallbackInfo ci) {
		if (locateTask != null && asyncFoundPos == null) {
			ci.cancel();
		}
	}

	@WrapOperation(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/animal/Dolphin;treasurePos:Lnet/minecraft/core/BlockPos;"))
	private BlockPos redirectTreasurePos(Dolphin instance, Operation<BlockPos> original) {
		return asyncFoundPos != null ? asyncFoundPos : original.call(instance);
	}

	@Unique
	private void handleFindTreasureAsync(ServerLevel level, BlockPos blockPos) {
		locateTask = AsyncLocator.locate(level, StructureTags.DOLPHIN_LOCATED, blockPos, 50, false)
				.thenOnServerThread(pos -> handleLocationFound(level, pos));
	}

	@Unique
	private void handleLocationFound(ServerLevel level, BlockPos pos) {
		locateTask = null;
		asyncFoundPos = pos;
		if (pos != null) {
			ALConstants.logInfo("Location found at {} - dolphin will now swim to treasure", pos);
		} else {
			ALConstants.logInfo("No location found - dolphin will continue normal behavior");
		}
	}
}
