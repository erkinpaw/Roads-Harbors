package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Trails;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.colony.VillageManager;

/**
 * A village in the real world, watched: does anyone jump about (onto roofs, into tents, up and down), where the
 * smithy and the miners stand, is the village's own land kept clean (no flowers, grass, trees, stumps, bumps), is
 * anything built on sand.
 */
public class NightClientGameTest implements FabricClientGameTest {

    private static final String SEED = "4242";

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[night] " + fmt, args);
    }

    private static Village village(MinecraftServer s) {
        VillageData data = VillageData.get(s);
        var p = s.getPlayerList().getPlayers().getFirst();
        Village best = null;
        for (Village v : data.all()) if (best == null || v.center.distSqr(p.blockPosition()) < best.center.distSqr(p.blockPosition())) best = v;
        return best;
    }

    /** What is left untidy on the village's land (out to 24 blocks from the middle, off the plots). */
    private static String mess(MinecraftServer s, Village v) {
        var level = s.overworld();
        int plants = 0, logs = 0, bumps = 0, pits = 0, sand = 0;
        for (int x = -24; x <= 24; x++) {
            for (int z = -24; z <= 24; z++) {
                int wx = v.center.getX() + x, wz = v.center.getZ() + z;
                boolean plot = false;
                for (Building b : v.buildings()) {
                    if (Math.abs(wx - b.origin.getX()) <= b.type.half + 1 && Math.abs(wz - b.origin.getZ()) <= b.type.half + 1) plot = true;
                }
                if (plot) continue;
                int g = Trails.groundAt(level, wx, wz);
                var top = level.getBlockState(new BlockPos(wx, g, wz));
                var up = level.getBlockState(new BlockPos(wx, g + 1, wz));
                if (!up.isAir() && (up.is(BlockTags.FLOWERS) || up.is(Blocks.SHORT_GRASS) || up.is(Blocks.TALL_GRASS) || up.is(Blocks.FERN))) plants++;
                if (up.is(BlockTags.LOGS)) logs++;
                int hi = Integer.MIN_VALUE, lo = Integer.MAX_VALUE;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int n = Trails.groundAt(level, wx + d[0], wz + d[1]);
                    hi = Math.max(hi, n);
                    lo = Math.min(lo, n);
                }
                if (g > hi) bumps++;
                if (g < lo) pits++;
                if (top.is(Blocks.SAND)) sand++;
            }
        }
        return "plants " + plants + ", logs " + logs + ", bumps " + bumps + ", pits " + pits + ", sand " + sand;
    }

    private static String buildings(Village v) {
        StringBuilder b = new StringBuilder();
        for (Building x : v.buildings()) {
            b.append(x.type.id()).append('@').append((int) Math.sqrt(x.origin.distSqr(v.center))).append(' ');
        }
        return b.toString();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed(SEED)).create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("time set 1000");
            server.waitFor(s -> VillageData.get(s).all().size() >= 1, 20 * 600);
            int[] id = {-1};
            server.runOnServer(s -> {
                Village v = village(s);
                id[0] = v.id;
                s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 30, v.center.getZ() + 20.5,
                        java.util.Set.of(), 180, 45, false);
                log("village #{} {} at {}: {}", v.id, v.name, v.center.toShortString(), buildings(v));
            });
            context.waitTicks(100);
            sp.getConnection().waitForChunksRender();
            server.runOnServer(s -> log("mess at the start: {}", mess(s, village(s))));
            // grown a while: days skipped with plenty to build with, so the smithy and the miners come
            server.runCommand("village give " + id[0] + " wood 600");
            server.runCommand("village give " + id[0] + " stone 300");
            server.runCommand("village give " + id[0] + " food 400");
            server.runCommand("village build " + id[0] + " smithy 1");
            server.runCommand("village build " + id[0] + " mine_house 1");
            for (int day = 1; day <= 12; day++) {
                server.runCommand("village day");
                context.waitTicks(40);
            }
            server.runOnServer(s -> {
                Village v = village(s);
                log("after 12 days: {} people, {}", v.population(), buildings(v));
            });
            // watched in real time: three in-game hours, the player hovering over the middle
            server.runOnServer(s -> VillageManager.SNAPS.clear());
            for (int k = 0; k < 9; k++) {
                context.waitTicks(20 * 20);
                server.runOnServer(s -> log("snaps so far {}", VillageManager.SNAPS.size()));
            }
            server.runOnServer(s -> {
                Village v = village(s);
                for (String line : VillageManager.SNAPS) log("snap: {}", line);
                long roofs = VillageManager.SNAPS.stream().filter(l -> l.contains("roof")).count();
                log("snaps {} (roofs {}) in 3 minutes with {} people", VillageManager.SNAPS.size(), roofs, v.population());
                // where the smithy and the miners are
                var level = s.overworld();
                for (Building b : v.buildings()) {
                    if (b.type != BuildingType.SMITHY && b.type != BuildingType.MINE_HOUSE) continue;
                    int d = (int) Math.sqrt(b.origin.distSqr(v.center));
                    int rock = 0;
                    for (BlockPos q : BlockPos.betweenClosed(b.origin.offset(-12, -2, -12), b.origin.offset(12, 8, 12))) {
                        var st = level.getBlockState(q);
                        if (st.is(Blocks.STONE) || st.is(Blocks.ANDESITE) || st.is(Blocks.GRANITE) || st.is(Blocks.DIORITE)) rock++;
                    }
                    log("{} {} blocks from the middle, y {} (middle {}), rock round it {}", b.type.id(), d, b.origin.getY(), v.center.getY(), rock);
                    if (b.type == BuildingType.SMITHY && d > 40) throw new AssertionError("the smithy stands out of the village: " + d);
                }
                // nothing on sand
                for (Building b : v.buildings()) {
                    var under = level.getBlockState(b.origin.below());
                    if (under.is(Blocks.SAND) || under.is(Blocks.RED_SAND)) log("WARN {} stands on sand at {}", b.type.id(), b.origin.toShortString());
                }
                log("mess after: {}", mess(s, v));
                log("store: tools {} wheat {} joinery {}", v.stock(Res.TOOLS1), v.stock(Res.WHEAT), v.stock(Res.JOINERY));
            });
            context.takeScreenshot("night_a_village");
            server.runOnServer(s -> {
                Village v = village(s);
                s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 60, v.center.getZ() + 0.5,
                        java.util.Set.of(), 0, 90, false);
            });
            context.waitTicks(80);
            sp.getConnection().waitForChunksRender();
            context.takeScreenshot("night_b_above");
            // the miners' house, seen
            server.runOnServer(s -> {
                Village v = village(s);
                for (Building b : v.buildings()) {
                    if (b.type != BuildingType.MINE_HOUSE) continue;
                    s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), b.origin.getX() + 14.5, b.origin.getY() + 12, b.origin.getZ() + 14.5,
                            java.util.Set.of(), 135, 30, false);
                }
            });
            context.waitTicks(80);
            sp.getConnection().waitForChunksRender();
            context.takeScreenshot("night_c_mine");
            server.runOnServer(s -> {
                long roofs = VillageManager.SNAPS.stream().filter(l -> l.contains("roof")).count();
                if (roofs > 2) throw new AssertionError("people put down off roofs " + roofs + " times");
            });
        }
    }
}
