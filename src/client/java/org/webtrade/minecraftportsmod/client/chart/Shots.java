package org.webtrade.minecraftportsmod.client.chart;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.stb.STBIWriteCallback;
import org.lwjgl.stb.STBImage;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.network.WorldMapPayloads;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The screenshots of the world map on this side: made small and sent to the server (a JPEG, a part at a time), and
 * the ones asked for from it kept as textures while the game runs.
 */
public final class Shots {

    /** A screenshot shown: its texture and size. */
    public record Picture(Identifier id, int width, int height) {
    }

    /** The width a screenshot is sent at (its height as the screen has it). */
    static final int WIDTH = 960;
    private static final int QUALITY = 85;

    private static final Map<Integer, Picture> PICTURES = new HashMap<>();
    private static final Set<Integer> ASKED = new HashSet<>();
    private static int uploads;

    private Shots() {
    }

    /** A screenshot's picture, if it is here yet (else it is asked for). */
    public static Picture picture(int id) {
        Picture p = PICTURES.get(id);
        if (p == null && ASKED.add(id) && ClientPlayNetworking.canSend(WorldMapPayloads.ShotRequest.TYPE)) {
            ClientPlayNetworking.send(new WorldMapPayloads.ShotRequest(id));
        }
        return p;
    }

    /** A picture from the server. */
    public static void received(WorldMapPayloads.ShotData data) {
        if (data.jpeg().length == 0) return;
        NativeImage img = decode(data.jpeg());
        if (img == null) return;
        Identifier id = Minecraftportsmod.id("shot/" + data.id());
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, img));
        PICTURES.put(data.id(), new Picture(id, img.getWidth(), img.getHeight()));
    }

    /** A world closed: its pictures are gone. */
    public static void clear() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (Picture p : PICTURES.values()) tm.release(p.id());
        PICTURES.clear();
        ASKED.clear();
    }

    // ------------------------------------------------------------------ sending

    /** Sends a picture to the server: for a mark, or (-1) to where the player stands. The picture is closed. */
    public static void send(NativeImage image, int mark) {
        byte[] jpeg;
        try (image) {
            jpeg = jpeg(image);
        }
        if (jpeg == null || !ClientPlayNetworking.canSend(WorldMapPayloads.ShotPart.TYPE)) return;
        int size = WorldMapPayloads.ShotPart.MAX_PART;
        int parts = (jpeg.length + size - 1) / size;
        int upload = ++uploads;
        for (int i = 0; i < parts; i++) {
            byte[] part = java.util.Arrays.copyOfRange(jpeg, i * size, Math.min(jpeg.length, (i + 1) * size));
            ClientPlayNetworking.send(new WorldMapPayloads.ShotPart(upload, mark, i, parts, part));
        }
    }

    /** A picture made small ({@link #WIDTH} wide at most) and packed as a JPEG. */
    static byte[] jpeg(NativeImage src) {
        int w = Math.min(WIDTH, src.getWidth()), h = Math.max(1, Math.round(src.getHeight() * (w / (float) src.getWidth())));
        try (NativeImage small = new NativeImage(w, h, false)) {
            src.resizeSubRectTo(0, 0, src.getWidth(), src.getHeight(), small);
            // (opaque: a screenshot's alpha is of no use, and JPEG has none)
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) small.setPixelABGR(x, y, small.getPixel(x, y) | 0xFF000000);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer pixels = MemoryUtil.memByteBuffer(small.getPointer(), w * h * 4);
            try (STBIWriteCallback cb = STBIWriteCallback.create((ctx, data, size) -> {
                ByteBuffer b = STBIWriteCallback.getData(data, size);
                byte[] chunk = new byte[size];
                b.get(chunk);
                out.write(chunk, 0, size);
            })) {
                if (STBImageWrite.stbi_write_jpg_to_func(cb, 0L, w, h, 4, pixels, QUALITY) == 0) return null;
            }
            return out.toByteArray();
        }
    }

    /** A JPEG (or any picture) read back into an image. */
    static NativeImage decode(byte[] bytes) {
        ByteBuffer in = MemoryUtil.memAlloc(bytes.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            in.put(bytes).flip();
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), comp = stack.mallocInt(1);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(in, w, h, comp, 4);
            if (pixels == null) return null;
            try {
                NativeImage img = new NativeImage(w.get(0), h.get(0), false);
                MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), img.getPointer(), (long) w.get(0) * h.get(0) * 4);
                return img;
            } finally {
                STBImage.stbi_image_free(pixels);
            }
        } finally {
            MemoryUtil.memFree(in);
        }
    }
}
