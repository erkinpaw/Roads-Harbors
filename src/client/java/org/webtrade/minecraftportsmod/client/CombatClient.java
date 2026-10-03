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
 * At the helm of a warship. Sailing: W/S set and take in the sails, A/D the rudder. The fighting mode (R, again to
 * leave it): A/D choose the broadside, port or starboard, the space bar fires it, the camera's height sets how far
 * the shot goes; the flight of the shot from the chosen side is drawn as a line of dots, wherever its guns are loaded.
 * The camera goes out behind and above her (third person) while one is aboard, and comes back after. The ship's hull,
 * sails and each side's guns (loaded, or how far through reloading) are shown at the bottom of the screen; an enemy's
 * hull over her masts' foot.
 */
public final class CombatClient {

    private CombatClient() {
    }

    /** The camera as it was before going aboard (null: not aboard). */
    private static CameraType before;
    /** The rudder last sent, and when the orders were last sent (they are sent again now and then). */
    private static int lastRudder;
    private static int resend;
    private static final DustParticleOptions AIM = new DustParticleOptions(0xFFFFD040, 2.2F);
    /** In the fighting mode (A/D choose the side, space fires), and the side chosen: -1 port, 1 starboard. */
    private static boolean fighting;
    private static int aimSide = 1;

    /** Into and out of the fighting mode. Default: R. */
    public static final net.minecraft.client.KeyMapping FIGHT = new net.minecraft.client.KeyMapping("key.minecraftportsmod.fight",
            org.lwjgl.glfw.GLFW.GLFW_KEY_R, org.webtrade.minecraftportsmod.client.chart.MinecraftportsmodKeys.CATEGORY);

    public static boolean fighting() {
        return fighting;
    }

    public static int aimSide() {
        return aimSide;
    }

    /** Into the fighting mode with a side chosen (tests). */
    public static void fight(boolean on, int side) {
        fighting = on;
        aimSide = side;
    }

    /** How far behind the ship the camera stands while one is at her helm (blocks). */
    public static final float CAMERA_DISTANCE = 22F;

    /** The warship the player is at the helm of, or null. */
    public static WarshipEntity helm(Minecraft mc) {
        return mc.player != null && mc.player.getVehicle() instanceof WarshipEntity w && w.getFirstPassenger() == mc.player ? w : null;
    }

    public static void init() {
        net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(FIGHT);
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
        if (at == null) fighting = false;
        if (at == null || mc.gui.screen() != null || !ClientPlayNetworking.canSend(CombatPayloads.ShipOrder.TYPE)) {
            while (FIGHT.consumeClick()) {
            }
            return;
        }
        while (FIGHT.consumeClick()) fighting = !fighting;
        int sails = 0, fire = 0, rudder = 0;
        while (mc.options.keyUp.consumeClick()) sails = 1;
        while (mc.options.keyDown.consumeClick()) sails = -1;
        float elevation = elevation(mc);
        if (fighting) {
            // A/D choose the broadside, the space bar fires it (the ship holds her course meanwhile)
            while (mc.options.keyLeft.consumeClick()) aimSide = -1;
            while (mc.options.keyRight.consumeClick()) aimSide = 1;
            while (mc.options.keyJump.consumeClick()) fire = aimSide;
        } else {
            rudder = (mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0);
            while (mc.options.keyLeft.consumeClick()) {
            }
            while (mc.options.keyRight.consumeClick()) {
            }
        }
        if (sails != 0 || fire != 0 || rudder != lastRudder || --resend <= 0) {
            ClientPlayNetworking.send(new CombatPayloads.ShipOrder(sails, rudder, fire, elevation));
            lastRudder = rudder;
            resend = 10;
        }
        // where the broadside of the chosen side would fall: a line of dots
        if (fighting && at.tickCount % 2 == 0 && at.reload(aimSide) == 0) aim(mc, at, aimSide, elevation);
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
        int n = ship.cls().guns();
        for (int g : new int[]{0, Math.min(n, 7) - 1}) {
            double[] spot = ship.cls().gun(g);
            Vec3 p = ship.at(side * ship.cls().muzzle(), spot[0], spot[1]);
            Vec3 v = out.scale(Math.cos(e) * WarshipEntity.SHOT_SPEED).add(0, Math.sin(e) * WarshipEntity.SHOT_SPEED, 0);
            for (int t = 0; t < 120; t++) {
                p = p.add(v);
                v = new Vec3(v.x * 0.995, (v.y - 0.03) * 0.995, v.z * 0.995);
                if (t % 2 == 0) mc.level.addParticle(AIM, p.x, p.y, p.z, 0, 0, 0);
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
            bar(g, x, y, 182, ship.hull() / ship.maxHull(), 0xFFB03020);
            // the sails: four pips
            for (int i = 0; i < 3; i++) g.fill(x + i * 12, y - 10, x + i * 12 + 10, y - 5, i < ship.sails() ? 0xFFF0E8D0 : 0x60FFFFFF);
            // the guns of each side, port on the left of the bar and starboard on the right: loaded (green), or how far
            // through reloading; the side chosen in the fighting mode framed
            for (int side : new int[]{-1, 1}) {
                float r = 1F - ship.reload(side) / (float) ship.cls().reload;
                int bx = side < 0 ? x - 52 : x + 182 + 6, by = y - 2;
                if (fighting && side == aimSide) g.fill(bx - 2, by - 2, bx + 48, by + 12, 0xFFF0C040);
                g.fill(bx, by, bx + 46, by + 10, 0xC0000000);
                g.fill(bx + 1, by + 1, bx + 1 + Math.round(44 * r), by + 9, r >= 1 ? 0xFF40C040 : 0xFFC08030);
                // the guns themselves: a pip each
                int guns = Math.min(7, ship.cls().guns());
                for (int k = 0; k < guns; k++) g.fill(bx + 3 + k * 6, by + 3, bx + 7 + k * 6, by + 7, r >= 1 ? 0xFF103010 : 0x80000000);
            }
            if (fighting) {
                // the fighting mode: the hull bar framed in red
                g.fill(x - 2, y - 2, x + 184, y - 1, 0xFFD03020);
                g.fill(x - 2, y + 5, x + 184, y + 6, 0xFFD03020);
            }
        }
        // the enemies about: a hull bar over each one in sight
        for (var e : mc.level.entitiesForRendering()) {
            if (!(e instanceof WarshipEntity s) || !s.isPirate() || s.sinking() > 0 || s.distanceTo(mc.player) > 160) continue;
            Vec3 top = s.position().add(0, s.cls().castle() + 7, 0);
            var proj = project(mc, top);
            if (proj == null) continue;
            bar(g, (int) proj.x - 30, (int) proj.y, 60, s.hull() / s.maxHull(), 0xFF202020);
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
