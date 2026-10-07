package org.webtrade.minecraftportsmod.mixin;

import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.webtrade.minecraftportsmod.colony.Carry;

/** A village's pack donkey: its packs are not opened on the way, and nothing harms it (but a command's kill). */
@Mixin(AbstractHorse.class)
public abstract class AbstractHorseMixin {

    @Inject(method = "openCustomInventoryScreen", at = @At("HEAD"), cancellable = true)
    private void minecraftportsmod$packsShut(Player player, CallbackInfo ci) {
        if (((AbstractHorse) (Object) this).entityTags().contains(Carry.TAG)) ci.cancel();
    }

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void minecraftportsmod$unharmed(net.minecraft.server.level.ServerLevel level, net.minecraft.world.damagesource.DamageSource source, float amount,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (!((AbstractHorse) (Object) this).entityTags().contains(Carry.TAG)) return;
        if (source.is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL)) return;
        cir.setReturnValue(false);
    }
}
