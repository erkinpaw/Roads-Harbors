package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;

/**
 * Many villages, most of them far from the player, and 25 days skipped at once (as a player did, and the game froze
 * for minutes): the skip must take seconds, not minutes, and the far villages must still grow.
 */
public class DaySkipClientGameTest implements FabricClientGameTest {

    private static final int VILLAGES = 8;

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[skip] " + fmt, args);
    }

    private static String summary(VillageData data) {
        StringBuilder b = new StringBuilder();
        for (Village v : data.all()) {
            int built = 0;
            for (Building x : v.buildings()) if (x.state() == Building.State.BUILT) built++;
            b.append('#').append(v.id).append(' ').append(v.population()).append("p/").append(built).append("b ");
        }
        return b.toString();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("4242")).create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.waitFor(s -> VillageData.get(s).all().size() >= VILLAGES, 20 * 900);
            server.runOnServer(s -> log("{} villages: {}", VillageData.get(s).all().size(), summary(VillageData.get(s))));
            for (int round = 1; round <= 4; round++) {
                long[] ms = {0};
                server.runOnServer(s -> {
                    long t0 = System.nanoTime();
                    s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "village day 25");
                    ms[0] = (System.nanoTime() - t0) / 1_000_000;
                });
                final int r = round;
                server.runOnServer(s -> log("round {}: 25 days skipped in {} ms | {}", r, ms[0], summary(VillageData.get(s))));
                if (ms[0] > 20_000) throw new AssertionError("25 days took " + ms[0] + " ms");
                // the land round the far villages is read meanwhile, on its own thread
                context.waitTicks(20 * 40);
            }
            // the trails: each village to its nearest few, the shortest worked out first
            for (int k = 1; k <= 3; k++) {
                context.waitTicks(20 * 50);
                final int kk = k;
                server.runOnServer(s -> {
                    VillageData data = VillageData.get(s);
                    int ready = 0, none = 0, waiting = 0, known = 0;
                    for (var t : data.trails()) {
                        if (t.ready()) ready++;
                        else if (t.none()) none++;
                        else waiting++;
                    }
                    for (Village v : data.all()) known += v.knownCount();
                    log("after {} s: trails ready {}, no way {}, being worked out {} (villages know {} others in all)", kk * 50, ready, none, waiting, known);
                });
            }
        }
    }
}
