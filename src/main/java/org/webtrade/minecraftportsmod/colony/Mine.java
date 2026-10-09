package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The village mine: an open pit well out of the village, dug by the miners from the day there are any (its place
 * chosen then: the plot of the miners' house to come, the pit behind it); the house is built there later. Dug a layer
 * at a time, each one a block narrower than the one above, so its sides go down in steps; wider and deeper with the
 * house's levels. A stair of cobblestone down one side, ladders up the other, posts at the corners holding up
 * lanterns. Once dug, the miners work its walls. How far along it the miners are is kept with the village.
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

    /**
     * What the pit is dug behind: the miners' house, or (not built yet) the plot kept for it, as a house of the first
     * level standing there; null if neither.
     */
    static Building site(Village v) {
        Building h = house(v);
        if (h != null) return h;
        // (the house being built: the pit goes on behind its site)
        for (Building b : v.buildings) if (b.type == BuildingType.MINE_HOUSE && b.state != Building.State.DEMOLISHING) return b;
        if (v.minePlot == null) return null;
        return new Building(-1, BuildingType.MINE_HOUSE, v.minePlot.origin(), v.minePlot.front(), 0);
    }

    /** The pits there are or will be: behind each miners' house, and behind the plot kept for one. */
    private static List<BlockPos> pits(Village v) {
        List<BlockPos> out = new ArrayList<>();
        for (Building b : v.buildings) {
            if (b.type != BuildingType.MINE_HOUSE || b.state == Building.State.DEMOLISHING && b.finished) continue;
            out.add(pitCenter(b.origin, b.front));
        }
        if (v.minePlot != null) out.add(pitCenter(v.minePlot.origin(), v.minePlot.front()));
        return out;
    }

    /** The middle of the pit of a miners' house standing at a place, facing a way (its back to the pit). */
    public static BlockPos pitCenter(BlockPos origin, Direction front) {
        int r = radius(3), d = BuildingType.MINE_HOUSE.half + 1 + r;
        Direction back = front.getOpposite();
        return origin.relative(back, d);
    }

    /** Does a column fall in the pit (as wide as it will ever be, with a margin) of the village's miners' house? */
    static boolean inPit(Village v, int x, int z, int margin) {
        int r = radius(3) + margin;
        for (BlockPos c : pits(v)) if (Math.abs(x - c.getX()) <= r && Math.abs(z - c.getZ()) <= r) return true;
        return false;
    }

    /** Would a plot (middle, half its side, a gap kept) fall on the pit of the village's miners' house? */
    static boolean pitClash(Village v, BlockPos pos, int half, int gap) {
        int d = radius(3) + 1 + half + gap;
        for (BlockPos c : pits(v)) if (Math.abs(pos.getX() - c.getX()) <= d && Math.abs(pos.getZ() - c.getZ()) <= d) return true;
        // (the plot kept for the miners' house)
        if (v.minePlot != null) {
            int h = BuildingType.MINE_HOUSE.half + half + gap;
            BlockPos o = v.minePlot.origin();
            if (Math.abs(pos.getX() - o.getX()) <= h && Math.abs(pos.getZ() - o.getZ()) <= h) return true;
        }
        return false;
    }

    /** How far a place is from the nearest pit there is or will be (MAX_VALUE: none). */
    static double fromPit(Village v, BlockPos pos) {
        double best = Double.MAX_VALUE;
        for (BlockPos c : pits(v)) best = Math.min(best, Math.hypot(pos.getX() - c.getX(), pos.getZ() - c.getZ()));
        return best;
    }

    /**
     * The whole plan of the pit of a miners' house, for its level: layer by layer from the top, each from its edge
     * inwards (where one stands to dig a block: the block before it, outwards, already dug; or the step of the layer
     * above).
     */
    static List<Step> plan(ServerLevel level, Village v, Building house) {
        List<Step> out = new ArrayList<>();
        BlockPos c = pitCenter(house.origin, house.front);
        // (read once, before any of it is dug: a pit widened later starts from the same land, not from its own floor)
        if (v.pitTop == Integer.MIN_VALUE) v.pitTop = PlotFinder.floorAt(level, c.getX(), c.getZ()) - 1;
        int top = v.pitTop;
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

    /**
     * The fittings of the layers dug out so far: on the side away from the house a stair of cobblestone (each step of
     * the pit's side made a stair block), on the side by the house a ladder up each step, at the corners of every other
     * layer a post of the village wood with a lantern on it. Only into what is open or is the step itself, so the
     * miners' work is never in the way; a pit widened later is fitted again.
     */
    static void furnish(ServerLevel level, Village v, Building house) {
        if (v.pitTop == Integer.MIN_VALUE) return;
        BlockPos c = pitCenter(house.origin, house.front);
        if (!Construction.loaded(level, c)) return;
        int top = v.pitTop;
        int r0 = radius(house.level), depth = depth(house.level);
        Direction away = house.front.getOpposite(), toHouse = house.front;
        var id = net.minecraft.resources.Identifier.withDefaultNamespace(v.wood + "_fence");
        BlockState post = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(id).orElse(Blocks.OAK_FENCE).defaultBlockState();
        for (int k = 1; k < depth; k++) {
            int r = Math.max(1, r0 - k), y = top - k;
            // (the narrowest layers go straight down: no step to fit)
            if (r == Math.max(1, r0 - k + 1)) break;
            // (only a layer dug out, and the one under it too: the miners stand on its edge to dig that one)
            if (!level.getBlockState(new BlockPos(c.getX(), y, c.getZ())).isAir()) break;
            if (k + 1 < depth && !level.getBlockState(new BlockPos(c.getX(), y - 1, c.getZ())).isAir()) break;
            // the stair: the step of the layer above on the far side, a cobblestone stair rising outwards
            BlockPos step = c.relative(away, r + 1).atY(y);
            BlockState st = level.getBlockState(step);
            if (st.isSolid() && !st.is(Blocks.COBBLESTONE_STAIRS) && level.getBlockState(step.above()).isAir()
                    && level.getBlockState(step.relative(toHouse)).isAir()) {
                level.setBlock(step, Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.StairBlock.FACING, away), 3);
            }
            // the ladder: up the step on the house's side
            BlockPos rung = c.relative(toHouse, r).atY(y);
            if (level.getBlockState(rung).isAir() && level.getBlockState(rung.relative(toHouse)).isSolid()) {
                level.setBlock(rung, Blocks.LADDER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LadderBlock.FACING, toHouse.getOpposite()), 3);
            }
            // the posts with their lanterns, at the corners of every other layer
            if (k % 2 == 1 && r >= 2) {
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sz = -1; sz <= 1; sz += 2) {
                        BlockPos p = new BlockPos(c.getX() + sx * r, y, c.getZ() + sz * r);
                        if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir() || !level.getBlockState(p.above(2)).isAir()) continue;
                        if (!level.getBlockState(p.below()).isSolid()) continue;
                        level.setBlock(p, post, 3);
                        level.setBlock(p.above(), Blocks.LANTERN.defaultBlockState(), 3);
                    }
                }
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
