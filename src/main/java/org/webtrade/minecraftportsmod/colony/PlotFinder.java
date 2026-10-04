package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/** Finds room for a new building: dry, loaded, not too steep, off the other plots, facing the middle. */
final class PlotFinder {

    /** Blocks kept free between two plots (a lane to walk, a yard). */
    static final int GAP = 4;
    /** How far from the middle a village builds. */
    static final int MAX_RING = 56;

    private PlotFinder() {
    }

    /** Why the plots looked at in the last search were turned down (for the log when none is found). */
    static final java.util.Map<String, Integer> WHY = new java.util.TreeMap<>();

    private static boolean no(String why) {
        WHY.merge(why, 1, Integer::sum);
        return false;
    }

    /** The ground people stand on in a column (top of the land, trees and leaves ignored). */
    static int floorAt(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        while (y > level.getMinY() && isPlant(level.getBlockState(new BlockPos(x, y, z)))) y--;
        return y + 1;
    }

    /**
     * Where someone can stand in the column (x, z) nearest the height {@code near}: solid underfoot, room for the
     * feet and the head; looked for a few blocks up and down (not the top of the column: in a village that is a
     * roof). Null if there is no such place close by.
     */
    public static BlockPos standAt(ServerLevel level, int x, int z, int near) {
        for (int d = 0; d <= 6; d++) {
            for (int y : d == 0 ? new int[]{near} : new int[]{near + d, near - d}) {
                BlockPos p = new BlockPos(x, y, z);
                BlockState below = level.getBlockState(p.below());
                if (!below.isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP) && !below.is(BlockTags.SLABS) && !below.is(BlockTags.STAIRS)) continue;
                if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty() || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) continue;
                if (!level.getBlockState(p).getFluidState().isEmpty()) continue;
                return p;
            }
        }
        return null;
    }

    /** {@link #standAt} near the ground of the column (trees and plants aside), or that ground itself. */
    public static BlockPos ground(ServerLevel level, int x, int z) {
        int g = Trails.groundAt(level, x, z) + 1;
        BlockPos p = standAt(level, x, z, g);
        return p != null ? p : new BlockPos(x, g, z);
    }

    private static boolean isPlant(BlockState s) {
        return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(net.minecraft.world.level.block.Blocks.BAMBOO)
                || s.is(net.minecraft.world.level.block.Blocks.CACTUS) || WorkGoal.fungus(s);
    }

    /**
     * A frame for a building of this type, or null if there is no room. {@code ignore} is a building that may be
     * overlapped (the one being replaced).
     */
    static Blueprint.Frame find(ServerLevel level, Village v, BuildingType type, int ignore) {
        WHY.clear();
        if (type == BuildingType.PIER) return harbour(level, v, ignore);
        try {
            // grass first, then the beach: a coastal village builds on the strand only when the grass is taken
            for (boolean sand : new boolean[]{false, true}) {
                sandOk = sand;
                Blueprint.Frame f = find(level, v, type, ignore, false);
                // hilly land (a jungle, a coast of knolls): a steeper plot, cut and heaped level round it, rather than none
                if (f == null) f = find(level, v, type, ignore, true);
                // no room round the middle (a coast, a spit of land): the village grows on inland, away from the water
                if (f == null) f = inland(level, v, type, ignore);
                if (f != null) return f;
            }
            return null;
        } finally {
            sandOk = false;
        }
    }

    /** Is a plot on sand (the beach) acceptable in the search going on now? */
    private static boolean sandOk;

    /** How much farther than {@link #MAX_RING} a village grows inland when there is no room nearer. */
    static final int INLAND = 72;

    /**
     * A plot beyond the rings round the middle, inland: on the side of the village away from the water (within a
     * quarter turn either way of it), out to {@link #INLAND} more blocks. Null: none, or no inland side to tell.
     */
    private static Blueprint.Frame inland(ServerLevel level, Village v, BuildingType type, int ignore) {
        if (type == BuildingType.MINE_HOUSE || type.branch == BuildingType.Branch.COAST) return null;
        Double dir = inlandAngle(level, v);
        if (dir == null) return null;
        BlockPos c = v.center;
        int half = type.half;
        for (boolean steep : new boolean[]{false, true}) {
            for (int ring = MAX_RING + 2; ring <= MAX_RING + INLAND; ring += 2) {
                int steps = Math.max(12, ring);
                for (int a = 0; a < steps; a++) {
                    // nearest the inland line first, then out to either side of it
                    int k = (a + 1) / 2 * (a % 2 == 0 ? 1 : -1);
                    double ang = dir + k * (Math.PI / 2) / (steps / 2.0);
                    if (Math.abs(ang - dir) > Math.PI / 2) continue;
                    int x = c.getX() + (int) Math.round(Math.cos(ang) * ring), z = c.getZ() + (int) Math.round(Math.sin(ang) * ring);
                    BlockPos pos = new BlockPos(x, c.getY(), z);
                    if (clash(v, pos, half, ignore)) continue;
                    Integer floor = floor(level, v, pos, half, steep);
                    if (floor == null || !fits(level, v, type, pos)) continue;
                    return new Blueprint.Frame(new BlockPos(x, floor, z), Direction.getApproximateNearest(c.getX() - x, 0, c.getZ() - z));
                }
            }
        }
        return null;
    }

    private static final java.util.Map<Integer, Double> INLAND_DIR = new java.util.HashMap<>();

    static void clear() {
        INLAND_DIR.clear();
    }

    /**
     * Which way inland lies from the village (an angle), or null if the land round it is all land or all water: the
     * dry ground on a circle round the middle, summed up as arrows.
     */
    static Double inlandAngle(ServerLevel level, Village v) {
        Double known = INLAND_DIR.get(v.id);
        if (known != null) return Double.isNaN(known) ? null : known;
        double sx = 0, sz = 0;
        int dry = 0, all = 0;
        VillageTerrain.Grid grid = null;
        for (int r : new int[]{32, 56}) {
            for (int a = 0; a < 32; a++) {
                double ang = a * Math.PI * 2 / 32;
                int x = v.center.getX() + (int) Math.round(Math.cos(ang) * r), z = v.center.getZ() + (int) Math.round(Math.sin(ang) * r);
                Boolean land;
                if (Construction.loaded(level, new BlockPos(x, 0, z))) {
                    land = level.getBlockState(new BlockPos(x, floorAt(level, x, z) - 1, z)).getFluidState().isEmpty();
                } else {
                    if (grid == null) grid = VillageTerrain.grid(level, v);
                    if (grid == null) return null;
                    Integer g = grid.at(x, z);
                    land = g == null ? null : g >= 0;
                }
                if (land == null) continue;
                all++;
                if (land) {
                    dry++;
                    sx += Math.cos(ang);
                    sz += Math.sin(ang);
                }
            }
        }
        // (land all round, or water all round: no side is inland)
        Double out = all == 0 || dry == all || dry == 0 || Math.hypot(sx, sz) < 2 ? null : Math.atan2(sz, sx);
        INLAND_DIR.put(v.id, out == null ? Double.NaN : out);
        return out;
    }

    private static Blueprint.Frame find(ServerLevel level, Village v, BuildingType type, int ignore, boolean steep) {
        // the trades' houses stand a little way out, by what they work; fields by the farm
        BlockPos anchor = anchor(level, v, type);
        if (anchor != null) {
            Blueprint.Frame f = near(level, v, type, anchor, ignore, steep);
            if (f != null) return f;
        }
        BlockPos c = v.center;
        int half = type.half;
        // (the mine keeps away from the houses even with no rock to go by; the smithy is in the village like any workshop)
        boolean mine = type == BuildingType.MINE_HOUSE;
        int first = mine ? MINE_AWAY : 8 + half, last = mine ? MINE_AWAY + 40 : MAX_RING + (steep ? 16 : 0);
        for (int ring = first; ring <= last; ring += 2) {
            int steps = Math.max(12, ring * 2);
            for (int a = 0; a < steps; a++) {
                double ang = a * Math.PI * 2 / steps + ring * 0.61 + v.id;
                int x = c.getX() + (int) Math.round(Math.cos(ang) * ring);
                int z = c.getZ() + (int) Math.round(Math.sin(ang) * ring);
                BlockPos pos = new BlockPos(x, c.getY(), z);
                if (clash(v, pos, half, ignore)) {
                    no("clash");
                    continue;
                }
                // not out over the water in front of the village
                Direction facing = Direction.getApproximateNearest(c.getX() - x, 0, c.getZ() - z);
                Integer floor = floor(level, v, pos, half, steep);
                if (floor == null || !fits(level, v, type, pos)) continue;
                return new Blueprint.Frame(new BlockPos(x, floor, z), facing);
            }
        }
        return null;
    }

    /**
     * A frame for a building of this type on the plot of one it replaces (a hut rebuilt into a house): the same
     * middle and front, if the bigger plot keeps two blocks from the neighbours and the land allows; else null.
     */
    static Blueprint.Frame inPlace(ServerLevel level, Village v, BuildingType type, Building old) {
        BlockPos pos = old.origin;
        if (Math.abs(pos.getX() - v.board.getX()) <= type.half + 1 && Math.abs(pos.getZ() - v.board.getZ()) <= type.half + 1) return null;
        for (Building b : v.buildings) if (b != old && b.overlaps(pos, type.half, 2)) return null;
        // the old plot is level already (and the old building stands on it): only the ring it grows by is looked at
        int floor = pos.getY(), h = type.half + 1;
        for (int x = -h; x <= h; x++) {
            for (int z = -h; z <= h; z++) {
                if (Math.abs(x) <= old.type.half && Math.abs(z) <= old.type.half) continue;
                int px = pos.getX() + x, pz = pos.getZ() + z;
                if (!Construction.loaded(level, new BlockPos(px, 0, pz))) return null;
                int f = floorAt(level, px, pz);
                BlockState st = level.getBlockState(new BlockPos(px, f - 1, pz));
                if (!st.getFluidState().isEmpty() || st.is(BlockTags.ICE)) return null;
                if (floor - f > 5 || f - floor > 6) return null;
            }
        }
        return new Blueprint.Frame(new BlockPos(pos.getX(), floor, pos.getZ()), old.front);
    }

    /** Where a building would best stand near: the rock, the wood, the shore, the farm. Null: anywhere. */
    private static BlockPos anchor(ServerLevel level, Village v, BuildingType type) {
        switch (type.branch) {
            case MINE -> {
                // the miners' house: by the rock (noise, dust, the mine behind it); else on a knoll, or down in a hollow
                return type == BuildingType.MINE_HOUSE ? mineSpot(level, v) : null;
            }
            case WOOD -> {
                for (BlockPos t : DwellerGoals.trees(level, v)) if (t.distSqr(v.center) > WOOD_AWAY * WOOD_AWAY) return t;
                return null;
            }
            case COAST -> {
                BlockPos[] spot = DwellerGoals.fishingSpot(level, v, 1);
                return spot == null ? null : spot[0];
            }
            case FOOD -> {
                for (Building b : v.buildings) if (b.type == BuildingType.FIELD || b.type.atLeast(BuildingType.FARM)) return b.origin;
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Bare rock well away from the village (at least {@link #MINE_AWAY}): looked for on rings round the middle, the
     * nearest first; null if there is none in reach.
     */
    private static BlockPos farRock(ServerLevel level, Village v) {
        for (int d = MINE_AWAY; d <= ROCK_REACH; d += 4) {
            int steps = Math.max(24, d / 2);
            for (int a = 0; a < steps; a++) {
                double ang = a * Math.PI * 2 / steps + v.id;
                int x = v.center.getX() + (int) Math.round(Math.cos(ang) * d), z = v.center.getZ() + (int) Math.round(Math.sin(ang) * d);
                if (!Construction.loaded(level, new BlockPos(x, 0, z))) continue;
                BlockState top = level.getBlockState(new BlockPos(x, floorAt(level, x, z) - 1, z));
                if (DwellerGoals.rock(top)) return new BlockPos(x, floorAt(level, x, z), z);
            }
        }
        return null;
    }

    /**
     * Where the miners dig with no rock about: the highest ground some way out if it rises well over the village (a
     * knoll), else the lowest dry ground if it falls well below it (a hollow); null if the land is flat all round.
     */
    private static BlockPos hillOrHollow(ServerLevel level, Village v) {
        BlockPos high = null, low = null;
        int top = Integer.MIN_VALUE, bottom = Integer.MAX_VALUE, c = v.center.getY();
        for (int d = MINE_AWAY; d <= MINE_AWAY + 50; d += 6) {
            int steps = Math.max(16, d / 2);
            for (int a = 0; a < steps; a++) {
                double ang = a * Math.PI * 2 / steps + v.id;
                int x = v.center.getX() + (int) Math.round(Math.cos(ang) * d), z = v.center.getZ() + (int) Math.round(Math.sin(ang) * d);
                if (!Construction.loaded(level, new BlockPos(x, 0, z))) continue;
                int f = floorAt(level, x, z);
                if (!level.getBlockState(new BlockPos(x, f - 1, z)).getFluidState().isEmpty()) continue;
                if (f > top) {
                    top = f;
                    high = new BlockPos(x, f, z);
                }
                if (f < bottom) {
                    bottom = f;
                    low = new BlockPos(x, f, z);
                }
            }
        }
        if (high != null && top >= c + 5) return high;
        if (low != null && bottom <= c - 4) return low;
        return null;
    }

    /**
     * The miners' house's place: by the nearest bare rock in reach (a quarry looks well there); else anywhere some
     * way out: the pit is dug wherever the house stands.
     */
    private static BlockPos mineSpot(ServerLevel level, Village v) {
        return farRock(level, v);
    }

    /** How far out of the village the miners' house stands at least (rock looked for out to {@link #ROCK_REACH}), and the woodcutters. */
    static final int MINE_AWAY = 40, ROCK_REACH = 125, WOOD_AWAY = 24;

    /** A plot as close as can be to a point, not too near the middle of the village (the trades keep apart). */
    private static Blueprint.Frame near(ServerLevel level, Village v, BuildingType type, BlockPos anchor, int ignore, boolean steep) {
        BlockPos c = v.center;
        int half = type.half;
        int minFromCenter = type == BuildingType.MINE_HOUSE ? MINE_AWAY - 4 : switch (type.branch) {
            case WOOD -> WOOD_AWAY - 4;
            case FOOD -> 10;
            default -> type.isWorkshop() ? 16 : 10;
        };
        int maxFromCenter = type == BuildingType.MINE_HOUSE ? ROCK_REACH + 10 : type.branch == BuildingType.Branch.WOOD ? 80 : MAX_RING + 12;
        for (int ring = half + 2; ring <= 22; ring += 2) {
            int steps = Math.max(8, ring * 2);
            for (int a = 0; a < steps; a++) {
                double ang = a * Math.PI * 2 / steps;
                int x = anchor.getX() + (int) Math.round(Math.cos(ang) * ring);
                int z = anchor.getZ() + (int) Math.round(Math.sin(ang) * ring);
                double d = Math.hypot(x - c.getX(), z - c.getZ());
                if (d < minFromCenter || d > maxFromCenter) continue;
                BlockPos pos = new BlockPos(x, c.getY(), z);
                if (clash(v, pos, half, ignore)) {
                    no("clash");
                    continue;
                }
                Integer floor = floor(level, v, pos, half, steep);
                if (floor == null || !fits(level, v, type, pos)) continue;
                Direction facing = Direction.getApproximateNearest(c.getX() - x, 0, c.getZ() - z);
                return new Blueprint.Frame(new BlockPos(x, floor, z), facing);
            }
        }
        return null;
    }

    /** Blocks kept between the water and any building but the fishers' and the pier. */
    static final int SHORE = 6;

    /**
     * The pier's plot: on the shore where a jetty can reach deep water ({@link Harbour#jetty}). The shore is looked for
     * along lines out from the middle (the nearest first); the plot a few blocks back from where the water starts.
     */
    private static Blueprint.Frame harbour(ServerLevel level, Village v, int ignore) {
        BuildingType type = BuildingType.PIER;
        int half = type.half;
        List<int[]> shores = new ArrayList<>();
        for (int a = 0; a < 32; a++) {
            double ang = a * Math.PI * 2 / 32 + v.id * 0.37;
            double cx = Math.cos(ang), cz = Math.sin(ang);
            int lastDry = -1;
            for (int d = 6; d <= MAX_RING + 24; d += 2) {
                int x = v.center.getX() + (int) Math.round(cx * d), z = v.center.getZ() + (int) Math.round(cz * d);
                if (wet(level, v, new BlockPos(x, 0, z), 0)) {
                    if (lastDry >= 0) shores.add(new int[]{lastDry, a});
                    break;
                }
                lastDry = d;
            }
        }
        if (shores.isEmpty()) no("shoreless");
        shores.sort(java.util.Comparator.comparingInt(s -> s[0]));
        for (int[] s : shores) {
            double ang = s[1] * Math.PI * 2 / 32 + v.id * 0.37;
            double cx = Math.cos(ang), cz = Math.sin(ang);
            // a few blocks back from the water, and a little to either side along the shore
            for (int back = half + 1; back <= half + 5; back += 2) {
                for (int side : new int[]{0, 4, -4, 8, -8}) {
                    double d = s[0] - back;
                    int x = v.center.getX() + (int) Math.round(cx * d - cz * side), z = v.center.getZ() + (int) Math.round(cz * d + cx * side);
                    BlockPos pos = new BlockPos(x, v.center.getY(), z);
                    if (clash(v, pos, half, ignore)) {
                        no("clash");
                        continue;
                    }
                    Integer floor = floor(level, v, pos, half, true);
                    if (floor == null || !fits(level, v, type, pos)) continue;
                    BlockPos origin = new BlockPos(x, floor, z);
                    int[] jetty = Harbour.jetty(level, v, origin, half);
                    if (jetty == null) {
                        no("jetty");
                        continue;
                    }
                    // (the jetty clear of the other plots)
                    Direction out = Direction.from2DDataValue(jetty[0]);
                    boolean free = true;
                    for (int k = 1; k <= jetty[1] + 1 && free; k++) {
                        BlockPos p = origin.relative(out, half + k);
                        for (Building b : v.buildings) if (b.id != ignore && b.overlaps(p, 2, 0)) free = false;
                    }
                    if (!free) {
                        no("jetty");
                        continue;
                    }
                    return new Blueprint.Frame(origin, Direction.getApproximateNearest(v.center.getX() - x, 0, v.center.getZ() - z));
                }
            }
        }
        return null;
    }


    /**
     * May a building of this type go up here: away from the shore (the fishers right by it), within the land the
     * village may hold (when that is capped), and where people can walk to from the square on dry feet.
     */
    private static boolean fits(ServerLevel level, Village v, BuildingType type, BlockPos pos) {
        if (type == BuildingType.MINE_HOUSE) {
            // the pit behind the house: clear of the other plots, dry
            Direction facing = Direction.getApproximateNearest(v.center.getX() - pos.getX(), 0, v.center.getZ() - pos.getZ());
            BlockPos pit = Mine.pitCenter(pos, facing);
            if (clash(v, pit, Mine.radius(3) + 1, -1)) return no("pit");
            if (Construction.loaded(level, pit) && wet(level, v, pit, Mine.radius(3) + 2)) return no("pit");
        }
        if (!Territory.allows(v, type, pos.getX(), pos.getZ())) return no("cap");
        if (onPath(level, pos, type.half + 1)) return no("path");
        if (type.branch != BuildingType.Branch.COAST && wet(level, v, pos, type.half + SHORE)) return no("shore");
        // (across a stream from the square: only if a short bridge will do; it is put up first)
        if (Construction.loaded(level, pos) && !Reach.ok(level, v, new BlockPos(pos.getX(), floorAt(level, pos.getX(), pos.getZ()), pos.getZ()))
                && !bridgeable(level, v, pos)) return no("reach");
        return true;
    }

    /** The most water a path from a plot to the square may cross (a short bridge). */
    static final int BRIDGE = 12;

    /** On the straight way from a plot to the square, is there no more water than a short bridge spans (and all loaded)? */
    static boolean bridgeable(ServerLevel level, Village v, BlockPos pos) {
        int dx = v.center.getX() - pos.getX(), dz = v.center.getZ() - pos.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz)), wet = 0;
        for (int k = 0; k <= steps; k++) {
            int x = pos.getX() + Math.round(dx * (float) k / Math.max(1, steps)), z = pos.getZ() + Math.round(dz * (float) k / Math.max(1, steps));
            if (!Construction.loaded(level, new BlockPos(x, 0, z))) return false;
            int f = floorAt(level, x, z);
            if (!level.getBlockState(new BlockPos(x, f - 1, z)).getFluidState().isEmpty() && ++wet > BRIDGE) return false;
        }
        return true;
    }

    /** Does a street (a trodden path) run over the square of {@code r} round a point? No building goes up on one. */
    static boolean onPath(ServerLevel level, BlockPos c, int r) {
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int px = c.getX() + x, pz = c.getZ() + z;
                if (!Construction.loaded(level, new BlockPos(px, 0, pz))) continue;
                if (level.getBlockState(new BlockPos(px, floorAt(level, px, pz) - 1, pz)).is(net.minecraft.world.level.block.Blocks.DIRT_PATH)) return true;
            }
        }
        return false;
    }

    /** Is there water within {@code r} of a point (the generator's land where not loaded)? */
    static boolean wet(ServerLevel level, Village v, BlockPos c, int r) {
        VillageTerrain.Grid grid = null;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int px = c.getX() + x, pz = c.getZ() + z;
                // (the water of the village's own fields and troughs is no shore)
                if (DwellerGoals.inPlot(v, px, pz, 0)) continue;
                if (Construction.loaded(level, new BlockPos(px, 0, pz))) {
                    int f = floorAt(level, px, pz);
                    if (!level.getBlockState(new BlockPos(px, f - 1, pz)).getFluidState().isEmpty()) return true;
                } else {
                    if (grid == null) grid = VillageTerrain.grid(level, v);
                    if (grid == null) return false;
                    Integer g = grid.at(px, pz);
                    if (g != null && g < 0) return true;
                }
            }
        }
        return false;
    }

    private static boolean clash(Village v, BlockPos pos, int half, int ignore) {
        if (Math.abs(pos.getX() - v.board.getX()) <= half + 1 && Math.abs(pos.getZ() - v.board.getZ()) <= half + 1) return true;
        for (Building b : v.buildings) {
            if (b.id == ignore) continue;
            if (b.overlaps(pos, half, GAP)) return true;
        }
        // (the players' plots)
        for (Plots.Plot p : v.tasks.plots()) {
            if (Math.abs(pos.getX() - p.marker().getX()) <= half + Plots.HALF + GAP && Math.abs(pos.getZ() - p.marker().getZ()) <= half + Plots.HALF + GAP) return true;
        }
        // (the pit of the miners' house)
        return Mine.pitClash(v, pos, half, GAP);
    }

    /**
     * The floor height for a plot: the middle of the land there, if it is dry, loaded and no steeper than a small
     * terrace; else null.
     */
    /** Is the plot on a beach (by the world's biomes: known even where the land is not loaded)? */
    static boolean beach(ServerLevel level, BlockPos center, int half) {
        var gen = level.getChunkSource().getGenerator();
        var rs = level.getChunkSource().randomState();
        int y = level.getSeaLevel() >> 2;
        int[][] at = half == 0 ? new int[][]{{0, 0}} : new int[][]{{0, 0}, {-half, -half}, {half, half}, {-half, half}, {half, -half}};
        for (int[] d : at) {
            var b = gen.getBiomeSource().getNoiseBiome((center.getX() + d[0]) >> 2, y, (center.getZ() + d[1]) >> 2, rs.sampler());
            if (b.is(net.minecraft.tags.BiomeTags.IS_BEACH)) return true;
        }
        return false;
    }

    static Integer floor(ServerLevel level, BlockPos center, int half) {
        return floor(level, null, center, half, false);
    }

    /**
     * The floor for a camp's middle: low in the lie of the land round it (a quarter of it lower, the rest higher), so
     * that a bump there is cut away and the land round eased down to it, never the fire put up on a heap of earth.
     * Null if there is water there or it is not loaded.
     */
    static Integer lowFloor(ServerLevel level, BlockPos center, int half) {
        List<Integer> heights = new ArrayList<>();
        for (int x = -half - 1; x <= half + 1; x++) {
            for (int z = -half - 1; z <= half + 1; z++) {
                int px = center.getX() + x, pz = center.getZ() + z;
                if (!Construction.loaded(level, new BlockPos(px, 0, pz))) return null;
                int f = floorAt(level, px, pz);
                BlockState st = level.getBlockState(new BlockPos(px, f - 1, pz));
                if (!st.getFluidState().isEmpty() || st.is(BlockTags.ICE)) continue;
                heights.add(f);
            }
        }
        if (heights.size() < 9) return null;
        heights.sort(Integer::compare);
        return Math.max(level.getSeaLevel(), heights.get(heights.size() / 4));
    }

    /**
     * {@code steep}: up to 5 blocks of the plot heaped up and 6 dug away (else 3 and 4). Where the land is not loaded
     * (nobody is near the village), the land as the world's generator made it, read round the village beforehand
     * ({@link VillageTerrain}); none until that is read.
     */
    static Integer floor(ServerLevel level, Village v, BlockPos center, int half, boolean steep) {
        List<Integer> heights = new ArrayList<>();
        int sea = level.getSeaLevel();
        VillageTerrain.Grid grid = null;
        // (not on a beach: sand is no ground for a house)
        int sand = 0, ground = 0;
        for (int x = -half - 1; x <= half + 1; x++) {
            for (int z = -half - 1; z <= half + 1; z++) {
                int px = center.getX() + x, pz = center.getZ() + z;
                int f;
                if (!Construction.loaded(level, new BlockPos(px, 0, pz))) {
                    if (grid == null) grid = v == null ? null : VillageTerrain.grid(level, v);
                    if (grid == null) {
                        no("unread");
                        return null;
                    }
                    Integer g = grid.at(px, pz);
                    if (g == null || g < 0) {
                        no(g == null ? "far" : "water");
                        return null;
                    }
                    f = g;
                } else {
                    f = floorAt(level, px, pz);
                    BlockState st = level.getBlockState(new BlockPos(px, f - 1, pz));
                    if (!st.getFluidState().isEmpty() || st.is(BlockTags.ICE)) {
                        no("water");
                        return null;
                    }
                    ground++;
                    if (st.is(BlockTags.SAND) || st.is(net.minecraft.world.level.block.Blocks.GRAVEL)) sand++;
                }
                heights.add(f);
            }
        }
        if (!sandOk && ground > 0 && sand * 4 > ground) {
            no("sand");
            return null;
        }
        heights.sort(Integer::compare);
        int floor = heights.get(heights.size() / 2);
        if (floor < sea) {
            no("low");
            return null;
        }
        // (where the land is loaded its sand is seen above; where it is not, a plot low down on a beach is passed
        // over: the beach biome runs in a band along every coast, grass and all, only its low strand is sand)
        if (!sandOk && ground == 0 && floor <= sea + 2 && beach(level, center, 0)) {
            no("beach");
            return null;
        }
        if (floor - heights.getFirst() > (steep ? 4 : 3) || heights.getLast() - floor > (steep ? 4 : 3)) {
            no("steep");
            return null;
        }
        return floor;
    }
}
