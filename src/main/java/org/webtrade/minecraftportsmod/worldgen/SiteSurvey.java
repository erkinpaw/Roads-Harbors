package org.webtrade.minecraftportsmod.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/**
 * Reads the terrain generator — heights and biomes — without generating chunks. Picks village sites by the water
 * and finds rough water lanes between them. Thread-safe (the generator's noise is), used off the server thread.
 */
final class SiteSurvey {

    /** Sites closer than this to each other are not planned. */
    static final int MIN_SPACING = 750;
    /** Lanes are searched between sites at most this far apart (straight line). */
    static final int MAX_LANE = 2600;
    private static final int GRID = 32;
    private static final int MARGIN = 120;
    private static final int LANE_STEP = 8;
    private static final int LANE_MAX_NODES = 400_000;

    private final ChunkGenerator generator;
    private final RandomState random;
    private final LevelHeightAccessor heights;
    private final int sea;
    private final long seed;

    SiteSurvey(ChunkGenerator generator, RandomState random, LevelHeightAccessor heights, long seed) {
        this.generator = generator;
        this.random = random;
        this.heights = heights;
        this.sea = generator.getSeaLevel();
        this.seed = seed;
    }

    // ------------------------------------------------------------------ sampling

    private Holder<Biome> biome(int x, int z) {
        return generator.getBiomeSource().getNoiseBiome(x >> 2, sea >> 2, z >> 2, random.sampler());
    }

    /** Biome-level water a boat can use: oceans and rivers. */
    boolean waterBiome(int x, int z) {
        Holder<Biome> b = biome(x, z);
        return b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_RIVER);
    }

    private boolean riverBiome(int x, int z) {
        return biome(x, z).is(BiomeTags.IS_RIVER);
    }

    /** y of the first free block above the ground or sea floor. */
    private int floor(int x, int z) {
        return generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, random);
    }

    // ------------------------------------------------------------------ sites

    /** A chosen site: stand-on-ground point by the water. */
    record Found(int x, int z, boolean river) {
    }

    /**
     * The best village spot in a cell, or null. A spot is low land (at most 6 above the sea) next to navigable
     * water (sea floor at least 3 below the surface), fairly flat, by a body of ocean or river water big enough to
     * sail out of, and not too close to other sites.
     */
    Found planCell(int cx, int cz, List<int[]> existing, BooleanSupplier cancelled) {
        int x0 = cx * WorldPlan.CELL, z0 = cz * WorldPlan.CELL;
        RandomSource rnd = RandomSource.create(seed ^ WorldPlan.cellKey(cx, cz) * 0x9E3779B97F4A7C15L);
        int ccx = x0 + WorldPlan.CELL / 2 + rnd.nextInt(301) - 150;
        int ccz = z0 + WorldPlan.CELL / 2 + rnd.nextInt(301) - 150;
        Found best = null;
        double bestScore = -1e9;
        for (int x = x0 + MARGIN; x < x0 + WorldPlan.CELL - MARGIN; x += GRID) {
            if (cancelled.getAsBoolean()) return null;
            for (int z = z0 + MARGIN; z < z0 + WorldPlan.CELL - MARGIN; z += GRID) {
                boolean spaced = true;
                for (int[] e : existing) {
                    long dx = e[0] - x, dz = e[1] - z;
                    if (dx * dx + dz * dz < (long) MIN_SPACING * MIN_SPACING) {
                        spaced = false;
                        break;
                    }
                }
                if (!spaced) continue;
                double score = score(x, z, rnd);
                if (score <= -1e8) continue;
                score -= Math.hypot(x - ccx, z - ccz) / 250.0;
                if (score > bestScore) {
                    bestScore = score;
                    best = new Found(x, z, riverNearby(x, z));
                }
            }
        }
        return best;
    }

    private double score(int x, int z, RandomSource rnd) {
        // cheap biome checks first: most of a cell is inland, and heights are costly to compute
        if (waterBiome(x, z)) return -1e9;
        boolean waterNear = false;
        for (int a = 0; a < 8 && !waterNear; a++) {
            waterNear = waterBiome(x + (int) Math.round(Math.cos(a * Math.PI / 4) * 20), z + (int) Math.round(Math.sin(a * Math.PI / 4) * 20));
        }
        if (!waterNear) return -1e9;
        int ground = floor(x, z);
        if (ground < sea || ground > sea + 6) return -1e9;          // under water, or too high
        // navigable water right next to it
        boolean nearDeep = false;
        int nearWater = 0;
        for (int d = 10; d <= 26 && !nearDeep; d += 8) {
            for (int a = 0; a < 8; a++) {
                int wx = x + (int) Math.round(Math.cos(a * Math.PI / 4) * d);
                int wz = z + (int) Math.round(Math.sin(a * Math.PI / 4) * d);
                int f = floor(wx, wz);
                if (f < sea) nearWater++;
                if (f <= sea - 3 && waterBiome(wx, wz)) {
                    nearDeep = true;
                    break;
                }
            }
        }
        if (!nearDeep) return -1e9;
        // a real body of water, not a puddle: ocean/river biome around
        int body = 0;
        for (int dx = -64; dx <= 64; dx += 32) {
            for (int dz = -64; dz <= 64; dz += 32) {
                if (waterBiome(x + dx, z + dz)) body++;
            }
        }
        if (body < 4) return -1e9;
        // flat enough to build on
        int flat = 0;
        int[][] around = {{8, 0}, {-8, 0}, {0, 8}, {0, -8}, {-14, -14}, {14, 14}};
        for (int[] o : around) {
            int f = floor(x + o[0], z + o[1]);
            if (f >= sea && Math.abs(f - ground) <= 2) flat++;
        }
        if (flat < 3) return -1e9;
        return flat * 0.4 + Math.min(body, 12) * 0.1 + nearWater * 0.05 + (ground <= sea + 2 ? 0.5 : 0) + rnd.nextDouble() * 0.6;
    }

    private boolean riverNearby(int x, int z) {
        int river = 0, ocean = 0;
        for (int dx = -48; dx <= 48; dx += 16) {
            for (int dz = -48; dz <= 48; dz += 16) {
                Holder<Biome> b = biome(x + dx, z + dz);
                if (b.is(BiomeTags.IS_RIVER)) river++;
                else if (b.is(BiomeTags.IS_OCEAN)) ocean++;
            }
        }
        return river > ocean;
    }

    String probe(int x, int z) {
        return "floor(OCEAN_FLOOR_WG)=" + floor(x, z) + " surface(WORLD_SURFACE_WG)="
                + generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heights, random) + " sea=" + sea
                + " waterBiome=" + waterBiome(x, z) + " biome=" + biome(x, z).unwrapKey().map(k -> k.identifier().getPath()).orElse("?")
                + " predictedNavigable=" + predictChunk(x >> 4, z >> 4).navigableCount();
    }

    // ------------------------------------------------------------------ predicted water

    /**
     * What a boat would find in a chunk that was never generated: water at sea level wherever the ground lies below
     * it (sampled every 4 blocks). Frozen waters are left out — their ice would stop a boat.
     */
    org.webtrade.minecraftportsmod.nav.ChunkNav predictChunk(int cx, int cz) {
        byte[] flags = new byte[org.webtrade.minecraftportsmod.nav.ChunkNav.COLUMNS];
        byte[] colors = new byte[org.webtrade.minecraftportsmod.nav.ChunkNav.COLUMNS];
        int bx = cx << 4, bz = cz << 4;
        boolean frozen = biome(bx + 8, bz + 8).unwrapKey().map(k -> k.identifier().getPath().contains("frozen")).orElse(false);
        if (!frozen) {
            // one sample per 4×4 blocks: the generator's heights are costly, and a real scan refines it later
            for (int lz = 0; lz < 16; lz += 4) {
                for (int lx = 0; lx < 16; lx += 4) {
                    if (floor(bx + lx + 2, bz + lz + 2) >= sea) continue;
                    byte f = (byte) (org.webtrade.minecraftportsmod.nav.ChunkNav.FLAG_NAVIGABLE | org.webtrade.minecraftportsmod.nav.ChunkNav.FLAG_SURFACE_WATER);
                    for (int dz = 0; dz < 4; dz++) {
                        for (int dx = 0; dx < 4; dx++) flags[org.webtrade.minecraftportsmod.nav.ChunkNav.index(lx + dx, lz + dz)] = f;
                    }
                }
            }
        }
        return new org.webtrade.minecraftportsmod.nav.ChunkNav(org.webtrade.minecraftportsmod.nav.ChunkNav.PREDICTED_VERSION, flags, colors);
    }

    /** Chunks within {@code radius} chunks of a polyline (block coordinates, interleaved x, z). */
    static java.util.LinkedHashSet<Long> corridor(int[] path, int radius) {
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
        for (int i = 0; i + 3 < path.length; i += 2) {
            int ax = path[i], az = path[i + 1], bx = path[i + 2], bz = path[i + 3];
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, bz - az) / 8));
            for (int s = 0; s <= steps; s++) {
                int x = ax + (bx - ax) * s / steps, z = az + (bz - az) * s / steps;
                int cx = x >> 4, cz = z >> 4;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) out.add(((long) (cx + dx) << 32) | ((cz + dz) & 0xFFFFFFFFL));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ lanes

    /**
     * A rough lane over ocean/river biomes between two sites (A* on an 8-block grid, 8 directions), simplified to
     * its corners; null if the waters don't connect within reach.
     */
    int[] lane(int ax, int az, int bx, int bz, BooleanSupplier cancelled) {
        Map<Long, Boolean> water = new HashMap<>();
        int[] s = nearestWater(ax, az, water);
        int[] g = nearestWater(bx, bz, water);
        if (s == null || g == null) return null;
        int minX = Math.min(s[0], g[0]) - 500, maxX = Math.max(s[0], g[0]) + 500;
        int minZ = Math.min(s[1], g[1]) - 500, maxZ = Math.max(s[1], g[1]) + 500;

        record Node(int x, int z, double f) {
        }
        PriorityQueue<Node> open = new PriorityQueue<>((p, q) -> Double.compare(p.f, q.f));
        Map<Long, Double> cost = new HashMap<>();
        Map<Long, Long> from = new HashMap<>();
        long start = key(s[0], s[1]), goal = key(g[0], g[1]);
        cost.put(start, 0.0);
        open.add(new Node(s[0], s[1], Math.hypot(g[0] - s[0], g[1] - s[1])));
        int expanded = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        while (!open.isEmpty()) {
            Node n = open.poll();
            long k = key(n.x, n.z);
            if (k == goal) return simplify(rebuild(from, goal), water);
            if (++expanded > LANE_MAX_NODES || (expanded & 1023) == 0 && cancelled.getAsBoolean()) return null;
            double c = cost.get(k);
            for (int[] d : dirs) {
                int nx = n.x + d[0] * LANE_STEP, nz = n.z + d[1] * LANE_STEP;
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                if (!isWater(nx, nz, water)) continue;
                double step = d[0] != 0 && d[1] != 0 ? LANE_STEP * 1.4142 : LANE_STEP;
                long nk = key(nx, nz);
                double nc = c + step;
                Double old = cost.get(nk);
                if (old != null && old <= nc) continue;
                cost.put(nk, nc);
                from.put(nk, k);
                open.add(new Node(nx, nz, nc + Math.hypot(g[0] - nx, g[1] - nz)));
            }
        }
        return null;
    }

    private boolean isWater(int x, int z, Map<Long, Boolean> cache) {
        return cache.computeIfAbsent(key(x, z), k -> waterBiome(x, z));
    }

    private int[] nearestWater(int x, int z, Map<Long, Boolean> cache) {
        int bx = Math.floorDiv(x, LANE_STEP) * LANE_STEP, bz = Math.floorDiv(z, LANE_STEP) * LANE_STEP;
        for (int r = 0; r <= 96; r += LANE_STEP) {
            for (int dx = -r; dx <= r; dx += LANE_STEP) {
                for (int dz = -r; dz <= r; dz += LANE_STEP) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    if (isWater(bx + dx, bz + dz, cache)) return new int[]{bx + dx, bz + dz};
                }
            }
        }
        return null;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static List<int[]> rebuild(Map<Long, Long> from, long goal) {
        List<int[]> pts = new ArrayList<>();
        Long k = goal;
        while (k != null) {
            pts.add(new int[]{(int) (k >> 32), (int) (long) k});
            k = from.get(k);
        }
        java.util.Collections.reverse(pts);
        return pts;
    }

    /** Keeps only the points where the straight line over water would break. */
    private int[] simplify(List<int[]> pts, Map<Long, Boolean> water) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        out.add(pts.get(0));
        while (i < pts.size() - 1) {
            int j = pts.size() - 1;
            while (j > i + 1 && !clear(pts.get(i), pts.get(j), water)) j--;
            out.add(pts.get(j));
            i = j;
        }
        int[] r = new int[out.size() * 2];
        for (int k = 0; k < out.size(); k++) {
            r[k * 2] = out.get(k)[0];
            r[k * 2 + 1] = out.get(k)[1];
        }
        return r;
    }

    private boolean clear(int[] a, int[] b, Map<Long, Boolean> water) {
        double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
        int steps = (int) Math.ceil(len / 4);
        for (int s = 1; s < steps; s++) {
            int x = (int) Math.round(a[0] + (b[0] - a[0]) * (double) s / steps);
            int z = (int) Math.round(a[1] + (b[1] - a[1]) * (double) s / steps);
            if (!isWater(Math.floorDiv(x, LANE_STEP) * LANE_STEP, Math.floorDiv(z, LANE_STEP) * LANE_STEP, water)) return false;
        }
        return true;
    }

    static double length(int[] path) {
        double l = 0;
        for (int i = 2; i < path.length; i += 2) l += Math.hypot(path[i] - path[i - 2], path[i + 1] - path[i - 1]);
        return l;
    }
}
