package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Harbour;
import org.webtrade.minecraftportsmod.colony.Trails;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.colony.Voyages;
import org.webtrade.minecraftportsmod.worldgen.WorldPlan;

/**
 * Island villages: the sea round the spawn is searched for islands (raised out of the open sea where there are none
 * to settle), a village set up on each; then the world left to itself for many days, the islands against the
 * mainland: people, buildings, the pier, the ships and what they trade. Pictures of an island village, its pier and
 * its ships at the end.
 */
public class IslandClientGameTest implements FabricClientGameTest {

    /** Worlds ("seed" or "seed:d150" for that many days). */
    private static final String[] SEEDS = {"4242:d60:ships"};
    private static final int VILLAGES = 10;

    private static void log(String seed, String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[island " + seed + "] " + fmt, args);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        for (String spec : SEEDS) {
            String seed = spec.split(":")[0];
            int days = 100;
            for (String part : spec.split(":")) if (part.startsWith("d") && part.length() > 1) days = Integer.parseInt(part.substring(1));
            try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed(seed)).create()) {
                TestServerContext server = sp.getServer();
                sp.getConnection().waitForChunksRender();
                server.runCommand("gamemode spectator @a");
                server.runCommand("gamerule advance_time false");
                server.runCommand("gamerule spawn_mobs false");
                server.runCommand("time set noon");
                server.runCommand("weather clear");
                long t0 = System.currentTimeMillis();
                server.waitFor(s -> WorldPlan.get(s).islandsSearched(), 20 * 400);
                log(spec, "searched in {} s", (System.currentTimeMillis() - t0) / 1000);
                server.runOnServer(s -> {
                    var spawn = s.overworld().getRespawnData().pos();
                    for (WorldPlan.Site site : WorldPlan.get(s).sites()) {
                        if (site.island) log(spec, "island site {} {} at {}, {} ({} from the spawn)", site.id, site.name, site.x, site.z,
                                (int) Math.hypot(site.x - spawn.getX(), site.z - spawn.getZ()));
                    }
                });
                // the villages set up (the islands' too)
                server.waitFor(s -> VillageData.get(s).all().size() >= VILLAGES && islands(s) >= islandSites(s), 20 * 1200);
                server.runOnServer(s -> {
                    for (Village v : VillageData.get(s).all()) {
                        log(spec, "village #{} {} at {}{}", v.id, v.name, v.center.toShortString(), v.island() ? " ISLAND" : "");
                    }
                });
                // a look at an island camp from the air
                int[] isl = new int[3];
                server.runOnServer(s -> {
                    for (Village v : VillageData.get(s).all()) {
                        if (!v.island()) continue;
                        isl[0] = v.center.getX();
                        isl[1] = v.center.getY();
                        isl[2] = v.center.getZ();
                        break;
                    }
                });
                server.runCommand("tp @a " + (isl[0] - 50) + " " + (isl[1] + 45) + " " + (isl[2] - 50) + " -45 35");
                sp.getConnection().waitForChunksRender();
                context.waitTicks(60);
                context.takeScreenshot("island_camp");
                server.runCommand("tp @a 0 120 0");
                boolean ships = spec.contains(":ships");
                for (int day = 1; day <= days; day++) {
                    // (":ships": the piers hurried along from day 20: opened and paid for everywhere)
                    if (ships && day == 8) server.runOnServer(s -> {
                        for (Village v : VillageData.get(s).all()) Harbour.testReady(v);
                    });
                    // (":passage": a ship at each pier from day 30, to sail with - not her building waited for)
                    if (spec.contains(":passage") && day == 30) server.runOnServer(s -> {
                        for (Village v : VillageData.get(s).all()) Harbour.testShip(v);
                    });
                    server.runOnServer(s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "village day"));
                    context.waitTicks(40);
                    long p0 = System.currentTimeMillis();
                    while (System.currentTimeMillis() - p0 < 60_000L) {
                        boolean[] busy = {false};
                        server.runOnServer(s -> busy[0] = Trails.planning());
                        if (!busy[0]) break;
                        context.waitTicks(20);
                    }
                    if (day % 10 == 0) {
                        final int d = day;
                        server.runOnServer(s -> report(s, spec, d));
                    }
                }
                // the island village, its pier and its ships
                int[] pier = new int[4];
                server.runOnServer(s -> {
                    for (Village v : VillageData.get(s).all()) {
                        if (!v.island()) continue;
                        isl[0] = v.center.getX();
                        isl[1] = v.center.getY();
                        isl[2] = v.center.getZ();
                        for (Building b : v.buildings()) {
                            if (b.type == BuildingType.PIER && b.state() == Building.State.BUILT) {
                                pier[0] = b.origin.getX();
                                pier[1] = b.origin.getY();
                                pier[2] = b.origin.getZ();
                                pier[3] = 1;
                            }
                        }
                        if (pier[3] == 1) break;
                    }
                });
                server.runCommand("tp @a " + (isl[0] - 45) + " " + (isl[1] + 40) + " " + (isl[2] - 45) + " -45 35");
                sp.getConnection().waitForChunksRender();
                context.waitTicks(100);
                context.takeScreenshot("island_village");
                // (":passage") a player pays a skipper for a passage, sails with him and is put ashore at the other harbour
                if (spec.contains(":passage")) passage(context, sp, server, spec);
                // a ship at sea, from above: a day or two more till one sets out, then the time for her to get away
                for (int extra = 0; extra < 8; extra++) {
                    boolean[] out = {false};
                    server.runOnServer(s -> out[0] = !VillageData.get(s).voyages().isEmpty());
                    if (out[0]) break;
                    server.runOnServer(s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "village day"));
                    context.waitTicks(20);
                }
                context.waitTicks(20 * 25);
                double[] sea = new double[3];
                server.runOnServer(s -> {
                    VillageData data = VillageData.get(s);
                    for (var t : data.voyages()) {
                        double[] w = Voyages.where(data, t);
                        if (w == null || t.at() < 40) continue;
                        sea[0] = w[0];
                        sea[1] = w[1];
                        sea[2] = 1;
                        break;
                    }
                });
                if (sea[2] == 1) {
                    server.runCommand("tp @a " + (int) (sea[0] - 18) + " 82 " + (int) (sea[1] - 18) + " -45 35");
                    sp.getConnection().waitForChunksRender();
                    context.waitTicks(100);
                    context.takeScreenshot("ship_at_sea");
                    server.runOnServer(s -> log(spec, "ships in sight: {}", s.overworld().getEntitiesOfClass(
                            org.webtrade.minecraftportsmod.vessel.TradeShipEntity.class, s.getPlayerList().getPlayers().getFirst().getBoundingBox().inflate(200)).size()));
                }
                if (pier[3] == 1) {
                    server.runCommand("tp @a " + (pier[0] - 14) + " " + (pier[1] + 10) + " " + (pier[2] - 14) + " -45 25");
                    sp.getConnection().waitForChunksRender();
                    context.waitTicks(100);
                    context.takeScreenshot("island_pier");
                }
            }
        }
    }

    private static void passage(ClientGameTestContext context, TestSingleplayerContext sp, TestServerContext server, String spec) {
        int[] trip = {-1, -1, -1, 0};
        for (int day = 0; day < 20 && trip[0] < 0; day++) {
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                for (Village v : data.all()) {
                    for (var d : v.dwellers()) {
                        if (d.job() != org.webtrade.minecraftportsmod.colony.Job.SAILOR) continue;
                        // (the shortest of all: the cheapest passage, its fare by the length of the way)
                        for (var ps : Voyages.passages(data, v, d)) {
                            if (trip[0] >= 0 && ps.price() >= trip[3]) continue;
                            trip[0] = v.id;
                            trip[1] = d.id;
                            trip[2] = ps.village();
                            trip[3] = ps.price();
                        }
                    }
                }
            });
            if (trip[0] >= 0) break;
            server.runOnServer(s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "village day"));
            context.waitTicks(20);
        }
        if (trip[0] < 0) throw new AssertionError("no skipper to take a passage with");
        log(spec, "passage from #{} with skipper {} to #{} for {} emeralds", trip[0], trip[1], trip[2], trip[3]);
        server.runCommand("gamemode survival @a");
        server.runCommand("effect give @a minecraft:resistance 1000 4 true");
        server.runOnServer(s -> {
            VillageData data = VillageData.get(s);
            Village v = data.get(trip[0]);
            var pl = s.getPlayerList().getPlayers().getFirst();
            var pier = v.buildings().stream().filter(b -> b.type == BuildingType.PIER).findFirst().orElseThrow();
            pl.teleportTo(pier.origin.getX() + 0.5, pier.origin.getY() + 1, pier.origin.getZ() + 0.5);
            pl.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, trip[3] + 5));
        });
        context.waitTicks(60);
        boolean[] ok = {false};
        server.runOnServer(s -> {
            VillageData data = VillageData.get(s);
            Village v = data.get(trip[0]);
            var pl = s.getPlayerList().getPlayers().getFirst();
            ok[0] = Voyages.charter(pl, data, v, v.dweller(trip[1]), trip[2]);
            log(spec, "chartered: {}, riding {}, emeralds left {}", ok[0], pl.getVehicle(), pl.getInventory().countItem(net.minecraft.world.item.Items.EMERALD));
        });
        if (!ok[0]) throw new AssertionError("the passage was refused");
        context.waitTicks(20 * 20);
        context.takeScreenshot("passage_aboard");
        // sailing: till put ashore (at most ten minutes)
        boolean[] ashore = {false};
        for (int k = 0; k < 60 && !ashore[0]; k++) {
            context.waitTicks(200);
            final int kk = k;
            server.runOnServer(s -> {
                var pl = s.getPlayerList().getPlayers().getFirst();
                Village to = VillageData.get(s).get(trip[2]);
                double far = Math.hypot(pl.getX() - to.center.getX(), pl.getZ() - to.center.getZ());
                if (kk % 3 == 0) log(spec, "aboard: {} at {} ({} from the harbour)", pl.getVehicle() != null, pl.blockPosition().toShortString(), (int) far);
                ashore[0] = pl.getVehicle() == null && far < 80;
            });
        }
        context.takeScreenshot("passage_ashore");
        server.runCommand("gamemode spectator @a");
        if (!ashore[0]) throw new AssertionError("the player was not put ashore at the harbour");
    }

    private static int islandSites(net.minecraft.server.MinecraftServer s) {

        int n = 0;
        for (WorldPlan.Site site : WorldPlan.get(s).sites()) if (site.island && site.state() != WorldPlan.State.FAILED) n++;
        return n;
    }

    private static int islands(net.minecraft.server.MinecraftServer s) {
        int n = 0;
        for (Village v : VillageData.get(s).all()) if (v.island()) n++;
        return n;
    }

    /** The islands against the mainland: people, buildings, piers, ships, the trade over the sea. */
    private static void report(net.minecraft.server.MinecraftServer s, String spec, int day) {
        VillageData data = VillageData.get(s);
        int mainPeople = 0, mainBuilt = 0, main = 0, mainPiers = 0;
        StringBuilder isles = new StringBuilder();
        for (Village v : data.all()) {
            int built = 0;
            for (Building b : v.buildings()) if (b.state() == Building.State.BUILT) built++;
            int pierLevel = 0;
            for (Building b : v.buildings()) if (b.type == BuildingType.PIER && b.state() == Building.State.BUILT) pierLevel = b.level();
            if (v.island()) {
                StringBuilder bs = new StringBuilder();
                for (Building b : v.buildings()) bs.append(b.type.id()).append(b.level()).append(b.state() == Building.State.BUILT ? "" : "*").append(' ');
                isles.append(String.format(" | #%d %s: people %d, built %d, pier %d, ships %d, emeralds %d [%s]", v.id, v.name, v.population(), built,
                        pierLevel, Harbour.ships(v), v.emeralds(), bs.toString().trim()));
                isles.append(" sea: ").append(Voyages.status(data, v).getString());
            } else {
                main++;
                mainPeople += v.population();
                mainBuilt += built;
                if (pierLevel > 0) mainPiers++;
            }
        }
        log(spec, "day {}: mainland {} villages, people {} (avg {}), built {} (avg {}), piers {}{}", day, main, mainPeople,
                main == 0 ? 0 : mainPeople / main, mainBuilt, main == 0 ? 0 : mainBuilt / main, mainPiers, isles);
        log(spec, "day {}: sea: voyages {} at sea {}, arrivals {}, stayed {}, ways {} (none {}), sold {}, bought {}", day, Voyages.sailed,
                data.voyages().size(), Voyages.arrivals, Voyages.stayed, data.seaLanes(), data.noSeaLanes(), Voyages.SOLD, Voyages.BOUGHT);
    }
}
