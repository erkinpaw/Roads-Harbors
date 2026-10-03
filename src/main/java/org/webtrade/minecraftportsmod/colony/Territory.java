package org.webtrade.minecraftportsmod.colony;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The village's own land: the ground round its square, its buildings (standing and planned) and its fields, in cells
 * of {@link #CELL} blocks, the gaps between them closed up into one piece. On it only what the village made has a
 * place: its people fell every tree, fill every pit and take away whatever the wild left there. Its edge is where a
 * palisade will one day stand. Worked out from the buildings, so it grows (and shrinks) with them.
 * <p>
 * Left out of it: the miners' house far out by the rock, and the woodcutters' grove (their hut's own yard aside).
 */
public final class Territory {

    /** The size of a cell of the land, in blocks. */
    public static final int CELL = 4;
    /** Blocks of the village's land round the square, and round each plot. */
    static final int SQUARE = 14, MARGIN = 6;
    /** Gaps up to this many cells between pieces of land are closed up. */
    private static final int CLOSE = 2;

    /** Does the land grow only so far for the people it has (a test of either way; see {@link #cap}). */
    public static boolean capped = Boolean.parseBoolean(System.getProperty("mpm.territoryCap", "false"));

    private record Cache(int sig, Set<Long> cells) {
    }

    private static final Map<Integer, Cache> CACHE = new HashMap<>();

    private Territory() {
    }

    static void clear() {
        CACHE.clear();
    }

    static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** Is the column (x, z) the village's own land? */
    public static boolean contains(Village v, int x, int z) {
        return cells(v).contains(key(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL)));
    }

    /** The cells of the village's land. */
    public static Set<Long> cells(Village v) {
        int sig = signature(v);
        Cache c = CACHE.get(v.id);
        if (c == null || c.sig != sig) {
            c = new Cache(sig, compute(v, null, 0));
            CACHE.put(v.id, c);
        }
        return c.cells;
    }

    /** The land's area in blocks. */
    public static int area(Village v) {
        return cells(v).size() * CELL * CELL;
    }

    /**
     * The most land the village may hold with the people it has, when the land is capped: room for the square and
     * a few plots to start with, and more for every soul.
     */
    static int cap(Village v) {
        return 1500 + 250 * v.dwellers.size();
    }

    /** May a building of this size go up here as far as the land is concerned (always, uncapped)? */
    static boolean allows(Village v, BuildingType type, int x, int z) {
        if (!capped || outpost(type)) return true;
        // (the cells the plot would add: the closing up of gaps aside, which is small)
        Set<Long> have = cells(v);
        int r = type.half + MARGIN, added = 0;
        for (int cx = Math.floorDiv(x - r, CELL); cx <= Math.floorDiv(x + r, CELL); cx++) {
            for (int cz = Math.floorDiv(z - r, CELL); cz <= Math.floorDiv(z + r, CELL); cz++) if (!have.contains(key(cx, cz))) added++;
        }
        return added == 0 || (have.size() + added) * CELL * CELL <= cap(v);
    }

    /** Buildings that stand apart from the village, not on its land. */
    static boolean outpost(BuildingType t) {
        return t == BuildingType.MINE_HOUSE;
    }

    private static int signature(Village v) {
        int s = v.center.hashCode();
        for (Building b : v.buildings) s = s * 31 + b.origin.hashCode() * 7 + b.type.ordinal();
        for (Plots.Plot p : v.tasks.plots()) s = s * 31 + p.marker().hashCode();
        return s;
    }

    /** The land of the village (with one more plot of type {@code extra} at {@code at}, if not null). */
    private static Set<Long> compute(Village v, BuildingType extra, long at) {
        Set<Long> cells = new HashSet<>();
        mark(cells, v.center.getX(), v.center.getZ(), SQUARE);
        for (Building b : v.buildings) {
            if (outpost(b.type)) continue;
            mark(cells, b.origin.getX(), b.origin.getZ(), b.type.half + (b.type == BuildingType.WOOD_HUT ? 2 : MARGIN));
        }
        if (extra != null) mark(cells, (int) (at >> 32), (int) at, extra.half + MARGIN);
        for (Plots.Plot p : v.tasks.plots()) mark(cells, p.marker().getX(), p.marker().getZ(), Plots.HALF + MARGIN);
        close(cells);
        // the woodcutters' grove is theirs to plant and fell: only their hut's own yard is the village's
        for (Building b : v.buildings) {
            if (b.type != BuildingType.WOOD_HUT) continue;
            int g = b.type.half + 14, yard = b.type.half + 2;
            for (int x = -g; x <= g; x += CELL) {
                for (int z = -g; z <= g; z += CELL) {
                    if (Math.abs(x) <= yard && Math.abs(z) <= yard) continue;
                    long k = key(Math.floorDiv(b.origin.getX() + x, CELL), Math.floorDiv(b.origin.getZ() + z, CELL));
                    if (!near(v, b, b.origin.getX() + x, b.origin.getZ() + z)) cells.remove(k);
                }
            }
        }
        return cells;
    }

    /** Is the column close by some other plot of the village (not the given one) or the square? */
    private static boolean near(Village v, Building except, int x, int z) {
        if (Math.abs(x - v.center.getX()) <= SQUARE && Math.abs(z - v.center.getZ()) <= SQUARE) return true;
        for (Building b : v.buildings) {
            if (b == except || outpost(b.type) || b.type == BuildingType.WOOD_HUT) continue;
            int h = b.type.half + MARGIN;
            if (Math.abs(x - b.origin.getX()) <= h && Math.abs(z - b.origin.getZ()) <= h) return true;
        }
        return false;
    }

    private static void mark(Set<Long> cells, int x, int z, int r) {
        for (int cx = Math.floorDiv(x - r, CELL); cx <= Math.floorDiv(x + r, CELL); cx++) {
            for (int cz = Math.floorDiv(z - r, CELL); cz <= Math.floorDiv(z + r, CELL); cz++) cells.add(key(cx, cz));
        }
    }

    /** Closes the gaps: grown by {@link #CLOSE} cells all round, then shrunk back (what was filled in stays). */
    private static void close(Set<Long> cells) {
        Set<Long> grown = new HashSet<>();
        for (long k : cells) {
            int cx = (int) (k >> 32), cz = (int) k;
            for (int dx = -CLOSE; dx <= CLOSE; dx++) for (int dz = -CLOSE; dz <= CLOSE; dz++) grown.add(key(cx + dx, cz + dz));
        }
        Set<Long> out = new HashSet<>();
        for (long k : grown) {
            int cx = (int) (k >> 32), cz = (int) k;
            boolean inner = true;
            for (int dx = -CLOSE; dx <= CLOSE && inner; dx++) {
                for (int dz = -CLOSE; dz <= CLOSE && inner; dz++) inner = grown.contains(key(cx + dx, cz + dz));
            }
            if (inner) out.add(k);
        }
        out.addAll(cells);
        cells.clear();
        cells.addAll(out);
    }
}
