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
        if (available(v, w) <= 0) return -1;
        if (w.res() != null) {
            int spare = v.stock(w.res()) - VillageLife.target(v, w.res());
            // the more it has, the cheaper it lets it go
            double glut = Math.min(0.4, spare / (double) Math.max(1, v.capacity(w.res())));
            return Math.max(1, (int) Math.round(base(w.res()) * units(w) * (1.3 - glut) * 100));
        }
        // a tool: its materials, and the work
        double worth = 0.3 * w.tier();
        for (var e : materials(w).entrySet()) worth += base(e.getKey()) * e.getValue();
        return Math.max(1, (int) Math.round(worth * 1.5 * 100));
    }

    /** What the village pays for a piece, in hundredths; -1 if it doesn't buy it (full, or a tool). */
    public static int buyCents(Village v, Ware w) {
        if (w.res() == null || v.full(w.res())) return -1;
        // from a fifth of the price for what it has plenty of to more than the price for what it is short of
        double want = Math.max(-1, Math.min(1, VillageLife.want(v, w.res())));
        double factor = Math.max(0.2, 0.6 + 0.5 * want);
        return Math.max(1, (int) Math.round(base(w.res()) * units(w) * factor * 100));
    }

    /** Pieces the village's stores have room for. */
    public static int room(Village v, Ware w) {
        return w.res() == null ? 0 : Math.max(0, (v.capacity(w.res()) - v.stock(w.res())) / units(w));
    }

    /** What {@code n} pieces cost in whole emeralds: at least one for any deal. */
    public static int total(int cents, int n) {
        return n <= 0 || cents < 0 ? 0 : Math.max(1, (int) Math.round(cents * (double) n / 100));
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

    public static int emeralds(ServerPlayer p) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.EMERALD)) n += inv.getItem(i).getCount();
        return n;
    }

    /** The most pieces the player can sell the village in one deal (goods, room, the village's purse). */
    public static int maxSell(ServerPlayer p, Village v, Ware w) {
        int cents = buyCents(v, w);
        if (cents < 0) return 0;
        int n = Math.min(carried(p, w), room(v, w));
        while (n > 0 && total(cents, n) > v.emeralds) n--;
        return n;
    }

    /** The most pieces the player can buy in one deal (the village's spare, the player's emeralds). */
    public static int maxBuy(ServerPlayer p, Village v, Ware w) {
        int cents = sellCents(v, w);
        if (cents < 0) return 0;
        int n = available(v, w), have = emeralds(p);
        while (n > 0 && total(cents, n) > have) n--;
        return n;
    }

    /** The player sells the village {@code n} pieces. Returns the emeralds paid, or 0 if it did not happen. */
    static int sell(ServerPlayer p, Village v, Ware w, int n) {
        n = Math.min(n, maxSell(p, v, w));
        if (n <= 0) return 0;
        int price = total(buyCents(v, w), n);
        int need = n * units(w);
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && need > 0; i++) {
            ItemStack s = inv.getItem(i);
            int per = w.res().unitsOf(s);
            if (per <= 0) continue;
            int take = Math.min(s.getCount(), (need + per - 1) / per);
            inv.removeItem(i, take);
            need -= take * per;
        }
        inv.setChanged();
        v.add(w.res(), n * units(w));
        v.emeralds -= price;
        give(p, new ItemStack(Items.EMERALD, price));
        return price;
    }

    /** The player buys {@code n} pieces from the village. Returns the emeralds paid, or 0. */
    static int buy(ServerPlayer p, Village v, Ware w, int n) {
        n = Math.min(n, maxBuy(p, v, w));
        if (n <= 0) return 0;
        int price = total(sellCents(v, w), n);
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
        if (w.res() != null) v.add(w.res(), -n * units(w));
        else for (var e : materials(w).entrySet()) v.add(e.getKey(), -e.getValue() * n);
        v.emeralds += price;
        ItemStack one = piece(v, w);
        for (int left = n; left > 0; ) {
            int k = Math.min(left, one.getMaxStackSize());
            give(p, one.copyWithCount(k));
            left -= k;
        }
        return price;
    }

    private static void give(ServerPlayer p, ItemStack stack) {
        if (!p.getInventory().add(stack)) p.drop(stack, false);
    }
}
