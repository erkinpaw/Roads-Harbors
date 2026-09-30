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
    /** The sawmill's wares, cut from planks: stairs, slabs, doors, fences (for trade, and later for building). */
    STAIRS(Items.OAK_STAIRS),
    SLABS(Items.OAK_SLAB),
    DOORS(Items.OAK_DOOR),
    FENCES(Items.OAK_FENCE);

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
            case WOOD, STONE, IRON, COAL -> Kind.RAW;
            case PLANKS, STICKS, STAIRS, SLABS, DOORS, FENCES -> Kind.BUILDING;
        };
    }

    public static Res byId(String id) {
        for (Res r : values()) if (r.id().equals(id)) return r;
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
            case STAIRS -> stack.is(ItemTags.WOODEN_STAIRS) ? 1 : 0;
            case SLABS -> stack.is(ItemTags.WOODEN_SLABS) ? 1 : 0;
            case DOORS -> stack.is(ItemTags.WOODEN_DOORS) ? 1 : 0;
            case FENCES -> stack.is(ItemTags.WOODEN_FENCES) ? 1 : 0;
        };
    }
}
