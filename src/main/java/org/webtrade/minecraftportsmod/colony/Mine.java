package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The village mine: an open pit dug by the miners behind their house, wherever the house stands (by rock, on a
 * knoll or on flat grass: the land round it is only how it looks). Dug a layer at a time, each one a block narrower
 * than the one above, so its sides go down in steps the miners walk; wider and deeper with the house's levels. Once
 * dug, the miners work its walls. How far along it the miners are is kept with the village.
 */
public final class Mine {

    /** One block to dig, where to stand for it, and whether a torch goes up once it is dug. */
    public record Step(BlockPos dig, BlockPos stand, Direction torchWall) {
    }

    /** The pit by the house's level: half its width at the top, and how many layers deep. */
    static int radius(int level) {
        return Math.max(1, Math.min(3, level)) + 2;
    }

    static int depth(int level) {
        return 1 + 2 * Math.max(1, Math.min(3, level));
    }

    private Mine() {
    }

    /** The miners' house the mine starts at, or null. */
    static Building house(Village v) {
        for (Building b : v.buildings) if (b.type == BuildingType.MINE_HOUSE && b.standing()) return b;
        return null;
    }

    /** The middle of the pit of a miners' house standing at a place, facing a way (its back to the pit). */
    static BlockPos pitCenter(BlockPos origin, Direction front) {
        int r = radius(3), d = BuildingType.MINE_HOUSE.half + 1 + r;
        Direction back = front.getOpposite();
        return origin.relative(back, d);
    }

    /** Does a column fall in the pit (as wide as it will ever be, with a margin) of the village's miners' house? */
    static boolean inPit(Village v, int x, int z, int margin) {
        for (Building b : v.buildings) {
            if (b.type != BuildingType.MINE_HOUSE || b.state == Building.State.DEMOLISHING && b.finished) continue;
            BlockPos c = pitCenter(b.origin, b.front);
            int r = radius(3) + margin;
            if (Math.abs(x - c.getX()) <= r && Math.abs(z - c.getZ()) <= r) return true;
        }
        return false;
    }

    /** Would a plot (middle, half its side, a gap kept) fall on the pit of the village's miners' house? */
    static boolean pitClash(Village v, BlockPos pos, int half, int gap) {
        for (Building b : v.buildings) {
            if (b.type != BuildingType.MINE_HOUSE || b.state == Building.State.DEMOLISHING && b.finished) continue;
            BlockPos c = pitCenter(b.origin, b.front);
            int d = radius(3) + 1 + half + gap;
            if (Math.abs(pos.getX() - c.getX()) <= d && Math.abs(pos.getZ() - c.getZ()) <= d) return true;
        }
        return false;
    }

    /**
     * The whole plan of the pit of a miners' house, for its level: layer by layer from the top, each from its edge
     * inwards (where one stands to dig a block: the block before it, outwards, already dug; or the step of the layer
     * above).
     */
    static List<Step> plan(ServerLevel level, Village v, Building house) {
        List<Step> out = new ArrayList<>();
        BlockPos c = pitCenter(house.origin, house.front);
        int top = PlotFinder.floorAt(level, c.getX(), c.getZ()) - 1;
        int r0 = radius(house.level), depth = depth(house.level);
        for (int k = 0; k < depth; k++) {
            int r = Math.max(1, r0 - k), y = top - k;
            for (int ring = r; ring >= 0; ring--) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        BlockPos dig = new BlockPos(c.getX() + dx, y, c.getZ() + dz);
                        // one step outwards: in this layer already dug (stand in it), else the step of the layer above
                        int ox = dx == 0 ? 0 : Integer.signum(dx), oz = dz == 0 ? 0 : Integer.signum(dz);
                        if (ring == 0) ox = 1;
                        BlockPos outward = dig.offset(ox, 0, oz);
                        BlockPos stand = ring < r ? outward : outward.above();
                        out.add(new Step(dig, stand, null));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Where a miner works a dug-out pit: standing on its floor or one of its steps, at the earth and rock of its sides
     * (it stays where it is: the pit is not dug any further, what is chipped off comes in by the day).
     */
    static List<BlockPos[]> faces(ServerLevel level, List<Step> plan) {
        List<BlockPos[]> out = new ArrayList<>();
        java.util.Set<BlockPos> open = new java.util.HashSet<>();
        for (Step s : plan) open.add(s.dig());
        for (Step s : plan) {
            BlockPos cell = s.dig();
            if (open.contains(cell.below()) || !level.getBlockState(cell).isAir()) continue;
            // a floor cell of the pit: the side beside it
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos wall = cell.relative(d);
                if (open.contains(wall)) continue;
                BlockState st = level.getBlockState(wall);
                if (st.isSolid() && st.getFluidState().isEmpty() && !st.is(Blocks.BEDROCK)) out.add(new BlockPos[]{cell, wall});
            }
        }
        return out;
    }

    /**
     * The pit's edge: a post of the village wood at each top corner, with a lantern (only into empty air, never over
     * anything already there).
     */
    static void entrance(ServerLevel level, Village v, Building house) {
        BlockPos c = pitCenter(house.origin, house.front);
        int r = radius(house.level) + 1;
        var id = net.minecraft.resources.Identifier.withDefaultNamespace(v.wood + "_fence");
        BlockState post = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(id).orElse(Blocks.OAK_FENCE).defaultBlockState();
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int x = c.getX() + sx * r, z = c.getZ() + sz * r;
                int y = PlotFinder.floorAt(level, x, z);
                BlockPos p = new BlockPos(x, y, z);
                // (a post already there: the floor is found on top of it)
                BlockState under = level.getBlockState(p.below());
                if (under.is(Blocks.LANTERN) || under.is(net.minecraft.tags.BlockTags.FENCES)) continue;
                if (!level.getBlockState(p.below()).isSolid() || !level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) continue;
                level.setBlock(p, post, 3);
                level.setBlock(p.above(), Blocks.LANTERN.defaultBlockState(), 3);
            }
        }
    }

    /** Is there anything to dig here (not air, not bedrock, not a liquid)? */
    static boolean diggable(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return !s.isAir() && s.getFluidState().isEmpty() && !s.is(Blocks.BEDROCK) && s.getDestroySpeed(level, p) >= 0;
    }

    /** Water or lava next to a block about to be dug is walled off first with cobblestone. */
    static void seal(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            if (!level.getBlockState(n).getFluidState().isEmpty()) level.setBlock(n, Blocks.COBBLESTONE.defaultBlockState(), 3);
        }
    }

    /** A torch on the wall on this side of a tunnel block, at head height. */
    static void torch(ServerLevel level, BlockPos floor, Direction wall) {
        BlockPos at = floor.above();
        if (!level.getBlockState(at).isAir() || !level.getBlockState(at.relative(wall)).isSolid()) return;
        level.setBlock(at, Blocks.WALL_TORCH.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, wall.getOpposite()), 3);
    }
}
