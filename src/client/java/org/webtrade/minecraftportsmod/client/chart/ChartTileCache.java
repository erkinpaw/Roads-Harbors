package org.webtrade.minecraftportsmod.client.chart;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.network.ChartPayloads;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Client-side store of chart imagery: one 128x128 texture per tile, fetched on demand from the server.
 * Revisions let the server skip tiles that did not change since we last saw them.
 */
public final class ChartTileCache {

    public static final int TILE = 128;
    private static final int UNKNOWN_REVISION = Integer.MIN_VALUE;
    private static final long PENDING_TIMEOUT_MS = 6000;

    private record Tile(int revision, Identifier id, DynamicTexture texture) {
    }

    private static final Map<Long, Tile> TILES = new HashMap<>();
    private static final Map<Long, Long> PENDING = new HashMap<>();
    /** Tiles whose revision was checked with the server since the chart was opened. */
    private static final Set<Long> CHECKED = new HashSet<>();

    private ChartTileCache() {
    }

    static long key(int tx, int tz) {
        return ((long) tx << 32) | (tz & 0xFFFFFFFFL);
    }

    /** Called when the chart opens: re-validate every tile we show against the server once. */
    static void beginSession() {
        CHECKED.clear();
    }

    static Identifier texture(int tx, int tz) {
        Tile t = TILES.get(key(tx, tz));
        return t == null ? null : t.id;
    }

    /** Asks the server for visible tiles we don't have (or haven't re-checked yet), nearest first. */
    static void requestVisible(int minTx, int minTz, int maxTx, int maxTz, int centerTx, int centerTz) {
        long now = System.currentTimeMillis();
        PENDING.values().removeIf(t -> now - t > PENDING_TIMEOUT_MS);
        if (PENDING.size() >= ChartPayloads.RequestTiles.MAX_TILES * 2) return;

        int[] buf = new int[ChartPayloads.RequestTiles.MAX_TILES * 3];
        int n = 0;
        int maxRing = Math.max(Math.max(centerTx - minTx, maxTx - centerTx), Math.max(centerTz - minTz, maxTz - centerTz));
        outer:
        for (int ring = 0; ring <= maxRing; ring++) {
            for (int tx = centerTx - ring; tx <= centerTx + ring; tx++) {
                for (int tz = centerTz - ring; tz <= centerTz + ring; tz++) {
                    if (Math.max(Math.abs(tx - centerTx), Math.abs(tz - centerTz)) != ring) continue;
                    if (tx < minTx || tx > maxTx || tz < minTz || tz > maxTz) continue;
                    long k = key(tx, tz);
                    if (CHECKED.contains(k) || PENDING.containsKey(k)) continue;
                    Tile t = TILES.get(k);
                    buf[n * 3] = tx;
                    buf[n * 3 + 1] = tz;
                    buf[n * 3 + 2] = t == null ? UNKNOWN_REVISION : t.revision;
                    CHECKED.add(k);
                    if (t == null) PENDING.put(k, now);
                    if (++n >= ChartPayloads.RequestTiles.MAX_TILES) break outer;
                }
            }
        }
        if (n > 0 && ClientPlayNetworking.canSend(ChartPayloads.RequestTiles.TYPE)) {
            int[] req = new int[n * 3];
            System.arraycopy(buf, 0, req, 0, n * 3);
            ClientPlayNetworking.send(new ChartPayloads.RequestTiles(req));
        }
    }

    /** A tile painted off the render thread, waiting to be uploaded. */
    private record Painted(int tileX, int tileZ, int revision, int[] pixels) {
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<Painted> READY = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.ExecutorService PAINTER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Ports&Routes-ChartPainter");
        t.setDaemon(true);
        return t;
    });
    /** Tiles uploaded to the GPU per frame at most: the rest wait for the next frames. */
    private static final int UPLOADS_PER_FRAME = 4;

    /**
     * A tile arrived: it is unpacked and painted on a background thread (painting a whole tile is too slow for
     * the render thread when the zoomed-out chart asks for hundreds of them) and uploaded later in small portions.
     */
    public static void accept(ChartPayloads.ChartTile payload) {
        PAINTER.execute(() -> {
            byte[] raw = inflate(payload.data(), TILE * TILE * 2);
            if (raw == null) return;
            byte[] colors = new byte[TILE * TILE];
            byte[] flags = new byte[TILE * TILE];
            System.arraycopy(raw, 0, colors, 0, colors.length);
            System.arraycopy(raw, colors.length, flags, 0, flags.length);
            READY.add(new Painted(payload.tileX(), payload.tileZ(), payload.revision(),
                    ChartStyle.paint(TILE, payload.tileX(), payload.tileZ(), colors, flags)));
        });
    }

    /** Uploads a few painted tiles; called every frame while the chart is open. */
    static void uploadReady() {
        for (int i = 0; i < UPLOADS_PER_FRAME; i++) {
            Painted p = READY.poll();
            if (p == null) return;
            upload(p);
        }
    }

    private static void upload(Painted p) {
        long k = key(p.tileX(), p.tileZ());
        PENDING.remove(k);
        int[] pixels = p.pixels();
        ChartPayloads.ChartTile payload = new ChartPayloads.ChartTile(p.tileX(), p.tileZ(), p.revision(), new byte[0]);

        Tile old = TILES.get(k);
        DynamicTexture texture;
        Identifier id;
        if (old != null) {
            texture = old.texture;
            id = old.id;
        } else {
            id = Minecraftportsmod.id("chart_tile/" + (payload.tileX() & 0xFFFFFFFFL) + "_" + (payload.tileZ() & 0xFFFFFFFFL));
            texture = new DynamicTexture(id::toString, TILE, TILE, false);
            Minecraft.getInstance().getTextureManager().register(id, texture);
        }
        NativeImage image = texture.getPixels();
        for (int z = 0; z < TILE; z++) {
            for (int x = 0; x < TILE; x++) {
                image.setPixel(x, z, pixels[z * TILE + x]);
            }
        }
        texture.upload();
        TILES.put(k, new Tile(payload.revision(), id, texture));
    }

    private static byte[] inflate(byte[] data, int expected) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            byte[] out = new byte[expected];
            int len = 0;
            while (len < expected && !inflater.finished()) {
                int r = inflater.inflate(out, len, expected - len);
                if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                len += r;
            }
            return len == expected ? out : null;
        } catch (DataFormatException e) {
            Minecraftportsmod.LOGGER.warn("Bad chart tile data", e);
            return null;
        } finally {
            inflater.end();
        }
    }

    /** Frees every texture (on disconnect). */
    public static void clear() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (Tile t : TILES.values()) {
            tm.release(t.id);
        }
        TILES.clear();
        PENDING.clear();
        READY.clear();
        CHECKED.clear();
    }
}
