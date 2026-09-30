package org.webtrade.minecraftportsmod.route;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.nav.ChunkNav;
import org.webtrade.minecraftportsmod.nav.DimensionNavCache;
import org.webtrade.minecraftportsmod.nav.NavCacheManager;
import org.webtrade.minecraftportsmod.nav.WaterPathfinder;
import org.webtrade.minecraftportsmod.port.Port;
import org.webtrade.minecraftportsmod.port.PortData;
import org.webtrade.minecraftportsmod.port.PortService;
import org.webtrade.minecraftportsmod.port.Route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps sea routes between ports fresh.
 * <ul>
 *     <li>Every pair of ports in the same dimension within {@link #MAX_ROUTE_DISTANCE} gets a route.</li>
 *     <li>Routes are computed on a single background thread, one at a time, and applied on the server thread.</li>
 *     <li>When water disappears on a route's path (someone built a dam), the route is re-validated and
 *     recalculated right away.</li>
 *     <li>When new water appears near a route (a dug canal, or simply newly explored chunks), the route is
 *     recalculated in the background and replaced only if the new one is noticeably shorter.</li>
 *     <li>Port pairs without a connection are retried when new water shows up in between.</li>
 * </ul>
 */
public final class RouteManager {

    /** Ports further apart than this (straight line) are not connected automatically. */
    public static final double MAX_ROUTE_DISTANCE = 20000;
    /** A recalculated route replaces the current one only if it is at least this much shorter. */
    private static final double IMPROVEMENT_RATIO = 0.97;
    private static final int PROCESS_INTERVAL_TICKS = 40;
    private static final long IMPROVE_COOLDOWN_TICKS = 20 * 60;
    private static final long RETRY_COOLDOWN_TICKS = 20 * 120;

    private enum Reason {
        /** Nothing usable exists yet, or it is blocked: highest priority. */
        REQUIRED(0),
        /** Manual request from a command. */
        MANUAL(1),
        /** An existing route might get shorter. */
        IMPROVE(2);

        final int priority;

        Reason(int priority) {
            this.priority = priority;
        }
    }

    private record Job(long routeKey, int portA, int portB, Reason reason, long enqueuedTick) {
    }

    private record JobResult(Job job, ResourceKey<Level> dimension, WaterPathfinder.Result result, long millis) {
    }

    private static MinecraftServer server;
    private static ExecutorService worker;
    private static final PriorityQueue<Job> QUEUE = new PriorityQueue<>((a, b) -> a.reason.priority != b.reason.priority
            ? Integer.compare(a.reason.priority, b.reason.priority) : Long.compare(a.enqueuedTick, b.enqueuedTick));
    private static final Set<Long> QUEUED = new HashSet<>();
    /** Recalculations that were requested while the route was cooling down: route key -> (reason, ready tick). */
    private static final Map<Long, Deferred> DEFERRED = new HashMap<>();

    private record Deferred(Reason reason, long readyTick) {
    }
    private static final ConcurrentLinkedQueue<JobResult> RESULTS = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile boolean shuttingDown;
    private static Job running;

    /** Chunks whose water changed since the last processing pass, per dimension; value = bitmask 1 gained, 2 lost. */
    private static final Map<ResourceKey<Level>, Map<Long, Integer>> CHANGED = new HashMap<>();

    private RouteManager() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(RouteManager::onStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> onStopping());
        ServerTickEvents.END_SERVER_TICK.register(org.webtrade.minecraftportsmod.Perf.timed("RouteManager", RouteManager::tick));
        NavCacheManager.addListener(RouteManager::onNavChanged);
    }

    private static void onStarted(MinecraftServer srv) {
        server = srv;
        shuttingDown = false;
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Ports&Routes-Router");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
        // make sure every pair has a route entry, and re-queue anything not usable
        PortData data = PortData.get(srv);
        for (Port p : data.ports()) {
            ensureRoutes(data, p, Reason.REQUIRED);
        }
    }

    private static void onStopping() {
        shuttingDown = true;
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        QUEUE.clear();
        QUEUED.clear();
        DEFERRED.clear();
        RESULTS.clear();
        CHANGED.clear();
        BUSY.set(false);
        running = null;
        server = null;
    }

    // ------------------------------------------------------------------ public hooks

    public static void onPortChanged(MinecraftServer srv, Port port) {
        PortData data = PortData.get(srv);
        // a moved anchorage invalidates this port's routes
        for (Route r : data.routes()) {
            if (r.connects(port.id())) {
                data.setRouteStatus(r, r.isUsable() ? Route.Status.BLOCKED : r.status());
            }
        }
        ensureRoutes(data, port, Reason.REQUIRED);
    }

    public static void onPortRemoved(int portId) {
        QUEUE.removeIf(j -> {
            boolean hit = j.portA == portId || j.portB == portId;
            if (hit) QUEUED.remove(j.routeKey);
            return hit;
        });
    }

    /** Admin: recalculate one route or all routes of a port (portB < 0). Returns number queued. */
    public static int requestRecalculation(MinecraftServer srv, int portA, int portB) {
        PortData data = PortData.get(srv);
        int n = 0;
        for (Route r : new ArrayList<>(data.routes())) {
            boolean match = portB < 0 ? (portA < 0 || r.connects(portA)) : r.key() == Route.key(portA, portB);
            if (match && enqueue(r, Reason.MANUAL)) n++;
        }
        return n;
    }

    public static int queuedJobs() {
        return QUEUE.size() + (BUSY.get() ? 1 : 0);
    }

    public static boolean isCalculating(Route r) {
        Job j = running;
        return QUEUED.contains(r.key()) || (j != null && j.routeKey == r.key());
    }

    private static void ensureRoutes(PortData data, Port port, Reason reason) {
        for (Port other : data.ports(port.dimension())) {
            if (other.id() == port.id()) continue;
            if (port.horizontalDistance(other) > MAX_ROUTE_DISTANCE) continue;
            // two villages of the world plan only look for a route along a lane (neighbours by water)
            if (server != null && org.webtrade.minecraftportsmod.worldgen.WorldPlanner.unconnectedVillages(server, port.id(), other.id())) continue;
            Route r = data.getOrCreateRoute(port.id(), other.id());
            if (!r.isUsable()) enqueue(r, reason);
        }
    }

    /** Asks for the route between two ports to be (re)calculated now. */
    public static void requestRoute(MinecraftServer srv, int portA, int portB) {
        PortData data = PortData.get(srv);
        if (data.port(portA) == null || data.port(portB) == null) return;
        Route r = data.getOrCreateRoute(portA, portB);
        DEFERRED.remove(r.key());
        enqueue(r, Reason.REQUIRED);
    }

    private static boolean enqueue(Route r, Reason reason) {
        if (server == null) return false;
        if (!QUEUED.add(r.key())) return false;
        QUEUE.add(new Job(r.key(), r.portA(), r.portB(), reason, server.getTickCount()));
        return true;
    }

    // ------------------------------------------------------------------ change tracking

    private static void onNavChanged(ServerLevel level, int chunkX, int chunkZ, ChunkNav.WaterDelta delta, boolean firstScan) {
        int bits = switch (delta) {
            case GAINED -> 1;
            case LOST -> 2;
            case BOTH -> 3;
            case NONE -> 0;
        };
        if (bits == 0) return;
        CHANGED.computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .merge(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL), bits, (a, b) -> a | b);
    }

    private static void processChanges(MinecraftServer srv) {
        if (CHANGED.isEmpty()) return;
        PortData data = PortData.get(srv);
        long now = srv.getTickCount();

        for (Map.Entry<ResourceKey<Level>, Map<Long, Integer>> e : CHANGED.entrySet()) {
            ServerLevel level = srv.getLevel(e.getKey());
            if (level == null) continue;
            Map<Long, Integer> chunks = e.getValue();
            if (chunks.isEmpty()) continue;

            for (Port p : data.ports(e.getKey())) {
                if (!touches(chunks, p.office(), PortService.ANCHORAGE_RADIUS + 16, 3)) continue;
                // anchorage went dry (or there was none yet)
                if ((p.anchorage() == null || !PortService.anchorageStillValid(level, p))
                        && PortService.refreshAnchorage(level, p)) {
                    onPortChanged(srv, p);
                }
                // berths next to a new pier / filled shore move away; a port without berths gets its first one
                PortService.revalidateBerths(level, p);
                if (p.anchorage() != null && data.docksOf(p.id()).isEmpty()) {
                    PortService.addBerth(level, p);
                }
            }

            WaterPathfinder validator = new WaterPathfinder(NavCacheManager.get(level));
            for (Route r : new ArrayList<>(data.routes())) {
                Port a = data.port(r.portA());
                Port b = data.port(r.portB());
                if (a == null || b == null || !a.dimension().equals(e.getKey())) continue;

                if (r.isUsable()) {
                    int[] bounds = r.bounds();
                    // 1) lost water on the path -> validate now, recalc immediately if broken
                    if (anyInBox(chunks, bounds, 2, 2) && !validator.pathStillValid(r.waypoints())) {
                        data.setRouteStatus(r, Route.Status.BLOCKED);
                        enqueue(r, Reason.REQUIRED);
                        continue;
                    }
                    // 2) gained water near the path -> maybe a shortcut
                    int margin = (int) Math.min(512, Math.max(64, r.length() * 0.3));
                    if (anyInBox(chunks, bounds, margin, 1)) {
                        requestAfterCooldown(r, Reason.IMPROVE, IMPROVE_COOLDOWN_TICKS, now);
                    }
                } else if (r.status() != Route.Status.PENDING) {
                    // 3) no connection yet -> retry when new water appears; the connecting water may be anywhere
                    //    (a long river), so any new water in this dimension counts
                    if (anyGained(chunks)) {
                        requestAfterCooldown(r, Reason.REQUIRED, RETRY_COOLDOWN_TICKS, now);
                    }
                }
            }
            chunks.clear();
        }
    }

    /** Queues now if the route is not cooling down, otherwise remembers the request until it is. */
    private static void requestAfterCooldown(Route r, Reason reason, long cooldown, long now) {
        long ready = r.lastAttemptTick() + cooldown;
        if (now >= ready) {
            DEFERRED.remove(r.key());
            enqueue(r, reason);
        } else {
            DEFERRED.merge(r.key(), new Deferred(reason, ready),
                    (a, b) -> new Deferred(a.reason.priority <= b.reason.priority ? a.reason : b.reason, Math.min(a.readyTick, b.readyTick)));
        }
    }

    private static void releaseDeferred(MinecraftServer srv) {
        if (DEFERRED.isEmpty()) return;
        PortData data = PortData.get(srv);
        long now = srv.getTickCount();
        DEFERRED.entrySet().removeIf(e -> {
            if (now < e.getValue().readyTick) return false;
            Route r = null;
            for (Route candidate : data.routes()) {
                if (candidate.key() == e.getKey()) {
                    r = candidate;
                    break;
                }
            }
            if (r != null) enqueue(r, e.getValue().reason);
            return true;
        });
    }

    private static boolean anyGained(Map<Long, Integer> chunks) {
        for (int bits : chunks.values()) {
            if ((bits & 1) != 0) return true;
        }
        return false;
    }

    private static boolean touches(Map<Long, Integer> chunks, BlockPos center, int radius, int mask) {
        int[] box = {center.getX(), center.getZ(), center.getX(), center.getZ()};
        return anyInBox(chunks, box, radius, mask);
    }

    private static boolean anyInBox(Map<Long, Integer> chunks, int[] box, int margin, int mask) {
        if (box == null) return false;
        int minCx = (box[0] - margin) >> 4, minCz = (box[1] - margin) >> 4;
        int maxCx = (box[2] + margin) >> 4, maxCz = (box[3] + margin) >> 4;
        for (Map.Entry<Long, Integer> c : chunks.entrySet()) {
            if ((c.getValue() & mask) == 0) continue;
            int cx = (int) (c.getKey() >> 32);
            int cz = (int) (long) c.getKey();
            if (cx >= minCx && cx <= maxCx && cz >= minCz && cz <= maxCz) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ job pump

    private static void tick(MinecraftServer srv) {
        if (server == null) return;

        JobResult res;
        while ((res = RESULTS.poll()) != null) {
            apply(srv, res);
        }

        if (srv.getTickCount() % PROCESS_INTERVAL_TICKS == 0) {
            processChanges(srv);
            releaseDeferred(srv);
        }

        if (!BUSY.get() && !QUEUE.isEmpty() && worker != null) {
            Job job = QUEUE.poll();
            QUEUED.remove(job.routeKey);
            startJob(srv, job);
        }
    }

    private static void startJob(MinecraftServer srv, Job job) {
        PortData data = PortData.get(srv);
        Port a = data.port(job.portA);
        Port b = data.port(job.portB);
        Route route = data.route(job.portA, job.portB);
        if (a == null || b == null || route == null) return;
        ServerLevel level = srv.getLevel(a.dimension());
        if (level == null) return;

        route.markAttempt(srv.getTickCount());

        // anchorages are picked on the server thread (needs live chunks for a fresh scan)
        for (Port p : new Port[]{a, b}) {
            if (!PortService.anchorageStillValid(level, p)) PortService.refreshAnchorage(level, p);
        }
        if (a.anchorage() == null || b.anchorage() == null) {
            if (route.status() != Route.Status.OK || job.reason != Reason.IMPROVE) {
                data.setRouteStatus(route, Route.Status.NO_PATH);
            }
            return;
        }
        if (route.status() == Route.Status.NO_PATH && job.reason == Reason.REQUIRED && route.waypoints().length == 0) {
            data.setRouteStatus(route, Route.Status.PENDING);
        }

        DimensionNavCache cache = NavCacheManager.get(level);
        BlockPos sa = a.anchorage();
        BlockPos sb = b.anchorage();
        ResourceKey<Level> dim = a.dimension();

        BUSY.set(true);
        running = job;
        worker.execute(() -> {
            long t0 = System.currentTimeMillis();
            WaterPathfinder.Result result;
            try {
                result = new WaterPathfinder(cache).findRoute(sa.getX(), sa.getZ(), sb.getX(), sb.getZ(), () -> shuttingDown);
            } catch (Throwable t) {
                Minecraftportsmod.LOGGER.error("Route calculation {} <-> {} crashed", job.portA, job.portB, t);
                result = new WaterPathfinder.Result(WaterPathfinder.Status.NO_PATH, new int[0], 0, 0);
            }
            RESULTS.add(new JobResult(job, dim, result, System.currentTimeMillis() - t0));
            running = null;
            BUSY.set(false);
        });
    }

    private static void apply(MinecraftServer srv, JobResult res) {
        PortData data = PortData.get(srv);
        Route route = data.route(res.job.portA, res.job.portB);
        if (route == null || data.port(res.job.portA) == null || data.port(res.job.portB) == null) return;
        WaterPathfinder.Result r = res.result;
        ServerLevel level = srv.getLevel(res.dimension);
        long time = level == null ? 0 : level.getGameTime();

        String label = data.port(res.job.portA).name() + " <-> " + data.port(res.job.portB).name();
        if (r.found()) {
            if (res.job.reason == Reason.IMPROVE && route.isUsable() && r.length() >= route.length() * IMPROVEMENT_RATIO) {
                return; // not worth switching
            }
            boolean wasUsable = route.isUsable();
            double oldLength = route.length();
            data.updateRoute(route, Route.Status.OK, r.waypoints(), r.length(), time);
            if (wasUsable) {
                Minecraftportsmod.LOGGER.info("Route {} updated: {} -> {} blocks ({} nodes, {} ms)",
                        label, Math.round(oldLength), Math.round(r.length()), r.expanded(), res.millis);
            } else {
                Minecraftportsmod.LOGGER.info("Route {} found: {} blocks, {} legs ({} nodes, {} ms)",
                        label, Math.round(r.length()), r.waypoints().length / 2 - 1, r.expanded(), res.millis);
            }
        } else if (r.status() == WaterPathfinder.Status.CANCELLED) {
            enqueue(route, res.job.reason);
        } else {
            if (res.job.reason == Reason.IMPROVE && route.isUsable()) return;
            data.setRouteStatus(route, Route.Status.NO_PATH);
            Minecraftportsmod.LOGGER.info("Route {}: no water connection yet ({}, {} nodes, {} ms)",
                    label, r.status(), r.expanded(), res.millis);
        }
    }

    /** Collects the routes that currently touch a port, for UI. */
    public static List<Route> routesOf(PortData data, int portId) {
        List<Route> list = new ArrayList<>();
        for (Route r : data.routes()) {
            if (r.connects(portId)) list.add(r);
        }
        return list;
    }
}
