package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

/**
 * How a village lies on uneven land: the buildings apart, the mine and the woodcutters out of the village, the land
 * eased down to every plot (no ledges), the paths a network with no breaks; and where the people spend a working
 * day (at their work and at home, not crowded in the middle).
 */
public class LayoutClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[layout] " + fmt, args);
    }

    private static Village village(MinecraftServer s) {
        return VillageData.get(s).get(1);
    }

    private static int top(MinecraftServer s, int x, int z) {
        return s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 6000");
            // uneven land: mounds and terraces of earth round the camp, water to the south, rock far east, a wood far west
            server.runCommand("fill -30 -61 -50 30 -62 -26 water");
            int[][] mounds = {{8, 6, 22, 20, 2}, {14, 10, 20, 16, 4}, {-24, 4, -8, 22, 1}, {-20, 8, -12, 16, 3}, {-8, 20, 10, 34, 2},
                    {18, -18, 30, -6, 3}, {-26, -20, -14, -8, 2}};
            for (int[] m : mounds) {
                server.runCommand("fill " + m[0] + " -60 " + m[1] + " " + m[2] + " " + (-61 + m[4]) + " " + m[3] + " dirt");
                server.runCommand("fill " + m[0] + " " + (-61 + m[4]) + " " + m[1] + " " + m[2] + " " + (-61 + m[4]) + " " + m[3] + " grass_block");
            }
            server.runCommand("fill 44 -60 -24 60 -54 24 stone");
            for (int[] t : new int[][]{{-40, 10}, {-44, 16}, {-38, 22}, {-46, 4}, {-42, -2}, {-50, 12}, {-48, 24}}) {
                server.runCommand("place feature minecraft:oak " + t[0] + " -60 " + t[1]);
            }
            server.runCommand("tp @a 0 -60 -10 0 0");
            context.waitTicks(20);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(40);
            server.runCommand("tp @a 0 -20 45 180 50");
            for (int day = 1; day <= 30; day++) {
                server.runCommand("village day");
                context.waitTicks(20 * 3);
                if (day % 5 == 0) {
                    final int d = day;
                    server.runOnServer(s -> {
                        Village v = village(s);
                        StringBuilder b = new StringBuilder();
                        for (Building x : v.buildings()) b.append(x.type.id()).append(x.level()).append(x.state() == Building.State.BUILT ? "" : ":" + x.state().id()).append(' ');
                        log("day {} {} people {} | {}", d, v.level(), v.population(), b);
                    });
                }
            }
            context.waitTicks(20 * 10);

            // ---- the layout: how far the trades are, how far apart the buildings
            server.runOnServer(s -> {
                Village v = village(s);
                double closest = Double.MAX_VALUE;
                for (Building a : v.buildings()) {
                    double dc = Math.sqrt(a.origin.distSqr(v.center));
                    if (a.type.isWorkshop() || a.type == BuildingType.MARKET) log("{} at {} blocks from the middle", a.type.id(), Math.round(dc));
                    for (Building b : v.buildings()) {
                        if (a == b || a.type.isCenter() || b.type.isCenter()) continue;
                        int gap = Math.max(Math.abs(a.origin.getX() - b.origin.getX()), Math.abs(a.origin.getZ() - b.origin.getZ())) - a.type.half - b.type.half - 1;
                        closest = Math.min(closest, gap);
                    }
                }
                log("closest gap between two plots: {}", closest);
            });

            // ---- ledges round the plots: k blocks out, the ground no more than k above or below the plot
            server.runOnServer(s -> {
                Village v = village(s);
                int bad = 0, checked = 0;
                for (Building b : v.buildings()) {
                    if (b.state() != Building.State.BUILT || b.type == BuildingType.TENT) continue;
                    int ground = b.origin.getY() - 1, h = b.type.half;
                    for (int k = 1; k <= 3; k++) {
                        for (int dx = -h - k; dx <= h + k; dx++) {
                            for (int dz = -h - k; dz <= h + k; dz++) {
                                if (Math.max(Math.abs(dx), Math.abs(dz)) != h + k) continue;
                                int x = b.origin.getX() + dx, z = b.origin.getZ() + dz;
                                boolean other = false;
                                for (Building o : v.buildings()) {
                                    if (Math.abs(x - o.origin.getX()) <= o.type.half && Math.abs(z - o.origin.getZ()) <= o.type.half) other = true;
                                }
                                if (other) continue;
                                var st = s.overworld().getBlockState(new BlockPos(x, top(s, x, z), z));
                                if (!st.getFluidState().isEmpty()) continue;
                                checked++;
                                int t = top(s, x, z);
                                if (Math.abs(t - ground) > k) {
                                    bad++;
                                    if (bad <= 8) log("LEDGE by {} #{} at {},{}: ground {} plot {} (k {}) block {}", b.type.id(), b.id, x, z, t, ground, k, st);
                                }
                            }
                        }
                    }
                }
                log("ledges: {} of {} columns round the plots", bad, checked);
            });

            // ---- the paths: every path block has a path neighbour, none more than a block up or down
            server.runOnServer(s -> {
                Village v = village(s);
                int blocks = 0, lonely = 0, steep = 0;
                for (int x = -90; x <= 90; x++) {
                    for (int z = -90; z <= 90; z++) {
                        int px = v.center.getX() + x, pz = v.center.getZ() + z;
                        int y = top(s, px, pz);
                        if (!s.overworld().getBlockState(new BlockPos(px, y, pz)).is(Blocks.DIRT_PATH)) continue;
                        blocks++;
                        boolean near = false, tooSteep = false;
                        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                            int ny = top(s, px + d[0], pz + d[1]);
                            if (s.overworld().getBlockState(new BlockPos(px + d[0], ny, pz + d[1])).is(Blocks.DIRT_PATH)) {
                                near = true;
                                if (Math.abs(ny - y) > 1) tooSteep = true;
                            }
                        }
                        if (!near) lonely++;
                        if (tooSteep) {
                            steep++;
                            if (steep <= 5) log("STEEP path at {},{},{}", px, y, pz);
                        }
                    }
                }
                log("paths: {} blocks, {} with no path next to them, {} with a step over a block", blocks, lonely, steep);
            });
            server.runCommand("tp @a 0 10 5 180 80");
            context.waitTicks(40);
            context.takeScreenshot("layout_00_above");
            server.runCommand("tp @a 0 -35 50 180 40");
            context.waitTicks(30);
            context.takeScreenshot("layout_01_from_south");
            server.runCommand("tp @a 45 -40 5 90 35");
            context.waitTicks(30);
            context.takeScreenshot("layout_02_from_east");

            // ---- a working day, as it goes: where are the people?
            server.runCommand("tp @a 0 -20 45 180 50");
            int[][] spread = new int[4][3];   // morning, noon, afternoon, evening × (in the middle, at home, elsewhere)
            int[] times = {2500, 5000, 8500, 11400};
            for (int part = 0; part < 4; part++) {
                server.runCommand("time set " + times[part]);
                for (int i = 0; i < 6; i++) {
                    context.waitTicks(20 * 5);
                    final int pp = part;
                    server.runOnServer(s -> {
                        Village v = village(s);
                        for (ResidentEntity e : s.overworld().getEntitiesOfClass(ResidentEntity.class, new net.minecraft.world.phys.AABB(v.center).inflate(100),
                                ResidentEntity::colony)) {
                            double dc = Math.sqrt(e.distanceToSqr(v.center.getX() + 0.5, v.center.getY(), v.center.getZ() + 0.5));
                            var d = v.dweller(e.colonyDweller());
                            Building home = d == null ? null : v.building(d.home());
                            double dh = home == null ? 999 : Math.sqrt(e.distanceToSqr(home.blueprint(v.wood).workSpot.getX() + 0.5,
                                    home.origin.getY(), home.blueprint(v.wood).workSpot.getZ() + 0.5));
                            if (dc < 7) spread[pp][0]++;
                            else if (dh < 5) spread[pp][1]++;
                            else spread[pp][2]++;
                        }
                    });
                }
                final int pp = part;
                server.runOnServer(s -> {
                    Village v = village(s);
                    StringBuilder b = new StringBuilder();
                    for (ResidentEntity e : s.overworld().getEntitiesOfClass(ResidentEntity.class, new net.minecraft.world.phys.AABB(v.center).inflate(100),
                            ResidentEntity::colony)) b.append(e.activity().getString()).append(" | ");
                    log("time {}: middle {} home {} elsewhere {} :: {}", times[pp], spread[pp][0], spread[pp][1], spread[pp][2], b);
                });
                if (part == 1) context.takeScreenshot("layout_03_noon");
            }
            server.runCommand("time set 6000");
        }
    }
}
