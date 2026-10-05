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
 * The orders in the workshops' queues (see {@link Workshops}): so many pieces of a thing by one of a workshop's
 * recipes. A player's is paid for when ordered and waits at the workshop when done until the player comes for it; the
 * village's own (what its building sites and stores want) go to the store as they are made. All in one queue per
 * workshop, the oldest first.
 */
public final class Orders {

    /** Orders a player can have open at one village. */
    public static final int MAX_OPEN = 4;
    /** Pieces in one order at most. */
    public static final int MAX_PIECES = 256;
    /** Whose an order of the village's own is. */
    static final UUID VILLAGE = new UUID(0, 0);

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

        /** The village's own order (no player's). */
        public boolean village() {
            return VILLAGE.equals(player);
        }
    }

    // ------------------------------------------------------------------ what can be ordered

    /** The trade that works a building (its people), or null. */
    static Job worker(BuildingType t) {
        return t.job;
    }

    /** The recipes of a workshop (all levels; {@link #open} says which are open now). */
    public static List<Workshops.Recipe> recipes(Building b) {
        return Workshops.recipes(b.type);
    }

    public static boolean open(Building b, Workshops.Recipe r) {
        return Workshops.open(b, r);
    }

    public static Workshops.Recipe recipe(Building b, int index) {
        return Workshops.recipe(b.type, index);
    }

    /**
     * What a piece costs, in hundredths of an emerald: what the making takes (at the stall's prices) and the work (half
     * an emerald for ten minutes of it), shared among the pieces a making gives.
     */
    public static int cents(Workshops.Recipe r) {
        double worth = 0.5 * r.seconds() / 600.0;
        for (var e : r.in().entrySet()) worth += Trade.base(e.getKey()) * e.getValue();
        return Math.max(1, (int) Math.round(worth * 100 / Math.max(1, r.n())));
    }

    /** Pieces the workshop's people make in a day by a recipe (all of them at it, with what tools it has). */
    public static int perDay(Village v, Building b, Workshops.Recipe r, double workSeconds) {
        int hands = Math.max(1, Workshops.hands(v, b).size());
        return (int) Math.floor(hands * workSeconds * Workshops.speed(b) / r.seconds()) * r.n();
    }

    /** Does the store have what one making takes. */
    static boolean supplied(Village v, Workshops.Recipe r) {
        for (var e : r.in().entrySet()) if (v.stock(e.getKey()) < e.getValue()) return false;
        return true;
    }

    /**
     * Days until an order is ready: the work on it and on the orders before it in its workshop's queue, at what the
     * workshop's people do in a day (0: ready; -1: nobody to make it).
     */
    public static int eta(Village v, Order o, double workSeconds) {
        if (o.done()) return 0;
        Building b = v.building(o.building);
        if (b == null) return -1;
        int hands = Workshops.hands(v, b).size();
        if (hands == 0) return -1;
        double seconds = 0;
        for (Order q : Workshops.queue(v, b)) {
            Workshops.Recipe r = recipe(b, q.recipe);
            if (r == null) continue;
            int makings = (int) Math.ceil((q.count - q.made) / (double) r.n());
            seconds += makings * r.seconds() / Workshops.speed(b);
            if (q == o) break;
        }
        return Math.max(1, (int) Math.ceil(seconds / hands / Math.max(1, workSeconds) - 1e-9));
    }

    public static List<Order> of(Village v, UUID player, int building) {
        List<Order> out = new ArrayList<>();
        for (Order o : v.orders) if (o.player.equals(player) && (building < 0 || o.building == building)) out.add(o);
        return out;
    }

    private static int nextId(Village v) {
        int id = 1;
        for (Order o : v.orders) id = Math.max(id, o.id + 1);
        return id;
    }

    /** An order of the village's own, at the end of a workshop's queue. */
    /** What a player pays the village for so much of its goods (emeralds, rounded up). */
    static int price(Village v, java.util.Map<Res, Integer> goods) {
        long cents = 0;
        for (var e : goods.entrySet()) cents += (long) Trade.sellCents(v, ware(e.getKey())) * e.getValue();
        return (int) Math.max(1, (cents + 99) / 100);
    }

    /** One of a made thing, as the village makes it (its doors of its own wood). */

    static ItemStack piece(Village v, Res r) {
        return Trade.piece(v, ware(r));
    }

    static Order villageOrder(Village v, Building b, int recipe, int count, long today) {
        return new Order(nextId(v), b.id, recipe, count, 0, VILLAGE, "", 0, today);
    }

    // ------------------------------------------------------------------ bought now, from what the village has

    private static Trade.Ware ware(Res res) {
        return Trade.WARES.get(res.ordinal());
    }

    /** Pieces the village has to spare of a thing right now. */
    public static int inStock(Village v, Workshops.Recipe r) {
        return Trade.available(v, ware(r.out()));
    }

    /** What a piece costs bought now (the stall's price), in hundredths; -1 if there is none to spare. */
    public static int nowCents(Village v, Workshops.Recipe r) {
        return Trade.sellCents(v, ware(r.out()));
    }

    private static void pay(ServerPlayer p, int price) {
        Wallet.pay(p, price);
    }

    private static void give(ServerPlayer p, Village v, Res r, int n) {
        ItemStack one = Trade.piece(v, ware(r));
        for (int left = n; left > 0; ) {
            int k = Math.min(left, one.getMaxStackSize());
            ItemStack st = one.copyWithCount(k);
            if (!p.getInventory().add(st)) p.drop(st, false);
            left -= k;
        }
    }

    /** The player buys pieces of a thing from what the village has. Returns the emeralds paid, 0 if it did not happen. */
    static int buyNow(ServerPlayer p, Village v, Building b, int recipe, int count) {
        Workshops.Recipe r = recipe(b, recipe);
        if (r == null) return 0;
        Trade.Ware w = ware(r.out());
        int n = Math.min(count, Trade.maxBuy(p, v, w));
        if (n <= 0) return 0;
        int price = Trade.total(Trade.sellCents(v, w), n);
        pay(p, price);
        v.add(r.out(), -n * Trade.units(w));
        v.emeralds += price;
        give(p, v, r.out(), n * Trade.units(w));
        return price;
    }

    // ------------------------------------------------------------------ the player's side

    /** A player orders pieces of a recipe: at the end of the workshop's queue. Returns the emeralds paid, 0 if it did not happen. */
    static int place(ServerPlayer p, Village v, Building b, int recipe, int count, long today) {
        Workshops.Recipe r = recipe(b, recipe);
        if (r == null || !open(b, r)) return 0;
        Job j = worker(b.type);
        if (j == null || v.workers(j) == 0) return 0;
        if (of(v, p.getUUID(), -1).size() >= MAX_OPEN) return 0;
        count = Math.max(1, Math.min(count, MAX_PIECES));
        int price = Trade.total(cents(r), count);
        if (Trade.emeralds(p) < price) return 0;
        pay(p, price);
        v.emeralds += price;
        v.orders.add(new Order(nextId(v), b.id, recipe, count, 0, p.getUUID(), p.getName().getString(), price, today));
        return price;
    }

    /** A player takes what is ready of their orders at a building. Returns the pieces given. */
    static int collect(ServerPlayer p, Village v, Building b) {
        int given = 0;
        for (Order o : new ArrayList<>(v.orders)) {
            if (!o.player.equals(p.getUUID()) || o.building != b.id || !o.done()) continue;
            Workshops.Recipe r = recipe(b, o.recipe);
            if (r == null) continue;
            give(p, v, r.out(), o.count);
            given += o.count;
            v.orders.remove(o);
        }
        return given;
    }

    /** (The orders are worked through in the workshops themselves, second by second: nothing on paper any more.) */
    static java.util.Map<Job, Double> work(Village v) {
        return new java.util.EnumMap<>(Job.class);
    }
}
