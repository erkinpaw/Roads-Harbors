package org.webtrade.minecraftportsmod.economy;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;

/**
 * Everything settlements produce, consume and trade. A good is an abstract unit tied to one item of the game
 * (what the player sees and trades). Prices are in emeralds per unit.
 */
public enum Good {
    // wood: every species is its own good, so forests of different villages complement each other
    OAK_LOG(Group.WOOD, 0.10, Items.OAK_LOG), SPRUCE_LOG(Group.WOOD, 0.11, Items.SPRUCE_LOG),
    BIRCH_LOG(Group.WOOD, 0.11, Items.BIRCH_LOG), DARK_OAK_LOG(Group.WOOD, 0.14, Items.DARK_OAK_LOG),
    ACACIA_LOG(Group.WOOD, 0.13, Items.ACACIA_LOG), JUNGLE_LOG(Group.WOOD, 0.13, Items.JUNGLE_LOG),
    CHERRY_LOG(Group.WOOD, 0.18, Items.CHERRY_LOG), MANGROVE_LOG(Group.WOOD, 0.14, Items.MANGROVE_LOG),
    OAK_PLANKS(Group.WOOD, 0.035, Items.OAK_PLANKS), SPRUCE_PLANKS(Group.WOOD, 0.038, Items.SPRUCE_PLANKS),
    BIRCH_PLANKS(Group.WOOD, 0.038, Items.BIRCH_PLANKS), DARK_OAK_PLANKS(Group.WOOD, 0.048, Items.DARK_OAK_PLANKS),
    ACACIA_PLANKS(Group.WOOD, 0.045, Items.ACACIA_PLANKS), JUNGLE_PLANKS(Group.WOOD, 0.045, Items.JUNGLE_PLANKS),
    CHERRY_PLANKS(Group.WOOD, 0.06, Items.CHERRY_PLANKS), MANGROVE_PLANKS(Group.WOOD, 0.048, Items.MANGROVE_PLANKS),

    // stone
    STONE(Group.STONE, 0.02, Items.COBBLESTONE), GRANITE(Group.STONE, 0.03, Items.GRANITE),
    DIORITE(Group.STONE, 0.03, Items.DIORITE), ANDESITE(Group.STONE, 0.03, Items.ANDESITE),
    SAND(Group.STONE, 0.02, Items.SAND), GLASS(Group.GOODS, 0.09, Items.GLASS),

    // ore and metal
    COAL(Group.METAL, 0.10, Items.COAL), IRON_ORE(Group.METAL, 0.25, Items.RAW_IRON),
    COPPER_ORE(Group.METAL, 0.12, Items.RAW_COPPER), GOLD_ORE(Group.METAL, 0.8, Items.RAW_GOLD),
    IRON(Group.METAL, 0.5, Items.IRON_INGOT), COPPER(Group.METAL, 0.22, Items.COPPER_INGOT),
    GOLD(Group.METAL, 1.4, Items.GOLD_INGOT), TOOLS(Group.GOODS, 3.0, Items.IRON_PICKAXE),

    // clay and pottery
    CLAY(Group.POTTERY, 0.05, Items.CLAY_BALL), BRICKS(Group.POTTERY, 0.08, Items.BRICK),
    TERRACOTTA(Group.POTTERY, 0.30, Items.TERRACOTTA), POTS(Group.POTTERY, 0.28, Items.FLOWER_POT),

    // food and farming
    WHEAT(Group.FOOD, 0.05, Items.WHEAT), BREAD(Group.FOOD, 0.13, Items.BREAD),
    FISH(Group.FOOD, 0.10, Items.COD), MUTTON(Group.FOOD, 0.12, Items.MUTTON), WOOL(Group.CLOTH, 0.15, Items.WOOL.white());

    public enum Group {WOOD, STONE, METAL, POTTERY, FOOD, CLOTH, GOODS}

    public final Group group;
    /** Emeralds per unit when supply exactly meets demand. */
    public final double basePrice;
    public final Item item;

    Good(Group group, double basePrice, Item item) {
        this.group = group;
        this.basePrice = basePrice;
        this.item = item;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Good byId(String id) {
        try {
            return valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Component displayName() {
        return Component.translatable(item.getDescriptionId());
    }

    /** Food value per unit (0 = not food). One resident eats 1 food value a day. */
    public double foodValue() {
        return switch (this) {
            case BREAD -> 1.0;
            case FISH -> 0.8;
            case MUTTON -> 1.0;
            case WHEAT -> 0.25;
            default -> 0;
        };
    }

    /** Fuel value per unit (0 = not fuel). One resident burns 0.3 fuel a day. */
    public double fuelValue() {
        if (this == COAL) return 1.0;
        if (group == Group.WOOD) return name().endsWith("_LOG") ? 0.35 : 0.1;
        return 0;
    }

    /** Share of the stock that spoils per day. */
    public double spoilage() {
        return switch (group) {
            case FOOD -> this == WHEAT ? 0.005 : 0.02;
            default -> 0;
        };
    }

    public boolean isLog() {
        return group == Group.WOOD && name().endsWith("_LOG");
    }

    /** The planks a log is sawn into (logs only). */
    public Good planks() {
        return byId(name().replace("_LOG", "_PLANKS"));
    }
}
