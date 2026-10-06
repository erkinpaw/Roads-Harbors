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

    /**
     * Lays the path of a building that has just been finished: from its door towards the square, the way people
     * would walk there. Where a street already runs that way, the new path joins it (walking an old path is easier
     * than treading a new one); else it makes a street of its own. So the streets leave the square in the directions
     * the village has grown in, and the houses' paths run into them: one network, not a tree grown from the first
     * house. False if no way could be found (tried again later).
     */
    static boolean lay(ServerLevel level, Village v, Building b) {
        if (b.type.isCenter()) return true;
        BlockPos door = doorstep(v, b);
        if (!Construction.loaded(level, door)) return false;
        return layFrom(level, v, door, b.type.id() + " #" + b.id, b);
    }

    /** A path from a door (a building's, a player's plot's) to the square. */
    static boolean layFrom(ServerLevel level, Village v, BlockPos door, String what) {
        return layFrom(level, v, door, what, null);
    }

    private static boolean layFrom(ServerLevel level, Village v, BlockPos door, String what, Building b) {
        BlockPos goal = squareEdge(level, v, door);
        // a short bridge over a stream rather than a long way round it (people would not walk that); never a long one
        List<int[]> route = route(level, v, door, goal, b, BRIDGE_STEP);
        if (route != null && longestWet(route) > PlotFinder.BRIDGE) route = route(level, v, door, goal, b, -1);
        if (route == null) {
            org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[paths] {}: no way from {} at {} to {}", v.name, what,
                    door.toShortString(), goal.toShortString());
            return false;
        }
        List<int[]> way = straighten(level, v, route);
        int wet = 0;
        for (int[] c : way) if (c[3] == 1) wet++;
        org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[paths] {}: {} from {} to {}: {} steps, {} over water", v.name, what,
                door.toShortString(), goal.toShortString(), way.size(), wet);
        carve(level, v, way);
        return true;
    }

    /** What a step over water (a bridge's) costs the way, against 1 for a step on the land. */
    private static final double BRIDGE_STEP = 2.5;

    private static int longestWet(List<int[]> route) {
        int best = 0, run = 0;
        for (int[] c : route) {
            run = c[3] == 1 ? run + 1 : 0;
            best = Math.max(best, run);
        }
        return best;
    }

    /** Where a building's path starts: just outside its plot, in front (whatever work spot it has inside). */
    static BlockPos doorstep(Village v, Building b) {
        return b.blueprint(v.wood).frame.at(0, 0, b.type.half + 1);
    }

    /** The edge of the square on the side facing a point: a step out from the fire, the well or the paving. */
    static BlockPos squareEdge(ServerLevel level, Village v, BlockPos from) {
        Building center = null;
        for (Building b : v.buildings) if (b.type.isCenter()) center = b;
        int edge = (center == null ? 2 : center.type.half) + 2;
        double dx = from.getX() - v.center.getX(), dz = from.getZ() - v.center.getZ(), len = Math.max(1, Math.hypot(dx, dz));
        int x = v.center.getX() + (int) Math.round(dx / len * edge), z = v.center.getZ() + (int) Math.round(dz / len * edge);
        return new BlockPos(x, Construction.loaded(level, new BlockPos(x, 0, z)) ? PlotFinder.floorAt(level, x, z) : v.center.getY(), z);
    }

    /**
     * Does a path lead from the building's door to the square (over paths and bridges, a little way round)? A path
     * cut by some later work is found out this way, and laid again.
     */
    static boolean connected(ServerLevel level, Village v, BlockPos door) {
        Building center = null;
        for (Building b : v.buildings) if (b.type.isCenter()) center = b;
        int reach = (center == null ? 2 : center.type.half) + 3;
        java.util.ArrayDeque<int[]> open = new java.util.ArrayDeque<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = door.getX() + dx, z = door.getZ() + dz;
                if (way(level, x, z) && seen.add(Blueprint.key(x, z))) open.add(new int[]{x, z});
            }
        }
        while (!open.isEmpty() && seen.size() < 6000) {
            int[] c = open.poll();
            if (Math.abs(c[0] - v.center.getX()) <= reach && Math.abs(c[1] - v.center.getZ()) <= reach) return true;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = c[0] + dx, z = c[1] + dz;
                    if (!Construction.loaded(level, new BlockPos(x, 0, z))) return true;
                    if (seen.add(Blueprint.key(x, z)) && way(level, x, z)) open.add(new int[]{x, z});
                }
            }
        }
        return false;
    }

    /** A column one walks along: a trodden path, a bridge's deck, the square's paving. */
    static boolean way(ServerLevel level, int x, int z) {
        BlockState s = level.getBlockState(new BlockPos(x, PlotFinder.floorAt(level, x, z) - 1, z));
        return s.is(Blocks.DIRT_PATH) || s.is(BlockTags.PLANKS) || s.is(Blocks.STONE_BRICKS) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.GRAVEL);
    }

    /** Is there a path at the building's door (its own, or one going by)? */
    static boolean linked(ServerLevel level, Building b, BlockPos door) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = door.getX() + dx, z = door.getZ() + dz;
                BlockState s = level.getBlockState(new BlockPos(x, PlotFinder.floorAt(level, x, z) - 1, z));
                if (s.is(Blocks.DIRT_PATH)) return true;
            }
        }
        return false;
    }

    private static boolean inPlot(Village v, int x, int z, int margin, Building except) {
        // (a player's plot: round it, never through it)
        if (Plots.inside(v, x, z, margin)) return true;
        // (round the miners' pit, never into it)
        if (Mine.inPit(v, x, z, margin)) return true;
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
    private static List<int[]> route(ServerLevel level, Village v, BlockPos from, BlockPos to, Building self, double water) {
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
                // (round what stands on the ground: a tree, a building's plot, the block of a trade, a barrel)
                if (up.is(BlockTags.LOGS) || !doorstep && !up.isAir() && !up.canBeReplaced() && !up.is(BlockTags.FENCE_GATES)
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
        // right beside a path already there (not on it): dear. A new way joins the street, or keeps a little away
        // from it; else they ran side by side into the square, one broad trodden band
        boolean[] beside = new boolean[n];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int k = i * h + j;
                if (kind[k] == 1 || kind[k] == 2) continue;
                if (i > 0 && kind[k - h] == 1 || i + 1 < w && kind[k + h] == 1 || j > 0 && kind[k - 1] == 1 || j + 1 < h && kind[k + 1] == 1) beside[k] = true;
            }
        }
        // the network: the paths that lead to the square (from its edge, over trodden ground). Walking one is cheap:
        // a new way to the square goes along it where it leads that way (a branch of a street, not a street of its
        // own beside it: two tents side by side had two paths side by side), and makes its own only where the square
        // is nearer the other way
        boolean[] net = new boolean[n];
        {
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            Building center = null;
            for (Building b : v.buildings) if (b.type.isCenter()) center = b;
            int reach = (center == null ? 2 : center.type.half) + 3;
            for (int i = 0; i < w; i++) {
                for (int j = 0; j < h; j++) {
                    int k = i * h + j, x = x0 + i, z = z0 + j;
                    if (kind[k] == 1 && Math.abs(x - v.center.getX()) <= reach && Math.abs(z - v.center.getZ()) <= reach) {
                        net[k] = true;
                        q.add(k);
                    }
                }
            }
            while (!q.isEmpty()) {
                int k = q.poll(), i = k / h, j = k % h;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        int ni = i + di, nj = j + dj;
                        if (ni < 0 || nj < 0 || ni >= w || nj >= h) continue;
                        int m = ni * h + nj;
                        if (kind[m] == 1 && !net[m]) {
                            net[m] = true;
                            q.add(m);
                        }
                    }
                }
            }
        }
        double[] cost = new double[n];
        java.util.Arrays.fill(cost, Double.MAX_VALUE);
        int[] prev = new int[n];
        java.util.Arrays.fill(prev, -1);
        cost[start] = 0;
        PriorityQueue<Cell> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        open.add(new Cell(start / h, start % h, 0));
        int gx = goal / h, gz = goal % h;
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int visited = 0, end = -1;
        while (!open.isEmpty() && visited++ < 40000) {
            Cell c = open.poll();
            int k = c.x * h + c.z;
            if (k == goal) {
                end = k;
                break;
            }
            if (c.f > cost[k] + 1e-6) continue;
            for (int[] s : steps) {
                int nx = c.x + s[0], nz = c.z + s[1];
                if (nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
                int m = nx * h + nz;
                if (kind[m] == 2 || kind[m] == 3 && water < 0) continue;
                int dy = Math.abs(ground[m] - ground[k]);
                // along a path already there is easiest; steep ground is hard (and will be dug or built up); over water
                // only if there is no way round (a bridge is a lot of work)
                double step = (net[m] ? NET_STEP : kind[m] == 1 ? 0.35 : kind[m] == 3 ? water : 1.0) + (dy == 0 ? 0 : dy == 1 ? 0.6 : dy * 4.0);
                // (the doorstep and the goal aside: a way has to come off and on to the street somewhere)
                if (beside[m] && kind[k] != 1 && Math.abs(nx - from.getX() + x0) + Math.abs(nz - from.getZ() + z0) > 2
                        && Math.abs(nx - gx) + Math.abs(nz - gz) > 3) step += 2.5;
                double g = cost[k] + step;
                if (g < cost[m]) {
                    cost[m] = g;
                    prev[m] = k;
                    open.add(new Cell(nx, nz, g));
                }
            }
        }
        if (end < 0) return null;
        List<int[]> out = new ArrayList<>();
        for (int k = end; k >= 0; k = prev[k]) {
            out.add(new int[]{x0 + k / h, z0 + k % h, ground[k], kind[k] == 3 ? 1 : 0});
            if (k == start) break;
        }
        Collections.reverse(out);
        // (only what is new is laid: up to where it meets the network, the rest of the way is trodden already)
        for (int i = 0; i < out.size(); i++) {
            int[] c = out.get(i);
            if (net[(c[0] - x0) * h + (c[1] - z0)]) return new ArrayList<>(out.subList(0, i + 1));
        }
        return out;
    }

    /** What a step along a path of the network costs, against 1 for a step on fresh ground. */
    private static final double NET_STEP = 0.2;

    /**
     * Each crossing of water made straight: from the last dry step before it to the first after, in a line (if that
     * line keeps off the plots), so that the bridge over it is one straight span.
     */
    private static List<int[]> straighten(ServerLevel level, Village v, List<int[]> route) {
        List<int[]> out = new ArrayList<>();
        int i = 0, n = route.size();
        while (i < n) {
            if (route.get(i)[3] != 1 || i == 0) {
                out.add(route.get(i++));
                continue;
            }
            int a = i - 1, b = i;
            while (b < n && route.get(b)[3] == 1) b++;
            if (b >= n) {
                while (i < n) out.add(route.get(i++));
                break;
            }
            int[] from = route.get(a), to = route.get(b);
            List<int[]> line = new ArrayList<>();
            int steps = Math.max(Math.abs(to[0] - from[0]), Math.abs(to[1] - from[1]));
            boolean ok = steps > 0;
            int deck = Math.max(from[2], to[2]);
            for (int k = 1; k < steps && ok; k++) {
                int x = from[0] + Math.round((to[0] - from[0]) * (float) k / steps), z = from[1] + Math.round((to[1] - from[1]) * (float) k / steps);
                if (inPlot(v, x, z, 0, null) || !Construction.loaded(level, new BlockPos(x, 0, z))) ok = false;
                int y = PlotFinder.floorAt(level, x, z) - 1;
                boolean wet = !level.getBlockState(new BlockPos(x, y, z)).getFluidState().isEmpty() || !level.getBlockState(new BlockPos(x, y + 1, z)).getFluidState().isEmpty();
                int surf = y;
                while (!level.getBlockState(new BlockPos(x, surf + 1, z)).getFluidState().isEmpty()) surf++;
                if (wet) deck = Math.max(deck, surf + 1);
                line.add(new int[]{x, z, wet ? surf + 1 : y, 1});
            }
            if (ok) {
                // one level all the way across
                for (int[] c : line) c[2] = deck;
                out.addAll(line);
            } else {
                for (int k = i; k < b; k++) out.add(route.get(k));
            }
            i = b;
        }
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
            // (a bridge's deck is laid whole below)
            if (dx == 0 && dz == 0 || c[3] == 1) continue;
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
        bridges(level, v, route, y, way);
        Map<Long, Boolean> done = new HashMap<>();
        for (int[] cell : cells) {
            int x = cell[0], z = cell[1], ground = cell[2], top = cell[3];
            int i = cell[5];
            if (done.put(Blueprint.key(x, z), true) != null) continue;
            BlockPos p = new BlockPos(x, top, z);
            if (cell[4] == 1) continue;
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

    /**
     * The bridges of a path: each run of steps over water gets a straight deck three planks wide across the way it
     * crosses, at one height, with rails along both sides from bank to bank.
     */
    private static void bridges(ServerLevel level, Village v, List<int[]> route, int[] y, java.util.Set<Long> way) {
        BlockState deck = wood(v, "planks"), rail = wood(v, "fence");
        int n = route.size();
        for (int i = 0; i < n; i++) {
            if (route.get(i)[3] != 1) continue;
            int a = i, b = i;
            while (b + 1 < n && route.get(b + 1)[3] == 1) b++;
            int[] s0 = route.get(Math.max(0, a - 1)), s1 = route.get(Math.min(n - 1, b + 1));
            int top = Integer.MIN_VALUE;
            for (int k = a; k <= b; k++) top = Math.max(top, Math.max(y[k], route.get(k)[2]));
            // across: the way the crossing goes, on the whole (east-west or north-south)
            boolean alongX = Math.abs(s1[0] - s0[0]) >= Math.abs(s1[1] - s0[1]);
            int px = alongX ? 0 : 1, pz = alongX ? 1 : 0;
            java.util.Set<Long> laid = new java.util.HashSet<>();
            // (a step on to it from the banks: the first and last dry steps get the deck's width too)
            for (int k = Math.max(0, a - 1); k <= Math.min(n - 1, b + 1); k++) {
                int[] c = route.get(k);
                boolean bank = k < a || k > b;
                int h = bank ? Math.min(top, Math.max(y[k], c[2])) : top;
                for (int w = -1; w <= 1; w++) {
                    int x = c[0] + px * w, z = c[1] + pz * w;
                    if (inPlot(v, x, z, 0, null)) continue;
                    BlockPos p = new BlockPos(x, h, z);
                    if (bank && w != 0 && level.getBlockState(p).getFluidState().isEmpty() && level.getBlockState(p.below()).getFluidState().isEmpty()) continue;
                    level.setBlock(p, deck, FLAGS);
                    for (int up = 1; up <= 2; up++) if (!level.getBlockState(p.above(up)).isAir()) level.setBlock(p.above(up), Blocks.AIR.defaultBlockState(), FLAGS);
                    laid.add(Blueprint.key(x, z));
                    way.add(Blueprint.key(x, z));
                }
            }
            for (int k = a; k <= b; k++) {
                int[] c = route.get(k);
                for (int w : new int[]{-2, 2}) {
                    int x = c[0] + px * w, z = c[1] + pz * w;
                    if (laid.contains(Blueprint.key(x, z)) || inPlot(v, x, z, 0, null)) continue;
                    BlockPos p = new BlockPos(x, top, z);
                    BlockState at = level.getBlockState(p);
                    if (!at.canBeReplaced()) continue;
                    level.setBlock(p, deck, FLAGS);
                    if (level.getBlockState(p.above()).canBeReplaced()) level.setBlock(p.above(), rail, FLAGS | Block.UPDATE_NEIGHBORS);
                }
            }
            i = b;
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
