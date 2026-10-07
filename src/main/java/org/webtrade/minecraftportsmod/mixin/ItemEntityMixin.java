package org.webtrade.minecraftportsmod.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.webtrade.minecraftportsmod.colony.Helping;

/**
 * A player hired at a gathering trade picks up what the trade brings in (logs, stone, fish...): while the day's work
 * is not all brought, it goes to the village's store instead of their inventory.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void minecraftportsmod$toTheVillage(Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer sp)) return;
        ItemEntity self = (ItemEntity) (Object) this;
        if (self.hasPickUpDelay()) return;
        ItemStack s = self.getItem();
        int n = Helping.collect(sp, s);
        if (n <= 0) return;
        sp.take(self, n);
        s.shrink(n);
        if (s.isEmpty()) {
            self.discard();
            ci.cancel();
        } else {
            self.setItem(s);
        }
    }
}
