package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Trails;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;

/**
 * A trail cut through a jungle: no leaves or vines left hanging in the air over it, no wooden blocks in it off
 * the bridges. Two camps are set down in the jungle nearest the world's first village, a trail between them made.
 */
public class JungleClientGameTest implements FabricClientGameTest {

    private static final String SEED = "4242";

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[jungle] " + fmt, args);
    }

    private static int surface(MinecraftServer s, int x, int z) {
        var gen = s.overworld().getChunkSource().getGenerator();
        return gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, s.overworld(), s.overworld().getChunkSource().randomState());
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed(SEED)).create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("time set 6000");
            server.waitFor(s -> VillageData.get(s).all().size() >= 1, 20 * 600);
            int[] jungle = {0, 0, 0};
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                var found = s.overworld().findClosestBiome3d(h -> h.is(Biomes.JUNGLE) || h.is(Biomes.SPARSE_JUNGLE) || h.is(Biomes.BAMBOO_JUNGLE),
                        p.blockPosition(), 6400, 32, 64);
                if (found == null) return;
                jungle[0] = found.getFirst().getX();
                jungle[1] = found.getFirst().getZ();
                jungle[2] = 1;
                log("jungle at {} {} ({})", jungle[0], jungle[1], found.getSecond().getRegisteredName());
            });
            if (jungle[2] == 0) {
                log("no jungle in reach: nothing to test");
                return;
            }
            // two camps some 60 blocks apart, inside the jungle
            int[][] sites = {{jungle[0] + 40, jungle[1] + 40}, {jungle[0] - 30, jungle[1] - 30}};
            int[] ids = new int[2];
            for (int i = 0; i < 2; i++) {
                final int[] site = sites[i];
                server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), site[0] + 0.5, surface(s, site[0], site[1]) + 2,
                        site[1] + 0.5, java.util.Set.of(), 0, 0, false));
                context.waitTicks(80);
                sp.getConnection().waitForChunksRender();
                server.runCommand("execute as @a at @s run village camp");
                context.waitTicks(40);
                final int k = i;
                server.runOnServer(s -> {
                    int best = -1;
                    for (Village v : VillageData.get(s).all()) if (v.id > best) best = v.id;
                    ids[k] = best;
                    log("camp #{} at {}", best, VillageData.get(s).get(best).center.toShortString());
                });
            }
            if (ids[0] == ids[1]) throw new AssertionError("the second camp was not set down");
            server.runCommand("village trail " + ids[0] + " " + ids[1]);
            server.waitFor(s -> {
                Trails.Trail t = Trails.find(VillageData.get(s), ids[0], ids[1]);
                return t != null && (t.ready() || t.none());
            }, 20 * 300);
            server.runCommand("village roads finish");
            int[][] pts = {null};
            server.runOnServer(s -> {
                Trails.Trail t = Trails.find(VillageData.get(s), ids[0], ids[1]);
                log("trail: none={} length {} bridges {}", t.none(), t.length(), t.bridges());
                if (t.ready()) pts[0] = t.points();
            });
            if (pts[0] == null) throw new AssertionError("no trail through the jungle");
            int[] p = pts[0];
            for (int i = 0; i + 1 < p.length; i += 12) {
                final int x = p[i], z = p[i + 1];
                server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), x + 0.5, surface(s, x, z) + 30, z + 0.5,
                        java.util.Set.of(), 0, 90, false));
                context.waitTicks(60);
            }
            // what hangs over the way, and what it is made of
            server.runOnServer(s -> {
                var level = s.overworld();
                int floating = 0, vines = 0, wooden = 0, cols = 0;
                java.util.Set<Long> seen = new java.util.HashSet<>();
                for (int i = 0; i + 3 < p.length; i += 2) {
                    double dx = p[i + 2] - p[i], dz = p[i + 3] - p[i + 1], len = Math.max(1, Math.hypot(dx, dz));
                    for (double t = 0; t <= len; t += 1) {
                        int x = (int) Math.round(p[i] + dx * t / len), z = (int) Math.round(p[i + 1] + dz * t / len);
                        if (!seen.add(((long) x << 32) ^ (z & 0xFFFFFFFFL))) continue;
                        cols++;
                        int g = Trails.groundAt(level, x, z);
                        var top = level.getBlockState(new BlockPos(x, g, z));
                        if (top.is(BlockTags.PLANKS) || top.is(BlockTags.LOGS)) wooden++;
                        for (int y = g + 1; y <= g + 18; y++) {
                            var st = level.getBlockState(new BlockPos(x, y, z));
                            if (st.is(Blocks.VINE)) vines++;
                            if (st.is(BlockTags.LEAVES)) {
                                boolean trunk = false;
                                for (BlockPos q : BlockPos.betweenClosed(new BlockPos(x - 4, y - 4, z - 4), new BlockPos(x + 4, y + 1, z + 4))) {
                                    if (level.getBlockState(q).is(BlockTags.LOGS)) {
                                        trunk = true;
                                        break;
                                    }
                                }
                                if (!trunk) floating++;
                            }
                        }
                    }
                }
                log("over {} columns of the way: floating leaves {}, vines {}, wooden tops {}", cols, floating, vines, wooden);
            });
            int mid = (p.length / 4) * 2;
            final int mx = p[mid], mz = p[mid + 1];
            server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), mx + 10.5, surface(s, mx, mz) + 12, mz + 10.5,
                    java.util.Set.of(), 135, 35, false));
            context.waitTicks(100);
            sp.getConnection().waitForChunksRender();
            context.takeScreenshot("jungle_a_close");
            server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), mx + 0.5, surface(s, mx, mz) + 50, mz + 0.5,
                    java.util.Set.of(), 0, 90, false));
            context.waitTicks(80);
            sp.getConnection().waitForChunksRender();
            context.takeScreenshot("jungle_b_above");
        }
    }
}
