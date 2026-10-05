package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Caravans;
import org.webtrade.minecraftportsmod.colony.Dweller;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Roadworks;
import org.webtrade.minecraftportsmod.colony.Scouting;
import org.webtrade.minecraftportsmod.colony.Trails;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

import java.util.List;

/**
 * The whole way from a scout to a merchant, in a real world, watched: village A (the world's first) with a
 * cartographer's house of the first level, and a village B set down some 350 blocks off it (no cartographer, no stall,
 * no merchant). A's scout finds B (and B hears of A); the way is worked out; the next morning a crew sets out from
 * each village; the player stands at A's end of the way: the crew is there, axes in hand, the trees in the way come
 * down as they get to them; in the evening they pitch a tent and sleep, the work stands still at night; in the morning
 * the tent is struck and they go on; they meet the other crew; the trail can be walked, and not before; A's merchant
 * walks it to B, which pays for what it buys (and sells to him), and the emeralds go from one to the other, none made.
 */
public class RoadworkClientGameTest implements FabricClientGameTest {

    private static final String SEED = "4242";
    /** A short village day (ticks), so that days pass while we watch. */
    private static final int DAY = 2400;

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[roadwork] " + fmt, args);
    }

    private static int ground(MinecraftServer s, int x, int z) {
        var gen = s.overworld().getChunkSource().getGenerator();
        return gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, s.overworld(), s.overworld().getChunkSource().randomState());
    }

    private static int surface(MinecraftServer s, int x, int z) {
        var gen = s.overworld().getChunkSource().getGenerator();
        return gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, s.overworld(), s.overworld().getChunkSource().randomState());
    }

    private static Roadworks.Work work(VillageData data, int a, int b) {
        for (Roadworks.Work w : data.works()) if (w.a == a && w.b == b || w.a == b && w.b == a) return w;
        return null;
    }

    private static void tp(MinecraftServer s, int x, int z, int up, float pitch) {
        s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), x + 0.5, s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + up,
                z + 0.5, java.util.Set.of(), 0, pitch, false);
    }

    /**
     * Logs standing within the cutting (three blocks either side of the way) from {@code from} to {@code to} blocks
     * along it: each with how far along the way it stands.
     */
    private static java.util.Map<Long, Double> logsAlong(ServerLevel l, int[] p, double from, double to) {
        java.util.Map<Long, Double> seen = new java.util.HashMap<>();
        double run = 0;
        for (int k = 1; 2 * k + 1 < p.length; k++) {
            int x0 = p[2 * k - 2], z0 = p[2 * k - 1], x1 = p[2 * k], z1 = p[2 * k + 1];
            double len = Math.max(1e-6, Math.hypot(x1 - x0, z1 - z0));
            for (double t = 0; t <= len; t += 1) {
                double along = run + t;
                if (along < from || along > to) continue;
                int cx = (int) Math.round(x0 + (x1 - x0) * t / len), cz = (int) Math.round(z0 + (z1 - z0) * t / len);
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        int x = cx + dx, z = cz + dz;
                        if (!l.hasChunkAt(new BlockPos(x, 0, z))) continue;
                        int top = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                        for (int y = top - 14; y <= top + 1; y++) {
                            BlockPos q = new BlockPos(x, y, z);
                            if (l.getBlockState(q).is(BlockTags.LOGS)) seen.putIfAbsent(q.asLong(), along);
                        }
                    }
                }
            }
            run += len;
        }
        return seen;
    }

    /** The point index of a way about {@code blocks} along it. */
    private static int index(int[] p, double blocks) {
        double run = 0;
        for (int k = 1; 2 * k + 1 < p.length; k++) {
            run += Math.hypot(p[2 * k] - p[2 * k - 2], p[2 * k + 1] - p[2 * k - 1]);
            if (run >= blocks) return k;
        }
        return p.length / 2 - 1;
    }

    /** A tree standing on the way: a trunk five high and a crown (so that there is something to fell for sure). */
    private static void plantTree(ServerLevel l, int x, int z) {
        int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int k = 0; k < 5; k++) l.setBlockAndUpdate(new BlockPos(x, y + k, z), Blocks.OAK_LOG.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int k = 3; k <= 5; k++) {
                    BlockPos q = new BlockPos(x + dx, y + k, z + dz);
                    if (l.getBlockState(q).isAir()) l.setBlockAndUpdate(q, Blocks.OAK_LEAVES.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
                }
            }
        }
    }

    private static List<ResidentEntity> crewBodies(MinecraftServer s, int village) {
        var p = s.getPlayerList().getPlayers().getFirst();
        return s.overworld().getEntitiesOfClass(ResidentEntity.class, p.getBoundingBox().inflate(140), e -> {
            if (!e.colony() || e.colonyVillage() != village) return false;
            String act = e.activity().getString().toLowerCase(java.util.Locale.ROOT);
            return act.contains("trail") || act.contains("bed") || act.contains("asleep") || act.contains("camping");
        });
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
            // (a village on the mainland: an island's has no way by land to anywhere)
            server.waitFor(s -> VillageData.get(s).all().stream().anyMatch(v -> !v.island()), 20 * 600);
            int[] a = {-1}, spot = {0, 0};
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                Village best = null;
                for (Village v : VillageData.get(s).all()) {
                    if (v.island()) continue;
                    if (best == null || v.center.distSqr(p.blockPosition()) < best.center.distSqr(p.blockPosition())) best = v;
                }
                a[0] = best.id;
                // B: on dry, fairly level land some 350 blocks away, woods on the way there if there are any
                int bx = best.center.getX(), bz = best.center.getZ();
                double bestScore = Double.MAX_VALUE;
                var gen = s.overworld().getChunkSource().getGenerator();
                var rs = s.overworld().getChunkSource().randomState();
                for (int k = 0; k < 16; k++) {
                    double ang = Math.toRadians(k * 22.5);
                    int x = bx + (int) Math.round(Math.cos(ang) * 350), z = bz + (int) Math.round(Math.sin(ang) * 350);
                    int g = ground(s, x, z), sf = surface(s, x, z);
                    if (sf > g) continue;
                    int rough = 0;
                    for (int[] d : new int[][]{{8, 0}, {-8, 0}, {0, 8}, {0, -8}}) rough = Math.max(rough, Math.abs(ground(s, x + d[0], z + d[1]) - g));
                    int woods = 0;
                    for (int t = 60; t < 300; t += 20) {
                        int wx = bx + (int) Math.round(Math.cos(ang) * t), wz = bz + (int) Math.round(Math.sin(ang) * t);
                        var b = gen.getBiomeSource().getNoiseBiome(wx >> 2, 16, wz >> 2, rs.sampler());
                        if (b.is(BiomeTags.IS_FOREST) || b.is(BiomeTags.IS_TAIGA)) woods++;
                    }
                    double score = rough + Math.abs(g - s.overworld().getSeaLevel()) * 0.2 - woods * 0.6;
                    if (score < bestScore) {
                        bestScore = score;
                        spot[0] = x;
                        spot[1] = z;
                    }
                }
                log("A #{} {} at {}; B's site {} {} (score {})", best.id, best.name, best.center.toShortString(), spot[0], spot[1], bestScore);
                p.teleportTo(s.overworld(), spot[0] + 0.5, ground(s, spot[0], spot[1]) + 2, spot[1] + 0.5, java.util.Set.of(), 0, 0, false);
            });
            context.waitTicks(60);
            sp.getConnection().waitForChunksRender();
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(40);
            int[] b = {-1};
            server.runOnServer(s -> {
                for (Village v : VillageData.get(s).all()) if (v.id != a[0] && (b[0] < 0 || v.id > b[0])) b[0] = v.id;
                log("B #{}", b[0]);
            });
            if (b[0] < 0) throw new AssertionError("no second village");
            // a village day of two minutes; A: a cartographer's house (level 1) and its scout, a stall and a merchant,
            // woodcutters; B: only people (no cartographer, no stall, no merchant)
            server.runCommand("village daylength " + DAY);
            // (A's buildings are put up where its land is loaded: the player goes there first)
            server.runOnServer(s -> {
                Village va = VillageData.get(s).get(a[0]);
                tp(s, va.center.getX(), va.center.getZ(), 20, 60);
            });
            context.waitTicks(80);
            sp.getConnection().waitForChunksRender();
            server.runOnServer(s -> {
                var cmd = s.getCommands();
                var src = s.createCommandSourceStack();
                for (String c : new String[]{"build %d cartographer 1", "build %d market 1", "build %d storehouse 3", "grow %d scout", "grow %d merchant", "grow %d woodcutter",
                        "grow %d woodcutter", "grow %d woodcutter"}) cmd.performPrefixedCommand(src, "village " + String.format(c, a[0]));
                for (String c : new String[]{"grow %d woodcutter", "grow %d woodcutter", "grow %d fisher"}) {
                    cmd.performPrefixedCommand(src, "village " + String.format(c, b[0]));
                }
                VillageData data = VillageData.get(s);
                Village va = data.get(a[0]), vb = data.get(b[0]);
                log("A: {} people, cartographer level {}; B: {} people, cartographer level {}, stall {}", va.population(), Scouting.houseLevel(va),
                        vb.population(), Scouting.houseLevel(vb), vb.has(BuildingType.MARKET));
                if (Scouting.houseLevel(va) != 1 || !va.has(BuildingType.MARKET)) throw new AssertionError("A's cartographer's house or stall was not put up");
            });

            // 1. A's scout (level-1 house) finds B; B learns of A
            long[] found = {-1};
            for (int day = 1; day <= 14 && found[0] < 0; day++) {
                server.runCommand("village day");
                context.waitTicks(40);
                server.runOnServer(s -> {
                    VillageData data = VillageData.get(s);
                    Village va = data.get(a[0]);
                    if (va.knownIds().contains(b[0])) found[0] = data.day();
                });
            }
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                Village va = data.get(a[0]), vb = data.get(b[0]);
                for (var l : va.log()) if (l.text().getString().contains("cout")) log("A log day {}: {}", l.day(), l.text().getString());
                log("found on day {}; A's house level {}; B knows A: {}", found[0], Scouting.houseLevel(va), vb.knownIds().contains(a[0]));
                if (found[0] < 0) throw new AssertionError("A's level-1 scout did not find B, 350 blocks off, in 14 days");
                if (Scouting.houseLevel(va) != 1) throw new AssertionError("the house was not of the first level");
                if (!vb.knownIds().contains(a[0])) throw new AssertionError("B did not learn of A when A's scout found it");
            });

            // 2. the way is worked out (a few seconds, off the server thread): the work planned that day
            server.waitFor(s -> work(VillageData.get(s), a[0], b[0]) != null || Trails.find(VillageData.get(s), a[0], b[0]) != null
                    && Trails.find(VillageData.get(s), a[0], b[0]).none(), 20 * 300);
            int[][] pts = {null};
            long[] planned = {-1};
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                Roadworks.Work w = work(data, a[0], b[0]);
                if (w == null) throw new AssertionError("no way by land between A and B (choose another site)");
                pts[0] = w.a == a[0] ? w.points() : reversed(w.points());
                planned[0] = w.planned;
                log("way worked out on day {} (found day {}): {} blocks, {} points; walkable A->B now: {}", w.planned, found[0], (int) w.length(),
                        pts[0].length / 2, Trails.route(data, a[0], b[0]) != null);
                if (Trails.route(data, a[0], b[0]) != null) throw new AssertionError("the trail can be walked before anybody made it");
                if (w.started() >= 0) throw new AssertionError("the crews set out the same day the way was found");
            });
            int[] p = pts[0];
            // A's end: the stretch from 50 to 150 blocks out (past the village's own ground, where nothing is felled)
            int k0 = index(p, 50), k1 = index(p, 150);
            int[] mid = {p[2 * ((k0 + k1) / 2)], p[2 * ((k0 + k1) / 2) + 1]};
            server.runOnServer(s -> tp(s, mid[0], mid[1], 30, 60));
            context.waitTicks(80);
            sp.getConnection().waitForChunksRender();
            java.util.Map<Long, Double> logsBefore = new java.util.HashMap<>();
            int[] pit = {0, 0, 0};
            int snapsBefore = Roadworks.snaps().size();
            server.runOnServer(s -> {
                ServerLevel l = s.overworld();
                // (trees planted on the way, to be sure there is some felling to see)
                for (double at : new double[]{60, 75, 95, 120}) {
                    int k = index(p, at);
                    plantTree(l, p[2 * k], p[2 * k + 1]);
                }
                logsBefore.putAll(logsAlong(l, p, 50, 150));
                log("logs in the cutting 50..150 blocks out of A before the work: {}", logsBefore.size());
                // a pit across the way (a quarry, a ravine): eight deep, ten along it, nine across; to be bridged
                int ka = index(p, 100), kb = index(p, 110);
                int edge = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p[2 * ka], p[2 * ka + 1]) - 1;
                pit[0] = ka;
                pit[1] = kb;
                pit[2] = edge;
                for (int k = ka; k <= kb; k++) {
                    for (int dx = -4; dx <= 4; dx++) {
                        for (int dz = -4; dz <= 4; dz++) {
                            int x = p[2 * k] + dx, z = p[2 * k + 1] + dz;
                            int top = l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                            for (int y = edge - 8; y <= top + 2; y++) l.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
                log("a pit dug across the way from point {} to {} (100..110 blocks out), eight deep under the edge at y {}", ka, kb, edge);
            });

            // 3. the next morning: a crew from each village sets out
            server.runOnServer(s -> VillageData.get(s).setDayTicks(DAY - 30));
            context.waitTicks(60);
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                Roadworks.Work w = work(data, a[0], b[0]);
                log("day {}: started {} | A crew {} state {} | B crew {} state {}", data.day(), w.started(), w.side(a[0]).crew(), w.side(a[0]).state(),
                        w.side(b[0]).crew(), w.side(b[0]).state());
                if (w.started() != w.planned + 1) throw new AssertionError("the crews set out on day " + w.started() + ", not the day after the way was found");
                if (w.side(a[0]).crew().isEmpty() || w.side(b[0]).crew().isEmpty()) throw new AssertionError("not both villages sent a crew");
                for (int side : new int[]{a[0], b[0]}) {
                    for (int id : w.side(side).crew()) {
                        Dweller d = data.get(side).dweller(id);
                        if (d == null || !d.away()) throw new AssertionError("a crew member is still at home");
                    }
                }
            });

            // 4. the work, watched: A's people at the end of the way, felling
            boolean[] seen = {false};
            double[] madeA = {0};
            int mornings = 0;
            for (int step = 0; step < 100; step++) {
                server.runOnServer(s -> {
                    VillageData data = VillageData.get(s);
                    Roadworks.Work w = work(data, a[0], b[0]);
                    if (w == null) return;
                    int[] f = w.at(w.front(w.side(a[0])));
                    // (the player keeps by A's end of the way, a little off it)
                    var pl = s.getPlayerList().getPlayers().getFirst();
                    if (Math.hypot(pl.getX() - f[0], pl.getZ() - f[1]) > 30) tp(s, f[0] + 6, f[1] + 6, 12, 50);
                });
                context.waitTicks(40);
                final int st = step;
                server.runOnServer(s -> {
                    VillageData data = VillageData.get(s);
                    Roadworks.Work w = work(data, a[0], b[0]);
                    if (w == null) return;
                    var bodies = crewBodies(s, a[0]);
                    madeA[0] = w.side(a[0]).done();
                    for (var e : bodies) {
                        String held = e.getMainHandItem().getItem().toString(), act = e.activity().getString();
                        // (a shovel to dig, an axe for a tree in the way, planks on a bridge)
                        if ((held.contains("axe") || held.contains("shovel") || held.contains("planks"))
                                && (act.startsWith("Making") || act.startsWith("Building a bridge"))) seen[0] = true;
                    }
                    if (st % 5 == 0) {
                        log("{} s (village time {}): A made {} blocks, B {} | bodies of A's crew: {}", st * 2, data.dayTicks(), (int) w.side(a[0]).done(),
                                (int) w.side(b[0]).done(), bodies.stream().map(e -> e.getName().getString() + " " + e.activity().getString() + " holding "
                                        + e.getMainHandItem().getItem() + " at " + e.blockPosition().toShortString()).toList());
                    }
                });
                // (till the evening: a day's work; if they are not past the pit yet, the next day too)
                boolean[] evening = {false};
                server.runOnServer(s -> evening[0] = !Roadworks.working(VillageData.get(s)) && VillageData.get(s).dayTicks() > DAY / 4);
                if (evening[0]) {
                    if (madeA[0] >= 125 || mornings >= 1) break;
                    mornings++;
                    server.runOnServer(s -> VillageData.get(s).setDayTicks(DAY - 20));
                }
            }
            context.takeScreenshot("roadwork_a_felling");
            server.runOnServer(s -> {
                ServerLevel l = s.overworld();
                // the logs that stood on what the crew made (past A's own ground), and how many of them still stand
                int onMade = 0, standing = 0;
                for (var e : logsBefore.entrySet()) {
                    if (e.getValue() > madeA[0] - 6) continue;
                    onMade++;
                    BlockPos q = BlockPos.of(e.getKey());
                    if (!l.getBlockState(q).is(BlockTags.LOGS)) continue;
                    // (a pile of a bridge: a log under the deck, not a tree)
                    boolean pile = false;
                    for (int k = 1; k <= 12 && !pile; k++) pile = l.getBlockState(q.above(k)).is(BlockTags.PLANKS);
                    if (pile) {
                        log("bridge pile at {}", q.toShortString());
                        continue;
                    }
                    // (on the plot of a building put up there since: its timber, not a tree)
                    boolean onPlot = false;
                    for (Village vv : VillageData.get(s).all()) {
                        for (Building bb : vv.buildings()) {
                            if (Math.abs(q.getX() - bb.origin.getX()) <= bb.type.half + 1 && Math.abs(q.getZ() - bb.origin.getZ()) <= bb.type.half + 1) onPlot = true;
                        }
                    }
                    if (onPlot) {
                        log("timber of a building at {}", q.toShortString());
                        continue;
                    }
                    // (a post of a house the village has put up there since: not a tree)
                    BlockPos foot = q;
                    while (l.getBlockState(foot.below()).is(BlockTags.LOGS)) foot = foot.below();
                    var under = l.getBlockState(foot.below());
                    if (under.is(BlockTags.PLANKS) || under.is(Blocks.COBBLESTONE) || under.is(BlockTags.STAIRS) || under.is(BlockTags.SLABS)) {
                        log("a house post at {}", q.toShortString());
                        continue;
                    }
                    double off = 1e9;
                    for (int i = 0; i + 1 < p.length; i += 2) off = Math.min(off, Math.hypot(p[i] - q.getX(), p[i + 1] - q.getZ()));
                    // (a tree beside the cutting, not in it: it stays)
                    if (off > 3.6) {
                        log("tree beside the way at {} ({} from it)", q.toShortString(), (int) Math.round(off * 10) / 10.0);
                        continue;
                    }
                    StringBuilder col = new StringBuilder();
                    for (int k = -3; k <= 10; k++) col.append(l.getBlockState(q.above(k)).getBlock().getName().getString()).append(k == 0 ? "* " : " ");
                    double near = 1e9;
                    for (int i = 0; i + 1 < p.length; i += 2) near = Math.min(near, Math.hypot(p[i] - q.getX(), p[i + 1] - q.getZ()));
                    log("log still standing at {} ({} blocks out, {} from the way): {}", q.toShortString(), e.getValue(), (int) Math.round(near * 10) / 10.0, col);
                    standing++;
                }
                int after = standing;
                VillageData data = VillageData.get(s);
                Roadworks.Work w = work(data, a[0], b[0]);
                Trails.Trail t = null;
                int builtSegs = 0, laidSegs = 0;
                for (var x : data.trails()) {
                    if (x.a == a[0] || x.b == a[0]) {
                        t = x;
                        builtSegs += x.builtCount();
                        laidSegs += x.laidCount();
                    }
                }
                log("A made {} blocks, watched; logs on what it made (50 blocks out and on): {} before, {} standing now; stretches made {}, laid {}",
                        (int) madeA[0], onMade, after, builtSegs, laidSegs);
                if (!seen[0]) throw new AssertionError("A's crew was never seen at work at the end of the way");
                if (madeA[0] < 80) throw new AssertionError("A's crew made only " + (int) madeA[0] + " blocks while watched");
                if (onMade == 0) throw new AssertionError("no trees stood on the stretch made (the planted ones should)");
                if (after > 0) throw new AssertionError("trees in the way were not felled: " + after + " of " + onMade + " logs still stand");
                // the pit: bridged at the edges' height, the crew over it, and nobody had to be lifted out of it
                int decked = 0, points = 0;
                StringBuilder tops = new StringBuilder();
                for (int k = pit[0] + 1; k < pit[1]; k++) {
                    int x = p[2 * k], z = p[2 * k + 1];
                    int top = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    var st = l.getBlockState(new BlockPos(x, top, z));
                    points++;
                    if (st.is(BlockTags.PLANKS) && Math.abs(top - pit[2]) <= 2) decked++;
                    tops.append(top).append(st.is(BlockTags.PLANKS) ? "P " : "=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath() + " ");
                    StringBuilder col = new StringBuilder();
                    for (int y = pit[2] - 9; y <= pit[2] + 3; y++) {
                        var cs = l.getBlockState(new BlockPos(x, y, z));
                        col.append(cs.isAir() ? "." : cs.is(BlockTags.PLANKS) ? "P" : cs.is(BlockTags.FENCES) ? "F" : cs.is(BlockTags.LOGS) ? "L" : !cs.getFluidState().isEmpty() ? "~" : "#");
                    }
                    StringBuilder ring = new StringBuilder();
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dx = -3; dx <= 3; dx++) {
                            int ty = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz) - 1;
                            var ts = l.getBlockState(new BlockPos(x + dx, ty, z + dz));
                            ring.append(ts.is(BlockTags.PLANKS) ? "P" : ts.is(BlockTags.FENCES) ? "F" : ty < pit[2] - 2 ? "_" : "#");
                        }
                        ring.append('/');
                    }
                    log("pit point {} at {} {}: column {} (y {}..) around {}", k, x, z, col, pit[2] - 9, ring);
                }
                // (stuck anywhere else: logged; at the pit, no: the pit is bridged, not got round by being lifted over it)
                var stuck = Roadworks.snaps().subList(snapsBefore, Roadworks.snaps().size());
                int atPit = 0;
                for (BlockPos q : stuck) {
                    for (int k = pit[0] - 3; k <= pit[1] + 3; k++) {
                        if (k >= 0 && 2 * k + 1 < p.length && Math.hypot(q.getX() - p[2 * k], q.getZ() - p[2 * k + 1]) < 10) {
                            atPit++;
                            break;
                        }
                    }
                }
                log("pit {}..{} (edge y {}): {} of {} points on a deck; tops {}| crew stuck and put at work {} times ({} at the pit): {}", pit[0], pit[1], pit[2],
                        decked, points, tops, stuck.size(), atPit, stuck);
                if (madeA[0] < 115) throw new AssertionError("A's crew did not get over the pit (made " + (int) madeA[0] + ")");
                if (decked < points - 1) throw new AssertionError("the pit was not bridged: " + decked + " of " + points + " points decked");
                if (atPit > 0) throw new AssertionError("crew members got stuck at the pit and had to be lifted over it " + atPit + " times");
                if (stuck.size() > 2) throw new AssertionError("crew members got stuck " + stuck.size() + " times on the way to their work");
            });
            // the bridge over the pit, from above: its deck and its rails (one unbroken line along each side)
            server.runOnServer(s -> {
                int pm = (pit[0] + pit[1]) / 2, x = p[2 * pm], z = p[2 * pm + 1];
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), String.format(java.util.Locale.ROOT,
                        "tp @a %d %d %d facing %d %d %d", x + 9, pit[2] + 12, z + 9, x, pit[2], z));
            });
            context.waitTicks(40);
            context.takeScreenshot("roadwork_d_pit_bridge");

            // 5. the evening: they stop, pitch a tent by the way, sleep; nothing is made at night (unless the crews
            // have met already, the way being short: then there is no night out to see)
            boolean[] going = {false};
            server.runOnServer(s -> going[0] = work(VillageData.get(s), a[0], b[0]) != null && work(VillageData.get(s), a[0], b[0]).finished() < 0);
            if (!going[0]) log("the crews met before the evening: no night by the way to see");
            if (going[0]) {
                server.runOnServer(s -> VillageData.get(s).setDayTicks((int) (DAY * 0.6)));
                context.waitTicks(100);
                double[] atDusk = {0};
                int[][] tent = {null};
                server.runOnServer(s -> {
                    VillageData data = VillageData.get(s);
                    Roadworks.Work w = work(data, a[0], b[0]);
                    atDusk[0] = w.side(a[0]).done() + w.side(b[0]).done();
                    tent[0] = w.side(a[0]).tent();
                    log("dusk: A camp {} tent {}", java.util.Arrays.toString(w.side(a[0]).camp()), java.util.Arrays.toString(tent[0]));
                    if (w.side(a[0]).camp() == null) throw new AssertionError("A's crew did not camp at dusk");
                    if (tent[0] == null) throw new AssertionError("no tent was pitched by A's crew (its land loaded, the player by it)");
                });
                context.waitTicks(20 * 15);
                // (they walk to the tent from where the day's work left them: a little longer, the night is short here)
                for (int k = 0; k < 3; k++) {
                    boolean[] sleeping = {false};
                    server.runOnServer(s -> sleeping[0] = crewBodies(s, a[0]).stream().anyMatch(net.minecraft.world.entity.LivingEntity::isSleeping));
                    if (sleeping[0]) break;
                    server.runOnServer(s -> {
                        int[] t = tent[0];
                        log("waiting for the crew to go to sleep: {}", crewBodies(s, a[0]).stream().map(e -> e.blockPosition().toShortString() + " ("
                                + (int) Math.hypot(e.getX() - t[0], e.getZ() - t[2]) + " from the tent) " + e.activity().getString()).toList());
                    });
                    context.waitTicks(20 * 5);
                }
                server.runOnServer(s -> {
                    VillageData data = VillageData.get(s);
                    Roadworks.Work w = work(data, a[0], b[0]);
                    double now = w.side(a[0]).done() + w.side(b[0]).done();
                    ServerLevel l = s.overworld();
                    int beds = 0;
                    BlockPos o = new BlockPos(tent[0][0], tent[0][1], tent[0][2]);
                    for (BlockPos q : BlockPos.betweenClosed(o.offset(-3, -1, -3), o.offset(3, 3, 3))) if (l.getBlockState(q).is(BlockTags.BEDS)) beds++;
                    var bodies = crewBodies(s, a[0]);
                    long asleep = bodies.stream().filter(net.minecraft.world.entity.LivingEntity::isSleeping).count();
                    log("night: made {} at dusk, {} now; bed blocks in the tent {}; A's crew {} ({} asleep): {}", (int) atDusk[0], (int) now, beds, bodies.size(), asleep,
                            bodies.stream().map(e -> e.activity().getString()).toList());
                    if (now > atDusk[0] + 0.01) throw new AssertionError("the way went on being made at night");
                    if (beds == 0) throw new AssertionError("the tent has no beds in the world");
                    if (asleep == 0) throw new AssertionError("A's crew is not asleep in the tent");
                });
                context.takeScreenshot("roadwork_b_camp");
                // the morning: the tent struck
                server.runOnServer(s -> VillageData.get(s).setDayTicks(DAY - 20));
                context.waitTicks(20 * 8);
                server.runOnServer(s -> {
                    VillageData data = VillageData.get(s);
                    Roadworks.Work w = work(data, a[0], b[0]);
                    ServerLevel l = s.overworld();
                    BlockPos o = new BlockPos(tent[0][0], tent[0][1], tent[0][2]);
                    int wool = 0;
                    for (BlockPos q : BlockPos.betweenClosed(o.offset(-3, -1, -3), o.offset(3, 3, 3))) {
                        if (l.getBlockState(q).is(BlockTags.WOOL) || l.getBlockState(q).is(BlockTags.BEDS)) wool++;
                    }
                    log("morning (day {}): A tent {}, wool and beds left where it stood: {}", data.day(), java.util.Arrays.toString(w.side(a[0]).tent()), wool);
                    if (w.side(a[0]).tent() != null || wool > 0) throw new AssertionError("the tent was not struck in the morning");
                });

}
            // 6. the crews meet (days go by); the trail can be walked; they go home
            for (int day = 0; day < 12; day++) {
                boolean[] done = {false};
                server.runOnServer(s -> {
                    Roadworks.Work w = work(VillageData.get(s), a[0], b[0]);
                    done[0] = w == null || w.finished() >= 0 && w.sideA().state() == Roadworks.HOME && w.sideB().state() == Roadworks.HOME;
                });
                if (done[0]) break;
                server.runCommand("village day");
                context.waitTicks(20);
                server.runOnServer(s -> {
                    VillageData data = VillageData.get(s);
                    Roadworks.Work w = work(data, a[0], b[0]);
                    if (w != null) log("day {}: A made {}, B made {} of {}; finished {}; states {} / {}", data.day(), (int) w.sideA().done(), (int) w.sideB().done(),
                            (int) w.length(), w.finished(), w.sideA().state(), w.sideB().state());
                });
            }
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                Roadworks.Work w = work(data, a[0], b[0]);
                int[] r = Trails.route(data, a[0], b[0]);
                int away = 0;
                StringBuilder who = new StringBuilder();
                for (int id : new int[]{a[0], b[0]}) for (Dweller d : data.get(id).dwellers()) if (d.away() && d.job() != Job.SCOUT && d.job() != Job.MERCHANT) {
                    away++;
                    who.append('#').append(id).append(' ').append(d.name).append(' ').append(d.job()).append("; ");
                }
                for (Roadworks.Work o : data.works()) who.append("work states ").append(o.sideA().state()).append('/').append(o.sideB().state()).append("; ");
                log("away: {}", who);
                log("after: work {}; walkable A->B {}; crew still away {}", w == null ? "done with" : "still on", r != null, away);
                for (int id : new int[]{a[0], b[0]}) {
                    for (var l : data.get(id).log()) {
                        String txt = l.text().getString();
                        if (txt.contains("trail") || txt.contains("way") || txt.contains("crew")) log("log #{} day {}: {}", id, l.day(), txt);
                    }
                }
                if (r == null) throw new AssertionError("the trail cannot be walked after the crews met");
                if (away > 0) throw new AssertionError("the crews did not come home");
            });

            // 7. trade: A has food to spare, B is short of it (B: no stall, no merchant); A's merchant walks the trail
            long[] total = {0}, bBefore = {0};
            server.runOnServer(s -> {
                var cmd = s.getCommands();
                var src = s.createCommandSourceStack();
                cmd.performPrefixedCommand(src, "village give " + a[0] + " food 3000");
                cmd.performPrefixedCommand(src, "village give " + b[0] + " food -2000");
                VillageData data = VillageData.get(s);
                Village va = data.get(a[0]), vb = data.get(b[0]);
                boolean bMerchant = vb.dwellers().stream().anyMatch(d -> d.job() == Job.MERCHANT);
                total[0] = va.emeralds() + vb.emeralds();
                bBefore[0] = vb.emeralds();
                log("before the trip: A purse {} food {} | B purse {} food {} | B merchant {} stall {} cartographer {} | {}", va.emeralds(), va.stock(Res.FOOD),
                        vb.emeralds(), vb.stock(Res.FOOD), bMerchant, vb.has(BuildingType.MARKET), Scouting.houseLevel(vb), Caravans.explain(data, a[0], b[0]));
                if (bMerchant || vb.has(BuildingType.MARKET)) throw new AssertionError("B was to have no merchant and no stall");
                // the player a quarter of the way along, by the trail
                double len = 0;
                for (int i = 2; i + 1 < p.length; i += 2) len += Math.hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]);
                int q = index(p, 0.25 * len);
                tp(s, p[2 * q] + 5, p[2 * q + 1] + 5, 6, 30);
            });
            context.waitTicks(40);
            server.runOnServer(s -> VillageData.get(s).setDayTicks(DAY - 20));
            boolean[] onTrail = {false};
            for (int k = 0; k < 60 && !onTrail[0]; k++) {
                context.waitTicks(20);
                final int kk = k;
                server.runOnServer(s -> {
                    VillageData data = VillageData.get(s);
                    for (var e : s.overworld().getEntitiesOfClass(ResidentEntity.class, s.getPlayerList().getPlayers().getFirst().getBoundingBox().inflate(90),
                            e -> e.colony() && e.colonyVillage() == a[0] && e.colonyJob() == Job.MERCHANT)) {
                        double near = Double.MAX_VALUE;
                        for (int i = 0; i + 1 < p.length; i += 2) near = Math.min(near, Math.hypot(e.getX() - p[i], e.getZ() - p[i + 1]));
                        if (kk % 5 == 0) log("merchant {} at {} ({} blocks off the trail): {}", e.getName().getString(), e.blockPosition().toShortString(), (int) near,
                                e.activity().getString());
                        // (out on the trail: away from his village, where the trail starts by his stall)
                        var home = data.get(a[0]);
                        if (near < 8 && home != null && Math.hypot(e.getX() - home.center.getX(), e.getZ() - home.center.getZ()) > 40) onTrail[0] = true;
                    }
                    if (kk % 10 == 0) for (var t : data.trips()) log("trip {}->{} {} {} at {} purse {}", t.from, t.to(), t.amount(), t.res() == null ? "-" : t.res().id(),
                            (int) t.at(), t.purse());
                });
            }
            context.takeScreenshot("roadwork_c_merchant");
            if (!onTrail[0]) throw new AssertionError("A's merchant was not seen walking the trail");
            // his mules: one or two, pack bags on, on his lead; and he is one: a copy of him (as saved with the world
            // and loaded again) put beside him is gone at once
            server.runOnServer(s -> {
                var pl = s.getPlayerList().getPlayers().getFirst();
                var merchants = s.overworld().getEntitiesOfClass(ResidentEntity.class, pl.getBoundingBox().inflate(120),
                        e -> e.colony() && e.colonyVillage() == a[0] && e.colonyJob() == Job.MERCHANT);
                if (merchants.isEmpty()) throw new AssertionError("the merchant is not in the world by the player");
                var m = merchants.getFirst();
                var mules = s.overworld().getEntitiesOfClass(net.minecraft.world.entity.animal.equine.Mule.class, m.getBoundingBox().inflate(14),
                        x -> x.entityTags().contains("minecraftportsmod_caravan"));
                long led = mules.stream().filter(x -> x.getLeashHolder() == m && x.hasChest()).count();
                log("merchant {} ({} of him about): {} mules by him, {} on his lead with pack bags", m.getName().getString(), merchants.size(), mules.size(), led);
                if (merchants.size() != 1) throw new AssertionError(merchants.size() + " of the merchant in the world");
                if (led == 0) throw new AssertionError("the merchant walks without his mules");
                var copy = org.webtrade.minecraftportsmod.registry.ModContent.RESIDENT.create(s.overworld(), net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
                VillageData data = VillageData.get(s);
                Village va = data.get(a[0]);
                copy.snapTo(m.getX() + 1, m.getY(), m.getZ(), 0, 0);
                copy.syncColony(va.dweller(m.colonyDweller()), va, data.day());
                s.overworld().addFreshEntity(copy);
            });
            context.waitTicks(60);
            server.runOnServer(s -> {
                var pl = s.getPlayerList().getPlayers().getFirst();
                var merchants = s.overworld().getEntitiesOfClass(ResidentEntity.class, pl.getBoundingBox().inflate(120),
                        e -> e.colony() && e.colonyVillage() == a[0] && e.colonyJob() == Job.MERCHANT);
                log("a copy of the merchant put beside him: {} of him now", merchants.size());
                if (merchants.size() != 1) throw new AssertionError("the copy of the merchant stayed: " + merchants.size() + " of him");
            });
            // (the player goes away: the merchant walks on unseen, a day's walk each day that passes)
            server.runOnServer(s -> {
                Village va = VillageData.get(s).get(a[0]);
                s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), va.center.getX() - 600.5, 150, va.center.getZ() - 600.5, java.util.Set.of(), 0, 0, false);
            });
            context.waitTicks(60);
            for (int day = 0; day < 4; day++) {
                server.runCommand("village day");
                context.waitTicks(20);
            }
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                Village va = data.get(a[0]), vb = data.get(b[0]);
                long purses = 0;
                for (var t : data.trips()) purses += t.purse();
                int sales = 0;
                for (var l : vb.log()) if (l.text().getString().contains("sold us")) sales++;
                log("after the trip: A purse {} food {} | B purse {} food {} | purses on the road {}, trips {} | sales at B {}", va.emeralds(), va.stock(Res.FOOD),
                        vb.emeralds(), vb.stock(Res.FOOD), purses, data.trips().size(), sales);
                for (var l : vb.log()) if (l.text().getString().contains("merchant")) log("B log day {}: {}", l.day(), l.text().getString());
                if (sales == 0) throw new AssertionError("A's merchant sold nothing at B");
                // every emerald of every village and every merchant on the road: what came in (new villages' purses,
                // levels, veins) less what was lost; the player traded nothing
                long all = purses;
                for (Village v : data.all()) all += v.emeralds();
                log("emeralds in the world {} = minted {} - lost {}? A+B before {} after {}", all, data.minted(), data.burnt(), total[0],
                        va.emeralds() + vb.emeralds() + purses);
                if (all != data.minted() - data.burnt()) throw new AssertionError("emeralds made or lost out of nothing: " + all + " vs " + (data.minted() - data.burnt()));
                // B paid A's merchant: the price in B's log ("... for N emeralds")
                int paid = 0;
                for (var l : vb.log()) {
                    var m = java.util.regex.Pattern.compile("sold us .* for ([0-9]+) emeralds").matcher(l.text().getString());
                    if (m.find()) paid += Integer.parseInt(m.group(1));
                }
                log("B paid {} emeralds for what it bought", paid);
                if (paid <= 0) throw new AssertionError("no emeralds went from B to A for what B bought");
            });
        }
    }

    private static int[] reversed(int[] p) {
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i += 2) {
            r[p.length - 2 - i] = p[i];
            r[p.length - 1 - i] = p[i + 1];
        }
        return r;
    }
}
