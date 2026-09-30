package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.AABB;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.client.chart.SiteScreen;
import org.webtrade.minecraftportsmod.client.chart.VillageScreen;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Dweller;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * A normal world, watched in real time: the first camp's people at their trades (trees felled and replanted, stone
 * broken, fish caught, all brought to the store), a baby, a building site supplied by the player and built by the
 * people, a night in the tents and a morning out of them; then days skipped until the camp is a hamlet; and the
 * village data saved and read back. Everything is logged with "[colony]" and photographed.
 */
public class ColonyClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[colony] " + fmt, args);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(ui -> ui.setSeed("12345"))
                .create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("weather clear");
            server.runCommand("time set 2000");
            server.waitFor(s -> !VillageData.get(s).all().isEmpty(), 20 * 600);
            int[] c = new int[4];
            server.runOnServer(s -> {
                Village v = VillageData.get(s).all().iterator().next();
                c[0] = v.center.getX();
                c[1] = v.center.getY();
                c[2] = v.center.getZ();
                c[3] = v.id;
            });
            final int id = c[3];
            look(server, c, 14, 16, 14);
            sp.getConnection().waitForChunksRender();
            context.waitTicks(20 * 10);
            state(server, id, "start");
            context.takeScreenshot("00_camp");

            // ---------------------------------------------------------------- 1. a morning at work
            int saplingsBefore = count(server, c, true);
            int stoneBefore = count(server, c, false);
            Map<String, Integer> stockBefore = stock(server, id);
            for (int i = 0; i < 12; i++) {
                context.waitTicks(20 * 10);
                activities(server, id, "work+" + (i + 1) * 10 + "s");
                if (i == 3) follow(context, server, id, Job.WOODCUTTER, "01_woodcutter");
                if (i == 5) follow(context, server, id, Job.MINER, "02_miner");
                if (i == 7) follow(context, server, id, Job.GATHERER, "03_gatherer");
                if (i == 9) follow(context, server, id, Job.WOODCUTTER, "04_woodcutter_later");
            }
            int saplingsAfter = count(server, c, true);
            int stoneAfter = count(server, c, false);
            Map<String, Integer> stockAfter = stock(server, id);
            log("WORK RESULT: saplings {} -> {}, mineable stone {} -> {}, stock {} -> {}", saplingsBefore, saplingsAfter,
                    stoneBefore, stoneAfter, stockBefore, stockAfter);
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(id);
                for (Dweller d : v.dwellers()) log("earned {} {} = {}", d.name, d.job(), d.earned());
            });

            // ---------------------------------------------------------------- 2. a baby
            server.runCommand("village baby " + id);
            context.waitTicks(20 * 4);
            server.runOnServer(s -> {
                for (ResidentEntity e : s.overworld().getEntitiesOfClass(ResidentEntity.class, new AABB(new BlockPos(c[0], c[1], c[2])).inflate(96))) {
                    if (e.isBaby()) {
                        log("BABY {} at {} height={} activity={}", e.getCustomName() == null ? "?" : e.getCustomName().getString(),
                                e.blockPosition().toShortString(), e.getBbHeight(), e.activity().getString());
                        lookAt(s, e.getX(), e.getY() + 0.6, e.getZ(), 4.5, 1.5, 4.5);
                        break;
                    }
                }
            });
            context.waitTicks(30);
            context.runOnClient(mc -> {
                for (var ent : mc.level.entitiesForRendering()) {
                    if (ent instanceof ResidentEntity e && e.colony()) {
                        log("CLIENT {} baby={} scale={} height={} job={}", name(e), e.isBaby(), e.getScale(), e.getBbHeight(), e.colonyJob());
                    }
                }
            });
            context.takeScreenshot("05_baby");

            // ---------------------------------------------------------------- 3. a building site, supplied by the player
            server.runCommand("village day");
            context.waitTicks(20 * 3);
            int[] site = new int[4];
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(id);
                for (Building b : v.projects()) {
                    if (b.state() == Building.State.PLANNED) {
                        site[0] = b.origin.getX();
                        site[1] = b.origin.getY();
                        site[2] = b.origin.getZ();
                        site[3] = b.id;
                        log("SITE planned: {} #{} missing wood={} stone={}", b.type.id(), b.id, b.missing(Res.WOOD), b.missing(Res.STONE));
                        break;
                    }
                }
            });
            if (site[3] > 0) {
                server.runCommand("give @a oak_log 64");
                server.runCommand("give @a cobblestone 64");
                server.runCommand("gamemode creative @a");
                look(server, site, 5, 3, 5);
                context.waitTicks(20 * 3);
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(id);
                    Building b = v.building(site[3]);
                    ColonyService.openSite(s.getPlayerList().getPlayers().getFirst(), b.blueprint(v.wood).post);
                });
                context.waitTicks(20);
                if (context.computeOnClient(mc -> mc.gui.screen() instanceof SiteScreen)) context.takeScreenshot("06_site_before");
                server.runOnServer(s -> ColonyService.handleSite(s.getPlayerList().getPlayers().getFirst(),
                        new ColonyPayloads.SiteAction(id, site[3], true)));
                context.waitTicks(20);
                if (context.computeOnClient(mc -> mc.gui.screen() instanceof SiteScreen)) context.takeScreenshot("07_site_after");
                context.setScreen(() -> null);
                server.runCommand("gamemode spectator @a");
                server.runOnServer(s -> {
                    Building b = VillageData.get(s).get(id).building(site[3]);
                    log("SITE after donation: state={} delivered wood={} stone={}", b.state(), b.delivered(Res.WOOD), b.delivered(Res.STONE));
                });
                // in the morning (only those done with their day's work build), then in the afternoon
                for (int i = 0; i < 24; i++) {
                    if (i == 6) server.runCommand("time set 7000");
                    context.waitTicks(20 * 10);
                    final int step = i;
                    boolean[] built = new boolean[1];
                    server.runOnServer(s -> {
                        Village v = VillageData.get(s).get(id);
                        Building b = v.building(site[3]);
                        built[0] = b == null || b.state() == Building.State.BUILT;
                        log("SITE +{}s: state={} work={}", (step + 1) * 10, b == null ? "-" : b.state(), b == null ? 0 : b.work());
                    });
                    activities(server, id, "build+" + (i + 1) * 10 + "s");
                    if (i == 2 || i == 8 || i == 14) {
                        look(server, site, 8, 6, 8);
                        context.waitTicks(10);
                        context.takeScreenshot("08_building_" + i);
                    }
                    if (built[0]) {
                        log("SITE BUILT after {}s", (i + 1) * 10);
                        look(server, site, 8, 6, 8);
                        context.waitTicks(20);
                        context.takeScreenshot("09_built");
                        break;
                    }
                }
            } else {
                log("SITE none planned!");
            }

            // ---------------------------------------------------------------- 4. night, and the morning after
            server.runCommand("time set 13500");
            context.waitTicks(20 * 60);
            Map<String, double[]> night = new HashMap<>();
            server.runOnServer(s -> {
                for (ResidentEntity e : residents(s, c)) {
                    log("NIGHT {} sleeping={} at {} activity={}", name(e), e.isSleeping(), e.blockPosition().toShortString(), e.activity().getString());
                    night.put(name(e), new double[]{e.getX(), e.getZ()});
                }
            });
            look(server, c, 10, 8, 10);
            context.waitTicks(10);
            context.takeScreenshot("10_night");
            server.runCommand("time set 1000");
            context.waitTicks(20 * 30);
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(id);
                for (ResidentEntity e : residents(s, c)) {
                    double[] was = night.get(name(e));
                    double moved = was == null ? -1 : Math.hypot(e.getX() - was[0], e.getZ() - was[1]);
                    boolean inside = false;
                    for (Building b : v.buildings()) {
                        if (!b.type.isHome() || !b.standing()) continue;
                        int h = b.type.half - 1;
                        if (Math.abs(e.getBlockX() - b.origin.getX()) <= h && Math.abs(e.getBlockZ() - b.origin.getZ()) <= h) inside = true;
                    }
                    log("MORNING {} moved={} insideHome={} sleeping={} activity={}", name(e), String.format("%.1f", moved), inside,
                            e.isSleeping(), e.activity().getString());
                }
            });
            context.takeScreenshot("11_morning");

            // ---------------------------------------------------------------- 5. days skipped: the camp grows
            for (int day = 0; day < 12; day++) {
                server.runCommand("village day");
                context.waitTicks(20 * 8);
                boolean[] done = new boolean[1];
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(id);
                    done[0] = v.level().ordinal() >= Village.Level.HAMLET.ordinal() && v.has(BuildingType.SQUARE);
                });
                state(server, id, "skip" + day);
                if (done[0]) break;
            }
            look(server, c, 18, 20, 18);
            context.waitTicks(20 * 5);
            context.takeScreenshot("12_hamlet_air");
            look(server, c, -8, 4, -9);
            context.waitTicks(40);
            context.takeScreenshot("13_hamlet_ground");
            server.runOnServer(s -> ColonyService.openBoard(s.getPlayerList().getPlayers().getFirst(), VillageData.get(s).get(id).board));
            context.waitForScreen(VillageScreen.class);
            context.waitTicks(20);
            context.takeScreenshot("14_board");
            context.runOnClient(mc -> ((VillageScreen) mc.gui.screen()).showTab("log"));
            context.waitTicks(10);
            context.takeScreenshot("15_board_log");
            context.setScreen(() -> null);

            // ---------------------------------------------------------------- 6. saving and loading
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                var tag = VillageData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
                VillageData back = VillageData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
                Village a = data.get(id), b = back.get(id);
                boolean same = b != null && a.population() == b.population() && a.buildings().size() == b.buildings().size()
                        && a.level() == b.level() && a.stock(Res.WOOD) == b.stock(Res.WOOD) && a.log().size() == b.log().size();
                log("SAVE ROUNDTRIP {} ({} villages, {} bytes-ish)", same ? "OK" : "MISMATCH", back.all().size(), tag.toString().length());
            });
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String name(ResidentEntity e) {
        return e.getCustomName() == null ? "?" : e.getCustomName().getString();
    }

    private static java.util.List<ResidentEntity> residents(MinecraftServer s, int[] c) {
        return s.overworld().getEntitiesOfClass(ResidentEntity.class, new AABB(new BlockPos(c[0], c[1], c[2])).inflate(96), ResidentEntity::colony);
    }

    private static void activities(TestServerContext server, int id, String tag) {
        server.runOnServer(s -> {
            Village v = VillageData.get(s).get(id);
            StringBuilder b = new StringBuilder();
            for (ResidentEntity e : residents(s, new int[]{v.center.getX(), v.center.getY(), v.center.getZ()})) {
                b.append(name(e)).append('[').append(e.colonyJob() == null ? "child" : e.colonyJob().id()).append("]: ")
                        .append(e.activity().getString()).append(" @").append(e.blockPosition().toShortString()).append(" | ");
            }
            log("{} {}", tag, b);
        });
    }

    private static void state(TestServerContext server, int id, String tag) {
        server.runOnServer(s -> {
            Village v = VillageData.get(s).get(id);
            StringBuilder st = new StringBuilder();
            for (Res r : Res.values()) st.append(r.id()).append('=').append(v.stock(r)).append(' ');
            log("{} day {} {} level={} people={} beds={} mood={} {}", tag, VillageData.get(s).day(), v.name, v.level(), v.population(),
                    v.beds(), v.mood(), st);
            for (Building b : v.buildings()) log("   #{} {} {} work={}", b.id, b.type.id(), b.state(), b.work());
        });
    }

    private static Map<String, Integer> stock(TestServerContext server, int id) {
        Map<String, Integer> m = new HashMap<>();
        server.runOnServer(s -> {
            Village v = VillageData.get(s).get(id);
            for (Res r : Res.values()) m.put(r.id(), v.stock(r));
        });
        return m;
    }

    /** Saplings (or bare stone) around the village. */
    private static int count(TestServerContext server, int[] c, boolean saplings) {
        int[] n = new int[1];
        server.runOnServer(s -> {
            var level = s.overworld();
            for (int x = -60; x <= 60; x++) {
                for (int z = -60; z <= 60; z++) {
                    int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c[0] + x, c[2] + z);
                    for (int y = top - 1; y <= top + 1; y++) {
                        var st = level.getBlockState(new BlockPos(c[0] + x, y, c[2] + z));
                        if (saplings ? st.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock : st.is(BlockTags.BASE_STONE_OVERWORLD)) n[0]++;
                    }
                }
            }
        });
        return n[0];
    }

    /** Puts the camera next to the first resident with this job and photographs them. */
    private static void follow(ClientGameTestContext context, TestServerContext server, int id, Job job, String shot) {
        boolean[] found = new boolean[1];
        server.runOnServer(s -> {
            Village v = VillageData.get(s).get(id);
            for (ResidentEntity e : residents(s, new int[]{v.center.getX(), v.center.getY(), v.center.getZ()})) {
                if (e.colonyJob() == job) {
                    lookAt(s, e.getX(), e.getY() + 0.8, e.getZ(), 4, 2.5, 4);
                    log("{}: {} {} at {}", shot, name(e), e.activity().getString(), e.blockPosition().toShortString());
                    found[0] = true;
                    break;
                }
            }
        });
        if (!found[0]) return;
        context.waitTicks(15);
        context.takeScreenshot(shot);
    }

    private static void look(TestServerContext server, int[] c, double ox, double oy, double oz) {
        server.runOnServer(s -> lookAt(s, c[0] + 0.5, c[1], c[2] + 0.5, ox, oy, oz));
    }

    private static void lookAt(MinecraftServer s, double tx, double ty, double tz, double ox, double oy, double oz) {
        ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
        double x = tx + ox, y = ty + oy, z = tz + oz;
        double dx = tx - x, dy = ty - y, dz = tz - z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        player.teleportTo(s.overworld(), x, y, z, java.util.Set.of(), yaw, pitch, false);
    }
}
