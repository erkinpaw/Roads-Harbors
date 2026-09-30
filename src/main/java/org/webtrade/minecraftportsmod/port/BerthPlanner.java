package org.webtrade.minecraftportsmod.port;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.webtrade.minecraftportsmod.nav.DimensionNavCache;
import org.webtrade.minecraftportsmod.nav.NavCacheManager;
import org.webtrade.minecraftportsmod.nav.WaterPathfinder;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides where a port's berths go. Rules:
 * <ul>
 *     <li>a berth has at least {@link #SHORE_GAP} blocks of open water between it and any land, pier or wall;</li>
 *     <li>berths are at least {@link #MIN_SPACING} blocks apart, and keep clear of the anchorage;</li>
 *     <li>from the anchorage every berth can be reached without passing through the spot of another berth,
 *     so a vessel coming or going never sails through a moored one.</li>
 * </ul>
 * New berths go to the free water closest to the office; a berth that breaks the rules
 * (someone built a pier next to it) moves to the closest valid spot to where it was.
 */
public final class BerthPlanner {

    /** Water blocks required between a berth and the shore. */
    public static final int SHORE_GAP = 2;
    /** Minimum distance between two berths. */
    public static final double MIN_SPACING = 5;
    /** Berths stay this far from the anchorage, which all traffic passes through. */
    public static final double ANCHORAGE_GAP = 5;
    /** Berths are placed within this radius of the office. */
    public static final int SEARCH_RADIUS = 40;
    /** Max berths per port. */
    public static final int MAX_BERTHS = 12;
    /** Space around another berth that approach paths must not enter (Chebyshev radius). */
    private static final int KEEP_OUT = 1;
    private static final int MAX_CANDIDATES_TRIED = 48;

    private BerthPlanner() {
    }

    /**
     * Finds a spot for a berth.
     *
     * @param others berths that stay where they are
     * @param near   prefer spots close to this point (the office for new berths, the old spot when relocating)
     */
    public static BlockPos find(ServerLevel level, Port port, List<BlockPos> others, BlockPos near) {
        BlockPos anchorage = port.anchorage();
        if (anchorage == null) return null;
        NavCacheManager.scanLoadedArea(level, port.office(), (SEARCH_RADIUS >> 4) + 1);
        DimensionNavCache cache = NavCacheManager.get(level);
        WaterPathfinder terrain = new WaterPathfinder(cache);
        BlockPos office = port.office();

        List<int[]> candidates = new ArrayList<>();
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                if (dx * dx + dz * dz > SEARCH_RADIUS * SEARCH_RADIUS) continue;
                int x = office.getX() + dx, z = office.getZ() + dz;
                if (terrain.clearance(x, z) <= SHORE_GAP) continue;
                if (dist(x, z, anchorage) < ANCHORAGE_GAP) continue;
                if (tooClose(x, z, others)) continue;
                candidates.add(new int[]{x, z});
            }
        }
        candidates.sort((a, b) -> Double.compare(dist(a[0], a[1], near), dist(b[0], b[1], near)));

        int tried = 0;
        for (int[] c : candidates) {
            if (++tried > MAX_CANDIDATES_TRIED) break;
            BlockPos pos = new BlockPos(c[0], cache.waterY(), c[1]);
            if (approachesClear(cache, anchorage, pos, others)) return pos;
        }
        return null;
    }

    /** Hand-placed berths may sit closer to piers and to each other than automatic ones. */
    public static final int MANUAL_SHORE_GAP = 1;
    public static final double MANUAL_SPACING = 4;

    /**
     * Checks a spot the player picked for a berth: water with at least one free block to the shore,
     * a little room from other berths, near the office, and reachable from the anchorage.
     * Returns null if fine, otherwise a translation key saying why not.
     */
    public static String checkManual(ServerLevel level, Port port, BlockPos pos, List<BlockPos> others) {
        BlockPos anchorage = port.anchorage();
        if (anchorage == null) return "minecraftportsmod.chart.no_anchorage";
        DimensionNavCache cache = NavCacheManager.get(level);
        int c = new WaterPathfinder(cache).clearance(pos.getX(), pos.getZ());
        if (c == 0) return "minecraftportsmod.berth.manual_not_water";
        if (c <= MANUAL_SHORE_GAP) return "minecraftportsmod.berth.manual_too_close_shore";
        if (dist(pos.getX(), pos.getZ(), port.office()) > SEARCH_RADIUS + 16) return "minecraftportsmod.berth.manual_too_far";
        for (BlockPos o : others) {
            if (dist(pos.getX(), pos.getZ(), o) < MANUAL_SPACING) return "minecraftportsmod.berth.manual_too_close_berth";
        }
        if (approachPath(cache, anchorage, pos, others) == null) return "minecraftportsmod.berth.manual_unreachable";
        return null;
    }

    /** Whether a berth at {@code pos} is still acceptable next to the other berths. */
    public static boolean isValid(ServerLevel level, Port port, BlockPos pos, List<BlockPos> others) {
        BlockPos anchorage = port.anchorage();
        if (anchorage == null) return false;
        DimensionNavCache cache = NavCacheManager.get(level);
        if (new WaterPathfinder(cache).clearance(pos.getX(), pos.getZ()) <= SHORE_GAP) return false;
        if (dist(pos.getX(), pos.getZ(), anchorage) < ANCHORAGE_GAP) return false;
        if (tooClose(pos.getX(), pos.getZ(), others)) return false;
        return approachesClear(cache, anchorage, pos, others);
    }

    /**
     * The berth must be reachable from the anchorage around the other berths, and every other berth
     * must still be reachable with the new one in place.
     */
    private static boolean approachesClear(DimensionNavCache cache, BlockPos anchorage, BlockPos pos, List<BlockPos> others) {
        if (!reachable(cache, anchorage, pos, others)) return false;
        for (BlockPos other : others) {
            List<BlockPos> rest = new ArrayList<>(others);
            rest.remove(other);
            rest.add(pos);
            if (!reachable(cache, anchorage, other, rest)) return false;
        }
        return true;
    }

    private static boolean reachable(DimensionNavCache cache, BlockPos from, BlockPos to, List<BlockPos> obstacles) {
        return approachPath(cache, from, to, obstacles) != null;
    }

    /** Path from {@code from} to a berth that keeps out of the other berths' spots, or null. */
    public static int[] approachPath(DimensionNavCache cache, BlockPos from, BlockPos to, List<BlockPos> otherBerths) {
        List<int[]> pts = new ArrayList<>();
        for (BlockPos b : otherBerths) {
            if (b.getX() == to.getX() && b.getZ() == to.getZ()) continue;
            // never block the start (a vessel leaving its own berth) or the target
            if (Math.abs(b.getX() - from.getX()) <= KEEP_OUT && Math.abs(b.getZ() - from.getZ()) <= KEEP_OUT) continue;
            pts.add(new int[]{b.getX(), b.getZ()});
        }
        WaterPathfinder pf = new WaterPathfinder(cache).withObstacles(pts, KEEP_OUT);
        WaterPathfinder.Result r = pf.find(from.getX(), from.getZ(), to.getX(), to.getZ(), WaterPathfinder.Options.local(), () -> false);
        return r.found() ? r.waypoints() : null;
    }

    private static boolean tooClose(int x, int z, List<BlockPos> others) {
        for (BlockPos o : others) {
            if (dist(x, z, o) < MIN_SPACING) return true;
        }
        return false;
    }

    private static double dist(int x, int z, BlockPos p) {
        double dx = x - p.getX(), dz = z - p.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
