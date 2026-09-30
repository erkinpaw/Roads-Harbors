package org.webtrade.minecraftportsmod.nav;

import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 32x32 chunks of navigation data, stored as one small gzip file per region
 * (same grid as vanilla region files, so files stay a manageable size).
 */
final class NavRegion {

    static final int SIZE = 32;
    private static final int MAGIC = 0x50524E56; // "PRNV"
    private static final short FORMAT = 1;

    final int regionX;
    final int regionZ;
    private final AtomicReferenceArray<ChunkNav> chunks = new AtomicReferenceArray<>(SIZE * SIZE);
    private volatile boolean dirty;

    NavRegion(int regionX, int regionZ) {
        this.regionX = regionX;
        this.regionZ = regionZ;
    }

    static long key(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    private static int slot(int chunkX, int chunkZ) {
        return ((chunkZ & 31) << 5) | (chunkX & 31);
    }

    ChunkNav get(int chunkX, int chunkZ) {
        return chunks.get(slot(chunkX, chunkZ));
    }

    ChunkNav put(int chunkX, int chunkZ, ChunkNav nav) {
        dirty = true;
        return chunks.getAndSet(slot(chunkX, chunkZ), nav);
    }

    ChunkNav remove(int chunkX, int chunkZ) {
        dirty = true;
        return chunks.getAndSet(slot(chunkX, chunkZ), null);
    }

    boolean isDirty() {
        return dirty;
    }

    int count() {
        int n = 0;
        for (int i = 0; i < SIZE * SIZE; i++) {
            if (chunks.get(i) != null) n++;
        }
        return n;
    }

    static Path file(Path dir, int regionX, int regionZ) {
        return dir.resolve("r." + regionX + "." + regionZ + ".nav");
    }

    static NavRegion load(Path dir, int regionX, int regionZ) {
        NavRegion region = new NavRegion(regionX, regionZ);
        Path file = file(dir, regionX, regionZ);
        if (!Files.isRegularFile(file)) {
            return region;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("bad magic");
            }
            short format = in.readShort();
            if (format != FORMAT) {
                throw new IOException("unknown format " + format);
            }
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                int slot = in.readUnsignedShort();
                byte version = in.readByte();
                byte[] flags = new byte[ChunkNav.COLUMNS];
                byte[] colors = new byte[ChunkNav.COLUMNS];
                in.readFully(flags);
                in.readFully(colors);
                if (slot < SIZE * SIZE) {
                    region.chunks.set(slot, new ChunkNav(version, flags, colors));
                }
            }
        } catch (IOException e) {
            Minecraftportsmod.LOGGER.warn("Could not read nav region {}: {} (it will be rebuilt)", file, e.toString());
        }
        return region;
    }

    /** Writes the region atomically (temp file + move). Safe to call from a background thread. */
    void save(Path dir) throws IOException {
        dirty = false;
        ChunkNav[] snapshot = new ChunkNav[SIZE * SIZE];
        int count = 0;
        for (int i = 0; i < snapshot.length; i++) {
            snapshot[i] = chunks.get(i);
            if (snapshot[i] != null) count++;
        }
        Files.createDirectories(dir);
        Path target = file(dir, regionX, regionZ);
        if (count == 0) {
            Files.deleteIfExists(target);
            return;
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(MAGIC);
            out.writeShort(FORMAT);
            out.writeShort(count);
            for (int i = 0; i < snapshot.length; i++) {
                ChunkNav nav = snapshot[i];
                if (nav == null) continue;
                out.writeShort(i);
                out.writeByte(nav.version());
                out.write(nav.rawFlags());
                out.write(nav.rawColors());
            }
        } catch (IOException e) {
            dirty = true;
            throw e;
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
