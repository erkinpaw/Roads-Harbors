package org.webtrade.minecraftportsmod.colony;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Locale;

/** What a field can be sown with. More kinds come with the farm's levels. */
public enum Crop {
    WHEAT(Blocks.WHEAT, Items.WHEAT, 4, 0),
    POTATO(Blocks.POTATOES, Items.POTATO, 6, 1),
    CARROT(Blocks.CARROTS, Items.CARROT, 5, 2),
    BEETROOT(Blocks.BEETROOTS, Items.BEETROOT, 5, 3);

    public final Block block;
    public final Item item;
    /** Food a harvested plant is worth. */
    public final int food;
    /** The level of the farm that opens this crop to the village (0: sown from the start). */
    public final int farmLevel;

    Crop(Block block, Item item, int food, int farmLevel) {
        this.block = block;
        this.item = item;
        this.food = food;
        this.farmLevel = farmLevel;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable("minecraftportsmod.crop." + id());
    }

    public static Crop byId(String id) {
        for (Crop c : values()) if (c.id().equals(id)) return c;
        return WHEAT;
    }

    /** Can this village sow it (its farm has come far enough)? */
    public boolean unlocked(Village v) {
        if (farmLevel == 0) return true;
        for (Building b : v.buildings) if (b.type == BuildingType.FARM && b.standing() && b.level >= farmLevel) return true;
        return false;
    }
}
