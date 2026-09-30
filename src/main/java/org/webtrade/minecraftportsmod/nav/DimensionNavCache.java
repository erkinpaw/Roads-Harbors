package org.webtrade.minecraftportsmod.nav;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Navigation cache for one dimension. Regions are loaded lazily from disk the first time
 * anything (scanner, pathfinder, chart) touches them, and stay in memory afterwards.
 * <p>
 * Thread-safety: region lookup is a {@link ConcurrentHashMap}, chunk entries are immutable
 * {@link ChunkNav}s behind an atomic array, so worker threads may read while the server thread writes.
 */
public final class DimensionNavCache {

    private final ResourceKey<Level> dimension;
    private final Path dir;
    private final int seaLevel;
    private final ConcurrentHashMap<Long, NavRegion> regions = new ConcurrentHashMap<>();

    DimensionNavCache(ResourceKey<Level> dimension, Path dir, int seaLevel) {
        this.dimension = dimension;
        this.dir = dir;
        this.seaLevel = seaLevel;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    /** Y of the water surface block boats float on. */
    public int waterY() {
        return seaLevel - 1;
    }

    private NavRegion region(int chunkX, int chunkZ) {
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        return regions.computeIfAbsent(NavRegion.key(rx, rz), k -> NavRegion.load(dir, rx, rz));
    }

    public ChunkNav getChunk(int chunkX, int chunkZ) {
        return region(chunkX, chunkZ).get(chunkX, chunkZ);
    }

    ChunkNav putChunk(int chunkX, int chunkZ, ChunkNav nav) {
        return region(chunkX, chunkZ).put(chunkX, chunkZ, nav);
    }

    /**
     * Block-level lookup.
     *
     * @return 1 = navigable, 0 = blocked (land, ice, wrong water level), -1 = never scanned
     */
    public int navState(int x, int z) {
        ChunkNav nav = getChunk(x >> 4, z >> 4);
        if (nav == null) return -1;
        return nav.isNavigable(x & 15, z & 15) ? 1 : 0;
    }

    public boolean isNavigable(int x, int z) {
        return navState(x, z) == 1;
    }

    /** A cheap single-thread view that remembers the last chunk it looked at. */
    public NavView view() {
        return new NavView(this);
    }

    public int loadedRegionCount() {
        return regions.size();
    }

    public int cachedChunkCount() {
        int n = 0;
        for (NavRegion r : regions.values()) n += r.count();
        return n;
    }

    public void clear() {
        regions.clear();
    }

    void saveDirty() {
        for (NavRegion region : regions.values()) {
            if (!region.isDirty()) continue;
            try {
                region.save(dir);
            } catch (IOException e) {
                Minecraftportsmod.LOGGER.error("Failed to save nav region {} {} in {}", region.regionX, region.regionZ, dimension.identifier(), e);
            }
        }
    }

    /** Per-thread lookup helper for hot loops (A*, smoothing). Not thread-safe itself. */
    public static final class NavView {
        private final DimensionNavCache cache;
        private long lastKey = Long.MIN_VALUE;
        private ChunkNav last;

        private NavView(DimensionNavCache cache) {
            this.cache = cache;
        }

        public int navState(int x, int z) {
            int cx = x >> 4;
            int cz = z >> 4;
            long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
            if (key != lastKey) {
                last = cache.getChunk(cx, cz);
                lastKey = key;
            }
            if (last == null) return -1;
            return last.isNavigable(x & 15, z & 15) ? 1 : 0;
        }

        public boolean isNavigable(int x, int z) {
            return navState(x, z) == 1;
        }

        public DimensionNavCache cache() {
            return cache;
        }
    }
}
