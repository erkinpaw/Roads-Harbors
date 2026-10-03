package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.colony.VillageManager;

/**
 * The village styles side by side: four camps, each made to build in one style (timber and plaster, log cabins,
 * stone and brick, painted boards), with the same homes and workshops at their full level; a picture of each from
 * above. Each building takes touches of its own (the ridge's way, the roof's kind, shutters, a canopy).
 */
public class LooksClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[looks] " + fmt, args);
    }

    private static final String[] BUILD = {"hut 3", "hut 3", "house 3", "house 3", "house_tall 3", "stone_house 3", "stone_house_tall 3",
            "smithy 3", "wood_hut 3", "storehouse_2 3", "carpenter 3"};

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            context.getInput().resizeWindow(1920, 1080);
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 5000");
            for (int s = 0; s < 4; s++) {
                int x = s * 160;
                server.runCommand("fill " + (x - 30) + " -61 -60 " + (x + 30) + " -62 -40 water");
                server.runCommand("tp @a " + x + " -60 -20 0 0");
                context.waitTicks(40);
                server.runCommand("execute as @a at @s run village camp");
                context.waitTicks(10);
                final int id = s + 1, style = s;
                server.runOnServer(srv -> VillageManager.style(srv, VillageData.get(srv).get(id), style));
                for (String b : BUILD) server.runCommand("village build " + id + " " + b);
                context.waitTicks(20);
                server.runOnServer(srv -> {
                    Village v = VillageData.get(srv).get(id);
                    int n = 0;
                    for (Building b : v.buildings()) if (b.standing()) n++;
                    log("village {} style {}: {} buildings", id, style, n);
                    srv.getPlayerList().getPlayers().getFirst().teleportTo(srv.overworld(), v.center.getX() + 0.5, v.center.getY() + 30,
                            v.center.getZ() + 34.5, java.util.Set.of(), 180, 45, false);
                });
                context.waitTicks(60);
                context.takeScreenshot("looks_" + s + "_a");
                server.runOnServer(srv -> {
                    Village v = VillageData.get(srv).get(id);
                    srv.getPlayerList().getPlayers().getFirst().teleportTo(srv.overworld(), v.center.getX() - 26.5, v.center.getY() + 12,
                            v.center.getZ() + 18.5, java.util.Set.of(), 235, 20, false);
                });
                context.waitTicks(40);
                context.takeScreenshot("looks_" + s + "_b");
            }
        }
    }
}
