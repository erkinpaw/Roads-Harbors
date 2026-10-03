package org.webtrade.minecraftportsmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.combat.WarshipEntity;
import org.webtrade.minecraftportsmod.network.CombatPayloads;

/**
 * At the helm of a warship: the keys sail her (W/S the sails, A/D the rudder), the attack button fires a broadside
 * to the side the camera looks at, the camera's height sets how far the shot goes (shown by a line of dots on the
 * water); the camera goes out behind and above her (third person) while one is aboard, and comes back after.
 * The ship's hull, sails and guns are shown at the bottom of the screen; an enemy's hull over its masts' foot.
 */
public final class CombatClient {

    private CombatClient() {
    }

    /** The camera as it was before going aboard (null: not aboard). */
    private static CameraType before;
    /** The rudder last sent, and when the orders were last sent (they are sent again now and then). */
    private static int lastRudder;
    private static int resend;
    private static final DustParticleOptions AIM = new DustParticleOptions(0xFFF0C040, 1.2F);

    /** How far behind the ship the camera stands while one is at her helm (blocks). */
    public static final float CAMERA_DISTANCE = 22F;

    /** The warship the player is at the helm of, or null. */
    public static WarshipEntity helm(Minecraft mc) {
        return mc.player != null && mc.player.getVehicle() instanceof WarshipEntity w && w.getFirstPassenger() == mc.player ? w : null;
    }

    public static void init() {
        ClientTickEvents.START_CLIENT_TICK.register(CombatClient::tick);
        HudElementRegistry.addLast(Minecraftportsmod.id("warship"), (g, delta) -> hud(Minecraft.getInstance(), g));
    }

    private static void tick(Minecraft mc) {
        WarshipEntity ship = mc.player != null && mc.player.getVehicle() instanceof WarshipEntity w ? w : null;
        // the camera: out behind her while aboard
        if (ship != null && before == null) {
            before = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        } else if (ship == null && before != null) {
            mc.options.setCameraType(before);
            before = null;
        }
        WarshipEntity at = helm(mc);
        if (at == null || mc.gui.screen() != null || !ClientPlayNetworking.canSend(CombatPayloads.ShipOrder.TYPE)) return;
        int sails = 0, fire = 0;
        while (mc.options.keyUp.consumeClick()) sails = 1;
        while (mc.options.keyDown.consumeClick()) sails = -1;
        int rudder = (mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0);
        int side = side(mc, at);
        float elevation = elevation(mc);
        while (mc.options.keyAttack.consumeClick()) fire = side;
        if (sails != 0 || fire != 0 || rudder != lastRudder || --resend <= 0) {
            ClientPlayNetworking.send(new CombatPayloads.ShipOrder(sails, rudder, fire, elevation));
            lastRudder = rudder;
            resend = 10;
        }
        // where the broadside to the side looked at would fall: a line of dots on the water
        if (at.tickCount % 2 == 0 && at.reload(side) == 0) aim(mc, at, side, elevation);
    }

    /** The side the camera looks at: -1 port, 1 starboard. */
    static int side(Minecraft mc, WarshipEntity ship) {
        float rel = Mth.wrapDegrees(mc.gameRenderer.mainCamera().yRot() - ship.getYRot());
        return rel > 0 ? 1 : -1;
    }

    /** The guns' elevation by the camera: looking down low, the shot falls near; looking up to the horizon, far. */
    static float elevation(Minecraft mc) {
        float pitch = mc.gameRenderer.mainCamera().xRot();
        // (pitch 45 down: point blank; 0 at the horizon: as far as the guns throw)
        return Mth.clamp((45F - pitch) / 45F * 18F, 0F, 18F);
    }

    private static void aim(Minecraft mc, WarshipEntity ship, int side, float elevation) {
        Vec3 out = ship.starboard().scale(side);
        double e = Math.toRadians(elevation);
        for (int g = 0; g < WarshipEntity.GUNS; g += WarshipEntity.GUNS - 1) {
            Vec3 p = ship.at(side * (WarshipEntity.HALF_BEAM + 0.4), WarshipEntity.gunAlong(g), WarshipEntity.DECK + 0.7);
            Vec3 v = out.scale(Math.cos(e) * WarshipEntity.SHOT_SPEED).add(0, Math.sin(e) * WarshipEntity.SHOT_SPEED, 0);
            for (int t = 0; t < 120; t++) {
                p = p.add(v);
                v = new Vec3(v.x * 0.995, (v.y - 0.03) * 0.995, v.z * 0.995);
                if (t % 3 == 0) mc.level.addParticle(AIM, p.x, p.y, p.z, 0, 0, 0);
                if (p.y < ship.getY() - 0.2) {
                    for (int k = 0; k < 4; k++) mc.level.addParticle(AIM, p.x + (k - 1.5) * 0.4, p.y + 0.3, p.z, 0, 0, 0);
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ the hud

    private static void hud(Minecraft mc, GuiGraphicsExtractor g) {
        if (mc.gui.hud.isHidden() || mc.player == null) return;
        WarshipEntity ship = helm(mc);
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        if (ship != null) {
            int x = w / 2 - 91, y = h - 62;
            bar(g, x, y, 182, ship.hull() / WarshipEntity.MAX_HULL, 0xFFB03020);
            // the sails: four pips
            for (int i = 0; i < 3; i++) g.fill(x + i * 12, y - 10, x + i * 12 + 10, y - 5, i < ship.sails() ? 0xFFF0E8D0 : 0x60FFFFFF);
            // the guns of each side: loaded, or how far through reloading
            for (int side : new int[]{-1, 1}) {
                float r = 1F - ship.reload(side) / (float) WarshipEntity.RELOAD;
                int bx = side < 0 ? x + 182 - 70 : x + 182 - 32;
                g.fill(bx, y - 10, bx + 30, y - 5, 0x60000000);
                g.fill(bx, y - 10, bx + Math.round(30 * r), y - 5, r >= 1 ? 0xFF40C040 : 0xFFC0A040);
            }
        }
        // the enemies about: a hull bar over each one in sight
        for (var e : mc.level.entitiesForRendering()) {
            if (!(e instanceof WarshipEntity s) || !s.isPirate() || s.sinking() > 0 || s.distanceTo(mc.player) > 160) continue;
            Vec3 top = s.position().add(0, 9, 0);
            var proj = project(mc, top);
            if (proj == null) continue;
            bar(g, (int) proj.x - 30, (int) proj.y, 60, s.hull() / WarshipEntity.MAX_HULL, 0xFF202020);
        }
    }

    private static void bar(GuiGraphicsExtractor g, int x, int y, int width, float fill, int color) {
        g.fill(x - 1, y - 1, x + width + 1, y + 5, 0xA0000000);
        g.fill(x, y, x + Math.round(width * Mth.clamp(fill, 0, 1)), y + 4, color);
    }

    /** A point of the world on the screen (gui coordinates), or null behind the camera. */
    private static Vec3 project(Minecraft mc, Vec3 world) {
        var cam = mc.gameRenderer.mainCamera();
        Vec3 rel = world.subtract(cam.position());
        org.joml.Vector3f v = new org.joml.Vector3f((float) rel.x, (float) rel.y, (float) rel.z);
        org.joml.Quaternionf q = new org.joml.Quaternionf(cam.rotation()).conjugate();
        v.rotate(q);
        if (v.z >= -0.1F) return null;
        double fov = Math.toRadians(mc.options.fov().get());
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        double f = (h / 2.0) / Math.tan(fov / 2);
        return new Vec3(w / 2.0 + v.x / -v.z * f, h / 2.0 - v.y / -v.z * f, 0);
    }
}
