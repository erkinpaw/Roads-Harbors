package org.webtrade.minecraftportsmod.colony;

import net.minecraft.server.level.ServerLevel;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The workshops' production, piece by piece. Each workshop (the sawmill, the joiner's, the smithy, the locksmith's,
 * the weaver's, the glassworks) has its own queue of orders, worked in the order they were placed: the players', the
 * village's own (what its building sites and its stores want), the traders'. One making of the order in hand at a
 * time: what it takes is taken from the store when it is begun (none there: it waits, nothing is used), and it is done
 * after so many seconds of work, only while a worker is actually at it (two at it, twice as fast; a second workshop of
 * the kind, a second queue). Tools are the workshop's own, in a slot of their own: so many uses of each kind; the best
 * there is is worked with (bare hands slower), a use worn out with every making.
 */
public final class Workshops {

    private Workshops() {
    }

    /**
     * One making: what it gives and takes, how long it takes one worker (seconds of work, with wooden tools at a
     * workshop of the first level), and the level of the workshop it opens at. {@code index}: its place in the
     * workshop's list (what orders refer to).
     */
    public record Recipe(BuildingType at, int index, int lvl, Res out, int n, Map<Res, Integer> in, int seconds) {
    }

    private static final Map<BuildingType, List<Recipe>> RECIPES = new EnumMap<>(BuildingType.class);

    private static void add(BuildingType at, int lvl, Res out, int n, int seconds, Object... in) {
        Map<Res, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < in.length; i += 2) m.put((Res) in[i], (Integer) in[i + 1]);
        List<Recipe> list = RECIPES.computeIfAbsent(at, k -> new ArrayList<>());
        list.add(new Recipe(at, list.size(), lvl, out, n, Map.copyOf(m), seconds));
    }

    static {
        // the sawmill: logs into planks, planks into sticks (as at a crafting table)
        add(BuildingType.SAWMILL, 1, Res.PLANKS, 4, 12, Res.WOOD, 1);
        add(BuildingType.SAWMILL, 1, Res.STICKS, 4, 10, Res.PLANKS, 2);
        // the joiner's: the woodwork of the houses (of the village's own wood); beds and barrels at the second level
        add(BuildingType.CARPENTER, 1, Res.STAIRS, 4, 30, Res.PLANKS, 6);
        add(BuildingType.CARPENTER, 1, Res.SLAB, 6, 20, Res.PLANKS, 3);
        add(BuildingType.CARPENTER, 1, Res.DOOR, 3, 40, Res.PLANKS, 6);
        add(BuildingType.CARPENTER, 1, Res.TRAPDOOR, 2, 35, Res.PLANKS, 6);
        add(BuildingType.CARPENTER, 1, Res.FENCE, 3, 30, Res.PLANKS, 4, Res.STICKS, 2);
        add(BuildingType.CARPENTER, 1, Res.FENCE_GATE, 1, 25, Res.PLANKS, 2, Res.STICKS, 4);
        add(BuildingType.CARPENTER, 1, Res.LADDER, 3, 25, Res.STICKS, 7);
        add(BuildingType.CARPENTER, 1, Res.CHEST, 1, 45, Res.PLANKS, 8);
        add(BuildingType.CARPENTER, 2, Res.BARREL, 1, 45, Res.PLANKS, 6, Res.SLAB, 2);
        add(BuildingType.CARPENTER, 2, Res.BED, 1, 60, Res.PLANKS, 3, Res.WOOL, 3);
        // the smithy: the tools of every trade, the better ones with its levels
        add(BuildingType.SMITHY, 1, Res.TOOLS1, 1, 40, Res.WOOD, 1, Res.STICKS, 2);
        add(BuildingType.SMITHY, 2, Res.TOOLS2, 1, 60, Res.STONE, 2, Res.STICKS, 2);
        add(BuildingType.SMITHY, 3, Res.TOOLS3, 1, 90, Res.IRON, 2, Res.STICKS, 2, Res.COAL, 1);
        // the locksmith's
        add(BuildingType.LOCKSMITH, 1, Res.LANTERN, 1, 45, Res.IRON, 1, Res.COAL, 1);
        add(BuildingType.LOCKSMITH, 1, Res.CHAIN, 1, 30, Res.IRON, 1);
        add(BuildingType.LOCKSMITH, 2, Res.IRON_BARS, 16, 60, Res.IRON, 6);
        add(BuildingType.LOCKSMITH, 2, Res.BUCKET, 1, 50, Res.IRON, 3);
        // the weaver's
        add(BuildingType.WEAVER, 1, Res.CARPET, 3, 30, Res.WOOL, 2);
        add(BuildingType.WEAVER, 2, Res.BANNER, 1, 60, Res.WOOL, 6, Res.STICKS, 1);
        // the glassworks (the sand dug on the shore by its people)
        add(BuildingType.GLASSWORKS, 1, Res.GLASS, 4, 40, Res.COAL, 1);
        add(BuildingType.GLASSWORKS, 1, Res.GLASS_PANE, 16, 40, Res.GLASS, 6);
    }

    /** A workshop's recipes, all levels (empty: it makes nothing to order). */
    public static List<Recipe> recipes(BuildingType t) {
        return RECIPES.getOrDefault(t, List.of());
    }

    public static Recipe recipe(BuildingType t, int index) {
        List<Recipe> all = recipes(t);
        return index >= 0 && index < all.size() ? all.get(index) : null;
    }

    /** Is it a workshop with a queue? */
    public static boolean workshop(BuildingType t) {
        return !recipes(t).isEmpty();
    }

    /** Is a recipe open at a workshop now (it stands, and is of the level)? */
    public static boolean open(Building b, Recipe r) {
        return b.standing() && r.lvl() <= b.level;
    }

    /** The open recipe of a workshop that makes something (null: none). */
    static Recipe makes(Building b, Res r) {
        for (Recipe x : recipes(b.type)) if (x.out() == r && open(b, x)) return x;
        return null;
    }

    /** Can the village make this thing (a workshop of it stands with the recipe open and someone to work it)? */
    static boolean canMake(Village v, Res r) {
        for (Building b : v.buildings) if (b.type.job != null && makes(b, r) != null && v.workers(b.type.job) > 0) return true;
        return false;
    }

    // ------------------------------------------------------------------ the tools' slot

    /** Uses one tool put in the slot gives, by its kind (1 wooden, 2 stone, 3 iron). */
    public static final int[] USES = {0, 10, 20, 30};
    /** Work done by bare hands, and with tools of each kind. */
    static final double[] SPEED = Job.TOOL_SPEED;
    /** Uses kept in the slot: under this, a worker fetches a tool from the store. */
    static final int REFILL = 3;

    /** The best kind of tool in the slot (0: none, bare hands). */
    public static int toolTier(Building b) {
        for (int l = 3; l >= 1; l--) if (b.tools[l - 1] > 0) return l;
        return 0;
    }

    /** Tools from the store into the slot, while it runs low: the best kind the store has. */
    static void refill(Village v, Building b) {
        int uses = b.tools[0] + b.tools[1] + b.tools[2];
        if (uses >= REFILL) return;
        for (int l = 3; l >= 1; l--) {
            if (v.stock(Res.tools(l)) <= 0) continue;
            v.add(Res.tools(l), -1);
            v.used.merge(Res.tools(l), 1, Integer::sum);
            b.tools[l - 1] += USES[l];
            return;
        }
    }

    /** Tools a player puts in the slot (of a kind): so many uses more. */
    public static void putTools(Building b, int tier, int count) {
        b.tools[tier - 1] += USES[tier] * count;
    }

    /**
     * All the tools a player carries put in a workshop's slot: each gives its kind's uses, as much of them as it has
     * left of its wear. Returns how many tools.
     */
    public static int takeTools(net.minecraft.server.level.ServerPlayer p, Building b) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var s = inv.getItem(i);
            for (int t = 1; t <= 3; t++) {
                if (Res.tools(t).unitsOf(s) <= 0) continue;
                double left = s.isDamageableItem() ? 1 - s.getDamageValue() / (double) Math.max(1, s.getMaxDamage()) : 1;
                b.tools[t - 1] += Math.max(1, (int) Math.round(USES[t] * left)) * s.getCount();
                n += s.getCount();
                inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                break;
            }
        }
        inv.setChanged();
        return n;
    }


    // ------------------------------------------------------------------ the work

    /** How fast a workshop works with what is in its slot and its level: a fifth faster a level. */
    static double speed(Building b) {
        return SPEED[toolTier(b)] * (1 + 0.2 * (Math.max(1, b.level) - 1));
    }

    /** The order in hand at a workshop: the oldest one not done (null: nothing to make). */
    public static Orders.Order current(Village v, Building b) {
        Orders.Order best = null;
        for (Orders.Order o : v.orders) {
            if (o.building != b.id || o.done()) continue;
            if (best == null || o.id < best.id) best = o;
        }
        return best;
    }

    /** The orders of a workshop, oldest first (done ones aside). */
    public static List<Orders.Order> queue(Village v, Building b) {
        List<Orders.Order> out = new ArrayList<>();
        for (Orders.Order o : v.orders) if (o.building == b.id && !o.done()) out.add(o);
        out.sort((x, y) -> Integer.compare(x.id, y.id));
        return out;
    }

    /** The people of a workshop's trade who work at it: those who live there, and the rest of the trade at the first of its kind. */
    static List<Dweller> hands(Village v, Building b) {
        List<Dweller> out = new ArrayList<>();
        Job j = b.type.job;
        if (j == null) return out;
        // (those who live at a workshop of the kind work there; the rest of the trade shared out among the workshops)
        List<Building> shops = new ArrayList<>();
        for (Building o : v.buildings) if (o.type == b.type && o.standing()) shops.add(o);
        if (shops.isEmpty()) return out;
        int[] count = new int[shops.size()];
        List<Dweller> rest = new ArrayList<>();
        for (Dweller d : v.dwellers) {
            if (d.job != j || d.away) continue;
            int at = -1;
            for (int k = 0; k < shops.size(); k++) if (shops.get(k).id == d.home) at = k;
            if (at < 0) {
                rest.add(d);
                continue;
            }
            count[at]++;
            if (shops.get(at) == b) out.add(d);
        }
        for (Dweller d : rest) {
            int at = 0;
            for (int k = 1; k < shops.size(); k++) if (count[k] < count[at]) at = k;
            count[at]++;
            if (shops.get(at) == b) out.add(d);
        }
        return out;
    }

    /** Is it work time in the village (the hours the trades are at their work)? */
    static boolean workTime(VillageData data) {
        int t = (int) ((long) data.dayTicks * 24000 / Math.max(1, data.dayLength));
        return t >= Routine.WORK_FROM && t < Routine.WORK_TO;
    }

    /** Seconds of work in a working day. */
    static double workSeconds(VillageData data) {
        return (Routine.WORK_TO - Routine.WORK_FROM) / 24000.0 * data.dayLength / 20.0;
    }

    /** How far a body has to be from where the work is done to be at it. */
    static final double AT_WORK = 5;

    /**
     * The workers at a workshop now: where its village is seen, the bodies at the workbench; where it is not, all its
     * hands in their working hours (the same work, at the same pace, unseen).
     */
    static int workers(ServerLevel level, VillageData data, Village v, Building b, boolean seen) {
        List<Dweller> hands = hands(v, b);
        if (hands.isEmpty() || !workTime(data)) return 0;
        if (!seen) return hands.size();
        int n = 0;
        var spot = b.blueprint(v.wood).workSpot;
        for (Dweller d : hands) {
            if (d.body == null) {
                n++;
                continue;
            }
            var e = level.getEntity(d.body);
            if (e == null || !e.isAlive()) {
                n++;
                continue;
            }
            if (e.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5) <= AT_WORK * AT_WORK) n++;
        }
        return n;
    }

    /** Every second: each workshop's order in hand goes on, if there is someone at it. */
    static void tick(ServerLevel level, VillageData data) {
        for (Village v : data.all()) {
            boolean seen = VillageManager.watched(level, v);
            for (Building b : new ArrayList<>(v.buildings)) {
                if (!workshop(b.type) || !b.standing()) continue;
                work(v, b, workers(level, data, v, b, seen), 1.0, data.day);
            }
        }
    }

    /** A day skipped: each workshop's day of work at once (its hands all at it in their hours). */
    static void skipDay(VillageData data, Village v) {
        double seconds = workSeconds(data);
        for (Building b : new ArrayList<>(v.buildings)) {
            if (!workshop(b.type) || !b.standing()) continue;
            int hands = hands(v, b).size();
            if (hands == 0) continue;
            for (double s = 0; s < seconds; s += 5) work(v, b, hands, 5, data.day);
        }
    }

    /** {@code seconds} of work at a workshop by {@code workers}. */
    static void work(Village v, Building b, int workers, double seconds, long today) {
        Orders.Order o = current(v, b);
        Recipe r = o == null ? null : recipe(b.type, o.recipe);
        if (o == null || r == null || !open(b, r)) {
            if (o != null && r == null) v.orders.remove(o);
            b.progress = 0;
            return;
        }
        if (workers <= 0) return;
        if (!b.taken) {
            // what one making takes, from the store (none there: it waits)
            for (var e : r.in().entrySet()) if (v.stock(e.getKey()) < e.getValue()) return;
            for (var e : r.in().entrySet()) {
                v.add(e.getKey(), -e.getValue());
                v.used.merge(e.getKey(), e.getValue(), Integer::sum);
                v.workshop(b.type, e.getKey(), 0, e.getValue());
            }
            b.taken = true;
            b.progress = 0;
        }
        refill(v, b);
        b.progress += seconds * workers * speed(b);
        if (b.progress < r.seconds()) return;
        // done: a use of the tools worn out, what it gives where it goes
        int tier = toolTier(b);
        if (tier > 0) b.tools[tier - 1]--;
        b.progress = 0;
        b.taken = false;
        int n = Math.min(r.n(), o.count - o.made);
        o.made += n;
        if (o.village()) {
            // the village's own: to the store (what is over the order's count, too: a making gives what it gives)
            v.add(r.out(), r.n());
            v.made.merge(r.out(), r.n(), Integer::sum);
            v.workshop(b.type, r.out(), r.n(), 0);
            if (o.done()) v.orders.remove(o);
        } else if (r.n() > n) {
            // (a player's order: the pieces over it to the store)
            v.add(r.out(), r.n() - n);
        }
    }

    /** Seconds until the order in hand is done (and how far along it is: 0..1); {-1, 0} if nothing. */
    public static double[] progress(Village v, Building b) {
        Orders.Order o = current(v, b);
        Recipe r = o == null ? null : recipe(b.type, o.recipe);
        if (r == null) return new double[]{-1, 0};
        return new double[]{Math.max(0, (r.seconds() - b.progress) / Math.max(0.01, speed(b))), Math.min(1, b.progress / r.seconds())};
    }

    // ------------------------------------------------------------------ the village's own orders

    /**
     * Once a day: what the village wants of what its workshops make (for its building sites, and what its stores keep)
     * and has not got nor ordered yet goes into a queue: the workshop of the kind with the shortest one. Of the tools,
     * the best kind the smithy can make.
     */
    static void plan(Village v, long today) {
        for (Res r : Res.values()) {
            if (r.toolLevel() > 0 && r.toolLevel() != bestTools(v)) continue;
            int want = VillageLife.target(v, r) - v.stock(r);
            if (want <= 0) continue;
            int ordered = 0;
            for (Orders.Order o : v.orders) {
                Building ob = v.building(o.building);
                Recipe rr = ob == null ? null : recipe(ob.type, o.recipe);
                if (rr != null && rr.out() == r && o.village()) ordered += o.count - o.made;
            }
            if (ordered >= want) continue;
            Building at = null;
            Recipe how = null;
            int shortest = Integer.MAX_VALUE;
            for (Building b : v.buildings) {
                Recipe x = makes(b, r);
                if (x == null || b.type.job == null || v.workers(b.type.job) == 0) continue;
                int q = queue(v, b).size();
                if (q < shortest) {
                    shortest = q;
                    at = b;
                    how = x;
                }
            }
            if (at == null) continue;
            // (a few makings at a time: the queue moves on to other things between them)
            int count = Math.min(want - ordered, how.n() * 6);
            v.orders.add(Orders.villageOrder(v, at, how.index(), count, today));
        }
    }

    /**
     * What the workshops' queues will take of a thing for the makings still to do (capped: a long queue is not all
     * bought for at once).
     */
    static int needs(Village v, Res r) {
        int n = 0;
        for (Orders.Order o : v.orders) {
            if (o.done()) continue;
            Building b = v.building(o.building);
            Recipe x = b == null ? null : recipe(b.type, o.recipe);
            if (x == null) continue;
            Integer per = x.in().get(r);
            if (per == null) continue;
            n += (int) Math.ceil((o.count - o.made) / (double) x.n()) * per;
        }
        return Math.min(n, 200);
    }

    /** The best kind of tools the village's smithy makes (0: none). */
    static int bestTools(Village v) {
        int best = 0;
        for (Building b : v.buildings) {
            if (b.type != BuildingType.SMITHY || !b.standing()) continue;
            for (Recipe r : recipes(b.type)) if (open(b, r) && Tree.affordable(v, r.in())) best = Math.max(best, r.out().toolLevel());
            if (best == 0) best = 1;
        }
        return best;
    }

    /** Logs a line for tests: each workshop's queue and progress. */
    public static String describe(Village v) {
        StringBuilder out = new StringBuilder();
        for (Building b : v.buildings) {
            if (!workshop(b.type) || !b.standing()) continue;
            out.append(b.type.id()).append('#').append(b.id).append(" tools ").append(b.tools[0]).append('/').append(b.tools[1]).append('/')
                    .append(b.tools[2]).append(" [");
            for (Orders.Order o : queue(v, b)) {
                Recipe r = recipe(b.type, o.recipe);
                out.append(r == null ? "?" : r.out().id()).append(' ').append(o.made()).append('/').append(o.count).append(o.village() ? "v" : "p").append(' ');
            }
            out.append("] ");
        }
        return out.toString();
    }

    static {
        Minecraftportsmod.LOGGER.debug("workshops: {} kinds", RECIPES.size());
    }
}
