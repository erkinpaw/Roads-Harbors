package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.webtrade.minecraftportsmod.Experimental;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Duels;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/** Shots for a video's preview: the village from above and in its streets at sundown, its pier, a duel; no HUD. */
public class ThumbClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[thumb] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static void look(MinecraftServer s, double x, double y, double z, double tx, double ty, double tz) {
        s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), String.format(java.util.Locale.ROOT,
                "tp @a %.1f %.1f %.1f facing %.1f %.1f %.1f", x, y, z, tx, ty, tz));
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean was = Experimental.duels();
        Experimental.setDuels(true);
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("4242")).create()) {
            var server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            context.getInput().resizeWindow(1920, 1080);
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("weather clear");
            server.runCommand("tp @a 300 100 -300");
            sp.getConnection().waitForChunksRender();
            server.runCommand("spreadplayers 300 -300 0 40 false @a");
            sp.getConnection().waitForChunksRender();
            context.waitTicks(40);
            server.runCommand("execute as @p at @p run village sandbox");
            context.waitTicks(20);
            int[] id = {-1};
            double[] c = new double[3], pier = new double[3];
            server.runOnServer(s -> {
                Village v = null;
                for (Village x : VillageData.get(s).all()) if (x.name.contains("Тест") || x.name.contains("Test")) v = x;
                id[0] = v.id;
                c[0] = v.center.getX();
                c[1] = v.center.getY();
                c[2] = v.center.getZ();
                for (Building b : v.buildings()) {
                    if (b.type == BuildingType.PIER) {
                        pier[0] = b.origin.getX();
                        pier[1] = b.origin.getY();
                        pier[2] = b.origin.getZ();
                    }
                }
                org.webtrade.minecraftportsmod.colony.Harbour.testShip(v);
                log("village {} at {}, pier {}", v.name, v.center.toShortString(), (int) pier[0] + " " + (int) pier[2]);
            });
            // the people about their work in the day, then the sundown
            server.runCommand("time set 6000");
            context.waitTicks(20 * 25);
            server.runCommand("time set 12300");
            server.runCommand("gamemode spectator @a");
            context.runOnClient(mc -> mc.gui.hud.toggle());

            // 1. from above, two sides
            server.runOnServer(s -> look(s, c[0] - 45, c[1] + 38, c[2] - 45, c[0], c[1], c[2]));
            sp.getConnection().waitForChunksRender();
            context.waitTicks(40);
            context.takeScreenshot("preview_01_village_above");
            server.runOnServer(s -> look(s, c[0] + 50, c[1] + 25, c[2] + 30, c[0], c[1] + 2, c[2]));
            sp.getConnection().waitForChunksRender();
            context.waitTicks(40);
            context.takeScreenshot("preview_02_village_side");
            // 2. in the street, at a person's height
            server.runOnServer(s -> look(s, c[0] + 7, c[1] + 2.2, c[2] + 9, c[0] - 6, c[1] + 2, c[2] - 8));
            sp.getConnection().waitForChunksRender();
            context.waitTicks(40);
            context.takeScreenshot("preview_03_street");
            // 3. the pier and the ship at it
            if (pier[0] != 0 || pier[2] != 0) {
                server.runOnServer(s -> look(s, pier[0] - 18, pier[1] + 10, pier[2] - 14, pier[0] + 6, pier[1], pier[2] + 4));
                sp.getConnection().waitForChunksRender();
                context.waitTicks(60);
                context.takeScreenshot("preview_04_pier");
            }

            // 4. a duel at sundown: the fighter coming on
            server.runCommand("gamemode survival @a");
            server.runOnServer(s -> look(s, c[0] + 3, c[1] + 1, c[2] + 3, c[0], c[1] + 1, c[2]));
            context.waitTicks(20);
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(id[0]);
                var d = v.dwellers().stream().filter(x -> !x.elder() && x.job() != null).findFirst().orElse(v.elder());
                ColonyService.handleAction(player(s), new ColonyPayloads.VillageAction(v.id, ColonyPayloads.VillageAction.DUEL, d.id, 0));
            });
            context.waitTicks(30);
            sp.getConnection().waitForChunksRender();
            // (the player keeps standing: no harm comes to them for the shots)
            server.runOnServer(s -> {
                player(s).setInvulnerable(true);
                player(s).getAbilities().invulnerable = true;
            });
            for (int k = 0; k < 8; k++) {
                context.waitTicks(15);
                server.runOnServer(s -> {
                    var f = Duels.fighter(player(s));
                    if (f != null) {
                        var p = player(s);
                        p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, f.getEyePosition());
                        p.connection.teleport(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
                    }
                });
                if (k >= 3) context.takeScreenshot("preview_05_duel_" + k);
            }
            context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            for (int k = 0; k < 4; k++) {
                context.waitTicks(12);
                context.takeScreenshot("preview_06_duel_behind_" + k);
            }
            context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
            context.waitTicks(10);
            context.takeScreenshot("preview_07_duel_front");
            context.runOnClient(mc -> {
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.gui.hud.toggle();
            });
        } finally {
            Experimental.setDuels(was);
        }
    }
}
