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
 * At the helm of a warship. W sets one more sail (three presses: all of them), S takes one in, A/D the rudder; the
 * mouse turns the camera freely round her. The fighting mode (R, again to leave it): X switches the broadside, the
 * space bar fires it, the camera's height sets how far the shot goes; on going into the fight (or switching sides)
 * the camera swings round behind her to the far side, looking out over the chosen broadside. The flight of the shot
 * from the chosen side is drawn as a line of dots, wherever its guns are loaded.
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

    /** Ticks the camera still swings round to the chosen broadside; how far off the bow it looks then (degrees). */
    private static int swing;
    private static final int SWING_TICKS = 30;
    private static final float LOOK_OUT = 62F;

    /** The other broadside. Default: X. */
    public static final net.minecraft.client.KeyMapping SIDE = new net.minecraft.client.KeyMapping("key.minecraftportsmod.broadside",
            org.lwjgl.glfw.GLFW.GLFW_KEY_X, org.webtrade.minecraftportsmod.client.chart.MinecraftportsmodKeys.CATEGORY);

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
        if (on) swing = SWING_TICKS;
    }

    /** How far behind the ship the camera stands while one is at her helm (blocks). */
    public static final float CAMERA_DISTANCE = 22F;

    /** The warship the player is at the helm of, or null. */
    public static WarshipEntity helm(Minecraft mc) {
        return mc.player != null && mc.player.getVehicle() instanceof WarshipEntity w && w.getFirstPassenger() == mc.player ? w : null;
    }

    public static void init() {
        net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(FIGHT);
        net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(SIDE);
        ClientTickEvents.START_CLIENT_TICK.register(CombatClient::tick);
        // (after the world's tick: the ships have moved, the player too; he is moved on with his ship)
        ClientTickEvents.END_CLIENT_TICK.register(CombatClient::carryPlayer);
        ClientTickEvents.END_CLIENT_TICK.register(SeaSounds::tick);
        ClientTickEvents.END_CLIENT_TICK.register(ShipMinimap::tick);
        HudElementRegistry.addLast(Minecraftportsmod.id("warship"), (g, delta) -> hud(Minecraft.getInstance(), g));
    }

    /**
     * The player standing on a warship's deck goes along with her (forward, and round as she turns): the game moves
     * nobody with the entity he stands on, and the player moves himself.
     */
    public static boolean DEBUG = false;

    private static void carryPlayer(Minecraft mc) {
        var p = mc.player;
        if (p == null || p.getVehicle() != null || mc.level == null) return;
        for (WarshipEntity w : mc.level.getEntitiesOfClass(WarshipEntity.class, p.getBoundingBox().inflate(16))) {
            if (DEBUG && w.tickCount % 10 == 0) Minecraftportsmod.LOGGER.info("[deck client] ship z {} old {} | player z {} old {} ground {}", w.getZ(),
                    w.zo, p.getZ(), p.zo, p.onGround());
            if (w.sinking() > 0 || !w.onDeckFrom(p.position(), w.xo, w.yo, w.zo, w.yRotO)) continue;
            net.minecraft.world.phys.Vec3 to = w.carry(p.position(), w.xo, w.yo, w.zo, w.yRotO);
            // (where he was is moved along too: the frames between ticks show no jump)
            p.xo += to.x - p.getX();
            p.yo += to.y - p.getY();
            p.zo += to.z - p.getZ();
            p.xOld = p.xo;
            p.yOld = p.yo;
            p.zOld = p.zo;
            p.setPos(to.x, to.y, to.z);
            float turn = w.getYRot() - w.yRotO;
            p.setYRot(p.getYRot() + turn);
            p.yRotO += turn;
            return;
        }
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
            while (SIDE.consumeClick()) {
            }
            return;
        }
        // R: into the fight and out of it; X: the other broadside; space: fire it
        while (FIGHT.consumeClick()) {
            fighting = !fighting;
            if (fighting) swing = SWING_TICKS;
        }
        while (SIDE.consumeClick()) {
            aimSide = -aimSide;
            if (fighting) swing = SWING_TICKS;
        }
        int sails = 0, fire = 0;
        // W sets one more sail (three presses: all of them), S takes one in
        while (mc.options.keyUp.consumeClick()) sails++;
        while (mc.options.keyDown.consumeClick()) sails--;
        int rudder = (mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0);
        while (mc.options.keyLeft.consumeClick()) {
        }
        while (mc.options.keyRight.consumeClick()) {
        }
        float elevation = elevation(mc);
        if (fighting) {
            while (mc.options.keyJump.consumeClick()) fire = aimSide;
            // the camera swings round behind her, over the far side, to look out over the chosen broadside
            if (swing > 0) {
                swing--;
                float want = at.getYRot() + aimSide * LOOK_OUT;
                float delta = Mth.wrapDegrees(want - mc.player.getYRot());
                mc.player.setYRot(mc.player.getYRot() + Mth.clamp(delta, -9F, 9F));
                float pitch = mc.player.getXRot();
                mc.player.setXRot(pitch + Mth.clamp(18F - pitch, -3F, 3F));
                if (Math.abs(delta) < 1F) swing = 0;
            }
        }
        for (; sails > 1; sails--) ClientPlayNetworking.send(new CombatPayloads.ShipOrder(1, rudder, 0, elevation));
        for (; sails < -1; sails++) ClientPlayNetworking.send(new CombatPayloads.ShipOrder(-1, rudder, 0, elevation));
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
        ShipMinimap.draw(mc, g);
        WarshipEntity ship = helm(mc);
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        if (ship != null) {
            int x = w / 2 - 91, y = h - 62;
            bar(g, x, y, 182, ship.hull() / ship.maxHull(), 0xFFB03020);
            // the sails: one for each that can be set, white when set
            for (int i = 0; i < 3; i++) glyph(g, SAIL, x + i * 17, y - 13, i < ship.sails() ? 0xFFF4EEDC : 0x55FFFFFF, 0xFF5A3A22);
            // the guns of each side, port on the left of the bar and starboard on the right: loaded (green), or how far
            // through reloading; the side chosen in the fighting mode framed
            for (int side : new int[]{-1, 1}) {
                float r = ship.loaded(side);
                int bx = side < 0 ? x - 52 : x + 182 + 6, by = y - 2;
                if (fighting && side == aimSide) g.fill(bx - 2, by - 2, bx + 48, by + 12, 0xFFF0C040);
                g.fill(bx, by, bx + 46, by + 10, 0xC0000000);
                g.fill(bx + 1, by + 1, bx + 1 + Math.round(44 * r), by + 9, r >= 1 ? 0xFF40C040 : 0xFFC08030);
                // a gun over the bar, looking out to its side
                glyph(g, side < 0 ? GUN_PORT : GUN_STARBOARD, bx + 16, by - 9, r >= 1 ? 0xFF2A2A2E : 0x90505050, 0xFF6A4628);
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
            // (over her mastheads)
            Vec3 top = s.position().add(0, s.cls().lift + 19, 0);
            var proj = project(mc, top);
            if (proj == null) continue;
            bar(g, (int) proj.x - 30, (int) proj.y, 60, s.hull() / s.maxHull(), 0xFF202020);
        }
    }

    /** Little pictures for the hud: a square sail on its yard; a gun on its carriage, muzzle to port or starboard. */
    private static final String[] SAIL = {"#######", ".ooooo.", ".ooooo.", ".ooooo.", "..ooo.."};
    private static final String[] GUN_STARBOARD = {"..ooooo", "ooooooo", ".##.##."};
    private static final String[] GUN_PORT = {"ooooo..", "ooooooo", ".##.##."};

    /** Draws a picture, two gui pixels to each of its own: 'o' in the main colour, '#' in the second. */
    private static void glyph(GuiGraphicsExtractor g, String[] rows, int x, int y, int main, int second) {
        for (int j = 0; j < rows.length; j++) {
            for (int i = 0; i < rows[j].length(); i++) {
                char c = rows[j].charAt(i);
                if (c == '.') continue;
                g.fill(x + i * 2, y + j * 2, x + i * 2 + 2, y + j * 2 + 2, c == 'o' ? main : second);
            }
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
