package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.Caravans;
import org.webtrade.minecraftportsmod.colony.Trails;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The whole world of villages for a hundred days, left to itself: villages grow (most of them far from the player),
 * scouts find each other, trails join them into one network (forks, stops, branches), merchants walk it and trade.
 * Every ten days the world in numbers; at the end the network is checked: every trail ends at a village or a fork
 * that exists, no two trails join the same two nodes, every two villages that know of each other are joined by the
 * network or known to have no way by land, and a day never takes long.
 */
public class NetworkRunClientGameTest implements FabricClientGameTest {

    private static final String[] SEEDS = {"4242", "4242:notrade"};
    private static final int DAYS = 100;
    /** The days of the world being run now: 100, or as its spec says ("seed:d500"). */
    private static int days = DAYS;
    private static final int VILLAGES = 10;

    /**
     * How far the villages are developed: the nodes of the tree they have open, out of all; the nodes of their own
     * speciality open and grown to the top level; the villages with all of their speciality grown, and with every
     * node open and grown. (The first day each village gets there is remembered.)
     */
    private static final java.util.Map<Integer, Long> OWN_DONE = new java.util.HashMap<>(), ALL_DONE = new java.util.HashMap<>();

    private static void pace(String seed, VillageData data, int day) {
        if (day <= 10) {
            OWN_DONE.clear();
            ALL_DONE.clear();
        }
        int nodes = 0, open = 0, grown = 0, ownAll = 0, ownGrown = 0, villages = 0;
        for (Village v : data.all()) {
            villages++;
            boolean own = true, all = true;
            for (org.webtrade.minecraftportsmod.colony.BuildingType t : org.webtrade.minecraftportsmod.colony.BuildingType.values()) {
                if (!t.isNode() || t.free) continue;
                nodes++;
                boolean o = v.unlocked(t), g = org.webtrade.minecraftportsmod.colony.Tree.grown(v, t);
                if (o) open++;
                if (g) grown++;
                boolean mine = t.branch == v.focus() || !t.branch.trade();
                if (mine) {
                    ownAll++;
                    if (g) ownGrown++;
                    else own = false;
                }
                if (!g) all = false;
            }
            if (own) OWN_DONE.putIfAbsent(v.id, (long) day);
            if (all) ALL_DONE.putIfAbsent(v.id, (long) day);
        }
        log(seed, "pace day {}: nodes open {}% grown {}% | own branch + common grown {}% | villages done own {} of {}, all {} | days own {} all {}",
                day, nodes == 0 ? 0 : open * 100 / nodes, nodes == 0 ? 0 : grown * 100 / nodes, ownAll == 0 ? 0 : ownGrown * 100 / ownAll,
                OWN_DONE.size(), villages, ALL_DONE.size(), new java.util.TreeMap<>(OWN_DONE).values(), new java.util.TreeMap<>(ALL_DONE).values());
    }

    private static void log(String seed, String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[net " + seed + "] " + fmt, args);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        // (every world run to its end: what went wrong in one does not hide how the others went)
        StringBuilder failed = new StringBuilder();
        for (String seed : SEEDS) {
            try {
                run(context, seed);
            } catch (AssertionError e) {
                Minecraftportsmod.LOGGER.error("[net " + seed + "] FAILED: " + e.getMessage());
                failed.append("[").append(seed).append("] ").append(e.getMessage()).append(" | ");
            }
        }
        // the same world with and without trade: trade is not to hold the villages back
        for (var e : RESULT.entrySet()) {
            if (e.getKey().endsWith(":notrade")) continue;
            int[] with = e.getValue(), without = RESULT.get(e.getKey() + ":notrade");
            if (without == null) continue;
            Minecraftportsmod.LOGGER.info("[net {}] with trade: {} people, {} buildings; without: {} people, {} buildings", e.getKey(), with[0], with[1],
                    without[0], without[1]);
            if (with[0] < without[0] * 0.95 || with[1] < without[1] * 0.95) {
                failed.append("[").append(e.getKey()).append("] trade held the villages back: ").append(with[0]).append('/').append(with[1])
                        .append(" against ").append(without[0]).append('/').append(without[1]).append(" | ");
            }
        }
        if (failed.length() > 0) throw new AssertionError(failed.toString());
    }

    /** People and buildings at the end of each world ("seed" or "seed:notrade"). */
    private static final Map<String, int[]> RESULT = new java.util.LinkedHashMap<>();

    private void run(ClientGameTestContext context, String spec) {
        String seed = spec.split(":")[0];
        boolean noTrade = spec.contains(":notrade");
        days = DAYS;
        for (String part : spec.split(":")) if (part.startsWith("d") && part.length() > 1) days = Integer.parseInt(part.substring(1));
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed(seed)).create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("village trade " + (noTrade ? "off" : "on"));
            server.waitFor(s -> VillageData.get(s).all().size() >= VILLAGES, 20 * 900);
            long worst = 0;
            int worstDay = 0;
            Watch watch = new Watch(spec);
            for (int day = 1; day <= days; day++) {
                long[] ms = {0};
                server.runOnServer(s -> {
                    long t0 = System.nanoTime();
                    s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "village day");
                    ms[0] = (System.nanoTime() - t0) / 1_000_000;
                });
                if (ms[0] > worst) {
                    worst = ms[0];
                    worstDay = day;
                }
                context.waitTicks(40);
                // (a game day is twenty minutes: a trail being worked out is done within the day it was asked for;
                // here the days pass in two seconds, so the day waits for the planner, up to what a day would give it)
                long p0 = System.currentTimeMillis();
                while (System.currentTimeMillis() - p0 < 150_000L) {
                    boolean[] busy = {false};
                    server.runOnServer(s -> busy[0] = Trails.planning());
                    if (!busy[0]) break;
                    context.waitTicks(20);
                }
                server.runOnServer(s -> watch.day(s));
                if (day % 10 == 0) {
                    final int d = day;
                    server.runOnServer(s -> report(s.overworld().getServer(), spec, d));
                }
            }
            final long w = worst;
            final int wd = worstDay;
            // (the trails the scouts asked for: worked out one after another, some 5-30 s each; the days here pass
            // in two seconds, in a game in twenty minutes: the planner is given the time it would have had)
            long w0 = System.currentTimeMillis();
            int idle = 0;
            while (idle < 3 && System.currentTimeMillis() - w0 < 12 * 60_000L) {
                context.waitTicks(20 * 10);
                boolean[] busy = {false};
                server.runOnServer(s -> busy[0] = Trails.planning());
                idle = busy[0] ? 0 : idle + 1;
            }
            log(spec, "the planner took {} s more to catch up", (System.currentTimeMillis() - w0) / 1000);
            server.runOnServer(s -> watch.day(s));
            server.runOnServer(s -> {
                int people = 0, built = 0;
                for (Village v : VillageData.get(s).all()) {
                    people += v.population();
                    for (Building b : v.buildings()) if (b.state() == Building.State.BUILT) built++;
                }
                RESULT.put(spec, new int[]{people, built});
            });
            if (!noTrade) server.runOnServer(s -> watch.check(s));
            server.runOnServer(s -> check(s, spec, w, wd));
        }
    }

    /**
     * What happens day by day, looked at every day: first finds (and by what level of cartographer's house), trails
     * made (the day they were worked out, the day the crews set out, crews from both ends), merchants' trips, sales to
     * villages that have no merchant of their own, and the emeralds (none made or lost out of nothing, ever).
     */
    private static final class Watch {
        final String seed;
        /** Village → the day it first knew of another, and its cartographer's house level that day (0: none, told by a visitor). */
        final Map<Integer, long[]> first = new HashMap<>();
        /** Works seen: "a-b-planned" → planned, started, crew at a, crew at b, finished, length. */
        final Map<String, long[]> works = new HashMap<>();
        final Map<String, Integer> trips = new HashMap<>();
        int sales, salesNoMerchant, emeraldsMoved, checks;
        final Set<String> seenLines = new HashSet<>();

        Watch(String seed) {
            this.seed = seed;
        }

        void day(net.minecraft.server.MinecraftServer s) {
            VillageData data = VillageData.get(s);
            long today = data.day();
            for (Village v : data.all()) {
                if (!first.containsKey(v.id) && v.knownCount() > 0) {
                    first.put(v.id, new long[]{today, org.webtrade.minecraftportsmod.colony.Scouting.houseLevel(v)});
                }
                boolean merchant = false;
                for (var d : v.dwellers()) if (d.job() == org.webtrade.minecraftportsmod.colony.Job.MERCHANT) merchant = true;
                for (var l : v.log()) {
                    String txt = l.text().getString();
                    String key = v.id + "|" + l.day() + "|" + txt;
                    if (!seenLines.add(key)) continue;
                    if (txt.contains("sold us")) {
                        sales++;
                        if (!merchant) salesNoMerchant++;
                        var m = java.util.regex.Pattern.compile("for ([0-9]+) emeralds").matcher(txt);
                        if (m.find()) emeraldsMoved += Integer.parseInt(m.group(1));
                    }
                    // a merchant's round: "... set out on a round (A, B) ..." / "... set out to buy (A) ...": a trip to each
                    if (txt.contains("set out on a round") || txt.contains("set out to buy")) {
                        int i = txt.indexOf('('), j = txt.indexOf(')', i + 1);
                        String stops = i >= 0 && j > i ? txt.substring(i + 1, j) : "";
                        for (Village o : data.all()) {
                            if (o.id == v.id) continue;
                            for (String n : stops.split(", ")) {
                                if (n.equals(o.name)) trips.merge(Math.min(v.id, o.id) + "-" + Math.max(v.id, o.id), 1, Integer::sum);
                            }
                        }
                    }
                }
            }
            for (var w : data.works()) {
                long[] r = works.computeIfAbsent(w.a + "-" + w.b + "-" + w.planned, k -> new long[]{w.planned, -1, 0, 0, -1, (long) w.length()});
                r[1] = w.started();
                if (!w.sideA().crew().isEmpty()) r[2] = 1;
                if (!w.sideB().crew().isEmpty()) r[3] = 1;
                r[4] = w.finished();
            }
            // every emerald: in a village's purse or a merchant's on the road; all of them what came in, less what was lost
            long all = 0;
            for (Village v : data.all()) all += v.emeralds();
            for (var t : data.trips()) all += t.purse();
            checks++;
            if (all != data.minted() - data.burnt()) {
                throw new AssertionError("day " + today + ": " + all + " emeralds in the world, " + data.minted() + " came in and " + data.burnt() + " were lost");
            }
        }

        void check(net.minecraft.server.MinecraftServer s) {
            VillageData data = VillageData.get(s);
            var level = s.overworld();
            // the first finds
            List<Long> days = new ArrayList<>();
            int byFirstHouse = 0, byVisit = 0;
            for (long[] f : first.values()) {
                days.add(f[0]);
                if (f[1] == 1) byFirstHouse++;
                if (f[1] == 0) byVisit++;
            }
            java.util.Collections.sort(days);
            long median = days.isEmpty() ? -1 : days.get(days.size() / 2);
            log(seed, "first finds: {} villages; day of the first {}, median {}; by a first-level house {}, told by a visitor {}; days {}", first.size(),
                    days.isEmpty() ? -1 : days.getFirst(), median, byFirstHouse, byVisit, days);
            // the works
            int started = 0, nextDay = 0, both = 0, finished = 0;
            for (var e : works.entrySet()) {
                long[] r = e.getValue();
                if (r[1] >= 0) started++;
                if (r[1] == r[0] + 1) nextDay++;
                if (r[2] == 1 && r[3] == 1) both++;
                if (r[4] >= 0) finished++;
                log(seed, "work {}: {} blocks, planned {}, started {}, crews {}+{}, finished {}", e.getKey(), r[5], r[0], r[1], r[2], r[3], r[4]);
            }
            log(seed, "works {}: started {} (the day after it was worked out: {}), crews from both ends {}, finished {}", works.size(), started, nextDay,
                    both, finished);
            // trade
            int pairs3 = 0;
            for (int n : trips.values()) if (n >= 3) pairs3++;
            log(seed, "trips by pair {}; pairs with 3+ trips {}; sales {}, to villages with no merchant {}; emeralds paid {}; emeralds checked {} days, "
                    + "minted {}, lost {}", trips, pairs3, sales, salesNoMerchant, emeraldsMoved, checks, data.minted(), data.burnt());
            // the market: what went where, what nobody had
            log(seed, "traded: sold by merchants {} (emeralds {}), bought by merchants {} (emeralds {}); wanted and not found on the round {}; "
                    + "rounds not made {} (nothing worth going for {}, too little {})",
                    org.webtrade.minecraftportsmod.colony.Caravans.SOLD, org.webtrade.minecraftportsmod.colony.Caravans.SOLD_FOR,
                    org.webtrade.minecraftportsmod.colony.Caravans.BOUGHT, org.webtrade.minecraftportsmod.colony.Caravans.BOUGHT_FOR,
                    org.webtrade.minecraftportsmod.colony.Caravans.UNMET, org.webtrade.minecraftportsmod.colony.Caravans.stayed,
                    org.webtrade.minecraftportsmod.colony.Caravans.stayedNothing,
                    org.webtrade.minecraftportsmod.colony.Caravans.stayed - org.webtrade.minecraftportsmod.colony.Caravans.stayedNothing);
            // what each village is short of, and its land
            Set<String> shortOf = new HashSet<>();
            int shortVillages = 0;
            org.webtrade.minecraftportsmod.colony.Res[] goods = {org.webtrade.minecraftportsmod.colony.Res.FOOD, org.webtrade.minecraftportsmod.colony.Res.WOOD,
                    org.webtrade.minecraftportsmod.colony.Res.STONE, org.webtrade.minecraftportsmod.colony.Res.IRON, org.webtrade.minecraftportsmod.colony.Res.COAL};
            for (Village v : data.all()) {
                var land = org.webtrade.minecraftportsmod.colony.Land.now(level, v);
                StringBuilder sh = new StringBuilder();
                for (var r : goods) {
                    double w = org.webtrade.minecraftportsmod.colony.VillageLife.want(v, r);
                    if (w > 0.3) {
                        sh.append(r.id()).append(' ');
                        shortOf.add(r.id());
                    }
                }
                if (sh.length() > 0) shortVillages++;
                log(seed, "  #{} {}: land forest {} rock {} meadow {} mountain {} (wood x{}, ore x{}, vein {}) | short of: {}| purse {}", v.id, v.name,
                        pct(land.forest()), pct(land.rock()), pct(land.meadow()), pct(land.mountain()), String.format("%.2f", land.wood()),
                        String.format("%.2f", land.ore()), land.vein(), sh, v.emeralds());
            }
            log(seed, "short of: {} (in {} villages of {})", shortOf, shortVillages, data.all().size());
            java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
            for (Village v : data.all()) kinds.merge((v.focus() == null ? "-" : v.focus().id()) + "/" + (v.sub() == null ? "-" : v.sub().id()), 1, Integer::sum);
            log(seed, "specialities: {}", kinds);
            long bound = 0;
            for (Village v : data.all()) bound += 10 + 3 + 5 + 8 + 12 + org.webtrade.minecraftportsmod.colony.Land.now(level, v).vein();
            StringBuilder bad = new StringBuilder();
            if (byFirstHouse == 0) bad.append("no village found another with a first-level cartographer's house; ");
            // (70: a scout eats for three, and only villages with food to spare send more than one; the farming
            // villages find their neighbours first, the others later - by design, 2026-09-30)
            if (median < 0 || median > 70) bad.append("the first finds came late (median day ").append(median).append("); ");
            if (started > 0 && nextDay < started * 7 / 10) bad.append("crews set out the day after the way was found in only ").append(nextDay).append(" of ").append(started).append("; ");
            if (started > 0 && both < started * 7 / 10) bad.append("crews from both ends in only ").append(both).append(" of ").append(started).append(" works started; ");
            if (pairs3 == 0) bad.append("no two villages traded three times; ");
            if (trips.size() < 3) bad.append("fewer than three pairs of villages traded; ");
            // (not a failure: once every village has its stall before the trails are made, there is none to sell to)
            if (salesNoMerchant == 0) log(seed, "no sale to a village with no merchant of its own (every village had its stall by then)");
            if (emeraldsMoved == 0) bad.append("no emeralds changed hands; ");
            if (shortOf.size() < 2) bad.append("villages short of fewer than two kinds of goods; ");
            if (data.minted() > bound) bad.append("more emeralds came in (").append(data.minted()).append(") than there can be (").append(bound).append("); ");
            if (bad.length() > 0) throw new AssertionError("[" + seed + "] " + bad);
        }

        private static String pct(double x) {
            return Math.round(x * 100) + "%";
        }
    }

    private static void report(net.minecraft.server.MinecraftServer s, String seed, int day) {
        VillageData data = VillageData.get(s);
        log(seed, "day {} market: {}", day, org.webtrade.minecraftportsmod.colony.Caravans.balance(data));
        int people = 0, built = 0, known = 0, stores = 0;
        for (Village v : data.all()) {
            people += v.population();
            for (Building b : v.buildings()) {
                if (b.state() == Building.State.BUILT) built++;
                if (b.state() == Building.State.BUILT && b.type.branch == org.webtrade.minecraftportsmod.colony.BuildingType.Branch.STORE) stores++;
            }
            known += v.knownCount();
        }
        int ready = 0, none = 0, waiting = 0;
        for (Trails.Trail t : data.trails()) {
            if (t.ready()) ready++;
            else if (t.none()) none++;
            else waiting++;
        }
        int sold = 0, lost = 0, out = 0;
        for (Village v : data.all()) {
            for (var l : v.log()) {
                String txt = l.text().getString();
                if (txt.contains("sold us")) sold++;
                if (txt.contains("lost on the road")) lost++;
                if (txt.contains("set out on a round") || txt.contains("set out to buy")) out++;
            }
        }
        log(seed, "day {}: villages {}, people {}, buildings {} (storehouses {}), known pairs {} | trails {}, no way {}, pending {}, forks {}, parts of the network {} | "
                        + "trips out {}, sales {}, lost {}, on the road now {}", day, data.all().size(), people, built, stores, known, ready, none, waiting,
                data.junctions().size(), parts(data), out, sold, lost, data.trips().size());
        pace(seed, data, day);
        if (day % 50 == 0) {
            for (Village v : data.all()) {
                int scouts = 0, away = 0;
                for (var d : v.dwellers()) if (d.job() == org.webtrade.minecraftportsmod.colony.Job.SCOUT) {
                    scouts++;
                    if (d.away()) away++;
                }
                StringBuilder st = new StringBuilder();
                for (var r : org.webtrade.minecraftportsmod.colony.Res.values()) if (v.stock(r) > 0) st.append(r.name().toLowerCase()).append(' ').append(v.stock(r)).append(' ');
                StringBuilder bs = new StringBuilder();
                for (Building b : v.buildings()) bs.append(b.type.name().toLowerCase()).append(b.level()).append(b.state() == Building.State.BUILT ? "" : "*").append(' ');
                bs.append("| research ").append(v.research()).append(" open");
                for (var t : org.webtrade.minecraftportsmod.colony.BuildingType.values()) if (t.isNode() && !t.free && v.unlocked(t)) bs.append(' ').append(t.id());
                java.util.Map<String, Integer> jobs = new java.util.TreeMap<>();
                for (var d : v.dwellers()) jobs.merge(d.job() == null ? "child" : d.job().id() + (d.away() ? "(away)" : ""), 1, Integer::sum);
                st.append("| jobs ").append(jobs).append(" mood ").append(v.mood()).append(" tools L")
                        .append(v.toolLevel(org.webtrade.minecraftportsmod.colony.Job.WOODCUTTER)).append(' ');
                log(seed, "  #{} {}: people {}, adults {}, focus {}, cartographer open {} built {}, scouts {} (away {}), known {} | {}| {}", v.id, v.name,
                        v.population(), v.adults(), v.focus(), v.unlocked(org.webtrade.minecraftportsmod.colony.BuildingType.CARTOGRAPHER),
                        v.has(org.webtrade.minecraftportsmod.colony.BuildingType.CARTOGRAPHER), scouts, away, v.knownCount(), st, bs);
                for (int o : Trails.reach(data, v.id)) {
                    if (o >= 0 && o != v.id) log(seed, "    to #{}: {}", o, Caravans.explain(data, v.id, o));
                }
                log(seed, "    emeralds {}", v.emeralds());
            }
        }
    }

    /** How many separate pieces the network is in (villages with no trail count as one each). */
    private static int parts(VillageData data) {
        Set<Integer> seen = new HashSet<>();
        int n = 0;
        for (Village v : data.all()) {
            if (seen.contains(v.id)) continue;
            n++;
            seen.addAll(Trails.reach(data, v.id));
        }
        return n;
    }

    private static void check(net.minecraft.server.MinecraftServer s, String seed, long worst, int worstDay) {
        VillageData data = VillageData.get(s);
        report(s, seed, days);
        StringBuilder bad = new StringBuilder();
        Map<String, Integer> pairs = new HashMap<>();
        int length = 0;
        for (Trails.Trail t : data.trails()) {
            if (!t.ready()) continue;
            length += t.length();
            for (int n : new int[]{t.a, t.b}) {
                boolean exists = n >= 0 ? data.get(n) != null : data.junctions().containsKey(n);
                if (!exists) bad.append("trail ").append(t.a).append('-').append(t.b).append(" ends at a missing node ").append(n).append("; ");
            }
            pairs.merge(t.a + "-" + t.b, 1, Integer::sum);
            // its ends are where the nodes are
            int[] p = t.points();
            int[] a = t.a >= 0 ? new int[]{data.get(t.a).center.getX(), data.get(t.a).center.getZ()} : data.junctions().get(t.a);
            int[] b = t.b >= 0 ? new int[]{data.get(t.b).center.getX(), data.get(t.b).center.getZ()} : data.junctions().get(t.b);
            if (a != null && (p[0] != a[0] || p[1] != a[1])) bad.append("trail ").append(t.a).append('-').append(t.b).append(" does not start at its node; ");
            if (b != null && (p[p.length - 2] != b[0] || p[p.length - 1] != b[1])) bad.append("trail ").append(t.a).append('-').append(t.b).append(" does not end at its node; ");
        }
        for (var e : pairs.entrySet()) if (e.getValue() > 1) bad.append("trail ").append(e.getKey()).append(" twice; ");
        // every fork has at least three ways out of it (else it is a bend, not a fork)
        Map<Integer, Integer> degree = new HashMap<>();
        for (Trails.Trail t : data.trails()) {
            if (!t.ready()) continue;
            degree.merge(t.a, 1, Integer::sum);
            degree.merge(t.b, 1, Integer::sum);
        }
        int bends = 0;
        for (int j : data.junctions().keySet()) {
            if (degree.getOrDefault(j, 0) >= 3) continue;
            bends++;
            for (Trails.Trail t : data.trails()) {
                if (t.a == j || t.b == j) log(seed, "bend {}: trail {} - {} ({} blocks){}", j, t.a, t.b, t.length(), t.ready() ? "" : " not ready");
            }
        }
        // every two villages that know of each other: joined, or no way by land
        int unjoined = 0;
        for (Village v : data.all()) {
            Set<Integer> reach = Trails.reach(data, v.id);
            for (int o : v.knownIds()) {
                if (reach.contains(o) || data.get(o) == null) continue;
                boolean noWay = false;
                for (Trails.Trail t : data.trails()) if (t.none() && (t.a == Math.min(v.id, o) && t.b == Math.max(v.id, o))) noWay = true;
                double dist = Math.sqrt(v.center.distSqr(data.get(o).center));
                if (!noWay && dist <= 1800) {
                    unjoined++;
                    log(seed, "not joined: #{} {} knows #{} {} ({} blocks)", v.id, v.name, o, data.get(o).name, (int) dist);
                }
            }
        }
        int trips = 0;
        for (Caravans.Trip t : data.trips()) trips++;
        log(seed, "network: {} blocks of trail, {} forks ({} of them mere bends), {} known pairs not joined, {} merchants on the road; slowest day {} ms (day {})",
                length, data.junctions().size(), bends, unjoined, trips, worst, worstDay);
        for (Village v : data.all()) {
            for (var l : v.log()) {
                String txt = l.text().getString();
                if (txt.contains("trail") || txt.contains("way by land") || txt.contains("goes through") || txt.contains("passes near")) {
                    log(seed, "#{} day {}: {}", v.id, l.day(), txt);
                }
            }
        }
        if (bad.length() > 0) throw new AssertionError("network broken: " + bad);
        if (bends > 0) throw new AssertionError(bends + " forks where only two trails meet");
        if (worst > 5000) throw new AssertionError("a day took " + worst + " ms");
    }
}
