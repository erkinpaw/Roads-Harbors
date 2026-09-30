package org.webtrade.minecraftportsmod.nav;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * A* over the sea-level navigation grid.
 * <ul>
 *     <li>8-directional movement, no corner cutting past land;</li>
 *     <li>cost grows near the shore, so routes prefer open water but still use narrow canals if they must;</li>
 *     <li>only scanned ("explored") chunks are passable — unknown water is never assumed;</li>
 *     <li>the raw grid path is then pulled tight into long straight legs that keep at least
 *     as much distance from the shore as the raw path did.</li>
 * </ul>
 * Pure function over an immutable cache snapshot, safe to run on a worker thread.
 */
public final class WaterPathfinder {

    /** Clearance levels are Chebyshev distance to the nearest non-navigable tile, capped. */
    private static final int MAX_CLEARANCE = 5;
    private static final float[] CLEARANCE_COST = {Float.POSITIVE_INFINITY, 6.0F, 2.0F, 1.35F, 1.1F, 1.0F};
    private static final float HEURISTIC_WEIGHT = 1.15F;
    private static final float SQRT2 = (float) Math.sqrt(2.0);
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    public enum Status {FOUND, NO_START, NO_END, NO_PATH, LIMIT_REACHED, CANCELLED}

    /**
     * @param waypoints smoothed path as interleaved block coordinates {x0, z0, x1, z1, ...};
     *                  sail through the centre of each block (+0.5)
     */
    public record Result(Status status, int[] waypoints, double length, int expanded) {
        public boolean found() {
            return status == Status.FOUND;
        }

        static Result fail(Status status, int expanded) {
            return new Result(status, new int[0], 0, expanded);
        }
    }

    /**
     * @param maxNodes     node expansion limit
     * @param margin       how far the search may stray outside the start/end bounding box
     * @param maxLegLength longest straight leg after smoothing
     */
    public record Options(int maxNodes, int margin, int maxLegLength) {
        public static Options route(double distance) {
            int margin = (int) Math.max(96, Math.min(1024, distance * 0.6));
            return new Options(4_000_000, margin, 48);
        }

        public static Options local() {
            return new Options(60_000, 48, 24);
        }
    }

    private final DimensionNavCache cache;
    private final DimensionNavCache.NavView nav;
    /** When set, the block search may only enter these chunks (the corridor from the coarse router). */
    private LongOpenHashSet allowedChunks;
    private final Long2ByteOpenHashMap clearanceMemo = new Long2ByteOpenHashMap();
    /** Extra impassable tiles on top of the terrain (e.g. the space around other berths). */
    private final LongOpenHashSet obstacles = new LongOpenHashSet();

    public WaterPathfinder(DimensionNavCache cache) {
        this.cache = cache;
        this.nav = cache.view();
        this.clearanceMemo.defaultReturnValue((byte) -1);
    }

    /**
     * Marks every tile within {@code radius} (Chebyshev) of the given points as impassable, like land.
     * Used so that the approach to one berth never cuts through the spot of another.
     */
    public WaterPathfinder withObstacles(Iterable<int[]> points, int radius) {
        for (int[] p : points) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    obstacles.add(key(p[0] + dx, p[1] + dz));
                }
            }
        }
        clearanceMemo.clear();
        return this;
    }

    private boolean free(int x, int z) {
        return nav.isNavigable(x, z) && (obstacles.isEmpty() || !obstacles.contains(key(x, z)));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** 0 = not navigable, 1..5 = distance to the nearest obstacle (5 = open water). */
    public int clearance(int x, int z) {
        long k = key(x, z);
        byte memo = clearanceMemo.get(k);
        if (memo >= 0) return memo;
        int result;
        if (!free(x, z)) {
            result = 0;
        } else {
            result = MAX_CLEARANCE;
            outer:
            for (int d = 1; d < MAX_CLEARANCE; d++) {
                for (int i = -d; i <= d; i++) {
                    if (!free(x + i, z - d) || !free(x + i, z + d)
                            || !free(x - d, z + i) || !free(x + d, z + i)) {
                        result = d;
                        break outer;
                    }
                }
            }
        }
        clearanceMemo.put(k, (byte) result);
        return result;
    }

    /** Finds the closest navigable block to (x, z) within {@code radius}, preferring open water. */
    public int[] snapToWater(int x, int z, int radius) {
        if (clearance(x, z) >= 1) return new int[]{x, z};
        int[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int c = clearance(x + dx, z + dz);
                if (c == 0) continue;
                double score = Math.sqrt(dx * dx + dz * dz) + (MAX_CLEARANCE - c) * 1.5;
                if (score < bestScore) {
                    bestScore = score;
                    best = new int[]{x + dx, z + dz};
                }
            }
        }
        return best;
    }

    /**
     * Long-distance search in two stages: first a cheap A* over connected pieces of water per chunk finds which
     * chunks the way goes through (following any river bend or detour, over any distance), then the block-level
     * A* runs only inside that corridor of chunks. If the coarse stage finds no connection there is none through
     * explored water.
     */
    public Result findRoute(int sx, int sz, int ex, int ez, BooleanSupplier cancelled) {
        if (clearance(sx, sz) == 0) return Result.fail(Status.NO_START, 0);
        if (clearance(ex, ez) == 0) return Result.fail(Status.NO_END, 0);
        CoarseRouter.Result coarse = new CoarseRouter(cache).find(sx, sz, ex, ez, cancelled);
        switch (coarse.status()) {
            case NO_PATH -> {
                return Result.fail(Status.NO_PATH, coarse.expanded());
            }
            case CANCELLED -> {
                return Result.fail(Status.CANCELLED, coarse.expanded());
            }
            case LIMIT_REACHED -> {
                return Result.fail(Status.LIMIT_REACHED, coarse.expanded());
            }
            default -> {
            }
        }
        Options unbounded = new Options(4_000_000, 1 << 24, 48);
        Result r = null;
        int total = coarse.expanded();
        // a 1-chunk margin lets the fine path cut corners; wider if that is too tight for some reason
        for (int radius : new int[]{1, 3}) {
            allowedChunks = CoarseRouter.corridor(coarse.chunks(), radius);
            try {
                r = find(sx, sz, ex, ez, unbounded, cancelled);
            } finally {
                allowedChunks = null;
            }
            total += r.expanded();
            if (r.status() != Status.NO_PATH) break;
        }
        return new Result(r.status(), r.waypoints(), r.length(), total);
    }

    public Result find(int sx, int sz, int ex, int ez, Options options, BooleanSupplier cancelled) {
        if (clearance(sx, sz) == 0) return Result.fail(Status.NO_START, 0);
        if (clearance(ex, ez) == 0) return Result.fail(Status.NO_END, 0);

        int minX = Math.min(sx, ex) - options.margin();
        int maxX = Math.max(sx, ex) + options.margin();
        int minZ = Math.min(sz, ez) - options.margin();
        int maxZ = Math.max(sz, ez) + options.margin();

        Nodes nodes = new Nodes();
        Heap open = new Heap();
        int start = nodes.add(sx, sz, 0F, -1);
        open.push(start, heuristic(sx, sz, ex, ez));

        int expanded = 0;
        int goal = -1;
        while (open.size > 0) {
            int current = open.pop();
            if (nodes.closed[current]) continue;
            nodes.closed[current] = true;
            int cx = nodes.x[current];
            int cz = nodes.z[current];
            if (cx == ex && cz == ez) {
                goal = current;
                break;
            }
            if (++expanded > options.maxNodes()) {
                return Result.fail(Status.LIMIT_REACHED, expanded);
            }
            if ((expanded & 4095) == 0 && cancelled.getAsBoolean()) {
                return Result.fail(Status.CANCELLED, expanded);
            }
            float g = nodes.g[current];
            for (int dir = 0; dir < 8; dir++) {
                int nx = cx + DX[dir];
                int nz = cz + DZ[dir];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                if (allowedChunks != null && (nx >> 4 != cx >> 4 || nz >> 4 != cz >> 4)
                        && !allowedChunks.contains(CoarseRouter.chunkKey(nx >> 4, nz >> 4))) continue;
                int c = clearance(nx, nz);
                if (c == 0) continue;
                boolean diagonal = dir >= 4;
                if (diagonal && (clearance(cx + DX[dir], cz) == 0 || clearance(cx, cz + DZ[dir]) == 0)) continue;
                float step = (diagonal ? SQRT2 : 1F) * CLEARANCE_COST[c];
                float ng = g + step;
                long nk = key(nx, nz);
                int existing = nodes.index.get(nk);
                if (existing >= 0) {
                    if (nodes.closed[existing] || ng >= nodes.g[existing]) continue;
                    nodes.g[existing] = ng;
                    nodes.parent[existing] = current;
                    open.push(existing, ng + heuristic(nx, nz, ex, ez));
                } else {
                    int n = nodes.add(nx, nz, ng, current);
                    open.push(n, ng + heuristic(nx, nz, ex, ez));
                }
            }
        }
        if (goal < 0) return Result.fail(Status.NO_PATH, expanded);

        // walk back
        int len = 0;
        for (int n = goal; n >= 0; n = nodes.parent[n]) len++;
        int[] raw = new int[len * 2];
        int i = len - 1;
        for (int n = goal; n >= 0; n = nodes.parent[n], i--) {
            raw[i * 2] = nodes.x[n];
            raw[i * 2 + 1] = nodes.z[n];
        }
        int[] smooth = smooth(raw, options.maxLegLength());
        return new Result(Status.FOUND, smooth, pathLength(smooth), expanded);
    }

    private static float heuristic(int x, int z, int ex, int ez) {
        int dx = Math.abs(x - ex);
        int dz = Math.abs(z - ez);
        int min = Math.min(dx, dz);
        int max = Math.max(dx, dz);
        return HEURISTIC_WEIGHT * (max - min + SQRT2 * min);
    }

    public static double pathLength(int[] path) {
        double total = 0;
        for (int i = 2; i < path.length; i += 2) {
            double dx = path[i] - path[i - 2];
            double dz = path[i + 1] - path[i - 1];
            total += Math.sqrt(dx * dx + dz * dz);
        }
        return total;
    }

    // ------------------------------------------------------------------ smoothing

    /**
     * String-pulling: from each anchor, jump to the furthest raw point that is reachable in a straight
     * line without getting closer to the shore than the raw path between them already does.
     */
    private int[] smooth(int[] raw, int maxLeg) {
        int n = raw.length / 2;
        if (n <= 2) return raw;
        int[] out = new int[raw.length];
        int outLen = 0;
        out[outLen++] = raw[0];
        out[outLen++] = raw[1];
        int anchor = 0;
        while (anchor < n - 1) {
            int best = anchor + 1;
            int minClear = clearance(raw[best * 2], raw[best * 2 + 1]);
            for (int k = anchor + 2; k < n; k++) {
                int kx = raw[k * 2];
                int kz = raw[k * 2 + 1];
                minClear = Math.min(minClear, clearance(kx, kz));
                double dx = kx - raw[anchor * 2];
                double dz = kz - raw[anchor * 2 + 1];
                if (dx * dx + dz * dz > (double) maxLeg * maxLeg) break;
                if (!lineClear(raw[anchor * 2], raw[anchor * 2 + 1], kx, kz, Math.min(minClear, 3))) break;
                best = k;
            }
            out[outLen++] = raw[best * 2];
            out[outLen++] = raw[best * 2 + 1];
            anchor = best;
        }
        return Arrays.copyOf(out, outLen);
    }

    /** Walks every block the segment between two block centres touches. */
    public boolean lineClear(int x0, int z0, int x1, int z1, int minClearance) {
        double sx = x0 + 0.5, sz = z0 + 0.5;
        double dx = x1 - x0, dz = z1 - z0;
        int x = x0, z = z0;
        int stepX = Integer.signum((int) dx), stepZ = Integer.signum((int) dz);
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : ((stepX > 0 ? (x0 + 1 - sx) : (sx - x0)) * tDeltaX);
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? (z0 + 1 - sz) : (sz - z0)) * tDeltaZ);
        int guard = Math.abs(x1 - x0) + Math.abs(z1 - z0) + 2;
        while (guard-- > 0) {
            if (clearance(x, z) < Math.max(1, minClearance)) return false;
            if (x == x1 && z == z1) return true;
            if (Math.abs(tMaxX - tMaxZ) < 1e-9) {
                // passing exactly through a corner: both side blocks must be water too
                if (clearance(x + stepX, z) == 0 || clearance(x, z + stepZ) == 0) return false;
                x += stepX;
                z += stepZ;
                tMaxX += tDeltaX;
                tMaxZ += tDeltaZ;
            } else if (tMaxX < tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        return false;
    }

    /** Checks that every leg of an existing path is still sailable (used to detect blocked routes). */
    public boolean pathStillValid(int[] path) {
        for (int i = 2; i < path.length; i += 2) {
            if (!lineClear(path[i - 2], path[i - 1], path[i], path[i + 1], 1)) return false;
        }
        return path.length >= 2 && clearance(path[0], path[1]) > 0;
    }

    // ------------------------------------------------------------------ primitive containers

    private static final class Nodes {
        final Long2IntOpenHashMap index = new Long2IntOpenHashMap();
        int[] x = new int[4096];
        int[] z = new int[4096];
        float[] g = new float[4096];
        int[] parent = new int[4096];
        boolean[] closed = new boolean[4096];
        int size;

        Nodes() {
            index.defaultReturnValue(-1);
        }

        int add(int nx, int nz, float ng, int par) {
            if (size == x.length) {
                int cap = size * 2;
                x = Arrays.copyOf(x, cap);
                z = Arrays.copyOf(z, cap);
                g = Arrays.copyOf(g, cap);
                parent = Arrays.copyOf(parent, cap);
                closed = Arrays.copyOf(closed, cap);
            }
            int id = size++;
            x[id] = nx;
            z[id] = nz;
            g[id] = ng;
            parent[id] = par;
            index.put(key(nx, nz), id);
            return id;
        }
    }

    /** Binary min-heap of (node, f) with lazy deletion. */
    private static final class Heap {
        int[] node = new int[4096];
        float[] f = new float[4096];
        int size;

        void push(int n, float priority) {
            if (size == node.length) {
                node = Arrays.copyOf(node, size * 2);
                f = Arrays.copyOf(f, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (f[p] <= priority) break;
                node[i] = node[p];
                f[i] = f[p];
                i = p;
            }
            node[i] = n;
            f[i] = priority;
        }

        int pop() {
            int result = node[0];
            size--;
            if (size > 0) {
                int lastNode = node[size];
                float lastF = f[size];
                int i = 0;
                while (true) {
                    int l = i * 2 + 1;
                    if (l >= size) break;
                    int r = l + 1;
                    int c = (r < size && f[r] < f[l]) ? r : l;
                    if (f[c] >= lastF) break;
                    node[i] = node[c];
                    f[i] = f[c];
                    i = c;
                }
                node[i] = lastNode;
                f[i] = lastF;
            }
            return result;
        }
    }
}
