package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Caravans;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Trails;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;

/**
 * Trails and caravans in a real world: a second village is set down on dry land some 350 blocks from the world's
 * first; the trail between them is worked out (round hills, not along steep slopes, bridges over rivers), then laid
 * as the player flies along it; then a village with food to spare sends its merchant to one short of it.
 */
public class TrailClientGameTest implements FabricClientGameTest {

    private static final String SEED = "4242";

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[trail] " + fmt, args);
    }

    private static int ground(MinecraftServer s, int x, int z) {
        var gen = s.overworld().getChunkSource().getGenerator();
        return gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, s.overworld(), s.overworld().getChunkSource().randomState());
    }

    private static int surface(MinecraftServer s, int x, int z) {
        var gen = s.overworld().getChunkSource().getGenerator();
        return gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, s.overworld(), s.overworld().getChunkSource().randomState());
    }

    private static String trips(VillageData data, int a, int b) {
        StringBuilder t = new StringBuilder();
        for (Caravans.Trip x : data.trips()) t.append(x.from).append("->").append(x.to()).append(x.back() ? " back " : " ").append(x.amount()).append(' ')
                .append(x.res() == null ? "-" : x.res().id()).append(" at ").append((int) x.at()).append("; ");
        return "A food " + data.get(a).stock(Res.FOOD) + " purse " + data.get(a).emeralds() + " | B food " + data.get(b).stock(Res.FOOD) + " purse "
                + data.get(b).emeralds() + " | trips: " + t;
    }

    private static boolean onRoad(TestServerContext server, int a) {
        boolean[] r = {false};
        server.runOnServer(s -> {
            for (Caravans.Trip t : VillageData.get(s).trips()) if (t.from == a) r[0] = true;
        });
        return r[0];
    }

    private static org.webtrade.minecraftportsmod.village.ResidentEntity merchantBody(MinecraftServer s, int village) {
        var p = s.getPlayerList().getPlayers().getFirst();
        for (var e : s.overworld().getEntitiesOfClass(org.webtrade.minecraftportsmod.village.ResidentEntity.class, p.getBoundingBox().inflate(120),
                org.webtrade.minecraftportsmod.village.ResidentEntity::colony)) {
            if (e.colonyVillage() == village && e.colonyJob() == org.webtrade.minecraftportsmod.colony.Job.MERCHANT
                    && e.activity().getString().startsWith("Carrying")) return e;
        }
        return null;
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
            int[] a = {-1}, spot = {0, 0};
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                Village best = null;
                for (Village v : VillageData.get(s).all()) {
                    if (best == null || v.center.distSqr(p.blockPosition()) < best.center.distSqr(p.blockPosition())) best = v;
                }
                a[0] = best.id;
                // a place on dry, fairly level land some 350 blocks away
                int bx = best.center.getX(), bz = best.center.getZ();
                double bestScore = Double.MAX_VALUE;
                for (int k = 0; k < 16; k++) {
                    double ang = Math.toRadians(k * 22.5);
                    int x = bx + (int) Math.round(Math.cos(ang) * 350), z = bz + (int) Math.round(Math.sin(ang) * 350);
                    int g = ground(s, x, z), sf = surface(s, x, z);
                    if (sf > g) continue;
                    int rough = 0;
                    for (int[] d : new int[][]{{8, 0}, {-8, 0}, {0, 8}, {0, -8}}) rough = Math.max(rough, Math.abs(ground(s, x + d[0], z + d[1]) - g));
                    double score = rough + Math.abs(g - s.overworld().getSeaLevel()) * 0.2;
                    if (score < bestScore) {
                        bestScore = score;
                        spot[0] = x;
                        spot[1] = z;
                    }
                }
                log("village #{} {} at {}; second site {} {} (score {})", best.id, best.name, best.center.toShortString(), spot[0], spot[1], bestScore);
                p.teleportTo(s.overworld(), spot[0] + 0.5, ground(s, spot[0], spot[1]) + 2, spot[1] + 0.5, java.util.Set.of(), 0, 0, false);
            });
            context.waitTicks(60);
            sp.getConnection().waitForChunksRender();
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(40);
            int[] b = {-1};
            server.runOnServer(s -> {
                for (Village v : VillageData.get(s).all()) if (v.id != a[0] && (b[0] < 0 || v.id > b[0])) b[0] = v.id;
                log("second village #{}", b[0]);
            });
            if (b[0] < 0) throw new AssertionError("no second village");
            long t0 = System.currentTimeMillis();
            server.runCommand("village trail " + a[0] + " " + b[0]);
            server.waitFor(s -> {
                Trails.Trail t = Trails.find(VillageData.get(s), a[0], b[0]);
                return t != null && (t.ready() || t.none());
            }, 20 * 300);
            // (this test looks at the ways themselves: they are made at once; the crews making them: RoadworkClientGameTest)
            server.runCommand("village roads finish");
            int[][] pts = {null};
            server.runOnServer(s -> {
                Trails.Trail t = Trails.find(VillageData.get(s), a[0], b[0]);
                Village va = VillageData.get(s).get(a[0]), vb = VillageData.get(s).get(b[0]);
                double straight = Math.sqrt(va.center.distSqr(vb.center));
                log("worked out in {} ms: none={} length {} (straight {}), points {}, bridges {}", System.currentTimeMillis() - t0, t.none(), t.length(),
                        (int) straight, t.points().length / 2, t.bridges());
                if (!t.ready()) return;
                pts[0] = t.points();
                // how the way goes over the land: the steepest step, steps along a steep slope, water crossed
                int[] p = pts[0];
                int steep = 0, alongSlope = 0, wet = 0, climb = 0;
                for (int i = 2; i + 1 < p.length; i += 2) {
                    int h0 = ground(s, p[i - 2], p[i - 1]), h1 = ground(s, p[i], p[i + 1]);
                    steep = Math.max(steep, Math.abs(h1 - h0));
                    climb += Math.abs(h1 - h0);
                    if (surface(s, p[i], p[i + 1]) > h1) wet++;
                    int rough = 0;
                    for (int[] d : new int[][]{{8, 0}, {-8, 0}, {0, 8}, {0, -8}}) rough = Math.max(rough, Math.abs(ground(s, p[i] + d[0], p[i + 1] + d[1]) - h1));
                    if (rough > 6) alongSlope++;
                }
                // the straight line, for comparison
                int sClimb = 0, sSteep = 0, n = (int) (straight / 4);
                for (int i = 1; i <= n; i++) {
                    int x0 = va.center.getX() + (vb.center.getX() - va.center.getX()) * (i - 1) / n, z0 = va.center.getZ() + (vb.center.getZ() - va.center.getZ()) * (i - 1) / n;
                    int x1 = va.center.getX() + (vb.center.getX() - va.center.getX()) * i / n, z1 = va.center.getZ() + (vb.center.getZ() - va.center.getZ()) * i / n;
                    int dh = Math.abs(ground(s, x1, z1) - ground(s, x0, z0));
                    sClimb += dh;
                    sSteep = Math.max(sSteep, dh);
                }
                log("trail: steepest step {} (4 blocks), total climb {}, points by steep slopes {}, points over water {}", steep, climb, alongSlope, wet);
                log("straight line: steepest step {}, total climb {}", sSteep, sClimb);
            });
            if (pts[0] == null) {
                log("no way by land between these two: nothing to lay");
            } else {
                // fly along it: the land loads, the trail is laid
                int[] p = pts[0];
                for (int i = 0; i + 1 < p.length; i += 24) {
                    final int x = p[i], z = p[i + 1];
                    server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), x + 0.5, surface(s, x, z) + 40, z + 0.5,
                            java.util.Set.of(), 0, 90, false));
                    context.waitTicks(105);
                }
                server.runOnServer(s -> {
                    Trails.Trail t = Trails.find(VillageData.get(s), a[0], b[0]);
                    log("laid {} of {} stretches", t.laidCount(), t.segments());
                });
                // the trail in among the houses of A, up to the square
                server.runOnServer(s -> {
                    Village va = VillageData.get(s).get(a[0]);
                    s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), va.center.getX() + 0.5, va.center.getY() + 45, va.center.getZ() + 0.5,
                            java.util.Set.of(), 0, 90, false);
                });
                context.waitTicks(200);
                sp.getConnection().waitForChunksRender();
                context.takeScreenshot("trail_e_village");
                // a look from above at the middle, and at a bridge if there is one
                int mid = (p.length / 4) * 2;
                final int mx = p[mid], mz = p[mid + 1];
                server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), mx + 0.5, surface(s, mx, mz) + 70, mz + 0.5,
                        java.util.Set.of(), 0, 90, false));
                context.waitTicks(120);
                sp.getConnection().waitForChunksRender();
                context.takeScreenshot("trail_a_above");
                server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), mx + 12.5, surface(s, mx, mz) + 14, mz + 12.5,
                        java.util.Set.of(), 135, 35, false));
                context.waitTicks(60);
                sp.getConnection().waitForChunksRender();
                context.takeScreenshot("trail_b_close");
                int[] bridge = {-1};
                server.runOnServer(s -> {
                    for (int i = 0; i + 1 < p.length; i += 2) {
                        if (surface(s, p[i], p[i + 1]) > ground(s, p[i], p[i + 1]) && Math.hypot(p[i] - p[0], p[i + 1] - p[1]) > 50
                                && Math.hypot(p[i] - p[p.length - 2], p[i + 1] - p[p.length - 1]) > 50) {
                            bridge[0] = i;
                            break;
                        }
                    }
                });
                if (bridge[0] >= 0) {
                    final int bx = p[bridge[0]], bz = p[bridge[0] + 1];
                    server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), bx + 10.5, surface(s, bx, bz) + 10, bz + 10.5,
                            java.util.Set.of(), 135, 30, false));
                    context.waitTicks(80);
                    sp.getConnection().waitForChunksRender();
                    context.takeScreenshot("trail_c_bridge");
                    server.runOnServer(s -> {
                        var l = s.overworld();
                        StringBuilder fb = new StringBuilder();
                        for (BlockPos q : BlockPos.betweenClosed(new BlockPos(bx - 8, 55, bz - 8), new BlockPos(bx + 8, 80, bz + 8))) {
                            var st = l.getBlockState(q);
                            if (st.is(net.minecraft.tags.BlockTags.FENCES) || st.is(net.minecraft.tags.BlockTags.PLANKS)) fb.append(q.toShortString()).append(' ').append(st).append("; ");
                        }
                        log("bridge blocks: {}", fb);
                    });
                }
            }

            // the network: a third village C off the middle of the A-B trail; its scout found A: its trail goes to the
            // A-B trail (a fork there), not to A's middle; and C's merchant reaches B over the fork
            int[] c = {-1};
            if (pts[0] != null) {
                int[] p = pts[0];
                int mid = (p.length / 4) * 2;
                int mx = p[mid], mz = p[mid + 1];
                // across the trail, some 150 blocks off it, on dry land
                double dx = p[mid + 2] - p[mid - 2], dz = p[mid + 3] - p[mid - 1], len = Math.hypot(dx, dz);
                int[] site = {0, 0};
                server.runOnServer(s -> {
                    for (int side : new int[]{1, -1}) {
                        for (int off = 150; off <= 260; off += 20) {
                            int x = mx + (int) Math.round(-dz / len * off * side), z = mz + (int) Math.round(dx / len * off * side);
                            if (surface(s, x, z) > ground(s, x, z)) continue;
                            site[0] = x;
                            site[1] = z;
                            return;
                        }
                    }
                });
                log("third site {} {} (trail middle {} {})", site[0], site[1], mx, mz);
                server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), site[0] + 0.5, ground(s, site[0], site[1]) + 2,
                        site[1] + 0.5, java.util.Set.of(), 0, 0, false));
                context.waitTicks(60);
                sp.getConnection().waitForChunksRender();
                server.runCommand("execute as @a at @s run village camp");
                context.waitTicks(40);
                server.runOnServer(s -> {
                    for (Village v : VillageData.get(s).all()) if (v.id != a[0] && v.id != b[0] && (c[0] < 0 || v.id > c[0])) c[0] = v.id;
                });
                long t1 = System.currentTimeMillis();
                server.runCommand("village trail " + c[0] + " " + a[0]);
                server.waitFor(s -> {
                    VillageData data = VillageData.get(s);
                    for (var t : data.trails()) if ((t.a == c[0] || t.b == c[0]) && (t.ready() || t.none())) return true;
                    return false;
                }, 20 * 300);
                server.runCommand("village roads finish");
                server.runOnServer(s -> {
                    VillageData data = VillageData.get(s);
                    Village va = data.get(a[0]), vc = data.get(c[0]);
                    for (var t : data.trails()) log("edge {} - {}: {} blocks{}", t.a, t.b, t.length(), t.none() ? " (no way)" : "");
                    int[] toB = Trails.route(data, c[0], b[0]);
                    log("C->B over the network: {} | C to A straight {} | worked out in {} ms", toB == null ? "none" : toB.length / 2 + " points",
                            (int) Math.sqrt(va.center.distSqr(vc.center)), System.currentTimeMillis() - t1);
                    for (var l : vc.log()) if (l.text().getString().contains("trail") || l.text().getString().contains("way")) log("C log: {}", l.text().getString());
                    boolean fork = false;
                    for (var t : data.trails()) if (t.a < 0 || t.b < 0) fork = true;
                    if (!fork) throw new AssertionError("C's trail did not join the A-B trail at a fork");
                    if (toB == null) throw new AssertionError("C cannot reach B over the network");
                });
            }

            // beyond B (seen from A) a village D; A's scout finds D: the new trail leaves the network at B (or near it),
            // not from A's middle past B
            if (pts[0] != null) {
                int[] site = {0, 0};
                server.runOnServer(s -> {
                    Village va = VillageData.get(s).get(a[0]), vb = VillageData.get(s).get(b[0]);
                    double dx = vb.center.getX() - va.center.getX(), dz = vb.center.getZ() - va.center.getZ(), len = Math.hypot(dx, dz);
                    for (int off = 260; off <= 420; off += 20) {
                        for (int turn : new int[]{0, 20, -20, 40, -40}) {
                            double ang = Math.atan2(dz, dx) + Math.toRadians(turn);
                            int x = vb.center.getX() + (int) Math.round(Math.cos(ang) * off), z = vb.center.getZ() + (int) Math.round(Math.sin(ang) * off);
                            if (surface(s, x, z) > ground(s, x, z)) continue;
                            site[0] = x;
                            site[1] = z;
                            return;
                        }
                    }
                });
                log("site beyond B: {} {}", site[0], site[1]);
                server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), site[0] + 0.5, ground(s, site[0], site[1]) + 2,
                        site[1] + 0.5, java.util.Set.of(), 0, 0, false));
                context.waitTicks(60);
                sp.getConnection().waitForChunksRender();
                server.runCommand("execute as @a at @s run village camp");
                context.waitTicks(40);
                int[] d4 = {-1};
                server.runOnServer(s -> {
                    for (Village v : VillageData.get(s).all()) if (v.id != a[0] && v.id != b[0] && v.id != c[0] && (d4[0] < 0 || v.id > d4[0])) d4[0] = v.id;
                });
                java.util.Set<Long> before = new java.util.HashSet<>();
                server.runOnServer(s -> {
                    for (var t : VillageData.get(s).trails()) before.add(((long) t.a << 32) ^ (t.b & 0xFFFFFFFFL));
                });
                server.runCommand("village trail " + a[0] + " " + d4[0]);
                server.waitFor(s -> {
                    for (var t : VillageData.get(s).trails()) if ((t.a == d4[0] || t.b == d4[0]) && (t.ready() || t.none())) return true;
                    return false;
                }, 20 * 300);
                server.runCommand("village roads finish");
                server.runOnServer(s -> {
                    VillageData data = VillageData.get(s);
                    boolean fromA = false;
                    for (var t : data.trails()) {
                        boolean fresh = !before.contains(((long) t.a << 32) ^ (t.b & 0xFFFFFFFFL));
                        log("edge {} - {}: {} blocks{}{}", t.a, t.b, t.length(), t.none() ? " (no way)" : "", fresh ? " NEW" : "");
                        if (fresh && t.ready() && (t.a == a[0] || t.b == a[0])) fromA = true;
                    }
                    log("A->D over the network: {}", Trails.route(data, a[0], d4[0]) == null ? "none" : "yes");
                    for (var l : data.get(d4[0]).log()) log("D log: {}", l.text().getString());
                    if (fromA) throw new AssertionError("the trail to D was laid from A's middle, not from the network beyond it");
                });
            }

            // the caravan: village A has food to spare, B is short of it and has emeralds; both keep a stall
            server.runOnServer(s -> {
                Village va = VillageData.get(s).get(a[0]);
                s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), va.center.getX() + 0.5, va.center.getY() + 20, va.center.getZ() + 0.5,
                        java.util.Set.of(), 0, 60, false);
            });
            context.waitTicks(100);
            server.runOnServer(s -> {
                for (String cmd : new String[]{"build %d market 1", "build %d storehouse 3", "grow %d merchant", "grow %d fisher", "grow %d fisher"}) {
                    for (int id : new int[]{a[0], b[0]}) s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "village " + String.format(cmd, id));
                }
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "village give " + a[0] + " food 3000");
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "village give " + b[0] + " food -1000");
                Village vb = VillageData.get(s).get(b[0]);
                log("A->B: {}", Caravans.explain(VillageData.get(s), a[0], b[0]));
                log("B->A: {}", Caravans.explain(VillageData.get(s), b[0], a[0]));
                log("before: A food {} purse {} | B food {} purse {}", VillageData.get(s).get(a[0]).stock(Res.FOOD), VillageData.get(s).get(a[0]).emeralds(),
                        vb.stock(Res.FOOD), vb.emeralds());
            });
            // skipped days: a day's walk each; the trade happens when he gets there
            for (int day = 1; day <= 6; day++) {
                server.runCommand("village day");
                context.waitTicks(10);
                final int d = day;
                server.runOnServer(s -> log("day {}: {}", d, trips(VillageData.get(s), a[0], b[0])));
            }
            // seen on the road: the player stands by the trail a little way out of A as the merchant sets out
            if (pts[0] != null) {
                int[] p = pts[0];
                int i = Math.min(p.length - 2, 2 * 20);
                final int sx = p[i], sz = p[i + 1];
                server.runOnServer(s -> {
                    VillageData data = VillageData.get(s);
                    // (the merchant rested: he sets out today; B is short of food again, enough for a round to be worth it)
                    s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "village give " + a[0] + " food 800");
                    s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "village give " + b[0] + " food -1000");
                    s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), sx + 6.5, surface(s, sx, sz) + 6, sz + 6.5, java.util.Set.of(), 135, 30, false);
                });
                context.waitTicks(40);
                for (int k = 0; k < 4 && !onRoad(server, a[0]); k++) {
                    server.runCommand("village day");
                    context.waitTicks(10);
                }
                server.runOnServer(s -> log("set out: {}", trips(VillageData.get(s), a[0], b[0])));
                boolean[] seen = {false};
                for (int k = 1; k <= 12; k++) {
                    context.waitTicks(40);
                    final int kk = k;
                    server.runOnServer(s -> {
                        var e = merchantBody(s, a[0]);
                        if (e != null) seen[0] = true;
                        log("after {} s: {} | body {}", kk * 2, trips(VillageData.get(s), a[0], b[0]),
                                e == null ? "none" : e.blockPosition().toShortString() + " " + e.activity().getString());
                    });
                    if (seen[0] && k >= 6) break;
                }
                context.takeScreenshot("trail_d_merchant");
                if (!seen[0]) throw new AssertionError("the merchant was never seen on the road by the player");
                // the world map (the chart key on land): the villages, the trail, the merchant on it
                server.runOnServer(s -> org.webtrade.minecraftportsmod.chart.WorldMapService.send(s.getPlayerList().getPlayers().getFirst(), false));
                context.waitTicks(60);
                boolean[] open = {false};
                context.runOnClient(mc -> open[0] = mc.gui.screen() instanceof org.webtrade.minecraftportsmod.client.chart.WorldMapScreen);
                if (!open[0]) throw new AssertionError("the world map did not open");
                context.takeScreenshot("worldmap_a_near");
                context.runOnClient(mc -> ((org.webtrade.minecraftportsmod.client.chart.WorldMapScreen) mc.gui.screen()).zoomOut(4));
                context.waitTicks(60);
                context.takeScreenshot("worldmap_b_far");
                context.setScreen(() -> null);
                // killed on the road: nothing is sold, the goods are gone
                server.runOnServer(s -> {
                    var e = merchantBody(s, a[0]);
                    if (e != null) e.kill(s.overworld());
                });
                context.waitTicks(60);
                server.runOnServer(s -> {
                    VillageData data = VillageData.get(s);
                    log("after the kill: {}", trips(data, a[0], b[0]));
                    if (!data.trips().isEmpty()) throw new AssertionError("the trip went on without its merchant");
                });
            }
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                for (int id : new int[]{a[0], b[0]}) {
                    for (var l : data.get(id).log()) {
                        String txt = l.text().getString();
                        if (txt.contains("trail") || txt.contains("road") || txt.contains("merchant") || txt.contains("sold") || txt.contains("bought")
                                || txt.contains("way by land")) log("log #{} day {}: {}", id, l.day(), txt);
                    }
                }
            });
        }
    }
}
