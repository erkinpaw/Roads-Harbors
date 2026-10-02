package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.fleet.FleetManager;
import org.webtrade.minecraftportsmod.fleet.VesselRecord;
import org.webtrade.minecraftportsmod.port.Port;
import org.webtrade.minecraftportsmod.port.PortData;
import org.webtrade.minecraftportsmod.port.PortService;
import org.webtrade.minecraftportsmod.port.Route;
import org.webtrade.minecraftportsmod.registry.ModContent;

import java.util.List;

/**
 * Filming the ships for a short vertical video (1080x1920): a village by the sea grown a little, a port at it and
 * another down the coast, a brig; then the shots, frame by frame (a frame a game tick), with the camera placed for
 * each frame and the game's interface hidden. The frames go to the screenshots; ffmpeg makes the video of them.
 */
public class ShipsFilmClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[film] " + fmt, args);
    }

    /** Where the camera is and what it looks at. */
    private record Cam(double x, double y, double z, double tx, double ty, double tz) {
        float yaw() {
            return (float) Math.toDegrees(Math.atan2(-(tx - x), tz - z));
        }

        float pitch() {
            return (float) -Math.toDegrees(Math.atan2(ty - y, Math.hypot(tx - x, tz - z)));
        }
    }

    private static int floor(ServerLevel l, int x, int z) {
        return l.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
    }

    private static int surface(ServerLevel l, int x, int z) {
        return l.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
    }

    /** Is (x, z) open water at least this deep. */
    private static boolean deep(ServerLevel l, int x, int z, int depth) {
        int s = surface(l, x, z);
        return !l.getBlockState(new BlockPos(x, s - 1, z)).getFluidState().isEmpty() && s - floor(l, x, z) >= depth;
    }

    /** A spot on the shore near (cx, cz), between r0 and r1 out: dry land with deep water right beside it; null if none. */
    private static BlockPos shore(ServerLevel l, int cx, int cz, int r0, int r1, int sea) {
        for (int r = r0; r <= r1; r += 4) {
            for (int a = 0; a < 48; a++) {
                double ang = a * Math.PI * 2 / 48;
                int x = cx + (int) Math.round(Math.cos(ang) * r), z = cz + (int) Math.round(Math.sin(ang) * r);
                if (!l.hasChunkAt(new BlockPos(x, 0, z))) continue;
                int s = surface(l, x, z);
                if (s < sea + 1 || s > sea + 3 || !l.getBlockState(new BlockPos(x, s - 1, z)).getFluidState().isEmpty()) continue;
                int water = 0;
                for (int[] d : new int[][]{{4, 0}, {-4, 0}, {0, 4}, {0, -4}, {8, 0}, {-8, 0}, {0, 8}, {0, -8}}) if (deep(l, x + d[0], z + d[1], 2)) water++;
                if (water >= 2) return new BlockPos(x, s, z);
            }
        }
        return null;
    }

    /** A shore on the same water as {@code from}, between r0 and r1 away (by water, on a 2-block grid); null if none. */
    private static BlockPos shoreByWater(ServerLevel l, BlockPos from, int r0, int r1, int sea) {
        java.util.ArrayDeque<int[]> open = new java.util.ArrayDeque<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int dx = -10; dx <= 10; dx += 2) for (int dz = -10; dz <= 10; dz += 2) {
            int x = from.getX() + dx, z = from.getZ() + dz;
            if (deep(l, x, z, 2) && seen.add(((long) x << 32) ^ (z & 0xffffffffL))) open.add(new int[]{x, z});
        }
        BlockPos best = null;
        double bestD = 0;
        while (!open.isEmpty() && seen.size() < 200000) {
            int[] c = open.poll();
            for (int[] d : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
                int x = c[0] + d[0], z = c[1] + d[1];
                if (!l.hasChunkAt(new BlockPos(x, 0, z)) || Math.hypot(x - from.getX(), z - from.getZ()) > r1) continue;
                long k = ((long) x << 32) ^ (z & 0xffffffffL);
                if (!seen.add(k)) continue;
                if (deep(l, x, z, 2)) {
                    open.add(new int[]{x, z});
                    continue;
                }
                // land next to the water: a shore, if low and far enough
                int sf = surface(l, x, z);
                double dist = Math.hypot(x - from.getX(), z - from.getZ());
                if (dist >= r0 && sf >= sea + 1 && sf <= sea + 3 && l.getBlockState(new BlockPos(x, sf - 1, z)).getFluidState().isEmpty() && dist > bestD) {
                    bestD = dist;
                    best = new BlockPos(x, sf, z);
                }
            }
        }
        return best;
    }

    private static Port makePort(ServerLevel l, BlockPos at, String name) {
        l.setBlock(at, ModContent.PORT_OFFICE.defaultBlockState(), 3);
        Port p = PortService.createPort(l, at, null, null);
        if (p != null) PortData.get(l.getServer()).renamePort(p, name);
        return p;
    }

    private static void camera(ClientGameTestContext context, Cam c) {
        context.runOnClient(mc -> {
            mc.player.snapTo(c.x(), c.y(), c.z(), c.yaw(), c.pitch());
            mc.player.setOldPosAndRot();
        });
    }

    /** The brig's place and heading: x, y, z, yaw. */
    private static double[] ship(MinecraftServer s, java.util.UUID id) {
        var e = FleetManager.live(id);
        if (e == null) return null;
        return new double[]{e.getX(), e.getY(), e.getZ(), e.getYRot()};
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("4242")).create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            context.getInput().resizeWindow(1080, 1920);
            context.runOnClient(mc -> {
                mc.options.renderDistance().set(16);
                mc.options.simulationDistance().set(12);
                mc.options.entityDistanceScaling().set(5.0);
                mc.options.fov().set(70);
            });
            server.runOnServer(s -> {
                s.getPlayerList().setViewDistance(16);
                s.getPlayerList().setSimulationDistance(12);
            });
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("weather clear 100000");
            server.waitFor(s -> VillageData.get(s).all().size() >= 1, 20 * 900);
            int[] vc = new int[3];
            server.runOnServer(s -> {
                Village v = VillageData.get(s).all().iterator().next();
                vc[0] = v.center.getX();
                vc[1] = v.center.getY();
                vc[2] = v.center.getZ();
                log("village {} at {}", v.name, v.center.toShortString());
            });
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a " + vc[0] + " " + (vc[1] + 30) + " " + vc[2]);
            // (a long way to see: the land round it takes a while to come in)
            context.waitTicks(400);
            // the village grown a little: houses, a field, a storehouse (its buildings go up as the player watches)
            for (int d = 0; d < 30; d++) {
                server.runCommand("village day");
                context.waitTicks(10);
            }
            server.runCommand("village day");
            context.waitTicks(200);

            // the ports: one by the village, one down the coast
            int sea = server.computeOnServer(s -> s.overworld().getSeaLevel());
            BlockPos[] at = new BlockPos[2];
            server.runOnServer(s -> {
                ServerLevel l = s.overworld();
                // (what the water round the village is like)
                StringBuilder probe = new StringBuilder();
                for (int a = 0; a < 8; a++) {
                    double ang = a * Math.PI / 4;
                    probe.append(a).append(':');
                    for (int r = 10; r <= 70; r += 10) {
                        int x = vc[0] + (int) Math.round(Math.cos(ang) * r), z = vc[2] + (int) Math.round(Math.sin(ang) * r);
                        int sf = surface(l, x, z), fl = floor(l, x, z);
                        probe.append(l.getBlockState(new BlockPos(x, sf - 1, z)).getFluidState().isEmpty() ? "L" + (sf - sea) : "W" + (sf - fl)).append(' ');
                    }
                    probe.append("| ");
                }
                log("round the village (L height over the sea, W depth): {}", probe);
                at[0] = shore(l, vc[0], vc[2], 6, 80, sea);
                if (at[0] != null) at[1] = shoreByWater(l, at[0], 160, 260, sea);
                log("port sites {} / {}", at[0], at[1]);
            });
            if (at[0] == null || at[1] == null) throw new AssertionError("no shore for the ports");
            server.runOnServer(s -> {
                ServerLevel l = s.overworld();
                makePort(l, at[0], "Коралловый порт");
                makePort(l, at[1], "Тихая гавань");
            });
            server.waitFor(s -> PortData.get(s).routes().size() >= 1
                    && PortData.get(s).routes().stream().allMatch(r -> r.status() != Route.Status.PENDING), 20 * 120);
            server.runOnServer(s -> {
                for (Route r : PortData.get(s).routes()) log("route {}-{} {} {}", r.portA(), r.portB(), r.status(), (int) r.length());
                for (Port p : PortData.get(s).ports()) log("port {} berths {}", p.name(), PortData.get(s).docksOf(p.id()).size());
            });

            // the brig, at the village's port
            java.util.UUID[] brig = new java.util.UUID[1];
            server.runCommand("gamemode creative @a");
            server.runOnServer(s -> {
                ServerPlayer p = s.getPlayerList().getPlayers().getFirst();
                Port home = PortData.get(s).port(1);
                FleetManager.build(p, home);
                VesselRecord r = FleetManager.vesselsOf(s, p.getUUID()).getFirst();
                FleetManager.upgrade(p, r.id(), home);
                FleetManager.upgrade(p, r.id(), home);
                brig[0] = r.id();
                log("vessel {} {}", r.name(), r.type());
            });
            server.runCommand("gamemode spectator @a");
            server.runCommand("time set 11200");
            context.runOnClient(mc -> log("render distance effective {}", mc.options.getEffectiveRenderDistance()));
            context.waitTicks(40);
            context.runOnClient(mc -> {
                if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
            });

            // ---- shot 1: the brig sets out; the camera follows it, gently, going round it
            server.runOnServer(s -> {
                ServerPlayer p = s.getPlayerList().getPlayers().getFirst();
                FleetManager.send(p, brig[0], 2, false);
            });
            context.waitTicks(20);
            // (the world waits for the camera: a tick at a time, however long a frame takes to take)
            server.runCommand("tick freeze");
            context.waitTicks(2);
            double[] look = null, cam = null;
            int ticks = 460;
            int frame = 0;
            for (int i = 0; i < ticks; i++) {
                server.runCommand("tick step 1");
                context.waitTicks(1);
                // the ship as the client has it, now and a tick ago
                int netId = server.computeOnServer(s -> {
                    var e = FleetManager.live(brig[0]);
                    return e == null ? -1 : e.getId();
                });
                double[] b = context.computeOnClient(mc -> {
                    var e = mc.level.getEntity(netId);
                    return e == null ? null : new double[]{e.xo, e.yo, e.zo, e.getX(), e.getY(), e.getZ()};
                });
                if (i % 20 == 0) {
                    double[] sv = server.computeOnServer(s -> ship(s, brig[0]));
                    log("tick {}: server {} client {} look {} cam {}", i, sv == null ? "-" : String.format("%.1f %.1f %.1f", sv[0], sv[1], sv[2]),
                            b == null ? "-" : String.format("%.1f %.1f %.1f", b[3], b[4], b[5]),
                            look == null ? "-" : String.format("%.1f %.1f %.1f", look[0], look[1], look[2]),
                            cam == null ? "-" : String.format("%.1f %.1f %.1f", cam[0], cam[1], cam[2]));
                }
                if (b == null) break;
                for (float part : new float[]{0.5F, 1.0F}) {
                    double[] pos = {b[0] + (b[3] - b[0]) * part, b[1] + (b[4] - b[1]) * part, b[2] + (b[5] - b[2]) * part};
                    if (look == null) look = pos.clone();
                    // (a camera operator, not a rail: it eases after the ship)
                    for (int k = 0; k < 3; k++) look[k] += (pos[k] - look[k]) * 0.1;
                    double ang = Math.toRadians(150 - frame * 0.18);
                    double[] want = {look[0] + Math.sin(ang) * 18, look[1] + 6, look[2] - Math.cos(ang) * 18};
                    if (cam == null) cam = want.clone();
                    for (int k = 0; k < 3; k++) cam[k] += (want[k] - cam[k]) * 0.08;
                    final float pt = part;
                    context.runOnClient(mc -> org.webtrade.minecraftportsmod.client.FilmClock.partial = pt);
                    camera(context, new Cam(cam[0], cam[1], cam[2], look[0], look[1] + 2.5, look[2]));
                    context.takeScreenshot(String.format("s1_%04d", frame++));
                }
            }
            context.runOnClient(mc -> org.webtrade.minecraftportsmod.client.FilmClock.partial = -1);
            server.runCommand("tick unfreeze");
        }
    }
}
