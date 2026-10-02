package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;

/**
 * What a village keeps in its stores. Kept coarse on purpose: any log is wood, any stone is stone, any food is
 * food (counted in hunger points).
 */
public enum Res {
    FOOD(Items.COOKED_COD),
    WOOD(Items.OAK_LOG),
    STONE(Items.COBBLESTONE),
    /** Found by miners with a stone pick or better; the players can bring it too. Needed for the better tools. */
    IRON(Items.IRON_INGOT),
    /** Sawn from logs: at the sawmill, or (slowly, wastefully) by hand. The better buildings are built with them. */
    PLANKS(Items.OAK_PLANKS),
    /** Made from logs too: the handles of every tool. */
    STICKS(Items.STICK),
    /** Found in the rock by the miners: the torches and lamps of the village, and what iron is worked with. */
    COAL(Items.COAL),
    /** From the wheat fields (and a little gathered wild): a hut is thatched with it. */
    WHEAT(Items.WHEAT),
    /**
     * The joiner's work, counted as one: doors, stairs, slabs, fences, chests... A house asks for so much of it; a
     * player buys the very pieces from the joiner.
     */
    JOINERY(Items.CHEST),
    /** The smith's work: tools for the trades, wooden, stone, iron; each worker wears them out. */
    TOOLS1(Items.WOODEN_PICKAXE),
    TOOLS2(Items.STONE_PICKAXE),
    TOOLS3(Items.IRON_PICKAXE),
    /** Shorn from the village's sheep at the sheepfold: beds and carpets are made with it. */
    WOOL(Items.WOOL.white()),
    /** The hides of the cattle barn: chairs, saddles, the covers of books. */
    LEATHER(Items.LEATHER),
    /** The locksmith's work, counted as one: buckets, chains, lanterns, hinges and nails. */
    METALWARE(Items.LANTERN),
    /** The joiner's finer work (his workshop raised a level): beds, tables, chairs, cupboards. The better homes are furnished with it. */
    FURNITURE(Items.BED.red());

    /** The tools of a level (1 wooden, 2 stone, 3 iron). */
    public static Res tools(int level) {
        return level <= 1 ? TOOLS1 : level == 2 ? TOOLS2 : TOOLS3;
    }

    /** Goods only some trades use: none kept (nor bought) by a village that has no use for them. */
    public boolean optional() {
        return this == WOOL || this == LEATHER || this == METALWARE || this == FURNITURE;
    }

    /** The level of a store of tools (0: not tools). */
    public int toolLevel() {
        return this == TOOLS1 ? 1 : this == TOOLS2 ? 2 : this == TOOLS3 ? 3 : 0;
    }

    /** The kinds of goods, as a merchant sorts them. */
    public enum Kind {
        FOOD, RAW, BUILDING, TOOLS;

        public Component displayName() {
            return Component.translatable("minecraftportsmod.goods." + name().toLowerCase(Locale.ROOT));
        }
    }

    public final Item icon;

    Res(Item icon) {
        this.icon = icon;
    }

    public Kind kind() {
        return switch (this) {
            case FOOD -> Kind.FOOD;
            case WOOD, STONE, IRON, COAL, WHEAT, WOOL, LEATHER -> Kind.RAW;
            case PLANKS, STICKS, JOINERY, METALWARE, FURNITURE -> Kind.BUILDING;
            case TOOLS1, TOOLS2, TOOLS3 -> Kind.TOOLS;
        };
    }

    public static Res byId(String id) {
        for (Res r : values()) if (r.id().equals(id)) return r;
        // (the sawmill's wares of old are joinery now)
        if (id.equals("stairs") || id.equals("slabs") || id.equals("doors") || id.equals("fences")) return JOINERY;
        return null;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable("minecraftportsmod.res." + id());
    }

    /** How many units of this resource one item of the stack is worth (0 if it isn't this resource). */
    public int unitsOf(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        return switch (this) {
            case FOOD -> {
                var food = stack.get(DataComponents.FOOD);
                yield food == null ? 0 : Math.max(1, food.nutrition());
            }
            case WOOD -> stack.is(ItemTags.LOGS) ? 1 : 0;
            case STONE -> stack.is(Items.COBBLESTONE) || stack.is(Items.STONE) || stack.is(Items.COBBLED_DEEPSLATE)
                    || stack.is(Items.ANDESITE) || stack.is(Items.DIORITE) || stack.is(Items.GRANITE)
                    || stack.getItem() instanceof BlockItem b && b.getBlock().defaultBlockState().is(BlockTags.BASE_STONE_OVERWORLD) ? 1 : 0;
            case IRON -> stack.is(Items.IRON_INGOT) || stack.is(Items.RAW_IRON) || stack.is(Items.IRON_ORE) || stack.is(Items.DEEPSLATE_IRON_ORE) ? 1
                    : stack.is(Items.IRON_BLOCK) || stack.is(Items.RAW_IRON_BLOCK) ? 9 : 0;
            case PLANKS -> stack.is(ItemTags.PLANKS) ? 1 : 0;
            case STICKS -> stack.is(Items.STICK) ? 1 : 0;
            case COAL -> stack.is(Items.COAL) || stack.is(Items.CHARCOAL) ? 1 : stack.is(Items.COAL_BLOCK) ? 9 : 0;
            case WHEAT -> stack.is(Items.WHEAT) ? 1 : stack.is(Items.HAY_BLOCK) ? 9 : 0;
            case JOINERY -> stack.is(ItemTags.WOODEN_STAIRS) || stack.is(ItemTags.WOODEN_SLABS) || stack.is(ItemTags.WOODEN_DOORS)
                    || stack.is(ItemTags.WOODEN_FENCES) || stack.is(ItemTags.WOODEN_TRAPDOORS) || stack.is(ItemTags.FENCE_GATES)
                    || stack.is(Items.CHEST) || stack.is(Items.BARREL) || stack.is(Items.LADDER) ? 1 : 0;
            case TOOLS1 -> stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.WOODEN_AXE) || stack.is(Items.WOODEN_SHOVEL) || stack.is(Items.WOODEN_HOE) ? 1 : 0;
            case TOOLS2 -> stack.is(Items.STONE_PICKAXE) || stack.is(Items.STONE_AXE) || stack.is(Items.STONE_SHOVEL) || stack.is(Items.STONE_HOE) ? 1 : 0;
            case TOOLS3 -> stack.is(Items.IRON_PICKAXE) || stack.is(Items.IRON_AXE) || stack.is(Items.IRON_SHOVEL) || stack.is(Items.IRON_HOE) ? 1 : 0;
            case WOOL -> stack.is(ItemTags.WOOL) ? 1 : 0;
            case LEATHER -> stack.is(Items.LEATHER) || stack.is(Items.RABBIT_HIDE) ? 1 : 0;
            case METALWARE -> stack.is(Items.BUCKET) || stack.is(Items.IRON_CHAIN) || stack.is(Items.LANTERN) || stack.is(Items.IRON_BARS)
                    || stack.is(Items.SHEARS) ? 1 : 0;
            case FURNITURE -> stack.is(ItemTags.BEDS) || stack.is(Items.BOOKSHELF) || stack.is(Items.LECTERN) || stack.is(Items.ITEM_FRAME)
                    || stack.is(Items.PAINTING) ? 1 : 0;
        };
    }
}
