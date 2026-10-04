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
    private static int cx = Integer.MIN_VALUE, cz;
    private static long updated = -100;

    /** The ship the player is aboard (at her helm, or standing on her deck), or null. */
    static WarshipEntity aboard(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        if (mc.player.getVehicle() instanceof WarshipEntity w) return w;
        for (WarshipEntity w : mc.level.getEntitiesOfClass(WarshipEntity.class, mc.player.getBoundingBox().inflate(16))) {
            if (w.onDeck(mc.player.position())) return w;
        }
        return null;
    }

    /** The land and water round her, read off the world now and then (every half second, or when she has moved on). */
    private static void update(Minecraft mc, WarshipEntity ship) {
        int x0 = Mth.floor(ship.getX()), z0 = Mth.floor(ship.getZ());
        long now = mc.level.getGameTime();
        if (now - updated < 10 && Math.abs(x0 - cx) < 4 && Math.abs(z0 - cz) < 4) return;
        updated = now;
        cx = x0;
        cz = z0;
        var level = mc.level;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int x = cx + (i - N / 2) * STEP, z = cz + (j - N / 2) * STEP;
                int c;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    c = 0xFF1A2430;
                } else {
                    int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                    p.set(x, y, z);
                    BlockState st = level.getBlockState(p);
                    MapColor m = st.getMapColor(level, p);
                    c = 0xFF000000 | m.col;
                    if (!st.getFluidState().isEmpty()) {
                        // the sea: darker where it is deep
                        int depth = 0;
                        while (depth < 12 && !level.getBlockState(p.move(0, -1, 0)).getFluidState().isEmpty()) depth++;
                        c = shade(0xFF3A5FB8, 1.15F - depth * 0.04F);
                    }
                }
                colors[j * N + i] = c;
            }
        }
    }

    private static int shade(int argb, float k) {
        int r = Math.min(255, Math.round(((argb >> 16) & 255) * k)), g = Math.min(255, Math.round(((argb >> 8) & 255) * k)),
                b = Math.min(255, Math.round((argb & 255) * k));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    static void draw(Minecraft mc, GuiGraphicsExtractor g) {
        WarshipEntity ship = aboard(mc);
        if (ship == null) return;
        update(mc, ship);
        int w = mc.getWindow().getGuiScaledWidth();
        int x0 = w - N - 8, y0 = 8;
        // the frame, the chart
        g.fill(x0 - 3, y0 - 3, x0 + N + 3, y0 + N + 3, 0xFF5A3A22);
        g.fill(x0 - 2, y0 - 2, x0 + N + 2, y0 + N + 2, 0xFFC9A55A);
        for (int j = 0; j < N; j++) {
            int run = 0;
            for (int i = 0; i <= N; i++) {
                // (runs of one colour drawn as one strip)
                if (i < N && i > run && colors[j * N + i] == colors[j * N + run]) continue;
                if (i > run || i == N) g.fill(x0 + run, y0 + j, x0 + i, y0 + j + 1, colors[j * N + run]);
                run = i;
            }
        }
        // the currents: an arrow every so far across the water
        int grid = 16;
        for (int gj = grid / 2; gj < N; gj += grid) {
            for (int gi = grid / 2; gi < N; gi += grid) {
                double wx = cx + (gi - N / 2) * STEP, wz = cz + (gj - N / 2) * STEP;
                double[] c = Currents.at(wx, wz);
                double len = 3 + 7 * Currents.strength(wx, wz);
                arrow(g, x0 + gi, y0 + gj, c[0], c[1], len, 0xB0D8F4FF);
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
