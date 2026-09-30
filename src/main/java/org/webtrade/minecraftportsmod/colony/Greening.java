package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A village makes the land around it its own: bare rock, gravel and dry sand turn to grass, a blade of grass or a
 * flower here and there, a little at a time, farther out as the village grows.
 */
final class Greening {

    private static final RandomSource RND = RandomSource.create();
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final Block[] FLOWERS = {Blocks.DANDELION, Blocks.POPPY, Blocks.CORNFLOWER, Blocks.OXEYE_DAISY, Blocks.AZURE_BLUET};

    private Greening() {
    }

    /** How far out the village has made the land green. */
    static int radius(Village v) {
        return 12 + 6 * v.level().ordinal();
    }

    /** A few blocks greener. */
    static void step(ServerLevel level, Village v, int tries) {
        int r = radius(v);
        for (int i = 0; i < tries; i++) {
            int x = v.center.getX() + RND.nextInt(2 * r + 1) - r, z = v.center.getZ() + RND.nextInt(2 * r + 1) - r;
            if ((x - v.center.getX()) * (x - v.center.getX()) + (z - v.center.getZ()) * (z - v.center.getZ()) > r * r) continue;
            if (!Construction.loaded(level, new BlockPos(x, 0, z)) || builtOn(v, x, z)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            if (Math.abs(top + 1 - v.center.getY()) > 4) continue;
            BlockPos p = new BlockPos(x, top, z);
            BlockState s = level.getBlockState(p);
            if (!level.getBlockState(p.above()).isAir()) continue;
            boolean bare = s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.GRAVEL) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.DIRT)
                    || (s.is(Blocks.SAND) || s.is(Blocks.RED_SAND)) && !nearWater(level, p, 3);
            // grass spreads from grass: the green grows out of the plots and paths, not in spots
            if (bare && !nearWater(level, p, 1) && nextToGrass(level, p)) {
                level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
                continue;
            }
        }
    }

    /** Anything the village built or uses stands here (plots, paths, the board)? */
    private static boolean builtOn(Village v, int x, int z) {
        for (Building b : v.buildings) {
            int h = b.type.half + 1;
            if (Math.abs(x - b.origin.getX()) <= h && Math.abs(z - b.origin.getZ()) <= h) return true;
        }
        return Math.abs(x - v.board.getX()) <= 1 && Math.abs(z - v.board.getZ()) <= 1;
    }

    private static boolean nextToGrass(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockState n = level.getBlockState(p.relative(d).above(dy));
                if (n.is(Blocks.GRASS_BLOCK) || n.is(Blocks.DIRT_PATH)) return true;
            }
        }
        return false;
    }

    private static boolean nearWater(ServerLevel level, BlockPos p, int d) {
        for (int dx = -d; dx <= d; dx++) {
            for (int dz = -d; dz <= d; dz++) {
                if (!level.getBlockState(p.offset(dx, 0, dz)).getFluidState().isEmpty()) return true;
                if (!level.getBlockState(p.offset(dx, 1, dz)).getFluidState().isEmpty()) return true;
            }
        }
        return false;
    }
}
