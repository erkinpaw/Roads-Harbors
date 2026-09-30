package org.webtrade.minecraftportsmod.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Trade between villages, a merchant's round: the village's merchant loads up what it has to spare (beyond what it
 * keeps and what its queue is to have: see {@link VillageLife#target}), up to a load of {@link #MAX_LOAD}, takes the
 * emeralds for what its queue is short of, and sets out along the made trails on a round of up to
 * {@link #MAX_STOPS} villages: those that are short of what he carries, and those that have to spare what his own is
 * short of. At each he sells what they can pay for and buys what home needs, and on to the next; then home, with the
 * goods and the purse. A village with a long queue and nothing to spare sends him out only to buy.
 * <p>
 * The other villages need no merchant of their own, nor a stall: he comes to them. Only trails the crews have made
 * from end to end are walked (see {@link Roadworks}). What happens to him on the road happens to what he carries: a
 * merchant who does not arrive sells nothing, and his goods and money are gone; a mule lost, its share of the load.
 * <p>
 * The walk is real: every trip knows how far along the trail its merchant is. Where a player is near, he is there in
 * the world, walking the trail with his mules (and how far he got is read off where he is); elsewhere he goes on at
 * a walker's pace all the same, unseen.
 */
public final class Caravans {

    /** Days a merchant rests at home between trips. */
    static final int REST = 3;
    /** Blocks a merchant walks in a day (when a day is skipped). */
    static final int PER_DAY = 2400;
    /** The longest round (over the trails, there and home) a merchant sets out on. */
    static final int MAX_WALK = 4000;
    /** Blocks a merchant walks in a second, unseen (about his pace when seen). */
    static final double PER_SECOND = 2.0;
    /** A merchant is put into the world where he is on the trail when a player is this near. */
    static final int SEEN = 80;
    /** The goods traded. */
    static final Res[] GOODS = {Res.FOOD, Res.WOOD, Res.STONE, Res.PLANKS, Res.STICKS, Res.COAL, Res.IRON};
    /** The most a merchant's mules carry, all told. */
    public static final int MAX_LOAD = 1000;
    /** A round is not worth the walk for less than this much to sell and buy, all told. */
    public static final int MIN_DEAL = 100;

    /** What changed hands in this world (since it was opened): by resource, sold by merchants and bought by them. */
    public static final EnumMap<Res, Integer> SOLD = new EnumMap<>(Res.class), BOUGHT = new EnumMap<>(Res.class);
    public static final EnumMap<Res, Integer> SOLD_FOR = new EnumMap<>(Res.class), BOUGHT_FOR = new EnumMap<>(Res.class);
    /** Short of it at home, and still short when the merchant turned for home: the want nobody met. */
    public static final EnumMap<Res, Integer> UNMET = new EnumMap<>(Res.class);
    /** Rounds not made: none worth anything at all, or too little for the walk. */
    public static int stayed, stayedNothing;
    /** What one mule carries. */
    static final int PER_MULE = 250;
    /** The most villages on a round. */
    public static final int MAX_STOPS = 3;
    /** Merchants set out at all (a world without trade between villages: to see what trade does, tests). */
    public static boolean enabled = true;

    private Caravans() {
    }

    /** One merchant on the road. */
    public static final class Trip {
        static final Codec<Trip> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("from").forGetter(t -> t.from),
                Codec.INT.fieldOf("to").forGetter(t -> t.to),
                Codec.INT.fieldOf("merchant").forGetter(t -> t.merchant),
                Codec.STRING.optionalFieldOf("res", "").forGetter(t -> ""),
                Codec.INT.optionalFieldOf("amount", 0).forGetter(t -> 0),
                Codec.INT.optionalFieldOf("back", 0).forGetter(t -> t.back ? 1 : 0),
                Codec.LONG.fieldOf("arrive").forGetter(t -> t.arrive),
                Codec.DOUBLE.optionalFieldOf("at", 0.0).forGetter(t -> t.at),
                Codec.INT.optionalFieldOf("purse", 0).forGetter(t -> t.purse),
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("cargo", Map.of()).forGetter(t -> {
                    Map<String, Integer> m = new java.util.HashMap<>();
                    t.cargo.forEach((r, n) -> m.put(r.id(), n));
                    return m;
                }),
                Codec.INT.listOf().optionalFieldOf("stops", List.of()).forGetter(t -> t.stops),
                Codec.INT.optionalFieldOf("node", -1).forGetter(t -> t.node)
        ).apply(i, (from, to, merchant, res, amount, back, arrive, at, purse, cargo, stops, node) -> {
            Trip t = new Trip(from, to, merchant, arrive);
            t.back = back != 0;
            t.at = at;
            t.purse = purse;
            cargo.forEach((k, n) -> {
                Res r = Res.byId(k);
                if (r != null && n > 0) t.cargo.put(r, n);
            });
            // (a trip from before the rounds: one good, straight there and home)
            Res old = Res.byId(res);
            if (old != null && amount > 0 && t.cargo.isEmpty()) t.cargo.put(old, amount);
            t.stops.addAll(stops);
            t.node = node >= 0 ? node : t.back ? to : from;
            if (node < 0 && t.back) {
                // (the old form: "to" was the village he went to, "back" the way home from it)
                t.node = to;
                t.to = from;
            }
            return t;
        }));

        public final int from, merchant;
        /** The village he is walking to now, and the one he walks from (home, or the stop before). */
        int to, node;
        /** The villages still to come on the round, after {@link #to}. */
        final List<Integer> stops = new ArrayList<>();
        /** What his mules carry. */
        final EnumMap<Res, Integer> cargo = new EnumMap<>(Res.class);
        /** On the way home. */
        boolean back;
        long arrive;
        /** How far along this leg the merchant is, in blocks. */
        double at;
        /** The emeralds he carries: to buy with, and what he was paid. */
        int purse;

        Trip(int from, int to, int merchant, long arrive) {
            this.from = from;
            this.to = to;
            this.node = from;
            this.merchant = merchant;
            this.arrive = arrive;
        }

        public int to() {
            return to;
        }

        /** The good he carries most of (null: nothing). */
        public Res res() {
            Res best = null;
            for (var e : cargo.entrySet()) if (best == null || e.getValue() > cargo.get(best)) best = e.getKey();
            return best;
        }

        /** All he carries, all told. */
        public int amount() {
            int n = 0;
            for (int x : cargo.values()) n += x;
            return n;
        }

        public Map<Res, Integer> cargo() {
            return java.util.Collections.unmodifiableMap(cargo);
        }

        public List<Integer> stops() {
            return List.copyOf(stops);
        }

        public boolean back() {
            return back;
        }

        public long arrive() {
            return arrive;
        }

        public double at() {
            return at;
        }

        public int purse() {
            return purse;
        }
    }

    // ------------------------------------------------------------------ what there is to trade

    /** What a village can let go of: beyond what it keeps and what its queue is to have. */
    static int spare(Village v, Res r) {
        return Math.max(0, v.stock(r) - VillageLife.target(v, r));
    }

    /** What a village is short of: what it keeps and what its queue is to have, beyond what it has (and room for it). */
    static int short_(Village v, Res r) {
        return Math.max(0, Math.min(VillageLife.target(v, r) - v.stock(r), v.capacity(r) - v.stock(r)));
    }

    /** Over the whole world, by resource: how much the villages are short of, and how much they have to spare. */
    public static String balance(VillageData data) {
        StringBuilder out = new StringBuilder();
        for (Res r : GOODS) {
            int shortOf = 0, over = 0, villages = 0;
            for (Village v : data.all()) {
                int n = short_(v, r);
                shortOf += n;
                over += spare(v, r);
                if (n > 0) villages++;
            }
            out.append(r.id()).append(": short ").append(shortOf).append(" in ").append(villages).append(", spare ").append(over).append(" | ");
        }
        return out.toString();
    }

    static int cents(Res r) {
        return (int) Math.round(Trade.base(r) * 100);
    }

    /** What a round to {@code there} is worth, in emeralds: what can be sold there (as it can pay) and bought there. */
    static double worth(Village home, Village there, Map<Res, Integer> load, int purse) {
        double sell = 0, buy = 0;
        for (Res r : GOODS) {
            sell += Math.min(load.getOrDefault(r, 0), short_(there, r)) * Trade.base(r);
            buy += Math.min(short_(home, r), spare(there, r)) * Trade.base(r);
        }
        return Math.min(sell, there.emeralds) + Math.min(buy, purse);
    }

    /** For tests and the command: what a merchant of {@code a} would do at {@code b} now. */
    public static String explain(VillageData data, int a, int b) {
        Village va = data.get(a), vb = data.get(b);
        if (va == null || vb == null) return "no village";
        int merchants = 0;
        for (Dweller d : va.dwellers) if (d.job == Job.MERCHANT) merchants++;
        int[] r = Trails.route(data, a, b);
        StringBuilder sell = new StringBuilder(), buy = new StringBuilder();
        for (Res x : GOODS) {
            int s = Math.min(spare(va, x), short_(vb, x)), p = Math.min(short_(va, x), spare(vb, x));
            if (s > 0) sell.append(s).append(' ').append(x.id()).append(' ');
            if (p > 0) buy.append(p).append(' ').append(x.id()).append(' ');
        }
        return "merchants " + merchants + ", stall " + va.has(BuildingType.MARKET) + ", way " + (r == null ? "none" : (int) length(r))
                + ", sell " + (sell.length() == 0 ? "none" : sell.toString().trim()) + ", buy " + (buy.length() == 0 ? "none" : buy.toString().trim())
                + ", last trip " + data.lastTrip.get(a);
    }

    // ------------------------------------------------------------------ setting out

    /** A new day: merchants at home setting out on a round (and, a day skipped, the merchants on the road walk a day's way). */
    static void day(ServerLevel level, VillageData data, boolean skip) {
        long today = data.day;
        if (skip) {
            for (Trip t : new ArrayList<>(data.trips)) {
                if (BODIES.containsKey(key(t))) continue;
                walk(level, data, t, PER_DAY);
            }
        }
        for (Village v : data.all()) {
            Dweller m = null;
            for (Dweller d : v.dwellers) if (d.job == Job.MERCHANT && !d.away) m = d;
            if (m == null) continue;
            if (today - data.lastTrip.getOrDefault(v.id, Long.MIN_VALUE / 2) < REST || !enabled) continue;
            setOut(data, v, m, today);
        }
        data.changed();
    }

    /** The round a merchant sets out on, if there is one worth the walk. */
    private static void setOut(VillageData data, Village v, Dweller m, long today) {
        // what there is to sell (up to a load) and what to buy
        EnumMap<Res, Integer> spare = new EnumMap<>(Res.class);
        for (Res r : GOODS) if (spare(v, r) > 0) spare.put(r, spare(v, r));
        int money = v.emeralds;
        // the villages worth going to, the best first
        List<Village> can = new ArrayList<>();
        Map<Integer, Double> worth = new java.util.HashMap<>();
        for (int node : Trails.reachWalkable(data, v.id)) {
            Village o = node >= 0 && node != v.id ? data.get(node) : null;
            if (o == null) continue;
            double w = worth(v, o, spare, money);
            if (w < 1) continue;
            int[] r = Trails.route(data, v.id, o.id);
            if (r == null || 2 * length(r) > MAX_WALK) continue;
            can.add(o);
            worth.put(o.id, w);
        }
        if (can.isEmpty()) {
            stayed++;
            stayedNothing++;
            return;
        }
        can.sort((x, y) -> Double.compare(worth.get(y.id), worth.get(x.id)));
        List<Village> chosen = new ArrayList<>(can.subList(0, Math.min(MAX_STOPS, can.size())));
        // the round: the nearest of them next, each time; as long as the whole walk home again is not too far
        List<Village> round = new ArrayList<>();
        int at = v.id;
        double walked = 0;
        while (!chosen.isEmpty()) {
            Village next = null;
            double nearest = Double.MAX_VALUE;
            for (Village o : chosen) {
                int[] r = Trails.route(data, at, o.id);
                if (r != null && length(r) < nearest) {
                    nearest = length(r);
                    next = o;
                }
            }
            if (next == null) break;
            chosen.remove(next);
            int[] home = Trails.route(data, next.id, v.id);
            if (!round.isEmpty() && (home == null || walked + nearest + length(home) > MAX_WALK)) break;
            round.add(next);
            walked += nearest;
            at = next.id;
        }
        if (round.isEmpty()) return;
        // the load: of what is spare, what the villages on the round are short of (up to what the mules carry)
        EnumMap<Res, Integer> load = new EnumMap<>(Res.class);
        int total = 0;
        List<Res> byWorth = new ArrayList<>(List.of(GOODS));
        byWorth.sort((x, y) -> Double.compare(Trade.base(y), Trade.base(x)));
        for (Res r : byWorth) {
            int wanted = 0;
            for (Village o : round) wanted += short_(o, r);
            int n = Math.min(Math.min(spare.getOrDefault(r, 0), wanted), MAX_LOAD - total);
            if (n <= 0) continue;
            load.put(r, n);
            total += n;
        }
        // the purse: what home is short of and the round has to spare, as the prices go (and a little over)
        double buy = 0;
        for (Res r : GOODS) {
            int there = 0;
            for (Village o : round) there += spare(o, r);
            buy += Math.min(short_(v, r), there) * Trade.base(r);
        }
        int purse = Math.min(v.emeralds, (int) Math.ceil(buy * 1.2));
        // (a round for a handful: not worth the walk)
        int toBuy = 0;
        for (Res r : GOODS) {
            int there = 0;
            for (Village o : round) there += spare(o, r);
            toBuy += Math.min(short_(v, r), there);
        }
        if (total + (purse > 0 ? toBuy : 0) < MIN_DEAL) {
            stayed++;
            org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("Merchant of #{} stays: {} to sell and {} to buy on the round, less than {}",
                    v.id, total, purse > 0 ? toBuy : 0, MIN_DEAL);
            return;
        }
        for (var e : load.entrySet()) v.add(e.getKey(), -e.getValue());
        v.emeralds -= purse;
        m.away = true;
        Trip trip = new Trip(v.id, round.getFirst().id, m.id, today);
        trip.cargo.putAll(load);
        trip.purse = purse;
        for (int i = 1; i < round.size(); i++) trip.stops.add(round.get(i).id);
        data.trips.add(trip);
        data.lastTrip.put(v.id, today);
        MutableComponent names = Component.empty();
        for (int i = 0; i < round.size(); i++) names.append(i == 0 ? "" : ", ").append(round.get(i).name);
        v.log(today, (total > 0
                ? Component.translatable("minecraftportsmod.vlog.caravan_round", m.name, names, goods(load), purse)
                : Component.translatable("minecraftportsmod.vlog.caravan_round_buy", m.name, names, purse)).withStyle(ChatFormatting.DARK_AQUA));
    }

    /** Goods as the log writes them: "120 food, 40 wood". */
    static Component goods(Map<Res, Integer> m) {
        MutableComponent out = Component.empty();
        boolean first = true;
        for (var e : m.entrySet()) {
            if (e.getValue() <= 0) continue;
            out.append(first ? "" : ", ").append(e.getValue() + " ").append(e.getKey().displayName());
            first = false;
        }
        return first ? Component.literal("-") : out;
    }

    // ------------------------------------------------------------------ at a village on the round

    /**
     * At a village on the round: sold what it is short of, as much as it can pay for (into the purse); bought out of
     * the purse what home is short of and it has to spare (as much as the mules can take). Then on to the next.
     */
    private static void arrive(VillageData data, Trip t, Village home, Village there, Dweller m, long today) {
        org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("Trip #{} at #{}: {} with {}, purse {}; there: {} emeralds", t.from, there.id, m.name,
                t.cargo, t.purse, there.emeralds);
        EnumMap<Res, Integer> sold = new EnumMap<>(Res.class), bought = new EnumMap<>(Res.class);
        int earned = 0, paid = 0;
        for (Res r : GOODS) {
            int have = t.cargo.getOrDefault(r, 0);
            int n = Math.min(have, short_(there, r));
            while (n > 0 && Trade.total(cents(r), n) > there.emeralds) n -= Math.max(1, n / 8);
            if (n <= 0) continue;
            int price = Trade.total(cents(r), n);
            there.add(r, n);
            there.emeralds -= price;
            t.purse += price;
            earned += price;
            t.cargo.merge(r, -n, Integer::sum);
            sold.put(r, n);
            there.log(today, Component.translatable("minecraftportsmod.vlog.caravan_sold", m.name, home.name, n, r.displayName(), price)
                    .withStyle(ChatFormatting.GOLD));
        }
        t.cargo.values().removeIf(n -> n <= 0);
        // what home is short of (less what he carries of it already), the dearest first
        List<Res> byWorth = new ArrayList<>(List.of(GOODS));
        byWorth.sort((x, y) -> Double.compare(Trade.base(y), Trade.base(x)));
        for (Res r : byWorth) {
            int n = Math.min(short_(home, r) - t.cargo.getOrDefault(r, 0), spare(there, r));
            n = Math.min(n, MAX_LOAD - t.amount());
            while (n > 0 && Trade.total(cents(r), n) > t.purse) n -= Math.max(1, n / 8);
            if (n <= 0) continue;
            int price = Trade.total(cents(r), n);
            there.add(r, -n);
            there.emeralds += price;
            t.purse -= price;
            paid += price;
            t.cargo.merge(r, n, Integer::sum);
            bought.put(r, n);
            there.log(today, Component.translatable("minecraftportsmod.vlog.caravan_bought", m.name, home.name, n, r.displayName(), price)
                    .withStyle(ChatFormatting.GOLD));
        }
        if (!sold.isEmpty() || !bought.isEmpty()) home.traded = today;
        sold.forEach((r, n) -> {
            SOLD.merge(r, n, Integer::sum);
            SOLD_FOR.merge(r, Trade.total(cents(r), n), Integer::sum);
        });
        bought.forEach((r, n) -> {
            BOUGHT.merge(r, n, Integer::sum);
            BOUGHT_FOR.merge(r, Trade.total(cents(r), n), Integer::sum);
        });
        // (the last stop: what home still wants, less what he carries back, nobody on the round had)
        if (t.stops.isEmpty()) {
            for (Res r : GOODS) {
                int still = short_(home, r) - t.cargo.getOrDefault(r, 0);
                if (still > 0) UNMET.merge(r, still, Integer::sum);
            }
        }
        org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("Deal #{} at #{} day {}: sold {} for {}, bought {} for {}", home.id, there.id, today,
                sold, earned, bought, paid);
        if (sold.isEmpty() && bought.isEmpty()) {
            there.log(today, Component.translatable("minecraftportsmod.vlog.caravan_unsold", m.name, home.name).withStyle(ChatFormatting.GRAY));
        }
        home.log(today, Component.translatable("minecraftportsmod.vlog.caravan_at", m.name, there.name, goods(sold), earned, goods(bought), paid)
                .withStyle(ChatFormatting.GOLD));
        // on: the next village of the round, or home
        t.node = t.to;
        t.at = 0;
        if (!t.stops.isEmpty()) {
            t.to = t.stops.removeFirst();
        } else {
            t.back = true;
            t.to = t.from;
        }
    }

    // ------------------------------------------------------------------ the road

    private static final java.util.Map<Long, org.webtrade.minecraftportsmod.village.ResidentEntity> BODIES = new java.util.HashMap<>();
    /** The pack mules walking with a merchant who is in the world (each carries its share of the load). */
    private static final java.util.Map<Long, List<net.minecraft.world.entity.animal.equine.Mule>> MULES = new java.util.HashMap<>();
    /** The tag of a caravan's mule (one found with no merchant to follow, after a restart, is taken away). */
    static final String MULE_TAG = "minecraftportsmod_caravan";

    /** A world closed: the merchants walking in it are no one's now. */
    static void reset() {
        SOLD.clear();
        BOUGHT.clear();
        SOLD_FOR.clear();
        BOUGHT_FOR.clear();
        UNMET.clear();
        stayed = 0;
        stayedNothing = 0;
        BODIES.clear();
        MULES.clear();
        enabled = true;
    }

    /** The body of a village's person already in the world (after a restart, the one saved with the world), or null. */
    static org.webtrade.minecraftportsmod.village.ResidentEntity existing(ServerLevel level, Village v, Dweller d) {
        if (d.body != null && level.getEntity(d.body) instanceof org.webtrade.minecraftportsmod.village.ResidentEntity e && e.isAlive()
                && e.colonyVillage() == v.id && e.colonyDweller() == d.id) return e;
        return null;
    }

    /** Anyone else in the world who is this same person (a copy saved with the world): gone; there is only one of him. */
    static void single(ServerLevel level, org.webtrade.minecraftportsmod.village.ResidentEntity body, int village, int dweller) {
        for (var e : level.getEntitiesOfClass(org.webtrade.minecraftportsmod.village.ResidentEntity.class, body.getBoundingBox().inflate(96),
                e -> e != body && e.colony() && e.colonyVillage() == village && e.colonyDweller() == dweller)) {
            e.discard();
        }
    }

    /** How many mules a load takes: one for each {@link #PER_MULE} of it (one at least: to bring home what he buys). */
    static int mulesFor(Trip t) {
        return Math.max(1, Math.min(MAX_LOAD / PER_MULE, (int) Math.ceil(t.amount() / (double) PER_MULE)));
    }

    /** The merchant's mules, pack bags on, on a lead held by him. */
    private static void mules(ServerLevel level, Trip t, org.webtrade.minecraftportsmod.village.ResidentEntity body) {
        long k = key(t);
        List<net.minecraft.world.entity.animal.equine.Mule> list = MULES.computeIfAbsent(k, x -> new ArrayList<>());
        while (list.size() < mulesFor(t)) {
            var mule = net.minecraft.world.entity.EntityTypes.MULE.create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
            if (mule == null) return;
            double a = level.getRandom().nextDouble() * Math.PI * 2;
            mule.snapTo(body.getX() + Math.cos(a) * 2, body.getY(), body.getZ() + Math.sin(a) * 2, body.getYRot(), 0);
            mule.setChest(true);
            mule.addTag(MULE_TAG);
            level.addFreshEntity(mule);
            mule.setLeashedTo(body, true);
            list.add(mule);
        }
    }

    /** The mules go with the merchant: away from the world with him. */
    private static void dropMules(long k) {
        List<net.minecraft.world.entity.animal.equine.Mule> list = MULES.remove(k);
        if (list != null) for (var m : list) if (!m.isRemoved()) m.discard();
    }

    /**
     * The mules on the road: one lost on the way (killed) is lost with its share of the load; one gone astray (the lead
     * snapped) is led back to the merchant.
     */
    private static void keepMules(ServerLevel level, VillageData data, Trip t, Village home, org.webtrade.minecraftportsmod.village.ResidentEntity body) {
        List<net.minecraft.world.entity.animal.equine.Mule> list = MULES.get(key(t));
        if (list == null) return;
        int count = list.size();
        for (var m : new ArrayList<>(list)) {
            if (m.isRemoved()) {
                list.remove(m);
                if (m.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.KILLED && t.amount() > 0) {
                    EnumMap<Res, Integer> gone = new EnumMap<>(Res.class);
                    for (var e : t.cargo.entrySet()) gone.put(e.getKey(), (int) Math.ceil(e.getValue() / (double) Math.max(1, count)));
                    gone.forEach((r, n) -> t.cargo.merge(r, -n, Integer::sum));
                    t.cargo.values().removeIf(n -> n <= 0);
                    home.log(data.day, Component.translatable("minecraftportsmod.vlog.caravan_mule_lost_goods", goods(gone)).withStyle(ChatFormatting.RED));
                    data.changed();
                }
                continue;
            }
            if (m.distanceTo(body) > 10) m.snapTo(body.getX() + 1, body.getY(), body.getZ() + 1, m.getYRot(), 0);
            if (!m.isLeashed()) m.setLeashedTo(body, true);
        }
    }

    /** Caravan mules left in the world with no merchant to follow (a restart): taken away. */
    private static void strayMules(ServerLevel level) {
        java.util.Set<net.minecraft.world.entity.animal.equine.Mule> ours = new java.util.HashSet<>();
        for (var l : MULES.values()) ours.addAll(l);
        for (var pl : level.players()) {
            for (var m : level.getEntitiesOfClass(net.minecraft.world.entity.animal.equine.Mule.class, pl.getBoundingBox().inflate(128),
                    m -> m.entityTags().contains(MULE_TAG))) {
                if (!ours.contains(m)) m.discard();
            }
        }
    }

    private static long key(Trip t) {
        return t.from * 1_000_000L + t.merchant;
    }

    /** The way the merchant goes now (from the village he left to the one he is going to), over the made trails; null if gone. */
    private static int[] leg(VillageData data, Trip t) {
        return Trails.route(data, t.node, t.to);
    }

    private static double length(int[] p) {
        double len = 0;
        for (int i = 2; i + 1 < p.length; i += 2) len += Math.hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]);
        return len;
    }

    /** The point {@code d} blocks along a leg. */
    private static int[] along(int[] p, double d) {
        for (int i = 2; i + 1 < p.length; i += 2) {
            double s = Math.hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]);
            if (d <= s) {
                double f = s == 0 ? 0 : d / s;
                return new int[]{(int) Math.round(p[i - 2] + (p[i] - p[i - 2]) * f), (int) Math.round(p[i - 1] + (p[i + 1] - p[i - 1]) * f)};
            }
            d -= s;
        }
        return new int[]{p[p.length - 2], p[p.length - 1]};
    }

    /** How far along a leg the point nearest (x, z) is, looking near {@code at} (not back more than a little). */
    private static double project(int[] p, double x, double z, double at) {
        double best = Double.MAX_VALUE, bestAt = at, run = 0;
        for (int i = 2; i + 1 < p.length; i += 2) {
            double s = Math.hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]);
            if (run + s >= at - 30 && run <= at + 60) {
                double d = Math.hypot(x - p[i - 2], z - p[i - 1]);
                if (d < best) {
                    best = d;
                    bestAt = run;
                }
            }
            run += s;
        }
        return best < 24 ? bestAt : at;
    }

    private static boolean playerNear(ServerLevel level, int x, int z, int r) {
        for (var pl : level.players()) if (Math.hypot(pl.getX() - x, pl.getZ() - z) < r) return true;
        return false;
    }

    /** Is this merchant on the road now. */
    static Trip tripOf(VillageData data, Village v, Dweller d) {
        if (v == null || d == null) return null;
        for (Trip t : data.trips) if (t.from == v.id && t.merchant == d.id) return t;
        return null;
    }

    /** Where a merchant seen on the road walks to next: a way further along the trail. */
    static net.minecraft.core.BlockPos aim(ServerLevel level, Village v, Dweller d) {
        VillageData data = VillageData.get(level.getServer());
        Trip t = tripOf(data, v, d);
        int[] p = t == null ? null : leg(data, t);
        if (p == null) return null;
        int[] a = along(p, Math.min(length(p), t.at + 12));
        if (!level.hasChunkAt(new net.minecraft.core.BlockPos(a[0], 0, a[1]))) return null;
        return new net.minecraft.core.BlockPos(a[0], PlotFinder.floorAt(level, a[0], a[1]), a[1]);
    }

    /** What a merchant carries most of, to be seen in his hands. */
    static Res cargo(VillageData data, Village v, Dweller d) {
        Trip t = tripOf(data, v, d);
        return t == null ? null : t.res();
    }

    /**
     * Every second: the merchants on the road go on. One a player is near is there in the world, walking the trail
     * with his mules (and is as far along as he is); the rest walk unseen at their pace. One who is killed on the road
     * is lost with his goods; one who gets to a village trades there.
     */
    static void tick(ServerLevel level, VillageData data) {
        strayMules(level);
        for (Trip t : new ArrayList<>(data.trips)) {
            Village home = data.get(t.from), there = data.get(t.to);
            Dweller m = home == null ? null : home.dweller(t.merchant);
            int[] p = home == null || there == null ? null : leg(data, t);
            if (home == null || there == null || m == null || p == null) {
                // (no trail any more, no village: home with what he carried)
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("Trip #{} -> #{} called off: home {}, there {}, merchant {}, way {}", t.from, t.to,
                        home != null, there != null, m != null, p != null);
                data.trips.remove(t);
                dropMules(key(t));
                if (home != null) t.cargo.forEach(home::add);
                if (home != null) home.emeralds += t.purse;
                else data.burnt += t.purse;
                if (m != null) m.away = false;
                continue;
            }
            long k = key(t);
            var body = BODIES.get(k);
            if (body != null && body.isRemoved()) {
                if (body.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.KILLED) {
                    lost(data, t, home, there, m);
                    BODIES.remove(k);
                    dropMules(k);
                    continue;
                }
                BODIES.remove(k);
                dropMules(k);
                body = null;
            }
            if (body == null) {
                // (in the world already: the body saved with the world, or the one he left home in: his)
                var mine = existing(level, home, m);
                if (mine != null && playerNear(level, mine.getBlockX(), mine.getBlockZ(), SEEN + 32)) {
                    BODIES.put(k, mine);
                    body = mine;
                }
            }
            if (body != null) {
                var at = body.blockPosition();
                if (!level.isPositionEntityTicking(at) || !playerNear(level, at.getX(), at.getZ(), SEEN + 32)) {
                    // gone out of anyone's sight: on unseen from here
                    body.discard();
                    BODIES.remove(k);
                    dropMules(k);
                } else {
                    single(level, body, home.id, m.id);
                    t.at = Math.max(t.at, project(p, body.getX(), body.getZ(), t.at));
                    mules(level, t, body);
                    keepMules(level, data, t, home, body);
                }
            } else {
                t.at += PER_SECOND;
                int[] a = along(p, t.at);
                var pos = new net.minecraft.core.BlockPos(a[0], 0, a[1]);
                if (t.at < length(p) - 20 && playerNear(level, a[0], a[1], SEEN) && level.hasChunkAt(pos) && level.isPositionEntityTicking(pos)) {
                    var e = org.webtrade.minecraftportsmod.registry.ModContent.RESIDENT.create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
                    if (e != null) {
                        int y = PlotFinder.floorAt(level, a[0], a[1]);
                        e.snapTo(a[0] + 0.5, y, a[1] + 0.5, 0, 0);
                        e.syncColony(m, home, data.day);
                        level.addFreshEntity(e);
                        m.body = e.getUUID();
                        BODIES.put(k, e);
                        mules(level, t, e);
                    }
                }
            }
            walk(level, data, t, 0);
        }
    }

    /** The merchant goes on {@code d} blocks; if that brings him to the end of the leg, he gets there. */
    private static void walk(ServerLevel level, VillageData data, Trip t, double d) {
        Village home = data.get(t.from), there = data.get(t.to);
        Dweller m = home == null ? null : home.dweller(t.merchant);
        int[] p = home == null || there == null ? null : leg(data, t);
        if (m == null || p == null) return;
        t.at += d;
        if (t.at < length(p) - 6) return;
        // (a day skipped: what is left of the day's walk goes on the next leg)
        double over = Math.max(0, t.at - length(p));
        var body = BODIES.remove(key(t));
        if (body != null) body.discard();
        dropMules(key(t));
        if (!t.back) {
            arrive(data, t, home, there, m, data.day);
            if (d > 0 && over > 0) walk(level, data, t, over);
        } else {
            comeHome(data, t, home, m, data.day);
        }
        data.changed();
    }

    /** Killed on the road: the merchant is gone, and what he carried with him (his purse too). */
    private static void lost(VillageData data, Trip t, Village home, Village there, Dweller m) {
        data.trips.remove(t);
        home.dwellers.remove(m);
        data.burnt += t.purse;
        home.log(data.day, Component.translatable("minecraftportsmod.vlog.caravan_lost", m.name, there.name, goods(t.cargo)).withStyle(ChatFormatting.RED));
        there.log(data.day, Component.translatable("minecraftportsmod.vlog.caravan_lost_there", m.name, home.name).withStyle(ChatFormatting.RED));
        VillageLife.rehouse(home, data.day);
        data.changed();
    }

    private static void comeHome(VillageData data, Trip t, Village home, Dweller m, long today) {
        org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("Trip #{}: {} home with {} and {} emeralds", t.from, m.name, t.cargo, t.purse);
        t.cargo.forEach(home::add);
        home.emeralds += t.purse;
        m.away = false;
        m.arriving = true;
        data.trips.remove(t);
        home.log(today, (t.amount() > 0
                ? Component.translatable("minecraftportsmod.vlog.caravan_home_round", m.name, goods(t.cargo), t.purse)
                : Component.translatable("minecraftportsmod.vlog.caravan_home_empty", m.name)).withStyle(ChatFormatting.DARK_AQUA));
    }
}
