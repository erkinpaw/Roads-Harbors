package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * What the land round a village gives: woods to fell, rock with ore in it, meadows to plough, and in the mountains a
 * vein of emerald. A village in a forest has wood to spare and one on a bare coast has little; one by the mountains
 * digs iron and coal, one on the plains hardly any: so villages are short of different things, and trade.
 * <p>
 * Read from the world's generator (its biomes and heights round the village, out to {@link #RADIUS} blocks), on a
 * thread of its own, once per village; the same land gives the same answer, so nothing of it is saved.
 */
public final class Land {

    static final int RADIUS = 128;
    private static final int STEP = 16;

    /**
     * Shares of the land round the village (0..1, of the dry land): woods, rock and heights, meadows; mountains (where
     * emeralds are found); and the share of all of it that is water.
     */
    public record Shares(double forest, double rock, double meadow, double mountain, double water) {
        /** Wood a woodcutter brings, against the usual. */
        public double wood() {
            return clamp(0.35 + 1.0 * forest + 0.15 * meadow, 0.35, 1.3);
        }

        /** Stone a miner breaks (there is stone under any land; in the rock it comes easier). */
        public double stone() {
            return clamp(0.75 + 0.5 * rock, 0.75, 1.25);
        }

        /** Iron and coal a miner finds: in the rock, not under the plains. */
        public double ore() {
            return clamp(0.15 + 1.4 * rock, 0.15, 1.4);
        }

        /** What the fields bear. */
        public double farm() {
            return clamp(0.8 + 0.4 * meadow, 0.8, 1.2);
        }

        /** The emeralds in the village's reach, all told: a vein in the mountains, none elsewhere. */
        public int vein() {
            return (int) Math.round(40 * mountain);
        }

        private static double clamp(double x, double lo, double hi) {
            return Math.max(lo, Math.min(hi, x));
        }
    }

    /** Before the land is read: the usual everywhere. */
    static final Shares USUAL = new Shares(0.65, 0.18, 0.3, 0, 0.3);

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "village-land");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Map<Integer, Shares> READY = new ConcurrentHashMap<>();
    private static final Map<Integer, Future<Shares>> READING = new ConcurrentHashMap<>();

    private Land() {
    }

    /** A world closed. */
    static void reset() {
        for (Future<Shares> f : READING.values()) f.cancel(true);
        READING.clear();
        READY.clear();
    }

    /** The land round a village, or null while it is being read (it is asked for then). Server thread. */
    public static Shares of(ServerLevel level, Village v) {
        Shares s = READY.get(v.id);
        if (s != null) return s;
        Future<Shares> f = READING.get(v.id);
        if (f == null) {
            var gen = level.getChunkSource().getGenerator();
            var rs = level.getChunkSource().randomState();
            int x0 = v.center.getX(), z0 = v.center.getZ();
            String name = v.name;
            READING.put(v.id, POOL.submit(() -> {
                Shares r = read(gen, rs, level, x0, z0);
                Minecraftportsmod.LOGGER.info("Land round {}: forest {}, rock {}, meadow {}, mountain {}, water {}", name,
                        pct(r.forest), pct(r.rock), pct(r.meadow), pct(r.mountain), pct(r.water));
                return r;
            }));
            return null;
        }
        if (!f.isDone()) return null;
        READING.remove(v.id);
        try {
            s = f.get();
        } catch (Exception e) {
            Minecraftportsmod.LOGGER.warn("Land round {} unreadable: {}", v.name, e.toString());
            s = USUAL;
        }
        READY.put(v.id, s);
        return s;
    }

    /** The land as it is known now (the usual until it has been read). */
    /** Has the land round the village been read yet? */
    static boolean ready(Village v) {
        return READY.containsKey(v.id);
    }

    static Shares known(Village v) {
        Shares s = READY.get(v.id);
        return s == null ? USUAL : s;
    }

    /** Reads it now, on this thread (tests, commands). */
    public static Shares now(ServerLevel level, Village v) {
        Shares s = READY.get(v.id);
        if (s != null) return s;
        Future<Shares> f = READING.remove(v.id);
        if (f != null) f.cancel(true);
        s = read(level.getChunkSource().getGenerator(), level.getChunkSource().randomState(), level, v.center.getX(), v.center.getZ());
        READY.put(v.id, s);
        return s;
    }

    private static String pct(double x) {
        return Math.round(x * 100) + "%";
    }

    private static Shares read(net.minecraft.world.level.chunk.ChunkGenerator gen, net.minecraft.world.level.levelgen.RandomState rs,
                               net.minecraft.world.level.LevelHeightAccessor heights, int x0, int z0) {
        int sea = gen.getSeaLevel();
        int land = 0, water = 0, forest = 0, rock = 0, meadow = 0, mountain = 0, all = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx += STEP) {
            for (int dz = -RADIUS; dz <= RADIUS; dz += STEP) {
                if (dx * dx + dz * dz > RADIUS * RADIUS) continue;
                int x = x0 + dx, z = z0 + dz;
                all++;
                Holder<Biome> b = gen.getBiomeSource().getNoiseBiome(x >> 2, sea >> 2, z >> 2, rs.sampler());
                if (b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_DEEP_OCEAN) || b.is(BiomeTags.IS_RIVER)) {
                    water++;
                    continue;
                }
                // (the heights only every other sample: they are the costly part)
                int h = (dx / STEP + dz / STEP) % 2 == 0 ? gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, rs) : Integer.MIN_VALUE;
                if (h != Integer.MIN_VALUE && h < sea) {
                    water++;
                    continue;
                }
                land++;
                String id = b.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
                boolean mount = b.is(BiomeTags.IS_MOUNTAIN) || id.contains("peaks") || id.contains("slopes");
                if (mount) mountain++;
                if (mount || b.is(BiomeTags.IS_HILL) || b.is(BiomeTags.IS_BADLANDS) || id.contains("stony") || id.contains("windswept")
                        || h != Integer.MIN_VALUE && h >= sea + 28) rock++;
                if (b.is(BiomeTags.IS_FOREST) || b.is(BiomeTags.IS_TAIGA) || b.is(BiomeTags.IS_JUNGLE) || id.contains("swamp") || id.contains("grove")) {
                    forest++;
                } else if (id.contains("plains") || id.contains("meadow") || b.is(BiomeTags.IS_SAVANNA) || id.contains("sunflower")) {
                    meadow++;
                }
            }
        }
        double l = Math.max(1, land);
        return new Shares(forest / l, rock / l, meadow / l, mountain / l, water / (double) Math.max(1, all));
    }
}
