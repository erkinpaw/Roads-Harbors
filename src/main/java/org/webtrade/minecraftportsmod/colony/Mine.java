package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The village mine, dug by the miners from behind their house: a stair going down away from the village, then a
 * main tunnel with side tunnels off it every few blocks (branch mining), lit with torches. The plan is fixed by the
 * miners' house; how far along it the miners are is kept with the village.
 */
public final class Mine {

    /** One block to dig, where to stand for it, and whether a torch goes up once it is dug. */
    public record Step(BlockPos dig, BlockPos stand, Direction torchWall) {
    }

    /** How deep the stair goes, and how long the tunnel is: a small mine, the miners work its walls. */
    static final int DEPTH = 4, TUNNEL = 8;

    private Mine() {
    }

    /** The miners' house the mine starts at, or null. */
    static Building house(Village v) {
        for (Building b : v.buildings) if (b.type.branch == BuildingType.Branch.MINE && b.standing()) return b;
        return null;
    }

    /** The whole plan of the mine of a miners' house: a short stair down, a tunnel, a chamber at its end. */
    static List<Step> plan(ServerLevel level, Village v, Building house) {
        List<Step> out = new ArrayList<>();
        Blueprint bp = house.blueprint(v.wood);
        Direction out_ = house.front.getOpposite();          // away from the village
        Direction left = out_.getCounterClockWise(), right = out_.getClockWise();
        BlockPos start = bp.frame.at(0, 0, -(house.type.half + 1));
        int y0 = PlotFinder.floorAt(level, start.getX(), start.getZ());
        BlockPos entrance = new BlockPos(start.getX(), y0, start.getZ());
        // the stair: each step one forward and one down, three blocks of headroom
        BlockPos stand = entrance;
        for (int i = 1; i <= DEPTH; i++) {
            BlockPos floor = entrance.relative(out_, i).below(i);
            for (int h = 2; h >= 0; h--) out.add(new Step(floor.above(h), stand, null));
            stand = floor;
        }
        // the tunnel, two high, a torch halfway; at its end a chamber three wide and three high
        BlockPos bottom = stand;
        for (int k = 1; k <= TUNNEL; k++) {
            BlockPos floor = bottom.relative(out_, k);
            out.add(new Step(floor.above(), stand, null));
            out.add(new Step(floor, stand, k == TUNNEL / 2 ? left : null));
            if (k > TUNNEL - 3) {
                out.add(new Step(floor.above(2), floor, null));
                for (Direction side : new Direction[]{left, right}) {
                    BlockPos f = floor.relative(side);
                    for (int h = 2; h >= 0; h--) out.add(new Step(f.above(h), floor, k == TUNNEL - 1 && h == 0 ? side : null));
                }
            }
            stand = floor;
        }
        return out;
    }

    /**
     * Where a miner works a dug-out mine: standing on its floor, at the rock of its walls (stone, coal, ore: it
     * stays where it is: the mine is not dug any further, what is chipped off comes in by the day).
     */
    static List<BlockPos[]> faces(ServerLevel level, List<Step> plan) {
        List<BlockPos[]> out = new ArrayList<>();
        java.util.Set<BlockPos> open = new java.util.HashSet<>();
        for (Step s : plan) open.add(s.dig());
        for (Step s : plan) {
            BlockPos floor = s.dig();
            if (!open.contains(floor.above()) || open.contains(floor.below())) continue;
            // a floor cell: the rock round it at head height
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos wall = floor.relative(d).above();
                if (open.contains(wall)) continue;
                BlockState st = level.getBlockState(wall);
                if (DwellerGoals.rock(st) || st.is(Blocks.COAL_ORE) || st.is(Blocks.DEEPSLATE_COAL_ORE)
                        || st.is(Blocks.IRON_ORE) || st.is(Blocks.DEEPSLATE_IRON_ORE)) {
                    out.add(new BlockPos[]{floor, wall});
                }
            }
        }
        return out;
    }

    /**
     * A timber frame over the mouth of the stair: two posts and a beam of the village wood. Only into empty air,
     * never over anything already there.
     */
    static void entrance(ServerLevel level, Village v, Building house) {
        Blueprint bp = house.blueprint(v.wood);
        Direction out_ = house.front.getOpposite();
        Direction left = out_.getCounterClockWise(), right = out_.getClockWise();
        BlockPos start = bp.frame.at(0, 0, -(house.type.half + 1));
        int y0 = PlotFinder.floorAt(level, start.getX(), start.getZ());
        BlockPos mouth = new BlockPos(start.getX(), y0, start.getZ()).relative(out_);
        if (!level.getBlockState(mouth.below()).isAir()) return;        // the stair not dug yet
        var id = net.minecraft.resources.Identifier.withDefaultNamespace(v.wood + "_log");
        BlockState log = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(id).orElse(Blocks.OAK_LOG).defaultBlockState();
        var axis = net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS;
        for (Direction side : new Direction[]{left, right}) {
            BlockPos post = mouth.relative(side);
            if (!level.getBlockState(post.below()).isSolid()) continue;
            for (int h = 0; h < 2; h++) {
                if (level.getBlockState(post.above(h)).isAir()) level.setBlock(post.above(h), log, 3);
            }
        }
        for (int k = -1; k <= 1; k++) {
            BlockPos beam = mouth.relative(right, k).above(2);
            if (level.getBlockState(beam).isAir()) level.setBlock(beam, log.trySetValue(axis, left.getAxis()), 3);
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
