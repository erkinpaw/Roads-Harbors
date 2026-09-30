package org.webtrade.minecraftportsmod.nav;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * First stage of long-distance pathfinding: A* over "water pieces" instead of blocks.
 * <p>
 * Every chunk is split into its connected pieces of navigable water ({@link ChunkNav#components()}); two pieces in
 * neighbouring chunks are linked when water crosses the chunk border between them. Searching this graph is cheap
 * (a 5000-block trip is only a few hundred steps) and follows any river bend or detour, with no search box.
 * The result is a corridor of chunks the block-level search is then confined to.
 */
final class CoarseRouter {

    /** Cost of crossing one chunk; small pieces (creeks, narrow canals) cost extra so wide water is preferred. */
    private static final float STEP = 16F;
    private static final int MAX_NODES = 1_500_000;

    enum Status {FOUND, NO_PATH, LIMIT_REACHED, CANCELLED}

    /** @param chunks interleaved {chunkX, chunkZ} of the corridor, start to end */
    record Result(Status status, int[] chunks, int expanded) {
    }

    private final DimensionNavCache cache;

    CoarseRouter(DimensionNavCache cache) {
        this.cache = cache;
    }

    private static long key(int cx, int cz, int comp) {
        return ((cx & 0x3FFFFFL) << 30) | ((cz & 0x3FFFFFL) << 8) | (comp & 0xFF);
    }

    Result find(int sx, int sz, int ex, int ez, BooleanSupplier cancelled) {
        int scx = sx >> 4, scz = sz >> 4, ecx = ex >> 4, ecz = ez >> 4;
        ChunkNav sn = cache.getChunk(scx, scz), en = cache.getChunk(ecx, ecz);
        if (sn == null || en == null) return new Result(Status.NO_PATH, new int[0], 0);
        int sc = sn.components().labels()[ChunkNav.index(sx & 15, sz & 15)];
        int ec = en.components().labels()[ChunkNav.index(ex & 15, ez & 15)];
        if (sc < 0 || ec < 0) return new Result(Status.NO_PATH, new int[0], 0);

        Nodes nodes = new Nodes();
        Heap open = new Heap();
        int start = nodes.add(scx, scz, sc, 0F, -1);
        open.push(start, heuristic(scx, scz, ecx, ecz));
        int goal = -1;
        int expanded = 0;
        int[] found = new int[8];

        while (open.size > 0) {
            int cur = open.pop();
            if (nodes.closed[cur]) continue;
            nodes.closed[cur] = true;
            int cx = nodes.cx[cur], cz = nodes.cz[cur], comp = nodes.comp[cur];
            if (cx == ecx && cz == ecz && comp == ec) {
                goal = cur;
                break;
            }
            if (++expanded > MAX_NODES) return new Result(Status.LIMIT_REACHED, new int[0], expanded);
            if ((expanded & 1023) == 0 && cancelled.getAsBoolean()) return new Result(Status.CANCELLED, new int[0], expanded);

            ChunkNav here = cache.getChunk(cx, cz);
            byte[] labels = here.components().labels();
            for (int dir = 0; dir < 4; dir++) {
                int ncx = cx + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
                int ncz = cz + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
                ChunkNav there = cache.getChunk(ncx, ncz);
                if (there == null) continue;
                byte[] nl = there.components().labels();
                int nFound = 0;
                for (int i = 0; i < 16; i++) {
                    int a, b;
                    switch (dir) {
                        case 0 -> { a = ChunkNav.index(15, i); b = ChunkNav.index(0, i); }
                        case 1 -> { a = ChunkNav.index(0, i); b = ChunkNav.index(15, i); }
                        case 2 -> { a = ChunkNav.index(i, 15); b = ChunkNav.index(i, 0); }
                        default -> { a = ChunkNav.index(i, 0); b = ChunkNav.index(i, 15); }
                    }
                    if (labels[a] != comp || nl[b] < 0) continue;
                    int nc = nl[b];
                    boolean dup = false;
                    for (int k = 0; k < nFound; k++) if (found[k] == nc) dup = true;
                    if (!dup && nFound < found.length) found[nFound++] = nc;
                }
                for (int k = 0; k < nFound; k++) {
                    int nc = found[k];
                    int size = there.components().sizes()[nc];
                    float step = STEP * (size >= 96 ? 1F : size >= 32 ? 1.25F : 1.8F);
                    float ng = nodes.g[cur] + step;
                    long nk = key(ncx, ncz, nc);
                    int existing = nodes.index.get(nk);
                    if (existing >= 0) {
                        if (nodes.closed[existing] || ng >= nodes.g[existing]) continue;
                        nodes.g[existing] = ng;
                        nodes.parent[existing] = cur;
                        open.push(existing, ng + heuristic(ncx, ncz, ecx, ecz));
                    } else {
                        int n = nodes.add(ncx, ncz, nc, ng, cur);
                        open.push(n, ng + heuristic(ncx, ncz, ecx, ecz));
                    }
                }
            }
        }
        if (goal < 0) return new Result(Status.NO_PATH, new int[0], expanded);

        int len = 0;
        for (int n = goal; n >= 0; n = nodes.parent[n]) len++;
        int[] chunks = new int[len * 2];
        int i = len - 1;
        for (int n = goal; n >= 0; n = nodes.parent[n], i--) {
            chunks[i * 2] = nodes.cx[n];
            chunks[i * 2 + 1] = nodes.cz[n];
        }
        return new Result(Status.FOUND, chunks, expanded);
    }

    private static float heuristic(int cx, int cz, int ecx, int ecz) {
        return (float) (Math.hypot(ecx - cx, ecz - cz) * STEP);
    }

    /** The chunks of a coarse path, widened by {@code radius} chunks on every side. */
    static LongOpenHashSet corridor(int[] chunks, int radius) {
        LongOpenHashSet set = new LongOpenHashSet();
        for (int i = 0; i < chunks.length; i += 2) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    set.add(chunkKey(chunks[i] + dx, chunks[i + 1] + dz));
                }
            }
        }
        return set;
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------ primitive containers

    private static final class Nodes {
        final Long2IntOpenHashMap index = new Long2IntOpenHashMap();
        int[] cx = new int[1024], cz = new int[1024], comp = new int[1024], parent = new int[1024];
        float[] g = new float[1024];
        boolean[] closed = new boolean[1024];
        int size;

        Nodes() {
            index.defaultReturnValue(-1);
        }

        int add(int x, int z, int c, float ng, int par) {
            if (size == cx.length) {
                int cap = size * 2;
                cx = Arrays.copyOf(cx, cap);
                cz = Arrays.copyOf(cz, cap);
                comp = Arrays.copyOf(comp, cap);
                parent = Arrays.copyOf(parent, cap);
                g = Arrays.copyOf(g, cap);
                closed = Arrays.copyOf(closed, cap);
            }
            int id = size++;
            cx[id] = x;
            cz[id] = z;
            comp[id] = c;
            g[id] = ng;
            parent[id] = par;
            index.put(key(x, z, c), id);
            return id;
        }
    }

    private static final class Heap {
        int[] node = new int[1024];
        float[] f = new float[1024];
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
