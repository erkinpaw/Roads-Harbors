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
                || s.is(net.minecraft.world.level.block.Blocks.CACTUS);
    }

    /**
     * A frame for a building of this type, or null if there is no room. {@code ignore} is a building that may be
     * overlapped (the one being replaced).
     */
    static Blueprint.Frame find(ServerLevel level, Village v, BuildingType type, int ignore) {
        Blueprint.Frame f = find(level, v, type, ignore, false);
        // hilly land (a jungle, a coast of knolls): a steeper plot, cut and heaped level round it, rather than none
        return f != null ? f : find(level, v, type, ignore, true);
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
                if (clash(v, pos, half, ignore)) continue;
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

    /** The miners' house's place: the nearest bare rock in reach; else a knoll or a hollow; null: anywhere some way out. */
    private static BlockPos mineSpot(ServerLevel level, Village v) {
        BlockPos rock = farRock(level, v);
        return rock != null ? rock : hillOrHollow(level, v);
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
                if (clash(v, pos, half, ignore)) continue;
                Integer floor = floor(level, v, pos, half, steep);
                if (floor == null || !fits(level, v, type, pos)) continue;
                Direction facing = Direction.getApproximateNearest(c.getX() - x, 0, c.getZ() - z);
                return new Blueprint.Frame(new BlockPos(x, floor, z), facing);
            }
        }
        return null;
    }

    /** Blocks kept between the water and any building but the fishers' (and, one day, the harbour's). */
    static final int SHORE = 6;

    /**
     * May a building of this type go up here: away from the shore (the fishers right by it), within the land the
     * village may hold (when that is capped), and where people can walk to from the square on dry feet.
     */
    private static boolean fits(ServerLevel level, Village v, BuildingType type, BlockPos pos) {
        if (!Territory.allows(v, type, pos.getX(), pos.getZ())) return false;
        if (onPath(level, pos, type.half + 1)) return false;
        if (type.branch != BuildingType.Branch.COAST && wet(level, v, pos, type.half + SHORE)) return false;
        // (across a stream from the square: only if a short bridge will do; it is put up first)
        if (Construction.loaded(level, pos) && !Reach.ok(level, v, new BlockPos(pos.getX(), floorAt(level, pos.getX(), pos.getZ()), pos.getZ()))
                && !bridgeable(level, v, pos)) return false;
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
        return false;
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
        for (int[] d : new int[][]{{0, 0}, {-half, -half}, {half, half}, {-half, half}, {half, -half}}) {
            var b = gen.getBiomeSource().getNoiseBiome((center.getX() + d[0]) >> 2, y, (center.getZ() + d[1]) >> 2, rs.sampler());
            if (b.is(net.minecraft.tags.BiomeTags.IS_BEACH)) return true;
        }
        return false;
    }

    static Integer floor(ServerLevel level, BlockPos center, int half) {
        return floor(level, null, center, half, false);
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
        if (beach(level, center, half)) return null;
        int sand = 0, ground = 0;
        for (int x = -half - 1; x <= half + 1; x++) {
            for (int z = -half - 1; z <= half + 1; z++) {
                int px = center.getX() + x, pz = center.getZ() + z;
                int f;
                if (!Construction.loaded(level, new BlockPos(px, 0, pz))) {
                    if (grid == null) grid = v == null ? null : VillageTerrain.grid(level, v);
                    if (grid == null) return null;
                    Integer g = grid.at(px, pz);
                    if (g == null || g < 0) return null;
                    f = g;
                } else {
                    f = floorAt(level, px, pz);
                    BlockState st = level.getBlockState(new BlockPos(px, f - 1, pz));
                    if (!st.getFluidState().isEmpty() || st.is(BlockTags.ICE)) return null;
                    ground++;
                    if (st.is(BlockTags.SAND) || st.is(net.minecraft.world.level.block.Blocks.GRAVEL)) sand++;
                }
                heights.add(f);
            }
        }
        if (ground > 0 && sand * 4 > ground) return null;
        heights.sort(Integer::compare);
        int floor = heights.get(heights.size() / 2);
        if (floor < sea) return null;
        if (floor - heights.getFirst() > (steep ? 4 : 3) || heights.getLast() - floor > (steep ? 4 : 3)) return null;
        return floor;
    }
}
