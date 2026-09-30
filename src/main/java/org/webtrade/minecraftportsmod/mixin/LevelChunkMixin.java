package org.webtrade.minecraftportsmod.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.webtrade.minecraftportsmod.nav.NavCacheManager;

/**
 * Marks a chunk dirty in the navigation cache whenever a block near or above sea level changes,
 * so dug canals, filled bays, new piers etc. are picked up and routes get re-evaluated.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Shadow
    public abstract Level getLevel();

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void minecraftportsmod$onBlockChanged(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() == null) return;
        Level level = getLevel();
        if (level.isClientSide()) return;
        // Underground edits cannot change the sea surface or what a map shows.
        if (pos.getY() < level.getSeaLevel() - 3) return;
        NavCacheManager.onBlockChanged(level, pos);
    }
}
