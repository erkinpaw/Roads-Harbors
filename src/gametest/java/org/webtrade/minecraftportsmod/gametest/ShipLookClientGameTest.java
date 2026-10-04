package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.combat.Pirates;
import org.webtrade.minecraftportsmod.combat.WarshipEntity;
import org.webtrade.minecraftportsmod.registry.ModContent;

/**
 * The warships close up, for their looks: a brig, a galleon and a ship of the line (and a pirate galleon) in a flat
 * pool, sails set, each from the side, from ahead and from above; then a broadside with its smoke and hits.
 */
public class ShipLookClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[shiplook] " + fmt, args);
    }

    static WarshipEntity ship(MinecraftServer s, EntityType<WarshipEntity> type, double x, double y, double z, float yaw, boolean pirate) {
        var level = s.overworld();
        WarshipEntity w = type.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
        w.snapTo(x, y, z, yaw, 0);
        if (pirate) w.makePirate();
        w.setSails(3);
        level.addFreshEntity(w);
        return w;
    }

    static void camera(TestServerContext server, double x, double y, double z, float yaw, float pitch) {
        server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), x, y, z, java.util.Set.of(), yaw, pitch, false));
    }

    /** A flat pool of water (superflat: the ground at -61), its surface at -61. */
    static void pool(TestServerContext server, int x0, int z0, int x1, int z1) {
        for (int x = x0; x < x1; x += 40) {
            for (int z = z0; z < z1; z += 40) {
                server.runCommand("fill " + x + " -63 " + z + " " + Math.min(x1, x + 39) + " -62 " + Math.min(z1, z + 39) + " water");
                server.runCommand("fill " + x + " -61 " + z + " " + Math.min(x1, x + 39) + " -61 " + Math.min(z1, z + 39) + " water");
            }
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        Pirates.enabled = false;
        context.getInput().resizeWindow(1920, 1080);
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 6000");
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 0 -40 0");
            context.waitTicks(40);
            pool(server, -80, -60, 120, 60);
            context.waitTicks(20);
            context.runOnClient(mc -> {
                if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
            });
            WarshipEntity[] ships = new WarshipEntity[4];
            server.runOnServer(s -> {
                ships[0] = ship(s, ModContent.WARSHIP, -40, -61, 0, 90, false);
                ships[1] = ship(s, ModContent.GALLEON, 10, -61, 0, 90, false);
                ships[2] = ship(s, ModContent.SHIP_OF_THE_LINE, 70, -61, 0, 90, false);
                ships[3] = ship(s, ModContent.GALLEON, 10, -61, 40, 90, true);
            });
            context.waitTicks(40);
            String[] names = {"brig", "galleon", "line", "pirate"};
            double[] xs = {-40, 10, 70, 10}, zs = {0, 0, 0, 40};
            for (int i = 0; i < 4; i++) {
                double x = xs[i], z = zs[i];
                camera(server, x, -55, z - 30, 0, 8);
                context.waitTicks(30);
                context.takeScreenshot("shiplook_" + names[i] + "_side");
                camera(server, x + 26, -52, z - 18, 55, 10);
                context.waitTicks(30);
                context.takeScreenshot("shiplook_" + names[i] + "_quarter");
                camera(server, x - 14, -30, z - 10, -55, 50);
                context.waitTicks(30);
                context.takeScreenshot("shiplook_" + names[i] + "_above");
            }
            // a broadside: the brig fires to starboard at the galleon's side
            server.runOnServer(s -> {
                ships[0].fire(1, 3);
                ships[1].fire(-1, 3);
            });
            camera(server, -15, -52, -22, 20, 8);
            context.waitTicks(12);
            context.takeScreenshot("shiplook_fire_a");
            context.waitTicks(12);
            context.takeScreenshot("shiplook_fire_b");
            context.waitTicks(20);
            context.takeScreenshot("shiplook_fire_c");
            server.runOnServer(s -> log("hulls after: brig {} galleon {}", ships[0].hull(), ships[1].hull()));
        }
    }
}
