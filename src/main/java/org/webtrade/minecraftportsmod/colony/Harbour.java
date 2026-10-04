package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The village's harbour: the pier (a skipper's house on the shore and a jetty out to water deep enough for a ship)
 * and its ships. A pier of the first level keeps a sloop, of the second a brig; at the third it keeps two brigs (the
 * village's fleet). Each ship is sailed by a skipper of the village; see {@link Voyages} for their trade.
 */
public final class Harbour {

    private Harbour() {
    }

    /** The longest jetty, and how far over dry land it may start (a strand before the water). */
    static final int MAX_JETTY = 22, DRY_START = 6;
    /** Water this deep at the jetty's end (a ship's keel). */
    static final int DEEP = 3;
    /** What a ship carries, by the pier's level: a sloop, a brig. */
    static final int SLOOP_LOAD = 1500, BRIG_LOAD = 2500;

    /**
     * Does the village want a pier: an island as soon as it has hands for it (its only way to trade); a village on the
     * mainland once it is big enough to send ships out besides its merchant.
     */
    static boolean wanted(Village v) {
        return testWanted || (v.island ? v.adults() >= 6 : v.adults() >= 12);
    }

    /** Tests: the pier opened for a village, and the store given what it takes to build it (and a fishers' hut grown). */
    public static void testReady(Village v) {
        v.unlocked.add(BuildingType.FISH_HUT);
        v.unlocked.add(BuildingType.PIER);
        v.add(Res.WOOD, 200);
        v.add(Res.PLANKS, 150);
        v.add(Res.STONE, 60);
        v.add(Res.COAL, 10);
        testWanted = true;
    }

    /** Tests: every village wants a pier. */
    public static boolean testWanted;

    /** The village's standing pier, or null. */

    static Building pier(Village v) {
        for (Building b : v.buildings) if (b.type == BuildingType.PIER && b.state == Building.State.BUILT && b.jetty != null) return b;
        return null;
    }

    /** Ships the village keeps: one at a pier of the first two levels, two at the third. */
    public static int ships(Village v) {
        Building p = pier(v);
        return p == null ? 0 : p.level >= 3 ? 2 : 1;
    }

    /** What a ship of the village carries. */
    static int load(Village v) {
        Building p = pier(v);
        return p == null ? 0 : p.level >= 2 ? BRIG_LOAD : SLOOP_LOAD;
    }

    /** The model of the village's ships: 1 a sloop, 2 a brig. */
    static int tier(Village v) {
        Building p = pier(v);
        return p == null || p.level < 2 ? 1 : 2;
    }

    /** The jetty's way out to sea. */
    static Direction out(Building pier) {
        return Direction.from2DDataValue(pier.jetty[0]);
    }

    /** The water at the end of the jetty (where the ships set out from and come in to), at the sea's level. */
    static BlockPos seaward(Building pier) {
        Direction out = out(pier);
        int k = pier.type.half + pier.jetty[1] + 2;
        return new BlockPos(pier.origin.getX() + out.getStepX() * k, pier.jetty[2], pier.origin.getZ() + out.getStepZ() * k);
    }

    /** Where ship {@code i} lies moored: alongside the jetty's end, one each side. */
    static Vec3 berth(Building pier, int i) {
        Direction out = out(pier), side = out.getClockWise();
        int k = pier.type.half + pier.jetty[1] - 1;
        double s = i == 0 ? 3.5 : -3.5;
        return new Vec3(pier.origin.getX() + 0.5 + out.getStepX() * k + side.getStepX() * s, pier.jetty[2] - 0.45,
                pier.origin.getZ() + 0.5 + out.getStepZ() * k + side.getStepZ() * s);
    }

    /** The way a ship moored at the jetty lies (yaw, degrees): along the jetty, bow out to sea. */
    static float berthYaw(Building pier) {
        return out(pier).toYRot();
    }

    // ------------------------------------------------------------------ the water

    /**
     * How deep the water is over a column (0: dry land): the blocks in the world where they are loaded, the
     * generator's sea floor where not.
     */
    static int depth(ServerLevel level, int x, int z) {
        return depth(level, null, x, z);
    }

    /** The same, unloaded land read off what was read round the village (see {@link VillageTerrain}) where it can be. */
    static int depth(ServerLevel level, Village v, int x, int z) {
        int sea = level.getSeaLevel();
        if (!Construction.loaded(level, new BlockPos(x, 0, z)) && frozen(level, x, z)) return 0;
        if (v != null && !Construction.loaded(level, new BlockPos(x, 0, z))) {
            VillageTerrain.Grid g = VillageTerrain.grid(level, v);
            Integer d = g == null ? null : g.depth(x, z);
            if (d != null) return d;
        }
        if (Construction.loaded(level, new BlockPos(x, 0, z))) {
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
            int d = 0;
            while (d < 12 && !level.getBlockState(p).getFluidState().isEmpty()) {
                d++;
                p.move(0, -1, 0);
            }
            return d;
        }
        Integer isle = org.webtrade.minecraftportsmod.worldgen.RaisedIslands.floor(x, z, sea);
        int floor = isle != null ? isle : level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level,
                level.getChunkSource().randomState());
        return Math.max(0, sea - floor);

    }

    /** Frozen water by the world's biomes (ice over it, where the land is not loaded yet: no way for a ship). */
    static boolean frozen(ServerLevel level, int x, int z) {
        var gen = level.getChunkSource().getGenerator();
        var b = gen.getBiomeSource().getNoiseBiome(x >> 2, level.getSeaLevel() >> 2, z >> 2, level.getChunkSource().randomState().sampler());
        return b.unwrapKey().map(k -> k.identifier().getPath().contains("frozen")).orElse(false);
    }

    /**
     * The jetty of a pier standing at {@code origin}: {way out (2D data value), length beyond the plot, the sea's
     * level}, or null if no side of the plot has water deep enough within {@link #MAX_JETTY} (dry land at most
     * {@link #DRY_START} of it, then water all the way, and room for a ship at its end). The shortest one.
     */
    static int[] jetty(ServerLevel level, BlockPos origin, int half) {
        return jetty(level, null, origin, half);
    }

    static int[] jetty(ServerLevel level, Village v, BlockPos origin, int half) {
        int[] best = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int dry = 0;
            boolean wet = false;
            for (int k = 1; k <= MAX_JETTY; k++) {
                int x = origin.getX() + d.getStepX() * (half + k), z = origin.getZ() + d.getStepZ() * (half + k);
                int depth = depth(level, v, x, z);
                if (depth == 0) {
                    if (wet || ++dry > DRY_START) break;
                    continue;
                }
                wet = true;
                if (depth < DEEP) continue;
                // room for the ship beyond the end, and to either side of it
                boolean room = true;
                for (int e = 1; e <= 5 && room; e++) {
                    int ex = x + d.getStepX() * e, ez = z + d.getStepZ() * e;
                    room = depth(level, v, ex, ez) >= 2;
                }
                Direction side = d.getClockWise();
                if (room) room = depth(level, v, x + side.getStepX() * 4, z + side.getStepZ() * 4) >= 2
                        && depth(level, v, x - side.getStepX() * 4, z - side.getStepZ() * 4) >= 2;
                if (room && (best == null || k < best[1])) best = new int[]{d.get2DDataValue(), k, level.getSeaLevel()};
                break;
            }
        }
        return best;
    }
}
