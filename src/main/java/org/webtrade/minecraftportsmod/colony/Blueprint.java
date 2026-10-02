package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The blocks of one building, in the order they are put up: the ground is levelled first, then the building grows
 * from the floor to the roof. The same blueprint (it only depends on the type, the plot and the village's wood)
 * is read backwards to pull the building down again.
 */
public final class Blueprint {

    /** Local coordinates of a plot: x to the right, z to the front (the door side), y up from the floor. */
    public record Frame(BlockPos origin, Direction front) {
        public BlockPos at(int x, int y, int z) {
            Direction right = front.getClockWise();
            return origin.offset(front.getStepX() * z + right.getStepX() * x, y, front.getStepZ() * z + right.getStepZ() * x);
        }

        public Direction right() {
            return front.getClockWise();
        }
    }

    /** @param shaped connect to the neighbours once placed (fences, panes) */
    public record Piece(BlockPos pos, BlockState state, boolean shaped) {
    }

    public final BuildingType type;
    public final Frame frame;
    /** Ground columns (x, z) and the surface block laid at floor level - 1. */
    public final Map<Long, BlockState> ground = new LinkedHashMap<>();
    public final List<Piece> pieces = new ArrayList<>();
    /** Head halves of the beds. */
    public final List<BlockPos> beds = new ArrayList<>();
    /** Where people stand to work at it (the field's middle, the store's counter...). */
    public BlockPos workSpot;
    /** The construction post: just outside the plot's front right corner. */
    public final BlockPos post;
    /** How many pieces stand at each level (index 1..3): the additions of a level come after the ones before. */
    private final int[] upTo = new int[4];

    private final String wood;

    private Blueprint(BuildingType type, Frame frame, String wood) {
        this.type = type;
        this.frame = frame;
        this.wood = wood;
        this.post = frame.at(type.half - 1, 0, type.half + 1);
        this.workSpot = frame.at(0, 0, type.half + 1);
    }

    public static Blueprint of(BuildingType type, Frame frame, String wood, long seed, Crop crop) {
        return of(type, frame, wood, seed, crop, 1);
    }

    /** The blueprint of a building grown to {@code level}: the building itself, then each level's additions. */
    public static Blueprint of(BuildingType type, Frame frame, String wood, long seed, Crop crop, int level) {
        Blueprint b = new Blueprint(type, frame, wood);
        RandomSource rnd = RandomSource.create(seed);
        switch (type) {
            case CAMPFIRE -> b.campfire();
            case SQUARE -> b.square(false);
            case WELL -> b.square(true);
            case TENT -> b.tent(rnd);
            case HUT -> b.hut();
            case HOUSE -> b.house();
            case HOUSE_TALL -> b.tallHouse(false);
            case STONE_HOUSE -> b.stoneHouse();
            case STONE_HOUSE_TALL -> b.tallHouse(true);
            case STOREHOUSE -> b.storehouse();
            case STOREHOUSE_2 -> b.storehouse2();
            case MINE_HOUSE -> b.mineHouse();
            case SMITHY -> b.smithy();
            case WOOD_HUT -> b.woodHut();
            case FISH_HUT -> b.fishHut();
            case SAWMILL -> b.sawmill();
            case CARPENTER -> b.carpenter();
            case CARTOGRAPHER -> b.cartographer();
            case FARM -> b.farm();
            case FIELD -> b.field(rnd, crop);
            case MARKET -> b.market(rnd);
            case FARMYARD -> b.farmyard();
            case LOCKSMITH -> b.locksmith();
            case WEAVER, SMELTER, GLASSWORKS -> b.craftHouse();
        }
        b.upTo[1] = b.pieces.size();
        // the additions draw on their own dice: the building under them stays the same
        RandomSource extra = RandomSource.create(seed ^ 0x2545F4914F6CDD1DL);
        int top = Math.max(1, Math.min(level, type.maxLevel));
        for (int l = 2; l <= 3; l++) {
            if (l <= top) b.extras(l, extra);
            b.upTo[l] = b.pieces.size();
        }
        return b;
    }

    /** The number of pieces that stand at a level (the rest are the additions of the levels above it). */
    public int upTo(int level) {
        return upTo[Math.max(1, Math.min(3, level))];
    }

    public static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------ helpers

    static BlockState block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(id)).defaultBlockState();
    }

    BlockState wood(String part) {
        var b = BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(wood + "_" + part));
        return (b.isPresent() ? b.get() : BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("oak_" + part))).defaultBlockState();
    }

    private void surface(int x, int z, BlockState s) {
        BlockPos p = frame.at(x, 0, z);
        ground.put(key(p.getX(), p.getZ()), s);
    }

    private void set(int x, int y, int z, BlockState s) {
        pieces.add(new Piece(frame.at(x, y, z), s, false));
    }

    private void shaped(int x, int y, int z, BlockState s) {
        pieces.add(new Piece(frame.at(x, y, z), s, true));
    }

    private void bed(int x, int z, String color) {
        bed(x, 0, z, color);
    }

    private void bed(int x, int y, int z, String color) {
        // head at the back, the foot towards the door
        Direction head = frame.front().getOpposite();
        BlockState bed = block(color + "_bed").setValue(BlockStateProperties.HORIZONTAL_FACING, head);
        set(x, y, z + 1, bed.setValue(BlockStateProperties.BED_PART, BedPart.FOOT));
        set(x, y, z, bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
        beds.add(frame.at(x, y, z));
    }

    private void levelAll(BlockState inside, BlockState outside, int inner) {
        for (int x = -type.half; x <= type.half; x++) {
            for (int z = -type.half; z <= type.half; z++) {
                surface(x, z, Math.abs(x) <= inner && Math.abs(z) <= inner ? inside : outside);
            }
        }
    }

    // ------------------------------------------------------------------ the buildings

    private void campfire() {
        BlockState grass = block("grass_block");
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) surface(x, z, Math.abs(x) <= 1 && Math.abs(z) <= 1 ? block("coarse_dirt") : grass);
        }
        set(0, 0, 0, block("campfire").setValue(BlockStateProperties.LIT, true));
        BlockState log = wood("log");
        // benches around the fire, open towards the front
        set(-2, 0, 0, log.setValue(BlockStateProperties.AXIS, frame.front().getAxis()));
        set(2, 0, 0, log.setValue(BlockStateProperties.AXIS, frame.front().getAxis()));
        set(0, 0, -2, log.setValue(BlockStateProperties.AXIS, frame.right().getAxis()));
        workSpot = frame.at(0, 0, 1);
    }

    private void tent(RandomSource rnd) {
        String[] colors = {"white", "light_gray", "brown", "white", "green"};
        String color = colors[rnd.nextInt(colors.length)];
        BlockState wool = block(color + "_wool");
        levelAll(block("grass_block"), block("grass_block"), 2);
        // an A-frame along the depth: low sides, a ridge in the middle
        for (int z = -2; z <= 2; z++) {
            set(-2, 0, z, wool);
            set(2, 0, z, wool);
        }
        bed(-1, -1, color.equals("white") ? "red" : "white");
        bed(1, -1, color.equals("brown") ? "yellow" : "brown");
        for (int x : new int[]{-1, 0, 1}) set(x, 0, -2, wool);
        set(0, 1, -2, wool);
        set(-1, 0, 2, wool);
        set(1, 0, 2, wool);
        for (int z = -2; z <= 2; z++) {
            set(-1, 1, z, wool);
            set(1, 1, z, wool);
            set(0, 2, z, wool);
        }
        // the lantern stands in a front corner, out of the way to the beds
        set(1, 0, 1, block("lantern"));
        workSpot = frame.at(0, 0, 3);
    }

    /**
     * A cottage of half-size {@code r}: corner posts, a footing course and walls, glass windows, a door in the front,
     * gable ends and a roof of stairs with a ridge.
     */
    private void cottage(int r, BlockState post, BlockState footing, BlockState wall, BlockState floor) {
        cottage(r, post, footing, wall, floor, 3, wood("stairs"));
    }

    /**
     * A cottage whose walls are {@code height} blocks high (3 for one storey, 6 for two, windows on each), roofed
     * with {@code stairs}.
     */
    private void cottage(int r, BlockState post, BlockState footing, BlockState wall, BlockState floor, int height, BlockState stairs) {
        BlockState ridge = wood("planks");
        levelAll(floor, block("grass_block"), r);
        eaves = height;
        // corner posts first, then the walls between them
        for (int y = 0; y < height; y++) {
            for (int[] c : new int[][]{{-r, -r}, {r, -r}, {-r, r}, {r, r}}) set(c[0], y, c[1], post);
        }
        for (int y = 0; y < height; y++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) != r && Math.abs(z) != r) continue;
                    if (Math.abs(x) == r && Math.abs(z) == r) continue;
                    if (z == r && x == 0 && y <= 1) continue;   // the doorway
                    if (y % 3 == 1 && (x == 0 || z == 0)) shaped(x, y, z, block("glass_pane"));
                    else set(x, y, z, y == 0 ? footing : wall);
                }
            }
        }
        Direction in = frame.front().getOpposite();
        set(0, 0, r, wood("door").setValue(BlockStateProperties.HORIZONTAL_FACING, in).setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        set(0, 1, r, wood("door").setValue(BlockStateProperties.HORIZONTAL_FACING, in).setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        // gable ends, then the roof
        for (int x : new int[]{-r, r}) {
            for (int z = -(r - 1); z <= r - 1; z++) {
                for (int y = height; y < r + height - Math.abs(z); y++) set(x, y, z, wall);
            }
        }
        for (int z = -r - 1; z <= r + 1; z++) {
            for (int x = -r - 1; x <= r + 1; x++) {
                int y = r + height - Math.abs(z);
                if (z == 0) {
                    set(x, y, z, ridge);
                } else {
                    Direction up = z > 0 ? frame.front().getOpposite() : frame.front();
                    set(x, y, z, stairs.setValue(BlockStateProperties.HORIZONTAL_FACING, up).setValue(BlockStateProperties.HALF, Half.BOTTOM));
                }
            }
        }
        workSpot = frame.at(0, 0, r + 2);
        size = r;
    }

    /** The cottage last laid out: its half-size and the height its walls end at (for the additions). */
    private int size = 2, eaves = 3;

    /** Two beds along the side walls of a small cottage, a chest between their heads with a lamp on it. */
    private void twoBeds(String a, String b) {
        bed(-1, -1, a);
        bed(1, -1, b);
        set(0, 0, -1, block("chest").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front()));
        set(0, 1, -1, block("lantern"));
    }

    /** A master's bed along one side wall, a chest at its head with a lamp on it, a barrel on the other side. */
    private void oneBed(String a) {
        bed(-1, -1, a);
        set(0, 0, -1, block("chest").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front()));
        set(0, 1, -1, block("lantern"));
        set(1, 0, -1, block("barrel").setValue(BlockStateProperties.FACING, Direction.UP));
    }

    private BlockState facing(String id, Direction d) {
        return block(id).setValue(BlockStateProperties.HORIZONTAL_FACING, d);
    }

    /** A small cottage: log frame, plank walls, glass windows, a gable roof, two beds. */
    private void hut() {
        BlockState planks = wood("planks");
        cottage(2, wood("log"), planks, planks, planks);
        twoBeds("red", "blue");
    }

    /** A hut grown into a house: a stone footing, room for two (and room to live in). */
    private void house() {
        BlockState planks = wood("planks");
        cottage(3, wood("log"), block("cobblestone"), planks, planks);
        bed(-2, -2, "red");
        bed(2, -2, "blue");
        furniture();
        set(0, 0, -2, block("chest").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front()));
        set(0, 1, -2, block("lantern"));
        set(-2, 1, 0, block("lantern").setValue(BlockStateProperties.HANGING, false));
    }

    /** A house of stone: a cobbled footing and stone brick walls, room for two. */
    private void stoneHouse() {
        cottage(3, wood("log"), block("cobblestone"), block("stone_bricks"), wood("planks"), 3, wood("stairs"));
        bed(-2, -2, "red");
        bed(2, -2, "blue");
        furniture();
        set(0, 0, -2, block("chest").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front()));
        set(0, 1, -2, block("lantern"));
    }

    /**
     * A house of two storeys, of wood or of stone: a room to live in down, three beds up, a stair along the left
     * wall to the upper floor.
     */
    private void tallHouse(boolean stone) {
        BlockState planks = wood("planks");
        cottage(3, wood("log"), block("cobblestone"), stone ? block("stone_bricks") : planks, planks, 6,
                stone ? block("stone_brick_stairs") : wood("stairs"));
        Direction back = frame.front().getOpposite();
        BlockState step = wood("stairs").setValue(BlockStateProperties.HORIZONTAL_FACING, back).setValue(BlockStateProperties.HALF, Half.BOTTOM);
        // the upper floor, with a well for the stair
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (x == -2) continue;
                set(x, 3, z, planks);
            }
        }
        // the stair: up towards the back, what is under each step filled in
        for (int k = 0; k <= 3; k++) {
            for (int y = 0; y < k; y++) set(-2, y, 1 - k, planks);
            set(-2, k, 1 - k, step);
        }
        set(0, 0, -2, block("chest").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front()));
        set(0, 1, -2, block("lantern"));
        set(2, 0, -2, block("crafting_table"));
        set(2, 0, 1, block("barrel"));
        bed(2, 4, -2, "green");
        bed(2, 4, 1, "yellow");
        bed(0, 4, -2, "white");
        set(1, 4, 0, block("lantern"));
    }

    /** The front half of a one-storey house, where the other two beds of old stood: a table, a barrel. */
    private void furniture() {
        set(-2, 0, 0, block("crafting_table"));
        set(2, 0, 1, block("barrel"));
    }

    /** A closed storehouse: stone footing, plank walls, barrels and chests inside. */
    private void storehouse2() {
        cottage(2, wood("log"), block("cobblestone"), wood("planks"), block("dirt_path"));
        set(-1, 0, -1, block("barrel").setValue(BlockStateProperties.FACING, Direction.UP));
        set(-1, 1, -1, block("barrel").setValue(BlockStateProperties.FACING, Direction.UP));
        set(1, 0, -1, block("barrel").setValue(BlockStateProperties.FACING, Direction.UP));
        set(1, 1, -1, block("barrel").setValue(BlockStateProperties.FACING, Direction.UP));
        set(0, 0, -1, block("chest").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front()));
        set(-1, 0, 1, block("hay_block"));
        workSpot = frame.at(0, 0, 4);
    }

    /** The miners' house: cobbled walls, a grindstone and a stone pile by it. */
    private void mineHouse() {
        cottage(2, wood("log"), block("cobblestone"), block("cobblestone"), block("cobblestone"));
        oneBed("gray");
        set(-3, 0, -1, block("cobblestone"));
        set(-3, 0, 0, block("cobblestone"));
        set(-3, 1, -1, block("cobblestone"));
        set(3, 0, 1, block("grindstone").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.right()));
    }

    /** The smithy: a stone cottage with the forge by its side: a furnace, an anvil. */
    private void smithy() {
        cottage(2, wood("log"), block("cobblestone"), block("cobblestone"), wood("planks"));
        oneBed("red");
        set(-3, 0, 0, facing("furnace", frame.right().getOpposite()));
        set(-3, 0, 1, block("anvil").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front()));
        set(-3, 0, -1, block("cobblestone"));
    }

    /** The woodcutters' hut: walls of logs, a woodpile, a chopping block. */
    private void woodHut() {
        BlockState log = wood("log");
        cottage(2, wood("log"), log, log, wood("planks"));
        oneBed("green");
        BlockState lying = log.setValue(BlockStateProperties.AXIS, frame.front().getAxis());
        for (int z = -1; z <= 1; z++) {
            set(-3, 0, z, lying);
            if (z != 1) set(-3, 1, z, lying);
        }
        set(3, 0, 1, wood("log"));
    }

    /** The fishers' hut: barrels of fish by the door. */
    private void fishHut() {
        BlockState planks = wood("planks");
        cottage(2, wood("log"), planks, planks, planks);
        oneBed("cyan");
        set(3, 0, 0, block("barrel").setValue(BlockStateProperties.FACING, Direction.UP));
        set(3, 0, 1, block("barrel").setValue(BlockStateProperties.FACING, Direction.UP));
        set(3, 1, 0, block("barrel").setValue(BlockStateProperties.FACING, Direction.UP));
    }

    /** The farmstead: a barn with hay by it and a composter. */
    private void farm() {
        BlockState planks = wood("planks");
        cottage(2, wood("log"), block("cobblestone"), planks, planks);
        oneBed("yellow");
        set(-3, 0, 0, block("hay_block"));
        set(-3, 0, 1, block("hay_block"));
        set(-3, 1, 0, block("hay_block"));
        set(3, 0, 1, block("composter"));
    }

    /** The sawmill: a log cabin, the saw (a stonecutter) at the door, logs waiting on one side, planks stacked on the other. */
    private void sawmill() {
        BlockState log = wood("log"), planks = wood("planks");
        cottage(2, log, block("cobblestone"), planks, planks);
        oneBed("brown");
        set(1, 0, 3, facing("stonecutter", frame.front()));
        BlockState lying = log.setValue(BlockStateProperties.AXIS, frame.right().getAxis());
        for (int x = -3; x <= -2; x++) {
            set(x, 0, 3, lying);
            set(x, 0, 4, lying);
        }
        set(-3, 1, 3, lying);
        set(3, 0, -1, planks);
        set(3, 0, 0, planks);
        set(3, 1, -1, wood("slab").setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));
        workSpot = frame.at(1, 0, 4);
    }

    /** The joiner's workshop: a cottage with a workbench at the door and planks stacked by it. */
    private void carpenter() {
        BlockState log = wood("log"), planks = wood("planks");
        cottage(2, log, block("cobblestone"), planks, planks);
        oneBed("orange");
        set(1, 0, 3, block("crafting_table"));
        set(-1, 0, 3, barrel());
        set(-3, 0, 3, planks);
        set(-3, 1, 3, wood("slab").setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));
        set(3, 0, -1, wood("stairs"));
        workSpot = frame.at(1, 0, 4);
    }

    /** The cartographer's house: plank walls, the map table inside, a compass rose of flowers... a weather vane on the roof. */
    private void cartographer() {
        BlockState planks = wood("planks");
        cottage(2, wood("log"), block("cobblestone"), planks, planks);
        bed(-1, -1, "blue");
        set(1, 0, -1, block("bookshelf"));
        set(0, 0, -1, org.webtrade.minecraftportsmod.registry.ModContent.MAP_TABLE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, frame.front()));
        set(0, 1, -1, block("lantern"));
        // a vane on the ridge
        shaped(0, 6, 0, wood("fence"));
        set(0, 7, 0, block("lightning_rod"));
    }

    // ------------------------------------------------------------------ the levels

    private static final String[] FLOWERS = {"potted_poppy", "potted_dandelion", "potted_blue_orchid", "potted_allium",
            "potted_azure_bluet", "potted_red_tulip", "potted_oxeye_daisy", "potted_cornflower", "potted_fern"};

    private BlockState barrel() {
        return block("barrel").setValue(BlockStateProperties.FACING, Direction.UP);
    }

    /** Flowers in pots on either side of a cottage's door, and torches on the wall above them. */
    private void porch(RandomSource rnd) {
        int z = size + 1;
        set(-1, 0, z, block(FLOWERS[rnd.nextInt(FLOWERS.length)]));
        set(1, 0, z, block(FLOWERS[rnd.nextInt(FLOWERS.length)]));
        BlockState torch = block("wall_torch").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front());
        set(-1, 1, z, torch);
        set(1, 1, z, torch);
    }

    /** A lamp post at the cottage's front left corner. */
    private void lampPost() {
        int c = size + 1;
        shaped(-c, 0, c, wood("fence"));
        shaped(-c, 1, c, wood("fence"));
        set(-c, 2, c, block("lantern"));
    }

    /** A chimney through the back of the roof, smoke rising from it. */
    private void chimney() {
        int x = -(size - 1), z = -(size - 1);
        int roof = size + eaves - Math.abs(z);
        for (int y = roof; y <= size + eaves + 1; y++) set(x, y, z, block("cobblestone"));
        set(x, size + eaves + 2, z, block("campfire").setValue(BlockStateProperties.LIT, true));
    }

    /**
     * What a level adds to a building: level 2 dresses it (flowers, crates, a scarecrow), level 3 finishes it (a
     * lamp, a chimney, the trade's own tools at the door).
     */
    private void extras(int level, RandomSource rnd) {
        switch (type) {
            case HUT, HOUSE, HOUSE_TALL, STONE_HOUSE, STONE_HOUSE_TALL, STOREHOUSE_2 -> {
                if (level == 2) porch(rnd);
                else {
                    lampPost();
                    chimney();
                }
            }
            case MINE_HOUSE -> {
                if (level == 2) {
                    porch(rnd);
                    set(-3, 1, 0, block("cobblestone"));
                } else {
                    // the forge: a furnace, an anvil, a chimney of its own
                    set(3, 0, -1, facing("blast_furnace", frame.right()));
                    set(-3, 0, 1, block("anvil").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front()));
                    for (int y = 3; y <= 6; y++) set(2, y, -2, block("cobblestone"));
                    set(2, 7, -2, block("campfire").setValue(BlockStateProperties.LIT, true));
                    lampPost();
                }
            }
            case SMITHY -> {
                if (level == 2) {
                    porch(rnd);
                    set(3, 0, 1, block("smithing_table"));
                } else {
                    set(3, 0, 0, facing("blast_furnace", frame.right()));
                    set(3, 0, -1, block("grindstone").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.right()));
                    lampPost();
                    chimney();
                }
            }
            case WOOD_HUT -> {
                if (level == 2) {
                    porch(rnd);
                    set(3, 1, 1, wood("log"));
                } else {
                    set(3, 0, -1, facing("stonecutter", frame.right()));
                    set(3, 0, 0, block("smithing_table"));
                    lampPost();
                    chimney();
                }
            }
            case FARMYARD -> {
                // the runs of the level, and its lights
                for (Herds.Run r : Herds.runs(type.half)) if (r.level() == level) run(r);
                int h = type.half;
                if (level == 2) {
                    for (int[] c : new int[][]{{-h, -h}, {h, Herds.FRONT}}) set(c[0], 1, c[1], block("lantern"));
                    set(h - 2, 0, h - 3, block("hay_block"));
                } else {
                    set(h, 1, -h, block("lantern"));
                    set(h - 4, 0, h - 1, block("water_cauldron").setValue(BlockStateProperties.LEVEL_CAULDRON, 3));
                    set(h - 2, 0, h - 1, barrel());
                }
            }
            case SAWMILL, CARPENTER, LOCKSMITH, WEAVER, SMELTER, GLASSWORKS -> {
                if (level == 2) {
                    porch(rnd);
                    set(-3, 0, 2, barrel());
                } else {
                    set(3, 0, 1, block("grindstone").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.right()));
                    lampPost();
                    chimney();
                }
            }
            case CARTOGRAPHER -> {
                if (level == 2) porch(rnd);
                else {
                    set(3, 0, 0, block("lectern").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.right().getOpposite()));
                    lampPost();
                    chimney();
                }
            }
            case FISH_HUT -> {
                if (level == 2) {
                    porch(rnd);
                    for (int y = 0; y <= 1; y++) shaped(-3, y, -1, wood("fence"));
                    set(-3, 2, -1, block("cobweb"));
                } else {
                    set(-3, 0, 0, facing("smoker", frame.right().getOpposite()));
                    set(-3, 0, 1, barrel());
                    lampPost();
                    chimney();
                }
            }
            case FARM -> {
                if (level == 2) {
                    porch(rnd);
                    set(-3, 0, -1, block("hay_block"));
                    set(-3, 1, 1, block("hay_block"));
                } else {
                    set(3, 0, 0, barrel());
                    set(3, 0, -1, block("composter"));
                    lampPost();
                    chimney();
                }
            }
            case STOREHOUSE -> {
                if (level == 2) {
                    set(-3, 0, -1, barrel());
                    set(3, 0, -1, barrel());
                    set(3, 0, 0, block("hay_block"));
                } else {
                    for (int x : new int[]{-3, 3}) {
                        shaped(x, 0, 2, wood("fence"));
                        shaped(x, 1, 2, wood("fence"));
                        set(x, 2, 2, block("lantern"));
                    }
                    set(-3, 1, -1, barrel());
                }
            }
            case MARKET -> {
                if (level == 2) {
                    set(-2, 0, 0, barrel());
                    set(2, 0, 0, barrel());
                    set(-1, 1, 1, block(FLOWERS[rnd.nextInt(FLOWERS.length)]));
                } else {
                    for (int[] c : new int[][]{{-1, -1}, {1, 1}, {-1, 1}, {1, -1}}) set(c[0], 2, c[1], block("lantern").setValue(BlockStateProperties.HANGING, true));
                    set(1, 1, 1, block(FLOWERS[rnd.nextInt(FLOWERS.length)]));
                }
            }
            case FIELD -> {
                if (level == 2) {
                    // a scarecrow in the back corner
                    shaped(-4, 0, -4, wood("fence"));
                    set(-4, 1, -4, block("hay_block"));
                    set(-4, 2, -4, facing("carved_pumpkin", frame.front()));
                } else {
                    for (int[] c : new int[][]{{4, -4}, {-4, 4}}) {
                        shaped(c[0], 0, c[1], wood("fence"));
                        set(c[0], 1, c[1], block("lantern"));
                    }
                    set(-4, 0, 0, block("beehive").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.right()));
                }
            }
            default -> {
            }
        }
    }

    /**
     * The farmyard: the keeper's lodge (a bed, a chest) in the front corner, the yard with the hay beside it, and
     * behind them the runs of the animals, each fenced, with a gate: the hens' at first; the other runs come with
     * the levels ({@link #extras}). The animals are put in by {@link Herds}.
     */
    private void farmyard() {
        int h = type.half;
        BlockState planks = wood("planks"), log = wood("log");
        levelAll(block("grass_block"), block("grass_block"), h);
        for (Herds.Run r : Herds.runs(h)) if (r.level() == 1) run(r);
        // the keeper's lodge: log corners, plank walls, a window each side, a flat roof of slabs
        int x0 = -h, x1 = -h + 4, z0 = h - 4, z1 = h;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) surface(x, z, planks);
        for (int y = 0; y <= 2; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edgeX = x == x0 || x == x1, edgeZ = z == z0 || z == z1;
                    if (!edgeX && !edgeZ) continue;
                    if (x == x0 + 2 && z == z1 && y <= 1) continue;   // the doorway
                    if (edgeX && edgeZ) set(x, y, z, log);
                    else if (y == 1 && (x == x0 + 2 || z == z0 + 2)) shaped(x, y, z, block("glass_pane"));
                    else set(x, y, z, planks);
                }
            }
        }
        Direction in = frame.front().getOpposite();
        set(x0 + 2, 0, z1, wood("door").setValue(BlockStateProperties.HORIZONTAL_FACING, in).setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        set(x0 + 2, 1, z1, wood("door").setValue(BlockStateProperties.HORIZONTAL_FACING, in).setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) set(x, 3, z, wood("slab").setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));
        bed(x0 + 1, z0 + 1, "brown");
        set(x0 + 3, 0, z0 + 1, block("chest").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front()));
        set(x0 + 3, 1, z0 + 1, block("lantern"));
        // the yard: hay for the animals, a barrel, the hens' composter
        set(h - 1, 0, h - 3, block("hay_block"));
        set(h - 1, 0, h - 2, barrel());
        set(h - 3, 0, h - 3, block("composter"));
        workSpot = frame.at(2, 0, Herds.FRONT + 1);
        size = h - 1;
    }

    /** One run of the farmyard: a fence all round it, its gate on the side away from the other runs. */
    private void run(Herds.Run r) {
        int h = type.half;
        int gx = (r.x0() + r.x1()) / 2, gz = r.z1() == Herds.FRONT ? Herds.FRONT : -h;
        Direction gateFacing = r.z1() == Herds.FRONT ? frame.front() : frame.front().getOpposite();
        for (int x = r.x0(); x <= r.x1(); x++) {
            for (int z = r.z0(); z <= r.z1(); z++) {
                if (x != r.x0() && x != r.x1() && z != r.z0() && z != r.z1()) continue;
                if (x == gx && z == gz) {
                    set(x, 0, z, wood("fence_gate").setValue(BlockStateProperties.HORIZONTAL_FACING, gateFacing).setValue(BlockStateProperties.OPEN, false));
                } else {
                    shaped(x, 0, z, wood("fence"));
                }
            }
        }
        // (nothing inside the run to climb on: from a block's top an animal is over the fence)
    }

    /** A craftsman's house (the weaver's, the smelter, the glassworks): a cottage with its work at the door. */
    private void craftHouse() {
        BlockState planks = wood("planks");
        boolean stone = type != BuildingType.WEAVER;
        cottage(2, wood("log"), stone ? block("cobblestone") : planks, stone ? block("cobblestone") : planks, planks);
        oneBed(type == BuildingType.WEAVER ? "pink" : type == BuildingType.SMELTER ? "red" : "light_blue");
        switch (type) {
            case WEAVER -> {
                set(1, 0, 3, block("loom").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front().getOpposite()));
                set(-1, 0, 3, block("white_wool"));
            }
            case SMELTER -> {
                set(1, 0, 3, block("blast_furnace").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front()));
                set(-1, 0, 3, block("furnace").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front()));
            }
            default -> {
                set(1, 0, 3, block("furnace").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front()));
                set(-1, 0, 3, block("glass"));
            }
        }
        workSpot = frame.at(1, 0, 4);
    }

    /** The locksmith's: a stone cottage, an anvil and a furnace at the door. */
    private void locksmith() {
        BlockState planks = wood("planks");
        cottage(2, wood("log"), block("cobblestone"), block("cobblestone"), planks);
        oneBed("gray");
        set(1, 0, 3, block("anvil").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.right()));
        set(-1, 0, 3, barrel());
        set(3, 0, -1, facing("furnace", frame.right()));
        set(3, 1, 1, block("lantern"));
        set(-3, 0, 1, block("iron_bars"));
        workSpot = frame.at(1, 0, 4);
    }

    /** The stall: a counter under a striped awning on posts, barrels and a chest of wares. */
    private void market(RandomSource rnd) {
        levelAll(block("dirt_path"), block("grass_block"), 2);
        String[] colors = {"red", "yellow", "blue", "green", "orange"};
        String a = colors[rnd.nextInt(colors.length)];
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            for (int y = 0; y <= 2; y++) shaped(c[0], y, c[1], wood("fence"));
        }
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) set(x, 3, z, block((x + z) % 2 == 0 ? a + "_wool" : "white_wool"));
        }
        // the counter at the front, the wares behind
        for (int x = -1; x <= 1; x++) set(x, 0, 1, wood("slab").setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP));
        set(-1, 0, -1, block("barrel").setValue(BlockStateProperties.FACING, Direction.UP));
        set(1, 0, -1, block("barrel").setValue(BlockStateProperties.FACING, Direction.UP));
        set(0, 0, -1, block("chest").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front()));
        set(-1, 1, -1, block("lantern"));
        workSpot = frame.at(0, 0, 0);
    }

    /**
     * The village square: paving round the fire (or, later, a well), lamp posts at the corners, benches. The front
     * (towards the water) stays open.
     */
    private void square(boolean well) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                boolean edge = Math.abs(x) == 3 || Math.abs(z) == 3;
                boolean center = x == 0 && z == 0;
                surface(x, z, well && center ? Blocks.WATER.defaultBlockState()
                        : edge ? block("cobblestone") : (x + z) % 2 == 0 ? block("stone_bricks") : block("polished_andesite"));
            }
        }
        if (well) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0) set(0, 0, 0, Blocks.WATER.defaultBlockState());
                    else shaped(x, 0, z, block("cobblestone_wall"));
                }
            }
            for (int[] c : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                shaped(c[0], 1, c[1], wood("fence"));
                shaped(c[0], 2, c[1], wood("fence"));
            }
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) set(x, 3, z, block("cobblestone_slab").setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));
            }
        } else {
            set(0, 0, 0, block("campfire").setValue(BlockStateProperties.LIT, true));
        }
        // lamps at the corners, benches facing the middle on three sides
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
            shaped(c[0], 0, c[1], wood("fence"));
            shaped(c[0], 1, c[1], wood("fence"));
            set(c[0], 2, c[1], block("lantern"));
        }
        BlockState stairs = wood("stairs");
        for (int k = -1; k <= 1; k++) {
            set(k, 0, -3, stairs.setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front().getOpposite()));
            set(-3, 0, k, stairs.setValue(BlockStateProperties.HORIZONTAL_FACING, frame.right()));
            set(3, 0, k, stairs.setValue(BlockStateProperties.HORIZONTAL_FACING, frame.right().getOpposite()));
        }
        workSpot = frame.at(0, 0, 2);
    }

    /** An open store: a slab roof on log posts, barrels and crates, a back wall. */
    private void storehouse() {
        BlockState planks = wood("planks"), log = wood("log");
        levelAll(planks, block("dirt_path"), 2);
        for (int y = 0; y <= 2; y++) {
            for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) set(c[0], y, c[1], log);
        }
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 2; y++) set(x, y, -2, planks);
        }
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                set(x, 3, z, wood("slab").setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));
            }
        }
        set(-1, 0, -1, block("barrel").setValue(BlockStateProperties.FACING, Direction.UP));
        set(-1, 1, -1, block("barrel").setValue(BlockStateProperties.FACING, Direction.UP));
        set(0, 0, -1, block("chest").setValue(BlockStateProperties.HORIZONTAL_FACING, frame.front()));
        set(1, 0, -1, block("barrel").setValue(BlockStateProperties.FACING, Direction.UP));
        set(-2, 0, 0, log.setValue(BlockStateProperties.AXIS, frame.front().getAxis()));
        set(-2, 0, 1, log.setValue(BlockStateProperties.AXIS, frame.front().getAxis()));
        set(-2, 1, 0, log.setValue(BlockStateProperties.AXIS, frame.front().getAxis()));
        set(2, 0, 0, block("cobblestone"));
        set(2, 0, 1, block("cobblestone"));
        set(1, 0, 1, block("hay_block"));
        set(1, 3, 1, block("lantern").setValue(BlockStateProperties.HANGING, false));
        workSpot = frame.at(0, 0, 1);
    }

    /** Farmland around a water channel, with a log border and a composter. */
    private void field(RandomSource rnd, Crop crop) {
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                boolean border = Math.abs(x) == 4 || Math.abs(z) == 4;
                boolean channel = x == 0 && !border;
                if (border) {
                    surface(x, z, wood("log").setValue(BlockStateProperties.AXIS, Math.abs(x) == 4 ? frame.front().getAxis() : frame.right().getAxis()));
                } else if (channel) {
                    surface(x, z, Blocks.WATER.defaultBlockState());
                } else {
                    surface(x, z, block("farmland").setValue(BlockStateProperties.MOISTURE, 7));
                }
            }
        }
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (x == 0) continue;
                // sown a while ago: some of it nearly ripe
                net.minecraft.world.level.block.CropBlock cb = (net.minecraft.world.level.block.CropBlock) crop.block;
                int max = cb.getMaxAge();
                set(x, 0, z, cb.getStateForAge(Math.min(max, max / 2 + rnd.nextInt(max / 2 + 1))));
            }
        }
        set(4, 0, 4, block("composter"));
        workSpot = frame.at(1, 0, 0);
    }

}
