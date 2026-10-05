package org.webtrade.minecraftportsmod.colony;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import org.webtrade.minecraftportsmod.Minecraftportsmod;

/**
 * A trader at a village of his round (a merchant with his mules, a ship at a harbour): he deals at the village's own
 * prices, what it pays for a thing and what it asks for it, which move with every few he sells or buys as its store
 * fills or empties. He sells there what it is short of (what home sent along, what he bought on the way for it), never
 * under what it cost him; buys what home is short of, unless it is cheaper farther on; and buys more where it is cheap
 * of what a village still ahead of him on the round pays well for.
 */
public final class Dealing {

    private Dealing() {
    }

    /** Units sold or bought at a time, the prices worked out again between. */
    static final int STEP = 8;
    /** What a village ahead must pay over the price here for a trader to carry a thing there. */
    static final double MARGIN = 1.15;
    /** Cheaper farther on, by this much: home's things are bought there instead. */
    static final double CHEAPER = 1.1;

    /** Bought for selling on, all the world: units, and what was made on them (hundredths of an emerald). */
    public static int carriedOn, madeOn;

    /** What came of a stop. */
    record Stop(EnumMap<Res, Integer> sold, EnumMap<Res, Integer> bought, EnumMap<Res, Integer> soldFor, EnumMap<Res, Integer> boughtFor,
                int earned, int paid) {
    }

    /** What a village pays for a unit, in hundredths of an emerald; -1: it buys none (its store full). */
    static double bid(Village v, Res r) {
        Trade.Ware w = Trade.WARES.get(r.ordinal());
        int c = Trade.buyCents(v, w);
        return c < 0 ? -1 : c / (double) Trade.units(w);
    }

    /** What a village asks for a unit, in hundredths of an emerald; -1: it has none to spare. */
    static double ask(Village v, Res r) {
        Trade.Ware w = Trade.WARES.get(r.ordinal());
        int c = Trade.sellCents(v, w);
        return c < 0 ? -1 : c / (double) Trade.units(w);
    }

    /** Hundredths to whole emeralds, as a deal is paid: one at least for anything at all. */
    static int emeralds(double cents) {
        return cents <= 0 ? 0 : Math.max(1, (int) Math.round(cents / 100));
    }

    /**
     * Sells and buys at {@code there}. {@code cargo} is what he carries, {@code cost} what each unit of it cost him
     * (in hundredths of a hundredth of an emerald, see {@link #cost}), {@code purse} his emeralds (changed in place), {@code cap} the most he can carry,
     * {@code ahead} the villages still to come on the round before home.
     */
    static Stop deal(Village home, Village there, List<Village> ahead, EnumMap<Res, Integer> cargo, EnumMap<Res, Integer> cost, int[] purse, int cap) {
        EnumMap<Res, Integer> sold = new EnumMap<>(Res.class), bought = new EnumMap<>(Res.class);
        EnumMap<Res, Integer> soldFor = new EnumMap<>(Res.class), boughtFor = new EnumMap<>(Res.class);
        int earned = 0, paid = 0;
        // selling: what it is short of, at what it pays, while that is no less than it cost (and it can pay)
        for (Res r : Caravans.GOODS) {
            int have = cargo.getOrDefault(r, 0) - Caravans.short_(home, r);
            int n = Math.min(have, Caravans.short_(there, r));
            if (n <= 0 || there.emeralds < 1) continue;
            double cents = 0, gain = 0, paidFor = cost.getOrDefault(r, 0) / 100.0;
            int k = 0;
            while (k < n) {
                int step = Math.min(STEP, n - k);
                double b = bid(there, r);
                if (b < 0 || b < paidFor || cents + b * step > there.emeralds * 100.0) break;
                there.add(r, step);
                cents += b * step;
                if (paidFor > 0) gain += (b - paidFor) * step;
                k += step;
            }
            if (k == 0) continue;
            int price = Math.min(there.emeralds, emeralds(cents));
            there.emeralds -= price;
            purse[0] += price;
            earned += price;
            cargo.merge(r, -k, Integer::sum);
            sold.put(r, k);
            soldFor.put(r, price);
            if (gain > 0) madeOn += (int) Math.round(gain);
        }
        cargo.values().removeIf(x -> x <= 0);
        cost.keySet().removeIf(r -> !cargo.containsKey(r));
        // buying for home: what it is short of, the dearest first; unless a village ahead has it cheaper
        List<Res> byWorth = new ArrayList<>(List.of(Caravans.GOODS));
        byWorth.sort((x, y) -> Double.compare(Trade.base(y), Trade.base(x)));
        for (Res r : byWorth) {
            int want = Caravans.short_(home, r) - cargo.getOrDefault(r, 0);
            int n = Math.min(Math.min(want, Caravans.spare(there, r)), cap - amount(cargo));
            if (n <= 0) continue;
            double here = ask(there, r);
            boolean later = false;
            for (Village o : ahead) {
                double a = ask(o, r);
                if (a >= 0 && a * CHEAPER < here && Caravans.spare(o, r) >= want) later = true;
            }
            if (later) continue;
            int k = buy(there, r, n, Double.MAX_VALUE, purse[0], cargo, cost);
            if (k <= 0) continue;
            int price = emeralds(spent);
            purse[0] -= price;
            there.emeralds += price;
            paid += price;
            bought.merge(r, k, Integer::sum);
            boughtFor.merge(r, price, Integer::sum);
        }
        // buying to sell on: cheap here, and a village ahead pays well over it and is short of it
        for (Res r : byWorth) {
            double here = ask(there, r);
            if (here < 0) continue;
            int room = 0;
            double best = -1;
            for (Village o : ahead) {
                double b = bid(o, r);
                if (b <= here * MARGIN) continue;
                room += Caravans.short_(o, r);
                best = Math.max(best, b);
            }
            room -= Math.max(0, cargo.getOrDefault(r, 0) - Caravans.short_(home, r));
            int n = Math.min(Math.min(room, Caravans.spare(there, r)), cap - amount(cargo));
            if (n <= 0) continue;
            int k = buy(there, r, n, best / MARGIN, purse[0], cargo, cost);
            if (k <= 0) continue;
            int price = emeralds(spent);
            purse[0] -= price;
            there.emeralds += price;
            paid += price;
            bought.merge(r, k, Integer::sum);
            boughtFor.merge(r, price, Integer::sum);
            carriedOn += k;
            Minecraftportsmod.LOGGER.info("[arb] #{} bought {} {} at #{} for {} each to sell on (best bid ahead {})", home.id, k, r.id(), there.id,
                    String.format("%.1f", cost.get(r) / 100.0), String.format("%.1f", best));
        }
        return new Stop(sold, bought, soldFor, boughtFor, earned, paid);
    }

    /** Hundredths the last {@link #buy} spent. */
    private static double spent;

    /**
     * Buys up to {@code n} of a thing at {@code there}, a few at a time at the price it asks then, while that is no
     * more than {@code most} and the purse holds out. Into the cargo, the cost of it averaged in.
     */
    private static int buy(Village there, Res r, int n, double most, int purse, EnumMap<Res, Integer> cargo, EnumMap<Res, Integer> cost) {
        double cents = 0;
        int k = 0;
        while (k < n) {
            int step = Math.min(STEP, n - k);
            double a = ask(there, r);
            if (a < 0 || a > most || emeralds(cents + a * step) > purse || Caravans.spare(there, r) < step) break;
            there.add(r, -step);
            cents += a * step;
            k += step;
        }
        spent = cents;
        if (k == 0) return 0;
        int had = cargo.getOrDefault(r, 0);
        double before = cost.getOrDefault(r, 0) / 100.0 * had;
        cargo.put(r, had + k);
        cost.put(r, cost(before + cents, had + k));
        return k;
    }

    /**
     * The emeralds a trader takes along: what home is short of and the round has to spare, at what they ask for it
     * (and a little over), and a tenth of home's purse more (thirty at most) to buy with where it is cheap.
     */
    static int purse(Village home, List<Village> round) {
        double cents = 0;
        for (Res r : Caravans.GOODS) {
            int there = 0;
            double ask = 0;
            for (Village o : round) {
                there += Caravans.spare(o, r);
                ask = Math.max(ask, Math.max(0, ask(o, r)));
            }
            cents += Math.min(Caravans.short_(home, r), there) * ask;
        }
        int need = (int) Math.ceil(cents * 1.1 / 100);
        return Math.min(home.emeralds, need + Math.min(30, home.emeralds / 10));
    }

    /** Home's own things loaded: each at what home would pay for it (it is not let go of for less). */
    static void homeCost(Village home, java.util.Map<Res, Integer> load, EnumMap<Res, Integer> cost) {
        for (Res r : load.keySet()) cost.put(r, cost(Math.max(0, bid(home, r)), 1));
    }

    /** The cargo's costs as saved with a trip. */
    static java.util.Map<String, Integer> save(EnumMap<Res, Integer> cost) {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        cost.forEach((r, n) -> m.put(r.id(), n));
        return m;
    }

    static void load(java.util.Map<String, Integer> m, EnumMap<Res, Integer> cost) {
        m.forEach((k, n) -> {
            Res r = Res.byId(k);
            if (r != null) cost.put(r, n);
        });
    }

    /** What a unit cost, as the cargo's costs keep it: hundredths of a hundredth of an emerald (cheap things cost a few hundredths). */
    static int cost(double cents, int units) {
        return (int) Math.round(cents * 100 / Math.max(1, units));
    }

    // ------------------------------------------------------------------ for tests

    /** Sets a village's store of a thing (tests). */
    public static void stock(Village v, Res r, int n) {
        v.add(r, n - v.stock(r));
    }

    /**
     * A round, deal by deal, as a trader of {@code home} would make it with {@code load} and {@code purse} (tests);
     * returns what he comes home with: {emeralds, units carried}.
     */
    public static int[] round(Village home, List<Village> round, java.util.Map<Res, Integer> load, int purse) {
        EnumMap<Res, Integer> cargo = new EnumMap<>(Res.class), cost = new EnumMap<>(Res.class);
        cargo.putAll(load);
        homeCost(home, load, cost);
        int[] p = {purse};
        for (int i = 0; i < round.size(); i++) {
            Stop s = deal(home, round.get(i), round.subList(i + 1, round.size()), cargo, cost, p, Caravans.MAX_LOAD);
            Minecraftportsmod.LOGGER.info("[deal] at #{}: sold {} for {}, bought {} for {}; cargo {}, purse {}", round.get(i).id, s.sold(), s.soldFor(),
                    s.bought(), s.boughtFor(), cargo, p[0]);
        }
        return new int[]{p[0], amount(cargo)};
    }

    static int amount(EnumMap<Res, Integer> cargo) {
        int n = 0;
        for (int x : cargo.values()) n += x;
        return n;
    }
}
