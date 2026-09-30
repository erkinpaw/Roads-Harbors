package org.webtrade.minecraftportsmod.nav;

/**
 * Immutable snapshot of one chunk as seen from sea level: which columns a boat can sail on,
 * plus a packed map colour for every column (used to draw the nautical chart).
 * <p>
 * Instances are never mutated after construction, so they can be read from worker threads
 * (pathfinding, chart tile building) while the server thread swaps in fresh scans.
 */
public final class ChunkNav {

    /** Bumped whenever the scanner logic changes; older entries are rescanned on chunk load. */
    public static final byte SCAN_VERSION = 1;
    /**
     * Version of a snapshot predicted from the terrain generator for a chunk that was never loaded (water only,
     * no map colours). Replaced by a real scan as soon as the chunk loads; the chart shows it as unexplored.
     */
    public static final byte PREDICTED_VERSION = 0x70;

    /** Surface water exactly at sea level with nothing solid in the way: a boat can float here. */
    public static final byte FLAG_NAVIGABLE = 1;
    /** The topmost visible block of the column is water (any height). */
    public static final byte FLAG_SURFACE_WATER = 2;

    public static final int COLUMNS = 256;

    private final byte version;
    private final byte[] flags;
    private final byte[] colors;

    public ChunkNav(byte version, byte[] flags, byte[] colors) {
        if (flags.length != COLUMNS || colors.length != COLUMNS) {
            throw new IllegalArgumentException("ChunkNav arrays must have 256 entries");
        }
        this.version = version;
        this.flags = flags;
        this.colors = colors;
    }

    public static int index(int localX, int localZ) {
        return (localZ << 4) | localX;
    }

    public byte version() {
        return version;
    }

    public boolean isPredicted() {
        return version == PREDICTED_VERSION;
    }

    public boolean isNavigable(int localX, int localZ) {
        return (flags[index(localX, localZ)] & FLAG_NAVIGABLE) != 0;
    }

    public int flags(int index) {
        return flags[index];
    }

    /** Packed vanilla map colour: {@code mapColorId << 2 | brightnessId}. 0 means "nothing". */
    public int packedColor(int index) {
        return colors[index] & 0xFF;
    }

    public int navigableCount() {
        int n = 0;
        for (byte f : flags) {
            if ((f & FLAG_NAVIGABLE) != 0) n++;
        }
        return n;
    }

    /** Result of comparing the navigable mask of two scans of the same chunk. */
    public enum WaterDelta {NONE, GAINED, LOST, BOTH}

    public WaterDelta compareWater(ChunkNav previous) {
        boolean gained = false;
        boolean lost = false;
        for (int i = 0; i < COLUMNS; i++) {
            boolean now = (flags[i] & FLAG_NAVIGABLE) != 0;
            boolean before = (previous.flags[i] & FLAG_NAVIGABLE) != 0;
            if (now && !before) gained = true;
            else if (!now && before) lost = true;
        }
        if (gained && lost) return WaterDelta.BOTH;
        if (gained) return WaterDelta.GAINED;
        if (lost) return WaterDelta.LOST;
        return WaterDelta.NONE;
    }

    // ------------------------------------------------------------------ water components (for the coarse router)

    /**
     * Connected pieces of navigable water inside this chunk (4-connected).
     *
     * @param labels per column: component index, or -1 where there is no navigable water
     * @param sizes  number of columns in each component
     */
    public record Components(byte[] labels, int[] sizes) {
        public int count() {
            return sizes.length;
        }
    }

    private volatile Components components;

    /** Lazily computed; the chunk data is immutable, so this never changes once built. */
    public Components components() {
        Components c = components;
        if (c == null) {
            c = computeComponents();
            components = c;
        }
        return c;
    }

    private Components computeComponents() {
        byte[] labels = new byte[COLUMNS];
        java.util.Arrays.fill(labels, (byte) -1);
        int[] sizes = new int[COLUMNS];
        int count = 0;
        int[] stack = new int[COLUMNS];
        for (int start = 0; start < COLUMNS; start++) {
            if ((flags[start] & FLAG_NAVIGABLE) == 0 || labels[start] != -1) continue;
            int sp = 0;
            stack[sp++] = start;
            labels[start] = (byte) count;
            int size = 0;
            while (sp > 0) {
                int i = stack[--sp];
                size++;
                int x = i & 15, z = i >> 4;
                if (x > 0) sp = visit(i - 1, count, labels, stack, sp);
                if (x < 15) sp = visit(i + 1, count, labels, stack, sp);
                if (z > 0) sp = visit(i - 16, count, labels, stack, sp);
                if (z < 15) sp = visit(i + 16, count, labels, stack, sp);
            }
            sizes[count++] = size;
        }
        return new Components(labels, java.util.Arrays.copyOf(sizes, count));
    }

    private int visit(int i, int label, byte[] labels, int[] stack, int sp) {
        if ((flags[i] & FLAG_NAVIGABLE) != 0 && labels[i] == -1) {
            labels[i] = (byte) label;
            stack[sp++] = i;
        }
        return sp;
    }

    byte[] rawFlags() {
        return flags;
    }

    byte[] rawColors() {
        return colors;
    }
}
