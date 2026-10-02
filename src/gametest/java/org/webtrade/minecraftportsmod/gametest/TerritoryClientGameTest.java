package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Territory;
import org.webtrade.minecraftportsmod.colony.Trails;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * The village's land, grown on real worlds twice — its land capped by its people and not — for the two to be
 * compared on screenshots: what is left on the land (trees, vines, branches, pits), who wades through water, whether
 * the paths are one network, how near the water the houses stand.
 */
public class TerritoryClientGameTest implements FabricClientGameTest {

    /** Seeds to grow a village on (set -Dmpm.territorySeeds=a,b to change). */
    private static final String[] SEEDS = {"4242"};
    /** Which ways to grow it: with the land capped, and not. */
    private static final boolean[] CAPS = {false};
    private static final int DAYS = Integer.getInteger("mpm.territoryDays", 60);
    /** Real working time after the days (ticks): the people at the land. */
    private static final int WORK = 20 * Integer.getInteger("mpm.territoryWork", 180);

    private static void log(String tag, String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[territory " + tag + "] " + fmt, args);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean was = Territory.capped;
        try {
            for (String seed : SEEDS) {
                for (boolean cap : CAPS) {
                    Territory.capped = cap;
                    run(context, seed.trim(), cap);
                }
            }
        } finally {
            Territory.capped = was;
        }
    }

    private void run(ClientGameTestContext context, String seed, boolean cap) {
        String tag = seed + (cap ? " capped" : " free");
        String shot = "territory_" + seed + (cap ? "_capped" : "_free");
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed(seed)).create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("time set 3000");
            server.waitFor(s -> VillageData.get(s).all().size() >= 1, 20 * 600);
            int[] id = {-1};
            server.runOnServer(s -> {
                BlockPos spawn = s.getPlayerList().getPlayers().getFirst().blockPosition();
                double best = Double.MAX_VALUE;
                for (Village v : VillageData.get(s).all()) {
                    if (v.center.distSqr(spawn) < best) {
                        best = v.center.distSqr(spawn);
                        id[0] = v.id;
                    }
                }
                Village v = VillageData.get(s).get(id[0]);
                var biome = s.overworld().getBiome(v.center).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
                log(tag, "village #{} {} at {} biome {}", v.id, v.name, v.center.toShortString(), biome);
                s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 30,
                        v.center.getZ() + 30.5, java.util.Set.of(), 180, 45, false);
            });
            final int vid = id[0];
            context.waitTicks(60);
            sp.getConnection().waitForChunksRender();
            for (int day = 1; day <= DAYS; day++) {
                server.runCommand("village day");
                context.waitTicks(50);
                if (day % 10 == 0) {
                    final int d = day;
                    server.runOnServer(s -> {
                        Village v = VillageData.get(s).get(vid);
                        log(tag, "day {}: people {} buildings {} land {} m2 (cap {})", d, v.population(), v.buildings().size(), Territory.area(v),
                                1500 + 250 * v.population());
                    });
                }
            }
            // a few minutes of the working day, as it goes: who wades through water?
            int[] wading = {0, 0};
            BlockPos[] firstWet = {null};
            Set<String> waders = new HashSet<>();
            for (int t = 0; t < WORK; t += 100) {
                context.waitTicks(100);
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(vid);
                    for (ResidentEntity e : s.overworld().getEntitiesOfClass(ResidentEntity.class, new AABB(v.center).inflate(120), ResidentEntity::colony)) {
                        wading[1]++;
                        if (e.isInWater()) {
                            wading[0]++;
                            if (firstWet[0] == null) firstWet[0] = e.blockPosition();
                            if (waders.add(e.getName().getString())) log(tag, "IN WATER {} at {}: {}", e.getName().getString(), e.blockPosition().toShortString(),
                                    e.activity().getString());
                        }
                    }
                });
            }
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(vid);
                log(tag, "in water: {} of {} looks ({} people)", wading[0], wading[1], waders.size());
                land(s, tag, v);
            });
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(vid);
                s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 60,
                        v.center.getZ() + 0.5, java.util.Set.of(), 180, 90, false);
            });
            context.waitTicks(100);
            sp.getConnection().waitForChunksRender();
            context.takeScreenshot(shot + "_a_top");
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(vid);
                s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 45,
                        v.center.getZ() + 55.5, java.util.Set.of(), 180, 40, false);
            });
            context.waitTicks(80);
            sp.getConnection().waitForChunksRender();
            context.takeScreenshot(shot + "_b_south");
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(vid);
                s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), v.center.getX() - 40.5, v.center.getY() + 25,
                        v.center.getZ() - 40.5, java.util.Set.of(), -45, 25, false);
            });
            context.waitTicks(80);
            sp.getConnection().waitForChunksRender();
            context.takeScreenshot(shot + "_c_nw");
            if (firstWet[0] != null) {
                BlockPos w = firstWet[0];
                server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), w.getX() + 8.5, w.getY() + 10,
                        w.getZ() + 8.5, java.util.Set.of(), 135, 40, false));
                context.waitTicks(60);
                sp.getConnection().waitForChunksRender();
                context.takeScreenshot(shot + "_d_wet");
            }
        }
    }

    /** What is on the village's land after the days: trees, vines, branches, pits; paths; houses by the water. */
    private static void land(MinecraftServer s, String tag, Village v) {
        ServerLevel level = s.overworld();
        Set<Long> cells = Territory.cells(v);
        int trees = 0, hanging = 0, branches = 0, pits = 0, cols = 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (long k : cells) {
            int cx = (int) (k >> 32), cz = (int) k;
            minX = Math.min(minX, cx * Territory.CELL);
            maxX = Math.max(maxX, cx * Territory.CELL + Territory.CELL - 1);
            minZ = Math.min(minZ, cz * Territory.CELL);
            maxZ = Math.max(maxZ, cz * Territory.CELL + Territory.CELL - 1);
            for (int dx = 0; dx < Territory.CELL; dx++) {
                for (int dz = 0; dz < Territory.CELL; dz++) {
                    int x = cx * Territory.CELL + dx, z = cz * Territory.CELL + dz;
                    if (inPlot(v, x, z)) continue;
                    cols++;
                    int g = Trails.groundAt(level, x, z);
                    BlockState top = level.getBlockState(new BlockPos(x, g, z));
                    if (!top.getFluidState().isEmpty()) continue;
                    BlockState stand = level.getBlockState(new BlockPos(x, g + 1, z));
                    if (stand.is(BlockTags.LOGS)) trees++;
                    for (int y = g + 2; y <= g + 28; y++) {
                        BlockState st = level.getBlockState(new BlockPos(x, y, z));
                        if (st.is(Blocks.VINE) || st.is(Blocks.COCOA)) hanging++;
                        if (st.is(BlockTags.LOGS) && !level.getBlockState(new BlockPos(x, y - 1, z)).is(BlockTags.LOGS)) branches++;
                    }
                    int lower = 0;
                    for (int[] d : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
                        if (Trails.groundAt(level, x + d[0], z + d[1]) - g >= 2) lower++;
                    }
                    if (lower == 4) pits++;
                }
            }
        }
        log(tag, "land: {} m2, {}x{} blocks; on {} open columns: tree trunks {}, vines/cocoa {}, branches in the air {}, pits {}",
                Territory.area(v), maxX - minX + 1, maxZ - minZ + 1, cols, trees, hanging, branches, pits);
        // houses by the water
        int wet = 0;
        for (Building b : v.buildings()) {
            if (b.type.branch == BuildingType.Branch.COAST || b.type.isCenter()) continue;
            boolean near = false;
            int r = b.type.half + 4;
            for (int dx = -r; dx <= r && !near; dx++) {
                for (int dz = -r; dz <= r && !near; dz++) {
                    int x = b.origin.getX() + dx, z = b.origin.getZ() + dz;
                    if (inPlot(v, x, z)) continue;
                    if (!level.getBlockState(new BlockPos(x, Trails.groundAt(level, x, z), z)).getFluidState().isEmpty()) near = true;
                }
            }
            if (near) {
                wet++;
                log(tag, "BY THE WATER: {} #{} at {}", b.type.id(), b.id, b.origin.toShortString());
            }
        }
        // the paths: one network? every door reached from the square over paths and bridges
        Set<Long> reached = new HashSet<>();
        ArrayDeque<int[]> open = new ArrayDeque<>();
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                int x = v.center.getX() + dx, z = v.center.getZ() + dz;
                if (way(level, x, z) && reached.add(key(x, z))) open.add(new int[]{x, z});
            }
        }
        while (!open.isEmpty()) {
            int[] c = open.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                int x = c[0] + d[0], z = c[1] + d[1];
                if (Math.abs(x - v.center.getX()) > 200 || Math.abs(z - v.center.getZ()) > 200) continue;
                if (!reached.contains(key(x, z)) && way(level, x, z)) {
                    reached.add(key(x, z));
                    open.add(new int[]{x, z});
                }
            }
        }
        int doors = 0, linked = 0;
        for (Building b : v.buildings()) {
            if (b.type.isCenter() || b.state() != Building.State.BUILT) continue;
            doors++;
            BlockPos door = b.blueprint(v.wood).frame.at(0, 0, b.type.half + 1);
            boolean ok = false;
            for (int dx = -2; dx <= 2 && !ok; dx++) for (int dz = -2; dz <= 2 && !ok; dz++) ok = reached.contains(key(door.getX() + dx, door.getZ() + dz));
            if (ok) linked++;
            else log(tag, "NOT LINKED: {} #{} door {}", b.type.id(), b.id, door.toShortString());
        }
        log(tag, "paths: {} blocks reached from the square; doors linked {} of {}; buildings by the water {}", reached.size(), linked, doors, wet);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static boolean way(ServerLevel level, int x, int z) {
        int g = Trails.groundAt(level, x, z);
        BlockState st = level.getBlockState(new BlockPos(x, g, z));
        return st.is(Blocks.DIRT_PATH) || st.is(BlockTags.PLANKS) && !level.getBlockState(new BlockPos(x, g - 1, z)).isSolid()
                || st.is(Blocks.STONE_BRICKS) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.GRAVEL);
    }

    private static boolean inPlot(Village v, int x, int z) {
        for (Building b : v.buildings()) {
            if (Math.abs(x - b.origin.getX()) <= b.type.half && Math.abs(z - b.origin.getZ()) <= b.type.half) return true;
        }
        return false;
    }
}
