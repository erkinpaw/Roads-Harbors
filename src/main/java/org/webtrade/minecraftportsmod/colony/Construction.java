package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.webtrade.minecraftportsmod.registry.ModContent;

import java.util.List;

/** Puts buildings up and pulls them down in the world, a block at a time. Server thread, loaded chunks only. */
final class Construction {

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** Headroom cleared above a levelled plot (trees, grass, snow...). */
    private static final int CLEAR = 10;

    private Construction() {
    }

    static boolean loaded(ServerLevel level, BlockPos p) {
        return level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) != null;
    }

    /** Is the whole plot of the building in loaded chunks? */
    static boolean plotLoaded(ServerLevel level, Building b) {
        int h = b.type.half + 2;
        for (int dx = -h; dx <= h; dx += h) {
            for (int dz = -h; dz <= h; dz += h) {
                if (!loaded(level, b.origin.offset(dx, 0, dz))) return false;
            }
        }
        return true;
    }

    /**
     * Brings the blocks in the world a few steps closer to what the building's progress says should stand.
     * Returns the number of blocks changed.
     */
    static int advance(ServerLevel level, Village v, Building b, int maxSteps) {
        Blueprint bp = b.blueprint(v.wood);
        List<Blueprint.Piece> pieces = bp.pieces;
        int target = b.target(pieces.size());
        int steps = 0;
        if (target > 0 && !b.levelled) {
            // the trees on and right around the plot come down whole (and go to the store)
            v.add(Res.WOOD, clearTrees(level, b.origin, b.type.half + 2));
            level(level, bp);
            blend(level, v, b);
            b.levelled = true;
            steps++;
        }
        while (b.placed < target && steps < maxSteps) {
            Blueprint.Piece p = pieces.get(b.placed);
            place(level, p);
            b.placed++;
            steps++;
            if (steps == 1) effect(level, p.pos(), p.state(), true);
        }
        while (b.placed > target && steps < maxSteps) {
            b.placed--;
            Blueprint.Piece p = pieces.get(b.placed);
            BlockState now = level.getBlockState(p.pos());
            if (now.is(p.state().getBlock()) || p.state().is(Blocks.WATER) && now.is(Blocks.WATER)) {
                level.setBlock(p.pos(), Blocks.AIR.defaultBlockState(), FLAGS);
                if (steps == 0) effect(level, p.pos(), p.state(), false);
            }
            steps++;
        }
        return steps;
    }

    private static void place(ServerLevel level, Blueprint.Piece p) {
        level.setBlock(p.pos(), p.state(), FLAGS);
        if (p.shaped()) level.setBlock(p.pos(), Block.updateFromNeighbourShapes(p.state(), level, p.pos()), FLAGS);
    }

    /** Dust and a knock: someone is working at this block. */
    private static void effect(ServerLevel level, BlockPos pos, BlockState state, boolean building) {
        if (state.isAir() || !state.getFluidState().isEmpty()) return;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                building ? 4 : 8, 0.3, 0.3, 0.3, 0.05);
        var sound = state.getSoundType();
        level.playSound(null, pos, building ? sound.getPlaceSound() : sound.getBreakSound(), SoundSource.BLOCKS,
                0.5F, sound.getPitch() * 0.9F);
    }

    /** Levels the plot: ground filled up to the floor, the surface laid, trees and grass cleared above. */
    static void level(ServerLevel level, Blueprint bp) {
        int floor = bp.frame.origin().getY();
        BlockState dirt = Blocks.DIRT.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (var e : bp.ground.entrySet()) {
            int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            for (int y = Math.min(top, floor - 2); y <= floor - 2; y++) {
                BlockPos p = new BlockPos(x, y, z);
                BlockState s = level.getBlockState(p);
                if (y > top || !s.isSolid() || !s.getFluidState().isEmpty()) level.setBlock(p, dirt, FLAGS);
            }
            level.setBlock(new BlockPos(x, floor - 1, z), e.getValue(), FLAGS);
            for (int y = floor; y <= floor + CLEAR; y++) {
                BlockPos p = new BlockPos(x, y, z);
                BlockState s = level.getBlockState(p);
                if (s.isAir()) continue;
                // other buildings never stand on a plot; the post of this one is kept
                if (s.is(ModContent.CONSTRUCTION_SITE)) continue;
                level.setBlock(p, air, FLAGS);
            }
            // and no crown of a tree left hanging over it
            for (int y = floor + CLEAR + 1; y <= floor + 32; y++) {
                BlockPos p = new BlockPos(x, y, z);
                BlockState s = level.getBlockState(p);
                if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(Blocks.VINE)) level.setBlock(p, air, FLAGS);
            }
        }
    }

    /** How wide the band round a plot is where the land is eased down (or up) to it. */
    private static final int BLEND = 5;

    /**
     * Eases the land round a levelled plot so that it meets the plot gently: {@code k} blocks out from the plot the
     * ground is at most {@code k} blocks above or below it. No ledge in front of a door, no house on a step.
     */
    static void blend(ServerLevel level, Village v, Building b) {
        int ground = b.origin.getY() - 1, h = b.type.half;
        var laid = b.blueprint(v.wood).ground;
        for (int dx = -h; dx <= h; dx++) {
            for (int dz = -h; dz <= h; dz++) {
                int x = b.origin.getX() + dx, z = b.origin.getZ() + dz;
                if (!laid.containsKey(Blueprint.key(x, z))) ease(level, x, z, ground, 1);
            }
        }
        for (int k = 1; k <= BLEND; k++) {
            for (int dx = -h - k; dx <= h + k; dx++) {
                for (int dz = -h - k; dz <= h + k; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != h + k) continue;
                    slope(level, v, b.origin.getX() + dx, b.origin.getZ() + dz, ground, k);
                }
            }
        }
    }

    /** Land that may be dug away or built up: earth, rock, sand... (never anything built). */
    static boolean natural(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM) || s.is(Blocks.COARSE_DIRT)
                || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.MOSS_BLOCK) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.DIRT_PATH) || s.is(Blocks.FARMLAND) || s.is(Blocks.SNOW_BLOCK) || s.is(BlockTags.TERRACOTTA)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.CLAY) || s.is(Blocks.MUD);
    }

    /**
     * Makes one column's ground lie within {@code k} of height {@code ref}: dug down if higher, earth heaped up if
     * lower. Columns of a plot, of water, or with anything built in the way are left alone.
     */
    static void slope(ServerLevel level, Village v, int x, int z, int ref, int k) {
        if (!loaded(level, new BlockPos(x, 0, z))) return;
        for (Building o : v.buildings) {
            int h = o.type.half;
            if (Math.abs(x - o.origin.getX()) <= h && Math.abs(z - o.origin.getZ()) <= h) return;
        }
        if (Math.abs(x - v.board.getX()) <= 1 && Math.abs(z - v.board.getZ()) <= 1) return;
        ease(level, x, z, ref, k);
    }

    /** {@link #slope} without asking whose plot the column is on. */
    private static void ease(ServerLevel level, int x, int z, int ref, int k) {
        if (!loaded(level, new BlockPos(x, 0, z))) return;
        int top = PlotFinder.floorAt(level, x, z) - 1;
        BlockPos p = new BlockPos(x, top, z);
        BlockState surface = level.getBlockState(p);
        if (!surface.getFluidState().isEmpty() || !level.getBlockState(p.above()).getFluidState().isEmpty()) return;
        if (top > ref + k) {
            // dig down: only land, nothing built, in the way
            for (int y = ref + k + 1; y <= top; y++) if (!natural(level.getBlockState(new BlockPos(x, y, z)))) return;
            BlockState above = level.getBlockState(p.above());
            if (!above.isAir() && !above.canBeReplaced()) return;
            for (int y = top; y > ref + k; y--) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
            BlockPos nt = new BlockPos(x, ref + k, z);
            if (level.getBlockState(nt).is(Blocks.DIRT)) level.setBlock(nt, Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
            if (!above.isAir()) level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), FLAGS);
        } else if (top < ref - k) {
            // heap up: earth, grass on top
            if (!natural(surface)) return;
            for (int y = top + 1; y <= ref - k; y++) {
                BlockPos q = new BlockPos(x, y, z);
                BlockState s = level.getBlockState(q);
                if (!s.isAir() && !s.canBeReplaced()) return;
            }
            if (surface.is(Blocks.GRASS_BLOCK)) level.setBlock(p, Blocks.DIRT.defaultBlockState(), FLAGS);
            for (int y = top + 1; y <= ref - k; y++) {
                level.setBlock(new BlockPos(x, y, z), y == ref - k ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), FLAGS);
            }
        }
    }

    /**
     * Clears the trees (whole: trunk and crown) standing within {@code r} of a point: a clearing for a new camp.
     * Returns the number of logs taken.
     */
    static int clearTrees(ServerLevel level, BlockPos c, int r) {
        int logs = 0;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z > r * r) continue;
                int px = c.getX() + x, pz = c.getZ() + z;
                if (!loaded(level, new BlockPos(px, 0, pz))) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz) - 1;
                BlockPos p = new BlockPos(px, top, pz);
                // (a huge mushroom: all of it, nothing kept)
                if (WorkGoal.fungus(level.getBlockState(p))) {
                    for (BlockPos q : WorkGoal.mushroom(level, p)) level.setBlock(q, Blocks.AIR.defaultBlockState(), FLAGS);
                    continue;
                }
                if (!level.getBlockState(p).is(BlockTags.LOGS)) continue;
                while (level.getBlockState(p.below()).is(BlockTags.LOGS)) p = p.below();
                List<BlockPos> tree = WorkGoal.tree(level, p, null);
                if (tree == null) continue;
                for (BlockPos t : tree) {
                    if (level.getBlockState(t).is(BlockTags.LOGS)) logs++;
                    level.setBlock(t, Blocks.AIR.defaultBlockState(), FLAGS);
                }
            }
        }
        return logs;
    }

    /** After a demolition: the plot goes back to grass. */
    static void restoreGround(ServerLevel level, Blueprint bp) {
        int floor = bp.frame.origin().getY();
        for (var e : bp.ground.entrySet()) {
            int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
            BlockPos p = new BlockPos(x, floor - 1, z);
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)) continue;
            if (s.isAir() || !s.getFluidState().isEmpty() || s.is(BlockTags.PLANKS) || s.is(BlockTags.LOGS) || s.is(Blocks.COARSE_DIRT)
                    || s.is(Blocks.DIRT_PATH) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.GRAVEL) || s.is(Blocks.FARMLAND)) {
                level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
            }
            BlockPos above = p.above();
            if (!level.getBlockState(above).getFluidState().isEmpty()) level.setBlock(above, Blocks.AIR.defaultBlockState(), FLAGS);
        }
    }

    /** Where the construction post of a building stands: at its corner, on the ground there. */
    static BlockPos postAt(ServerLevel level, Village v, Building b) {
        BlockPos p = b.blueprint(v.wood).post;
        if (!loaded(level, p)) return p;
        BlockState here = level.getBlockState(p);
        if (here.is(ModContent.CONSTRUCTION_SITE)) return p;
        for (int dy = -4; dy <= 4; dy++) {
            if (level.getBlockState(p.above(dy)).is(ModContent.CONSTRUCTION_SITE)) return p.above(dy);
        }
        return new BlockPos(p.getX(), PlotFinder.floorAt(level, p.getX(), p.getZ()), p.getZ());
    }

    /** Puts up or takes away the construction post of a building. */
    static void post(ServerLevel level, Village v, Building b, boolean wanted) {
        BlockPos p = postAt(level, v, b);
        BlockState now = level.getBlockState(p);
        if (wanted) {
            // a building being raised a level shows the site's post again: there are things to bring
            boolean built = b.state == Building.State.BUILT && !b.upgrading();
            if (now.is(ModContent.CONSTRUCTION_SITE)) {
                if (now.getValue(org.webtrade.minecraftportsmod.block.ConstructionSiteBlock.BUILT) != built) {
                    level.setBlock(p, now.setValue(org.webtrade.minecraftportsmod.block.ConstructionSiteBlock.BUILT, built), FLAGS);
                }
                return;
            }
            if (!now.isAir() && !now.canBeReplaced()) return;
            // stand it on the ground
            BlockPos below = p.below();
            if (level.getBlockState(below).isAir()) level.setBlock(below, Blocks.DIRT.defaultBlockState(), FLAGS);
            level.setBlock(p, ModContent.CONSTRUCTION_SITE.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, b.front)
                    .setValue(org.webtrade.minecraftportsmod.block.ConstructionSiteBlock.BUILT, built), FLAGS);
        } else if (now.is(ModContent.CONSTRUCTION_SITE)) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
        }
    }
}
