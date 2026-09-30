package org.webtrade.minecraftportsmod.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What players order from a building's people: so many pieces of an item by one of the building's recipes (the tree's,
 * see {@link TreeData}), paid for when ordered. Every day the building's workers do the orders first, oldest first,
 * with what the village's stores have of what a recipe takes; what is left of their day goes to the village's own
 * work. A finished order waits at the building until the player comes for it.
 */
public final class Orders {

    /** Orders a player can have open at one village. */
    public static final int MAX_OPEN = 4;
    /** Pieces in one order at most. */
    public static final int MAX_PIECES = 256;

    private Orders() {
    }

    public static final class Order {
        static final Codec<Order> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(o -> o.id),
                Codec.INT.fieldOf("building").forGetter(o -> o.building),
                Codec.INT.fieldOf("recipe").forGetter(o -> o.recipe),
                Codec.INT.fieldOf("count").forGetter(o -> o.count),
                Codec.INT.optionalFieldOf("made", 0).forGetter(o -> o.made),
                UUIDUtil.CODEC.fieldOf("player").forGetter(o -> o.player),
                Codec.STRING.optionalFieldOf("player_name", "").forGetter(o -> o.playerName),
                Codec.INT.optionalFieldOf("paid", 0).forGetter(o -> o.paid),
                Codec.LONG.optionalFieldOf("placed", 0L).forGetter(o -> o.placed)
        ).apply(i, Order::new));

        public final int id, building, recipe, count;
        int made;
        public final UUID player;
        public final String playerName;
        public final int paid;
        public final long placed;

        Order(int id, int building, int recipe, int count, int made, UUID player, String playerName, int paid, long placed) {
            this.id = id;
            this.building = building;
            this.recipe = recipe;
            this.count = count;
            this.made = made;
            this.player = player;
            this.playerName = playerName;
            this.paid = paid;
            this.placed = placed;
        }

        public int made() {
            return made;
        }

        public boolean done() {
            return made >= count;
        }
    }

    // ------------------------------------------------------------------ what can be ordered

    /** The trade that works a building (its people), or null. */
    static Job worker(BuildingType t) {
        return t.job;
    }

    /** The recipes of a building the village can make (all levels; {@link #open} says which are open now). */
    public static List<TreeData.Recipe> recipes(Building b) {
        List<TreeData.Recipe> out = new ArrayList<>();
        for (TreeData.Recipe r : TreeData.recipes(b.type)) if (r.item() != null && r.makeable()) out.add(r);
        return out;
    }

    public static boolean open(Building b, TreeData.Recipe r) {
        return b.standing() && r.lvl() <= b.level;
    }

    public static TreeData.Recipe recipe(Building b, int index) {
        List<TreeData.Recipe> all = TreeData.recipes(b.type);
        return index >= 0 && index < all.size() ? all.get(index) : null;
    }

    /**
     * What a piece costs, in hundredths of an emerald: what the making takes (at the stall's prices) and the work (half
     * an emerald a worker's day), shared among the pieces a making gives.
     */
    public static int cents(TreeData.Recipe r) {
        double worth = 0.5 / Math.max(1, r.per());
        for (TreeData.Input in : r.use()) {
            Res res = TreeData.res(in.cat());
            if (res != null) worth += Trade.base(res) * in.n();
        }
        return Math.max(1, (int) Math.round(worth * 100 / Math.max(1, r.n())));
    }

    /** Pieces the building's people make in a day by a recipe (all of them at it). */
    public static int perDay(Village v, TreeData.Recipe r) {
        Job j = worker(BuildingType.byId(r.building()));
        return Math.max(1, j == null ? 0 : v.workers(j)) * r.per() * r.n() * r.chance() / 100;
    }

    /** Does the store have what one making takes. */
    static boolean supplied(Village v, TreeData.Recipe r) {
        for (TreeData.Input in : r.use()) if (v.stock(TreeData.res(in.cat())) < in.n()) return false;
        return true;
    }

    /**
     * Days until an order is ready: the work on it and on the orders before it at its building, at what the
     * building's people do in a day (0: ready).
     */
    public static int eta(Village v, Order o) {
        if (o.done()) return 0;
        Building b = v.building(o.building);
        if (b == null) return -1;
        Job j = worker(b.type);
        int workers = j == null ? 0 : v.workers(j);
        if (workers == 0) return -1;
        double days = 0;
        for (Order q : v.orders) {
            if (q.building != o.building || q.done()) continue;
            TreeData.Recipe r = recipe(b, q.recipe);
            if (r == null) continue;
            int makings = (int) Math.ceil((q.count - q.made) / (double) r.n() * 100 / r.chance());
            days += makings / (double) r.per();
            if (q == o) break;
        }
        return Math.max(1, (int) Math.ceil(days / workers - 1e-9));
    }

    /** Days a new order of so many pieces would take, after the orders already there. */
    public static int etaNew(Village v, Building b, TreeData.Recipe r, int count) {
        Order o = new Order(-1, b.id, r.index(), count, 0, new UUID(0, 0), "", 0, 0);
        v.orders.add(o);
        try {
            return eta(v, o);
        } finally {
            v.orders.remove(o);
        }
    }

    public static List<Order> of(Village v, UUID player, int building) {
        List<Order> out = new ArrayList<>();
        for (Order o : v.orders) if (o.player.equals(player) && (building < 0 || o.building == building)) out.add(o);
        return out;
    }

    // ------------------------------------------------------------------ bought now, from what the village has

    /** The store an item of a recipe is kept in by the village (null: not kept, only made to order). */
    public static Res stored(TreeData.Recipe r) {
        if (r.item() == null) return null;
        String id = r.item();
        Res res = TreeData.res(r.cat());
        // (the store of wood is logs; of iron, ingots; of coal, coal: other things of the same category are not in it)
        if (res == Res.WOOD && !id.endsWith("_log")) return null;
        if (res == Res.IRON && !id.equals("iron_ingot")) return null;
        if (res == Res.STONE && !id.equals("cobblestone")) return null;
        if (res == Res.COAL && !id.equals("coal")) return null;
        return res;
    }

    private static Trade.Ware ware(Res res) {
        return Trade.WARES.get(res.ordinal());
    }

    /** Pieces the village has to spare of an item right now. */
    public static int inStock(Village v, TreeData.Recipe r) {
        Res res = stored(r);
        return res == null ? 0 : Trade.available(v, ware(res));
    }

    /** What a piece costs bought now (the stall's price), in hundredths; -1 if there is none to spare. */
    public static int nowCents(Village v, TreeData.Recipe r) {
        Res res = stored(r);
        return res == null ? -1 : Trade.sellCents(v, ware(res));
    }

    /** The player buys pieces of an item from what the village has. Returns the emeralds paid, 0 if it did not happen. */
    static int buyNow(ServerPlayer p, Village v, Building b, int recipe, int count) {
        TreeData.Recipe r = recipe(b, recipe);
        if (r == null) return 0;
        Res res = stored(r);
        if (res == null) return 0;
        Trade.Ware w = ware(res);
        int n = Math.min(count, Trade.maxBuy(p, v, w));
        if (n <= 0) return 0;
        int price = Trade.total(Trade.sellCents(v, w), n);
        int need = price;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && need > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.EMERALD)) continue;
            int take = Math.min(need, s.getCount());
            inv.removeItem(i, take);
            need -= take;
        }
        inv.setChanged();
        TreeData.TreeItem item = TreeData.item(r.item());
        v.add(res, -n * (res == Res.FOOD ? Trade.units(w) : item != null ? Math.max(1, item.units()) : 1));
        v.emeralds += price;
        ItemStack one = TreeData.stack(v, r, 1);
        for (int left = n; left > 0; ) {
            int k = Math.min(left, one.getMaxStackSize());
            ItemStack st = one.copyWithCount(k);
            if (!p.getInventory().add(st)) p.drop(st, false);
            left -= k;
        }
        return price;
    }

    // ------------------------------------------------------------------ the player's side

    /** A player orders pieces of a recipe. Returns the emeralds paid, 0 if it did not happen. */
    static int place(ServerPlayer p, Village v, Building b, int recipe, int count, long today) {
        TreeData.Recipe r = recipe(b, recipe);
        if (r == null || r.item() == null || !r.makeable() || !open(b, r)) return 0;
        Job j = worker(b.type);
        if (j == null || v.workers(j) == 0) return 0;
        if (of(v, p.getUUID(), -1).size() >= MAX_OPEN) return 0;
        count = Math.max(1, Math.min(count, MAX_PIECES));
        int price = Trade.total(cents(r), count);
        if (Trade.emeralds(p) < price) return 0;
        int need = price;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && need > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.EMERALD)) continue;
            int take = Math.min(need, s.getCount());
            inv.removeItem(i, take);
            need -= take;
        }
        inv.setChanged();
        v.emeralds += price;
        int id = 1;
        for (Order o : v.orders) id = Math.max(id, o.id + 1);
        v.orders.add(new Order(id, b.id, recipe, count, 0, p.getUUID(), p.getName().getString(), price, today));
        return price;
    }

    /** A player takes what is ready of their orders at a building. Returns the pieces given. */
    static int collect(ServerPlayer p, Village v, Building b) {
        int given = 0;
        for (Order o : new ArrayList<>(v.orders)) {
            if (!o.player.equals(p.getUUID()) || o.building != b.id || !o.done()) continue;
            TreeData.Recipe r = recipe(b, o.recipe);
            if (r == null) continue;
            ItemStack one = TreeData.stack(v, r, 1);
            for (int left = o.count; left > 0; ) {
                int k = Math.min(left, one.getMaxStackSize());
                ItemStack st = one.copyWithCount(k);
                if (!p.getInventory().add(st)) p.drop(st, false);
                left -= k;
            }
            given += o.count;
            v.orders.remove(o);
        }
        return given;
    }

    // ------------------------------------------------------------------ the day's work

    /**
     * The orders' share of the day: each building's workers work through its orders, oldest first, as long as their
     * day lasts and the stores have what the recipes take. Returns, per trade, the share of its day that went on
     * orders (0..1), for the village's own work to be cut by.
     */
    static java.util.Map<Job, Double> work(Village v) {
        java.util.Map<Job, Double> used = new java.util.EnumMap<>(Job.class);
        java.util.Map<Integer, Double> left = new java.util.HashMap<>();
        for (Order o : v.orders) {
            if (o.done()) continue;
            Building b = v.building(o.building);
            if (b == null || !b.standing()) continue;
            Job j = worker(b.type);
            int workers = j == null ? 0 : v.workers(j);
            if (workers == 0) continue;
            TreeData.Recipe r = recipe(b, o.recipe);
            if (r == null || !r.makeable()) continue;
            double day = left.computeIfAbsent(b.id, k -> (double) workers);
            double step = 1.0 / Math.max(1, r.per());
            while (!o.done() && day >= step - 1e-9 && supplied(v, r)) {
                for (TreeData.Input in : r.use()) {
                    Res res = TreeData.res(in.cat());
                    v.add(res, -in.n());
                    v.used.merge(res, in.n(), Integer::sum);
                }
                if (r.chance() >= 100 || VillageLife.RND.nextInt(100) < r.chance()) o.made = Math.min(o.count, o.made + r.n());
                day -= step;
                used.merge(j, step / workers, Double::sum);
            }
            left.put(b.id, day);
        }
        used.replaceAll((j, d) -> Math.min(1.0, d));
        return used;
    }
}
