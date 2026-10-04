package org.webtrade.minecraftportsmod.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.webtrade.minecraftportsmod.client.CombatClient;
import org.webtrade.minecraftportsmod.combat.WarshipEntity;

/** Aboard a warship the third-person camera stands far out behind her, to see the whole ship and the enemy. */
@Mixin(Camera.class)
public abstract class CameraDistanceMixin {

    @ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
    private float minecraftportsmod$warshipDistance(float distance) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getVehicle() instanceof WarshipEntity ? Math.max(distance, CombatClient.CAMERA_DISTANCE) : distance;
    }
}
