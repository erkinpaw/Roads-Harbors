package org.webtrade.minecraftportsmod.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import org.webtrade.minecraftportsmod.economy.Good;
import org.webtrade.minecraftportsmod.economy.Specialization;
import org.webtrade.minecraftportsmod.registry.ModContent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a village on real, generated terrain: finds the exact shore, reads what the land is rich in, and puts up
 * the port office with a pier, a square with a bell, houses with beds, a workshop for the village's trade, fields
 * and pens, paths, and the first villagers. One building style everywhere; the wood is whatever grows around.
 */
final class VillageBuilder {

    private VillageBuilder() {
    }

    // ------------------------------------------------------------------ the shore

    /** @param ground the top land block by the water; {@code toWater} points at the water */
    record Spot(BlockPos ground, Direction toWater) {
    }

    private static int top(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private static boolean isWater(BlockState s) {
        return s.getFluidState().isSource() && s.getBlock() == Blocks.WATER;
    }

    private static int depth(ServerLevel level, int x, int y, int z) {
        int d = 0;
        while (d < 8 && isWater(level.getBlockState(new BlockPos(x, y - d, z)))) d++;
        return d;
    }

    /** Low land within {@code radius} of (px, pz) with open, boat-deep water right in front of it; null if none. */
    static Spot findSpot(ServerLevel level, int px, int pz, int radius) {
        int sea = level.getSeaLevel();
        Spot best = null;
        double bestScore = -1e9;
        for (int x = px - radius; x <= px + radius; x++) {
            for (int z = pz - radius; z <= pz + radius; z++) {
                int y = top(level, x, z);
                if (y < sea - 1 || y > sea + 3) continue;
                BlockState ground = level.getBlockState(new BlockPos(x, y, z));
                if (!ground.getFluidState().isEmpty() || ground.isAir() || ground.is(BlockTags.LEAVES) || ground.is(BlockTags.ICE)) continue;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    int first = -1;
                    for (int k = 1; k <= 3; k++) {
                        int wx = x + d.getStepX() * k, wz = z + d.getStepZ() * k;
                        if (isWater(level.getBlockState(new BlockPos(wx, sea - 1, wz))) && top(level, wx, wz) == sea - 1) {
                            first = k;
                            break;
                        }
                    }
                    if (first < 0) continue;
                    int open = 0, deep = 0;
                    for (int k = first; k < first + 12; k++) {
                        int wx = x + d.getStepX() * k, wz = z + d.getStepZ() * k;
                        if (top(level, wx, wz) != sea - 1 || !isWater(level.getBlockState(new BlockPos(wx, sea - 1, wz)))) continue;
                        open++;
                        if (depth(level, wx, sea - 1, wz) >= 2) deep++;
                    }
                    // open water (sea, lake) or at least a proper river
                    boolean wide = open >= 10 && deep >= 6;
                    if (!wide && (open < 5 || deep < 3)) continue;
                    // room to build behind the shore
                    int flat = 0;
                    Direction r = d.getClockWise();
                    for (int b = 3; b <= 15; b += 3) {
                        for (int l = -9; l <= 9; l += 3) {
                            int bx = x - d.getStepX() * b + r.getStepX() * l, bz = z - d.getStepZ() * b + r.getStepZ() * l;
                            int by = top(level, bx, bz);
                            if (Math.abs(by - y) <= 2 && level.getBlockState(new BlockPos(bx, by, bz)).getFluidState().isEmpty()) flat++;
                        }
                    }
                    // open land is better than a wood: count the trees behind the shore
                    int trees = 0;
                    for (int b = 4; b <= 16; b += 4) {
                        for (int l = -8; l <= 8; l += 4) {
                            int bx = x - d.getStepX() * b + r.getStepX() * l, bz = z - d.getStepZ() * b + r.getStepZ() * l;
                            int canopy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) - 1;
                            if (level.getBlockState(new BlockPos(bx, canopy, bz)).is(BlockTags.LEAVES)) trees++;
                        }
                    }
                    double score = flat - Math.hypot(x - px, z - pz) / 6.0 + deep * 0.3 - first + (wide ? 3 : 0) - trees * 0.6;
                    if (score > bestScore) {
                        bestScore = score;
                        best = new Spot(new BlockPos(x, y, z), d);
                    }
                }
            }
        }
        return best != null && bestScore > 14 ? best : null;
    }

    // ------------------------------------------------------------------ what the land offers

    /** Counts of what grows and lies around a spot. */
    static final class Survey {
        final Map<String, Integer> woods = new HashMap<>();
        int logs, samples, water, clay, sand, stone, flatGrass, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

        /** Wood species by how much of it grows here, most first. */
        List<String> woodsByAmount() {
            List<String> list = new ArrayList<>(woods.keySet());
            list.sort(Comparator.comparingInt((String w) -> -woods.get(w)));
            return list;
        }

        /** The wood to build with. */
        String buildingWood() {
            List<String> w = woodsByAmount();
            return w.isEmpty() ? "oak" : w.getFirst();
        }
    }

    static Survey survey(ServerLevel level, BlockPos center, int radius) {
        Survey s = new Survey();
        int sea = level.getSeaLevel();
        for (int x = center.getX() - radius; x <= center.getX() + radius; x += 2) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z += 2) {
                s.samples++;
                int y = top(level, x, z);
                // trunks stand on the ground: count them and step down to the soil
                while (y > sea - 8 && level.getBlockState(new BlockPos(x, y, z)).is(BlockTags.LOGS)) {
                    countLog(s, level.getBlockState(new BlockPos(x, y, z)));
                    y--;
                }
                BlockState st = level.getBlockState(new BlockPos(x, y, z));
                if (isWater(st)) {
                    s.water++;
                    for (int d = 1; d <= 8; d++) {
                        BlockState below = level.getBlockState(new BlockPos(x, y - d, z));
                        if (below.getFluidState().isEmpty()) {
                            if (below.is(Blocks.CLAY)) s.clay++;
                            if (below.is(Blocks.SAND)) s.sand++;
                            break;
                        }
                    }
                    continue;
                }
                s.minY = Math.min(s.minY, y);
                s.maxY = Math.max(s.maxY, y);
                if (st.is(Blocks.SAND) || st.is(Blocks.RED_SAND)) s.sand++;
                else if (st.is(Blocks.CLAY)) s.clay++;
                else if (st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.GRAVEL) || st.is(Blocks.CALCITE)) s.stone++;
                else if ((st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT)) && Math.abs(y - sea) <= 4) s.flatGrass++;
                // trees: logs between the canopy and the ground
                int canopy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                for (int ty = canopy; ty > y; ty--) {
                    BlockState t = level.getBlockState(new BlockPos(x, ty, z));
                    if (t.is(BlockTags.LOGS)) countLog(s, t);
                }
            }
        }
        return s;
    }

    private static void countLog(Survey s, BlockState t) {
        String id = BuiltInRegistries.BLOCK.getKey(t.getBlock()).getPath();
        if (!id.endsWith("_log")) return;
        String species = id.replace("stripped_", "").replace("_log", "");
        if (Good.byId(species + "_log") != null) {
            s.woods.merge(species, 1, Integer::sum);
            s.logs++;
        }
    }

    /** Picks the trade the land suggests; {@code nearby} counts specialisations of the closest villages. */
    static Specialization chooseSpec(Survey s, Map<Specialization, Integer> nearby, boolean river, RandomSource rnd) {
        double n = Math.max(1, s.samples);
        int relief = s.maxY == Integer.MIN_VALUE ? 0 : s.maxY - s.minY;
        Map<Specialization, Double> score = new EnumMap<>(Specialization.class);
        score.put(Specialization.LUMBER, s.logs / n * 4.0);
        score.put(Specialization.QUARRY, s.stone / n * 3.0 + relief / 60.0);
        score.put(Specialization.MINING, s.stone / n * 2.0 + relief / 40.0);
        score.put(Specialization.POTTERY, s.clay / n * 30.0 + s.sand / n * 1.2 + (river ? 0.15 : 0));
        score.put(Specialization.FARMING, s.flatGrass / n * 1.8);
        score.put(Specialization.FISHING, s.water / n * 1.1);
        Specialization best = Specialization.FISHING;
        double bestV = -1;
        for (var e : score.entrySet()) {
            double v = e.getValue() + rnd.nextDouble() * 0.35 - 0.3 * nearby.getOrDefault(e.getKey(), 0);
            if (v > bestV) {
                bestV = v;
                best = e.getKey();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ building

    /** What was built. */
    /** @param pierTip last deck block of the pier; {@code out} points along the pier, out to the water */
    record Built(BlockPos office, int houses, BlockPos pierTip, Direction out, BlockPos square,
                 org.webtrade.minecraftportsmod.village.VillageLayout layout) {
    }

    private static BlockState block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(id)).defaultBlockState();
    }

    private static BlockState wood(String wood, String part) {
        var b = BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(wood + "_" + part));
        return (b.isPresent() ? b.get() : BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("oak_" + part))).defaultBlockState();
    }

    /** Local coordinates of a building: x to the right, z to the front, y up from the floor level. */
    private record Frame(BlockPos origin, Direction front) {
        BlockPos at(int x, int y, int z) {
            Direction right = front.getClockWise();
            return origin.offset(front.getStepX() * z + right.getStepX() * x, y, front.getStepZ() * z + right.getStepZ() * x);
        }

        Direction right() {
            return front.getClockWise();
        }
    }

    private static final class Site {
        final ServerLevel level;
        final RandomSource rnd;
        final String wood;
        /** The y people stand on at the plot being built (each plot has its own, following the land). */
        int base;
        final int sea;
        /** Columns levelled so far, with their floor height; the land around them is sloped to meet them. */
        final Map<Long, Integer> prepared = new HashMap<>();

        Site(ServerLevel level, RandomSource rnd, String wood, int base) {
            this.level = level;
            this.rnd = rnd;
            this.wood = wood;
            this.base = base;
            this.sea = level.getSeaLevel();
        }

        void set(BlockPos p, BlockState s) {
            level.setBlock(p, s, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }

        /** Places a block and lets it connect to what is around (fences, panes, stairs). */
        void setShaped(BlockPos p, BlockState s) {
            set(p, s);
            set(p, Block.updateFromNeighbourShapes(s, level, p));
        }

        /** Levels a column: ground up to the floor, air above. */
        void prepare(int x, int z, BlockState surface, BlockState fill, int clear) {
            prepared.put(key(x, z), base);
            int t = top(level, x, z);
            for (int y = Math.min(t, base - 2); y <= base - 2; y++) {
                if (y > t || !level.getBlockState(new BlockPos(x, y, z)).isSolid()) set(new BlockPos(x, y, z), fill);
            }
            set(new BlockPos(x, base - 1, z), surface);
            for (int y = base; y <= base + clear; y++) {
                BlockPos p = new BlockPos(x, y, z);
                if (!level.getBlockState(p).isAir()) set(p, Blocks.AIR.defaultBlockState());
            }
        }

        static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        }

        /**
         * The floor height for a square plot of half-size {@code half} around {@code center}: the middle of the land
         * there, if the land is dry, loaded, not yet built on and no steeper than a small terrace; else null.
         */
        Integer plotFloor(BlockPos center, Direction front, int half) {
            List<Integer> heights = new ArrayList<>();
            Frame f = new Frame(center, front);
            for (int x = -half - 1; x <= half + 1; x++) {
                for (int z = -half - 1; z <= half + 1; z++) {
                    BlockPos p = f.at(x, 0, z);
                    if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) return null;
                    if (prepared.containsKey(key(p.getX(), p.getZ()))) return null;
                    int t = top(level, p.getX(), p.getZ());
                    BlockState st = level.getBlockState(new BlockPos(p.getX(), t, p.getZ()));
                    if (!st.getFluidState().isEmpty()) return null;
                    heights.add(t + 1);
                }
            }
            heights.sort(Integer::compare);
            int floor = heights.get(heights.size() / 2);
            if (floor < sea || floor > sea + 16) return null;
            if (floor - heights.getFirst() > 4 || heights.getLast() - floor > 5) return null;
            return floor;
        }
    }

    /** Builds a whole village in one go (tests; the world planner uses a {@link Session} spread over ticks). */
    static Built build(ServerLevel level, Spot spot, Specialization spec, Survey survey, int houseCount, RandomSource rnd) {
        Session session = new Session(level, spot, spec, survey, houseCount, rnd);
        while (!session.step()) {
            // keep going
        }
        return session.result();
    }

    /**
     * A village being built a piece at a time, so that no single server tick has to do it all: the clearing in
     * strips, then the wharf and square, then one building per step, then paths and the blending of slopes.
     */
    static final class Session {
        private static final int CLEARING_STRIPS = 6;

        private final Site site;
        private final Frame village, square;
        private final Specialization spec;
        private final Survey survey;
        private final int houseCount;
        private int stage;
        private final List<BlockPos> doors = new ArrayList<>(), beds = new ArrayList<>(), fields = new ArrayList<>(),
                workBlocks = new ArrayList<>();
        private BlockPos workshopAt, penAt, office, pierTip, stockyardAt;
        private int houses, fieldsBuilt;
        private boolean penDone, workshopDone, stockyardDone, noRoomForHouses;
        private final List<BlockPos> plots = new ArrayList<>(), piles = new ArrayList<>();
        private List<BlockPos> board = new ArrayList<>();
        private Built result;

        Session(ServerLevel level, Spot spot, Specialization spec, Survey survey, int houseCount, RandomSource rnd) {
            Direction f = spot.toWater();
            int base = Math.max(spot.ground().getY() + 1, level.getSeaLevel());
            this.site = new Site(level, rnd, survey.buildingWood(), base);
            this.village = new Frame(new BlockPos(spot.ground().getX(), base, spot.ground().getZ()), f);
            this.square = new Frame(village.at(0, 0, -11), f);
            this.spec = spec;
            this.survey = survey;
            this.houseCount = houseCount;
        }

        /** Does the next piece of work; true when the village is finished. */
        boolean step() {
            if (result != null) return true;
            int villageBase = village.origin().getY();
            if (stage < CLEARING_STRIPS) {
                clearing(site, square.at(0, 0, 0), 46, stage, CLEARING_STRIPS);
                stage++;
                return false;
            }
            if (stage == CLEARING_STRIPS) {
                site.base = villageBase;
                BlockPos[] pier = new BlockPos[1];
                office = wharfAndPier(site, village, pier);
                pierTip = pier[0];
                site.base = villageBase;
                square(site, square);
                board = priceBoard(site, square);
                stage++;
                return false;
            }
            // one building per step: workshop, houses, fields, the pen
            if (!workshopDone) {
                workshopDone = true;
                Frame plot = nextPlot(site, village, square, 4);
                if (plot != null) {
                    workshopAt = plot.origin();
                    plots.add(plot.origin());
                    doors.add(workshop(site, plot, spec, survey, workBlocks));
                }
                return false;
            }
            if (!stockyardDone) {
                stockyardDone = true;
                Frame yard = nextPlot(site, village, square, 4);
                if (yard != null) {
                    stockyardAt = yard.origin();
                    plots.add(yard.origin());
                    doors.add(stockyard(site, yard, piles));
                }
                return false;
            }
            if (houses < houseCount && !noRoomForHouses) {
                Frame h = nextPlot(site, village, square, 4);
                int r = 3;
                if (h == null) {
                    h = nextPlot(site, village, square, 3);   // no room for a cottage: a hut then
                    r = 2;
                }
                if (h == null) {
                    noRoomForHouses = true;
                } else {
                    plots.add(h.origin());
                    doors.add(house(site, h, houses, r, beds, workBlocks));
                    houses++;
                }
                return false;
            }
            int fieldCount = spec == Specialization.FARMING ? 2 : 1;
            if (fieldsBuilt < fieldCount) {
                Frame fl = nextPlot(site, village, square, 4);
                if (fl == null) {
                    fieldsBuilt = fieldCount;
                } else {
                    plots.add(fl.origin());
                    field(site, fl);
                    fields.add(fl.origin());
                    fieldsBuilt++;
                }
                return false;
            }
            if (spec == Specialization.FARMING && !penDone) {
                penDone = true;
                Frame pn = nextPlot(site, village, square, 4);
                if (pn != null) {
                    plots.add(pn.origin());
                    pen(site, pn);
                    penAt = pn.origin();
                }
                return false;
            }
            for (BlockPos door : doors) path(site, square.at(0, 0, 0), door);
            path(site, square.at(0, 0, 0), village.at(0, 0, -4));
            blend(site);
            var layout = new org.webtrade.minecraftportsmod.village.VillageLayout(square.at(0, 0, 0), office,
                    java.util.Optional.ofNullable(pierTip), java.util.Optional.ofNullable(workshopAt), workBlocks, fields,
                    java.util.Optional.ofNullable(penAt), beds, java.util.Optional.ofNullable(stockyardAt), piles, board, plots,
                    site.wood, village.origin(), village.front());
            result = new Built(office, houses, pierTip, village.front(), square.at(0, 0, 0), layout);
            return true;
        }

        Built result() {
            return result;
        }
    }

    /** The office on a plank wharf at the water's edge, and a pier on log piles out into deep water. */
    private static BlockPos wharfAndPier(Site s, Frame v, BlockPos[] tip) {
        BlockState planks = wood(s.wood, "planks");
        BlockState log = wood(s.wood, "log");
        for (int x = -3; x <= 3; x++) {
            for (int z = -5; z <= 0; z++) {
                BlockPos p = v.at(x, 0, z);
                s.prepare(p.getX(), p.getZ(), planks, Blocks.COBBLESTONE.defaultBlockState(), 6);
            }
        }
        BlockPos office = v.at(0, 0, -3);
        s.set(office, ModContent.PORT_OFFICE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, v.front().getOpposite()));
        for (int x : new int[]{-3, 3}) {
            BlockPos post = v.at(x, 0, 0);
            s.setShaped(post, wood(s.wood, "fence"));
            s.set(post.above(), block("lantern"));
        }
        // the pier: deck level with the shore, piles down to the bottom; a third of the water's width at most,
        // so it never closes a river
        int deckY = s.base - 1;
        int run = 0, firstWater = 0;
        for (int z = 1; z <= 40; z++) {
            BlockPos probe = v.at(0, 0, z);
            boolean water = isWater(s.level.getBlockState(new BlockPos(probe.getX(), s.sea - 1, probe.getZ())))
                    && top(s.level, probe.getX(), probe.getZ()) == s.sea - 1;
            if (water) {
                if (firstWater == 0) firstWater = z;
                run++;
            } else if (firstWater > 0) {
                break;
            }
        }
        int length = Math.max(firstWater, 1) - 1 + Math.max(2, Math.min(12, run / 3));
        tip[0] = v.at(0, 0, 1).atY(deckY);
        for (int z = 1; z <= length; z++) {
            tip[0] = v.at(0, 0, z).atY(deckY);
            for (int x = -1; x <= 1; x++) {
                BlockPos p = v.at(x, 0, z);
                s.set(new BlockPos(p.getX(), deckY, p.getZ()), planks);
                for (int y = s.base; y <= s.base + 3; y++) {
                    BlockPos a = new BlockPos(p.getX(), y, p.getZ());
                    if (!s.level.getBlockState(a).isAir()) s.set(a, Blocks.AIR.defaultBlockState());
                }
            }
            if (z % 4 == 0) {
                for (int x : new int[]{-2, 2}) {
                    BlockPos p = v.at(x, 0, z);
                    for (int y = deckY; y > deckY - 12; y--) {
                        BlockPos q = new BlockPos(p.getX(), y, p.getZ());
                        BlockState at = s.level.getBlockState(q);
                        if (y < deckY && at.getFluidState().isEmpty() && !at.isAir()) break;
                        s.set(q, log);
                    }
                    s.setShaped(new BlockPos(p.getX(), deckY + 1, p.getZ()), wood(s.wood, "fence"));
                    if (z % 8 == 0) s.set(new BlockPos(p.getX(), deckY + 2, p.getZ()), block("lantern"));
                }
            }
        }
        return office;
    }

    /**
     * Clears the village ground: trees (logs, leaves, vines, bamboo) and surface lava within {@code radius} of the
     * square. Leaves left hanging outside decay by themselves.
     */
    private static void clearing(Site s, BlockPos center, int radius, int strip, int strips) {
        BlockState air = Blocks.AIR.defaultBlockState();
        int width = (2 * radius + 1 + strips - 1) / strips;
        int fromX = center.getX() - radius + strip * width;
        int toX = Math.min(center.getX() + radius, fromX + width - 1);
        for (int x = fromX; x <= toX; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                if ((x - center.getX()) * (x - center.getX()) + (z - center.getZ()) * (z - center.getZ()) > radius * radius) continue;
                if (s.level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
                int ground = top(s.level, x, z);
                // a tree trunk or a bamboo stalk is not the ground: walk down through them
                while (ground > s.sea - 8 && isPlant(s.level.getBlockState(new BlockPos(x, ground, z)))) ground--;
                // vines hang beside the leaves of the next column, so look well above every column
                int canopy = Math.max(s.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), ground + 40);
                for (int y = ground + 1; y <= canopy; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState st = s.level.getBlockState(p);
                    if (st.isAir()) continue;
                    if (st.is(BlockTags.LOGS) || st.is(Blocks.BAMBOO) || st.is(Blocks.COCOA) || st.is(Blocks.BEE_NEST)
                            || st.is(Blocks.MANGROVE_ROOTS) || st.is(Blocks.MOSS_CARPET)
                            || st.is(BlockTags.REPLACEABLE_BY_TREES) && st.getFluidState().isEmpty()) {
                        s.set(p, air);
                    }
                }
                for (int y = ground - 6; y <= ground + 1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (s.level.getBlockState(p).is(Blocks.LAVA)) {
                        boolean surface = s.level.getBlockState(p.above()).isAir();
                        s.set(p, surface ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
    }

    /** Grows on the ground rather than being it. */
    private static boolean isPlant(BlockState st) {
        return st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || st.is(Blocks.BAMBOO) || st.is(Blocks.CACTUS)
                || st.is(Blocks.MUSHROOM_STEM) || st.is(Blocks.BROWN_MUSHROOM_BLOCK) || st.is(Blocks.RED_MUSHROOM_BLOCK)
                || st.is(Blocks.MANGROVE_ROOTS) || st.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }

    /** Slopes the land around levelled plots to meet them, instead of leaving cliffs and pits. */
    private static void blend(Site s) {
        Map<Long, Integer> floors = new HashMap<>(s.prepared);
        java.util.Set<Long> done = new java.util.HashSet<>(s.prepared.keySet());
        java.util.List<Long> layer = new ArrayList<>(s.prepared.keySet());
        for (int d = 1; d <= 3; d++) {
            java.util.List<Long> next = new ArrayList<>();
            for (long k : layer) {
                int x = (int) (k >> 32), z = (int) k;
                for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    long nk = Site.key(x + o[0], z + o[1]);
                    if (done.add(nk)) {
                        next.add(nk);
                        floors.put(nk, floors.get(k));
                    }
                }
            }
            for (long k : next) {
                int x = (int) (k >> 32), z = (int) k;
                if (s.level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
                int t = top(s.level, x, z);
                BlockPos tp = new BlockPos(x, t, z);
                BlockState surface = s.level.getBlockState(tp);
                if (!surface.getFluidState().isEmpty() || surface.is(BlockTags.LOGS) || !surface.isSolid()) continue;
                if (surface.is(Blocks.DIRT) || surface.is(Blocks.DIRT_PATH)) surface = Blocks.GRASS_BLOCK.defaultBlockState();
                int floor = floors.get(k) - 1;
                if (t > floor + d) {
                    for (int y = t; y > floor + d; y--) s.set(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    s.set(new BlockPos(x, floor + d, z), surface);
                } else if (t < floor - d) {
                    for (int y = t; y < floor - d; y++) s.set(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState());
                    s.set(new BlockPos(x, floor - d, z), surface);
                }
            }
            layer = next;
        }
    }

    /** A paved square with the village bell and lamp posts. */
    private static void square(Site s, Frame sq) {
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                BlockPos p = sq.at(x, 0, z);
                boolean edge = Math.abs(x) == 4 || Math.abs(z) == 4;
                s.prepare(p.getX(), p.getZ(), edge ? block("dirt_path") : block("cobblestone"), Blocks.DIRT.defaultBlockState(), 7);
            }
        }
        s.set(sq.at(0, -1, 0), block("stone_bricks"));
        s.set(sq.at(0, 0, 0), block("bell").setValue(BlockStateProperties.BELL_ATTACHMENT, BellAttachType.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, sq.front()));
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
            BlockPos p = sq.at(c[0], 0, c[1]);
            s.setShaped(p, wood(s.wood, "fence"));
            s.setShaped(p.above(), wood(s.wood, "fence"));
            s.set(p.above(2), block("lantern"));
        }
    }

    /**
     * The price board at the back of the square: a plank wall with a row of signs facing the square (the first one
     * for the title). Returns the sign positions.
     */
    private static List<BlockPos> priceBoard(Site s, Frame sq) {
        List<BlockPos> signs = new ArrayList<>();
        BlockState planks = wood(s.wood, "planks"), log = wood(s.wood, "log");
        for (int x = -3; x <= 3; x++) {
            BlockPos p = sq.at(x, 0, -5);
            s.prepare(p.getX(), p.getZ(), planks, Blocks.DIRT.defaultBlockState(), 5);
            for (int y = 0; y <= 2; y++) s.set(sq.at(x, y, -5), Math.abs(x) == 3 ? log : planks);
            s.set(sq.at(x, 3, -5), wood(s.wood, "slab").setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));
        }
        for (int x = -2; x <= 2; x++) {
            BlockPos sign = sq.at(x, 1, -4);
            s.set(sign, wood(s.wood, "wall_sign").setValue(BlockStateProperties.HORIZONTAL_FACING, sq.front()));
            signs.add(sign);
        }
        return signs;
    }

    /**
     * The stock yard: a fenced yard whose piles (logs, crates, bales, ores...) grow and shrink with the village's
     * stores. Adds the pile spots (ground level) and returns the gate.
     */
    private static BlockPos stockyard(Site s, Frame f, List<BlockPos> piles) {
        s.base = f.origin().getY();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                BlockPos p = f.at(x, 0, z);
                boolean edge = Math.abs(x) == 4 || Math.abs(z) == 4;
                s.prepare(p.getX(), p.getZ(), edge ? block("dirt_path") : block("coarse_dirt"), Blocks.DIRT.defaultBlockState(), 6);
            }
        }
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.abs(x) != 4 && Math.abs(z) != 4) continue;
                BlockPos p = f.at(x, 0, z);
                if (z == 4 && Math.abs(x) <= 1) continue;   // the way in, facing the square
                s.setShaped(p, wood(s.wood, "fence"));
            }
        }
        for (int[] c : new int[][]{{-4, 4}, {4, 4}}) s.set(f.at(c[0], 1, c[1]), block("lantern"));
        for (int z : new int[]{-2, 0, 2}) {
            for (int x : new int[]{-3, -1, 1, 3}) piles.add(f.at(x, 0, z));
        }
        return f.at(0, 0, 5);
    }

    /** What a growing village adds: its new layout, or null if there was no room. */
    static org.webtrade.minecraftportsmod.village.VillageLayout growHouse(ServerLevel level,
            org.webtrade.minecraftportsmod.village.VillageLayout layout, int index, RandomSource rnd) {
        if (!layout.canGrow()) return null;
        Site site = new Site(level, rnd, layout.wood(), layout.square().getY());
        // everything already built is off limits
        java.util.function.BiConsumer<BlockPos, Integer> reserve = (c, half) -> {
            for (int x = -half; x <= half; x++) {
                for (int z = -half; z <= half; z++) site.prepared.put(Site.key(c.getX() + x, c.getZ() + z), c.getY());
            }
        };
        for (BlockPos p : layout.plots()) reserve.accept(p, 5);
        reserve.accept(layout.square(), 6);
        reserve.accept(layout.office(), 5);
        Frame village = new Frame(layout.origin(), layout.front());
        Frame square = new Frame(layout.square(), layout.front());
        Frame h = nextPlot(site, village, square, 4);
        int r = 3;
        if (h == null) {
            h = nextPlot(site, village, square, 3);
            r = 2;
        }
        if (h == null) return null;
        site.prepared.clear();
        List<BlockPos> beds = new ArrayList<>(), work = new ArrayList<>();
        BlockPos door = house(site, h, index, r, beds, work);
        path(site, layout.square(), door);
        blend(site);
        return layout.withHouse(h.origin(), beds, work);
    }

    /** The nearest free plot of half-size {@code half} around the square, facing it, at its own floor height. */
    private static Frame nextPlot(Site s, Frame village, Frame square, int half) {
        BlockPos c = square.at(0, 0, 0);
        for (int ring = 12; ring <= 50; ring += 3) {
            int steps = Math.max(12, ring * 2);
            for (int a = 0; a < steps; a++) {
                double ang = a * Math.PI * 2 / steps + ring * 0.37;
                int x = (int) Math.round(Math.cos(ang) * ring), z = (int) Math.round(Math.sin(ang) * ring);
                BlockPos pos = square.at(x, 0, z);
                // stay inland of the wharf
                BlockPos rel = pos.subtract(village.at(0, 0, 0));
                int forward = rel.getX() * village.front().getStepX() + rel.getZ() * village.front().getStepZ();
                if (forward > -6) continue;
                Direction facing = Direction.getApproximateNearest(c.getX() - pos.getX(), 0, c.getZ() - pos.getZ());
                Integer floor = s.plotFloor(pos, facing, half);
                if (floor == null) continue;
                // keep a lane between buildings
                if (s.plotFloor(pos, facing, half + 1) == null && half >= 4) continue;
                return new Frame(new BlockPos(pos.getX(), floor, pos.getZ()), facing);
            }
        }
        return null;
    }

    /**
     * A cottage ({@code r} = 3, two beds) or a hut ({@code r} = 2, one bed): log frame, plank walls, glass windows,
     * a gable roof. Returns the door step; adds its beds (head blocks) and work block.
     */
    private static BlockPos house(Site s, Frame h, int index, int r, List<BlockPos> beds, List<BlockPos> workBlocks) {
        s.base = h.origin().getY();
        BlockState planks = wood(s.wood, "planks"), log = wood(s.wood, "log"), stairs = wood(s.wood, "stairs");
        for (int x = -r - 1; x <= r + 1; x++) {
            for (int z = -r - 1; z <= r + 1; z++) {
                BlockPos p = h.at(x, 0, z);
                boolean inside = Math.abs(x) <= r && Math.abs(z) <= r;
                s.prepare(p.getX(), p.getZ(), inside ? planks : block("grass_block"), Blocks.DIRT.defaultBlockState(), 9);
            }
        }
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (Math.abs(x) != r && Math.abs(z) != r) continue;
                boolean corner = Math.abs(x) == r && Math.abs(z) == r;
                for (int y = 0; y <= 2; y++) {
                    BlockPos p = h.at(x, y, z);
                    if (corner) s.set(p, log);
                    else if (y == 1 && (x == 0 || z == 0) && !(z == r && x == 0)) s.setShaped(p, block("glass_pane"));
                    else s.set(p, planks);
                }
            }
        }
        // door
        Direction out = h.front();
        s.set(h.at(0, 0, r), wood(s.wood, "door").setValue(BlockStateProperties.HORIZONTAL_FACING, out.getOpposite())
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        s.set(h.at(0, 1, r), wood(s.wood, "door").setValue(BlockStateProperties.HORIZONTAL_FACING, out.getOpposite())
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        // gable roof, ridge running left-right
        for (int x = -r - 1; x <= r + 1; x++) {
            for (int z = -r - 1; z <= r + 1; z++) {
                int y = r + 3 - Math.abs(z);
                BlockPos p = h.at(x, y, z);
                if (z == 0) {
                    s.set(p, planks);
                } else {
                    Direction up = z > 0 ? h.front().getOpposite() : h.front();
                    s.set(p, stairs.setValue(BlockStateProperties.HORIZONTAL_FACING, up).setValue(BlockStateProperties.HALF, Half.BOTTOM));
                }
            }
        }
        // gable ends
        for (int x : new int[]{-r, r}) {
            for (int z = -(r - 1); z <= r - 1; z++) {
                for (int y = 3; y < r + 3 - Math.abs(z); y++) s.set(h.at(x, y, z), planks);
            }
        }
        // inside: beds at the back, a chest, a lamp, something to work at
        Direction head = h.front().getOpposite();
        int[] bedX = r == 3 ? new int[]{-2, 2} : new int[]{-1};
        for (int x : bedX) {
            s.set(h.at(x, 0, -(r - 1)), block("red_bed").setValue(BlockStateProperties.HORIZONTAL_FACING, head).setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
            s.set(h.at(x, 0, -(r - 1) + 1), block("red_bed").setValue(BlockStateProperties.HORIZONTAL_FACING, head).setValue(BlockStateProperties.BED_PART, BedPart.FOOT));
            beds.add(h.at(x, 0, -(r - 1)));
        }
        s.set(h.at(r - 1, 0, r - 1), block("chest").setValue(BlockStateProperties.HORIZONTAL_FACING, h.right().getOpposite()));
        if (r == 3) s.set(h.at(-2, 0, 2), block("lantern"));
        String[] jobs = {"crafting_table", "loom", "barrel", "smoker", "cartography_table", "composter"};
        BlockPos work = r == 3 ? h.at(0, 0, -2) : h.at(1, 0, -1);
        s.set(work, block(jobs[index % jobs.length]));
        return h.at(0, 0, r + 2);
    }

    /** An open workshop for the village's trade. Returns its front step. */
    private static BlockPos workshop(Site s, Frame w, Specialization spec, Survey survey, List<BlockPos> workBlocks) {
        s.base = w.origin().getY();
        BlockState planks = wood(s.wood, "planks"), log = wood(s.wood, "log");
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                BlockPos p = w.at(x, 0, z);
                s.prepare(p.getX(), p.getZ(), Math.abs(x) <= 3 && Math.abs(z) <= 3 ? block("cobblestone") : block("dirt_path"),
                        Blocks.DIRT.defaultBlockState(), 7);
            }
        }
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}, {0, -3}}) {
            for (int y = 0; y <= 2; y++) s.set(w.at(c[0], y, c[1]), log);
        }
        for (int x = -2; x <= 2; x++) {
            if (x == 0) continue;
            for (int y = 0; y <= 1; y++) s.set(w.at(x, y, -3), planks);
        }
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                s.set(w.at(x, 3, z), wood(s.wood, "slab").setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));
            }
        }
        List<String> props = switch (spec) {
            case LUMBER -> List.of("stonecutter", "fletching_table", "chest");
            case MINING -> List.of("blast_furnace", "furnace", "smithing_table", "anvil", "grindstone");
            case POTTERY -> List.of("furnace", "furnace", "decorated_pot", "decorated_pot", "flower_pot");
            case FISHING -> List.of("barrel", "barrel", "barrel", "smoker");
            case FARMING -> List.of("composter", "composter", "hay_block", "hay_block", "smoker");
            case QUARRY -> List.of("stonecutter", "stonecutter", "chest");
        };
        int i = 0;
        for (String id : props) {
            BlockState st = block(id);
            if (st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) st = st.setValue(BlockStateProperties.HORIZONTAL_FACING, w.front());
            s.set(w.at(-2 + i, 0, -2), st);
            workBlocks.add(w.at(-2 + i, 0, -2));
            i++;
        }
        // piles of the village's goods along the sides
        List<BlockState> piles = new ArrayList<>();
        switch (spec) {
            case LUMBER -> {
                List<String> woods = survey.woodsByAmount();
                if (woods.isEmpty()) woods = List.of(s.wood);
                for (String wd : woods.subList(0, Math.min(3, woods.size()))) {
                    piles.add(wood(wd, "log").setValue(BlockStateProperties.AXIS, w.front().getAxis()));
                }
            }
            case MINING -> piles.addAll(List.of(block("coal_ore"), block("iron_ore"), block("copper_ore"), block("cobblestone")));
            case POTTERY -> piles.addAll(List.of(block("clay"), block("bricks"), block("terracotta"), block("sand")));
            case FISHING -> piles.addAll(List.of(block("barrel"), block("dried_kelp_block")));
            case FARMING -> piles.addAll(List.of(block("hay_block"), block("pumpkin")));
            case QUARRY -> piles.addAll(List.of(block("stone"), block("granite"), block("diorite"), block("andesite")));
        }
        for (int z = -1; z <= 1; z++) {
            for (int side : new int[]{-2, 2}) {
                BlockState pile = piles.get(s.rnd.nextInt(piles.size()));
                s.set(w.at(side, 0, z), pile);
                if (s.rnd.nextBoolean()) s.set(w.at(side, 1, z), pile);
            }
        }
        return w.at(0, 0, 5);
    }

    /** A wheat field around a water channel, with a composter. */
    private static void field(Site s, Frame f) {
        s.base = f.origin().getY();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                BlockPos p = f.at(x, 0, z);
                boolean border = Math.abs(x) == 4 || Math.abs(z) == 4;
                boolean channel = x == 0 && !border;
                s.prepare(p.getX(), p.getZ(), border ? wood(s.wood, "log").setValue(BlockStateProperties.AXIS,
                        Math.abs(x) == 4 ? f.front().getAxis() : f.right().getAxis())
                        : channel ? Blocks.WATER.defaultBlockState() : block("farmland").setValue(BlockStateProperties.MOISTURE, 7),
                        Blocks.DIRT.defaultBlockState(), 5);
                if (!border && !channel) {
                    s.set(p, block("wheat").setValue(BlockStateProperties.AGE_7, 2 + s.rnd.nextInt(6)));
                }
            }
        }
        s.set(f.at(4, 0, 4), block("composter"));
    }

    /** A fenced pen with a few sheep. */
    private static void pen(Site s, Frame f) {
        s.base = f.origin().getY();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                BlockPos p = f.at(x, 0, z);
                s.prepare(p.getX(), p.getZ(), block("grass_block"), Blocks.DIRT.defaultBlockState(), 5);
            }
        }
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.abs(x) != 4 && Math.abs(z) != 4) continue;
                if (z == 4 && x == 0) {
                    s.set(f.at(x, 0, z), wood(s.wood, "fence_gate").setValue(BlockStateProperties.HORIZONTAL_FACING, f.front()));
                } else {
                    s.setShaped(f.at(x, 0, z), wood(s.wood, "fence"));
                }
            }
        }
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.abs(x) == 4 || Math.abs(z) == 4) {
                    BlockPos p = f.at(x, 0, z);
                    s.set(p, Block.updateFromNeighbourShapes(s.level.getBlockState(p), s.level, p));
                }
            }
        }
        for (int i = 0; i < 4; i++) spawn(s, "sheep", f.at(s.rnd.nextInt(5) - 2, 0, s.rnd.nextInt(5) - 2));
    }

    /** A dirt path from one point to another (grass and dirt only; everything else is left as is). */
    private static void path(Site s, BlockPos from, BlockPos to) {
        int x = from.getX(), z = from.getZ();
        while (x != to.getX() || z != to.getZ()) {
            if (x != to.getX()) x += Integer.signum(to.getX() - x);
            else z += Integer.signum(to.getZ() - z);
            for (int w = 0; w <= 1; w++) {
                int px = x + (x == to.getX() ? w : 0), pz = z + (x == to.getX() ? 0 : w);
                int t = top(s.level, px, pz);
                BlockPos p = new BlockPos(px, t, pz);
                BlockState st = s.level.getBlockState(p);
                if ((st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.PODZOL))
                        && s.level.getBlockState(p.above()).isAir()) {
                    s.set(p, block("dirt_path"));
                } else if ((st.is(BlockTags.FLOWERS) || st.is(Blocks.SHORT_GRASS) || st.is(Blocks.TALL_GRASS))) {
                    s.set(p, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static void spawn(Site s, String type, BlockPos at) {
        var t = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(type));
        Entity e = t.create(s.level, EntitySpawnReason.STRUCTURE);
        if (e == null) return;
        e.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, s.rnd.nextFloat() * 360, 0);
        if (e instanceof net.minecraft.world.entity.Mob m) m.setPersistenceRequired();
        s.level.addFreshEntity(e);
    }
}
