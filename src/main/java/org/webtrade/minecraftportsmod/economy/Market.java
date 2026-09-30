package org.webtrade.minecraftportsmod.economy;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Trading between a player and a settlement: its market (buy from and sell to its stores at its prices) and its
 * orders. Every unit moves the price, so a big deal costs more (or fetches less) than its first unit suggests.
 * The settlement keeps a merchant's margin between buying and selling.
 */
public final class Market {

    /** The settlement sells at this much above its price... */
    public static final double MARKUP = 1.15;
    /** ...and buys at this much below it. */
    public static final double MARKDOWN = 0.85;
    /** Orders open at a time. */
    static final int MAX_ORDERS = 3;
    static final int ORDER_DAYS = 6;
    /** Orders pay this much over the current price. */
    static final double ORDER_BONUS = 1.4;

    private Market() {
    }

    /** Price of one unit of a good at a given stock, for a settlement that wants {@code target} units. */
    public static double unitPrice(Good g, double target, double stock) {
        return g.basePrice * Simulation.priceMultiplier(target, Math.max(0, stock));
    }

    /** Emeralds for buying {@code qty} units from a stock (rounded up); every unit taken raises the price. */
    public static int buyTotal(Good g, double target, double stock, int qty) {
        double total = 0;
        for (int i = 0; i < qty; i++) total += unitPrice(g, target, stock - i - 1) * MARKUP;
        return (int) Math.ceil(total - 1e-9);
    }

    /** Emeralds received for selling {@code qty} units into a stock (rounded down); every unit lowers the price. */
    public static int sellTotal(Good g, double target, double stock, int qty) {
        double total = 0;
        for (int i = 0; i < qty; i++) total += unitPrice(g, target, stock + i + 1) * MARKDOWN;
        return (int) Math.floor(total + 1e-9);
    }

    public static double target(Settlement s, Good g) {
        return Simulation.target(s, g);
    }

    /** How many units the settlement is willing to sell: it keeps a third of what it needs for itself. */
    public static int available(Settlement s, Good g) {
        return (int) Math.floor(Math.max(0, s.stock(g) - Simulation.target(s, g) * 0.3));
    }

    /** Emeralds to pay, rounded up; -1 if the settlement won't sell that many. */
    public static int buyQuote(Settlement s, Good g, int qty) {
        if (qty < 1 || available(s, g) < qty) return -1;
        return buyTotal(g, Simulation.target(s, g), s.stock(g), qty);
    }

    /** Emeralds received, rounded down; -1 if the treasury can't pay it or it's worth less than one emerald. */
    public static int sellQuote(Settlement s, Good g, int qty) {
        if (qty < 1) return -1;
        int pay = sellTotal(g, Simulation.target(s, g), s.stock(g), qty);
        if (pay < 1 || pay > s.treasury) return -1;
        return pay;
    }

    /** Applies a purchase the caller has already been paid for. */
    public static void bought(Settlement s, Good g, int qty, int paid, long day, String player) {
        s.add(g, -qty);
        s.treasury += paid;
        s.log(day, Component.translatable("minecraftportsmod.log.player_bought", player, qty, g.displayName(), paid));
    }

    /** Applies a sale; the caller gives the player the emeralds. */
    public static void sold(Settlement s, Good g, int qty, int paid, long day, String player) {
        s.add(g, qty);
        s.treasury -= paid;
        s.log(day, Component.translatable("minecraftportsmod.log.player_sold", player, qty, g.displayName(), paid));
    }

    // ------------------------------------------------------------------ orders

    public static List<Order> orders(Settlement s) {
        return new ArrayList<>(s.orders);
    }

    public static Order order(Settlement s, int id) {
        for (Order o : s.orders) if (o.id == id) return o;
        return null;
    }

    /** Drops expired orders and opens new ones for what the settlement lacks most. Once a day. */
    static void updateOrders(Settlement s, long day) {
        s.orders.removeIf(o -> o.expires < day || o.remaining() <= 0);
        if (s.orders.size() >= MAX_ORDERS) return;
        List<Good> wanted = new ArrayList<>();
        for (Good g : Good.values()) {
            if (Simulation.want(s, g) < 16 || Simulation.price(s, g) < g.basePrice * 1.1) continue;
            boolean open = false;
            for (Order o : s.orders) if (o.good == g) open = true;
            if (!open) wanted.add(g);
        }
        wanted.sort(Comparator.comparingDouble(g -> -Simulation.want(s, g) * Simulation.price(s, g)));
        for (Good g : wanted) {
            if (s.orders.size() >= MAX_ORDERS) break;
            int amount = (int) Math.min(192, Math.max(16, Math.ceil(Simulation.want(s, g) / 16.0) * 16));
            int reward = (int) Math.max(2, Math.ceil(Simulation.price(s, g) * amount * ORDER_BONUS));
            if (reward > s.treasury * 0.4) {
                amount = (int) Math.max(16, Math.floor(s.treasury * 0.4 / (Simulation.price(s, g) * ORDER_BONUS) / 16) * 16);
                reward = (int) Math.max(2, Math.ceil(Simulation.price(s, g) * amount * ORDER_BONUS));
                if (reward > s.treasury * 0.4) continue;
            }
            s.orders.add(new Order(s.nextOrderId++, g, amount, reward, day + ORDER_DAYS));
            s.log(day, Component.translatable("minecraftportsmod.log.order", amount, g.displayName(), reward));
        }
    }

    /**
     * Takes {@code units} of the order's good into the stores and returns the emeralds due for them now (the
     * reward is paid in proportion; the last delivery rounds it off).
     */
    public static int deliver(Settlement s, Order o, int units, long day, String player) {
        units = Math.min(units, o.remaining());
        if (units <= 0) return 0;
        o.delivered += units;
        s.add(o.good, units);
        int due = (int) Math.floor((double) o.reward * o.delivered / o.amount) - o.paid;
        due = (int) Math.max(0, Math.min(due, Math.floor(s.treasury)));
        o.paid += due;
        s.treasury -= due;
        if (o.remaining() == 0) {
            s.log(day, Component.translatable("minecraftportsmod.log.order_done", player, o.amount, o.good.displayName(), o.paid));
            s.orders.remove(o);
        }
        return due;
    }
}
