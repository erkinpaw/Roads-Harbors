package org.webtrade.minecraftportsmod.mixin.client;

import net.minecraft.client.DeltaTracker;
import org.webtrade.minecraftportsmod.client.FilmClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While a film is made, every frame is drawn at the same point of its tick (the frames then step evenly). */
@Mixin(DeltaTracker.Timer.class)
public abstract class FilmTimerMixin {

    @Inject(method = "getGameTimeDeltaPartialTick", at = @At("HEAD"), cancellable = true)
    private void minecraftportsmod$filmPartial(boolean runsNormally, CallbackInfoReturnable<Float> cir) {
        if (FilmClock.partial >= 0) cir.setReturnValue(FilmClock.partial);
    }
}
