package org.webtrade.minecraftportsmod.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The ways between villages: one network of trails, not a trail from every village to every other. A village whose
 * scout has found another is joined to it by a trail to the nearest point of the network that leads there (a trail
 * that branches off one already trodden, at a fork), or to the village itself if no way leads there yet. A village
 * already joined to the other by the network gets no new trail. The network's nodes are the villages (their ids) and
 * the forks (negative ids); its edges are the trails between them. A merchant walks it fork by fork.
 * <p>
 * A trail is found the way people would tread it, on the land as the world's generator makes it (so without loading
 * a single chunk, on a thread of its own): A* on a 4-block grid where every step costs its length and more for what
 * makes walking hard. Climbing costs by the square of the height climbed, so hills are gone round and mountains are
 * never crossed; a step next to a steep slope costs extra, so the way does not cling to the foot of a mountain either;
 * the cost wavers a little over the land, so on open plains the trail bends as trodden ways do instead of running
 * ruler-straight. Water is crossed only where it is a river or a narrow stretch (a bridge, and a costly one): the sea,
 * a wide lake and long crossings are no way at all.
 * <p>
 * Once found, the trail is laid in the world bit by bit, wherever the land is loaded: a trodden path two blocks wide,
 * and a plank bridge with rails over the water.
 */
public final class Trails {

    /** The grid the way is looked for on. */
    static final int GRID = 4;
    /** A village is joined by trails to this many of the villages it knows, the nearest. */
    static final int NEIGHBOURS = 3;
    /** Villages farther apart than this (straight) are not joined by a trail. */
    static final int MAX_REACH = 1800;
    /** The trail is not laid this near a village's middle: the village's own paths are there. */
    static final int VILLAGE_EDGE = 40;
    /** A trail goes through a village it passes this near (its middle): a stop on the way, a node of the network. */
    static final int THROUGH = 45;
    /** A village the land round which (this far) a trail is looked for over is walked through gladly (it costs less). */
    static final int DRAW = 60;
    /** A village this near a new trail, and not on the network yet, is joined to it by a trail of its own. */
    static final int NOTICE = 150;
    /** The longest crossing of water (blocks) a trail may bridge. */
    static final int MAX_BRIDGE = 40;
    private static final int MAX_NODES = 120_000;
    /** The search leans toward the goal this much (1: exact; a little more is much faster and hardly longer). */
    private static final double HEURISTIC = 1.3;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** Searches at once. */
    static final int THREADS = 3;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, r -> {
        Thread t = new Thread(r, "village-trails");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /**
     * A trail worked out: its points (from the village to the point of the network it reaches), which of the goals it
     * reached, the crossings of water on it and how long it took (all off the server thread).
     */
    private record Found(int[] points, int start, int goal, int bridges, long seconds) {
    }

    /** What a search set out to reach: the points of the network leading to the village found, then the village. */
    private record Aim(int village, int other, int[] starts, int[] goals) {
    }

    private static final Map<Integer, Aim> AIMS = new ConcurrentHashMap<>();

    private static final Map<Long, Future<Found>> PLANNING = new ConcurrentHashMap<>();

    private Trails() {
    }

    /** A trail between two villages: its points (x, z, every GRID blocks or so), and which stretches are laid. */
    public static final class Trail {
        static final Codec<Trail> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("a").forGetter(t -> t.a),
                Codec.INT.fieldOf("b").forGetter(t -> t.b),
                Codec.INT.listOf().optionalFieldOf("points", List.of()).forGetter(t -> toList(t.points)),
                Codec.LONG.listOf().optionalFieldOf("laid", List.of()).forGetter(t -> {
                    List<Long> l = new ArrayList<>();
                    for (long w : t.laid) l.add(w);
                    return l;
                }),
                Codec.BOOL.optionalFieldOf("none", false).forGetter(t -> t.none),
                Codec.INT.optionalFieldOf("bridges", 0).forGetter(t -> t.bridges),
                Codec.BOOL.optionalFieldOf("building", false).forGetter(t -> t.building),
                Codec.LONG.listOf().optionalFieldOf("built", List.of()).forGetter(t -> {
                    List<Long> l = new ArrayList<>();
                    for (long w : t.built) l.add(w);
                    return l;
                })
        ).apply(i, (a, b, points, laid, none, bridges, building, built) -> {
            Trail t = new Trail(a, b);
            t.points = points.stream().mapToInt(Integer::intValue).toArray();
            t.laid = laid.stream().mapToLong(Long::longValue).toArray();
            t.none = none;
            t.bridges = bridges;
            t.building = building;
            t.built = built.stream().mapToLong(Long::longValue).toArray();
            return t;
        }));

        public final int a, b;
        int[] points = new int[0];
        long[] laid = new long[0];
        /** Looked for, and there is no way by land. */
        boolean none;
        int bridges;
        /**
         * Still being made by the villages' crews: only the stretches in {@link #built} are cut and trodden (a trail
         * from before there were crews, or a finished one, is all made).
         */
        boolean building;
        long[] built = new long[0];

        Trail(int a, int b) {
            this.a = Math.min(a, b);
            this.b = Math.max(a, b);
        }

        public boolean ready() {
            return points.length >= 4;
        }

        public boolean none() {
            return none;
        }

        public int bridges() {
            return bridges;
        }

        public int[] points() {
            return points.clone();
        }

        /** Its length in blocks. */
        public int length() {
            double len = 0;
            for (int i = 2; i + 1 < points.length; i += 2) len += Math.hypot(points[i] - points[i - 2], points[i + 1] - points[i - 1]);
            return (int) Math.round(len);
        }

        public int segments() {
            return Math.max(0, points.length / 2 - 1);
        }

        boolean laid(int s) {
            return s / 64 < laid.length && (laid[s / 64] >>> (s % 64) & 1L) != 0;
        }

        void setLaid(int s) {
            if (laid.length <= s / 64) laid = java.util.Arrays.copyOf(laid, s / 64 + 1);
            laid[s / 64] |= 1L << (s % 64);
        }

        /** Has the crew made this stretch (cut the woods, trodden the way)? */
        public boolean built(int s) {
            return !building || s / 64 < built.length && (built[s / 64] >>> (s % 64) & 1L) != 0;
        }

        void setBuilt(int s) {
            if (!building) return;
            if (built.length <= s / 64) built = java.util.Arrays.copyOf(built, s / 64 + 1);
            built[s / 64] |= 1L << (s % 64);
            for (int k = 0; k < segments(); k++) if (!built(k)) return;
            // made from end to end
            building = false;
            built = new long[0];
        }

        /** Can it be walked from end to end (the crews have made all of it)? */
        public boolean passable() {
            return ready() && !building;
        }

        /** Stretches made so far. */
        public int builtCount() {
            int n = 0;
            for (int s = 0; s < segments(); s++) if (built(s)) n++;
            return n;
        }

        /** Stretches laid in the world so far. */
        public int laidCount() {
            int n = 0;
            for (int s = 0; s < segments(); s++) if (laid(s)) n++;
            return n;
        }

        public int other(int id) {
            return id == a ? b : a;
        }

        private static List<Integer> toList(int[] a) {
            List<Integer> l = new ArrayList<>(a.length);
            for (int x : a) l.add(x);
            return l;
        }
    }

    static long key(int a, int b) {
        return ((long) Math.min(a, b) << 32) | Math.max(a, b);
    }

    public static Trail find(VillageData data, int a, int b) {
        return data.trails.get(key(a, b));
    }

    /** The trails of a village that are ready to walk. */
    static List<Trail> of(VillageData data, int id) {
        List<Trail> out = new ArrayList<>();
        for (Trail t : data.trails.values()) if ((t.a == id || t.b == id) && t.ready()) out.add(t);
        return out;
    }

    // ------------------------------------------------------------------ the network

    /** Where a node of the network is: a village's middle, or a fork. */
    static int[] at(VillageData data, int node) {
        if (node >= 0) {
            Village v = data.get(node);
            return v == null ? null : new int[]{v.center.getX(), v.center.getZ()};
        }
        return data.junctions.get(node);
    }

    /** The trails of the network (only the ones made, end to end: {@code walkable}), by the nodes they join. */
    private static Map<Integer, List<Trail>> links(VillageData data, boolean walkable) {
        Map<Integer, List<Trail>> m = new HashMap<>();
        for (Trail t : data.trails.values()) {
            if (!t.ready() || walkable && !t.passable()) continue;
            m.computeIfAbsent(t.a, k -> new ArrayList<>()).add(t);
            m.computeIfAbsent(t.b, k -> new ArrayList<>()).add(t);
        }
        return m;
    }

    /** The nodes a node is joined to by the network, made or still being made (itself included). */
    public static java.util.Set<Integer> reach(VillageData data, int node) {
        return reach(data, node, false);
    }

    /** The nodes one can walk to from a node, over trails made from end to end (itself included). */
    public static java.util.Set<Integer> reachWalkable(VillageData data, int node) {
        return reach(data, node, true);
    }

    private static java.util.Set<Integer> reach(VillageData data, int node, boolean walkable) {
        Map<Integer, List<Trail>> links = links(data, walkable);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<Integer> open = new java.util.ArrayDeque<>();
        seen.add(node);
        open.add(node);
        while (!open.isEmpty()) {
            int n = open.poll();
            for (Trail t : links.getOrDefault(n, List.of())) if (seen.add(t.other(n))) open.add(t.other(n));
        }
        return seen;
    }

    /**
     * The way from one village to another over the trails that can be walked (the shortest, fork by fork), as one line
     * of points; null if no made way joins them.
     */
    public static int[] route(VillageData data, int from, int to) {
        return route(data, from, to, true);
    }

    /** The same over the network as it is planned (trails still being made too). */
    public static int[] plannedRoute(VillageData data, int from, int to) {
        return route(data, from, to, false);
    }

    private static int[] route(VillageData data, int from, int to, boolean walkable) {
        Map<Integer, List<Trail>> links = links(data, walkable);
        Map<Integer, Double> dist = new HashMap<>();
        Map<Integer, Trail> via = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        dist.put(from, 0.0);
        open.add(new double[]{0, from});
        while (!open.isEmpty()) {
            double[] top = open.poll();
            int n = (int) top[1];
            if (top[0] > dist.getOrDefault(n, Double.MAX_VALUE)) continue;
            if (n == to) break;
            for (Trail t : links.getOrDefault(n, List.of())) {
                int o = t.other(n);
                double d = top[0] + t.length();
                if (d < dist.getOrDefault(o, Double.MAX_VALUE)) {
                    dist.put(o, d);
                    via.put(o, t);
                    open.add(new double[]{d, o});
                }
            }
        }
        if (!dist.containsKey(to)) return null;
        // back from the end, then each trail the right way round
        List<int[]> legs = new ArrayList<>();
        for (int n = to; n != from; ) {
            Trail t = via.get(n);
            int prev = t.other(n);
            int[] p = t.points();
            legs.add(prev == t.a ? p : reverse(p));
            n = prev;
        }
        java.util.Collections.reverse(legs);
        int size = 0;
        for (int[] l : legs) size += l.length;
        int[] out = new int[size];
        int k = 0;
        for (int[] l : legs) {
            System.arraycopy(l, 0, out, k, l.length);
            k += l.length;
        }
        return out;
    }

    static int[] reverse(int[] p) {
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i += 2) {
            r[p.length - 2 - i] = p[i];
            r[p.length - 1 - i] = p[i + 1];
        }
        return r;
    }

    /**
     * A trail between two nodes, its points running from the one to the other (turned round if need be); {@code built}:
     * the stretches made (null: all of it).
     */
    private static Trail edge(int from, int to, int[] pts, boolean[] laid, boolean[] built) {
        Trail t = new Trail(from, to);
        boolean turned = t.a != from;
        t.points = turned ? reverse(pts) : pts;
        t.building = built != null;
        int segs = t.segments();
        for (int s = 0; s < segs; s++) {
            int src = turned ? segs - 1 - s : s;
            if (laid != null && src < laid.length && laid[src]) t.setLaid(s);
        }
        if (built != null) {
            for (int s = 0; s < segs; s++) {
                int src = turned ? segs - 1 - s : s;
                if (src < built.length && built[src]) t.setBuilt(s);
            }
            // (nothing to make: a trail of no length)
            if (segs == 0) t.building = false;
        }
        return t;
    }

    /** The stretches made, or null if all of it is. */
    private static boolean[] builtOf(Trail t) {
        if (!t.building) return null;
        boolean[] r = new boolean[t.segments()];
        for (int s = 0; s < r.length; s++) r[s] = t.built(s);
        return r;
    }

    /** A slice of what is made (null stays null: all of it). */
    private static boolean[] slice(boolean[] b, int from, int to) {
        return b == null ? null : java.util.Arrays.copyOfRange(b, Math.min(b.length, Math.max(0, from)), Math.min(b.length, Math.max(0, to)));
    }

    /**
     * Forks where only two trails meet (a new way was worked out from the middle of a trail, then found a shorter
     * joining at a village on the same network): no fork, one trail through it; a fork nothing meets at is dropped.
     */
    static void mergeBends(VillageData data) {
        // (one fork dropped can leave the next a bend: until nothing changes)
        for (int pass = 0; pass < 4 && mergeBendsOnce(data); pass++) {
        }
    }

    private static boolean mergeBendsOnce(VillageData data) {
        boolean changed = false;
        for (int j : new ArrayList<>(data.junctions.keySet())) {
            List<Trail> at = new ArrayList<>();
            for (Trail t : data.trails.values()) if ((t.a == j || t.b == j) && !t.none()) at.add(t);
            if (at.isEmpty()) {
                data.junctions.remove(j);
                changed = true;
                continue;
            }
            // a fork at the end of a stub of a few blocks, nothing else at it: the stub and the fork are dropped
            if (at.size() == 1 && at.getFirst().ready() && at.getFirst().length() <= 4) {
                Trail stub = at.getFirst();
                data.trails.remove(key(stub.a, stub.b));
                data.junctions.remove(j);
                data.changed();
                changed = true;
                continue;
            }
            if (at.size() != 2 || !at.get(0).ready() || !at.get(1).ready()) continue;
            Trail t1 = at.get(0), t2 = at.get(1);
            int x = t1.a == j ? t1.b : t1.a, y = t2.a == j ? t2.b : t2.a;
            if (x == y || data.trails.containsKey(key(x, y))) {
                if (BLOCKED.add(j)) Minecraftportsmod.LOGGER.warn("Fork {} of #{} and #{}: another trail joins them already, the fork stays", j, x, y);
                continue;
            }
            // t1 from x to the fork, t2 from the fork to y
            int[] p1 = t1.a == x ? t1.points : reverse(t1.points);
            boolean[] l1 = laidOf(t1);
            if (t1.a != x) l1 = flip(l1);
            int[] p2 = t2.a == j ? t2.points : reverse(t2.points);
            boolean[] l2 = laidOf(t2);
            if (t2.a != j) l2 = flip(l2);
            int[] pts = new int[p1.length + p2.length - 2];
            System.arraycopy(p1, 0, pts, 0, p1.length);
            System.arraycopy(p2, 2, pts, p1.length, p2.length - 2);
            boolean[] laid = new boolean[l1.length + l2.length];
            System.arraycopy(l1, 0, laid, 0, l1.length);
            System.arraycopy(l2, 0, laid, l1.length, l2.length);
            boolean[] built = null;
            if (t1.building || t2.building) {
                boolean[] b1 = builtOf(t1), b2 = builtOf(t2);
                if (b1 == null) java.util.Arrays.fill(b1 = new boolean[l1.length], true);
                else if (t1.a != x) b1 = flip(b1);
                if (b2 == null) java.util.Arrays.fill(b2 = new boolean[l2.length], true);
                else if (t2.a != j) b2 = flip(b2);
                built = new boolean[b1.length + b2.length];
                System.arraycopy(b1, 0, built, 0, b1.length);
                System.arraycopy(b2, 0, built, b1.length, b2.length);
            }
            Trail t = edge(x, y, pts, laid, built);
            t.bridges = t1.bridges + t2.bridges;
            data.trails.remove(key(t1.a, t1.b));
            data.trails.remove(key(t2.a, t2.b));
            data.trails.put(key(t.a, t.b), t);
            data.junctions.remove(j);
            data.changed();
            changed = true;
        }
        return changed;
    }

    /** Nothing made yet (a trail just worked out). */
    private static final boolean[] NOTHING = new boolean[0];

    /**
     * A crew has made the stretch from (x0, z0) to (x1, z1): the trail it is part of (found by its points, whatever
     * trails were parted or joined meanwhile) has it made. Returns whether one was found.
     */
    static boolean markBuilt(VillageData data, int x0, int z0, int x1, int z1) {
        for (Trail t : data.trails.values()) {
            if (!t.building) continue;
            int[] p = t.points;
            for (int s = 0; s + 3 < p.length; s += 2) {
                if (p[s] == x0 && p[s + 1] == z0 && p[s + 2] == x1 && p[s + 3] == z1 || p[s] == x1 && p[s + 1] == z1 && p[s + 2] == x0 && p[s + 3] == z0) {
                    t.setBuilt(s / 2);
                    data.changed();
                    return true;
                }
            }
        }
        return false;
    }

    /** Every trail made at once (tests of the ways themselves, the command). */
    public static void finishAll(VillageData data) {
        for (Trail t : data.trails.values()) {
            t.building = false;
            t.built = new long[0];
        }
        // (their crews home: nothing left to make)
        for (Roadworks.Work w : data.works) Roadworks.callHome(data, w);
        data.works.clear();
        data.changed();
    }

    /** Forks left as they are (logged once). */
    private static final java.util.Set<Integer> BLOCKED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static boolean[] flip(boolean[] l) {
        boolean[] r = new boolean[l.length];
        for (int i = 0; i < l.length; i++) r[i] = l[l.length - 1 - i];
        return r;
    }

    private static boolean[] laidOf(Trail t) {
        boolean[] r = new boolean[t.segments()];
        for (int s = 0; s < r.length; s++) r[s] = t.laid(s);
        return r;
    }

    /**
     * The node of the network at a point of it: a village (its middle), an end of a trail, or a fork made where the
     * point is in the middle of a trail (the trail parted there).
     */
    private static int nodeAt(VillageData data, int x, int z, int fallback) {
        for (Village w : data.all()) if (w.center.getX() == x && w.center.getZ() == z) return w.id;
        for (var e : data.junctions.entrySet()) if (e.getValue()[0] == x && e.getValue()[1] == z) return e.getKey();
        Trail hit = null;
        int at = -1;
        for (Trail t : data.trails.values()) {
            if (!t.ready()) continue;
            for (int i = 0; i + 1 < t.points.length; i += 2) {
                if (t.points[i] == x && t.points[i + 1] == z) {
                    hit = t;
                    at = i / 2;
                    break;
                }
            }
            if (hit != null) break;
        }
        if (hit == null) return fallback;
        if (at <= 1) return hit.a;
        if (at >= hit.points.length / 2 - 2) return hit.b;
        int node = --data.lastJunction;
        data.junctions.put(node, new int[]{x, z});
        boolean[] laid = laidOf(hit), built = builtOf(hit);
        int[] one = java.util.Arrays.copyOfRange(hit.points, 0, 2 * at + 2);
        int[] two = java.util.Arrays.copyOfRange(hit.points, 2 * at, hit.points.length);
        data.trails.remove(key(hit.a, hit.b));
        Trail t1 = edge(hit.a, node, one, java.util.Arrays.copyOfRange(laid, 0, Math.max(0, at)), slice(built, 0, at));
        Trail t2 = edge(node, hit.b, two, java.util.Arrays.copyOfRange(laid, Math.min(laid.length, at), laid.length), slice(built, at, hit.segments()));
        t1.bridges = hit.bridges;
        data.trails.put(key(t1.a, t1.b), t1);
        data.trails.put(key(t2.a, t2.b), t2);
        return node;
    }

    // ------------------------------------------------------------------ the server's side

    /** A world closed: what was being worked out for it is dropped. */
    static void reset() {
        for (Future<Found> f : PLANNING.values()) f.cancel(true);
        PLANNING.clear();
        AIMS.clear();
        BLOCKED.clear();
    }


    /** Every few seconds: trails worked out are taken in, new ones asked for, and the ready ones laid a little further. */
    static void tick(ServerLevel level, VillageData data) {
        // the ones worked out
        for (var e : new ArrayList<>(PLANNING.entrySet())) {
            if (!e.getValue().isDone()) continue;
            PLANNING.remove(e.getKey());
            Aim aim = AIMS.remove((int) (long) e.getKey());
            Found found;
            try {
                found = e.getValue().get();
            } catch (Exception ex) {
                Minecraftportsmod.LOGGER.warn("Trail from #{} failed: {}", e.getKey(), ex.toString());
                found = null;
            }
            if (aim != null) done(level, data, aim, found);
        }
        // villages whose scouts found one the network does not join them to yet: the nearest first, a few at a time
        if (PLANNING.size() < THREADS) {
            List<long[]> wanted = new ArrayList<>();
            for (Village v : data.all()) {
                if (PLANNING.containsKey((long) v.id)) continue;
                java.util.Set<Integer> joined = null;
                for (int o : v.known.keySet()) {
                    Village ov = data.get(o);
                    if (ov == null || ov.id == v.id || v.center.distSqr(ov.center) > (double) MAX_REACH * MAX_REACH) continue;
                    if (data.trails.containsKey(key(v.id, o)) && data.trails.get(key(v.id, o)).none) continue;
                    if (joined == null) joined = reach(data, v.id);
                    if (joined.contains(o)) continue;
                    // (the other one is looking for a way to us already: one trail is enough)
                    Aim theirs = AIMS.get(o);
                    if (theirs != null && joined.contains(theirs.other())) continue;
                    wanted.add(new long[]{(long) v.center.distSqr(ov.center), v.id, o});
                }
            }
            wanted.sort(java.util.Comparator.comparingLong(w -> w[0]));
            java.util.Set<Integer> started = new java.util.HashSet<>();
            for (long[] w : wanted) {
                if (PLANNING.size() >= THREADS) break;
                if (!started.add((int) w[1])) continue;
                // (one search for a pair: the other one may have just set out for us)
                Aim theirs = AIMS.get((int) w[2]);
                if (theirs != null && theirs.other() == (int) w[1]) continue;
                plan(level, data, data.get((int) w[1]), data.get((int) w[2]));
            }
        }
        // (a fork only two trails meet at, however it came about, is one trail again)
        mergeBends(data);
        // laid where the land is loaded (a little at a time)
        int budget = 24;
        for (Trail t : new ArrayList<>(data.trails.values())) {
            if (!t.ready()) continue;
            budget -= lay(level, data, t, budget);
            if (budget <= 0) break;
        }
    }

    /**
     * Starts working out the trail that joins village {@code v} to {@code o}: to the nearest point of the network that
     * leads to {@code o}, or to {@code o} itself (command, tests; else when a scout finds a village).
     */
    public static void plan(ServerLevel level, VillageData data, Village v, Village o) {
        if (PLANNING.containsKey((long) v.id)) return;
        // the goals: the village itself, then every point of every trail of the network that leads to it
        List<Integer> goals = new ArrayList<>();
        goals.add(o.center.getX());
        goals.add(o.center.getZ());
        java.util.Set<Integer> net = reach(data, o.id);
        for (Trail t : data.trails.values()) {
            if (!t.ready() || !net.contains(t.a)) continue;
            for (int x : t.points) goals.add(x);
        }
        int[] g = goals.stream().mapToInt(Integer::intValue).toArray();
        // the starts: the village, the villages and every point of the trails it is joined to already (a new way
        // branches off the network where it is nearest the goal, not from the village's middle again)
        List<Integer> starts = new ArrayList<>();
        starts.add(v.center.getX());
        starts.add(v.center.getZ());
        java.util.Set<Integer> mine = reach(data, v.id);
        for (int n : mine) {
            if (n < 0 || n == v.id || data.get(n) == null) continue;
            starts.add(data.get(n).center.getX());
            starts.add(data.get(n).center.getZ());
        }
        for (Trail t : data.trails.values()) {
            if (!t.ready() || !mine.contains(t.a)) continue;
            for (int x : t.points) starts.add(x);
        }
        int[] st = starts.stream().mapToInt(Integer::intValue).toArray();
        v.log(data.day, Component.translatable("minecraftportsmod.vlog.trail_search", o.name).withStyle(ChatFormatting.GRAY));
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int sx = v.center.getX(), sz = v.center.getZ();
        long seed = level.getSeed() ^ key(v.id, o.id);
        // the other villages about: a trail is drawn through them, as roads go from village to village
        List<Integer> towns = new ArrayList<>();
        for (Village w : data.all()) {
            if (w.id == v.id) continue;
            towns.add(w.center.getX());
            towns.add(w.center.getZ());
        }
        int[] draw = towns.stream().mapToInt(Integer::intValue).toArray();
        Minecraftportsmod.LOGGER.info("Trail search #{} -> #{} started ({} under way)", v.id, o.id, PLANNING.size());
        AIMS.put(v.id, new Aim(v.id, o.id, st, g));
        PLANNING.put((long) v.id, POOL.submit(() -> {
            long t0 = System.currentTimeMillis();
            int[] res = search(gen, rs, level, st, g, seed, draw);
            if (res == null) return null;
            int goal = res[res.length - 1], start = res[res.length - 2];
            int[] pts = java.util.Arrays.copyOf(res, res.length - 2);
            return new Found(pts, start, goal, countBridges(gen, rs, level, pts), (System.currentTimeMillis() - t0) / 1000);
        }));
    }

    /** Is a trail being worked out. */
    public static boolean planning() {
        return !PLANNING.isEmpty();
    }

    /**
     * A trail worked out: joined to the network where it reached it. At the village itself, a trail to it; in the middle
     * of a trail, that trail is parted there by a fork.
     */
    private static void done(ServerLevel level, VillageData data, Aim aim, Found found) {
        Village v = data.get(aim.village()), o = data.get(aim.other());
        long today = data.day;
        if (v == null || o == null) return;
        // (joined meanwhile, by a trail found from the other side: this one is not wanted)
        if (reach(data, v.id).contains(o.id)) return;
        if (found == null || found.points().length < 4) {
            Trail none = new Trail(v.id, o.id);
            none.none = true;
            data.trails.put(key(v.id, o.id), none);
            v.log(today, Component.translatable("minecraftportsmod.vlog.trail_none", o.name).withStyle(ChatFormatting.GRAY));
            data.changed();
            return;
        }
        int[] pts = found.points();
        int node = found.goal() == 0 ? o.id : nodeAt(data, aim.goals()[2 * found.goal()], aim.goals()[2 * found.goal() + 1], o.id);
        int first = found.start() == 0 ? v.id : nodeAt(data, aim.starts()[2 * found.start()], aim.starts()[2 * found.start() + 1], v.id);
        // (the points it runs between joined meanwhile, by another trail worked out at the same time: it would only
        // make a loop, with a fork on it that is no fork)
        if (first != node && reach(data, first).contains(node)) {
            Minecraftportsmod.LOGGER.info("Trail from #{} to #{} (for #{} to #{}) not wanted: joined meanwhile", first, node, v.id, o.id);
            return;
        }
        int[] begin = at(data, first);
        if (begin != null) {
            pts[0] = begin[0];
            pts[1] = begin[1];
        }
        int[] end = at(data, node);
        if (end != null) {
            pts[pts.length - 2] = end[0];
            pts[pts.length - 1] = end[1];
        }
        data.trails.remove(key(v.id, o.id));
        // the villages it goes through: stops on the way, the trail parted at each (in the order they are passed)
        List<int[]> stops = new ArrayList<>();
        for (Village w : data.all()) {
            if (w.id == v.id || w.id == node || w.id == first) continue;
            int best = -1;
            double bestD = THROUGH;
            for (int i = 2; i + 3 < pts.length; i += 2) {
                double d = Math.hypot(pts[i] - w.center.getX(), pts[i + 1] - w.center.getZ());
                if (d < bestD) {
                    bestD = d;
                    best = i / 2;
                }
            }
            if (best > 0) stops.add(new int[]{best, w.id});
        }
        stops.sort(java.util.Comparator.comparingInt(x -> x[0]));
        int from = first, cut = 0;
        Trail t = null;
        for (int[] stop : stops) {
            if (stop[0] <= cut) continue;
            Village w = data.get(stop[1]);
            int[] part = java.util.Arrays.copyOfRange(pts, 2 * cut, 2 * stop[0] + 2);
            part[part.length - 2] = w.center.getX();
            part[part.length - 1] = w.center.getZ();
            if (part.length >= 4 && !reach(data, from).contains(w.id)) {
                Trail e = edge(from, w.id, part, null, NOTHING);
                data.trails.put(key(e.a, e.b), e);
                if (w != o) w.log(today, Component.translatable("minecraftportsmod.vlog.trail_through", v.name, o.name).withStyle(ChatFormatting.DARK_AQUA));
            }
            from = w.id;
            cut = stop[0];
            pts[2 * cut] = w.center.getX();
            pts[2 * cut + 1] = w.center.getZ();
        }
        int[] rest = java.util.Arrays.copyOfRange(pts, 2 * cut, pts.length);
        if (rest.length >= 4 && !(from != first && reach(data, from).contains(node))) {
            t = edge(from, node, rest, null, NOTHING);
            t.bridges = found.bridges();
            data.trails.put(key(t.a, t.b), t);
        }
        if (t == null) t = edge(first, node, pts, null, NOTHING);
        // the villages beside it, not on the network yet: they have seen the way, and join it
        for (Village w : data.all()) {
            if (reach(data, w.id).contains(v.id)) continue;
            for (int i = 0; i + 1 < pts.length; i += 2) {
                if (Math.hypot(pts[i] - w.center.getX(), pts[i + 1] - w.center.getZ()) < NOTICE) {
                    w.known.putIfAbsent(v.id, today);
                    w.log(today, Component.translatable("minecraftportsmod.vlog.trail_seen", v.name).withStyle(ChatFormatting.GRAY));
                    break;
                }
            }
        }
        mergeBends(data);
        // the way is known: from tomorrow the two villages' crews make it, each from its end
        Roadworks.begin(data, v, o, first, node, pts, today);
        v.log(today, Component.translatable(node == o.id ? "minecraftportsmod.vlog.trail_ready" : "minecraftportsmod.vlog.trail_joined", o.name,
                t.length(), t.bridges).withStyle(ChatFormatting.DARK_AQUA));
        o.log(today, Component.translatable("minecraftportsmod.vlog.trail_came", v.name, t.length()).withStyle(ChatFormatting.DARK_AQUA));
        Minecraftportsmod.LOGGER.info("Trail from {} to {} (for #{} to #{}): {} blocks, {} bridge(s), worked out in {} s",
                first >= 0 ? "#" + first : "a fork at " + begin[0] + " " + begin[1], 
                node >= 0 ? "#" + node : "a fork at " + end[0] + " " + end[1], v.id, o.id, t.length(), t.bridges, found.seconds());
        data.changed();
    }

    /** Crossings of water along the way (as the generator has it). */
    private static int countBridges(ChunkGenerator gen, RandomState rs, LevelHeightAccessor level, int[] pts) {
        int n = 0;
        boolean wet = false;
        for (int i = 0; i + 1 < pts.length; i += 2) {
            int g = gen.getBaseHeight(pts[i], pts[i + 1], Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
            int s = gen.getBaseHeight(pts[i], pts[i + 1], Heightmap.Types.WORLD_SURFACE_WG, level, rs);
            boolean w = s > g;
            if (w && !wet) n++;
            wet = w;
        }
        return n;
    }

    // ------------------------------------------------------------------ the search (off the server thread)

    /** What the generator says of a grid cell: the ground, the water's surface (= ground where dry), a sea. */
    private record Cell(int ground, int surface, boolean sea, boolean river) {
        boolean water() {
            return surface > ground;
        }

        /** Water that is no river: the sea, a lake (a shore to keep off). */
        boolean shore() {
            return water() && !river;
        }

        int walk() {
            return water() ? surface : ground;
        }
    }

    /** The way between two points (tests). */
    static int[] search(ChunkGenerator gen, RandomState rs, LevelHeightAccessor heights, int sx, int sz, int ex, int ez, long seed) {
        int[] r = search(gen, rs, heights, new int[]{sx, sz}, new int[]{ex, ez}, seed, new int[0]);
        return r == null ? null : java.util.Arrays.copyOf(r, r.length - 2);
    }

    /**
     * The way from (sx, sz) to the nearest of the goals (x, z pairs; the first is a village, reached anywhere near its
     * middle, the rest points of trails, reached right by them). Returns the points and, last, the index of the goal
     * reached; null if none can be.
     */
    static int[] search(ChunkGenerator gen, RandomState rs, LevelHeightAccessor heights, int[] starts, int[] goals, long seed, int[] draw) {
        int sx = starts[0], sz = starts[1];
        int ex = goals[0], ez = goals[1];
        double far = Double.MAX_VALUE;
        for (int i = 0; i + 1 < goals.length; i += 2) {
            for (int j = 0; j + 1 < starts.length; j += Math.max(2, starts.length / 200 * 2)) {
                far = Math.min(far, Math.hypot(goals[i] - starts[j], goals[i + 1] - starts[j + 1]));
            }
        }
        // (a long trail is looked for on a coarser grid: four times fewer cells to read, and it is smoothed after)
        final int g = far > 600 ? 8 : GRID;
        // the goal cells: round the village, and right by each point of the network
        Map<Long, Integer> goalCells = new HashMap<>();
        for (int i = goals.length / 2 - 1; i >= 0; i--) {
            int gx = Math.floorDiv(goals[2 * i], g), gz = Math.floorDiv(goals[2 * i + 1], g);
            int r = i == 0 ? Math.max(1, 12 / g) : 1;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) goalCells.put(pk(gx + dx, gz + dz), i);
        }
        // (the pull toward the goals: the nearest of a few hundred of them)
        int stride = Math.max(1, goals.length / 2 / 300);
        List<int[]> pull = new ArrayList<>();
        for (int i = 0; i < goals.length / 2; i += stride) pull.add(new int[]{Math.floorDiv(goals[2 * i], g), Math.floorDiv(goals[2 * i + 1], g)});
        java.util.function.ToDoubleBiFunction<Integer, Integer> toGoal = (x, z) -> {
            double m = Double.MAX_VALUE;
            for (int[] q : pull) m = Math.min(m, Math.hypot(q[0] - x, q[1] - z));
            return m;
        };
        Map<Long, Cell> cells = new HashMap<>();
        int seaLevel = gen.getSeaLevel();
        java.util.function.LongFunction<Cell> cellAt = k -> cells.computeIfAbsent(k, kk -> {
            long key = kk;
            int x = (int) (key >> 32) * g, z = (int) key * g;
            var column = gen.getBaseColumn(x, z, heights, rs);
            int surface = heights.getMinY(), ground = heights.getMinY();
            for (int y = heights.getMaxY(); y >= heights.getMinY(); y--) {
                var st = column.getBlock(y);
                if (st.isAir()) continue;
                if (surface == heights.getMinY()) surface = y + 1;
                if (st.getFluidState().isEmpty()) {
                    ground = y + 1;
                    break;
                }
            }
            boolean sea = false, river = false;
            if (surface > ground) {
                var biome = gen.getBiomeSource().getNoiseBiome(x >> 2, seaLevel >> 2, z >> 2, rs.sampler());
                // a river is bridged; the sea, and water too deep for piles, is no way
                sea = biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN) || surface - ground > 8;
                river = biome.is(BiomeTags.IS_RIVER);
            }
            return new Cell(ground, surface, sea, river);
        });
        int gsx = Math.floorDiv(sx, g), gsz = Math.floorDiv(sz, g), gex = Math.floorDiv(ex, g), gez = Math.floorDiv(ez, g);
        Map<Long, Double> best = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Integer> wetRun = new HashMap<>();
        Map<Long, Integer> startOf = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        for (int i = starts.length / 2 - 1; i >= 0; i--) {
            int cx = Math.floorDiv(starts[2 * i], g), cz = Math.floorDiv(starts[2 * i + 1], g);
            long k = pk(cx, cz);
            // (the village itself is where a way may start without cost; out along its trails, a little more)
            double c0 = i == 0 ? 0 : 4;
            if (best.containsKey(k) && best.get(k) <= c0) continue;
            best.put(k, c0);
            wetRun.put(k, 0);
            startOf.put(k, i);
            open.add(new double[]{c0 + toGoal.applyAsDouble(cx, cz) * g * HEURISTIC, Double.longBitsToDouble(k)});
        }
        int expanded = 0;
        long found = Long.MIN_VALUE;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        while (!open.isEmpty()) {
            double[] top = open.poll();
            long cur = Double.doubleToRawLongBits(top[1]);
            int cx = (int) (cur >> 32), cz = (int) cur;
            double gc = best.get(cur);
            if (top[0] - toGoal.applyAsDouble(cx, cz) * g * HEURISTIC > gc + 1e-6) continue;
            // (a goal: the village's own ground, or right by a trail of the network)
            if (goalCells.containsKey(cur) && !startOf.containsKey(cur)) {
                found = cur;
                break;
            }
            if (++expanded > MAX_NODES) break;
            Cell here = cellAt.apply(cur);
            for (int[] d : dirs) {
                int nx = cx + d[0], nz = cz + d[1];
                long nk = pk(nx, nz);
                Cell there = cellAt.apply(nk);
                // near the ends (the villages' own ground) anything goes; elsewhere no sea
                boolean nearEnd = dist(nx, nz, gsx, gsz) * g < 24 || dist(nx, nz, gex, gez) * g < 24 || goalCells.containsKey(nk) || startOf.containsKey(nk);
                if (there.sea() && !nearEnd) continue;
                int run = there.water() ? wetRun.getOrDefault(cur, 0) + 1 : 0;
                if (run * g > MAX_BRIDGE && !nearEnd) continue;
                double step = cost(here, there, d[0] != 0 && d[1] != 0, nx, nz, cellAt, seed, g);
                if (step < 0) continue;
                // (through a village on the way: its land is trodden already, it costs less)
                for (int i = 0; i + 1 < draw.length; i += 2) {
                    if (Math.hypot(nx * g - draw[i], nz * g - draw[i + 1]) < DRAW) {
                        step *= 0.55;
                        break;
                    }
                }
                double ng = gc + step;
                Double old = best.get(nk);
                if (old != null && old <= ng) continue;
                best.put(nk, ng);
                parent.put(nk, cur);
                wetRun.put(nk, run);
                open.add(new double[]{ng + toGoal.applyAsDouble(nx, nz) * g * HEURISTIC, Double.longBitsToDouble(nk)});
            }
        }
        if (found == Long.MIN_VALUE) return null;
        List<long[]> way = new ArrayList<>();
        long root = found;
        for (long k = found; ; k = parent.get(k)) {
            way.add(new long[]{k});
            root = k;
            if (!parent.containsKey(k)) break;
        }
        int start = startOf.getOrDefault(root, 0);
        sx = starts[2 * start];
        sz = starts[2 * start + 1];
        java.util.Collections.reverse(way);
        int goal = goalCells.get(found);
        int[] pts = new int[way.size() * 2 + 2];
        for (int i = 0; i < way.size(); i++) {
            long k = way.get(i)[0];
            pts[2 * i] = (int) (k >> 32) * g + g / 2;
            pts[2 * i + 1] = (int) k * g + g / 2;
        }
        pts[pts.length - 2] = goals[2 * goal];
        pts[pts.length - 1] = goals[2 * goal + 1];
        pts[0] = sx;
        pts[1] = sz;
        int[] sm = smooth(smooth(pts));
        int[] out = java.util.Arrays.copyOf(sm, sm.length + 2);
        out[sm.length] = start;
        out[sm.length + 1] = goal;
        return out;
    }

    /** Chaikin's corner cutting: every corner of the way rounded off (the two ends kept). */
    static int[] smooth(int[] p) {
        int n = p.length / 2;
        if (n < 3) return p;
        List<int[]> out = new ArrayList<>();
        out.add(new int[]{p[0], p[1]});
        for (int i = 0; i + 1 < n; i++) {
            int x0 = p[2 * i], z0 = p[2 * i + 1], x1 = p[2 * i + 2], z1 = p[2 * i + 3];
            if (i > 0) out.add(new int[]{Math.round(x0 * 0.75F + x1 * 0.25F), Math.round(z0 * 0.75F + z1 * 0.25F)});
            if (i + 2 < n) out.add(new int[]{Math.round(x0 * 0.25F + x1 * 0.75F), Math.round(z0 * 0.25F + z1 * 0.75F)});
        }
        out.add(new int[]{p[p.length - 2], p[p.length - 1]});
        int[] r = new int[out.size() * 2];
        for (int i = 0; i < out.size(); i++) {
            r[2 * i] = out.get(i)[0];
            r[2 * i + 1] = out.get(i)[1];
        }
        return r;
    }

    /**
     * What a step costs (-1: no step): its length; the height climbed or gone down, by its square (a hill is gone
     * round, a cliff is no way); the steepness of the land beside it (not along the foot of a mountain); water, as a
     * bridge; all of it wavering a little over the land.
     */
    private static double cost(Cell from, Cell to, boolean diagonal, int nx, int nz, java.util.function.LongFunction<Cell> cellAt, long seed, int g) {
        double len = diagonal ? g * Math.sqrt(2) : g;
        int dh = Math.abs(to.walk() - from.walk());
        if (dh > 6 * g / 4) return -1;
        double c = len + dh * dh * 1.5 * 4.0 / g;
        // the lie of the land around: how steep it is right beside the way
        int rough = 0;
        for (int[] d : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
            Cell n = cellAt.apply(pk(nx + d[0], nz + d[1]));
            rough = Math.max(rough, Math.abs(n.walk() - to.walk()));
        }
        c += len * Math.max(0, rough - 3 * g / 4) * 0.7 * 4.0 / g;
        if (to.water()) {
            // a river is crossed by a bridge; a lake or a bay is not waded through
            c += len * (to.river ? 5 : 12);
        } else {
            // keep off the shore of the sea or a lake (the nearer, the dearer); along a river, a little
            int nearShore = Integer.MAX_VALUE;
            boolean nearRiver = false;
            for (int ddx = -3; ddx <= 3; ddx++) {
                for (int ddz = -3; ddz <= 3; ddz++) {
                    if (ddx == 0 && ddz == 0) continue;
                    Cell n = cellAt.apply(pk(nx + ddx, nz + ddz));
                    if (!n.water()) continue;
                    int dist = Math.max(Math.abs(ddx), Math.abs(ddz));
                    if (n.shore()) nearShore = Math.min(nearShore, dist);
                    else if (dist <= 1) nearRiver = true;
                }
            }
            if (nearShore != Integer.MAX_VALUE) c += len * 6.0 / nearShore;
            else if (nearRiver) c += len * 1.0;
        }
        // (a trodden way wanders a little)
        c *= 1 + 0.2 * wander(nx * g, nz * g, seed);
        return c;
    }

    /** Smooth noise over the land, 0..1, on a lattice of 48 blocks. */
    private static double wander(int x, int z, long seed) {
        int s = 48;
        int ix = Math.floorDiv(x, s), iz = Math.floorDiv(z, s);
        double fx = (x - ix * s) / (double) s, fz = (z - iz * s) / (double) s;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = hash(ix, iz, seed), b = hash(ix + 1, iz, seed), c = hash(ix, iz + 1, seed), d = hash(ix + 1, iz + 1, seed);
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    private static double hash(int x, int z, long seed) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) / (double) (1L << 53);
    }

    private static double dist(int x0, int z0, int x1, int z1) {
        return Math.hypot(x1 - x0, z1 - z0);
    }

    private static long pk(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------ laying it in the world

    /** Lays up to {@code budget} stretches of a trail where the land is loaded. Returns the stretches laid. */
    static int lay(ServerLevel level, VillageData data, Trail t, int budget) {
        Village a = woodOf(data, t);
        if (a == null) return 0;
        int done = 0;
        int[] decks = null;
        for (int s = 0; s < t.segments() && done < budget; s++) {
            if (t.laid(s) || !t.built(s)) continue;
            int x0 = t.points[2 * s], z0 = t.points[2 * s + 1], x1 = t.points[2 * s + 2], z1 = t.points[2 * s + 3];
            if (!level.hasChunkAt(new BlockPos(x0, 0, z0)) || !level.hasChunkAt(new BlockPos(x1, 0, z1))) continue;
            if (decks == null) decks = decks(level, data, t);
            // (a gap whose far side is not loaded yet: its bridge waits till it is)
            if (decks[s] == UNKNOWN || decks[s + 1] == UNKNOWN) continue;
            layStretch(level, data, a, t, decks, s);
            t.setLaid(s);
            done++;
        }
        if (done > 0) data.changed();
        return done;
    }

    /** The village whose wood a trail is laid with: one at an end; a trail between two forks, the nearest. */
    private static Village woodOf(VillageData data, Trail t) {
        Village a = data.get(t.a) != null ? data.get(t.a) : data.get(t.b);
        if (a == null) {
            int[] p0 = t.points.length >= 2 ? new int[]{t.points[0], t.points[1]} : new int[]{0, 0};
            for (Village v : data.all()) if (a == null || v.center.distSqr(new BlockPos(p0[0], 0, p0[1])) < a.center.distSqr(new BlockPos(p0[0], 0, p0[1]))) a = v;
        }
        return a;
    }

    /**
     * A crew has just made the stretch from (x0, z0) to (x1, z1): it is laid in the world at once, where its land is
     * loaded (the crew stands on what is laid, a bridge's deck as it grows); else it is laid when the land loads.
     */
    static void layNow(ServerLevel level, VillageData data, int x0, int z0, int x1, int z1) {
        for (Trail t : data.trails.values()) {
            int[] p = t.points;
            for (int s = 0; s + 3 < p.length; s += 2) {
                boolean fwd = p[s] == x0 && p[s + 1] == z0 && p[s + 2] == x1 && p[s + 3] == z1;
                boolean back = p[s] == x1 && p[s + 1] == z1 && p[s + 2] == x0 && p[s + 3] == z0;
                if (!fwd && !back) continue;
                int seg = s / 2;
                if (t.laid(seg) || !t.built(seg)) return;
                if (!level.hasChunkAt(new BlockPos(x0, 0, z0)) || !level.hasChunkAt(new BlockPos(x1, 0, z1))) return;
                int[] decks = decks(level, data, t);
                if (decks[seg] == UNKNOWN || decks[seg + 1] == UNKNOWN) return;
                Village a = woodOf(data, t);
                if (a == null) return;
                layStretch(level, data, a, t, decks, seg);
                t.setLaid(seg);
                data.changed();
                return;
            }
        }
    }

    /**
     * Lays stretch {@code s} of a trail. A stretch of a bridge is laid along the straight line from the bank the
     * bridge starts at to the bank it ends at (its part of that line), so the bridge is one straight span however
     * the trail bends; a dry gap (a pit, a hollow) not too deep is filled with earth instead, and the way goes on
     * over it.
     */
    private static void layStretch(ServerLevel level, VillageData data, Village a, Trail t, int[] decks, int s) {
        int[] p = t.points;
        int x0 = p[2 * s], z0 = p[2 * s + 1], x1 = p[2 * s + 2], z1 = p[2 * s + 3];
        if (decks[s] == NONE || decks[s + 1] == NONE) {
            line(level, data, a, x0, z0, x1, z1, decks[s], decks[s + 1], alongX(t, decks, s), false);
            return;
        }
        int i0 = s, i1 = s + 1;
        while (i0 > 0 && decks[i0 - 1] != NONE && decks[i0 - 1] != UNKNOWN) i0--;
        while (i1 + 1 < decks.length && decks[i1 + 1] != NONE && decks[i1 + 1] != UNKNOWN) i1++;
        boolean wet = false;
        int deepest = 0;
        for (int i = i0; i <= i1; i++) {
            int g = groundAt(level, p[2 * i], p[2 * i + 1]);
            if (!level.getBlockState(new BlockPos(p[2 * i], g, p[2 * i + 1])).getFluidState().isEmpty()) wet = true;
            deepest = Math.max(deepest, Math.max(decks[i], decks[s]) - g);
        }
        boolean fill = !wet && deepest <= FILL;
        double[] cum = new double[i1 - i0 + 1];
        for (int i = i0 + 1; i <= i1; i++) cum[i - i0] = cum[i - i0 - 1] + Math.hypot(p[2 * i] - p[2 * i - 2], p[2 * i + 1] - p[2 * i - 1]);
        double total = Math.max(1e-6, cum[i1 - i0]);
        double u0 = cum[s - i0] / total, u1 = cum[s + 1 - i0] / total;
        int ax = p[2 * i0], az = p[2 * i0 + 1], bx = p[2 * i1], bz = p[2 * i1 + 1];
        int sx0 = (int) Math.round(ax + (bx - ax) * u0), sz0 = (int) Math.round(az + (bz - az) * u0);
        int sx1 = (int) Math.round(ax + (bx - ax) * u1), sz1 = (int) Math.round(az + (bz - az) * u1);
        boolean alongX = Math.abs(bx - ax) >= Math.abs(bz - az);
        line(level, data, a, sx0, sz0, sx1, sz1, decks[s], decks[s + 1], alongX, fill);
    }

    /** A dry gap at most this deep is filled with earth rather than bridged. */
    static final int FILL = 8;

    /** No bridge at a point of a trail (the way follows the ground). */
    static final int NONE = Integer.MIN_VALUE;
    /** Not known yet whether a point is in a gap (the land about is not loaded). */
    static final int UNKNOWN = Integer.MIN_VALUE + 1;
    /** The ground falling away this far below the edge (and coming up again) is a gap to be bridged. */
    static final int GAP = 3;

    /**
     * Where a trail crosses a gap (a pit, a quarry, a ravine, a flooded hole: the ground falls away and comes up
     * again within a bridge's length), the height of the deck at each point of it: from the edge on one side to the
     * edge on the other, rising or falling evenly; the edge points have their ground's height (the deck starts
     * there). {@link #NONE} where the way follows the ground (a slope that goes down and stays down is no gap),
     * {@link #UNKNOWN} where the land to tell is not loaded.
     */
    /** Does the bridge a stretch is part of run more along x than along z (its deck laid across the other way)? */
    private static boolean alongX(Trail t, int[] decks, int s) {
        int i0 = s, i1 = s + 1;
        while (i0 > 0 && decks[i0 - 1] != NONE && decks[i0 - 1] != UNKNOWN) i0--;
        while (i1 + 1 < decks.length && decks[i1 + 1] != NONE && decks[i1 + 1] != UNKNOWN) i1++;
        return Math.abs(t.points[2 * i1] - t.points[2 * i0]) >= Math.abs(t.points[2 * i1 + 1] - t.points[2 * i0 + 1]);
    }

    /** The least run of water the way crosses for it to be bridged (less: earth is heaped in, and the way goes on). */
    static final double WET_BRIDGE = 4;

    static int[] decks(ServerLevel level, VillageData data, Trail t) {
        int n = t.points.length / 2;
        int[] g = new int[n], deck = new int[n];
        boolean[] ok = new boolean[n], wet = new boolean[n], town = new boolean[n];
        double[] cum = new double[n];
        for (int i = 0; i < n; i++) {
            int x = t.points[2 * i], z = t.points[2 * i + 1];
            if (i > 0) cum[i] = cum[i - 1] + Math.hypot(x - t.points[2 * i - 2], z - t.points[2 * i - 1]);
            ok[i] = level.hasChunkAt(new BlockPos(x, 0, z));
            // (in a village the way keeps to the ground: no bridges among the houses)
            town[i] = home(data, x, z);
            if (ok[i]) {
                g[i] = groundAt(level, x, z);
                wet[i] = !level.getBlockState(new BlockPos(x, g[i], z)).getFluidState().isEmpty();
            }
            deck[i] = NONE;
        }
        int last = -1;
        for (int i = 0; i < n; ) {
            if (!ok[i]) {
                deck[i] = UNKNOWN;
                last = -1;
                i++;
                continue;
            }
            if (last >= 0 && g[i] <= g[last] - GAP && !town[i] && !town[last] && !wet[i]) {
                // the ground falls away: is there a far side within a bridge's length?
                int j = i;
                boolean known = true;
                while (j < n && cum[j] - cum[last] <= MAX_BRIDGE + 8) {
                    if (!ok[j]) {
                        known = false;
                        break;
                    }
                    if (g[j] > g[last] - GAP) break;
                    j++;
                }
                if (!known) {
                    for (int m = i; m < j; m++) deck[m] = UNKNOWN;
                    last = -1;
                    i = j;
                    continue;
                }
                if (j < n && g[j] > g[last] - GAP && cum[j] - cum[last] <= MAX_BRIDGE + 8) {
                    // a gap: a deck from edge to edge
                    for (int m = last; m <= j; m++) {
                        double f = cum[j] == cum[last] ? 0 : (cum[m] - cum[last]) / (cum[j] - cum[last]);
                        deck[m] = (int) Math.round(g[last] + (g[j] - g[last]) * f);
                    }
                    last = j;
                    i = j + 1;
                    continue;
                }
                // (it goes down and stays down: the way follows the ground)
            }
            last = i;
            i++;
        }
        // water the way crosses for a good stretch: a bridge over all of it, a block over the water, bank to bank
        for (int i = 0; i < n; ) {
            if (!ok[i] || !wet[i] || town[i] || deck[i] != NONE) {
                i++;
                continue;
            }
            int j = i;
            boolean known = true;
            while (j < n && (!ok[j] || wet[j]) && !town[j]) {
                if (!ok[j]) known = false;
                j++;
            }
            int bank0 = i - 1, bank1 = j < n ? j : n - 1;
            if (cum[j - 1] - cum[i] + 1 >= WET_BRIDGE && bank0 >= 0) {
                int top = Integer.MIN_VALUE;
                for (int m = i; m < j; m++) if (ok[m]) top = Math.max(top, g[m] + 1);
                for (int m = i; m < j; m++) deck[m] = known ? top : UNKNOWN;
                // the banks: where the deck starts (their own ground, at least the deck's height less one)
                if (deck[bank0] == NONE) deck[bank0] = known ? Math.max(g[bank0], top - 1) : UNKNOWN;
                if (j < n && deck[bank1] == NONE) deck[bank1] = known ? Math.max(g[bank1], top - 1) : UNKNOWN;
            }
            i = j + 1;
        }
        return deck;
    }

    /** The ground of a column: its top block, trees, bushes and grass aside (water counts: its surface). */
    public static int groundAt(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        while (y > level.getMinY()) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (!(s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(Blocks.VINE) || s.is(Blocks.BAMBOO) || s.isAir()
                    || s.canBeReplaced() && s.getFluidState().isEmpty())) break;
            y--;
        }
        return y;
    }

    /**
     * Where a trail is not trodden in a village: on a building's plot (and a block round it) or on the square itself.
     * Elsewhere it goes on in among the houses, up to the square.
     */
    private static boolean built(VillageData data, int x, int z) {
        for (Village v : data.all()) {
            double dx = x - v.center.getX(), dz = z - v.center.getZ();
            if (dx * dx + dz * dz > (double) (VILLAGE_EDGE + 40) * (VILLAGE_EDGE + 40)) continue;
            for (Building b : v.buildings) {
                int h = b.type.half + (b.type.isCenter() ? 0 : 1);
                if (Math.abs(x - b.origin.getX()) <= h && Math.abs(z - b.origin.getZ()) <= h) return true;
            }
            if (Math.abs(x - v.board.getX()) <= 1 && Math.abs(z - v.board.getZ()) <= 1) return true;
        }
        return false;
    }

    /** The village's own ground (near its middle): no trees are felled there for a trail. */
    private static boolean home(VillageData data, int x, int z) {
        for (Village v : data.all()) {
            double dx = x - v.center.getX(), dz = z - v.center.getZ();
            if (dx * dx + dz * dz < VILLAGE_EDGE * VILLAGE_EDGE) return true;
        }
        return false;
    }

    /** Half the width of the way (it is three blocks wide), and of the cutting through the woods (seven). */
    static final double WAY = 1.55, CUTTING = 3.55;

    /**
     * One stretch: the way from one point to the next, three blocks wide, drawn with a round brush along the line (so
     * it runs smooth whatever its direction), through a cutting seven blocks wide where every tree and bush is felled.
     * Over water, a plank deck with rails on its outer edges.
     */
    private static void line(ServerLevel level, VillageData data, Village a, int x0, int z0, int x1, int z1, int d0, int d1, boolean alongX, boolean fill) {
        double dx = x1 - x0, dz = z1 - z0, len = Math.max(1e-6, Math.hypot(dx, dz));
        java.util.Set<Long> way = new java.util.LinkedHashSet<>(), cutting = new java.util.LinkedHashSet<>();
        for (double t = 0; t <= len + 1e-6; t += 0.5) {
            double cx = x0 + 0.5 + dx * t / len, cz = z0 + 0.5 + dz * t / len;
            for (int x = (int) Math.floor(cx - CUTTING); x <= (int) Math.floor(cx + CUTTING); x++) {
                for (int z = (int) Math.floor(cz - CUTTING); z <= (int) Math.floor(cz + CUTTING); z++) {
                    double ex = x + 0.5 - cx, ez = z + 0.5 - cz, d2 = ex * ex + ez * ez;
                    if (d2 <= WAY * WAY) way.add(pk(x, z));
                    if (d2 <= CUTTING * CUTTING) cutting.add(pk(x, z));
                }
            }
        }
        // the cutting: trees and bushes felled, whole; then what still hangs over it (a jungle's leaves and vines)
        for (long k : cutting) {
            int x = (int) (k >> 32), z = (int) k;
            if (home(data, x, z) || !level.hasChunkAt(new BlockPos(x, 0, z))) continue;
            clear(level, x, z);
        }
        for (long k : cutting) {
            int x = (int) (k >> 32), z = (int) k;
            if (home(data, x, z) || !level.hasChunkAt(new BlockPos(x, 0, z))) continue;
            loose(level, x, z);
        }
        // a dry gap: earth heaped in up to the way's height (from edge to edge), the way trodden on it
        if (d0 != NONE && d1 != NONE && fill) {
            for (long k : way) {
                int x = (int) (k >> 32), z = (int) k;
                if (built(data, x, z) || !level.hasChunkAt(new BlockPos(x, 0, z))) continue;
                double u = Math.max(0, Math.min(1, ((x + 0.5 - x0 - 0.5) * dx + (z + 0.5 - z0 - 0.5) * dz) / (len * len)));
                int h = (int) Math.round(d0 + (d1 - d0) * u);
                int g = groundAt(level, x, z);
                for (int y = g + 1; y <= h; y++) {
                    BlockPos q = new BlockPos(x, y, z);
                    BlockState st = level.getBlockState(q);
                    if (!st.isAir() && !st.canBeReplaced()) break;
                    level.setBlock(q, y == h ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), FLAGS);
                }
                column(level, a, x, z);
            }
            return;
        }
        // across water: a bridge, its deck from bank to bank
        if (d0 != NONE && d1 != NONE) {
            span(level, data, a, x0, z0, x1, z1, d0, d1, alongX);
            return;
        }
        for (long k : way) {
            int x = (int) (k >> 32), z = (int) k;
            if (built(data, x, z)) continue;
            column(level, a, x, z);
        }
    }

    /** Leaves and vines left hanging over a column of the cutting, held by no trunk near them: taken down. */
    private static void loose(ServerLevel level, int x, int z) {
        int ground = groundAt(level, x, z);
        for (int y = ground + 1; y <= ground + 18; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState st = level.getBlockState(p);
            if (st.is(Blocks.VINE) || st.is(BlockTags.LEAVES) && !trunkNear(level, p)) level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
        }
    }

    private static boolean trunkNear(ServerLevel level, BlockPos p) {
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-3, -4, -3), p.offset(3, 2, 3))) {
            if (level.getBlockState(q).is(BlockTags.LOGS)) return true;
        }
        return false;
    }

    /**
     * A stretch of bridge over a gap: a plank deck at its height along the stretch (from {@code d0} to {@code d1}),
     * where the ground is below it; posts down to the bottom every few blocks; rails beside it where the land falls
     * away. Where the ground is at the deck's height (the edges), the way is trodden there as anywhere.
     */
    private static void span(ServerLevel level, VillageData data, Village a, int x0, int z0, int x1, int z1, int d0, int d1, boolean runAlongX) {
        double dx = x1 - x0, dz = z1 - z0, len = Math.max(1e-6, Math.hypot(dx, dz));
        // (the deck lies across this stretch's own way: a bend in the run turns it)
        boolean alongX = Math.abs(dx) == Math.abs(dz) ? runAlongX : Math.abs(dx) > Math.abs(dz);
        java.util.Map<Long, Integer> deckAt = new java.util.LinkedHashMap<>();
        java.util.Set<Long> edges = new java.util.HashSet<>();
        for (double t = 0; t <= len + 1e-6; t += 0.25) {
            double u = t / len;
            int cx = (int) Math.floor(x0 + 0.5 + dx * u), cz = (int) Math.floor(z0 + 0.5 + dz * u);
            int deckY = (int) Math.round(d0 + (d1 - d0) * u);
            for (int w = -2; w <= 2; w++) {
                int x = alongX ? cx : cx + w, z = alongX ? cz + w : cz;
                long k = pk(x, z);
                deckAt.merge(k, deckY, Math::max);
                if (Math.abs(w) == 2) edges.add(k);
            }
        }
        // (a column that is edge of one slice and middle of the next is the middle)
        java.util.Set<Long> middle = new java.util.HashSet<>(deckAt.keySet());
        middle.removeAll(edges);
        List<BlockPos> rails = new ArrayList<>();
        for (var e : deckAt.entrySet()) {
            long k = e.getKey();
            int x = (int) (k >> 32), z = (int) k, deckY = e.getValue();
            if (built(data, x, z)) continue;
            int g = groundAt(level, x, z);
            boolean dry = level.getBlockState(new BlockPos(x, g, z)).getFluidState().isEmpty();
            boolean edge = !middle.contains(k);
            // the banks: where the ground comes up to the deck, the way goes on on the ground
            if (dry && g >= deckY - 1) {
                if (!edge) column(level, a, x, z);
                continue;
            }
            BlockPos deck = new BlockPos(x, deckY, z);
            BlockState at = level.getBlockState(deck);
            if (at.canBeReplaced() || at.is(BlockTags.LOGS) || at.is(BlockTags.LEAVES) || at.is(BlockTags.FENCES)) {
                level.setBlock(deck, wood(a, "planks"), FLAGS | Block.UPDATE_NEIGHBORS);
            }
            for (int k2 = 1; k2 <= 3; k2++) {
                BlockPos up = deck.above(k2);
                BlockState st = level.getBlockState(up);
                if (!st.isAir() && (st.canBeReplaced() || st.is(BlockTags.LEAVES) || st.is(Blocks.VINE))) level.setBlock(up, Blocks.AIR.defaultBlockState(), FLAGS);
            }
            if (edge) {
                // the rail on the deck's edge; under it, every third block along the bridge, a post to the bottom
                BlockPos rail = deck.above();
                if (level.getBlockState(rail).canBeReplaced()) {
                    level.setBlock(rail, wood(a, "fence"), FLAGS | Block.UPDATE_NEIGHBORS);
                    rails.add(rail);
                }
                if (Math.floorMod(alongX ? x : z, 3) == 0) {
                    for (int y = g + (dry ? 1 : 0); y < deckY; y++) {
                        BlockPos q = new BlockPos(x, y, z);
                        BlockState qs = level.getBlockState(q);
                        if (qs.canBeReplaced() || !qs.getFluidState().isEmpty()) level.setBlock(q, wood(a, "log"), FLAGS);
                    }
                }
            }
        }
        // along a slant the rails of the edge step sideways: a corner rail (on a plank of its own, outside the deck)
        // closes each such step, so that the rail runs on unbroken
        for (BlockPos r : new ArrayList<>(rails)) {
            for (int[] d : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                // (the rail beside it may be of the stretch before: what stands in the world counts)
                BlockPos o = r.offset(d[0], 0, d[1]);
                if (!level.getBlockState(o).is(BlockTags.FENCES)) continue;
                BlockPos c1 = r.offset(d[0], 0, 0), c2 = r.offset(0, 0, d[1]);
                if (level.getBlockState(c1).is(BlockTags.FENCES) || level.getBlockState(c2).is(BlockTags.FENCES)) continue;
                // the corner off the walking deck (no plank under it yet)
                boolean deck1 = level.getBlockState(c1.below()).is(BlockTags.PLANKS) || middle.contains(pk(c1.getX(), c1.getZ()));
                boolean deck2 = level.getBlockState(c2.below()).is(BlockTags.PLANKS) || middle.contains(pk(c2.getX(), c2.getZ()));
                // (never on the walking line: only out at the edge, two blocks off the way's middle)
                deck1 |= offLine(c1, x0, z0, x1, z1) < 1.9;
                deck2 |= offLine(c2, x0, z0, x1, z1) < 1.9;
                if (deck1 && deck2) continue;
                BlockPos c = deck1 ? c2 : c1;
                BlockPos under = c.below();
                BlockState us = level.getBlockState(under);
                if (us.canBeReplaced() || !us.getFluidState().isEmpty()) level.setBlock(under, wood(a, "planks"), FLAGS);
                if (level.getBlockState(c).canBeReplaced()) {
                    level.setBlock(c, wood(a, "fence"), FLAGS);
                    rails.add(c);
                }
            }
        }
        // a rail with the deck on all four sides of it stands on the walkway (on a bend one stretch's edge is the
        // next one's middle): taken away, here and of the stretches beside
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, mx0 = Integer.MAX_VALUE, mx1 = Integer.MIN_VALUE, mz0 = Integer.MAX_VALUE, mz1 = Integer.MIN_VALUE;
        for (var e : deckAt.entrySet()) {
            int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
            minY = Math.min(minY, e.getValue());
            maxY = Math.max(maxY, e.getValue());
            mx0 = Math.min(mx0, x);
            mx1 = Math.max(mx1, x);
            mz0 = Math.min(mz0, z);
            mz1 = Math.max(mz1, z);
        }
        if (minY != Integer.MAX_VALUE) {
            for (BlockPos q : BlockPos.betweenClosed(mx0 - 3, minY, mz0 - 3, mx1 + 3, maxY + 2, mz1 + 3)) {
                if (!level.getBlockState(q).is(BlockTags.FENCES) || !level.getBlockState(q.below()).is(BlockTags.PLANKS)) continue;
                boolean inside = true;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    if (!level.getBlockState(q.relative(d).below()).is(BlockTags.PLANKS)) {
                        inside = false;
                        break;
                    }
                }
                if (!inside) continue;
                BlockPos gone = q.immutable();
                level.setBlock(gone, Blocks.AIR.defaultBlockState(), FLAGS);
                rails.remove(gone);
                for (Direction d : Direction.Plane.HORIZONTAL) rails.add(gone.relative(d));
            }
        }
        // the rails joined into one (and to those of the stretch before)
        for (BlockPos r : rails) {
            for (BlockPos q : new BlockPos[]{r, r.north(), r.south(), r.east(), r.west()}) {
                BlockState st = level.getBlockState(q);
                if (!st.is(BlockTags.FENCES)) continue;
                BlockState joined = Block.updateFromNeighbourShapes(st, level, q);
                if (joined != st) level.setBlock(q, joined, FLAGS);
            }
        }
    }

    /** How far a block's middle is from the line through (x0, z0) and (x1, z1) (the blocks' middles). */
    private static double offLine(BlockPos c, int x0, int z0, int x1, int z1) {
        double dx = x1 - x0, dz = z1 - z0, len = Math.hypot(dx, dz);
        if (len < 1e-6) return Math.hypot(c.getX() - x0, c.getZ() - z0);
        return Math.abs((c.getX() - x0) * dz - (c.getZ() - z0) * dx) / len;
    }

    /**
     * Fells what grows on a column: a tree whose trunk stands here (logs and crown, all of it), and leaves (a bush)
     * down to the ground. A giant too big to fell whole loses what of it stands here.
     */
    private static void clear(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        int ground = y;
        while (ground > level.getMinY()) {
            BlockState s = level.getBlockState(new BlockPos(x, ground, z));
            if (!(s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(Blocks.VINE) || s.is(Blocks.BAMBOO) || s.isAir()
                    || s.canBeReplaced() && s.getFluidState().isEmpty())) break;
            ground--;
        }
        if (y <= ground) return;
        // the lowest log above the ground: a tree stands here
        for (int k = ground + 1; k <= y; k++) {
            BlockPos p = new BlockPos(x, k, z);
            BlockState s = level.getBlockState(p);
            if (!s.is(BlockTags.LOGS)) continue;
            List<BlockPos> tree = WorkGoal.tree(level, p, null);
            if (tree != null) {
                for (BlockPos q : tree) {
                    BlockState qs = level.getBlockState(q);
                    if (qs.is(BlockTags.LOGS) || qs.is(BlockTags.LEAVES)) level.setBlock(q, Blocks.AIR.defaultBlockState(), FLAGS);
                }
            } else {
                // a trunk with no crown left (its leaves taken down over the stretch before): the whole trunk,
                // its other columns too (a thick one)
                java.util.ArrayDeque<BlockPos> open = new java.util.ArrayDeque<>(List.of(p));
                java.util.Set<BlockPos> seen = new java.util.HashSet<>(List.of(p));
                int n = 0;
                while (!open.isEmpty() && n < 96) {
                    BlockPos q = open.poll();
                    if (!level.getBlockState(q).is(BlockTags.LOGS)) continue;
                    level.setBlock(q, Blocks.AIR.defaultBlockState(), FLAGS);
                    n++;
                    for (BlockPos r : BlockPos.betweenClosed(q.offset(-1, 0, -1), q.offset(1, 1, 1))) {
                        BlockPos rr = r.immutable();
                        if (Math.abs(rr.getX() - x) <= 2 && Math.abs(rr.getZ() - z) <= 2 && rr.getY() > ground && seen.add(rr)) open.add(rr);
                    }
                }
            }
            break;
        }
        // what is left over the ground here: a giant's trunk, leaves, vines
        for (int k = ground + 1; k <= y + 1; k++) {
            BlockPos p = new BlockPos(x, k, z);
            BlockState s = level.getBlockState(p);
            if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(Blocks.VINE) || s.is(Blocks.BAMBOO)
                    || !s.isAir() && s.canBeReplaced() && s.getFluidState().isEmpty()) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
            }
        }
    }

    /** A block of the way: trodden ground, or (over water) a deck, whose place is returned. */
    private static BlockPos column(ServerLevel level, Village v, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos p = new BlockPos(x, top, z);
        BlockState s = level.getBlockState(p);
        if (!s.getFluidState().isEmpty()) {
            // water at the way's edge, a shallow crossing: earth heaped in to the surface, the way trodden on it
            int depth = 0;
            while (depth <= 4 && !level.getBlockState(p.below(depth)).getFluidState().isEmpty()) depth++;
            if (depth > 4) return null;
            for (int k = depth - 1; k >= 1; k--) level.setBlock(p.below(k), Blocks.DIRT.defaultBlockState(), FLAGS);
            level.setBlock(p, Blocks.DIRT_PATH.defaultBlockState(), FLAGS);
            return null;
        }
        if (s.is(BlockTags.PLANKS)) {
            // a deck laid before (the next stretch's): a deck still
            return null;
        }
        if (s.is(BlockTags.FENCES) && !level.getBlockState(p.below()).getFluidState().isEmpty()) {
            // the rail a stretch before put over the water, where this stretch's deck goes
            level.setBlock(p, wood(v, "planks"), FLAGS);
            return p;
        }
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS) || s.is(BlockTags.SLABS) || s.is(BlockTags.STAIRS)) {
            // a tree, somebody's wall or a fence: the way goes by it
            return null;
        }
        if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM)
                || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.SNOW_BLOCK) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL)) {
            level.setBlock(p, Blocks.DIRT_PATH.defaultBlockState(), FLAGS);
        } else if (s.is(Blocks.STONE) || s.is(Blocks.GRANITE) || s.is(Blocks.DIORITE) || s.is(Blocks.ANDESITE) || s.is(Blocks.TUFF)) {
            level.setBlock(p, Blocks.GRAVEL.defaultBlockState(), FLAGS);
        }
        // a clear way: grass, flowers and snow trodden down
        for (int k = 1; k <= 2; k++) {
            BlockPos above = p.above(k);
            BlockState st = level.getBlockState(above);
            if (!st.isAir() && st.canBeReplaced() && st.getFluidState().isEmpty()) level.setBlock(above, Blocks.AIR.defaultBlockState(), FLAGS);
        }
        return null;
    }

    private static BlockState wood(Village v, String part) {
        var b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.withDefaultNamespace(v.wood + "_" + part));
        return (b.isPresent() ? b.get() : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                net.minecraft.resources.Identifier.withDefaultNamespace("oak_" + part))).defaultBlockState();
    }
}
