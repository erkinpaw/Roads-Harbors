package org.webtrade.minecraftportsmod.colony;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;

/**
 * What a grown-up of the village does for a living. Children have no job yet. The tools of every trade come from the
 * village's smithy: bare hands without one, then wooden, stone and iron ones with its levels.
 */
public enum Job {
    /** Catches fish off the shore: food. */
    FISHER(Res.FOOD, 30, Items.FISHING_ROD, Items.FISHING_ROD, Items.FISHING_ROD, Items.FISHING_ROD),
    /** Works the fields: more food, but needs a field. */
    FARMER(Res.FOOD, 40, Items.AIR, Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE),
    /** Fells trees: wood to build with. */
    WOODCUTTER(Res.WOOD, 18, Items.AIR, Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE),
    /** Breaks stone, and with a good pick, iron ore. */
    MINER(Res.STONE, 15, Items.AIR, Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE),
    /** Keeps the village's stall: buys what the village lacks, sells what it has too much of. Makes nothing itself. */
    MERCHANT(null, 0, Items.EMERALD, Items.EMERALD, Items.EMERALD, Items.EMERALD),
    /** Works the sawmill: logs into planks and sticks. */
    SAWYER(null, 0, Items.IRON_AXE, Items.IRON_AXE, Items.IRON_AXE, Items.IRON_AXE),
    /** Goes out into the world to map it and find the other villages; eats twice as much. */
    SCOUT(null, 0, Items.COMPASS, Items.COMPASS, Items.COMPASS, Items.COMPASS),
    /** Works the smithy: the tools of every trade (wooden, stone, iron with its levels). Makes nothing to store. */
    SMITH(null, 0, Items.ANVIL, Items.ANVIL, Items.ANVIL, Items.ANVIL),
    /** Gathers what grows wild (berries, mushrooms, greens): a camp's first food, anywhere; no tools to it. */
    GATHERER(Res.FOOD, 28, Items.AIR, Items.AIR, Items.AIR, Items.AIR),
    /** Works the joiner's workshop: planks and sticks into joinery. Makes nothing of his own: see the workshop. */
    JOINER(null, 0, Items.CRAFTING_TABLE, Items.CRAFTING_TABLE, Items.CRAFTING_TABLE, Items.CRAFTING_TABLE);

    /** How fast the work goes: bare hands (no smithy), then the smithy's levels: wooden, stone, iron tools. */
    public static final double[] TOOL_SPEED = {0.6, 1.0, 1.3, 1.6};

    /** What the job brings in, and how much of it a day with wooden tools. */
    public final Res makes;
    public final int perDay;
    private final Item[] tools;

    Job(Res makes, int perDay, Item wood, Item stone, Item copper, Item iron) {
        this.makes = makes;
        this.perDay = perDay;
        this.tools = new Item[]{wood, stone, copper, iron};
    }

    /** The tool in hand at a tool level (0 bare hands, 1 wooden, 2 stone, 3 iron). */
    public Item tool(int level) {
        return tools[Math.max(0, Math.min(3, level))];
    }

    /** The plain tool (for icons). */
    public Item tool() {
        return tools[1];
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable("minecraftportsmod.job." + id());
    }

    public static Component toolName(int level) {
        return Component.translatable("minecraftportsmod.tools." + new String[]{"hands", "wood", "stone", "iron"}[Math.max(0, Math.min(3, level))]);
    }

    /** The trades whose tools wear out (the ones that make something). */
    public boolean usesTools() {
        return makes != null && this != GATHERER;
    }

    /** How many days of one worker's work a tool of a level lasts (a wooden one half a day, a stone one three, an iron one six). */
    public static final double[] TOOL_DAYS = {0, 0.5, 3, 6};

    /** What the smith makes a tool of, by its level: a log and two sticks; two cobblestone and two sticks; two iron, two sticks and coal. */
    public static java.util.Map<Res, Integer> toolRecipe(int level) {
        return switch (level) {
            case 1 -> java.util.Map.of(Res.WOOD, 1, Res.STICKS, 2);
            case 2 -> java.util.Map.of(Res.STONE, 2, Res.STICKS, 2);
            default -> java.util.Map.of(Res.IRON, 2, Res.STICKS, 2, Res.COAL, 1);
        };
    }

    public static Job byId(String id) {
        for (Job j : values()) if (j.id().equals(id)) return j;
        return null;
    }
}
