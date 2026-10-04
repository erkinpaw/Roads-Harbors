package org.webtrade.minecraftportsmod.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Islands raised out of the open sea where a world has none to settle near the spawn: a low island of grass with a
 * sandy strand all round, a rocky knoll on one side, woods here and there, its shore sloping away under the water.
 * Its shape comes from its middle, size and seed alone, so what the generator would say of its columns (for land
 * nobody has loaded yet: where a village plans its plots, where the ships' ways go) is answered from that shape too.
 */
public final class RaisedIslands {

    private RaisedIslands() {
    }

    /** An island: its middle, its size (the mean distance to the shore) and its seed. */
    public record Isle(int x, int z, int r, long seed) {
        /** The distance from the middle to the shore going the way {@code a}. */
        double edge(double a) {
            double s1 = (seed & 0xFF) / 40.0, s2 = ((seed >> 8) & 0xFF) / 40.0, s3 = ((seed >> 16) & 0xFF) / 40.0;
            return r * (1 + 0.13 * Math.sin(3 * a + s1) + 0.08 * Math.sin(5 * a + s2) + 0.05 * Math.sin(7 * a + s3));
        }

        /** The middle of the rocky knoll on the island. */
        int[] knoll() {
            double a = ((seed >> 24) & 0xFF) / 40.0;
            return new int[]{x + (int) Math.round(Math.cos(a) * r * 0.45), z + (int) Math.round(Math.sin(a) * r * 0.45)};
        }
    }

    /** The shore slopes away under the water this far out from the strand. */
    static final int SKIRT = 14;
    /** Size of a raised island (the mean distance from its middle to its shore). */
    public static final int SIZE = 64;
    private static final int KNOLL = 16;
    private static final int FLAGS = Block.UPDATE_CLIENTS;

    /** The raised islands of the world being played (the plan's sites that have one). */
    static final List<Isle> ISLES = new CopyOnWriteArrayList<>();

    static void clear() {
        ISLES.clear();
    }

    /**
     * The floor (the first free block above the ground) of a column of a raised island, or null if the column is no
     * part of one. Under the sea level: water (the island's strand going down under the waves).
     */
    public static Integer floor(int x, int z, int sea) {
        for (Isle i : ISLES) {
            double dx = x - i.x, dz = z - i.z, d = Math.hypot(dx, dz);
            if (d > i.r * 1.3 + SKIRT) continue;
            double e = i.edge(Math.atan2(dz, dx));
            if (d > e + SKIRT) continue;
            if (d > e) return sea - 1 - (int) Math.round((d - e) / SKIRT * 6);
            double k = d / e;
            int h = sea + 1 + (int) Math.round((1 - k * k) * 5);
            int[] kn = i.knoll();
            double kd = Math.hypot(x - kn[0], z - kn[1]);
            if (kd < KNOLL) h += (int) Math.round((1 - kd / KNOLL) * 9);
            return h;
        }
        return null;
    }

    /** Is the column dry land of a raised island? */
    public static boolean land(int x, int z, int sea) {
        Integer f = floor(x, z, sea);
        return f != null && f > sea;
    }

    /** Is the column on the knoll's rock (bare stone on top)? */
    static boolean rock(Isle i, int x, int z) {
        int[] kn = i.knoll();
        return Math.hypot(x - kn[0], z - kn[1]) < KNOLL * 0.7;
    }

    /** Rows of the island (from its bottom edge) raised so far by {@link #raise}, and the rows in all. */
    static int rows(Isle i) {
        return 2 * ((int) Math.ceil(i.r * 1.3) + SKIRT) + 1;
    }

    /**
     * Raises rows {@code from} to {@code to} (exclusive) of an island (its chunks loaded): ground filled up from the
     * sea floor, a strand of sand, grass with trees on it, the knoll's stone; the shore under the water sloped out.
     */
    static void raise(ServerLevel level, Isle i, int from, int to) {
        int sea = level.getSeaLevel(), half = (int) Math.ceil(i.r * 1.3) + SKIRT;
        RandomSource rnd = RandomSource.create(i.seed ^ from * 0x9E3779B97F4A7C15L);
        BlockState stone = Blocks.STONE.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), grass = Blocks.GRASS_BLOCK.defaultBlockState(),
                sand = Blocks.SAND.defaultBlockState(), sandstone = Blocks.SANDSTONE.defaultBlockState(), gravel = Blocks.GRAVEL.defaultBlockState();
        for (int row = from; row < to && row < rows(i); row++) {
            int z = i.z - half + row;
            for (int x = i.x - half; x <= i.x + half; x++) {
                Integer f = floor(x, z, sea);
                if (f == null) continue;
                int seabed = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
                int top = f - 1;
                double dx = x - i.x, dz = z - i.z;
                double e = i.edge(Math.atan2(dz, dx)), d = Math.hypot(dx, dz);
                if (top < sea) {
                    // (ice on the water round it, and icebergs, melted away)
                    for (int y = sea - 1; y <= sea + 40; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState st = level.getBlockState(p);
                        if (st.is(BlockTags.ICE) || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.SNOW)) {
                            level.setBlock(p, y == sea - 1 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), FLAGS);
                        }
                    }
                    // the shore under the water: sand heaped up where the floor lies deeper than the slope
                    for (int y = seabed + 1; y <= top; y++) level.setBlock(new BlockPos(x, y, z), y == top ? sand : sandstone, FLAGS);
                    continue;
                }
                boolean strand = d > e - 5 && top <= sea + 1, rocky = rock(i, x, z);
                for (int y = Math.min(seabed + 1, top); y <= top; y++) {
                    BlockState s = y == top ? (rocky ? stone : strand ? sand : grass)
                            : y >= top - 3 ? (rocky ? stone : strand ? sand : dirt) : strand && y >= top - 5 ? sandstone : stone;
                    level.setBlock(new BlockPos(x, y, z), s, FLAGS);
                }
                // nothing of the sea left over the land (its water, ice, an iceberg; the island's own trees kept)
                for (int y = top + 1; y <= sea + 40; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState st = level.getBlockState(p);
                    if (st.isAir() || st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) continue;
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
                }
                if (rocky && rnd.nextInt(9) == 0) level.setBlock(new BlockPos(x, top, z), gravel, FLAGS);
                // woods here and there (more of them inland), a little grass
                if (!strand && !rocky) {
                    double inland = 1 - d / e;
                    if (rnd.nextDouble() < 0.012 + 0.03 * inland && Math.floorMod(x * 31 + z * 17, 3) == 0) tree(level, new BlockPos(x, top + 1, z), rnd);
                    else if (rnd.nextInt(5) == 0) level.setBlock(new BlockPos(x, top + 1, z), Blocks.SHORT_GRASS.defaultBlockState(), FLAGS);
                }
            }
        }
    }

    /** A tree: an oak (now and then a birch), a trunk and a round crown. */
    private static void tree(ServerLevel level, BlockPos foot, RandomSource rnd) {
        boolean birch = rnd.nextInt(4) == 0;
        BlockState log = (birch ? Blocks.BIRCH_LOG : Blocks.OAK_LOG).defaultBlockState();
        BlockState leaves = (birch ? Blocks.BIRCH_LEAVES : Blocks.OAK_LEAVES).defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
        int h = 4 + rnd.nextInt(3);
        for (int y = -2; y <= 1; y++) {
            int r = y >= 0 ? 1 : 2;
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) == r && Math.abs(z) == r && (y >= 0 || rnd.nextBoolean())) continue;
                    BlockPos p = foot.offset(x, h + y, z);
                    if (level.getBlockState(p).isAir()) level.setBlock(p, leaves, FLAGS);
                }
            }
        }
        for (int y = 0; y < h; y++) {
            BlockPos p = foot.above(y);
            if (level.getBlockState(p).isAir() || level.getBlockState(p).is(BlockTags.LEAVES) || level.getBlockState(p).canBeReplaced()) {
                level.setBlock(p, log, FLAGS);
            }
        }
    }
}
