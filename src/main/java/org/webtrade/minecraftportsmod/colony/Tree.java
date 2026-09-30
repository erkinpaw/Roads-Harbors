package org.webtrade.minecraftportsmod.colony;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The village's development tree. A node (a kind of building) is opened for a price, dearer than the building
 * itself; then the village may put such buildings up, and each of them grows level 1 → 3 on its plot. The next node
 * of a branch can be opened once a building of the one before it stands at level 3.
 * <p>
 * Nothing else holds a village back: there are no levels to reach. What keeps it from having everything is the
 * price: each step from the middle costs more, the branch of the village's speciality less, and every node opened
 * in the other trades makes the next one there dearer still. A village does best going deep into one trade; one
 * good at everything must be rich.
 */
public final class Tree {

    /** Where a node stands for a village. */
    public enum Node {
        /** The node before it isn't open: not even known what it is. */
        HIDDEN,
        /** Known, but no building of the node before it has reached level 3 yet. */
        WAIT,
        /** Can be opened (if the village can pay). */
        READY,
        /** Open: the village builds these. */
        OPEN
    }

    private Tree() {
    }

    /** One condition of a node or a level. */
    public record Condition(Component label, int have, int need) {
        public boolean ok() {
            return have >= need;
        }
    }

    /** One part of a node's price: why, and by how much (in hundredths). */
    public record Factor(Component label, int percent) {
    }

    // ------------------------------------------------------------------ the nodes

    public static boolean unlocked(Village v, BuildingType t) {
        return t.free || v.unlocked.contains(t);
    }

    /** A building of this kind at level 3 stands (opening what comes after it). */
    public static boolean grown(Village v, BuildingType t) {
        for (Building b : v.buildings) if (b.type == t && b.standing() && b.level >= t.maxLevel) return true;
        return false;
    }

    /** A building of this kind stands at this level or higher. */
    public static boolean stands(Village v, BuildingType t, int level) {
        for (Building b : v.buildings) if (b.type == t && b.standing() && b.level >= level) return true;
        return false;
    }

    public static Node node(Village v, BuildingType t) {
        if (unlocked(v, t)) return Node.OPEN;
        if (t.parent != null && !unlocked(v, t.parent)) return Node.HIDDEN;
        return t.parent == null || stands(v, t.parent, t.opensAt()) ? Node.READY : Node.WAIT;
    }

    /**
     * Opening a node: twice a building's worth for the first of a branch, two and a half times after it; +40% a step
     * from the middle; -25% in the speciality; +35% per node open deeper in the other trades (the first houses of the
     * trades, every village may have: going deep is what specialises it).
     */
    public static List<Factor> factors(Village v, BuildingType t) {
        List<Factor> out = new ArrayList<>();
        out.add(new Factor(Component.translatable("minecraftportsmod.tree.f_base"), t.parent == null ? 200 : 250));
        int d = t.depth();
        if (d > 1) out.add(new Factor(Component.translatable("minecraftportsmod.tree.f_depth", d - 1), 100 + 40 * (d - 1)));
        if (t.branch == v.focus) {
            out.add(new Factor(Component.translatable("minecraftportsmod.tree.f_focus"), 75));
        } else if (t.branch.trade() && t.parent != null) {
            int others = others(v, t.branch);
            if (others > 0) out.add(new Factor(Component.translatable("minecraftportsmod.tree.f_others", others), 100 + 35 * others));
        }
        return out;
    }

    /** Nodes open in the trades other than this one and the village's own speciality. */
    static int others(Village v, BuildingType.Branch branch) {
        int n = 0;
        for (BuildingType t : v.unlocked) if (t.branch.trade() && t.parent != null && t.branch != branch && t.branch != v.focus) n++;
        return n;
    }

    public static Map<Res, Integer> unlockCost(Village v, BuildingType t) {
        double k = 1;
        for (Factor f : factors(v, t)) k *= f.percent() / 100.0;
        Map<Res, Integer> out = new EnumMap<>(Res.class);
        for (Res r : Res.values()) {
            int c = t.cost(r);
            if (c > 0) out.put(r, (int) Math.max(1, Math.round(c * k / 5.0) * 5));
        }
        return out;
    }

    /** Each building of a kind past the first three costs a quarter more again than the one before it. */
    public static final double DEARER = 0.25;

    /**
     * What a new building of this kind costs this village now: its price, and a quarter more for each one of the kind
     * it has past the first two (the fourth hut a quarter dearer, the fifth half as dear again...): the good plots by
     * the middle are taken, the wood and stone near by used up, and every next one stands farther out. So a village
     * that keeps building huts soon finds a proper house the better buy.
     */
    public static Map<Res, Integer> price(Village v, BuildingType t) {
        int have = 0;
        for (Building b : v.buildings) if (b.type == t && b.state != Building.State.DEMOLISHING) have++;
        double k = 1 + DEARER * Math.max(0, have - 2);
        Map<Res, Integer> out = new EnumMap<>(Res.class);
        for (Res r : Res.values()) {
            int c = t.cost(r);
            if (c > 0) out.put(r, (int) Math.ceil(c * k));
        }
        return out;
    }

    public static boolean affordable(Village v, Map<Res, Integer> cost) {
        for (var e : cost.entrySet()) if (v.stock(e.getKey()) < e.getValue()) return false;
        return true;
    }

    /** Opens a node, paying for it. Returns false if it can't be opened now or the store can't pay. */
    static boolean unlock(Village v, BuildingType t) {
        if (!t.isNode() || node(v, t) != Node.READY) return false;
        Map<Res, Integer> cost = unlockCost(v, t);
        if (!affordable(v, cost)) return false;
        cost.forEach((r, n) -> v.add(r, -n));
        v.unlocked.add(t);
        return true;
    }

    // ------------------------------------------------------------------ the buildings

    /** How many fields the farm allows: one without a farm, then one more per level of the farm. */
    public static int fieldLimit(Village v) {
        int best = 0;
        for (Building b : v.buildings) if (b.type == BuildingType.FARM && b.standing()) best = Math.max(best, b.level);
        return 1 + best;
    }

    /** How many storehouses (of either kind) there may be: one, and one more for every four people. */
    public static int storeLimit(Village v) {
        return 1 + v.population() / 4;
    }

    static int stores(Village v) {
        return v.count(BuildingType.STOREHOUSE, false) + v.count(BuildingType.STOREHOUSE_2, false);
    }

    /** What stops the village putting up another of these now (its materials aside); empty if nothing. */
    public static List<Condition> conditions(Village v, BuildingType t) {
        List<Condition> out = new ArrayList<>();
        switch (t) {
            case FIELD -> out.add(new Condition(Component.translatable("minecraftportsmod.vreq.fields", fieldLimit(v)),
                    fieldLimit(v) - v.count(BuildingType.FIELD, false), 1));
            case STOREHOUSE, STOREHOUSE_2 -> out.add(new Condition(Component.translatable("minecraftportsmod.vreq.stores", storeLimit(v)),
                    storeLimit(v) - stores(v), 1));
            default -> {
            }
        }
        if (t.isCenter() && t.upgradeOf != null) {
            out.add(new Condition(Component.translatable("minecraftportsmod.vreq.people"), v.population(), centerPeople(t)));
        }
        return out;
    }

    /** The people a village needs before its middle is rebuilt into this. */
    public static int centerPeople(BuildingType t) {
        return switch (t) {
            case SQUARE -> 4;
            case WELL -> 6;
            default -> 0;
        };
    }

    /** Buildings that there is only one of in a village. */
    static boolean single(BuildingType t) {
        return t.isWorkshop() || t == BuildingType.MARKET;
    }

    /** Can the village start a new building of this kind now (open, room for it, nothing of the kind waiting)? */
    public static boolean open(Village v, BuildingType t) {
        if (t == BuildingType.TENT || t.isCenter() || !unlocked(v, t)) return false;
        for (Condition c : conditions(v, t)) if (!c.ok()) return false;
        for (Building b : v.buildings) if (b.type == t && (b.state == Building.State.PLANNED || b.state == Building.State.BUILDING)) return false;
        return !single(t) || v.count(t, false) == 0;
    }

    /** The middle of the village as it stands now. */
    public static Building center(Village v) {
        for (Building b : v.buildings) if (b.type.isCenter() && b.state != Building.State.DEMOLISHING) return b;
        return null;
    }

    /** The next step of the middle, or null if there is none. */
    public static BuildingType centerNext(Village v) {
        Building c = center(v);
        return c == null ? null : c.type.upgrade();
    }

    /** Can the middle be rebuilt into its next step now (people enough, not already under way)? */
    public static boolean centerReady(Village v) {
        BuildingType next = centerNext(v);
        if (next == null || v.population() < centerPeople(next)) return false;
        for (Building b : v.buildings) if (b.type == next) return false;
        return true;
    }

    // ------------------------------------------------------------------ the levels of a building

    /**
     * Raising a building to {@code level}: half its price for level 2, three quarters for level 3, and at level 3
     * half the logs are wanted as planks.
     */
    public static Map<Res, Integer> levelCost(BuildingType t, int level) {
        Map<Res, Integer> out = new EnumMap<>(Res.class);
        double k = level >= 3 ? 0.75 : 0.5;
        for (Res r : Res.values()) {
            int c = t.cost(r);
            if (c > 0) out.put(r, (int) Math.max(1, Math.round(c * k)));
        }
        // the lamps and torches the level brings
        out.put(Res.COAL, level >= 3 ? 2 : 1);
        if (level >= 3 && out.getOrDefault(Res.WOOD, 0) >= 4) {
            int logs = out.get(Res.WOOD), planks = logs / 2;
            out.put(Res.WOOD, logs - planks);
            out.merge(Res.PLANKS, planks * 2, Integer::sum);
        }
        return out;
    }

    /** Can this building be raised a level now? */
    public static boolean canRaise(Village v, Building b) {
        return b.standing() && !b.upgrading() && b.level < b.type.maxLevel;
    }

    /** Starts raising a building to its next level: the materials will be brought, then the additions go up. */
    static boolean raise(Village v, Building b, long today) {
        if (!canRaise(v, b) || VillageLife.declined(v, "raise:" + b.id, today)) return false;
        b.goal = b.level + 1;
        if (!v.order.contains(b.id)) v.order.add(b.id);
        b.delivered.clear();
        b.price.clear();
        b.price.putAll(levelCost(b.type, b.goal));
        // the additions go up after what stands already
        b.work = b.blueprint(v.wood).upTo(b.level);
        v.log(today, Component.translatable("minecraftportsmod.vlog.raise", b.type.displayName(), b.goal));
        return true;
    }

    // ------------------------------------------------------------------ the title

    /** What the village needs for its next title (only people: the title follows the size of the village). */
    public static List<Condition> level(Village v, Village.Level level) {
        List<Condition> out = new ArrayList<>();
        if (level == null) return out;
        out.add(new Condition(Component.translatable("minecraftportsmod.vreq.people"), v.population(), level.people));
        return out;
    }

    /** The house of the trade with the most people in it (the one that helps most) among those open. */
    static BuildingType bestWorkshop(Village v) {
        BuildingType best = null;
        int most = -1;
        for (BuildingType t : new BuildingType[]{BuildingType.MINE_HOUSE, BuildingType.WOOD_HUT, BuildingType.FISH_HUT, BuildingType.FARM}) {
            int n = v.workers(t.job);
            if (n > most && open(v, t)) {
                most = n;
                best = t;
            }
        }
        return best;
    }
}
