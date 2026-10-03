package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The upkeep of the village's land, done by its people in their free time: holes and cracks filled in, puddles
 * dried, ledges eased, weeds and flowers pulled, leaves left hanging in the air taken down. The village's ground is
 * kept clear and even: the decoration of it is left to come.
 */
public final class Tidy {

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** What needs doing at a spot. */
    public enum Kind {
        HOLE, PUDDLE, LEDGE, WEED, LEAVES, TREE, POST, BUMP, HANGING, DEBRIS, PIT;

        String act() {
            return "tidy_" + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One job of upkeep: what, where (the column's ground, or the block to take away). */
    public record Job(Kind kind, BlockPos pos) {
    }

    /** What each village's people have found to do, and who has taken which. */
    private static final Map<Integer, Deque<Job>> TODO = new HashMap<>();
    private static final Map<Integer, Set<BlockPos>> TAKEN = new HashMap<>();
    /** Spots no one could get to: left alone. */
    private static final Map<Integer, Set<BlockPos>> NEVER = new HashMap<>();
    /** How many jobs a village's list holds. */
    private static final int LIST = 160;

    private Tidy() {
    }

    static void clear() {
        OWED.clear();
        TODO.clear();
        TAKEN.clear();
        NEVER.clear();
    }

    /** The village's own land (see {@link Territory}). */
    static boolean territory(Village v, int x, int z) {
        return Territory.contains(v, x, z);
    }

    private static boolean inPlot(Village v, int x, int z) {
        // (a player's plot is the player's: nothing cleared there)
        if (Plots.inside(v, x, z, 0)) return true;
        for (Building b : v.buildings) {
            int h = b.type.half;
            if (Math.abs(x - b.origin.getX()) <= h && Math.abs(z - b.origin.getZ()) <= h) return true;
        }
        return Math.abs(x - v.board.getX()) <= 1 && Math.abs(z - v.board.getZ()) <= 1;
    }

    /** A weed, a flower, a bush: something growing wild on the village's ground (not a sapling, not a crop). */
    static boolean weed(BlockState s) {
        if (s.isAir() || s.getBlock() instanceof SaplingBlock || s.getBlock() instanceof CropBlock) return false;
        return s.is(BlockTags.FLOWERS) || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN)
                || s.is(Blocks.DEAD_BUSH) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.BUSH) || s.is(Blocks.SHORT_DRY_GRASS) || s.is(Blocks.TALL_DRY_GRASS)
                || s.is(Blocks.VINE) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON)
                || s.getBlock() instanceof BushBlock && s.canBeReplaced();
    }

    // ------------------------------------------------------------------ looking for work

    /** A look round the village's land: a few columns each time, what needs doing added to the list. */
    static void survey(ServerLevel level, Village v, RandomSource rnd, int tries) {
        Deque<Job> todo = TODO.computeIfAbsent(v.id, k -> new ArrayDeque<>());
        if (todo.size() >= LIST) return;
        Set<BlockPos> listed = new HashSet<>(NEVER.getOrDefault(v.id, Set.of()));
        for (Job j : todo) listed.add(j.pos());
        int reach = 16;
        for (Building b : v.buildings) {
            reach = Math.max(reach, Math.max(Math.abs(b.origin.getX() - v.center.getX()), Math.abs(b.origin.getZ() - v.center.getZ())) + b.type.half + 8);
        }
        reach = Math.min(reach, 90);
        for (int i = 0; i < tries && todo.size() < LIST; i++) {
            int x = v.center.getX() + rnd.nextInt(2 * reach + 1) - reach, z = v.center.getZ() + rnd.nextInt(2 * reach + 1) - reach;
            if (!territory(v, x, z) || inPlot(v, x, z) || !Construction.loaded(level, new BlockPos(x, 0, z))) continue;
            Job j = look(level, v, x, z);
            if (j != null && listed.add(j.pos())) todo.add(j);
        }
    }

    /** What (if anything) needs doing in this column. */
    private static Job look(ServerLevel level, Village v, int x, int z) {
        int ground = PlotFinder.floorAt(level, x, z) - 1;
        BlockPos g = new BlockPos(x, ground, z);
        BlockState top = level.getBlockState(g), above = level.getBlockState(g.above());
        // leaves left hanging in the air, with no trunk near them
        int leafTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        if (leafTop > ground + 1) {
            BlockPos l = new BlockPos(x, leafTop, z);
            if (level.getBlockState(l).is(BlockTags.LEAVES) && !trunkNear(level, l)) return new Job(Kind.LEAVES, l);
        }
        // up the column: what the wild left in the air (vines, cocoa pods, a branch of a felled tree)
        for (int y = ground + 1; y <= ground + 28; y++) {
            BlockPos q = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(q);
            if (s.isAir()) continue;
            if (WorkGoal.hangs(s)) return new Job(Kind.HANGING, q);
            if (s.is(BlockTags.LOGS) && y > ground + 1 && !level.getBlockState(q.below()).is(BlockTags.LOGS) && !nearBuilding(v, x, z, 1)) {
                return new Job(Kind.DEBRIS, q);
            }
        }
        // a puddle: a little water lying on the land (not the sea, not the river)
        if (!above.getFluidState().isEmpty() || !top.getFluidState().isEmpty()) {
            BlockPos w = !above.getFluidState().isEmpty() ? g.above() : g;
            return smallWater(level, w) ? new Job(Kind.PUDDLE, w) : null;
        }
        if (weed(above)) return new Job(Kind.WEED, g.above());
        // a tree on the village's land (the woodcutters' grove aside): felled; a stump or a lone post: taken away
        BlockPos up = g.above();
        BlockState stand = level.getBlockState(up);
        if (stand.is(BlockTags.LOGS) && !grove(v, x, z)) {
            boolean crown = false;
            for (int k = 1; k <= 12 && !crown; k++) crown = level.getBlockState(up.above(k)).is(BlockTags.LEAVES);
            return new Job(crown ? Kind.TREE : Kind.POST, up);
        }
        if (stand.is(BlockTags.FENCES) && !level.getBlockState(up.above()).is(BlockTags.FENCES) && level.getBlockState(up.above()).isAir()
                && !nearBuilding(v, x, z, 1)) return new Job(Kind.POST, up);
        // a hole or a crack: lower than its neighbours all round by two or more
        int[] n = new int[4];
        int k = 0, lowest = Integer.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            n[k] = PlotFinder.floorAt(level, x + d.getStepX(), z + d.getStepZ()) - 1;
            lowest = Math.min(lowest, n[k]);
            k++;
        }
        if (lowest - ground >= 1 && Construction.natural(top)) return new Job(Kind.HOLE, g);
        // a pit: lower by two or more than the land round it (the middle of the heights a few blocks out)
        if (Construction.natural(top) && !top.is(Blocks.DIRT_PATH) && !top.is(Blocks.FARMLAND) && !nearBuilding(v, x, z, 1)) {
            int around = around(level, v, x, z);
            if (around != Integer.MIN_VALUE && around - ground >= 2) return new Job(Kind.PIT, g);
        }
        // a bump: one column standing up over all of its neighbours
        int highestN = Integer.MIN_VALUE;
        for (int h : n) highestN = Math.max(highestN, h);
        if (ground - highestN >= 1 && Construction.natural(top) && !top.is(Blocks.DIRT_PATH) && (above.isAir() || above.canBeReplaced())) {
            return new Job(Kind.BUMP, g);
        }
        // a ledge by the buildings and paths: two to five straight down to a neighbour (a higher cliff is the land's own)
        int drop = ground - lowest;
        if (drop >= 2 && drop <= 5 && Construction.natural(top) && !top.is(Blocks.DIRT_PATH) && (above.isAir() || above.canBeReplaced())
                && nearWay(level, v, x, z)) {
            return new Job(Kind.LEDGE, g);
        }
        return null;
    }

    /**
     * The lie of the land round a column: the middle of the ground heights on a ring three blocks out (dry land
     * only, plots aside); Integer.MIN_VALUE if there is too little of it to tell (the shore, the village's middle).
     */
    private static int around(ServerLevel level, Village v, int x, int z) {
        List<Integer> hs = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 3) continue;
                int px = x + dx, pz = z + dz;
                if (inPlot(v, px, pz) || !Construction.loaded(level, new BlockPos(px, 0, pz))) continue;
                int f = PlotFinder.floorAt(level, px, pz) - 1;
                BlockState st = level.getBlockState(new BlockPos(px, f, pz));
                if (!st.getFluidState().isEmpty()) return Integer.MIN_VALUE;
                hs.add(f);
            }
        }
        if (hs.size() < 16) return Integer.MIN_VALUE;
        hs.sort(Integer::compare);
        return hs.get(hs.size() / 2);
    }

    /** The woodcutters' grove: round their hut, out past its yard. */
    private static boolean grove(Village v, int x, int z) {
        for (Building b : v.buildings) {
            if (b.type != BuildingType.WOOD_HUT) continue;
            int h = b.type.half + 14;
            if (Math.abs(x - b.origin.getX()) <= h && Math.abs(z - b.origin.getZ()) <= h) return true;
        }
        return false;
    }

    private static boolean nearBuilding(Village v, int x, int z, int margin) {
        for (Building b : v.buildings) {
            int h = b.type.half + margin;
            if (Math.abs(x - b.origin.getX()) <= h && Math.abs(z - b.origin.getZ()) <= h) return true;
        }
        return false;
    }

    private static boolean trunkNear(ServerLevel level, BlockPos p) {
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-4, -5, -4), p.offset(4, 3, 4))) {
            if (level.getBlockState(q).is(BlockTags.LOGS)) return true;
        }
        return false;
    }

    /** Is this water a puddle: fewer than 24 blocks of it round here, and none of it deep? */
    private static boolean smallWater(ServerLevel level, BlockPos start) {
        Deque<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        open.add(start);
        seen.add(start);
        while (!open.isEmpty()) {
            BlockPos p = open.poll();
            if (seen.size() > 24) return false;
            if (!level.getBlockState(p.below()).getFluidState().isEmpty() && !level.getBlockState(p.below(2)).getFluidState().isEmpty()) return false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos q = p.relative(d);
                if (seen.contains(q) || level.getBlockState(q).getFluidState().isEmpty()) continue;
                seen.add(q);
                open.add(q);
            }
        }
        return true;
    }

    /** Close to a building's plot or on a path's way: where a ledge is in the way of people. */
    private static boolean nearWay(ServerLevel level, Village v, int x, int z) {
        for (Building b : v.buildings) {
            int h = b.type.half + 3;
            if (Math.abs(x - b.origin.getX()) <= h && Math.abs(z - b.origin.getZ()) <= h) return true;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int px = x + dx, pz = z + dz;
                if (level.getBlockState(new BlockPos(px, PlotFinder.floorAt(level, px, pz) - 1, pz)).is(Blocks.DIRT_PATH)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the work of time no one saw

    /** Jobs of upkeep a grown-up gets through in a day's spare time. */
    static final int PER_DAY = 6;
    /** The upkeep owed by each village for days that went by unwatched (or were skipped). */
    private static final Map<Integer, Integer> OWED = new HashMap<>();

    /** A day went by with no one there to see the village's people at work on its land. */
    static void owe(Village v, int adults) {
        OWED.merge(v.id, adults * PER_DAY, (a, b) -> Math.min(a + b, 2000));
    }

    /**
     * The work of the days no one saw, done now that the land is loaded: what the people would have got through, in
     * the order they would have (the nearest to the middle first). A batch at a time.
     */
    static void catchUp(ServerLevel level, Village v, RandomSource rnd) {
        int owed = OWED.getOrDefault(v.id, 0);
        if (owed <= 0) return;
        survey(level, v, rnd, 600);
        int done = 0;
        for (int i = 0; i < 40 && owed > 0; i++) {
            Job j = take(level, v, v.center);
            if (j == null) break;
            finish(level, v, j);
            owed--;
            done++;
        }
        // (nothing left to do: the rest of the time went on nothing)
        OWED.put(v.id, done == 0 ? 0 : owed);
    }

    // ------------------------------------------------------------------ doing it

    /** A job for someone (the nearest to them of what is listed), taken so that no one else goes to it. */
    static Job take(ServerLevel level, Village v, BlockPos near) {
        Deque<Job> todo = TODO.get(v.id);
        if (todo == null || todo.isEmpty()) return null;
        Set<BlockPos> taken = TAKEN.computeIfAbsent(v.id, k -> new HashSet<>());
        Job best = null;
        double bestD = Double.MAX_VALUE;
        for (Job j : todo) {
            if (taken.contains(j.pos())) continue;
            double d = j.pos().distSqr(near);
            if (d < bestD) {
                bestD = d;
                best = j;
            }
        }
        if (best == null) return null;
        // (across the water, up a cliff: not for today)
        if (!Reach.ok(level, v, best.pos())) {
            drop(v, best);
            return null;
        }
        taken.add(best.pos());
        return best;
    }

    static boolean any(Village v) {
        Deque<Job> todo = TODO.get(v.id);
        if (todo == null) return false;
        Set<BlockPos> taken = TAKEN.getOrDefault(v.id, Set.of());
        for (Job j : todo) if (!taken.contains(j.pos())) return true;
        return false;
    }

    /** Couldn't be got to: off the list, and not looked at again. */
    static void drop(Village v, Job j) {
        Deque<Job> todo = TODO.get(v.id);
        if (todo != null) todo.remove(j);
        release(v, j);
        Set<BlockPos> never = NEVER.computeIfAbsent(v.id, k -> new HashSet<>());
        if (never.size() > 400) never.clear();
        never.add(j.pos());
    }

    static void release(Village v, Job j) {
        Set<BlockPos> taken = TAKEN.get(v.id);
        if (taken != null) taken.remove(j.pos());
    }

    /** Done: the job is off the list, and the land put right. */
    static void finish(ServerLevel level, Village v, Job j) {
        Deque<Job> todo = TODO.get(v.id);
        if (todo != null) todo.remove(j);
        release(v, j);
        BlockPos p = j.pos();
        switch (j.kind()) {
            case WEED -> {
                // the patch round it too
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int x = p.getX() + dx, z = p.getZ() + dz;
                        if (inPlot(v, x, z)) continue;
                        BlockPos q = new BlockPos(x, PlotFinder.floorAt(level, x, z), z);
                        if (Math.abs(q.getY() - p.getY()) > 2 || !weed(level.getBlockState(q))) continue;
                        // (tall ones: the top half too)
                        if (weed(level.getBlockState(q.above()))) level.setBlock(q.above(), Blocks.AIR.defaultBlockState(), FLAGS);
                        level.destroyBlock(q, false);
                    }
                }
            }
            case LEAVES -> {
                // the whole loose clump
                List<BlockPos> clump = new ArrayList<>();
                for (BlockPos q : BlockPos.betweenClosed(p.offset(-3, -3, -3), p.offset(3, 3, 3))) {
                    if (level.getBlockState(q).is(BlockTags.LEAVES) && !trunkNear(level, q)) clump.add(q.immutable());
                }
                for (BlockPos q : clump) level.setBlock(q, Blocks.AIR.defaultBlockState(), FLAGS);
            }
            case TREE -> {
                // felled whole, crown and all; the logs go to the store
                BlockPos foot = p;
                while (level.getBlockState(foot.below()).is(BlockTags.LOGS)) foot = foot.below();
                List<BlockPos> tree = WorkGoal.tree(level, foot, null);
                int logs = 0;
                if (tree != null) {
                    for (BlockPos t : tree) {
                        if (level.getBlockState(t).is(BlockTags.LOGS)) logs++;
                        level.setBlock(t, Blocks.AIR.defaultBlockState(), FLAGS);
                    }
                }
                // (and what hangs round it: loose leaves, vines)
                for (BlockPos q : BlockPos.betweenClosed(foot.offset(-4, 0, -4), foot.offset(4, 16, 4))) {
                    BlockState s = level.getBlockState(q);
                    if (s.is(Blocks.VINE) || s.is(BlockTags.LEAVES) && !trunkNear(level, q)) level.setBlock(q, Blocks.AIR.defaultBlockState(), FLAGS);
                }
                if (logs > 0) {
                    v.add(Res.WOOD, logs);
                    v.made.merge(Res.WOOD, logs, Integer::sum);
                }
            }
            case HANGING -> {
                // the whole of it: the vine down to its end and along, the pods beside it
                Deque<BlockPos> open = new ArrayDeque<>();
                Set<BlockPos> seen = new HashSet<>();
                open.add(p);
                seen.add(p);
                while (!open.isEmpty() && seen.size() < 200) {
                    BlockPos q = open.poll();
                    if (!WorkGoal.hangs(level.getBlockState(q))) continue;
                    level.setBlock(q, Blocks.AIR.defaultBlockState(), FLAGS);
                    for (Direction d : Direction.values()) {
                        BlockPos n = q.relative(d);
                        if (seen.add(n) && WorkGoal.hangs(level.getBlockState(n))) open.add(n);
                    }
                }
            }
            case DEBRIS -> {
                // a branch left in the air: its logs (to the store), its leaves and what hangs on them
                Deque<BlockPos> open = new ArrayDeque<>();
                Set<BlockPos> seen = new HashSet<>();
                open.add(p);
                seen.add(p);
                int logs = 0;
                while (!open.isEmpty() && seen.size() < 400) {
                    BlockPos q = open.poll();
                    BlockState s = level.getBlockState(q);
                    boolean log = s.is(BlockTags.LOGS);
                    if (!log && !s.is(BlockTags.LEAVES) && !WorkGoal.hangs(s)) continue;
                    if (inPlot(v, q.getX(), q.getZ())) continue;
                    if (log) logs++;
                    level.setBlock(q, Blocks.AIR.defaultBlockState(), FLAGS);
                    for (BlockPos n : BlockPos.betweenClosed(q.offset(-1, -1, -1), q.offset(1, 1, 1))) {
                        if (seen.add(n.immutable())) open.add(n.immutable());
                    }
                }
                if (logs > 0) {
                    v.add(Res.WOOD, logs);
                    v.made.merge(Res.WOOD, logs, Integer::sum);
                }
            }
            case PIT -> {
                // earth heaped up a block or two at a time, up to the land round it
                int to = around(level, v, p.getX(), p.getZ());
                if (to == Integer.MIN_VALUE) break;
                to = Math.min(to, p.getY() + 2);
                for (int y = p.getY(); y < to; y++) {
                    BlockPos q = new BlockPos(p.getX(), y, p.getZ());
                    BlockState s = level.getBlockState(q.above());
                    if (!s.isAir() && !s.canBeReplaced()) break;
                    level.setBlock(q, Blocks.DIRT.defaultBlockState(), FLAGS);
                    level.setBlock(q.above(), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
                }
            }
            case POST -> {
                // a stump, a lone post: the column of it taken away
                BlockPos q = p;
                while (level.getBlockState(q).is(BlockTags.LOGS) || level.getBlockState(q).is(BlockTags.FENCES)) {
                    level.setBlock(q, Blocks.AIR.defaultBlockState(), FLAGS);
                    q = q.above();
                }
            }
            case BUMP -> {
                // cut down level with the highest of its neighbours
                int highest = Integer.MIN_VALUE;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    highest = Math.max(highest, PlotFinder.floorAt(level, p.getX() + d.getStepX(), p.getZ() + d.getStepZ()) - 1);
                }
                BlockState over = level.getBlockState(p.above());
                if (!over.isAir() && over.canBeReplaced()) level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), FLAGS);
                for (int y = p.getY(); y > highest; y--) {
                    BlockPos q = new BlockPos(p.getX(), y, p.getZ());
                    if (!Construction.natural(level.getBlockState(q))) break;
                    level.setBlock(q, Blocks.AIR.defaultBlockState(), FLAGS);
                }
                BlockPos nt = new BlockPos(p.getX(), highest, p.getZ());
                BlockState s = level.getBlockState(nt);
                if (s.is(Blocks.DIRT) || s.is(Blocks.STONE) || s.is(Blocks.GRAVEL)) level.setBlock(nt, Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
            }
            case PUDDLE -> {
                // the puddle filled with earth, level with the land round it
                Deque<BlockPos> open = new ArrayDeque<>();
                Set<BlockPos> seen = new HashSet<>();
                open.add(p);
                seen.add(p);
                while (!open.isEmpty() && seen.size() <= 24) {
                    BlockPos q = open.poll();
                    level.setBlock(q, Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
                    if (!level.getBlockState(q.below()).getFluidState().isEmpty()) level.setBlock(q.below(), Blocks.DIRT.defaultBlockState(), FLAGS);
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos r = q.relative(d);
                        if (seen.contains(r) || level.getBlockState(r).getFluidState().isEmpty()) continue;
                        seen.add(r);
                        open.add(r);
                    }
                }
            }
            case LEDGE -> {
                // cut down to a step above the lowest neighbour
                int lowest = Integer.MAX_VALUE;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    lowest = Math.min(lowest, PlotFinder.floorAt(level, p.getX() + d.getStepX(), p.getZ() + d.getStepZ()) - 1);
                }
                int to = lowest + 1;
                if (to >= p.getY() || !Construction.natural(level.getBlockState(p))) break;
                BlockState over = level.getBlockState(p.above());
                if (!over.isAir()) {
                    if (!over.canBeReplaced()) break;
                    level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), FLAGS);
                }
                for (int y = p.getY(); y > to; y--) {
                    BlockPos q = new BlockPos(p.getX(), y, p.getZ());
                    if (!Construction.natural(level.getBlockState(q))) break;
                    level.setBlock(q, Blocks.AIR.defaultBlockState(), FLAGS);
                }
                BlockPos nt = new BlockPos(p.getX(), to, p.getZ());
                BlockState s = level.getBlockState(nt);
                if (s.is(Blocks.DIRT) || s.is(Blocks.STONE) || s.is(Blocks.GRAVEL)) level.setBlock(nt, Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
            }
            case HOLE -> {
                // earth heaped up to a block below the lowest neighbour (a hole) or the highest one (a ledge)
                int target = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    int n = PlotFinder.floorAt(level, p.getX() + d.getStepX(), p.getZ() + d.getStepZ()) - 1;
                    target = Math.min(target, n);
                    highest = Math.max(highest, n);
                }
                int to = target;
                if (to - p.getY() > 4) to = p.getY() + 4;
                for (int y = p.getY(); y < to; y++) {
                    BlockPos q = new BlockPos(p.getX(), y, p.getZ());
                    BlockState s = level.getBlockState(q.above());
                    if (!s.isAir() && !s.canBeReplaced()) break;
                    if (level.getBlockState(q).is(Blocks.GRASS_BLOCK)) level.setBlock(q, Blocks.DIRT.defaultBlockState(), FLAGS);
                    level.setBlock(q.above(), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
                }
            }
        }
    }
}
