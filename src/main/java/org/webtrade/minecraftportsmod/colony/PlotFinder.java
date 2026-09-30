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
        // (the mine keeps well away from the houses even with no rock to go by)
        boolean mine = type.branch == BuildingType.Branch.MINE;
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
                if (floor == null) continue;
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
                // the rock well away from the houses (noise, dust, the mine behind it); the farthest there is if none is that far
                return farRock(level, v);
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
        for (int d = MINE_AWAY; d <= MINE_AWAY + 40; d += 4) {
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

    /** How far out of the village the mine and the woodcutters stand. */
    static final int MINE_AWAY = 85, WOOD_AWAY = 24;

    /** A plot as close as can be to a point, not too near the middle of the village (the trades keep apart). */
    private static Blueprint.Frame near(ServerLevel level, Village v, BuildingType type, BlockPos anchor, int ignore, boolean steep) {
        BlockPos c = v.center;
        int half = type.half;
        int minFromCenter = switch (type.branch) {
            case MINE -> MINE_AWAY - 4;
            case WOOD -> WOOD_AWAY - 4;
            case FOOD -> 10;
            default -> type.isWorkshop() ? 16 : 10;
        };
        int maxFromCenter = type.branch == BuildingType.Branch.MINE ? MINE_AWAY + 45 : type.branch == BuildingType.Branch.WOOD ? 80 : MAX_RING + 12;
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
                if (floor == null) continue;
                Direction facing = Direction.getApproximateNearest(c.getX() - x, 0, c.getZ() - z);
                return new Blueprint.Frame(new BlockPos(x, floor, z), facing);
            }
        }
        return null;
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
                }
                heights.add(f);
            }
        }
        heights.sort(Integer::compare);
        int floor = heights.get(heights.size() / 2);
        if (floor < sea) return null;
        if (floor - heights.getFirst() > (steep ? 5 : 3) || heights.getLast() - floor > (steep ? 6 : 4)) return null;
        return floor;
    }
}
