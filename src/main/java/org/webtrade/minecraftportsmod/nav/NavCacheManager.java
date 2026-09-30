package org.webtrade.minecraftportsmod.nav;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps the per-dimension navigation caches up to date:
 * <ul>
 *     <li>every chunk that loads and is not cached yet gets scanned (spread over ticks, time-budgeted);</li>
 *     <li>block changes (see {@code LevelChunkMixin}) mark the chunk dirty, it is rescanned once edits settle;</li>
 *     <li>when the navigable mask of a chunk changes, listeners (the route manager) are told;</li>
 *     <li>dirty regions are written to disk on every world save and on shutdown.</li>
 * </ul>
 */
public final class NavCacheManager {

    /** Wait this long after the last block edit in a chunk before rescanning it. */
    private static final int DIRTY_SETTLE_TICKS = 60;
    /** Scanning budget per server tick. */
    private static final long SCAN_BUDGET_NANOS = 2_000_000L;
    /** Chart tile size in chunks (8 chunks = 128 blocks). */
    public static final int TILE_CHUNKS = 8;

    private static MinecraftServer server;
    private static final Map<ResourceKey<Level>, DimensionNavCache> CACHES = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, LinkedHashSet<Long>> SCAN_QUEUE = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<Long, Long>> DIRTY = new HashMap<>();
    private static final List<NavChangeListener> LISTENERS = new ArrayList<>();

    /** Chart tile revisions: bumped whenever a chunk inside the tile changes. */
    private static final ConcurrentHashMap<Long, Integer> TILE_REVISIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger REVISION_COUNTER = new AtomicInteger();
    private static int sessionRevision;

    private static ExecutorService ioExecutor;

    private NavCacheManager() {
    }

    public interface NavChangeListener {
        /**
         * Called on the server thread after a chunk was (re)scanned and its navigable mask differs
         * from what was cached before. {@code firstScan} is true for chunks never seen before.
         */
        void onNavChanged(ServerLevel level, int chunkX, int chunkZ, ChunkNav.WaterDelta delta, boolean firstScan);
    }

    public static void addListener(NavChangeListener listener) {
        LISTENERS.add(listener);
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(NavCacheManager::onServerStarted);
        ServerLifecycleEvents.AFTER_SAVE.register((srv, flush, force) -> saveAllAsync());
        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> saveAllBlocking());
        ServerLifecycleEvents.SERVER_STOPPED.register(srv -> onServerStopped());
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            long t0 = System.nanoTime();
            onChunkLoad(level, chunk, generated);
            org.webtrade.minecraftportsmod.Perf.report("nav chunk load", t0);
        });
        ServerTickEvents.END_SERVER_TICK.register(org.webtrade.minecraftportsmod.Perf.timed("NavCacheManager", NavCacheManager::tick));
    }

    private static void onServerStarted(MinecraftServer srv) {
        server = srv;
        sessionRevision = ThreadLocalRandom.current().nextInt();
        REVISION_COUNTER.set(sessionRevision + 1);
        ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Ports&Routes-NavIO");
            t.setDaemon(true);
            return t;
        });
    }

    private static void onServerStopped() {
        if (ioExecutor != null) {
            ioExecutor.shutdown();
            try {
                ioExecutor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ioExecutor = null;
        }
        CACHES.clear();
        SCAN_QUEUE.clear();
        DIRTY.clear();
        TILE_REVISIONS.clear();
        server = null;
    }

    public static DimensionNavCache get(ServerLevel level) {
        return CACHES.computeIfAbsent(level.dimension(), key -> new DimensionNavCache(key, dirFor(level), level.getSeaLevel()));
    }

    private static Path dirFor(ServerLevel level) {
        var id = level.dimension().identifier();
        return level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve(Minecraftportsmod.MOD_ID).resolve("nav").resolve(id.getNamespace()).resolve(id.getPath());
    }

    // ------------------------------------------------------------------ events

    private static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean generated) {
        ChunkPos pos = chunk.getPos();
        ChunkNav existing = get(level).getChunk(pos.x(), pos.z());
        if (existing == null || existing.version() != ChunkNav.SCAN_VERSION) {
            SCAN_QUEUE.computeIfAbsent(level.dimension(), k -> new LinkedHashSet<>()).add(pos.pack());
        }
    }

    /** Called from the LevelChunk mixin for every successful block change on the server. */
    public static void onBlockChanged(Level level, BlockPos pos) {
        if (server == null || level.isClientSide()) return;
        DIRTY.computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .put(ChunkPos.pack(pos), (long) server.getTickCount());
    }

    private static void tick(MinecraftServer srv) {
        int now = srv.getTickCount();

        // promote settled dirty chunks to the scan queue
        for (Map.Entry<ResourceKey<Level>, Map<Long, Long>> e : DIRTY.entrySet()) {
            Iterator<Map.Entry<Long, Long>> it = e.getValue().entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Long> d = it.next();
                if (now - d.getValue() >= DIRTY_SETTLE_TICKS) {
                    SCAN_QUEUE.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(d.getKey());
                    it.remove();
                }
            }
        }

        long deadline = System.nanoTime() + SCAN_BUDGET_NANOS;
        for (Map.Entry<ResourceKey<Level>, LinkedHashSet<Long>> e : SCAN_QUEUE.entrySet()) {
            LinkedHashSet<Long> queue = e.getValue();
            if (queue.isEmpty()) continue;
            ServerLevel level = srv.getLevel(e.getKey());
            if (level == null) {
                queue.clear();
                continue;
            }
            Iterator<Long> it = queue.iterator();
            while (it.hasNext() && System.nanoTime() < deadline) {
                long packed = it.next();
                it.remove();
                LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed));
                if (chunk != null) {
                    scanNow(level, chunk);
                }
            }
            if (System.nanoTime() >= deadline) break;
        }
    }

    /**
     * Stores predicted snapshots of chunks nobody has loaded yet (never over a real scan) and tells the listeners
     * about the new water, so routes can be found through it. Server thread only.
     */
    public static void putPredicted(ServerLevel level, java.util.List<long[]> chunks, java.util.List<ChunkNav> navs) {
        DimensionNavCache cache = get(level);
        for (int i = 0; i < chunks.size(); i++) {
            int cx = (int) chunks.get(i)[0], cz = (int) chunks.get(i)[1];
            if (cache.getChunk(cx, cz) != null) continue;
            ChunkNav nav = navs.get(i);
            cache.putChunk(cx, cz, nav);
            if (nav.navigableCount() > 0) {
                for (NavChangeListener l : LISTENERS) l.onNavChanged(level, cx, cz, ChunkNav.WaterDelta.GAINED, true);
            }
        }
    }

    /** Scans a loaded chunk immediately and notifies listeners. Server thread only. */
    public static void scanNow(ServerLevel level, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        DimensionNavCache cache = get(level);
        ChunkNav fresh = NavScanner.scan(level, chunk);
        ChunkNav previous = cache.putChunk(pos.x(), pos.z(), fresh);

        if (previous == null || !sameData(previous, fresh)) {
            bumpTile(level.dimension(), pos.x(), pos.z());
        }

        ChunkNav.WaterDelta delta;
        boolean firstScan = previous == null;
        if (firstScan) {
            delta = fresh.navigableCount() > 0 ? ChunkNav.WaterDelta.GAINED : ChunkNav.WaterDelta.NONE;
        } else {
            delta = fresh.compareWater(previous);
        }
        if (delta != ChunkNav.WaterDelta.NONE) {
            for (NavChangeListener l : LISTENERS) {
                l.onNavChanged(level, pos.x(), pos.z(), delta, firstScan);
            }
        }
    }

    /**
     * Makes sure every currently loaded chunk around a block is scanned right now
     * (used when a port or dock is placed, so their water is known immediately).
     */
    public static void scanLoadedArea(ServerLevel level, BlockPos center, int radiusChunks) {
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;
        LinkedHashSet<Long> queue = SCAN_QUEUE.get(level.dimension());
        Map<Long, Long> dirty = DIRTY.get(level.dimension());
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
                if (chunk == null) continue;
                long key = ChunkPos.pack(cx + dx, cz + dz);
                // queued or recently edited chunks have stale data: scan them now
                boolean pending = (queue != null && queue.remove(key)) | (dirty != null && dirty.remove(key) != null);
                if (pending || get(level).getChunk(cx + dx, cz + dz) == null) {
                    scanNow(level, chunk);
                }
            }
        }
    }

    /** Queues every loaded chunk in a radius for rescanning (admin command). */
    public static int queueRescan(ServerLevel level, BlockPos center, int radiusChunks) {
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;
        int n = 0;
        LinkedHashSet<Long> queue = SCAN_QUEUE.computeIfAbsent(level.dimension(), k -> new LinkedHashSet<>());
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) != null) {
                    queue.add(ChunkPos.pack(cx + dx, cz + dz));
                    n++;
                }
            }
        }
        return n;
    }

    public static int pendingScans() {
        int n = 0;
        for (LinkedHashSet<Long> q : SCAN_QUEUE.values()) n += q.size();
        for (Map<Long, Long> d : DIRTY.values()) n += d.size();
        return n;
    }

    private static boolean sameData(ChunkNav a, ChunkNav b) {
        for (int i = 0; i < ChunkNav.COLUMNS; i++) {
            if (a.flags(i) != b.flags(i) || a.packedColor(i) != b.packedColor(i)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ chart tiles

    public static long tileKey(ResourceKey<Level> dim, int tileX, int tileZ) {
        return ((long) dim.hashCode() * 31L) ^ (((long) tileX << 32) | (tileZ & 0xFFFFFFFFL));
    }

    private static void bumpTile(ResourceKey<Level> dim, int chunkX, int chunkZ) {
        TILE_REVISIONS.put(tileKey(dim, Math.floorDiv(chunkX, TILE_CHUNKS), Math.floorDiv(chunkZ, TILE_CHUNKS)),
                REVISION_COUNTER.incrementAndGet());
    }

    public static int tileRevision(ResourceKey<Level> dim, int tileX, int tileZ) {
        return TILE_REVISIONS.getOrDefault(tileKey(dim, tileX, tileZ), sessionRevision);
    }

    // ------------------------------------------------------------------ persistence

    private static void saveAllAsync() {
        if (ioExecutor == null) return;
        List<DimensionNavCache> caches = new ArrayList<>(CACHES.values());
        ioExecutor.execute(() -> caches.forEach(DimensionNavCache::saveDirty));
    }

    private static void saveAllBlocking() {
        if (ioExecutor != null) {
            List<DimensionNavCache> caches = new ArrayList<>(CACHES.values());
            try {
                ioExecutor.submit(() -> caches.forEach(DimensionNavCache::saveDirty)).get(30, TimeUnit.SECONDS);
                return;
            } catch (Exception e) {
                Minecraftportsmod.LOGGER.warn("Async nav save failed, saving on the server thread", e);
            }
        }
        CACHES.values().forEach(DimensionNavCache::saveDirty);
    }
}
