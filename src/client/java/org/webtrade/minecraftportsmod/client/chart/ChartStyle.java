package org.webtrade.minecraftportsmod.client.chart;

import net.minecraft.world.level.material.MapColor;

/**
 * Palette and pixel painting for the "old nautical chart" look: aged parchment, sepia ink coastlines,
 * blue water that pales into shallows near the shore, and hatched parchment where nobody has been yet.
 */
final class ChartStyle {

    // frame & ui
    static final int INK = 0xFF3B2A1C;
    static final int INK_SOFT = 0xFF6B5037;
    static final int WOOD_DARK = 0xFF2A1D12;
    static final int WOOD = 0xFF4A3322;
    static final int BRASS = 0xFFC9A55A;
    static final int BRASS_DARK = 0xFF8C6F33;
    static final int PARCHMENT = 0xFFEADBB5;
    static final int PARCHMENT_DARK = 0xFFD8C495;
    static final int PARCHMENT_SHADE = 0xFFC7B283;
    static final int ROUTE = 0xFFA8322A;
    static final int ROUTE_FAINT = 0x9A6B4A30;
    static final int ROUTE_HIGHLIGHT = 0xFFE3A12F;
    static final int ROUTE_BLOCKED = 0xFF8A8278;
    static final int TEXT = 0xFF3B2A1C;
    static final int TEXT_LIGHT = 0xFFF3E6C4;
    static final int TEXT_MUTED = 0xFF7D6446;
    static final int GOOD = 0xFF3F7A3A;
    static final int BAD = 0xFFA33A2A;

    // map pixels
    private static final int UNKNOWN = 0xE4D2A6;
    private static final int UNKNOWN_HATCH = 0xD6C290;
    private static final int WATER_DEEP = 0x3C6C93;
    private static final int WATER_MID = 0x4E84AB;
    private static final int WATER_SHALLOW = 0x72A7C6;
    private static final int WATER_SHORE = 0x9CC6D6;
    private static final int WATER_INLAND = 0x5E97A6;
    private static final int COAST_INK = 0x5A4430;
    private static final int LAND_TINT = 0xE2CFA0;

    static final int FLAG_NAV = 1;
    static final int FLAG_WATER = 2;
    static final int FLAG_KNOWN = 0x80;

    private ChartStyle() {
    }

    /**
     * @param colors packed vanilla map colours, row-major (z rows)
     * @param flags  per-pixel flags, same layout
     * @return ARGB pixels
     */
    static int[] paint(int size, int tileX, int tileZ, byte[] colors, byte[] flags) {
        int[] out = new int[size * size];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                int f = flags[i] & 0xFF;
                int wx = tileX * size + x;
                int wz = tileZ * size + z;
                if ((f & FLAG_KNOWN) == 0) {
                    out[i] = 0xFF000000 | unexplored(wx, wz);
                } else if ((f & (FLAG_NAV | FLAG_WATER)) != 0) {
                    out[i] = 0xFF000000 | water(size, colors, flags, x, z, f, wx, wz);
                } else {
                    out[i] = 0xFF000000 | land(size, colors, flags, x, z);
                }
            }
        }
        return out;
    }

    private static int unexplored(int wx, int wz) {
        int n = hash(wx, wz);
        int base = ((wx + wz) & 7) == 0 ? UNKNOWN_HATCH : UNKNOWN;
        return shade(base, ((n & 7) - 3) * 2);
    }

    private static int water(int size, byte[] colors, byte[] flags, int x, int z, int f, int wx, int wz) {
        int packed = colors[z * size + x] & 0xFF;
        int brightness = packed & 3; // vanilla water: HIGH = shallow, LOW = deep
        int base = (f & FLAG_NAV) == 0 ? WATER_INLAND
                : brightness == MapColor.Brightness.HIGH.id ? WATER_SHALLOW
                : brightness == MapColor.Brightness.LOW.id ? WATER_DEEP : WATER_MID;
        int shore = distanceToLand(size, flags, x, z, 3);
        if (shore == 1) base = WATER_SHORE;
        else if (shore == 2) base = mix(base, WATER_SHORE, 0.55);
        else if (shore == 3) base = mix(base, WATER_SHORE, 0.25);
        // faint engraved wave strokes in open water
        if (shore == 0 && ((wx * 3 + wz * 7) % 23 == 0) && (hash(wx >> 2, wz >> 2) & 3) == 0) {
            base = mix(base, 0xFFFFFF, 0.18);
        }
        return base;
    }

    private static int land(int size, byte[] colors, byte[] flags, int x, int z) {
        if (touchesWater(size, flags, x, z)) return COAST_INK;
        int packed = colors[z * size + x] & 0xFF;
        MapColor mc = MapColor.byId(packed >> 2);
        int argb = mc == MapColor.NONE ? LAND_TINT : mc.calculateARGBColor(MapColor.Brightness.byId(packed & 3));
        int rgb = argb & 0xFFFFFF;
        rgb = desaturate(rgb, 0.45);
        return mix(rgb, LAND_TINT, 0.38);
    }

    private static boolean isWaterPx(int size, byte[] flags, int x, int z) {
        if (x < 0 || z < 0 || x >= size || z >= size) return false;
        int f = flags[z * size + x] & 0xFF;
        return (f & FLAG_KNOWN) != 0 && (f & (FLAG_NAV | FLAG_WATER)) != 0;
    }

    private static boolean isLandPx(int size, byte[] flags, int x, int z) {
        if (x < 0 || z < 0 || x >= size || z >= size) return false;
        int f = flags[z * size + x] & 0xFF;
        return (f & FLAG_KNOWN) != 0 && (f & (FLAG_NAV | FLAG_WATER)) == 0;
    }

    private static boolean touchesWater(int size, byte[] flags, int x, int z) {
        return isWaterPx(size, flags, x + 1, z) || isWaterPx(size, flags, x - 1, z)
                || isWaterPx(size, flags, x, z + 1) || isWaterPx(size, flags, x, z - 1);
    }

    /** 1..max = Chebyshev distance to land inside the tile, 0 = further than max. */
    private static int distanceToLand(int size, byte[] flags, int x, int z, int max) {
        for (int d = 1; d <= max; d++) {
            for (int i = -d; i <= d; i++) {
                if (isLandPx(size, flags, x + i, z - d) || isLandPx(size, flags, x + i, z + d)
                        || isLandPx(size, flags, x - d, z + i) || isLandPx(size, flags, x + d, z + i)) {
                    return d;
                }
            }
        }
        return 0;
    }

    static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int desaturate(int rgb, double amount) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int grey = (r * 30 + g * 59 + b * 11) / 100;
        return mix(rgb, (grey << 16) | (grey << 8) | grey, amount);
    }

    private static int shade(int rgb, int delta) {
        int r = clamp(((rgb >> 16) & 0xFF) + delta), g = clamp(((rgb >> 8) & 0xFF) + delta), b = clamp((rgb & 0xFF) + delta);
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    static int hash(int x, int z) {
        int h = x * 0x27d4eb2d ^ z * 0x165667b1;
        h ^= h >>> 15;
        h *= 0x2c1b3c6d;
        h ^= h >>> 12;
        return h;
    }

    static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0xFFFFFF);
    }
}
