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
import java.util.function.Predicate;

/**
 * What a village keeps in its stores. The raw goods are kept coarse on purpose (any log is wood, any stone is stone,
 * any food is food, counted in hunger points); what the workshops make is kept as the very things: doors, fences,
 * stairs, beds, lanterns... each made to order in a workshop's queue (see {@link Workshops}), and asked for by name
 * by the buildings put up with them.
 */
public enum Res {
    FOOD(Items.COOKED_COD, 0.04, Kind.FOOD, null, s -> s.get(DataComponents.FOOD) != null),
    WOOD(Items.OAK_LOG, 0.08, Kind.RAW, null, s -> s.is(ItemTags.LOGS)),
    STONE(Items.COBBLESTONE, 0.05, Kind.RAW, null, s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE) || s.is(Items.COBBLED_DEEPSLATE)
            || s.is(Items.ANDESITE) || s.is(Items.DIORITE) || s.is(Items.GRANITE)
            || s.getItem() instanceof BlockItem b && b.getBlock().defaultBlockState().is(BlockTags.BASE_STONE_OVERWORLD)),
    /** Found by miners with a stone pick or better; the players can bring it too. Needed for the better tools. */
    IRON(Items.IRON_INGOT, 0.8, Kind.RAW, null, s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE)
            || s.is(Items.DEEPSLATE_IRON_ORE) || s.is(Items.IRON_BLOCK) || s.is(Items.RAW_IRON_BLOCK)),
    /** Sawn from logs: at the sawmill, or (slowly, wastefully) by hand. The better buildings are built with them. */
    PLANKS(Items.OAK_PLANKS, 0.03, Kind.BUILDING, null, s -> s.is(ItemTags.PLANKS)),
    /** Made from logs too: the handles of every tool. */
    STICKS(Items.STICK, 0.02, Kind.BUILDING, null, s -> s.is(Items.STICK)),
    /** Found in the rock by the miners: the torches and lamps of the village, and what iron is worked with. */
    COAL(Items.COAL, 0.15, Kind.RAW, null, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(Items.COAL_BLOCK)),
    /** From the wheat fields (and a little gathered wild): a hut is thatched with it. */
    WHEAT(Items.WHEAT, 0.03, Kind.RAW, null, s -> s.is(Items.WHEAT) || s.is(Items.HAY_BLOCK)),
    /** The smith's work: tools for the trades, wooden, stone, iron; each worker wears them out. */
    TOOLS1(Items.WOODEN_PICKAXE, 0.12, Kind.TOOLS, null, s -> s.is(Items.WOODEN_PICKAXE) || s.is(Items.WOODEN_AXE) || s.is(Items.WOODEN_SHOVEL)
            || s.is(Items.WOODEN_HOE)),
    TOOLS2(Items.STONE_PICKAXE, 0.3, Kind.TOOLS, null, s -> s.is(Items.STONE_PICKAXE) || s.is(Items.STONE_AXE) || s.is(Items.STONE_SHOVEL)
            || s.is(Items.STONE_HOE)),
    TOOLS3(Items.IRON_PICKAXE, 1.2, Kind.TOOLS, null, s -> s.is(Items.IRON_PICKAXE) || s.is(Items.IRON_AXE) || s.is(Items.IRON_SHOVEL)
            || s.is(Items.IRON_HOE)),
    /** Shorn from the village's sheep at the farmyard: beds, carpets and banners are made with it. */
    WOOL(Items.WOOL.white(), 0.1, Kind.RAW, null, s -> s.is(ItemTags.WOOL)),
    /** The hides of the cows. */
    LEATHER(Items.LEATHER, 0.15, Kind.RAW, null, s -> s.is(Items.LEATHER) || s.is(Items.RABBIT_HIDE)),
    /** The glassworks' sand and fire. */
    GLASS(Items.GLASS, 0.1, Kind.BUILDING, null, s -> s.is(Items.GLASS)),

    // ---- the joiner's work (of the village's own wood)
    DOOR(Items.OAK_DOOR, 0.15, Kind.BUILDING, "door", s -> s.is(ItemTags.WOODEN_DOORS)),
    TRAPDOOR(Items.OAK_TRAPDOOR, 0.15, Kind.BUILDING, "trapdoor", s -> s.is(ItemTags.WOODEN_TRAPDOORS)),
    FENCE(Items.OAK_FENCE, 0.06, Kind.BUILDING, "fence", s -> s.is(ItemTags.WOODEN_FENCES)),
    FENCE_GATE(Items.OAK_FENCE_GATE, 0.1, Kind.BUILDING, "fence_gate", s -> s.is(ItemTags.FENCE_GATES)),
    LADDER(Items.LADDER, 0.05, Kind.BUILDING, null, s -> s.is(Items.LADDER)),
    STAIRS(Items.OAK_STAIRS, 0.05, Kind.BUILDING, "stairs", s -> s.is(ItemTags.WOODEN_STAIRS)),
    SLAB(Items.OAK_SLAB, 0.02, Kind.BUILDING, "slab", s -> s.is(ItemTags.WOODEN_SLABS)),
    CHEST(Items.CHEST, 0.25, Kind.BUILDING, null, s -> s.is(Items.CHEST)),
    BARREL(Items.BARREL, 0.22, Kind.BUILDING, null, s -> s.is(Items.BARREL)),
    /** The joiner's finer work (his workshop raised a level): planks and wool. The better homes are furnished with them. */
    BED(Items.BED.red(), 0.5, Kind.BUILDING, null, s -> s.is(ItemTags.BEDS)),

    // ---- the locksmith's work
    LANTERN(Items.LANTERN, 0.45, Kind.BUILDING, null, s -> s.is(Items.LANTERN)),
    CHAIN(Items.IRON_CHAIN, 0.3, Kind.BUILDING, null, s -> s.is(Items.IRON_CHAIN)),
    BUCKET(Items.BUCKET, 1.0, Kind.BUILDING, null, s -> s.is(Items.BUCKET)),
    IRON_BARS(Items.IRON_BARS, 0.12, Kind.BUILDING, null, s -> s.is(Items.IRON_BARS)),

    // ---- the weaver's work
    CARPET(Items.CARPET.white(), 0.08, Kind.BUILDING, null, s -> s.is(ItemTags.WOOL_CARPETS)),
    BANNER(Items.BANNER.white(), 0.25, Kind.BUILDING, null, s -> s.is(ItemTags.BANNERS)),

    // ---- the glassworks' finer work
    GLASS_PANE(Items.GLASS_PANE, 0.04, Kind.BUILDING, null, s -> s.is(Items.GLASS_PANE));

    /** The tools of a level (1 wooden, 2 stone, 3 iron). */
    public static Res tools(int level) {
        return level <= 1 ? TOOLS1 : level == 2 ? TOOLS2 : TOOLS3;
    }

    /** Goods only some trades use: none kept (nor bought) by a village that has no use for them. */
    public boolean optional() {
        return this == WOOL || this == LEATHER || this == GLASS || made();
    }

    /** Made in a workshop, one by one: the joiner's, the locksmith's, the weaver's, the glassworks' wares. */
    public boolean made() {
        return ordinal() >= DOOR.ordinal();
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
    /** Emeralds a unit is worth at an even keel. */
    final double price;
    private final Kind kind;
    /** Of the village's wood ("door": a spruce village's doors are spruce), or null. */
    final String wooden;
    private final Predicate<ItemStack> is;

    Res(Item icon, double price, Kind kind, String wooden, Predicate<ItemStack> is) {
        this.icon = icon;
        this.price = price;
        this.kind = kind;
        this.wooden = wooden;
        this.is = is;
    }

    public Kind kind() {
        return kind;
    }

    public static Res byId(String id) {
        for (Res r : values()) if (r.id().equals(id)) return r;
        // (the categories of old: their stores are gone, what was in them is not carried over)
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
        if (stack.isEmpty() || !is.test(stack)) return 0;
        return switch (this) {
            case FOOD -> {
                var food = stack.get(DataComponents.FOOD);
                yield food == null ? 0 : Math.max(1, food.nutrition());
            }
            case IRON -> stack.is(Items.IRON_BLOCK) || stack.is(Items.RAW_IRON_BLOCK) ? 9 : 1;
            case COAL -> stack.is(Items.COAL_BLOCK) ? 9 : 1;
            case WHEAT -> stack.is(Items.HAY_BLOCK) ? 9 : 1;
            default -> 1;
        };
    }
}
