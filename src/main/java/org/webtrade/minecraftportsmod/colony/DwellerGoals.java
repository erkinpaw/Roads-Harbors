package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the people of a village do all day. Mornings at their trade (fishing on the shore, felling trees, breaking
 * stone, in the field); afternoons on the building sites when there is building to do — carrying materials from the
 * store and putting the blocks up; evenings by the fire; nights in bed. Children play. What someone is doing is
 * written under their name.
 */
public final class DwellerGoals {

    /** A store holding this much of something needs no more of it for now. */
    static final int PLENTY = 40;

    private DwellerGoals() {
    }

    public static void register(ResidentEntity r, GoalSelector goals) {
        goals.addGoal(1, new Arrive(r));
        goals.addGoal(0, new Road(r));
        goals.addGoal(0, new RoadWork(r));
        goals.addGoal(1, new Depart(r));
        goals.addGoal(2, new Sleep(r));
        goals.addGoal(3, new Build(r));
        goals.addGoal(4, new WorkGoal(r));
        goals.addGoal(4, new Tend(r));
        goals.addGoal(4, new Stall(r));
        goals.addGoal(4, new Play(r));
        goals.addGoal(5, new Rest(r));
    }

    // ------------------------------------------------------------------ helpers

    private static Village village(ResidentEntity r) {
        if (!r.colony() || !(r.level() instanceof ServerLevel level)) return null;
        return VillageData.get(level.getServer()).get(r.colonyVillage());
    }

    private static Dweller dweller(ResidentEntity r) {
        Village v = village(r);
        return v == null ? null : v.dweller(r.colonyDweller());
    }

    private static long today(ResidentEntity r) {
        return VillageData.get(((ServerLevel) r.level()).getServer()).day();
    }

    private static boolean night(ResidentEntity r) {
        return Routine.asleep(r);
    }

    private static boolean workHours(ResidentEntity r) {
        return Routine.working(r);
    }

    private static boolean afternoon(ResidentEntity r) {
        return Routine.afternoon(r);
    }

    private static boolean grownUp(ResidentEntity r) {
        return r.colonyJob() != null;
    }

    private static Component act(String key, Object... args) {
        return Component.translatable("minecraftportsmod.act." + key, args);
    }

    private static double dist2(ResidentEntity r, BlockPos p) {
        double dx = r.getX() - (p.getX() + 0.5), dz = r.getZ() - (p.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    /** Walks towards a point; true once there. */
    private static boolean walk(ResidentEntity r, BlockPos to, double reach, int[] repath) {
        if (dist2(r, to) <= reach * reach && Math.abs(r.getY() - to.getY()) < 3) {
            r.getNavigation().stop();
            return true;
        }
        r.walking = r.level().getGameTime();
        if (r.getNavigation().isDone() || --repath[0] <= 0) {
            repath[0] = 40;
            r.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, Routine.mode(r).speed);
        }
        return false;
    }

    // ------------------------------------------------------------------ what the land offers

    private record Found(long time, List<BlockPos> spots) {
    }

    private static final Map<Integer, Found> TREES = new HashMap<>(), STONE = new HashMap<>();
    private static final Map<Integer, List<BlockPos[]>> SHORE = new HashMap<>();

    /** Trunks around the village (their lowest log), nearest first. */
    static List<BlockPos> trees(ServerLevel level, Village v) {
        return scan(level, v, TREES, true);
    }

    /** Bare stone around the village, nearest first. */
    static List<BlockPos> stone(ServerLevel level, Village v) {
        return scan(level, v, STONE, false);
    }

    static void clearCaches() {
        TREES.clear();
        STONE.clear();
        SHORE.clear();
        QUARRY.clear();
        WorkGoal.plans.clear();
        Build.BUILDERS.clear();
    }

    // ------------------------------------------------------------------ the quarry

    /** Blocks still to dig at each village's quarry pit, in order. */
    private static final Map<Integer, java.util.Deque<BlockPos>> QUARRY = new HashMap<>();

    /**
     * The next block of the village's quarry: where there is no bare rock about, the miner digs a shallow pit in
     * steps (5x5, then 3x3, then the middle: easy to climb out of) on dry land away from the water and the houses,
     * and starts the next pit beside it when one is done.
     */
    static BlockPos nextQuarryBlock(ServerLevel level, Village v, net.minecraft.util.RandomSource rnd) {
        java.util.Deque<BlockPos> plan = QUARRY.get(v.id);
        for (int tries = 0; tries < 3; tries++) {
            if (plan == null || plan.isEmpty()) {
                plan = pit(level, v, rnd);
                if (plan == null) return null;
                QUARRY.put(v.id, plan);
            }
            while (!plan.isEmpty()) {
                BlockPos p = plan.peekFirst();
                BlockState s = level.getBlockState(p);
                if (s.isAir() || !s.getFluidState().isEmpty() || s.is(BlockTags.LOGS) || !level.getBlockState(p.above()).getFluidState().isEmpty()) {
                    plan.pollFirst();
                    continue;
                }
                return p;
            }
        }
        return null;
    }

    static void dugQuarryBlock(Village v, BlockPos p) {
        java.util.Deque<BlockPos> plan = QUARRY.get(v.id);
        if (plan != null) plan.remove(p);
    }

    private static java.util.Deque<BlockPos> pit(ServerLevel level, Village v, net.minecraft.util.RandomSource rnd) {
        for (int i = 0; i < 40; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            // the quarry away from the houses too
            int dist = 30 + rnd.nextInt(40);
            int x = v.center.getX() + (int) (Math.cos(a) * dist), z = v.center.getZ() + (int) (Math.sin(a) * dist);
            if (!dryAround(level, v, x, z, 5)) continue;
            // (off the village's own land: there pits are filled, not dug)
            if (Territory.contains(v, x - 4, z - 4) || Territory.contains(v, x + 4, z + 4) || Territory.contains(v, x - 4, z + 4)
                    || Territory.contains(v, x + 4, z - 4)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            // (where the miner can walk to, and back with the stone, on dry feet)
            if (!Reach.ok(level, v, new BlockPos(x, top + 1, z))) continue;
            // no digging into sand or gravel: it slides in and buries the digger
            boolean loose = false;
            for (int dx = -3; dx <= 3 && !loose; dx++) {
                for (int dz = -3; dz <= 3 && !loose; dz++) {
                    for (int dy = -3; dy <= 1; dy++) {
                        if (level.getBlockState(new BlockPos(x + dx, top + dy, z + dz)).getBlock() instanceof net.minecraft.world.level.block.FallingBlock) {
                            loose = true;
                            break;
                        }
                    }
                }
            }
            if (loose) continue;
            java.util.Deque<BlockPos> plan = new java.util.ArrayDeque<>();
            for (int d = 0; d <= 2; d++) {
                int h = 2 - d;
                for (int dx = -h; dx <= h; dx++) {
                    for (int dz = -h; dz <= h; dz++) plan.add(new BlockPos(x + dx, top - d, z + dz));
                }
            }
            return plan;
        }
        return null;
    }

    /** Dry, loaded land around a point, off the plots, not by the water, not steep. */
    private static boolean dryAround(ServerLevel level, Village v, int x, int z, int r) {
        int top0 = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int px = x + dx, pz = z + dz;
                if (!Construction.loaded(level, new BlockPos(px, 0, pz))) return false;
                if (inPlot(v, px, pz, 2)) return false;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
                if (Math.abs(top - top0) > 2) return false;
                if (!level.getBlockState(new BlockPos(px, top - 1, pz)).getFluidState().isEmpty()) return false;
            }
        }
        return true;
    }

    /** A spot on dry land about the village (for someone with nothing to do to walk to), or null. */
    static BlockPos dryLand(ServerLevel level, Village v, net.minecraft.util.RandomSource rnd, int dist) {
        for (int i = 0; i < 12; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            int x = v.center.getX() + (int) (Math.cos(a) * dist), z = v.center.getZ() + (int) (Math.sin(a) * dist);
            if (!Construction.loaded(level, new BlockPos(x, 0, z))) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos ground = new BlockPos(x, top - 1, z);
            if (!level.getBlockState(ground).getFluidState().isEmpty()) continue;
            boolean wet = false;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (!level.getBlockState(ground.relative(dir, 2)).getFluidState().isEmpty()) wet = true;
            }
            if (!wet) return new BlockPos(x, top, z);
        }
        return null;
    }

    /** A dry spot on the ground within {@code dist} of a point, or null. */
    static BlockPos near(ServerLevel level, BlockPos c, int dist, net.minecraft.util.RandomSource rnd) {
        for (int i = 0; i < 10; i++) {
            int x = c.getX() + rnd.nextInt(2 * dist + 1) - dist, z = c.getZ() + rnd.nextInt(2 * dist + 1) - dist;
            if (!Construction.loaded(level, new BlockPos(x, 0, z))) continue;
            int y = PlotFinder.floorAt(level, x, z);
            if (!level.getBlockState(new BlockPos(x, y - 1, z)).getFluidState().isEmpty()) continue;
            if (Math.abs(y - c.getY()) > 3) continue;
            return new BlockPos(x, y, z);
        }
        return null;
    }

    static void forgetTrees(Village v) {
        TREES.remove(v.id);
    }

    static void forgetStone(Village v, BlockPos p) {
        Found f = STONE.get(v.id);
        if (f != null) f.spots.remove(p);
    }

    /**
     * May this block be quarried: natural stone with open air above, outside the plots, not by water, not in the
     * middle of the village, and never below the village's own ground (the hills are cut down, no pits are dug).
     */
    /** Rock a miner brings home as stone. */
    static boolean rock(BlockState s) {
        return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)
                || s.is(net.minecraft.world.level.block.Blocks.MOSSY_COBBLESTONE) || s.is(net.minecraft.world.level.block.Blocks.SANDSTONE)
                || s.is(net.minecraft.world.level.block.Blocks.RED_SANDSTONE) || s.is(BlockTags.TERRACOTTA);
    }

    static boolean mineable(ServerLevel level, Village v, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (!rock(s)) return false;
        if (!level.getBlockState(p.above()).isAir()) return false;
        // ledges down to the shore too, but not far below the village nor high up a mountain
        if (p.getY() < v.center.getY() - 6 || p.getY() > v.center.getY() + 14) return false;
        // only rock that stands out (a slope, a ledge): the hills are cut down, flat ground is never dug into
        boolean face = false;
        for (Direction dir : Direction.Plane.HORIZONTAL) if (level.getBlockState(p.relative(dir)).isAir()) face = true;
        if (!face) return false;
        double d = Math.sqrt(p.distSqr(v.center));
        if (d < 10) return false;
        if (inPlot(v, p.getX(), p.getZ(), 3)) return false;
        for (Direction dir : Direction.values()) {
            if (dir == Direction.UP) continue;
            if (!level.getBlockState(p.relative(dir)).getFluidState().isEmpty()) return false;
        }
        return true;
    }

    private static List<BlockPos> scan(ServerLevel level, Village v, Map<Integer, Found> cache, boolean logs) {
        Found f = cache.get(v.id);
        long now = level.getGameTime();
        if (f != null && now - f.time < 1200) return f.spots;
        List<BlockPos> out = new ArrayList<>();
        int r = logs ? 90 : 60;
        BlockPos hut = logs ? woodHut(v) : null;
        // (the woodcutters' grove may lie beyond the reach round the middle)
        int gx = hut == null ? 0 : hut.getX() - v.center.getX(), gz = hut == null ? 0 : hut.getZ() - v.center.getZ();
        int minX = Math.min(-r, gx - GROVE), maxX = Math.max(r, gx + GROVE), minZ = Math.min(-r, gz - GROVE), maxZ = Math.max(r, gz + GROVE);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean round = Math.abs(x) <= r && Math.abs(z) <= r || hut != null && Math.abs(x - gx) <= GROVE && Math.abs(z - gz) <= GROVE;
                if (!round) continue;
                int px = v.center.getX() + x, pz = v.center.getZ() + z;
                if (!Construction.loaded(level, new BlockPos(px, 0, pz))) continue;
                // trees right by the buildings are the first to go; only the buildings themselves are off limits
                if (logs ? inside(v, px, pz) : inPlot(v, px, pz, 1)) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz) - 1;
                BlockPos p = new BlockPos(px, top, pz);
                if (logs) {
                    if (!level.getBlockState(p).is(BlockTags.LOGS)) continue;
                    while (level.getBlockState(p.below()).is(BlockTags.LOGS)) p = p.below();
                    if (Math.abs(p.getY() - v.center.getY()) < 14) out.add(p);
                } else if (mineable(level, v, p)) {
                    out.add(p);
                }
            }
        }
        if (logs) {
            // clear the village first: trees crowding a building, then the nearest to the middle
            // the village's own land cleared first (trees crowding a building before the rest), then the nearest
            // then the woodcutters' own: nearest their hut
            BlockPos from = hut != null ? hut : v.center;
            out.sort(java.util.Comparator.comparingDouble((BlockPos p) -> Tidy.territory(v, p.getX(), p.getZ())
                    ? Math.min(nearBuilding(v, p) * 3.0, Math.sqrt(p.distSqr(v.center))) : 1000 + Math.sqrt(p.distSqr(from))));
        } else {
            out.sort((a, b) -> Double.compare(a.distSqr(v.center), b.distSqr(v.center)));
        }
        if (out.size() > 60) out = new ArrayList<>(out.subList(0, 60));
        cache.put(v.id, new Found(now, out));
        return out;
    }

    /** How far round the woodcutters' hut their grove reaches. */
    static final int GROVE = 16;

    /** The woodcutters' hut (the first standing one), or null. */
    static BlockPos woodHut(Village v) {
        for (Building b : v.buildings) if (b.type == BuildingType.WOOD_HUT && b.standing()) return b.origin;
        return null;
    }

    /** Is this column part of a building itself (inside its walls)? */
    static boolean inside(Village v, int x, int z) {
        for (Building b : v.buildings) {
            int h = Math.max(1, b.type.half - 1);
            if (Math.abs(x - b.origin.getX()) <= h && Math.abs(z - b.origin.getZ()) <= h) return true;
        }
        return Math.abs(x - v.board.getX()) <= 0 && Math.abs(z - v.board.getZ()) <= 0;
    }

    /** How far (in blocks) from the nearest building's plot. */
    private static int nearBuilding(Village v, BlockPos p) {
        int best = 99;
        for (Building b : v.buildings) {
            int d = Math.max(Math.abs(p.getX() - b.origin.getX()), Math.abs(p.getZ() - b.origin.getZ())) - b.type.half;
            best = Math.min(best, Math.max(0, d));
        }
        return best;
    }

    static boolean inPlot(Village v, int x, int z, int margin) {
        for (Building b : v.buildings) {
            if (Math.abs(x - b.origin.getX()) <= b.type.half + margin && Math.abs(z - b.origin.getZ()) <= b.type.half + margin) return true;
        }
        return Math.abs(x - v.board.getX()) <= 1 && Math.abs(z - v.board.getZ()) <= 1;
    }

    /**
     * Places on the shore to fish from: {stand, water}, found walking from the middle of the village towards the
     * water in several directions. Each fisher takes a different one ({@code n}).
     */
    static BlockPos[] fishingSpot(ServerLevel level, Village v, int n) {
        List<BlockPos[]> spots = SHORE.get(v.id);
        if (spots == null || spots.isEmpty()) {
            spots = new ArrayList<>();
            double base = Math.atan2(v.front.getStepZ(), v.front.getStepX());
            for (int k = -4; k <= 4; k++) {
                double a = base + k * 0.28;
                BlockPos last = null;
                for (int d = 1; d <= 56; d++) {
                    int x = v.center.getX() + (int) Math.round(Math.cos(a) * d), z = v.center.getZ() + (int) Math.round(Math.sin(a) * d);
                    if (!Construction.loaded(level, new BlockPos(x, 0, z))) break;
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    BlockPos ground = new BlockPos(x, top, z);
                    if (!level.getBlockState(ground).getFluidState().isEmpty()) {
                        if (last != null && Math.abs(last.getY() - top) <= 3 && !inPlot(v, last.getX(), last.getZ(), 0)) {
                            // the float lands three blocks out
                            int wx = v.center.getX() + (int) Math.round(Math.cos(a) * (d + 3)), wz = v.center.getZ() + (int) Math.round(Math.sin(a) * (d + 3));
                            BlockPos water = new BlockPos(wx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz), wz);
                            spots.add(new BlockPos[]{last, water});
                        }
                        break;
                    }
                    last = ground.above();
                }
            }
            SHORE.put(v.id, spots);
        }
        if (spots.isEmpty()) return null;
        return spots.get(Math.floorMod(n * 3, spots.size()));
    }

    // ------------------------------------------------------------------ goals

    /** A newcomer walks in from the road to the middle of the village. */
    static final class Arrive extends Goal {
        private final ResidentEntity r;
        private final int[] repath = {0};
        private int timer;

        Arrive(ResidentEntity r) {
            this.r = r;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            Dweller d = dweller(r);
            return d != null && d.arriving;
        }

        @Override
        public void tick() {
            Village v = village(r);
            Dweller d = dweller(r);
            if (v == null || d == null) return;
            r.setActivity(act("arriving"));
            // no way down to the square (a cliff, water): after half a minute the newcomer counts as come
            if (walk(r, v.center, 5, repath) || ++timer > 300) {
                timer = 0;
                VillageManager.arrived((ServerLevel) r.level(), v, d);
            }
        }
    }

    /** A scout setting out: off to the edge of the village the way the expedition goes, and gone. */
    /** A merchant on the road: along the trail to the other village, his load in his hands. */
    static final class Road extends Goal {
        private final ResidentEntity r;
        private final int[] repath = {0};

        Road(ResidentEntity r) {
            this.r = r;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            Dweller d = dweller(r);
            return d != null && d.away && d.job == Job.MERCHANT && Caravans.tripOf(VillageData.get(r.level().getServer()), village(r), d) != null;
        }

        @Override
        public void tick() {
            Village v = village(r);
            Dweller d = dweller(r);
            VillageData data = VillageData.get(r.level().getServer());
            Caravans.Trip t = Caravans.tripOf(data, v, d);
            if (t == null) return;
            Village to = data.get(t.back ? t.from : t.to);
            Res load = t.res();
            r.hold(load == null ? ItemStack.EMPTY : new ItemStack(load.icon));
            r.setActivity(act("travelling", to == null ? "" : to.name));
            BlockPos aim = Caravans.aim((ServerLevel) r.level(), v, d);
            if (aim != null) walk(r, aim, 2, repath);
        }
    }

    /**
     * A crew making a trail: by day at the end of the way (felling, clearing, treading), axe in hand; at night asleep
     * in the tent pitched by the way.
     */
    static final class RoadWork extends Goal {
        private final ResidentEntity r;
        private final int[] repath = {0};
        private int swing, timer;
        /** The block being dug or hewn (its cracks shown), and how far along. */
        private BlockPos cracking;
        private int crack;

        RoadWork(ResidentEntity r) {
            this.r = r;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            Dweller d = dweller(r);
            return d != null && d.away && Roadworks.atWay(VillageData.get(r.level().getServer()), village(r), d);
        }

        @Override
        public void start() {
            timer = 0;
        }

        @Override
        public void tick() {
            Village v = village(r);
            Dweller d = dweller(r);
            ServerLevel level = (ServerLevel) r.level();
            VillageData data = VillageData.get(level.getServer());
            if (v == null || d == null) return;
            if (!Roadworks.working(data)) {
                // the night in the tent
                BlockPos bed = Roadworks.bed(level, data, v, d);
                r.hold(ItemStack.EMPTY);
                if (r.isSleeping()) return;
                if (bed == null) {
                    r.setActivity(act("camping", Roadworks.towards(data, v, d)));
                    return;
                }
                r.setActivity(act("to_bed"));
                if (walk(r, bed, 1.6, repath) || ++timer > 300) {
                    timer = 0;
                    if (r.level().getBlockState(bed).getBlock() instanceof BedBlock) {
                        r.startSleeping(bed);
                        r.setActivity(act("sleeping"));
                    }
                }
                return;
            }
            if (r.isSleeping()) r.stopSleeping();
            BlockPos at = Roadworks.front(level, data, v, d);
            if (at == null) return;
            BlockPos work = Roadworks.workBlock(level, data, v, d);
            BlockState ws = work == null ? null : level.getBlockState(work);
            // the first of a crew digs (a shovel), the other hews and clears (an axe); over water both build the deck
            boolean deck = ws != null && (ws.is(BlockTags.PLANKS) || ws.is(BlockTags.WOODEN_SLABS) || ws.is(BlockTags.LOGS)
                    || !level.getBlockState(work.above()).getFluidState().isEmpty() || !ws.getFluidState().isEmpty());
            // an axe only for a tree in the way (the second of a crew fells it); with no tree, both dig
            BlockPos log = null;
            if (!deck && work != null && Roadworks.crewIndex(data, v, d) == 1) {
                for (BlockPos q : BlockPos.betweenClosed(work.offset(-3, 1, -3), work.offset(3, 6, 3))) {
                    if (level.getBlockState(q).is(BlockTags.LOGS)) {
                        log = q.immutable();
                        break;
                    }
                }
            }
            if (log != null) {
                work = log;
                ws = level.getBlockState(log);
            }
            boolean digger = !deck && log == null;
            int tier = v.toolLevel(Job.WOODCUTTER);
            r.hold(new ItemStack(deck ? Items.OAK_PLANKS
                    : digger ? (tier >= 3 ? Items.IRON_SHOVEL : tier == 2 ? Items.STONE_SHOVEL : Items.WOODEN_SHOVEL)
                    : (tier >= 3 ? Items.IRON_AXE : tier == 2 ? Items.STONE_AXE : Items.WOODEN_AXE)));
            // (along the way made, a few steps at a time: over its bridges, not through the water beside them)
            BlockPos next = Roadworks.step(level, data, v, d, r.getX(), r.getZ());
            if (next != null && !next.equals(at)) {
                walk(r, next, 1.5, repath);
                r.setActivity(act("to_roadwork", Roadworks.towards(data, v, d)));
                uncrack(level);
            } else if (walk(r, at, 3, repath)) {
                r.setActivity(act(deck ? "roadwork_bridge" : "roadwork", Roadworks.towards(data, v, d)));
                BlockPos look = work != null && work.distSqr(r.blockPosition()) < 36 ? work : at;
                r.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + (look == work ? 0.9 : 1.5), look.getZ() + 0.5);
                if (++swing % 12 == 0) {
                    r.swing(InteractionHand.MAIN_HAND);
                    if (look == work && ws != null && !ws.isAir()) {
                        // the blows land: cracks in the ground dug, chips flying, the sound of the tool
                        if (!work.equals(cracking)) {
                            uncrack(level);
                            cracking = work;
                            crack = 0;
                        }
                        crack = (crack + 1) % 10;
                        level.destroyBlockProgress(r.getId(), work, crack);
                        BlockState shown = ws.getFluidState().isEmpty() ? ws : level.getBlockState(work.below());
                        if (!shown.isAir()) {
                            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, shown), work.getX() + 0.5, work.getY() + 1.0,
                                    work.getZ() + 0.5, 6, 0.3, 0.1, 0.3, 0.1);
                        }
                        var sound = deck ? net.minecraft.sounds.SoundEvents.WOOD_PLACE
                                : digger ? shown.getSoundType().getHitSound() : net.minecraft.sounds.SoundEvents.WOOD_HIT;
                        level.playSound(null, work, sound, net.minecraft.sounds.SoundSource.BLOCKS, 0.6F, 0.9F + r.getRandom().nextFloat() * 0.2F);
                    }
                }
            } else {
                r.setActivity(act("to_roadwork", Roadworks.towards(data, v, d)));
                uncrack(level);
            }
        }

        private void uncrack(ServerLevel level) {
            if (cracking != null) level.destroyBlockProgress(r.getId(), cracking, -1);
            cracking = null;
        }

        @Override
        public void stop() {
            if (r.isSleeping()) r.stopSleeping();
            uncrack((ServerLevel) r.level());
        }
    }

    static final class Depart extends Goal {
        private final ResidentEntity r;
        private final int[] repath = {0};
        private BlockPos to;
        private int timer;

        Depart(ResidentEntity r) {
            this.r = r;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            Dweller d = dweller(r);
            // (a merchant leaves along his trail: see Road; a crew at the end of the way works there: see RoadWork)
            return d != null && d.away && d.job != Job.MERCHANT && !Roadworks.atWay(VillageData.get(r.level().getServer()), village(r), d);
        }

        @Override
        public void start() {
            Village v = village(r);
            Dweller d = dweller(r);
            timer = 0;
            if (v == null || d == null) return;
            double a = Math.toRadians(d.heading);
            int x = v.center.getX() + (int) Math.round(Math.cos(a) * 48), z = v.center.getZ() + (int) Math.round(Math.sin(a) * 48);
            to = Construction.loaded((ServerLevel) r.level(), new BlockPos(x, 0, z))
                    ? new BlockPos(x, PlotFinder.floorAt((ServerLevel) r.level(), x, z), z) : v.center;
            r.hold(new ItemStack(net.minecraft.world.item.Items.COMPASS));
            // a skipper goes aboard: down the jetty to the end of it
            Building pier = d.job == Job.SAILOR ? Harbour.pier(v) : null;
            if (pier != null) {
                net.minecraft.core.Direction out = Harbour.out(pier);
                int k = pier.type.half + pier.jetty[1];
                to = new BlockPos(pier.origin.getX() + out.getStepX() * k, pier.origin.getY(), pier.origin.getZ() + out.getStepZ() * k);
                r.hold(new ItemStack(net.minecraft.world.item.Items.SPYGLASS));
            }
        }

        @Override
        public void tick() {
            r.setActivity(act("leaving"));
            // out of sight down the road (or, if the way can't be walked, after a while anyway)
            if (to == null || walk(r, to, 3, repath) || ++timer > 200) r.discard();
        }
    }

    /** Bed at night: the tent or the house. */
    static final class Sleep extends Goal {
        private final ResidentEntity r;
        private final int[] repath = {0};
        private BlockPos bed;

        Sleep(ResidentEntity r) {
            this.r = r;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            if (!night(r) || village(r) == null) return false;
            bed = bed();
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            return night(r);
        }

        private BlockPos bed() {
            Village v = village(r);
            Dweller d = dweller(r);
            if (v == null || d == null) return null;
            Building home = v.building(d.home);
            if (home == null || !home.standing()) return null;
            List<BlockPos> beds = home.blueprint(v.wood).beds;
            // the n-th person living there takes the n-th bed
            int n = 0;
            for (Dweller o : v.dwellers) {
                if (o == d) break;
                if (o.home == d.home) n++;
            }
            return beds.isEmpty() ? null : beds.get(Math.min(n, beds.size() - 1));
        }

        private int timer;

        @Override
        public void start() {
            r.hold(ItemStack.EMPTY);
            timer = 0;
        }

        @Override
        public void tick() {
            if (r.isSleeping()) return;
            Village v = village(r);
            if (bed == null || v == null) {
                r.setActivity(act("sleeping_out"));
                if (v != null) walk(r, v.center, 4, repath);
                return;
            }
            r.setActivity(act("to_bed"));
            // a bed the path-finding can't find a way to (upstairs, a door stuck): after half a minute, as if they had got there
            boolean far = ++timer > 300;
            if (walk(r, bed, 1.6, repath) || far) {
                BlockState st = r.level().getBlockState(bed);
                if (st.getBlock() instanceof BedBlock) {
                    r.startSleeping(bed);
                    r.setActivity(act("sleeping"));
                }
                timer = 0;
            }
        }

        @Override
        public void stop() {
            if (r.isSleeping()) r.stopSleeping();
        }
    }

    /**
     * The building sites: bring materials from the store while a site lacks them, then put the building up
     * (or take one down).
     */
    static final class Build extends Goal {
        private final ResidentEntity r;
        private final int[] repath = {0};
        private int site = -1;
        private Res carrying;
        private boolean loaded;
        private BlockPos stand;
        private int swing, walking;

        Build(ResidentEntity r) {
            this.r = r;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        /** At most this many people of a village build at a time; the others keep to their own work. */
        static final int CREW = 3;
        /** More hands on top of the crew: the ones whose trade's store has plenty (else they would stand idle). */
        static final int EXTRA = 3;
        /** Who is building in each village now: dweller id → the game time last seen at it. */
        private static final Map<Integer, Map<Integer, Long>> BUILDERS = new HashMap<>();

        private boolean crewFull(Village v) {
            Map<Integer, Long> crew = BUILDERS.computeIfAbsent(v.id, k -> new HashMap<>());
            long now = r.level().getGameTime();
            crew.values().removeIf(t -> now - t > 100);
            if (crew.containsKey(r.colonyDweller())) return false;
            // (one whose own work is not wanted now joins even a full crew, up to a few more)
            Dweller d = dweller(r);
            boolean idle = d != null && d.job() != null && VillageLife.plenty(v, d.job().makes);
            return crew.size() >= CREW + (idle ? EXTRA : 0);
        }

        private void onCrew(Village v, boolean on) {
            Map<Integer, Long> crew = BUILDERS.computeIfAbsent(v.id, k -> new HashMap<>());
            if (on) crew.put(r.colonyDweller(), r.level().getGameTime());
            else crew.remove(r.colonyDweller());
        }

        @Override
        public boolean canUse() {
            // the stall's merchant, the sawyer and the scout have their own work places
            if (!grownUp(r) || !buildTime() || r.colonyJob() != null && r.colonyJob().makes == null) return false;
            Village v = village(r);
            if (v == null || crewFull(v)) return false;
            return pick(v) != null;
        }

        /**
         * Afternoons everybody builds; mornings those whose day's work is done already, and those whose trade the
         * store has plenty of anyway (more logs would only lie about: better to build with them).
         */
        private boolean buildTime() {
            if (afternoon(r)) return true;
            Dweller d = dweller(r);
            Village v = village(r);
            if (!workHours(r) || d == null || v == null) return false;
            // (a store that is nearly full needs no more of it this morning; and whoever is ahead of the day helps)
            return d.doneForToday() || WorkGoal.ahead(v, d, r.dayTime())
                    || d.job() != null && d.job().makes != null && VillageLife.plenty(v, d.job().makes);
        }

        @Override
        public boolean canContinueToUse() {
            Village v = village(r);
            if (v == null || !buildTime()) return false;
            Building b = v.building(site);
            return b != null && needs(v, b);
        }

        /** Is there something this person can do at the site? */
        private boolean needs(Village v, Building b) {
            if (!Construction.plotLoaded((ServerLevel) r.level(), b)) return false;
            return switch (b.state) {
                case PLANNED -> {
                    for (Res res : Res.values()) if (b.missing(res) > 0 && (v.stock(res) > 0 || carrying == res && loaded)) yield true;
                    yield false;
                }
                case BUILDING -> true;
                case DEMOLISHING -> !b.finished;
                // raised a level: materials first, then the additions go up
                case BUILT -> {
                    if (!b.upgrading()) yield false;
                    if (b.supplied()) yield true;
                    for (Res res : Res.values()) if (b.missing(res) > 0 && (v.stock(res) > 0 || carrying == res && loaded)) yield true;
                    yield false;
                }
            };
        }

        private Building pick(Village v) {
            // spread people: by their number, start at a different site
            List<Building> sites = new ArrayList<>();
            for (Building b : v.projects()) if (needs(v, b)) sites.add(b);
            if (sites.isEmpty()) return null;
            return sites.get(Math.floorMod(r.colonyDweller(), sites.size()));
        }

        @Override
        public void start() {
            Village v = village(r);
            Building b = v == null ? null : pick(v);
            site = b == null ? -1 : b.id;
            carrying = null;
            loaded = false;
            stand = null;
            if (v != null && b != null) onCrew(v, true);
        }

        @Override
        public void stop() {
            Village v = village(r);
            if (v != null) onCrew(v, false);
            site = -1;
            r.hold(ItemStack.EMPTY);
        }

        @Override
        public void tick() {
            try {
                work();
            } catch (RuntimeException e) {
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.error("Resident {} building failed", r.getName().getString(), e);
                site = -1;
                stand = null;
                carrying = null;
            }
        }

        private void work() {
            Village v = village(r);
            Building b = v == null ? null : v.building(site);
            if (b == null) return;
            onCrew(v, true);
            ServerLevel level = (ServerLevel) r.level();
            if (b.state == Building.State.PLANNED || b.upgrading() && !b.supplied()) {
                haul(level, v, b);
                return;
            }
            // put it up (or pull it down): stand at the edge of the plot and work
            if (stand == null) {
                int h = b.type.half + 1;
                int side = r.getRandom().nextInt(4), along = r.getRandom().nextInt(2 * h + 1) - h;
                BlockPos at = switch (side) {
                    case 0 -> b.origin.offset(along, 0, -h);
                    case 1 -> b.origin.offset(along, 0, h);
                    case 2 -> b.origin.offset(-h, 0, along);
                    default -> b.origin.offset(h, 0, along);
                };
                // on the ground there, not at the height of the plot (the land around may be higher or lower)
                stand = new BlockPos(at.getX(), PlotFinder.floorAt(level, at.getX(), at.getZ()), at.getZ());
                walking = 0;
            }
            boolean down = b.state == Building.State.DEMOLISHING;
            r.setActivity(act(down ? "demolishing" : "building", b.type.displayName()));
            r.hold(new ItemStack(down ? Items.STONE_AXE : Items.OAK_PLANKS));
            // close enough to the plot is good enough; a spot that can't be reached is swapped for another
            boolean near = dist2(r, b.origin) <= (b.type.half + 4) * (b.type.half + 4) && Math.abs(r.getY() - b.origin.getY()) < 5;
            if (!near && !walk(r, stand, 2.2, repath)) {
                if (++walking > 200) stand = null;
                return;
            }
            r.getNavigation().stop();
            r.getLookControl().setLookAt(b.origin.getX() + 0.5, b.origin.getY() + 1.5, b.origin.getZ() + 0.5);
            if (++swing % 20 == 0) {
                r.swing(InteractionHand.MAIN_HAND);
                VillageManager.work(level, v, b, 1);
            }
            // now and then, walk round to another side
            if (swing % 400 == 0) stand = null;
        }

        private void haul(ServerLevel level, Village v, Building b) {
            if (carrying == null || b.missing(carrying) <= 0 || !loaded && v.stock(carrying) <= 0) {
                carrying = null;
                loaded = false;
                for (Res res : Res.values()) {
                    if (b.missing(res) > 0 && v.stock(res) > 0) {
                        carrying = res;
                        break;
                    }
                }
                if (carrying == null) return;
            }
            if (!loaded) {
                r.setActivity(act("fetching", carrying.displayName()));
                r.hold(ItemStack.EMPTY);
                if (walk(r, v.storeSpot(), 2.5, repath)) {
                    loaded = true;
                    r.swing(InteractionHand.MAIN_HAND);
                }
                return;
            }
            r.setActivity(act("carrying", carrying.displayName(), b.type.displayName()));
            r.hold(new ItemStack(carrying == Res.WOOD ? Items.OAK_LOG : carrying == Res.STONE ? Items.COBBLESTONE : Items.BREAD));
            BlockPos post = Construction.postAt(level, v, b);
            if (walk(r, post, 2.5, repath)) {
                VillageManager.carry(level, v, b, carrying, 8);
                r.swing(InteractionHand.MAIN_HAND);
                loaded = false;
            }
        }
    }

    /** The merchant behind the stall's counter all day. */
    static final class Stall extends Goal {
        private final ResidentEntity r;
        private final int[] repath = {0};

        Stall(ResidentEntity r) {
            this.r = r;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private int ticks;

        /** Where each of the trades that make nothing themselves works: the stall, the sawmill, the map table. */
        private static BuildingType placeOf(Job j) {
            return j == Job.MERCHANT ? BuildingType.MARKET : j == Job.SAWYER ? BuildingType.SAWMILL : j == Job.SCOUT ? BuildingType.CARTOGRAPHER
                    : j == Job.SMITH ? BuildingType.SMITHY : j == Job.JOINER ? BuildingType.CARPENTER : j == Job.LOCKSMITH ? BuildingType.LOCKSMITH
                    : j == Job.WEAVER ? BuildingType.WEAVER : j == Job.SMELTER ? BuildingType.SMELTER : j == Job.GLASSBLOWER ? BuildingType.GLASSWORKS
                    : j == Job.SAILOR ? BuildingType.PIER : null;
        }

        private Building stall() {
            Village v = village(r);
            BuildingType t = placeOf(r.colonyJob());
            if (v == null || t == null) return null;
            for (Building b : v.buildings) if (b.type == t && b.standing()) return b;
            return null;
        }

        @Override
        public boolean canUse() {
            return placeOf(r.colonyJob()) != null && workHours(r) && stall() != null;
        }

        @Override
        public void tick() {
            Building b = stall();
            Village v = village(r);
            if (b == null || v == null) return;
            Job job = r.colonyJob();
            BlockPos spot = b.blueprint(v.wood).workSpot;
            if (job == Job.MERCHANT) {
                r.hold(new ItemStack(net.minecraft.world.item.Items.EMERALD));
                r.setActivity(act("trading"));
                if (walk(r, spot, 1.2, repath)) {
                    BlockPos front = b.origin.relative(b.front, 3);
                    r.getLookControl().setLookAt(front.getX() + 0.5, front.getY() + 1.5, front.getZ() + 0.5);
                }
                return;
            }
            if (job == Job.SMITH) {
                // at the anvil: hammer on iron, now and then
                r.hold(new ItemStack(net.minecraft.world.item.Items.IRON_INGOT));
                r.setActivity(act("smithing"));
                BlockPos anvil = b.blueprint(v.wood).frame.at(-3, 0, 1);
                if (walk(r, anvil.relative(b.front), 1.2, repath)) {
                    r.getLookControl().setLookAt(anvil.getX() + 0.5, anvil.getY() + 0.5, anvil.getZ() + 0.5);
                    if (++ticks % 40 == 0) {
                        r.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        r.level().playSound(null, anvil, net.minecraft.sounds.SoundEvents.ANVIL_USE,
                                net.minecraft.sounds.SoundSource.BLOCKS, 0.35F, 0.9F + r.getRandom().nextFloat() * 0.2F);
                    }
                }
                return;
            }
            if (job == Job.SAWYER) {
                // at the saw: logs in, planks out, with the sound of it
                r.hold(new ItemStack(net.minecraft.world.item.Items.OAK_LOG));
                r.setActivity(act("sawing"));
                if (walk(r, spot, 1.2, repath)) {
                    BlockPos saw = b.blueprint(v.wood).frame.at(1, 0, 3);
                    r.getLookControl().setLookAt(saw.getX() + 0.5, saw.getY() + 0.5, saw.getZ() + 0.5);
                    if (++ticks % 30 == 0) {
                        r.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        r.level().playSound(null, saw, net.minecraft.sounds.SoundEvents.UI_STONECUTTER_TAKE_RESULT,
                                net.minecraft.sounds.SoundSource.BLOCKS, 0.5F, 0.9F + r.getRandom().nextFloat() * 0.2F);
                    }
                }
                return;
            }
            if (job == Job.WEAVER || job == Job.SMELTER || job == Job.GLASSBLOWER) {
                // at the loom, the furnaces, the glass furnace: the work at the door
                r.hold(new ItemStack(job == Job.WEAVER ? net.minecraft.world.item.Items.STRING : job == Job.SMELTER ? net.minecraft.world.item.Items.RAW_IRON
                        : net.minecraft.world.item.Items.GLASS_BOTTLE));
                r.setActivity(act(job == Job.WEAVER ? "weaving" : job == Job.SMELTER ? "smelting" : "glassblowing"));
                if (walk(r, spot, 1.2, repath)) {
                    BlockPos bench = b.blueprint(v.wood).frame.at(1, 0, 3);
                    r.getLookControl().setLookAt(bench.getX() + 0.5, bench.getY() + 0.8, bench.getZ() + 0.5);
                    if (++ticks % 30 == 0) {
                        r.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        var sound = job == Job.WEAVER ? net.minecraft.sounds.SoundEvents.UI_LOOM_TAKE_RESULT
                                : job == Job.SMELTER ? net.minecraft.sounds.SoundEvents.BLASTFURNACE_FIRE_CRACKLE : net.minecraft.sounds.SoundEvents.FURNACE_FIRE_CRACKLE;
                        r.level().playSound(null, bench, sound, net.minecraft.sounds.SoundSource.BLOCKS, 0.5F, 0.9F + r.getRandom().nextFloat() * 0.2F);
                    }
                }
                return;
            }
            if (job == Job.SAILOR) {
                // on the jetty by the ships: looking out to sea, seeing to the ropes now and then
                r.hold(new ItemStack(net.minecraft.world.item.Items.SPYGLASS));
                r.setActivity(act("at_pier"));
                if (b.jetty == null) return;
                net.minecraft.core.Direction out = Harbour.out(b);
                int k = b.type.half + Math.max(1, b.jetty[1] - 2);
                BlockPos end = new BlockPos(b.origin.getX() + out.getStepX() * k, b.origin.getY(), b.origin.getZ() + out.getStepZ() * k);
                if (walk(r, end, 1.5, repath)) {
                    BlockPos sea = end.relative(out, 12);
                    r.getLookControl().setLookAt(sea.getX() + 0.5, sea.getY() + 1.0, sea.getZ() + 0.5);
                    if (++ticks % 80 == 0) r.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
                return;
            }
            if (job == Job.JOINER || job == Job.LOCKSMITH) {
                // at the bench (the joiner) or the anvil by the door (the locksmith), at the work
                boolean lock = job == Job.LOCKSMITH;
                r.hold(new ItemStack(lock ? net.minecraft.world.item.Items.IRON_CHAIN : net.minecraft.world.item.Items.OAK_PLANKS));
                r.setActivity(act(lock ? "metalwork" : "joinering"));
                if (walk(r, spot, 1.2, repath)) {
                    BlockPos bench = b.blueprint(v.wood).frame.at(1, 0, 3);
                    r.getLookControl().setLookAt(bench.getX() + 0.5, bench.getY() + 0.8, bench.getZ() + 0.5);
                    if (++ticks % 30 == 0) {
                        r.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        r.level().playSound(null, bench, lock ? net.minecraft.sounds.SoundEvents.ANVIL_USE : net.minecraft.sounds.SoundEvents.WOOD_HIT,
                                net.minecraft.sounds.SoundSource.BLOCKS, lock ? 0.3F : 0.6F, 0.9F + r.getRandom().nextFloat() * 0.3F);
                    }
                }
                return;
            }
            // the scout at home between expeditions: over the maps
            r.hold(new ItemStack(net.minecraft.world.item.Items.FILLED_MAP));
            r.setActivity(act("mapping"));
            BlockPos table = b.blueprint(v.wood).frame.at(0, 0, 0);
            if (walk(r, table, 1.5, repath)) {
                BlockPos t = b.blueprint(v.wood).frame.at(0, 0, -1);
                r.getLookControl().setLookAt(t.getX() + 0.5, t.getY() + 1.0, t.getZ() + 0.5);
            }
        }
    }

    /**
     * The village's land looked after in free time: someone with their work done for now fills a hole, dries a
     * puddle, eases a ledge, pulls weeds, takes down leaves left hanging.
     */
    static final class Tend extends Goal {
        private final ResidentEntity r;
        private final int[] repath = {0};
        private Tidy.Job job;
        private int timer, swings;

        Tend(ResidentEntity r) {
            this.r = r;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private boolean free() {
            Village v = village(r);
            Dweller d = dweller(r);
            if (v == null || d == null || !grownUp(r) || !workHours(r) || d.job == null || d.job.makes == null) return false;
            if (d.job == Job.WOODCUTTER && WorkGoal.clearing((ServerLevel) r.level(), v)) return false;
            return WorkGoal.ahead(v, d, r.dayTime()) || v.full(d.job.makes);
        }

        @Override
        public boolean canUse() {
            Village v = village(r);
            return v != null && free() && Tidy.any(v);
        }

        @Override
        public boolean canContinueToUse() {
            return job != null && workHours(r);
        }

        @Override
        public void start() {
            Village v = village(r);
            job = v == null ? null : Tidy.take((ServerLevel) r.level(), v, r.blockPosition());
            timer = 0;
            swings = 0;
            r.hold(new ItemStack(job == null ? net.minecraft.world.item.Items.WOODEN_HOE : switch (job.kind()) {
                case HOLE, LEDGE, PUDDLE, BUMP, PIT -> net.minecraft.world.item.Items.WOODEN_SHOVEL;
                case TREE, POST, DEBRIS -> net.minecraft.world.item.Items.STONE_AXE;
                default -> net.minecraft.world.item.Items.WOODEN_HOE;
            }));
        }

        @Override
        public void stop() {
            Village v = village(r);
            if (v != null && job != null) Tidy.release(v, job);
            job = null;
            r.hold(ItemStack.EMPTY);
        }

        @Override
        public void tick() {
            Village v = village(r);
            if (v == null || job == null) return;
            r.setActivity(act(job.kind().act()));
            BlockPos p = job.pos();
            if (!walk(r, p, 2.2, repath)) {
                // a spot that can't be walked to is left for another day
                if (++timer > 150) {
                    Tidy.drop(v, job);
                    job = null;
                }
                return;
            }
            r.getNavigation().stop();
            r.getLookControl().setLookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
            if (++timer % 12 == 0) {
                r.swing(InteractionHand.MAIN_HAND);
                ServerLevel level = (ServerLevel) r.level();
                BlockState st = level.getBlockState(p);
                if (!st.isAir()) {
                    level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, st),
                            p.getX() + 0.5, p.getY() + 0.7, p.getZ() + 0.5, 6, 0.3, 0.2, 0.3, 0.05);
                    level.playSound(null, p, st.getSoundType().getHitSound(), net.minecraft.sounds.SoundSource.BLOCKS, 0.5F, 1.0F);
                }
                if (++swings >= (job.kind() == Tidy.Kind.WEED ? 2 : job.kind() == Tidy.Kind.TREE ? 10 : 4)) {
                    Tidy.finish(level, v, job);
                    job = null;
                }
            }
        }
    }

    /** Children play about the middle of the village. */
    static final class Play extends Goal {
        private final ResidentEntity r;
        private final int[] repath = {0};
        private BlockPos to;
        private int wait;

        Play(ResidentEntity r) {
            this.r = r;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return r.colony() && !grownUp(r) && !night(r) && village(r) != null;
        }

        @Override
        public void tick() {
            Village v = village(r);
            if (v == null) return;
            r.setActivity(act("playing"));
            if (to == null || walk(r, to, 1.5, repath) && --wait <= 0) {
                // round their own home (the one next door now and then), not all of them in the middle
                Dweller d = dweller(r);
                Building home = d == null ? null : v.building(d.home);
                BlockPos around = home != null && home.standing() ? home.blueprint(v.wood).workSpot : v.center;
                to = near((ServerLevel) r.level(), around, 2 + r.getRandom().nextInt(6), r.getRandom());
                if (to == null) to = around;
                wait = 20 + r.getRandom().nextInt(60);
            }
        }
    }

    /** Evenings (and idle hours) by the fire, or by the well in a hamlet. */
    static final class Rest extends Goal {
        private final ResidentEntity r;
        private final int[] repath = {0};
        private BlockPos seat;

        Rest(ResidentEntity r) {
            this.r = r;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return r.colony() && grownUp(r) && village(r) != null;
        }

        /** Resting at home (by the door) rather than in the middle; where they look while sitting. */
        private boolean atHome;
        private BlockPos look;
        private int stay;

        @Override
        public void start() {
            pick();
            r.hold(ItemStack.EMPTY);
        }

        /**
         * Where to rest: by one's own door, most of the time. Of an evening, one in three goes to the fire (a
         * different few each day); and so does anyone without a home.
         */
        private void pick() {
            Village v = village(r);
            Dweller d = dweller(r);
            if (v == null) return;
            ServerLevel level = (ServerLevel) r.level();
            Building home = d == null ? null : v.building(d.home);
            int t = r.dayTime();
            long day = level.getOverworldClockTime() / 24000L;
            boolean evening = t >= 11000 && t < 12500;
            boolean social = evening && d != null && Math.floorMod(d.id + day, 3) == 0;
            atHome = home != null && home.standing() && !social;
            if (atHome) {
                BlockPos door = home.blueprint(v.wood).workSpot;
                seat = near(level, door, 2, r.getRandom());
                if (seat == null) seat = door;
                look = door;
            } else {
                double a = r.getRandom().nextDouble() * Math.PI * 2;
                int x = v.center.getX() + (int) Math.round(Math.cos(a) * 3.5), z = v.center.getZ() + (int) Math.round(Math.sin(a) * 3.5);
                seat = new BlockPos(x, PlotFinder.floorAt(level, x, z), z);
                look = v.center;
            }
            stay = 400 + r.getRandom().nextInt(600);
        }

        @Override
        public void tick() {
            Village v = village(r);
            if (v == null || seat == null) return;
            // now and then, up and about a little (and the evening can bring them out to the fire)
            if (--stay <= 0) pick();
            Dweller d = dweller(r);
            boolean full = d != null && d.job() != null && d.job().makes != null && v.full(d.job().makes);
            boolean fire = v.count(BuildingType.CAMPFIRE, true) > 0;
            r.setActivity(workHours(r) && full ? act("store_full")
                    : workHours(r) && d != null && d.job() != null && d.job().makes != null ? act("break_home")
                    : atHome ? act("resting_home")
                    : act(fire ? "resting_fire" : "resting_square"));
            if (walk(r, seat, 1.5, repath)) r.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + 0.5, look.getZ() + 0.5);
        }
    }
}
