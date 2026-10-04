package org.webtrade.minecraftportsmod.worldgen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.economy.EconomyManager;
import org.webtrade.minecraftportsmod.economy.Settlement;
import org.webtrade.minecraftportsmod.economy.SettlementData;
import org.webtrade.minecraftportsmod.economy.Specialization;
import org.webtrade.minecraftportsmod.fleet.Names;
import org.webtrade.minecraftportsmod.nav.NavCacheManager;
import org.webtrade.minecraftportsmod.port.Port;
import org.webtrade.minecraftportsmod.port.PortData;
import org.webtrade.minecraftportsmod.port.PortService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plans the world's villages and builds them.
 * <ol>
 *   <li>On the first start of a new world, the terrain generator is surveyed (no chunks are generated) around the
 *   spawn: at most one village site per {@value WorldPlan#CELL}-block cell, by the sea or a river. The plan grows
 *   around players as they travel.</li>
 *   <li>Rough water lanes are searched between neighbouring sites, so villages can trade before anyone has
 *   sailed there.</li>
 *   <li>Each site's chunks are then generated in the background (force-loaded), the exact shore is found and the
 *   village is built and registered: port office, berths, settlement.</li>
 * </ol>
 */
public final class WorldPlanner {

    /** Cells planned around the spawn right away: (2r+1)² cells. */
    static final int INITIAL_RADIUS = 3;
    /** Cells kept planned around every player. */
    static final int PLAYER_RADIUS = 2;
    /** Villages being built at the same time. */
    static final int PARALLEL_BUILDS = 1;
    /** Only sites this close to a player (or the spawn) are built; the rest wait until someone comes near. */
    static final int BUILD_RANGE = 2500;
    /** Nothing is built in the first ticks after the server starts: the players' own chunks come first. */
    static final int STARTUP_DELAY = 20 * 30;
    /** Pause between two villages. */
    static final int BUILD_PAUSE = 20 * 5;
    /** No new village is started while the server's average tick is slower than this (ms). */
    static final double BUSY_TICK_MS = 35;
    /** Chunk radius generated to find the shore, and around the shore to build. */
    static final int SEARCH_CHUNKS = 3, BUILD_CHUNKS = 4;
    /** Chunk radius loaded round an island being raised, and its rows raised every second. */
    static final int ISLE_CHUNKS = 7, ISLE_ROWS = 10;
    /** Give up on a site whose chunks don't arrive in this many ticks. */
    static final int JOB_TIMEOUT = 20 * 180;
    /** No village closer than this to a port someone else founded. */
    static final int PORT_CLEARANCE = 200;
    /**
     * Sea lanes between villages (for trade by ship). Off while the villages grow on their own, without trade: the
     * planning of lanes and the prediction of waters along them cost a lot of work in the background.
     */
    static final boolean TRADE_LANES = false;

    private static MinecraftServer server;
    private static ExecutorService worker;
    /** Water prediction runs apart from the planning, on two threads of its own. */
    private static ExecutorService predictor;
    private static SiteSurvey survey;
    private static volatile boolean stopping;
    /** Cells and lanes handed to the worker and not yet back. */
    private static final Set<Long> QUEUED_CELLS = new HashSet<>();
    private static final Set<Long> QUEUED_LANES = new HashSet<>();
    private static final Set<Long> QUEUED_PREDICTIONS = new HashSet<>();
    /** Site positions the worker must keep its distance from (includes sites not yet applied). */
    private static final List<int[]> KNOWN = new CopyOnWriteArrayList<>();
    private static final List<Job> JOBS = new ArrayList<>();
    /** Server tick when the planner started, and when the last village was finished. */
    private static int startedTick, lastFinishTick;

    private WorldPlanner() {
    }

    private enum Phase {RAISE, SEARCH, BUILD, CONSTRUCT}

    private static final class Job {
        final WorldPlan.Site site;
        Phase phase = Phase.SEARCH;
        final List<long[]> forced = new ArrayList<>();
        VillageBuilder.Spot spot;
        int waited;
        // CONSTRUCT: the village going up a piece at a time
        VillageBuilder.Session session;
        VillageBuilder.Survey land;
        Specialization spec;
        RandomSource rnd;
        /** RAISE: the next row of the island to raise. */
        int row;

        Job(WorldPlan.Site site) {
            this.site = site;
        }
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(WorldPlanner::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            stopping = true;
            if (worker != null) worker.shutdownNow();
            worker = null;
            if (predictor != null) predictor.shutdownNow();
            predictor = null;
            for (Job j : JOBS) unforce(srv.overworld(), j);
            JOBS.clear();
            RaisedIslands.clear();
            QUEUED_CELLS.clear();
            QUEUED_LANES.clear();
            QUEUED_PREDICTIONS.clear();
            KNOWN.clear();
            server = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(org.webtrade.minecraftportsmod.Perf.timed("WorldPlanner", WorldPlanner::tick));
    }

    private static void start(MinecraftServer srv) {
        ServerLevel level = srv.overworld();
        WorldPlan plan = WorldPlan.get(srv);
        if (plan.mode == 0) {
            // a fresh world gets villages; a world that was already played in is left alone
            boolean fresh = level.getGameTime() < 20 * 60 * 5 && PortData.get(srv).ports().isEmpty();
            plan.mode = fresh ? 1 : 2;
            plan.lang = Locale.getDefault().getLanguage().startsWith("ru") ? "ru" : "en";
            plan.setDirty();
            Minecraftportsmod.LOGGER.info("World plan: {} (names: {})", fresh ? "villages on" : "existing world, villages off", plan.lang);
        }
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator)) return;
        server = srv;
        stopping = false;
        startedTick = srv.getTickCount();
        lastFinishTick = startedTick;
        survey = new SiteSurvey(level.getChunkSource().getGenerator(), level.getChunkSource().randomState(), level, level.getSeed());
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Ports&Routes-WorldPlanner");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
        predictor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Ports&Routes-WaterPredictor");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
        KNOWN.clear();
        RaisedIslands.clear();
        for (WorldPlan.Site s : plan.sites()) {
            KNOWN.add(new int[]{s.x, s.z});
            if (s.isle != null && s.state != WorldPlan.State.FAILED) RaisedIslands.ISLES.add(s.isle());
        }
        if (plan.enabled()) {
            BlockPos spawn = level.getRespawnData().pos();
            planIslands(plan, spawn.getX(), spawn.getZ());
            planAround(plan, spawn.getX(), spawn.getZ(), INITIAL_RADIUS);
        }
    }

    /** Islands settled round the spawn (at most), and how far from it they are looked for. */
    static final int ISLANDS = 2, ISLAND_RANGE = 2400;

    /**
     * The sea round the spawn searched once for islands big enough for a village; up to {@link #ISLANDS} of them get
     * one (before the cells round them are planned: those keep their distance).
     */
    private static void planIslands(WorldPlan plan, int x, int z) {
        if (plan.islandsSearched || !QUEUED_CELLS.add(Long.MIN_VALUE)) return;
        worker.execute(() -> {
            List<SiteSurvey.Found> found = new ArrayList<>();
            try {
                long t0 = System.currentTimeMillis();
                found = survey.islands(x, z, ISLAND_RANGE, ISLANDS, KNOWN, () -> stopping);
                for (SiteSurvey.Found f : found) KNOWN.add(new int[]{f.x(), f.z()});
                Minecraftportsmod.LOGGER.info("Islands searched in {} s", (System.currentTimeMillis() - t0) / 1000);
            } catch (Throwable t) {
                Minecraftportsmod.LOGGER.error("Searching for islands failed", t);
            }
            final List<SiteSurvey.Found> list = found;
            MinecraftServer srv = server;
            if (srv != null) srv.execute(() -> {
                QUEUED_CELLS.remove(Long.MIN_VALUE);
                if (server == null || stopping) return;
                WorldPlan wp = WorldPlan.get(srv);
                wp.islandsSearched = true;
                wp.setDirty();
                for (SiteSurvey.Found f : list) addSite(wp, f);
            });
        });
    }

    private static void addSite(WorldPlan plan, SiteSurvey.Found found) {
        List<String> taken = new ArrayList<>();
        plan.sites().forEach(s -> taken.add(s.name));
        PortData.get(server).ports().forEach(p -> taken.add(p.name()));
        String name = Names.portName("ru".equals(plan.lang), taken);
        WorldPlan.Site site = new WorldPlan.Site(plan.nextSiteId(), found.x(), found.z(), name, found.river());
        site.island = found.island();
        site.isle = found.isle();
        if (site.isle != null) RaisedIslands.ISLES.add(site.isle());
        plan.addSite(site);
        Minecraftportsmod.LOGGER.info("Planned village {} at {}, {}{}{}", name, found.x(), found.z(), found.river() ? " (river)" : "",
                found.island() ? " (island)" : "");
    }

    /** Turns the world plan on or off (admin). */
    public static void setEnabled(MinecraftServer srv, boolean on) {
        WorldPlan plan = WorldPlan.get(srv);
        plan.mode = on ? 1 : 2;
        plan.setDirty();
        if (on && server != null) {
            BlockPos spawn = srv.overworld().getRespawnData().pos();
            planAround(plan, spawn.getX(), spawn.getZ(), INITIAL_RADIUS);
        }
    }

    public static boolean active() {
        return server != null;
    }

    public static int queuedCells() {
        return QUEUED_CELLS.size();
    }

    public static int queuedLanes() {
        return QUEUED_LANES.size();
    }

    public static int jobs() {
        return JOBS.size();
    }

    // ------------------------------------------------------------------ planning (worker thread)

    private static void planAround(WorldPlan plan, int x, int z, int radius) {
        int ccx = Math.floorDiv(x, WorldPlan.CELL), ccz = Math.floorDiv(z, WorldPlan.CELL);
        // nearest cells first
        List<int[]> cells = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) cells.add(new int[]{ccx + dx, ccz + dz});
        }
        cells.sort(Comparator.comparingInt(c -> Math.abs(c[0] - ccx) + Math.abs(c[1] - ccz)));
        for (int[] c : cells) {
            long key = WorldPlan.cellKey(c[0], c[1]);
            if (plan.cellPlanned(c[0], c[1]) || !QUEUED_CELLS.add(key)) continue;
            final int cx = c[0], cz = c[1];
            worker.execute(() -> {
                SiteSurvey.Found found = null;
                try {
                    found = survey.planCell(cx, cz, KNOWN, () -> stopping);
                    if (found != null) KNOWN.add(new int[]{found.x(), found.z()});
                } catch (Throwable t) {
                    Minecraftportsmod.LOGGER.error("Planning cell {},{} failed", cx, cz, t);
                }
                final SiteSurvey.Found f = found;
                MinecraftServer srv = server;
                if (srv != null) srv.execute(() -> applyCell(cx, cz, f));
            });
        }
    }

    private static void applyCell(int cx, int cz, SiteSurvey.Found found) {
        if (server == null) return;
        QUEUED_CELLS.remove(WorldPlan.cellKey(cx, cz));
        WorldPlan plan = WorldPlan.get(server);
        if (plan.cellPlanned(cx, cz)) return;
        plan.markCell(cx, cz);
        if (found == null) return;
        addSite(plan, found);
    }

    private static void queueLanes(WorldPlan plan) {
        List<WorldPlan.Site> sites = new ArrayList<>();
        for (WorldPlan.Site s : plan.sites()) if (s.state != WorldPlan.State.FAILED) sites.add(s);
        for (int i = 0; i < sites.size(); i++) {
            for (int j = i + 1; j < sites.size(); j++) {
                WorldPlan.Site a = sites.get(i), b = sites.get(j);
                if (Math.hypot(a.x - b.x, a.z - b.z) > SiteSurvey.MAX_LANE) continue;
                long key = WorldPlan.pair(a.id, b.id);
                if (plan.laneSearched(a.id, b.id) || !QUEUED_LANES.add(key)) continue;
                worker.execute(() -> {
                    int[] path = null;
                    try {
                        path = survey.lane(a.x, a.z, b.x, b.z, () -> stopping);
                    } catch (Throwable t) {
                        Minecraftportsmod.LOGGER.error("Lane {} -> {} failed", a.name, b.name, t);
                    }
                    final int[] p = path;
                    MinecraftServer srv = server;
                    if (srv != null) srv.execute(() -> {
                        QUEUED_LANES.remove(key);
                        WorldPlan wp = WorldPlan.get(srv);
                        if (p == null || p.length < 4) wp.addNoLane(a.id, b.id);
                        else wp.addLane(new WorldPlan.Lane(a.id, b.id, p, SiteSurvey.length(p)));
                    });
                });
            }
        }
    }

    // ------------------------------------------------------------------ predicted waters

    public static int queuedPredictions() {
        return QUEUED_PREDICTIONS.size();
    }

    /**
     * Along every lane, the water of chunks nobody has loaded is predicted from the generator and put into the
     * navigation cache; real sea routes between the villages are then found through it (and shown on the chart),
     * without generating those chunks.
     */
    private static void queuePredictions(WorldPlan plan) {
        ServerLevel level = server.overworld();
        var cache = NavCacheManager.get(level);
        for (WorldPlan.Lane lane : plan.lanes()) {
            long key = WorldPlan.pair(lane.siteA(), lane.siteB());
            if (plan.predicted.contains(key) || !QUEUED_PREDICTIONS.add(key)) continue;
            WorldPlan.Site a = plan.site(lane.siteA()), b = plan.site(lane.siteB());
            if (a == null || b == null) continue;
            // from village to village: site, the lane, the other site
            int[] path = new int[lane.path().length + 4];
            path[0] = a.x;
            path[1] = a.z;
            System.arraycopy(lane.path(), 0, path, 2, lane.path().length);
            path[path.length - 2] = b.x;
            path[path.length - 1] = b.z;
            predictor.execute(() -> {
                List<long[]> chunks = new ArrayList<>();
                List<org.webtrade.minecraftportsmod.nav.ChunkNav> navs = new ArrayList<>();
                try {
                    for (long c : SiteSurvey.corridor(path, 1)) {
                        if (stopping) return;
                        int cx = (int) (c >> 32), cz = (int) c;
                        if (cache.getChunk(cx, cz) != null) continue;
                        chunks.add(new long[]{cx, cz});
                        navs.add(survey.predictChunk(cx, cz));
                    }
                } catch (Throwable t) {
                    Minecraftportsmod.LOGGER.error("Predicting waters {} -> {} failed", a.name, b.name, t);
                }
                MinecraftServer srv = server;
                if (srv != null) srv.execute(() -> {
                    QUEUED_PREDICTIONS.remove(key);
                    if (server == null) return;
                    NavCacheManager.putPredicted(srv.overworld(), chunks, navs);
                    WorldPlan wp = WorldPlan.get(srv);
                    wp.predicted.add(key);
                    wp.setDirty();
                    if (a.portId() >= 0 && b.portId() >= 0) {
                        org.webtrade.minecraftportsmod.route.RouteManager.requestRoute(srv, a.portId(), b.portId());
                    }
                    Minecraftportsmod.LOGGER.info("Predicted {} chunks of water between {} and {}", chunks.size(), a.name, b.name);
                });
            });
        }
    }

    /**
     * A way over the open water between two points, worked out in the background on the world's generator; {@code done}
     * gets the points (x, z) on the server thread, or null if the waters don't join. False if it cannot be asked now
     * (no generator to read: a flat world).
     */
    public static boolean seaLane(int ax, int az, int bx, int bz, java.util.function.Consumer<int[]> done) {
        if (server == null || worker == null || survey == null) return false;
        worker.execute(() -> {
            int[] path = null;
            try {
                path = survey.lane(ax, az, bx, bz, () -> stopping);
            } catch (Throwable t) {
                Minecraftportsmod.LOGGER.error("Sea way {},{} -> {},{} failed", ax, az, bx, bz, t);
            }
            final int[] p = path;
            MinecraftServer srv = server;
            if (srv != null) srv.execute(() -> done.accept(p));
        });
        return true;
    }

    /** Debug: what the generator says about a column. */
    public static String probe(int x, int z) {
        if (survey == null) return "no survey";
        return survey.probe(x, z);
    }

    /** Are these two ports villages of the plan with no lane between them (then no route is searched)? */
    public static boolean unconnectedVillages(MinecraftServer srv, int portA, int portB) {
        WorldPlan plan = WorldPlan.get(srv);
        WorldPlan.Site a = siteOf(srv, plan, portA), b = siteOf(srv, plan, portB);
        return a != null && b != null && plan.lane(a.id, b.id) == null;
    }

    /**
     * The plan's site of a port: registered, or (while the village is being registered and the site doesn't
     * know its port yet) the site the port's office stands on.
     */
    private static WorldPlan.Site siteOf(MinecraftServer srv, WorldPlan plan, int portId) {
        WorldPlan.Site s = plan.siteOfPort(portId);
        if (s != null) return s;
        Port port = PortData.get(srv).port(portId);
        if (port == null || port.owner() != null) return null;
        for (JobView j : jobSites()) {
            if (Math.abs(j.x - port.office().getX()) <= 96 && Math.abs(j.z - port.office().getZ()) <= 96) return j.site;
        }
        return null;
    }

    private record JobView(WorldPlan.Site site, int x, int z) {
    }

    private static List<JobView> jobSites() {
        List<JobView> out = new ArrayList<>();
        for (Job j : JOBS) {
            if (j.spot != null) out.add(new JobView(j.site, j.spot.ground().getX(), j.spot.ground().getZ()));
        }
        return out;
    }

    // ------------------------------------------------------------------ building (server thread)

    private static void tick(MinecraftServer srv) {
        if (server == null) return;
        WorldPlan plan = WorldPlan.get(srv);
        if (!plan.enabled()) return;
        int t = srv.getTickCount();
        if (t % 100 == 0) {
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                if (p.level() == srv.overworld()) planAround(plan, p.getBlockX(), p.getBlockZ(), PLAYER_RADIUS);
            }
        }
        if (TRADE_LANES && t % 200 == 50) queueLanes(plan);
        if (TRADE_LANES && t % 200 == 150) queuePredictions(plan);
        ServerLevel level = srv.overworld();
        // a village under construction advances one piece every other tick
        if (t % 2 == 0) {
            for (Job j : new ArrayList<>(JOBS)) {
                if (j.phase == Phase.CONSTRUCT) construct(level, plan, j);
            }
        }
        if (t % 20 == 0) {
            for (Job j : new ArrayList<>(JOBS)) {
                if (j.phase != Phase.CONSTRUCT) advance(level, plan, j);
            }
            boolean settled = t - startedTick > STARTUP_DELAY && t - lastFinishTick > BUILD_PAUSE;
            boolean calm = srv.getAverageTickTimeNanos() / 1_000_000.0 < BUSY_TICK_MS;
            while (settled && calm && JOBS.size() < PARALLEL_BUILDS) {
                WorldPlan.Site next = nextSite(srv, plan);
                if (next == null) break;
                Job j = new Job(next);
                if (next.isle != null && !next.raised) {
                    // an island to raise first: all of it loaded
                    j.phase = Phase.RAISE;
                    force(level, j, next.isle[0], next.isle[1], ISLE_CHUNKS);
                } else {
                    force(level, j, next.x, next.z, SEARCH_CHUNKS);
                }
                JOBS.add(j);
            }
        }
    }

    /** The planned site closest to a player (or the spawn) that isn't being built already. */
    private static WorldPlan.Site nextSite(MinecraftServer srv, WorldPlan plan) {
        List<int[]> anchors = new ArrayList<>();
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) anchors.add(new int[]{p.getBlockX(), p.getBlockZ()});
        BlockPos spawn = srv.overworld().getRespawnData().pos();
        anchors.add(new int[]{spawn.getX(), spawn.getZ()});
        WorldPlan.Site best = null;
        double bestD = Double.MAX_VALUE;
        for (WorldPlan.Site s : plan.sites()) {
            if (s.state != WorldPlan.State.PLANNED) continue;
            boolean busy = false;
            for (Job j : JOBS) if (j.site == s) busy = true;
            if (busy) continue;
            for (int[] a : anchors) {
                double d = Math.hypot(s.x - a[0], s.z - a[1]);
                if (d < bestD && d <= BUILD_RANGE) {
                    bestD = d;
                    best = s;
                }
            }
        }
        return best;
    }

    /**
     * Asks for the chunks around a point to be loaded (generated if needed) in the background. Not
     * {@code setChunkForced}: that one waits for every chunk right there on the server thread, which froze the game
     * for seconds per village.
     */
    private static void force(ServerLevel level, Job j, int x, int z, int radius) {
        ChunkPos center = new ChunkPos(x >> 4, z >> 4);
        level.getChunkSource().addTicketWithRadius(TicketType.PLAYER_LOADING, center, radius);
        j.forced.add(new long[]{center.x(), center.z(), radius});
    }

    private static void unforce(ServerLevel level, Job j) {
        for (long[] c : j.forced) {
            level.getChunkSource().removeTicketWithRadius(TicketType.PLAYER_LOADING, new ChunkPos((int) c[0], (int) c[1]), (int) c[2]);
        }
        j.forced.clear();
    }

    private static boolean loaded(ServerLevel level, int x, int z, int radius) {
        int cx = x >> 4, cz = z >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) return false;
            }
        }
        return true;
    }

    private static void advance(ServerLevel level, WorldPlan plan, Job j) {
        j.waited += 20;
        if (j.waited > JOB_TIMEOUT) {
            Minecraftportsmod.LOGGER.warn("Village {}: chunks did not load in time, will retry later", j.site.name);
            finish(level, j);
            return;
        }
        WorldPlan.Site s = j.site;
        if (j.phase == Phase.RAISE) {
            if (!loaded(level, s.isle[0], s.isle[1], ISLE_CHUNKS)) return;
            RaisedIslands.Isle isle = s.isle();
            long t0 = System.nanoTime();
            RaisedIslands.raise(level, isle, j.row, j.row + ISLE_ROWS);
            org.webtrade.minecraftportsmod.Perf.report("raise " + s.name, t0);
            j.row += ISLE_ROWS;
            j.waited = 0;
            if (j.row < RaisedIslands.rows(isle)) return;
            s.raised = true;
            plan.setDirty();
            Minecraftportsmod.LOGGER.info("Island raised for {} at {}, {}", s.name, s.isle[0], s.isle[1]);
            j.phase = Phase.SEARCH;
            force(level, j, s.x, s.z, SEARCH_CHUNKS);
            return;
        }
        if (j.phase == Phase.SEARCH) {
            if (!loaded(level, s.x, s.z, SEARCH_CHUNKS)) return;
            long t0 = System.nanoTime();
            j.spot = VillageBuilder.findSpot(level, s.x, s.z, 40);
            org.webtrade.minecraftportsmod.Perf.report("findSpot " + s.name, t0);
            if (j.spot == null) {
                fail(level, plan, j, "no shore to build on");
                return;
            }
            j.phase = Phase.BUILD;
            j.waited = 0;
            force(level, j, j.spot.ground().getX(), j.spot.ground().getZ(), BUILD_CHUNKS);
            return;
        }
        BlockPos g = j.spot.ground();
        if (!loaded(level, g.getX(), g.getZ(), BUILD_CHUNKS)) return;
        Port near = PortData.get(level.getServer()).nearestPort(level.dimension(), g, PORT_CLEARANCE);
        if (near != null) {
            fail(level, plan, j, "too close to port " + near.name());
            return;
        }
        try {
            long t0 = System.nanoTime();
            camp(level, plan, j);
            org.webtrade.minecraftportsmod.Perf.report("camp " + s.name, t0);
            finish(level, j);
        } catch (Throwable t) {
            Minecraftportsmod.LOGGER.error("Building village {} failed", s.name, t);
            plan.setState(s, WorldPlan.State.FAILED, -1);
            finish(level, j);
        }
    }

    /** One piece of construction; when the village stands, it is registered. */
    private static void construct(ServerLevel level, WorldPlan plan, Job j) {
        try {
            long t0 = System.nanoTime();
            boolean done = j.session.step();
            org.webtrade.minecraftportsmod.Perf.report("construct " + j.site.name, t0);
            if (!done) return;
            t0 = System.nanoTime();
            register(level, plan, j);
            org.webtrade.minecraftportsmod.Perf.report("register " + j.site.name, t0);
        } catch (Throwable t) {
            Minecraftportsmod.LOGGER.error("Building village {} failed", j.site.name, t);
            plan.setState(j.site, WorldPlan.State.FAILED, -1);
        }
        finish(level, j);
    }

    private static void fail(ServerLevel level, WorldPlan plan, Job j, String why) {
        Minecraftportsmod.LOGGER.info("Village site {} at {}, {} dropped: {}", j.site.name, j.site.x, j.site.z, why);
        plan.setState(j.site, WorldPlan.State.FAILED, -1);
        finish(level, j);
    }

    private static void finish(ServerLevel level, Job j) {
        unforce(level, j);
        JOBS.remove(j);
        lastFinishTick = level.getServer().getTickCount();
    }

    /**
     * A village starts as a camp by the water: a campfire, two tents, three people. It grows from there by itself
     * (see the colony package).
     */
    private static void camp(ServerLevel level, WorldPlan plan, Job j) {
        WorldPlan.Site s = j.site;
        VillageBuilder.Survey land = VillageBuilder.survey(level, j.spot.ground(), 32);
        var v = org.webtrade.minecraftportsmod.colony.VillageManager.foundCamp(level, j.spot.ground(), j.spot.toWater(), s.name,
                "ru".equals(plan.lang), land.buildingWood(), s.island);
        plan.setState(s, WorldPlan.State.BUILT, -1);
        Minecraftportsmod.LOGGER.info("Village {} (#{}) set up camp at {}{}", s.name, v.id, v.center.toShortString(), s.island ? " (island)" : "");

    }

    /** Reads the land, picks the trade and sets up the construction (the old, ready-made villages with a port). */
    private static void begin(ServerLevel level, WorldPlan plan, Job j) {
        MinecraftServer srv = level.getServer();
        WorldPlan.Site s = j.site;
        RandomSource rnd = RandomSource.create(level.getSeed() ^ (s.id * 0x5DEECE66DL));
        VillageBuilder.Survey land = VillageBuilder.survey(level, j.spot.ground(), 40);

        // what the nearest villages already do: neighbours tend to differ
        Map<Specialization, Integer> nearby = new EnumMap<>(Specialization.class);
        SettlementData settlements = SettlementData.get(srv);
        plan.sites().stream()
                .filter(o -> o.state == WorldPlan.State.BUILT && o != s)
                .sorted(Comparator.comparingDouble(o -> Math.hypot(o.x - s.x, o.z - s.z)))
                .limit(3)
                .forEach(o -> {
                    Settlement st = settlements.get(o.portId);
                    if (st != null) nearby.merge(st.spec(), 1, Integer::sum);
                });
        Specialization spec = VillageBuilder.chooseSpec(land, nearby, s.river, rnd);
        int houses = EconomyManager.startingHouses(spec);
        j.land = land;
        j.spec = spec;
        j.rnd = rnd;
        j.session = new VillageBuilder.Session(level, j.spot, spec, land, houses, rnd);
    }

    /** The village stands: port, berths, settlement and people. */
    private static void register(ServerLevel level, WorldPlan plan, Job j) {
        MinecraftServer srv = level.getServer();
        WorldPlan.Site s = j.site;
        Specialization spec = j.spec;
        VillageBuilder.Survey land = j.land;
        RandomSource rnd = j.rnd;
        VillageBuilder.Built built = j.session.result();
        NavCacheManager.scanLoadedArea(level, built.office(), BUILD_CHUNKS);
        Port port = PortService.createPort(level, built.office(), null, s.name);
        PortService.addBerth(level, port);
        PortService.addBerth(level, port);
        if (PortData.get(srv).docksOf(port.id()).size() < 2) pierBerths(level, port, built);
        Settlement settlement = EconomyManager.found(srv, port, spec, land.woodsByAmount(), built.houses());
        if (settlement != null) {
            settlement.setLayout(built.layout());
            org.webtrade.minecraftportsmod.village.Residents.spawn(level, settlement,
                    org.webtrade.minecraftportsmod.village.Residents.MAX_VISIBLE, "ru".equals(plan.lang), rnd);
        }
        plan.setState(s, WorldPlan.State.BUILT, port.id());
        for (WorldPlan.Lane lane : plan.lanes()) {
            if (lane.siteA() != s.id && lane.siteB() != s.id) continue;
            WorldPlan.Site other = plan.site(lane.siteA() == s.id ? lane.siteB() : lane.siteA());
            if (other != null && other.portId() >= 0 && plan.predicted.contains(WorldPlan.pair(lane.siteA(), lane.siteB()))) {
                org.webtrade.minecraftportsmod.route.RouteManager.requestRoute(srv, port.id(), other.portId());
            }
        }
        Minecraftportsmod.LOGGER.info("Built village {} ({}) at {} with {} houses, {} berths", s.name, spec.id(),
                built.office().toShortString(), built.houses(), PortData.get(srv).docksOf(port.id()).size());
    }

    /**
     * Narrow waters (a river) leave the berth planner no room: tie up along the pier end instead, locked in
     * place so they don't wander.
     */
    private static void pierBerths(ServerLevel level, Port port, VillageBuilder.Built built) {
        PortData data = PortData.get(level.getServer());
        int y = level.getSeaLevel() - 1;
        Direction out = built.out(), side = out.getClockWise();
        BlockPos tip = built.pierTip().atY(y);
        BlockPos[] candidates = {tip.relative(side, 3), tip.relative(side, -3), tip.relative(out, 2), tip.relative(side, 3).relative(out, -3)};
        for (BlockPos c : candidates) {
            if (data.docksOf(port.id()).size() >= 2) break;
            if (!level.getBlockState(c).is(net.minecraft.world.level.block.Blocks.WATER)) continue;
            boolean clash = false;
            for (var d : data.docksOf(port.id())) if (d.berth().distManhattan(c) < 4) clash = true;
            if (clash) continue;
            var dock = data.createDock(port.id(), c);
            data.setLocked(dock, true);
        }
    }

    // ------------------------------------------------------------------ lanes for the economy

    /** The rough lane between two ports' villages as sailed from {@code fromPort}, or null. */
    public static int[] lanePath(MinecraftServer srv, int fromPort, int toPort) {
        WorldPlan plan = WorldPlan.get(srv);
        WorldPlan.Site a = plan.siteOfPort(fromPort), b = plan.siteOfPort(toPort);
        if (a == null || b == null) return null;
        WorldPlan.Lane lane = plan.lane(a.id, b.id);
        return lane == null ? null : lane.pathFrom(a.id);
    }

    public static double laneLength(MinecraftServer srv, int portA, int portB) {
        WorldPlan plan = WorldPlan.get(srv);
        WorldPlan.Site a = plan.siteOfPort(portA), b = plan.siteOfPort(portB);
        if (a == null || b == null) return -1;
        WorldPlan.Lane lane = plan.lane(a.id, b.id);
        return lane == null ? -1 : lane.length();
    }
}
