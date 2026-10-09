package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Blueprint;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Mine;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.colony.VillageManager;

/**
 * Miners with no house of their own yet: the village keeps a plot well out for it, and they dig the pit behind it
 * meanwhile (a cobblestone stair, ladders, posts with lanterns as its layers are dug out); the house, once built,
 * stands on that plot by the pit.
 */
public class MinePitClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[pit] " + fmt, args);
    }

    /** Blocks of a kind in and round the pit. */
    private static int count(net.minecraft.server.level.ServerLevel level, BlockPos pit, net.minecraft.world.level.block.Block kind) {
        int n = 0;
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++)
                for (int y = pit.getY() - 8; y <= pit.getY() + 3; y++) if (level.getBlockState(new BlockPos(pit.getX() + dx, y, pit.getZ() + dz)).is(kind)) n++;
        return n;
    }

    private static void shot(ClientGameTestContext context, net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext server, BlockPos pit, String name) {
        server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), pit.getX() + 7.5, pit.getY() + 6,
                pit.getZ() + 7.5, java.util.Set.of(), 135, 40, false));
        context.waitTicks(20);
        context.takeScreenshot(name);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            var server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            context.getInput().resizeWindow(1920, 1080);
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 1500");
            server.runCommand("tp @a 0 -60 0 180 0");
            context.waitTicks(20);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(40);
            server.runCommand("village grow 1 miner");
            server.runCommand("village grow 1 miner");
            server.runCommand("village day");
            context.waitTicks(40);

            // the plot kept for the miners' house, well out of the village
            Blueprint.Frame[] plot = new Blueprint.Frame[1];
            BlockPos[] pit = new BlockPos[1];
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                plot[0] = v.minePlot();
                if (plot[0] == null) throw new AssertionError("no plot kept for the miners' house");
                pit[0] = Mine.pitCenter(plot[0].origin(), plot[0].front());
                double away = Math.hypot(pit[0].getX() - v.center.getX(), pit[0].getZ() - v.center.getZ());
                log("plot {} facing {}, pit at {}, {} blocks out", plot[0].origin().toShortString(), plot[0].front(), pit[0].toShortString(), (int) away);
                if (v.count(BuildingType.MINE_HOUSE, false) > 0) throw new AssertionError("the miners' house was built at once");
                if (away < 40) throw new AssertionError("the pit is in the village: " + (int) away);
            });
            // watching from over the pit (the village is still in sight)
            server.runCommand("tp @a " + (pit[0].getX() + 10) + " -50 " + (pit[0].getZ() + 10));
            // (until the stair and the ladders are in: the layers under them dug out first; 8 minutes at most)
            for (int i = 0; i < 48; i++) {
                context.waitTicks(20 * 10);
                final int secs = (i + 1) * 10;
                int[] fitted = new int[1];
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(1);
                    fitted[0] = count(s.overworld(), pit[0], Blocks.COBBLESTONE_STAIRS) * count(s.overworld(), pit[0], Blocks.LADDER);
                    log("t={}s step {} stone {} iron {} fitted {}", secs, VillageManager.mineStepOf(v), v.stock(Res.STONE), v.stock(Res.IRON), fitted[0] > 0);
                });
                if (i == 14) shot(context, server, pit[0], "pit_" + i);
                if (fitted[0] > 0) break;
            }
            shot(context, server, pit[0], "pit_fitted");
            // dug, and fitted out
            server.runOnServer(s -> {
                var level = s.overworld();
                int open = 0, stairs = 0, ladders = 0, lanterns = 0;
                for (int dx = -5; dx <= 5; dx++) {
                    for (int dz = -5; dz <= 5; dz++) {
                        for (int y = pit[0].getY() - 8; y <= pit[0].getY() + 3; y++) {
                            BlockState st = level.getBlockState(new BlockPos(pit[0].getX() + dx, y, pit[0].getZ() + dz));
                            if (st.is(Blocks.COBBLESTONE_STAIRS)) stairs++;
                            if (st.is(Blocks.LADDER)) ladders++;
                            if (st.is(Blocks.LANTERN)) lanterns++;
                        }
                        if (Math.abs(dx) <= 3 && Math.abs(dz) <= 3
                                && level.getBlockState(new BlockPos(pit[0].getX() + dx, pit[0].getY() - 1, pit[0].getZ() + dz)).isAir()) open++;
                    }
                }
                log("pit: top layer {} of 49 open; stairs {}, ladders {}, lanterns {}", open, stairs, ladders, lanterns);
                if (open < 25) throw new AssertionError("the pit was hardly dug: " + open + " of 49");
                if (stairs == 0 || ladders == 0) throw new AssertionError("the pit has no stair or no ladder: " + stairs + " / " + ladders);
            });
            // the house, once built, on the plot kept for it
            server.runCommand("village build 1 mine_house");
            context.waitTicks(40);
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                Building h = null;
                for (Building b : v.buildings()) if (b.type == BuildingType.MINE_HOUSE) h = b;
                if (h == null) throw new AssertionError("no miners' house");
                log("miners' house at {} facing {} (plot {}); plot still kept: {}", h.origin.toShortString(), h.front, plot[0].origin().toShortString(), v.minePlot() != null);
                if (h.origin.getX() != plot[0].origin().getX() || h.origin.getZ() != plot[0].origin().getZ() || h.front != plot[0].front())
                    throw new AssertionError("the miners' house is not on the plot kept for it");
                if (v.minePlot() != null) throw new AssertionError("the plot is still kept with the house built");
            });
            // the woodcutters' hut: as far out, and not by the pit
            server.runCommand("village build 1 wood_hut");
            context.waitTicks(20);
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                Building w = null;
                for (Building b : v.buildings()) if (b.type == BuildingType.WOOD_HUT) w = b;
                if (w == null) throw new AssertionError("no woodcutters' hut");
                double out = Math.hypot(w.origin.getX() - v.center.getX(), w.origin.getZ() - v.center.getZ());
                double fromPit = Math.hypot(w.origin.getX() - pit[0].getX(), w.origin.getZ() - pit[0].getZ());
                log("woodcutters' hut at {}: {} blocks out, {} from the pit", w.origin.toShortString(), (int) out, (int) fromPit);
                if (out < 36) throw new AssertionError("the woodcutters' hut is in the village: " + (int) out);
                if (fromPit < 40) throw new AssertionError("the woodcutters' hut is by the pit: " + (int) fromPit);
            });
            server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), pit[0].getX() + 12.5, pit[0].getY() + 10,
                    pit[0].getZ() + 12.5, java.util.Set.of(), 135, 35, false));
            context.waitTicks(40);
            context.takeScreenshot("pit_house");
        }
    }
}
