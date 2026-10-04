package org.webtrade.minecraftportsmod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.webtrade.minecraftportsmod.combat.Currents;
import org.webtrade.minecraftportsmod.combat.WarshipEntity;

/**
 * The little chart aboard a warship, in the corner of the screen: the water and the land round her (north up), her
 * mark in the middle pointing her way, the ships about (pirates red), and the set of the currents: arrows over the
 * water, longer where they run stronger; the current under her keel, the big arrow.
 */
public final class ShipMinimap {

    private ShipMinimap() {
    }

    /** Cells across (one gui pixel each) and blocks to a cell. */
    static final int N = 80, STEP = 2;
    private static final int[] colors = new int[N * N];
    /** Which cells are water (the currents run over them). */
    private static final boolean[] wet = new boolean[N * N];
    private static int cx = Integer.MIN_VALUE, cz;
    private static long updated = -100;
    private static final long START = System.currentTimeMillis();

    /** The ship the player is aboard (at her helm, or standing on her deck), or null. */
    static WarshipEntity aboard(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        if (mc.player.getVehicle() instanceof WarshipEntity w) return w;
        for (WarshipEntity w : mc.level.getEntitiesOfClass(WarshipEntity.class, mc.player.getBoundingBox().inflate(16))) {
            if (w.onDeck(mc.player.position())) return w;
        }
        return null;
    }

    /** Rows of the chart read off the world a tick (all of it every ten ticks), the next row to read. */
    private static final int ROWS_A_TICK = 8;
    private static int nextRow;
    private static final net.minecraft.resources.Identifier TEXTURE = org.webtrade.minecraftportsmod.Minecraftportsmod.id("ship_minimap");
    private static net.minecraft.client.renderer.texture.DynamicTexture texture;

    /** Reads a row of the land and water round her (the sea one colour, lighter only in the shallows). */
    private static void readRow(Minecraft mc, int j) {
        var level = mc.level;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < N; i++) {
            int x = cx + (i - N / 2) * STEP, z = cz + (j - N / 2) * STEP;
            int c;
            wet[j * N + i] = false;
            if (!level.hasChunk(x >> 4, z >> 4)) {
                c = 0xFF1A2430;
            } else {
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                p.set(x, y, z);
                BlockState st = level.getBlockState(p);
                c = 0xFF000000 | st.getMapColor(level, p).col;
                if (!st.getFluidState().isEmpty()) {
                    int depth = 0;
                    while (depth < 4 && !level.getBlockState(p.move(0, -1, 0)).getFluidState().isEmpty()) depth++;
                    c = depth < 4 ? shade(0xFF3A5FB8, 1.25F - depth * 0.06F) : 0xFF3A5FB8;
                    wet[j * N + i] = true;
                }
            }
            colors[j * N + i] = c;
        }
    }

    /**
     * A tick of the chart: a few more rows read off the world (all of them when she has gone some way), and the
     * picture made anew with the streaks of the currents where they have run to now.
     */
    static void tick(Minecraft mc) {
        WarshipEntity ship = aboard(mc);
        if (ship == null) return;
        int x0 = Mth.floor(ship.getX()), z0 = Mth.floor(ship.getZ());
        boolean moved = Math.abs(x0 - cx) >= STEP * 4 || Math.abs(z0 - cz) >= STEP * 4;
        if (moved || cx == Integer.MIN_VALUE) {
            cx = x0;
            cz = z0;
            for (int j = 0; j < N; j++) readRow(mc, j);
        } else {
            for (int k = 0; k < ROWS_A_TICK; k++) {
                readRow(mc, nextRow);
                nextRow = (nextRow + 1) % N;
            }
        }
        if (texture == null) {
            texture = new net.minecraft.client.renderer.texture.DynamicTexture(TEXTURE::toString, N, N, false);
            mc.getTextureManager().register(TEXTURE, texture);
        }
        var image = texture.getPixels();
        double t = (System.currentTimeMillis() - START) / 50.0;
        // (the current is worked out once for every four cells by four: it hardly changes over a few blocks)
        double[][] cur = new double[(N / 4) * (N / 4)][];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int color = colors[j * N + i];
                if (wet[j * N + i]) {
                    double wx = cx + (i - N / 2) * STEP, wz = cz + (j - N / 2) * STEP;
                    int key = (j / 4) * (N / 4) + i / 4;
                    double[] c = cur[key] != null ? cur[key] : (cur[key] = Currents.at(wx, wz));
                    double len = Math.max(1e-6, Math.hypot(c[0], c[1]));
                    double k = (len - Currents.MIN) / (Currents.MAX - Currents.MIN);
                    double along = (wx * c[0] + wz * c[1]) / len, across = (-wx * c[1] + wz * c[0]) / len;
                    // (all the streaks move at one pace: a pace by the strength of each spot would tear them apart)
                    double wave = Math.sin(along * 0.22 - t * 0.03 + Math.sin(across * 0.07) * 0.6);
                    double band = Math.max(0, wave);
                    color = shade(color, (float) (1.0 - (0.14 + 0.16 * k) * band * band));
                }
                image.setPixel(i, j, color);
            }
        }
        texture.upload();
    }

    private static int shade(int argb, float k) {
        int r = Math.min(255, Math.round(((argb >> 16) & 255) * k)), g = Math.min(255, Math.round(((argb >> 8) & 255) * k)),
                b = Math.min(255, Math.round((argb & 255) * k));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    static void draw(Minecraft mc, GuiGraphicsExtractor g) {
        WarshipEntity ship = aboard(mc);
        if (ship == null || texture == null) return;
        int w = mc.getWindow().getGuiScaledWidth();
        int x0 = w - N - 8, y0 = 8;
        // the frame, the chart
        g.fill(x0 - 3, y0 - 3, x0 + N + 3, y0 + N + 3, 0xFF5A3A22);
        g.fill(x0 - 2, y0 - 2, x0 + N + 2, y0 + N + 2, 0xFFC9A55A);
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, TEXTURE, x0, y0, 0, 0, N, N, N, N);
        double t = (System.currentTimeMillis() - START) / 50.0;
        // the arrows of the currents, sliding with the flow and fading out as they go
        int grid = 20;
        for (int gj = grid / 2; gj < N; gj += grid) {
            for (int gi = grid / 2; gi < N; gi += grid) {
                if (!wet[gj * N + gi]) continue;
                double wx = cx + (gi - N / 2) * STEP, wz = cz + (gj - N / 2) * STEP;
                double[] c = Currents.at(wx, wz);
                double k = Currents.strength(wx, wz), l = Math.max(1e-6, Math.hypot(c[0], c[1]));
                double phase = ((t * 0.0067 + (gi + gj) * 0.37) % 1.0);
                int ox = (int) Math.round(c[0] / l * (phase - 0.5) * 8), oz = (int) Math.round(c[1] / l * (phase - 0.5) * 8);
                int alpha = (int) (200 * Math.sin(Math.PI * phase));
                arrow(g, x0 + gi + ox, y0 + gj + oz, c[0], c[1], 3 + 6 * k, alpha << 24 | 0xE8F6FF);
            }
        }
        // the ships about
        for (WarshipEntity o : mc.level.getEntitiesOfClass(WarshipEntity.class, ship.getBoundingBox().inflate(N * STEP / 2.0))) {
            if (o == ship || o.sinking() > 0) continue;
            int px = x0 + N / 2 + (int) Math.round((o.getX() - cx) / STEP), pz = y0 + N / 2 + (int) Math.round((o.getZ() - cz) / STEP);
            if (px < x0 + 1 || pz < y0 + 1 || px > x0 + N - 2 || pz > y0 + N - 2) continue;
            g.fill(px - 1, pz - 1, px + 2, pz + 2, o.isPirate() ? 0xFFE02020 : 0xFFFFFFFF);
        }
        // her: a mark pointing her way
        int mx = x0 + N / 2 + (int) Math.round((ship.getX() - cx) / STEP), mz = y0 + N / 2 + (int) Math.round((ship.getZ() - cz) / STEP);
        var f = ship.forward();
        arrow(g, mx - (int) Math.round(f.x * 3), mz - (int) Math.round(f.z * 3), f.x, f.z, 7, 0xFFFFE070);
        g.fill(mx - 1, mz - 1, mx + 2, mz + 2, 0xFF202020);
        // the current under her keel: the big arrow below the chart, and north
        double[] c = Currents.at(ship.getX(), ship.getZ());
        int bx = x0 + N / 2, by = y0 + N + 14;
        g.fill(bx - 12, by - 10, bx + 12, by + 10, 0xA0000000);
        arrow(g, bx - (int) Math.round(c[0] / Currents.MAX * 7), by - (int) Math.round(c[1] / Currents.MAX * 7), c[0], c[1],
                6 + 10 * Currents.strength(ship.getX(), ship.getZ()), 0xFF8ED8FF);
        g.text(mc.font, "N", x0 + N / 2 - 2, y0 - 1, 0xFF202020, false);
    }

    /** An arrow from a point along a direction (x east, z south), so long (gui pixels): a shaft and a head. */
    private static void arrow(GuiGraphicsExtractor g, int x, int y, double dx, double dz, double len, int color) {
        double l = Math.max(1e-6, Math.hypot(dx, dz));
        double ux = dx / l, uz = dz / l;
        for (int k = 0; k <= len; k++) {
            int px = x + (int) Math.round(ux * k), pz = y + (int) Math.round(uz * k);
            g.fill(px, pz, px + 1, pz + 1, color);
        }
        int hx = x + (int) Math.round(ux * len), hz = y + (int) Math.round(uz * len);
        for (int k = 1; k <= 3; k++) {
            int lx = hx + (int) Math.round((-ux - uz) * k * 0.8), lz = hz + (int) Math.round((-uz + ux) * k * 0.8);
            int rx = hx + (int) Math.round((-ux + uz) * k * 0.8), rz = hz + (int) Math.round((-uz - ux) * k * 0.8);
            g.fill(lx, lz, lx + 1, lz + 1, color);
            g.fill(rx, rz, rx + 1, rz + 1, color);
        }
    }
}
