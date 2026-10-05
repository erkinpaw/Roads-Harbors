package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The village merchant's trade with players, for emeralds, piece by piece: the wares sorted by kind (food, raw
 * goods, building goods, tools). The village buys what its stores have room for (the shorter it is of it, the
 * better it pays) and sells what it has more of than it needs; tools it makes to order from its own sticks, stone
 * and iron. Prices are kept in hundredths of an emerald a piece; a deal is paid in whole emeralds (at least one).
 */
public final class Trade {

    private Trade() {
    }

    /** A tool the village makes for sale. */
    public enum Tool {
        AXE, PICKAXE, SHOVEL, HOE, ROD
    }

    /**
     * One line of the merchant's goods: a resource (in pieces of {@link #units} units), or a tool of a tier (1 wood,
     * 2 stone, 3 iron).
     */
    public record Ware(Res res, Tool tool, int tier) {
        public Res.Kind kind() {
            return res != null ? res.kind() : Res.Kind.TOOLS;
        }
    }

    /** Everything the merchant deals in, in order: the resources, then the tools. The index is the ware's id. */
    public static final List<Ware> WARES;

    static {
        List<Ware> w = new ArrayList<>();
        for (Res r : Res.values()) w.add(new Ware(r, null, 0));
        for (int tier = 1; tier <= 3; tier++) {
            for (Tool t : new Tool[]{Tool.AXE, Tool.PICKAXE, Tool.SHOVEL, Tool.HOE}) w.add(new Ware(null, t, tier));
        }
        w.add(new Ware(null, Tool.ROD, 1));
        WARES = Collections.unmodifiableList(w);
    }

    /** Emeralds a unit of a resource is worth at an even keel. */
    static double base(Res r) {
        return r.price;
    }

    /** Is a ware on the stall: the trades' tools are not, as such (the stall sells the tools themselves). */
    public static boolean listed(Ware w) {
        return w.res() == null || w.res().toolLevel() == 0;
    }

    /** Units of its resource in one piece of a ware (food goes by the loaf: five of it). */
    public static int units(Ware w) {
        return w.res() == Res.FOOD ? 5 : 1;
    }

    /** What a tool is made of. */
    static Map<Res, Integer> materials(Ware w) {
        Map<Res, Integer> m = new EnumMap<>(Res.class);
        if (w.tool() == Tool.ROD) {
            m.put(Res.STICKS, 3);
            return m;
        }
        // (a tool of the smith's, from the village's store of them)
        m.put(Res.tools(w.tier()), 1);
        return m;
    }

    /** One piece of a ware, as the player gets it. */
    public static ItemStack piece(Village v, Ware w) {
        if (w.res() != null) {
            return switch (w.res()) {
                case FOOD -> new ItemStack(Items.BREAD);
                case WOOD -> new ItemStack(wood(v, "log", Items.OAK_LOG));
                case PLANKS -> new ItemStack(wood(v, "planks", Items.OAK_PLANKS));
                // (the joiner's work is of the village's own wood)
                default -> new ItemStack(w.res().wooden != null ? wood(v, w.res().wooden, w.res().icon) : w.res().icon);
            };
        }
        return new ItemStack(switch (w.tool()) {
            case AXE -> w.tier() == 1 ? Items.WOODEN_AXE : w.tier() == 2 ? Items.STONE_AXE : Items.IRON_AXE;
            case PICKAXE -> w.tier() == 1 ? Items.WOODEN_PICKAXE : w.tier() == 2 ? Items.STONE_PICKAXE : Items.IRON_PICKAXE;
            case SHOVEL -> w.tier() == 1 ? Items.WOODEN_SHOVEL : w.tier() == 2 ? Items.STONE_SHOVEL : Items.IRON_SHOVEL;
            case HOE -> w.tier() == 1 ? Items.WOODEN_HOE : w.tier() == 2 ? Items.STONE_HOE : Items.IRON_HOE;
            case ROD -> Items.FISHING_ROD;
        });
    }

    private static Item wood(Village v, String part, Item fallback) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(v.wood + "_" + part)).orElse(fallback);
    }

    public static Component name(Village v, Ware w) {
        return piece(v, w).getHoverName();
    }

    // ------------------------------------------------------------------ prices

    /** Pieces of a ware the village has to spare for sale (what it keeps for itself is not for sale). */
    public static int available(Village v, Ware w) {
        if (w.res() != null) return Math.max(0, (v.stock(w.res()) - VillageLife.target(v, w.res())) / units(w));
        int n = Integer.MAX_VALUE;
        for (var e : materials(w).entrySet()) n = Math.min(n, Math.max(0, v.stock(e.getKey()) - VillageLife.target(v, e.getKey())) / e.getValue());
        return n == Integer.MAX_VALUE ? 0 : Math.min(n, 64);
    }

    /** What the village asks for a piece, in hundredths of an emerald; -1 if it has none to sell. */
    public static int sellCents(Village v, Ware w) {
        return sellCents(v, w, 0);
    }

    /** The same, its store {@code delta} units off what it holds now (a deal under way: the price moves with it). */
    static int sellCents(Village v, Ware w, int delta) {
        if (w.res() != null ? (v.stock(w.res()) + delta - VillageLife.target(v, w.res())) / units(w) <= 0 : available(v, w) <= 0) return -1;
        if (w.res() != null) {
            int spare = v.stock(w.res()) + delta - VillageLife.target(v, w.res());
            // the more it has over what it keeps, the cheaper it lets it go (twice what it keeps over: a little over half the price)
            double glut = Math.min(0.7, 0.35 * spare / (double) Math.max(10, VillageLife.target(v, w.res())));
            return Math.max(1, (int) Math.round(base(w.res()) * units(w) * (1.3 - glut) * 100));
        }
        // a tool: its materials, and the work
        double worth = 0.3 * w.tier();
        for (var e : materials(w).entrySet()) worth += base(e.getKey()) * e.getValue();
        return Math.max(1, (int) Math.round(worth * 1.5 * 100));
    }

    /** What the village pays for a piece, in hundredths; -1 if it doesn't buy it (full, or a tool). */
    public static int buyCents(Village v, Ware w) {
        return buyCents(v, w, 0);
    }

    /** The same, its store {@code delta} units off what it holds now. */
    static int buyCents(Village v, Ware w, int delta) {
        if (w.res() == null || v.free(w.res()) - delta <= 0) return -1;
        // from a fifth of the price for what it has plenty of to more than the price for what it is short of
        double want = Math.max(-1, Math.min(1, delta == 0 ? VillageLife.want(v, w.res()) : want(v, w.res(), delta)));
        double factor = Math.max(0.2, 0.6 + 0.5 * want);
        return Math.max(1, (int) Math.round(base(w.res()) * units(w) * factor * 100));
    }

    /** {@link VillageLife#want}, the store {@code delta} units off what it holds (the food's own rule aside). */
    private static double want(Village v, Res r, int delta) {
        double t = VillageLife.target(v, r);
        int stock = v.stock(r) + delta;
        if (t <= 0) return stock > 0 ? -2 : 0;
        return (t - stock) / t;
    }

    // ------------------------------------------------------------------ a deal of many: lot by lot

    /** Pieces dealt at one price; between lots the village's price moves with its store (its spare less, its want less). */
    public static final int LOT = 8;
    /** Lots a stall's row tells the price of (the rest at the last one's). */
    static final int LOTS = 64;

    /** The price of each lot of a purchase from the village, cheapest first (hundredths a piece), as many lots as {@code n} pieces take. */
    public static int[] buyLots(Village v, Ware w, int n) {
        int lots = Math.min(LOTS, (n + LOT - 1) / LOT);
        int[] out = new int[Math.max(0, lots)];
        for (int i = 0; i < lots; i++) out[i] = w.res() == null ? sellCents(v, w) : sellCents(v, w, -i * LOT * units(w));
        return out;
    }

    /** The price of each lot of a sale to the village (hundredths a piece). */
    public static int[] sellLots(Village v, Ware w, int n) {
        int lots = Math.min(LOTS, (n + LOT - 1) / LOT);
        int[] out = new int[Math.max(0, lots)];
        for (int i = 0; i < lots; i++) out[i] = buyCents(v, w, i * LOT * units(w));
        return out;
    }

    /** What {@code n} pieces come to at these lots' prices (hundredths); -1 if a lot is not to be had. */
    public static long total(int[] lots, int n) {
        long t = 0;
        for (int k = 0, i = 0; k < n; i++) {
            int c = lots.length == 0 ? -1 : lots[Math.min(i, lots.length - 1)];
            if (c < 0) return -1;
            int step = Math.min(LOT, n - k);
            t += (long) c * step;
            k += step;
        }
        return t;
    }

    /** The most pieces these lots allow for {@code money} hundredths, up to {@code most}. */
    static int afford(int[] lots, int most, long money) {
        long t = 0;
        int k = 0;
        for (int i = 0; k < most; i++) {
            int c = lots.length == 0 ? -1 : lots[Math.min(i, lots.length - 1)];
            if (c <= 0) break;
            int step = Math.min(LOT, most - k);
            if (t + (long) c * step > money) return k + (int) ((money - t) / c);
            t += (long) c * step;
            k += step;
        }
        return k;
    }

    /** Pieces the village's stores have room for. */
    public static int room(Village v, Ware w) {
        return w.res() == null ? 0 : Math.max(0, (v.capacity(w.res()) - v.stock(w.res())) / units(w));
    }

    /** What {@code n} pieces cost in whole emeralds: at least one for any deal. */
    public static int total(int cents, int n) {
        return n <= 0 || cents < 0 ? 0 : Math.max(1, (int) Math.round(cents * (double) n / 100));
    }

    /** Hundredths of an emerald as money is written: "63", "63.45", "0.24". */
    public static String money(long cents) {
        if (cents % 100 == 0) return String.valueOf(cents / 100);
        return String.format(java.util.Locale.ROOT, "%.2f", cents / 100.0);
    }

    // ------------------------------------------------------------------ the player's side

    /** How many units of a resource the player carries. */
    public static int carried(ServerPlayer p, Res r) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) n += r.unitsOf(inv.getItem(i)) * inv.getItem(i).getCount();
        return n;
    }

    /** Pieces of a ware the player could sell. */
    public static int carried(ServerPlayer p, Ware w) {
        return w.res() == null ? 0 : carried(p, w.res()) / units(w);
    }

    /** The player's own ship lying at the village (her hold trades at the stall too), or null. */
    static net.minecraft.world.SimpleContainer hold(ServerPlayer p, Village v) {
        var ship = Harbour.playerShip((net.minecraft.server.level.ServerLevel) p.level(), v, p.getUUID());
        return ship == null ? null : ship.hold();
    }

    /** Pieces of a ware the player could sell here: what they carry, and what is in their ship's hold at the pier. */
    public static int carried(ServerPlayer p, Village v, Ware w) {
        if (w.res() == null) return 0;
        int n = carried(p, w.res());
        var hold = hold(p, v);
        if (hold != null) for (int i = 0; i < hold.getContainerSize(); i++) n += w.res().unitsOf(hold.getItem(i)) * hold.getItem(i).getCount();
        return n / units(w);
    }

    /** Takes so many units of a resource out of a container (the inventory, a hold); returns how many are still to take. */
    private static int take(net.minecraft.world.Container c, Res r, int need) {
        for (int i = 0; i < c.getContainerSize() && need > 0; i++) {
            ItemStack s = c.getItem(i);
            int per = r.unitsOf(s);
            if (per <= 0) continue;
            int k = Math.min(s.getCount(), (need + per - 1) / per);
            c.removeItem(i, k);
            need -= k * per;
        }
        c.setChanged();
        return need;
    }

    /** What a player has in the purse, in hundredths (what was just picked up counted in). */
    public static long purse(ServerPlayer p) {
        Wallet.absorb(p);
        return Wallet.cents(p);
    }

    /** The emeralds a player has: in the purse (see {@link Wallet}). */
    public static int emeralds(ServerPlayer p) {
        Wallet.absorb(p);
        return Wallet.balance(p);
    }

    /** The most pieces the player can sell the village in one deal (goods, room, the village's purse, to the hundredth). */
    public static int maxSell(ServerPlayer p, Village v, Ware w) {
        if (buyCents(v, w) <= 0) return 0;
        int most = Math.min(carried(p, v, w), room(v, w));
        return afford(sellLots(v, w, most), most, v.cents());
    }

    /** The most pieces the player can buy in one deal (the village's spare, the player's purse, to the hundredth). */
    public static int maxBuy(ServerPlayer p, Village v, Ware w) {
        if (sellCents(v, w) <= 0) return 0;
        Wallet.absorb(p);
        int most = available(v, w);
        return afford(buyLots(v, w, most), most, Wallet.cents(p));
    }

    /** The player sells the village {@code n} pieces. Returns what they were paid, in hundredths, or 0 if it did not happen. */
    static long sell(ServerPlayer p, Village v, Ware w, int n) {
        n = Math.min(n, maxSell(p, v, w));
        if (n <= 0) return 0;
        long price = total(sellLots(v, w, n), n);
        if (price < 0 || !v.payOut(price)) return 0;
        // (out of what they carry first, then out of their ship's hold)
        int need = take(p.getInventory(), w.res(), n * units(w));
        var hold = hold(p, v);
        if (need > 0 && hold != null) take(hold, w.res(), need);
        v.add(w.res(), n * units(w));
        Wallet.addCents(p, price);
        return price;
    }

    /** The player buys {@code n} pieces from the village. Returns what they paid, in hundredths, or 0. */
    static long buy(ServerPlayer p, Village v, Ware w, int n) {
        n = Math.min(n, maxBuy(p, v, w));
        if (n <= 0) return 0;
        long price = total(buyLots(v, w, n), n);
        if (price < 0 || !Wallet.payCents(p, price)) return 0;
        if (w.res() != null) v.add(w.res(), -n * units(w));
        else for (var e : materials(w).entrySet()) v.add(e.getKey(), -e.getValue() * n);
        v.receive(price);
        // (into their ship's hold, if she lies here; what does not go in, to them)
        ItemStack one = piece(v, w);
        var hold = hold(p, v);
        for (int left = n; left > 0; ) {
            int k = Math.min(left, one.getMaxStackSize());
            ItemStack st = one.copyWithCount(k);
            if (hold != null) st = hold.addItem(st);
            if (!st.isEmpty()) give(p, st);
            left -= k;
        }
        return price;
    }

    private static void give(ServerPlayer p, ItemStack stack) {
        if (!p.getInventory().add(stack)) p.drop(stack, false);
    }
}
