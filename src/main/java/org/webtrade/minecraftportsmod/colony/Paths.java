package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The paths of a village. A new building's path runs from its door to the nearest path already trodden (or, for the
 * first ones, to the middle), the way people would walk it: round the other buildings, the trees and the water, up
 * and down the land gently. So the paths grow into a network, the way they do in a real village, rather than a star
 * of lines to the square. The ground along a path is smoothed so that it never climbs or drops more than a block at
 * a step: no path that breaks off at a ledge.
 */
final class Paths {

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** How far round the straight line a path may wander. */
    private static final int MARGIN = 14;
    /** Paths are looked for this far from the middle. */
    private static final int REACH = 90;

    private Paths() {
    }

    /** Lays the path of a building that has just been finished. */
    static void lay(ServerLevel level, Village v, Building b) {
        if (b.type.isCenter()) return;
        BlockPos door = b.blueprint(v.wood).workSpot;
        if (!Construction.loaded(level, door)) return;
        // where the path goes: the nearest trodden path, else the edge of the middle
        BlockPos goal = nearestPath(level, v, door, b);
        List<int[]> route = route(level, v, door, goal, b);
        if (route == null) return;
        carve(level, v, route);
    }

    /** The nearest block of path already laid (not this building's own doorstep), or the edge of the middle. */
    private static BlockPos nearestPath(ServerLevel level, Village v, BlockPos door, Building self) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        // the middle: a few steps out from the fire (or the well), on the side facing the door
        double dx = door.getX() - v.center.getX(), dz = door.getZ() - v.center.getZ(), len = Math.max(1, Math.hypot(dx, dz));
        int edge = 4;
        BlockPos mid = new BlockPos(v.center.getX() + (int) Math.round(dx / len * edge), v.center.getY(), v.center.getZ() + (int) Math.round(dz / len * edge));
        bestD = door.distSqr(mid);
        best = mid;
        int r = (int) Math.min(REACH, Math.sqrt(bestD) + 2);
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z > r * r) continue;
                int px = door.getX() + x, pz = door.getZ() + z;
                // the doorstep of the building itself doesn't count
                if (Math.abs(px - door.getX()) <= 1 && Math.abs(pz - door.getZ()) <= 1) continue;
                if (!Construction.loaded(level, new BlockPos(px, 0, pz))) continue;
                int y = PlotFinder.floorAt(level, px, pz) - 1;
                if (!level.getBlockState(new BlockPos(px, y, pz)).is(Blocks.DIRT_PATH)) continue;
                if (inPlot(v, px, pz, 0, self)) continue;
                double d = (double) x * x + (double) z * z;
                if (d < bestD) {
                    bestD = d;
                    best = new BlockPos(px, y + 1, pz);
                }
            }
        }
        return best;
    }

    private static boolean inPlot(Village v, int x, int z, int margin, Building except) {
        for (Building b : v.buildings) {
            if (b == except || b.state == Building.State.DEMOLISHING && b.finished) continue;
            int h = b.type.half + margin;
            if (Math.abs(x - b.origin.getX()) <= h && Math.abs(z - b.origin.getZ()) <= h) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ finding the way

    private record Cell(int x, int z, double f) {
    }

    /**
     * The way from the door to the goal over the land, cell by cell (x, z, the ground's height): round plots, trees
     * and water; along paths already there when it can; steep ground only if there is no other way.
     */
    private static List<int[]> route(ServerLevel level, Village v, BlockPos from, BlockPos to, Building self) {
        int x0 = Math.min(from.getX(), to.getX()) - MARGIN, z0 = Math.min(from.getZ(), to.getZ()) - MARGIN;
        int w = Math.abs(from.getX() - to.getX()) + 2 * MARGIN + 1, h = Math.abs(from.getZ() - to.getZ()) + 2 * MARGIN + 1;
        int n = w * h;
        int[] ground = new int[n];
        byte[] kind = new byte[n];   // 0 free, 1 path, 2 blocked, 3 water (a bridge)
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int x = x0 + i, z = z0 + j, k = i * h + j;
                if (!Construction.loaded(level, new BlockPos(x, 0, z))) {
                    kind[k] = 2;
                    continue;
                }
                int y = PlotFinder.floorAt(level, x, z) - 1;
                ground[k] = y;
                BlockState top = level.getBlockState(new BlockPos(x, y, z));
                BlockState up = level.getBlockState(new BlockPos(x, y + 1, z));
                boolean doorstep = Math.abs(x - from.getX()) <= 1 && Math.abs(z - from.getZ()) <= 1;
                if (!top.getFluidState().isEmpty() || !up.getFluidState().isEmpty()) {
                    // water: a bridge's deck, a block above the water's surface
                    int surf = y;
                    while (!level.getBlockState(new BlockPos(x, surf + 1, z)).getFluidState().isEmpty()) surf++;
                    ground[k] = surf + 1;
                    kind[k] = inPlot(v, x, z, 0, null) ? 2 : (byte) 3;
                    continue;
                }
                if (up.is(BlockTags.LOGS)
                        || !doorstep && inPlot(v, x, z, 0, null)
                        || Math.abs(x - v.board.getX()) <= 0 && Math.abs(z - v.board.getZ()) <= 0) {
                    kind[k] = 2;
                } else if (top.is(Blocks.DIRT_PATH)) {
                    kind[k] = 1;
                }
            }
        }
        int start = (from.getX() - x0) * h + (from.getZ() - z0), goal = (to.getX() - x0) * h + (to.getZ() - z0);
        if (kind[start] == 2) kind[start] = 0;
        if (kind[goal] == 2) kind[goal] = 0;
        double[] cost = new double[n];
        java.util.Arrays.fill(cost, Double.MAX_VALUE);
        int[] prev = new int[n];
        java.util.Arrays.fill(prev, -1);
        cost[start] = 0;
        PriorityQueue<Cell> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        open.add(new Cell(start / h, start % h, 0));
        int gx = goal / h, gz = goal % h;
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int visited = 0;
        while (!open.isEmpty() && visited++ < 40000) {
            Cell c = open.poll();
            int k = c.x * h + c.z;
            if (k == goal) break;
            if (c.f > cost[k] + Math.abs(c.x - gx) + Math.abs(c.z - gz) + 1e-6) continue;
            for (int[] s : steps) {
                int nx = c.x + s[0], nz = c.z + s[1];
                if (nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
                int m = nx * h + nz;
                if (kind[m] == 2) continue;
                int dy = Math.abs(ground[m] - ground[k]);
                // along a path already there is easiest; steep ground is hard (and will be dug or built up); over water
                // only if there is no way round (a bridge is a lot of work)
                double step = (kind[m] == 1 ? 0.35 : kind[m] == 3 ? 6.0 : 1.0) + (dy == 0 ? 0 : dy == 1 ? 0.6 : dy * 4.0);
                double g = cost[k] + step;
                if (g < cost[m]) {
                    cost[m] = g;
                    prev[m] = k;
                    open.add(new Cell(nx, nz, g + Math.abs(nx - gx) + Math.abs(nz - gz)));
                }
            }
        }
        if (prev[goal] < 0 && goal != start) return null;
        List<int[]> out = new ArrayList<>();
        for (int k = goal; k >= 0; k = prev[k]) {
            out.add(new int[]{x0 + k / h, z0 + k % h, ground[k], kind[k] == 3 ? 1 : 0});
            if (k == start) break;
        }
        Collections.reverse(out);
        return out;
    }

    // ------------------------------------------------------------------ laying it

    /**
     * Lays the path along the way found: the heights smoothed so that no step is more than a block (dug down or
     * built up where the land is steeper), trodden earth on top, a clear way above.
     */
    private static void carve(ServerLevel level, Village v, List<int[]> route) {
        int n = route.size();
        int[] y = new int[n];
        for (int i = 0; i < n; i++) y[i] = route.get(i)[2];
        // no step higher than one block, going either way
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 1; i < n; i++) y[i] = Math.max(y[i - 1] - 1, Math.min(y[i - 1] + 1, y[i]));
            for (int i = n - 2; i >= 0; i--) y[i] = Math.max(y[i + 1] - 1, Math.min(y[i + 1] + 1, y[i]));
        }
        // a bridge's deck is never lower than a block above its water
        for (int i = 0; i < n; i++) if (route.get(i)[3] == 1) y[i] = Math.max(y[i], route.get(i)[2]);
        // two blocks wide: beside each step, the column to its right too (where that is free: no building's plot, the
        // ground there not much higher or lower); over water, the deck two planks wide
        List<int[]> cells = new java.util.ArrayList<>();
        java.util.Set<Long> way = new java.util.HashSet<>();
        for (int[] c : route) way.add(Blueprint.key(c[0], c[1]));
        for (int i = 0; i < n; i++) {
            int[] c = route.get(i);
            cells.add(new int[]{c[0], c[1], c[2], y[i], c[3], i});
            int[] prev = route.get(Math.max(0, i - 1)), next = route.get(Math.min(n - 1, i + 1));
            int dx = Integer.signum(next[0] - prev[0]), dz = Integer.signum(next[1] - prev[1]);
            if (dx == 0 && dz == 0) continue;
            // (to the right of the way it goes; along a diagonal, the one column that closes the gap)
            int sx = c[0] - dz, sz = c[1] + dx;
            if (dx != 0 && dz != 0) {
                sx = c[0] - dz;
                sz = c[1];
            }
            long k = Blueprint.key(sx, sz);
            if (way.contains(k) || inPlot(v, sx, sz, 0, null) || !Construction.loaded(level, new BlockPos(sx, 0, sz))) continue;
            int g = PlotFinder.floorAt(level, sx, sz) - 1;
            if (c[3] != 1 && Math.abs(g - y[i]) > 2) continue;
            way.add(k);
            cells.add(new int[]{sx, sz, g, y[i], c[3], -1});
        }
        Map<Long, Boolean> done = new HashMap<>();
        BlockState deck = wood(v, "planks"), rail = wood(v, "fence");
        for (int[] cell : cells) {
            int x = cell[0], z = cell[1], ground = cell[2], top = cell[3];
            int i = cell[5];
            if (done.put(Blueprint.key(x, z), true) != null) continue;
            BlockPos p = new BlockPos(x, top, z);
            if (cell[4] == 1) {
                // a bridge: planks over the water (at least a block above it), rails where the water is beside it
                top = Math.max(top, ground);
                p = new BlockPos(x, top, z);
                level.setBlock(p, deck, FLAGS);
                for (int k = 1; k <= 2; k++) if (!level.getBlockState(p.above(k)).isAir()) level.setBlock(p.above(k), Blocks.AIR.defaultBlockState(), FLAGS);
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos side = p.relative(d);
                    if (way.contains(Blueprint.key(side.getX(), side.getZ()))) continue;
                    boolean water = !level.getBlockState(side).getFluidState().isEmpty() || !level.getBlockState(side.below()).getFluidState().isEmpty();
                    if (water && level.getBlockState(side).canBeReplaced()) level.setBlock(side, rail, FLAGS | Block.UPDATE_NEIGHBORS);
                }
                continue;
            }
            // built up: earth under it
            for (int k = ground + 1; k < top; k++) level.setBlock(new BlockPos(x, k, z), Blocks.DIRT.defaultBlockState(), FLAGS);
            // dug down: what was above goes
            for (int k = top + 1; k <= ground; k++) level.setBlock(new BlockPos(x, k, z), Blocks.AIR.defaultBlockState(), FLAGS);
            BlockState at = level.getBlockState(p);
            if (!at.is(Blocks.DIRT_PATH)) {
                if (at.isAir()) level.setBlock(p.below(), Blocks.DIRT.defaultBlockState(), FLAGS);
                level.setBlock(p, Blocks.DIRT_PATH.defaultBlockState(), FLAGS);
            }
            // a clear way above: grass, flowers and snow trodden down (a sapling, a post or a fence stays)
            for (int k = 1; k <= 2; k++) {
                BlockPos a2 = p.above(k);
                BlockState st = level.getBlockState(a2);
                if (st.isAir()) continue;
                boolean keep = st.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock || !st.canBeReplaced() && !st.is(BlockTags.LEAVES);
                if (!keep) level.setBlock(a2, Blocks.AIR.defaultBlockState(), FLAGS);
            }
            // the ground beside a dug path: no ledge higher than a block along it
            if (top < ground) {
                // (three columns out: no trench walls along the way)
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    for (int k = 1; k <= 3; k++) {
                        if (way.contains(Blueprint.key(x + d[0] * k, z + d[1] * k))) break;
                        Construction.slope(level, v, x + d[0] * k, z + d[1] * k, top, k);
                    }
                }
            }
            // a lamp by the way now and then (if there is coal for it)
            if (i > 2 && i % LAMP_EVERY == 0 && i < n - 2) lamp(level, v, route, i, way, y[i]);
        }
    }

    /** A path gets a lamp this often (in steps). */
    private static final int LAMP_EVERY = 12;

    /** A lamp post beside the path at step {@code i}: a fence post with a lantern, a coal's worth of light. */
    private static void lamp(ServerLevel level, Village v, List<int[]> route, int i, java.util.Set<Long> way, int y) {
        if (v.stock(Res.COAL) < 1) return;
        int[] a = route.get(i - 1), b = route.get(i + 1);
        int dx = Integer.signum(b[0] - a[0]), dz = Integer.signum(b[1] - a[1]);
        // beside the way: to its left, else its right
        for (int side : new int[]{1, -1}) {
            int x = route.get(i)[0] - dz * side, z = route.get(i)[1] + dx * side;
            if (way.contains(Blueprint.key(x, z)) || inPlot(v, x, z, 0, null)) continue;
            int g = PlotFinder.floorAt(level, x, z);
            if (Math.abs(g - 1 - y) > 1) continue;
            BlockPos at = new BlockPos(x, g, z);
            BlockState ground = level.getBlockState(at.below());
            if (!ground.getFluidState().isEmpty() || !Construction.natural(ground) && !ground.is(Blocks.DIRT_PATH)) continue;
            if (!level.getBlockState(at).canBeReplaced() || !level.getBlockState(at.above()).canBeReplaced()) continue;
            level.setBlock(at, wood(v, "fence"), FLAGS);
            level.setBlock(at.above(), Blocks.LANTERN.defaultBlockState(), FLAGS);
            v.add(Res.COAL, -1);
            return;
        }
    }

    private static BlockState wood(Village v, String part) {
        var b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.withDefaultNamespace(v.wood + "_" + part));
        return (b.isPresent() ? b.get() : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                net.minecraft.resources.Identifier.withDefaultNamespace("oak_" + part))).defaultBlockState();
    }
}
