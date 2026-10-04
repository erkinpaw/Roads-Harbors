package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.combat.Pirates;
import org.webtrade.minecraftportsmod.combat.WarshipEntity;
import org.webtrade.minecraftportsmod.registry.ModContent;

/**
 * Pictures for a video's preview: a sea fight at sunset, the game's interface hidden, at 1920x1080. Not a test of
 * anything: not in the suite (run it on its own).
 */
public class PreviewClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[preview] " + fmt, args);
    }

    private static WarshipEntity ship(MinecraftServer s, EntityType<WarshipEntity> type, double x, double z, float yaw, boolean pirate) {
        var level = s.overworld();
        WarshipEntity w = type.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
        w.snapTo(x, level.getSeaLevel() - 0.4, z, yaw, 0);
        if (pirate) w.makePirate();
        w.setSails(3);
        level.addFreshEntity(w);
        return w;
    }

    private static void camera(TestServerContext server, double x, double y, double z, float yaw, float pitch) {
        server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), x, y, z, java.util.Set.of(), yaw, pitch, false));
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        Pirates.enabled = false;
        context.getInput().resizeWindow(1920, 1080);
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("4242")).create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule advance_weather false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("weather clear");
            server.runCommand("time set 11800");
            server.runCommand("gamemode spectator @a");
            double[] sea = {0, 0, 0};
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                var found = s.overworld().findClosestBiome3d(h -> h.is(BiomeTags.IS_DEEP_OCEAN), p.blockPosition(), 6400, 32, 64);
                if (found == null) return;
                sea[0] = found.getFirst().getX() + 0.5;
                sea[1] = found.getFirst().getZ() + 0.5;
                sea[2] = s.overworld().getSeaLevel();
                p.teleportTo(s.overworld(), sea[0], sea[2] + 20, sea[1], java.util.Set.of(), 0, 30, false);
            });
            if (sea[2] == 0) throw new AssertionError("no ocean");
            context.waitTicks(100);
            sp.getConnection().waitForChunksRender();
            final double x = sea[0], z = sea[1], y = sea[2];
            context.runOnClient(mc -> {
                if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
            });

            // 1. a ship of the line gives a pirate galleon her broadside
            WarshipEntity[] line = {null}, galleon = {null};
            server.runOnServer(s -> {
                line[0] = ship(s, ModContent.SHIP_OF_THE_LINE, x, z, 0, false);
                galleon[0] = ship(s, ModContent.GALLEON, x + 30, z + 6, 180, true);
                ship(s, ModContent.WARSHIP, x - 26, z - 34, 20, false);
            });
            camera(server, x - 22, y + 3.5, z + 26, -135, 4);
            context.waitTicks(60);
            sp.getConnection().waitForChunksRender();
            context.takeScreenshot("preview_1_fleet_sunset");
            server.runOnServer(s -> line[0].fire(-1, WarshipEntity.elevationFor(30)));
            context.waitTicks(12);
            context.takeScreenshot("preview_2_broadside");
            context.waitTicks(10);
            context.takeScreenshot("preview_3_hits");
            // the galleon answers
            server.runOnServer(s -> galleon[0].fire(-1, WarshipEntity.elevationFor(30)));
            camera(server, x + 12, y + 9, z - 22, 25, 14);
            context.waitTicks(10);
            context.takeScreenshot("preview_4_return_fire");
            // 2. broadside after broadside until the pirate goes down
            for (int i = 0; i < 6; i++) {
                context.waitTicks(115);
                server.runOnServer(s -> {
                    if (line[0].isAlive()) line[0].fire(-1, WarshipEntity.elevationFor(30));
                });
                if (galleon[0].sinking() > 0) break;
            }
            context.waitTicks(40);
            camera(server, x + 44, y + 6, z + 22, 125, 12);
            context.waitTicks(30);
            context.takeScreenshot("preview_5_pirate_sinking");
            context.waitTicks(40);
            context.takeScreenshot("preview_6_going_down");
            log("galleon sinking {} hull {}", galleon[0].sinking(), galleon[0].hull());

            // 3. the gameplay: at the helm, the fight mode, the line of the shot on the water
            server.runCommand("gamemode creative @a");
            context.runOnClient(mc -> {
                if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
            });
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                WarshipEntity own = ship(s, ModContent.WARSHIP, x - 60, z + 40, 90, false);
                ship(s, ModContent.WARSHIP, x - 60, z + 64, 270, true);
                p.teleportTo(s.overworld(), own.getX(), own.getY() + 2, own.getZ(), java.util.Set.of(), 90, 15, false);
                p.startRiding(own, true, false);
            });
            context.waitTicks(20);
            context.runOnClient(mc -> org.webtrade.minecraftportsmod.client.CombatClient.fight(true, -1));
            context.waitTicks(45);
            sp.getConnection().waitForChunksRender();
            context.takeScreenshot("preview_7_gameplay");
        } finally {
            Pirates.enabled = true;
        }
    }
}
