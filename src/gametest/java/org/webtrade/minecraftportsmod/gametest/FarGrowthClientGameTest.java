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
 * A village nobody is near still grows: the player flies 4000 blocks away, forty village days go by, the village
 * plans and puts up buildings on paper (its plots read off the world's generator); back there, the buildings stand
 * up in the world.
 */
public class FarGrowthClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[far] " + fmt, args);
    }

    private static String state(Village v) {
        int built = 0, planned = 0, placedAll = 0;
        StringBuilder b = new StringBuilder();
        for (Building x : v.buildings()) {
            if (x.state() == Building.State.BUILT) built++;
            else planned++;
            b.append(x.type.id()).append(x.level());
            if (x.state() != Building.State.BUILT) b.append(':').append(x.state().id());
            b.append(' ');
        }
        return "people " + v.population() + ", built " + built + ", under way " + planned + " | " + b;
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("4242")).create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.waitFor(s -> VillageData.get(s).all().size() >= 1, 20 * 600);
            int[] id = {-1};
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                Village best = null;
                for (Village v : VillageData.get(s).all()) if (best == null || v.center.distSqr(p.blockPosition()) < best.center.distSqr(p.blockPosition())) best = v;
                id[0] = best.id;
                log("village #{} {} at {}: {}", best.id, best.name, best.center.toShortString(), state(best));
                // far away: the village's land is no longer loaded
                p.teleportTo(s.overworld(), best.center.getX() + 4000.5, 200, best.center.getZ() + 0.5, java.util.Set.of(), 0, 0, false);
            });
            context.waitTicks(200);
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(id[0]);
                boolean loaded = s.overworld().getChunkSource().getChunkNow(v.center.getX() >> 4, v.center.getZ() >> 4) != null;
                log("away: village chunk loaded {}", loaded);
            });
            for (int day = 1; day <= 40; day++) {
                server.runCommand("village day");
                context.waitTicks(4);
                if (day % 10 == 0) {
                    final int d = day;
                    server.runOnServer(s -> log("day {} (nobody near): {}", d, state(VillageData.get(s).get(id[0]))));
                }
            }
            int[] before = {0};
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(id[0]);
                for (Building b : v.buildings()) if (b.state() == Building.State.BUILT) before[0]++;
                p(s).teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 40, v.center.getZ() + 30.5, java.util.Set.of(), 180, 45, false);
            });
            if (before[0] <= 3) throw new AssertionError("the village did not grow while nobody was near: " + before[0] + " buildings");
            context.waitTicks(20 * 60);
            sp.getConnection().waitForChunksRender();
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(id[0]);
                int placed = 0, pieces = 0;
                for (Building b : v.buildings()) {
                    placed += b.placed();
                    pieces += b.pieces(v);
                }
                log("back after a minute: blocks placed {} of {} | {}", placed, pieces, state(v));
            });
            context.takeScreenshot("far_back");
        }
    }

    private static net.minecraft.server.level.ServerPlayer p(net.minecraft.server.MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }
}
