package org.webtrade.minecraftportsmod.colony;

import net.minecraft.server.level.ServerLevel;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The land round a village as the world's generator makes it, for when nobody is near and its chunks are not loaded:
 * the floor of every other column (and where there is water) out to {@link #RADIUS} blocks from its middle. Read once
 * per village, on a thread of its own (the generator's heights are costly: reading them on the server thread for
 * every plot a village looked at froze the game for minutes), then kept; the plots of the village's next buildings
 * are looked for on it (see {@link PlotFinder}).
 */
final class VillageTerrain {

    /** How far round the middle the land is read: the farthest plot a village looks at (its mine) and a margin. */
    static final int RADIUS = 144;
    /** Every how many blocks a column is read (the ones between take the nearest: enough to pick a plot by). */
    static final int STEP = 4;
    /** Threads the land is read on (the generator's noise is costly: a village's land is read in rows at once). */
    private static final int THREADS = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() / 2));
    private static final int WATER = Short.MIN_VALUE;

    /** One village at a time is read (by rows on {@link #ROWS}), the nearest asked first. */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "village-terrain");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final ExecutorService ROWS = Executors.newFixedThreadPool(THREADS, r -> {
        Thread t = new Thread(r, "village-terrain-rows");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Map<Integer, Grid> READY = new ConcurrentHashMap<>();
    private static final Map<Integer, Future<Grid>> READING = new ConcurrentHashMap<>();

    /** A world closed: the land read for its villages is dropped. */
    static void reset() {
        for (Future<Grid> f : READING.values()) f.cancel(true);
        READING.clear();
        READY.clear();
    }

    private VillageTerrain() {
    }

    /** The land read round a village's middle: the floor of each column read, or {@link #WATER}. */
    record Grid(int x0, int z0, int size, short[] floor) {
        /** The floor at a column (-1: water; null: outside what was read). */
        Integer at(int x, int z) {
            int i = Math.floorDiv(x - x0 + STEP / 2, STEP), j = Math.floorDiv(z - z0 + STEP / 2, STEP);
            if (i < 0 || j < 0 || i >= size || j >= size) return null;
            short f = floor[i * size + j];
            return f <= WATER + 64 ? -1 : (int) f;
        }

        /** How deep the water is over a column (0: land; null: outside what was read). */
        Integer depth(int x, int z) {
            int i = Math.floorDiv(x - x0 + STEP / 2, STEP), j = Math.floorDiv(z - z0 + STEP / 2, STEP);
            if (i < 0 || j < 0 || i >= size || j >= size) return null;
            short f = floor[i * size + j];
            return f <= WATER + 64 ? f - WATER : 0;
        }
    }

    /**
     * The land round a village, or null while it is still being read (it is asked for then, and ready in a few
     * seconds). Server thread.
     */
    static Grid grid(ServerLevel level, Village v) {
        Grid g = READY.get(v.id);
        if (g != null && g.x0 == v.center.getX() - RADIUS && g.z0 == v.center.getZ() - RADIUS) return g;
        Future<Grid> f = READING.get(v.id);
        if (f == null) {
            var gen = level.getChunkSource().getGenerator();
            var rs = level.getChunkSource().randomState();
            int x0 = v.center.getX() - RADIUS, z0 = v.center.getZ() - RADIUS, size = 2 * RADIUS / STEP + 1;
            String name = v.name;
            READING.put(v.id, POOL.submit(() -> {
                long t0 = System.currentTimeMillis();
                short[] floor = new short[size * size];
                java.util.List<Future<?>> rows = new java.util.ArrayList<>();
                for (int row = 0; row < size; row++) {
                    final int i = row;
                    rows.add(ROWS.submit(() -> {
                        for (int j = 0; j < size; j++) floor[i * size + j] = read(gen, rs, level, x0 + i * STEP, z0 + j * STEP);
                    }));
                }
                for (Future<?> r : rows) r.get();
                Minecraftportsmod.LOGGER.info("Land round {} read in {} ms", name, System.currentTimeMillis() - t0);
                return new Grid(x0, z0, size, floor);
            }));
            return null;
        }
        if (!f.isDone()) return null;
        READING.remove(v.id);
        try {
            g = f.get();
        } catch (Exception e) {
            Minecraftportsmod.LOGGER.warn("Land round {} unreadable: {}", v.name, e.toString());
            return null;
        }
        READY.put(v.id, g);
        return g;
    }

    /**
     * One column as the generator makes it, in one reading: the top of the ground (the floor stood on), or
     * {@link #WATER} where water stands over it.
     */
    private static short read(net.minecraft.world.level.chunk.ChunkGenerator gen, net.minecraft.world.level.levelgen.RandomState rs,
                              net.minecraft.world.level.LevelHeightAccessor level, int x, int z) {
        // (an island raised out of the sea: its land as it was raised)
        Integer isle = org.webtrade.minecraftportsmod.worldgen.RaisedIslands.floor(x, z, gen.getSeaLevel());
        if (isle != null) return isle < gen.getSeaLevel() ? (short) (WATER + Math.min(63, gen.getSeaLevel() - isle)) : (short) (int) isle;
        var column = gen.getBaseColumn(x, z, level, rs);
        for (int y = level.getMaxY(); y >= level.getMinY(); y--) {
            var st = column.getBlock(y);
            if (st.isAir()) continue;
            if (st.getFluidState().isEmpty()) return (short) (y + 1);
            // water: how deep it is kept with it
            int d = 0;
            while (d < 63 && y - d - 1 >= level.getMinY() && !column.getBlock(y - d - 1).getFluidState().isEmpty()) d++;
            return (short) (WATER + d + 1);
        }
        return WATER;
    }

    /** Forgets what was read (a new world, a village gone). */
    static void forget(int id) {
        READY.remove(id);
        Future<Grid> f = READING.remove(id);
        if (f != null) f.cancel(true);
    }
}
