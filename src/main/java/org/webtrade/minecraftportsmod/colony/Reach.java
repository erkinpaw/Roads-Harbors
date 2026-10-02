package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Where the people of a village can walk to from its square on dry feet: a step up or down of a block at most, over
 * the land and the bridges, never through water. Worked out over the loaded land round the village now and then;
 * work across a river or up a cliff no one can climb is not taken on.
 */
public final class Reach {

    /** How far round the square the walkable land is worked out. */
    static final int RADIUS = 144;
    /** How long (ticks) a map of it is kept. */
    private static final int KEEP = 400;
    private static final int SIZE = RADIUS * 2 + 1;

    private record Map2(long time, int x0, int z0, short[] floor) {
        /** The floor of a reachable column, or Short.MIN_VALUE. */
        int at(int x, int z) {
            int i = x - x0, j = z - z0;
            if (i < 0 || j < 0 || i >= SIZE || j >= SIZE) return Short.MIN_VALUE;
            return floor[i * SIZE + j];
        }
    }

    private static final Map<Integer, Map2> MAPS = new HashMap<>();

    private Reach() {
    }

    static void clear() {
        MAPS.clear();
    }

    /** Forget the village's map (the land changed a lot: a bridge put up). */
    static void forget(Village v) {
        MAPS.remove(v.id);
    }

    /**
     * Can someone from the village walk to (stand next to) this spot on dry feet? True as well where it can't be
     * known (the land round the square not loaded, the spot out of the map's reach).
     */
    public static boolean ok(ServerLevel level, Village v, BlockPos p) {
        Map2 m = map(level, v);
        if (m == null) return true;
        int i = p.getX() - m.x0, j = p.getZ() - m.z0;
        if (i < 2 || j < 2 || i >= SIZE - 2 || j >= SIZE - 2) return true;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int f = m.at(p.getX() + dx, p.getZ() + dz);
                if (f != Short.MIN_VALUE && Math.abs(f - p.getY()) <= 4) return true;
            }
        }
        return false;
    }

    private static Map2 map(ServerLevel level, Village v) {
        long now = level.getGameTime();
        Map2 m = MAPS.get(v.id);
        if (m != null && now - m.time < KEEP && now >= m.time) return m;
        if (!Construction.loaded(level, v.center)) return null;
        m = compute(level, v, now);
        MAPS.put(v.id, m);
        return m;
    }

    private static Map2 compute(ServerLevel level, Village v, long now) {
        int x0 = v.center.getX() - RADIUS, z0 = v.center.getZ() - RADIUS;
        short[] floor = new short[SIZE * SIZE];
        short[] seen = new short[SIZE * SIZE];
        java.util.Arrays.fill(floor, Short.MIN_VALUE);
        // the height of each column, read when first come to (Short.MIN_VALUE + 1: water or not loaded)
        java.util.Arrays.fill(seen, Short.MIN_VALUE);
        Deque<int[]> open = new ArrayDeque<>();
        // set out from round the square (the fire or the well in the middle stands on it)
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.abs(dx) < 3 && Math.abs(dz) < 3) continue;
                int i = RADIUS + dx, j = RADIUS + dz;
                int f = height(level, x0, z0, i, j, seen);
                if (f <= Short.MIN_VALUE + 1 || floor[i * SIZE + j] != Short.MIN_VALUE) continue;
                floor[i * SIZE + j] = (short) f;
                open.add(new int[]{i, j});
            }
        }
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty()) {
            int[] c = open.poll();
            int f = floor[c[0] * SIZE + c[1]];
            for (int[] s : steps) {
                int i = c[0] + s[0], j = c[1] + s[1];
                if (i < 0 || j < 0 || i >= SIZE || j >= SIZE || floor[i * SIZE + j] != Short.MIN_VALUE) continue;
                int g = height(level, x0, z0, i, j, seen);
                if (g <= Short.MIN_VALUE + 1 || Math.abs(g - f) > 1) continue;
                floor[i * SIZE + j] = (short) g;
                open.add(new int[]{i, j});
            }
        }
        return new Map2(now, x0, z0, floor);
    }

    /** The floor of a column (where one stands), or Short.MIN_VALUE + 1 for water, a column not loaded. */
    private static int height(ServerLevel level, int x0, int z0, int i, int j, short[] seen) {
        int k = i * SIZE + j;
        if (seen[k] != Short.MIN_VALUE) return seen[k];
        int x = x0 + i, z = z0 + j;
        int out;
        if (!Construction.loaded(level, new BlockPos(x, 0, z))) {
            out = Short.MIN_VALUE + 1;
        } else {
            int f = PlotFinder.floorAt(level, x, z);
            BlockState under = level.getBlockState(new BlockPos(x, f - 1, z));
            BlockState at = level.getBlockState(new BlockPos(x, f, z));
            out = !under.getFluidState().isEmpty() || !at.getFluidState().isEmpty() ? Short.MIN_VALUE + 1 : f;
        }
        seen[k] = (short) out;
        return out;
    }
}
