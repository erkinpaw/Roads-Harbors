package org.webtrade.minecraftportsmod.colony;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * What a village can build: the nodes of its development tree. A node is a kind of building; opening it (for a
 * price) lets the village put such buildings up, and each of them then grows level 1 → 2 → 3 on its own plot. The
 * next node of a branch opens once a building of the one before it has reached level 3 ({@link #parent}).
 * <p>
 * The middle of the village is different: one building that is rebuilt in place, the campfire into a square and the
 * square into one with a well ({@link #upgradeOf}). Sizes are half-extents of the plot.
 */
public enum BuildingType {
    // ---- the middle of the village: rebuilt in place, one step after another
    /** The heart of a camp: a fire and log benches around it. */
    CAMPFIRE(Branch.CENTER, null, null, 2, 0, null, 0xE06A2A, Items.CAMPFIRE, 1, true, Map.of(Res.WOOD, 2)),
    /** A paved square round the fire, with benches and lamps. */
    SQUARE(Branch.CENTER, null, CAMPFIRE, 3, 0, null, 0x9A9A9A, Items.STONE_BRICKS, 1, false, Map.of(Res.STONE, 50, Res.WOOD, 20)),
    /** The square with a well in the middle. */
    WELL(Branch.CENTER, null, SQUARE, 3, 0, null, 0x6080B0, Items.WATER_BUCKET, 1, false, Map.of(Res.STONE, 70, Res.WOOD, 10, Res.IRON, 5)),

    // ---- homes
    /** A wool tent with two sleeping places. What people live in before there are houses. */
    TENT(Branch.HOME, null, null, 2, 2, null, 0xD8D0C0, Items.WOOL.white(), 1, true, Map.of(Res.WOOD, 4)),
    /** The first real house: log frame, plank walls, two beds. */
    HUT(Branch.HOME, null, null, 3, 2, null, 0xA07040, Items.OAK_DOOR, 3, true, Map.of(Res.WOOD, 20, Res.WHEAT, 10)),
    /** A wooden house on a stone footing, four beds. */
    HOUSE(Branch.HOME, HUT, null, 4, 2, null, 0x8A5A30, Items.SPRUCE_DOOR, 3, false, Map.of(Res.WOOD, 30, Res.PLANKS, 20, Res.STONE, 15, Res.JOINERY, 12)),
    /** A wooden house of two storeys, six beds. */
    HOUSE_TALL(Branch.HOME, HOUSE, null, 4, 3, null, 0x7A4A28, Items.DARK_OAK_DOOR, 3, false, Map.of(Res.WOOD, 40, Res.PLANKS, 45, Res.STONE, 20, Res.JOINERY, 24)),
    /** A stone house, four beds: warmer, people are happier in it. */
    STONE_HOUSE(Branch.HOME, HOUSE, null, 4, 2, null, 0x8A8A8A, Items.IRON_DOOR, 3, false, Map.of(Res.STONE, 65, Res.PLANKS, 20, Res.WOOD, 10, Res.JOINERY, 14)),
    /** A stone house of two storeys, six beds. */
    STONE_HOUSE_TALL(Branch.HOME, STONE_HOUSE, null, 4, 3, null, 0x6A6A70, Items.STONE_BRICKS, 3, false,
            Map.of(Res.STONE, 110, Res.PLANKS, 35, Res.WOOD, 10, Res.METALWARE, 6, Res.JOINERY, 24)),

    // ---- stores
    /** A roofed store with barrels and crates: the village's stock is kept here. */
    STOREHOUSE(Branch.STORE, null, null, 3, 0, null, 0x7A5A3A, Items.BARREL, 3, true, Map.of(Res.WOOD, 20, Res.STONE, 4)),
    /** A closed storehouse with walls: holds twice as much. */
    STOREHOUSE_2(Branch.STORE, STOREHOUSE, null, 3, 0, null, 0x6A4A2A, Items.CHEST, 3, false,
            Map.of(Res.WOOD, 40, Res.PLANKS, 30, Res.STONE, 40, Res.IRON, 5)),

    // ---- the trades' own houses (away from the middle, by what they work); their levels are their tools
    /** The smithy: the smith makes the tools of every trade; without it people work with their bare hands. The ore branch starts here. */
    SMITHY(Branch.MINE, null, null, 3, 1, Job.SMITH, 0x5A5A60, Items.ANVIL, 3, true, Map.of(Res.WOOD, 40, Res.STONE, 15)),
    /** The miners' house by the rock: its mine, and iron ore now and then (opens once the smithy stands). */
    MINE_HOUSE(Branch.MINE, SMITHY, null, 3, 1, Job.MINER, 0x707070, Items.STONE_PICKAXE, 3, false, Map.of(Res.WOOD, 35, Res.STONE, 40)),
    /** The woodcutters' hut by the wood. */
    WOOD_HUT(Branch.WOOD, SMITHY, null, 3, 1, Job.WOODCUTTER, 0x6A8A40, Items.STONE_AXE, 3, false, Map.of(Res.WOOD, 45, Res.STONE, 15)),
    /** The sawmill: its sawyer turns logs into planks and sticks, many more than by hand, and none wasted. */
    SAWMILL(Branch.WOOD, WOOD_HUT, null, 3, 1, Job.SAWYER, 0x9A6A3A, Items.STONECUTTER, 3, false, Map.of(Res.WOOD, 60, Res.STONE, 25)),
    /** The joiner's workshop: planks and sticks into joinery (doors, stairs, chests...), which the houses are built with. */
    CARPENTER(Branch.WOOD, SAWMILL, null, 3, 1, Job.JOINER, 0xB07A4A, Items.CRAFTING_TABLE, 3, false, Map.of(Res.WOOD, 40, Res.PLANKS, 25, Res.STONE, 10)),
    /** The fishers' hut on the shore (one day, the village's port): its fisher catches fish off the shore. */
    FISH_HUT(Branch.COAST, null, null, 3, 1, Job.FISHER, 0x4A7AA0, Items.COD, 3, false, Map.of(Res.WOOD, 40, Res.STONE, 10)),

    // ---- trade
    /** The village stall: its merchant trades with the players (and one day, with other villages). */
    MARKET(Branch.TRADE, null, null, 3, 0, null, 0x3AA06A, Items.EMERALD, 3, false, Map.of(Res.WOOD, 40, Res.STONE, 10)),
    /** The cartographer's house: its scout goes out to map the land and find the other villages; the map is on its table. */
    CARTOGRAPHER(Branch.TRADE, null, null, 3, 1, Job.SCOUT, 0x4A6A9A, Items.COMPASS, 3, false, Map.of(Res.WOOD, 40, Res.PLANKS, 20, Res.STONE, 10)),

    // ---- food
    /** A field around a water channel: a farmer works it. What is sown is chosen in its menu. */
    FIELD(Branch.FOOD, null, null, 4, 0, null, 0xC8B040, Items.WHEAT, 3, true, Map.of(Res.WOOD, 6)),
    /** The farmstead: a barn by the fields. More fields, and more kinds of crops, with its levels. */
    FARM(Branch.FOOD, FIELD, null, 3, 1, Job.FARMER, 0xB08A30, Items.HAY_BLOCK, 3, false, Map.of(Res.WOOD, 45, Res.STONE, 20)),

    // the animals (husbandry): a fenced run with the keeper's lodge at its front; the animals in it are real ones
    /** The poultry yard: hens in a run; eggs and fowl, fed on grain. */
    COOP(Branch.FOOD, FARM, null, 5, 1, Job.HERDER, 0xD8C890, Items.EGG, 3, false, Map.of(Res.WOOD, 30, Res.PLANKS, 16, Res.WHEAT, 10)),
    /** The sheepfold: sheep, shorn for their wool; a little mutton; fed on grain. */
    SHEEP_PEN(Branch.FOOD, COOP, null, 6, 1, Job.HERDER, 0xE8E8E0, Items.WOOL.white(), 3, false, Map.of(Res.WOOD, 35, Res.PLANKS, 20, Res.WHEAT, 15)),
    /** The cattle barn: cows; milk and beef, and their hides; fed on grain, milked into the locksmith's buckets. */
    CATTLE_BARN(Branch.FOOD, SHEEP_PEN, null, 6, 1, Job.HERDER, 0x6A4A30, Items.MILK_BUCKET, 3, false,
            Map.of(Res.WOOD, 40, Res.PLANKS, 30, Res.STONE, 10, Res.WHEAT, 20)),
    /** The locksmith's (metalwork): iron and coal into buckets, chains, lanterns, hinges. */
    LOCKSMITH(Branch.MINE, MINE_HOUSE, null, 3, 1, Job.LOCKSMITH, 0x4A4A55, Items.LANTERN, 3, false, Map.of(Res.WOOD, 30, Res.STONE, 40, Res.IRON, 6));

    /**
     * The branches of the development tree. Three are what a village can make its speciality (food, wood, ore); the
     * rest every village has alike (its middle, homes, stores, trade, the shore).
     */
    public enum Branch {
        CENTER, HOME, STORE, MINE, WOOD, FOOD, TRADE, COAST;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public Component displayName() {
            return Component.translatable("minecraftportsmod.branch." + id());
        }

        /** Can a village make this its speciality? */
        public boolean trade() {
            return this == MINE || this == WOOD || this == FOOD;
        }

        public static Branch byId(String id) {
            // (the fishing and the farming branches of old are food now)
            if ("fish".equals(id) || "farm".equals(id)) return FOOD;
            for (Branch b : values()) if (b.id().equals(id)) return b;
            return null;
        }
    }

    /**
     * The two sub-branches of each speciality: what the village is known for. In its own sub-branch it works a fifth
     * better; in the other of its branch, as anyone does; in another branch's, at a loss (it can, but slowly: buying
     * from a village whose trade it is comes cheaper).
     */
    public enum Sub {
        FARMING(Branch.FOOD), HUSBANDRY(Branch.FOOD), LOGGING(Branch.WOOD), JOINERY(Branch.WOOD), MINING(Branch.MINE), METALWORK(Branch.MINE);

        public final Branch branch;

        Sub(Branch branch) {
            this.branch = branch;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public Component displayName() {
            return Component.translatable("minecraftportsmod.sub." + id());
        }

        public static Sub byId(String id) {
            for (Sub s : values()) if (s.id().equals(id)) return s;
            return null;
        }

        /** The first sub-branch of a branch (a village's own until it chooses). */
        public static Sub first(Branch b) {
            for (Sub s : values()) if (s.branch == b) return s;
            return null;
        }
    }

    /** The sub-branch this node belongs to (null: the base of its branch, or a branch every village has alike). */
    public Sub sub() {
        return switch (this) {
            case FARM -> Sub.FARMING;
            case SAWMILL -> Sub.LOGGING;
            case CARPENTER -> Sub.JOINERY;
            case COOP, SHEEP_PEN, CATTLE_BARN -> Sub.HUSBANDRY;
            case LOCKSMITH -> Sub.METALWORK;
            default -> null;
        };
    }

    /** The level a building of the node before this one must have for this one to open (the woods and the mine: once the smithy stands). */
    public int opensAt() {
        return this == MINE_HOUSE || this == WOOD_HUT || this == CARPENTER || this == COOP ? 1
                : this == SHEEP_PEN || this == CATTLE_BARN || this == LOCKSMITH ? 2 : 3;
    }

    public final Branch branch;
    /** The node before this one in the tree (a building of it at level 3 opens this one), or null for a root. */
    public final BuildingType parent;
    /** The middle of the village only: the building this one is rebuilt from, in place. */
    public final BuildingType upgradeOf;
    /** Half-extent of the plot (the building's footprint plus a margin). */
    public final int half;
    public final int beds;
    /** The trade whose people live and work here, or null. */
    public final Job job;
    /** Colour on the village map. */
    public final int color;
    public final Item icon;
    /** How far a building of this kind can grow (1: not at all). */
    public final int maxLevel;
    /** Open from the start, nothing to pay. */
    public final boolean free;
    private final Map<Res, Integer> cost;

    BuildingType(Branch branch, BuildingType parent, BuildingType upgradeOf, int half, int beds, Job job, int color, Item icon,
                 int maxLevel, boolean free, Map<Res, Integer> cost) {
        this.branch = branch;
        this.parent = parent;
        this.upgradeOf = upgradeOf;
        this.half = half;
        this.beds = beds;
        this.job = job;
        this.color = color;
        this.icon = icon;
        this.maxLevel = maxLevel;
        this.free = free;
        this.cost = cost;
    }

    public Map<Res, Integer> cost() {
        Map<Res, Integer> m = new EnumMap<>(Res.class);
        m.putAll(cost);
        return m;
    }

    public int cost(Res r) {
        return cost.getOrDefault(r, 0);
    }

    public boolean isHome() {
        return beds > 0;
    }

    /** One of the steps of the village's middle. */
    public boolean isCenter() {
        return branch == Branch.CENTER;
    }

    /** A node of the tree (the middle and the tents are not). */
    public boolean isNode() {
        return !isCenter() && this != TENT;
    }

    /** The middle's next step: the building this one can be rebuilt into, or null. */
    public BuildingType upgrade() {
        for (BuildingType t : values()) if (t.upgradeOf == this) return t;
        return null;
    }

    /** A house of one of the trades (miners, woodcutters, fishers, farmers). */
    public boolean isWorkshop() {
        return job != null;
    }

    /** A run of animals (the hens, the sheep, the cows). */
    public boolean isPen() {
        return this == COOP || this == SHEEP_PEN || this == CATTLE_BARN;
    }

    /** How far from the middle of the tree: 1 for a root. */
    public int depth() {
        int d = 1;
        for (BuildingType t = parent; t != null; t = t.parent) d++;
        return d;
    }

    /** The nodes that open after this one. */
    public java.util.List<BuildingType> children() {
        java.util.List<BuildingType> out = new java.util.ArrayList<>();
        for (BuildingType t : values()) if (t.parent == this) out.add(t);
        return out;
    }

    /** Is this the middle's step {@code other}, or one it was rebuilt into later? ("a square or better") */
    public boolean atLeast(BuildingType other) {
        for (BuildingType t = this; t != null; t = t.upgradeOf) if (t == other) return true;
        return false;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable("minecraftportsmod.building." + id());
    }

    public static BuildingType byId(String id) {
        for (BuildingType t : values()) if (t.id().equals(id)) return t;
        return null;
    }
}
