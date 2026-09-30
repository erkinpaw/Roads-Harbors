package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Dweller;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.village.ResidentEntity;
import org.webtrade.minecraftportsmod.worldgen.WorldPlan;

import java.util.HashMap;
import java.util.Map;

/**
 * A hundred village days in real worlds (their own seeds, their own land): how a village grows where the world put
 * it, what every trade does, where people get stuck, what the land looks like after (ledges, puddles, trees and
 * weeds left in the village, paths, bridges).
 */
public class WorldRunClientGameTest implements FabricClientGameTest {

    /** Seeds, and optionally ":branch" to make the village take that speciality (FARM, FISH, MINE, WOOD). */
    private static final String[] SEEDS = {"4242:FOOD"};
    private static final int DAYS = 100;

    private static void log(String seed, String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[world " + seed + "] " + fmt, args);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        for (String s : SEEDS) {
            String[] p = s.split(":");
            run(context, p[0], p.length > 1 ? BuildingType.Branch.valueOf(p[1]) : null);
        }
    }

    private static int top(MinecraftServer s, int x, int z) {
        return s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private void run(ClientGameTestContext context, String seed, BuildingType.Branch focus) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed(seed)).create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("time set 6000");
            // wait for the world's first villages
            server.waitFor(s -> VillageData.get(s).all().size() >= 1, 20 * 600);
            int[] id = {-1};
            server.runOnServer(s -> {
                BlockPos spawn = s.getPlayerList().getPlayers().getFirst().blockPosition();
                double best = Double.MAX_VALUE;
                for (Village v : VillageData.get(s).all()) {
                    double d = v.center.distSqr(spawn);
                    if (d < best) {
                        best = d;
                        id[0] = v.id;
                    }
                }
                Village v = VillageData.get(s).get(id[0]);
                if (focus != null) org.webtrade.minecraftportsmod.colony.VillageManager.focus(s, v, focus);
                var biome = s.overworld().getBiome(v.center).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
                log(seed, "village #{} {} at {} biome {} focus {} wood {} (villages in the world: {})", v.id, v.name, v.center.toShortString(), biome,
                        v.focus(), v.wood, VillageData.get(s).all().size());
                s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 30,
                        v.center.getZ() + 30.5, java.util.Set.of(), 180, 45, false);
            });
            final int vid = id[0];
            context.waitTicks(60);
            sp.getConnection().waitForChunksRender();
            for (int day = 1; day <= DAYS; day++) {
                server.runCommand("village day");
                context.waitTicks(50);
                final int d = day;
                if (day % 10 == 0) server.runOnServer(s -> report(s, seed, vid, d));
                if (day == 15 || day == 50 || day == 90) watch(context, server, seed, vid, day);
            }
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(vid);
                for (var l : v.log()) log(seed, "log {}: {}", l.day(), l.text().getString());
                land(s, seed, v);
            });
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(vid);
                s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 45,
                        v.center.getZ() + 40.5, java.util.Set.of(), 180, 50, false);
            });
            context.waitTicks(80);
            sp.getConnection().waitForChunksRender();
            context.takeScreenshot("world_" + seed + "_air");
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(vid);
                s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), v.center.getX() + 16.5, v.center.getY() + 8,
                        v.center.getZ() + 16.5, java.util.Set.of(), 135, 20, false);
            });
            context.waitTicks(60);
            context.takeScreenshot("world_" + seed + "_close");
            boolean[] hut = {false};
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(vid);
                for (Building b : v.buildings()) {
                    if (b.type != BuildingType.WOOD_HUT) continue;
                    hut[0] = true;
                    s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), b.origin.getX() + 14.5, b.origin.getY() + 12,
                            b.origin.getZ() + 14.5, java.util.Set.of(), 135, 35, false);
                    break;
                }
            });
            if (hut[0]) {
                context.waitTicks(60);
                sp.getConnection().waitForChunksRender();
                context.takeScreenshot("world_" + seed + "_grove");
            }
        }
    }

    /** Every ten days: the village in numbers. */
    private static void report(MinecraftServer s, String seed, int vid, int day) {
        Village v = VillageData.get(s).get(vid);
        StringBuilder jobs = new StringBuilder();
        for (Job j : Job.values()) if (v.workers(j) > 0) jobs.append(j.id()).append('=').append(v.workers(j)).append(v.toolsShort(j) ? "(NO TOOLS)" : "").append(' ');
        StringBuilder stock = new StringBuilder();
        for (Res r : Res.values()) stock.append(r.id()).append('=').append(v.stock(r)).append('/').append(v.capacity(r)).append(' ');
        StringBuilder built = new StringBuilder();
        for (Building b : v.buildings()) {
            built.append(b.type.id()).append(b.level());
            if (b.state() != Building.State.BUILT) built.append(':').append(b.state().id());
            else if (b.goal() > 0) built.append('^');
            built.append(' ');
        }
        StringBuilder open = new StringBuilder();
        for (BuildingType t : BuildingType.values()) if (t.isNode() && !t.free && v.unlocked(t)) open.append(t.id()).append(' ');
        int children = 0;
        for (Dweller d : v.dwellers()) if (d.job() == null) children++;
        log(seed, "day {} {} people {} (children {}) beds {} mood {} | {}| {}", day, v.level(), v.population(), children, v.beds(), v.mood(), jobs, stock);
        int homes = 0, shops = 0;
        StringBuilder dist = new StringBuilder();
        for (Building b : v.buildings()) {
            if (b.state() == Building.State.DEMOLISHING) continue;
            if (b.type.branch == BuildingType.Branch.HOME && b.type != BuildingType.TENT) {
                homes++;
                dist.append(b.type.id()).append('@').append((int) Math.sqrt(b.origin.distSqr(v.center))).append(' ');
            }
            if (b.type.isWorkshop()) shops++;
            if (b.type == BuildingType.MINE_HOUSE || b.type == BuildingType.WOOD_HUT) {
                dist.append(b.type.id()).append('@').append((int) Math.sqrt(b.origin.distSqr(v.center))).append(' ');
            }
        }
        log(seed, "day {} homes {} workshops {} | built: {}| open: {}", day, homes, shops, built, open);
        log(seed, "day {} homes from the middle: {}", day, dist);
    }

    /**
     * A minute of a working day as it goes: who does what, who stands stuck (the same place, the same thing, for
     * half a minute), how many build at once.
     */
    private static void watch(ClientGameTestContext context, TestServerContext server, String seed, int vid, int day) {
        server.runCommand("time set 7000");
        Map<Integer, String> last = new HashMap<>();
        Map<Integer, Integer> still = new HashMap<>();
        int[] maxBuilders = {0};
        for (int i = 0; i < 12; i++) {
            context.waitTicks(100);
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(vid);
                int builders = 0;
                for (ResidentEntity e : s.overworld().getEntitiesOfClass(ResidentEntity.class, new AABB(v.center).inflate(120), ResidentEntity::colony)) {
                    String act = e.activity().getString();
                    if (act.startsWith("Building") || act.startsWith("Carrying") || act.startsWith("Fetching") || act.startsWith("Demolishing")) builders++;
                    String key = e.blockPosition().toShortString() + " " + act;
                    int n = key.equals(last.get(e.colonyDweller())) ? still.merge(e.colonyDweller(), 1, Integer::sum) : 0;
                    if (n == 0) still.put(e.colonyDweller(), 0);
                    last.put(e.colonyDweller(), key);
                    boolean idleOk = act.startsWith("Tending") || act.startsWith("Building") || act.startsWith("Resting") || act.startsWith("Keeping") || act.startsWith("Sawing") || act.startsWith("Drawing")
                            || act.startsWith("Playing") || act.startsWith("On a break") || act.startsWith("Fishing") || act.startsWith("Gathering") || act.startsWith("Working the mine")
                            || act.contains("stall") || act.startsWith("Works at the anvil");
                    // (out of the players' reach nothing moves: not stuck)
                    if (n == 6 && !idleOk && s.overworld().isPositionEntityTicking(e.blockPosition())) {
                        log(seed, "STUCK day {}: {} ({}) {} at {} for 30 s", day, e.getCustomName().getString(), e.colonyJob() == null ? "child" : e.colonyJob().id(),
                                act, e.blockPosition().toShortString());
                    }
                }
                maxBuilders[0] = Math.max(maxBuilders[0], builders);
            });
        }
        server.runOnServer(s -> {
            Village v = VillageData.get(s).get(vid);
            StringBuilder b = new StringBuilder();
            for (ResidentEntity e : s.overworld().getEntitiesOfClass(ResidentEntity.class, new AABB(v.center).inflate(120), ResidentEntity::colony)) {
                b.append(e.colonyJob() == null ? "child" : e.colonyJob().id()).append(": ").append(e.activity().getString()).append(" | ");
            }
            log(seed, "watch day {}: at most {} building at once :: {}", day, maxBuilders[0], b);
        });
        server.runCommand("time set 6000");
    }

    /** The village's land after it all: ledges round the plots, puddles, trees and weeds on it, the paths and bridges. */
    private static void land(MinecraftServer s, String seed, Village v) {
        int ledges = 0, wayLedges = 0, cols = 0, puddles = 0, logs = 0, weeds = 0, paths = 0, lonely = 0, steep = 0, bridge = 0;
        int reach = 20;
        for (Building b : v.buildings()) reach = Math.max(reach, (int) Math.sqrt(b.origin.distSqr(v.center)) + 12);
        for (int x = -reach; x <= reach; x++) {
            for (int z = -reach; z <= reach; z++) {
                int px = v.center.getX() + x, pz = v.center.getZ() + z;
                boolean mine = false, inPlot = false;
                for (Building b : v.buildings()) {
                    int dx = Math.abs(px - b.origin.getX()), dz = Math.abs(pz - b.origin.getZ());
                    if (dx <= b.type.half + 8 && dz <= b.type.half + 8) mine = true;
                    if (dx <= b.type.half && dz <= b.type.half) inPlot = true;
                }
                if (!mine || inPlot) continue;
                int y = top(s, px, pz);
                BlockPos p = new BlockPos(px, y, pz);
                var st = s.overworld().getBlockState(p);
                var up = s.overworld().getBlockState(p.above());
                cols++;
                if (st.is(BlockTags.LOGS) || up.is(BlockTags.LOGS)) logs++;
                if (up.is(BlockTags.FLOWERS) || up.is(Blocks.SHORT_GRASS) || up.is(Blocks.TALL_GRASS) || up.is(Blocks.FERN)) weeds++;
                if (!st.getFluidState().isEmpty() && s.overworld().getBlockState(p.below()).getFluidState().isEmpty()) {
                    int wet = 0;
                    for (int dx = -3; dx <= 3; dx++) {
                        for (int dz = -3; dz <= 3; dz++) {
                            if (!s.overworld().getBlockState(new BlockPos(px + dx, top(s, px + dx, pz + dz), pz + dz)).getFluidState().isEmpty()) wet++;
                        }
                    }
                    if (wet < 12) puddles++;
                }
                if (st.is(BlockTags.PLANKS)) bridge++;
                if (st.is(Blocks.DIRT_PATH)) {
                    paths++;
                    boolean near = false, tooSteep = false;
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int ny = top(s, px + d[0], pz + d[1]);
                        var ns = s.overworld().getBlockState(new BlockPos(px + d[0], ny, pz + d[1]));
                        if (ns.is(Blocks.DIRT_PATH) || ns.is(BlockTags.PLANKS)) {
                            near = true;
                            if (Math.abs(ny - y) > 1) tooSteep = true;
                        }
                    }
                    if (!near) lonely++;
                    if (tooSteep) steep++;
                }
                if (st.getFluidState().isEmpty()) {
                    for (int[] d : new int[][]{{1, 0}, {0, 1}}) {
                        int ny = top(s, px + d[0], pz + d[1]);
                        if (Math.abs(ny - y) >= 3) {
                            ledges++;
                            if (nearWay(s, v, px, pz)) wayLedges++;
                        }
                    }
                }
            }
        }
        log(seed, "LAND over {} columns of the village: ledges(3+) {} (by ways {}) puddles {} tree trunks {} weeds {} | paths {} (lonely {}, steep {}), plank blocks {}",
                cols, ledges, wayLedges, puddles, logs, weeds, paths, lonely, steep, bridge);
    }

    /** By a plot or a path: where a ledge is in people's way. */
    private static boolean nearWay(MinecraftServer s, Village v, int x, int z) {
        for (Building b : v.buildings()) {
            if (Math.abs(x - b.origin.getX()) <= b.type.half + 3 && Math.abs(z - b.origin.getZ()) <= b.type.half + 3) return true;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (s.overworld().getBlockState(new BlockPos(x + dx, top(s, x + dx, z + dz), z + dz)).is(Blocks.DIRT_PATH)) return true;
            }
        }
        return false;
    }
}
