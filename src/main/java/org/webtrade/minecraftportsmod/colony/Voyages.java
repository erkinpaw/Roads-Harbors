package org.webtrade.minecraftportsmod.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.combat.Currents;
import org.webtrade.minecraftportsmod.vessel.TradeShipEntity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trade over the sea: the ships of the villages with a pier ({@link Harbour}). A ship whose skipper is at home sets
 * out on a round of up to {@link #MAX_STOPS} other harbours, much as a merchant does over the trails
 * ({@link Caravans}): loaded with what home has to spare and those harbours are short of, with emeralds to buy what
 * home is short of. A ship carries far more than a merchant's mules and goes faster, so over the sea trade pays
 * better; an island (with no trail to it) trades only so.
 * <p>
 * The voyage is real: the ship goes along her way at her pace (the current helping or holding her), seen where a
 * player is near, unseen elsewhere all the same. She trades at a harbour only when she gets there. The ways between
 * the harbours are worked out on the world's generator (over the open water, away from the land).
 */
public final class Voyages {

    /** A ship's pace under sail, blocks a second (before the current). */
    static final double SPEED = 4.0;
    /** Blocks a ship makes in a day (a day skipped). */
    static final int PER_DAY = (int) (SPEED * 1200);
    /** Days a ship lies at her pier between voyages. */
    static final int REST = 2;
    /** The longest round over the sea (there and home), and the farthest harbours a way is worked out to. */
    static final int MAX_SAIL = 9000, MAX_LANE = 2600;
    /** A voyage is not worth making for less than this much to sell and buy, all told. */
    static final int MIN_DEAL = 150;
    /** The most harbours on a round. */
    static final int MAX_STOPS = 3;
    /** A ship is put into the world where she is when a player is this near (and her pier's ships when this near it). */
    static final int SEEN = 160, PIER_SEEN = 112;

    /** What changed hands over the sea in this world (since it was opened). */
    public static final EnumMap<Res, Integer> SOLD = new EnumMap<>(Res.class), BOUGHT = new EnumMap<>(Res.class);
    /** Voyages made, and days a ship stayed for want of trade. */
    public static int sailed, stayed, arrivals;

    private Voyages() {
    }

    /** One ship on a voyage. */
    public static final class Voyage {
        static final Codec<Voyage> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("from").forGetter(t -> t.from),
                Codec.INT.fieldOf("ship").forGetter(t -> t.ship),
                Codec.INT.fieldOf("sailor").forGetter(t -> t.sailor),
                Codec.INT.fieldOf("to").forGetter(t -> t.to),
                Codec.INT.fieldOf("node").forGetter(t -> t.node),
                Codec.INT.listOf().optionalFieldOf("stops", List.of()).forGetter(t -> t.stops),
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("cargo", Map.of()).forGetter(t -> {
                    Map<String, Integer> m = new HashMap<>();
                    t.cargo.forEach((r, n) -> m.put(r.id(), n));
                    return m;
                }),
                Codec.BOOL.optionalFieldOf("back", false).forGetter(t -> t.back),
                Codec.DOUBLE.optionalFieldOf("at", 0.0).forGetter(t -> t.at),
                Codec.INT.optionalFieldOf("purse", 0).forGetter(t -> t.purse)
        ).apply(i, (from, ship, sailor, to, node, stops, cargo, back, at, purse) -> {
            Voyage v = new Voyage(from, ship, sailor, to);
            v.node = node;
            v.stops.addAll(stops);
            cargo.forEach((k, n) -> {
                Res r = Res.byId(k);
                if (r != null && n > 0) v.cargo.put(r, n);
            });
            v.back = back;
            v.at = at;
            v.purse = purse;
            return v;
        }));

        public final int from, ship, sailor;
        /** The harbour she is sailing to now, and the one she left (home, or the one before). */
        int to, node;
        final List<Integer> stops = new ArrayList<>();
        final EnumMap<Res, Integer> cargo = new EnumMap<>(Res.class);
        boolean back;
        /** Blocks along this leg. */
        double at;
        int purse;

        Voyage(int from, int ship, int sailor, int to) {
            this.from = from;
            this.ship = ship;
            this.sailor = sailor;
            this.to = to;
            this.node = from;
        }

        public int to() {
            return to;
        }

        public boolean back() {
            return back;
        }

        public double at() {
            return at;
        }

        public int amount() {
            int n = 0;
            for (int x : cargo.values()) n += x;
            return n;
        }

        public Map<Res, Integer> cargo() {
            return java.util.Collections.unmodifiableMap(cargo);
        }
    }

    /** The ways over the sea between harbours (key: the two villages; the points from the lower id to the higher). */
    public record Lane(int a, int b, List<Integer> path) {
        static final Codec<Lane> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("a").forGetter(Lane::a),
                Codec.INT.fieldOf("b").forGetter(Lane::b),
                Codec.INT.listOf().fieldOf("path").forGetter(Lane::path)
        ).apply(i, Lane::new));

        int[] points() {
            int[] p = new int[path.size()];
            for (int k = 0; k < p.length; k++) p[k] = path.get(k);
            return p;
        }
    }

    /** Everything of the sea trade kept with the world. */
    record Sea(List<Voyage> voyages, List<Lane> lanes, List<Long> none, Map<String, Long> last) {
        static final Sea EMPTY = new Sea(List.of(), List.of(), List.of(), Map.of());
        static final Codec<Sea> CODEC = RecordCodecBuilder.create(i -> i.group(
                Voyage.CODEC.listOf().optionalFieldOf("voyages", List.of()).forGetter(Sea::voyages),
                Lane.CODEC.listOf().optionalFieldOf("lanes", List.of()).forGetter(Sea::lanes),
                Codec.LONG.listOf().optionalFieldOf("none", List.of()).forGetter(Sea::none),
                Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("last", Map.of()).forGetter(Sea::last)
        ).apply(i, Sea::new));
    }

    static Sea save(VillageData d) {
        List<Lane> lanes = new ArrayList<>();
        d.seaLanes.forEach((k, p) -> {
            List<Integer> l = new ArrayList<>();
            for (int x : p) l.add(x);
            lanes.add(new Lane((int) (k >> 32), (int) (long) k, l));
        });
        Map<String, Long> last = new HashMap<>();
        d.lastVoyage.forEach((k, v) -> last.put(String.valueOf(k), v));
        return new Sea(new ArrayList<>(d.voyages), lanes, new ArrayList<>(d.noSea), last);
    }

    static void load(VillageData d, Sea s) {
        d.voyages.addAll(s.voyages());
        for (Lane l : s.lanes()) d.seaLanes.put(key(l.a(), l.b()), l.points());
        d.noSea.addAll(s.none());
        s.last().forEach((k, v) -> {
            try {
                d.lastVoyage.put(Integer.parseInt(k), v);
            } catch (NumberFormatException ignored) {
            }
        });
    }

    static long key(int a, int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------ the ways over the sea

    /** Pairs of harbours whose way is being worked out now. */
    private static final Set<Long> ASKED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The way from harbour {@code a} to harbour {@code b} (points x, z), or null if there is none (yet). */
    static int[] lane(VillageData data, int a, int b) {
        int[] p = data.seaLanes.get(key(a, b));
        if (p == null || a < b) return p;
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i += 2) {
            r[p.length - 2 - i] = p[i];
            r[p.length - 1 - i] = p[i + 1];
        }
        return r;
    }

    /** The harbours of the world: the villages with a pier standing. */
    static List<Village> harbours(VillageData data) {
        List<Village> out = new ArrayList<>();
        for (Village v : data.all()) if (Harbour.pier(v) != null) out.add(v);
        return out;
    }

    /** Ways asked for between every two harbours near enough, that have none yet. */
    private static void askLanes(ServerLevel level, VillageData data) {
        List<Village> hs = harbours(data);
        for (int i = 0; i < hs.size(); i++) {
            for (int j = i + 1; j < hs.size(); j++) {
                Village a = hs.get(i), b = hs.get(j);
                long k = key(a.id, b.id);
                if (data.seaLanes.containsKey(k) || data.noSea.contains(k) || ASKED.contains(k)) continue;
                if (Math.hypot(a.center.getX() - b.center.getX(), a.center.getZ() - b.center.getZ()) > MAX_LANE) continue;
                Village lo = a.id < b.id ? a : b, hi = a.id < b.id ? b : a;
                BlockPos from = Harbour.seaward(Harbour.pier(lo)), to = Harbour.seaward(Harbour.pier(hi));
                ASKED.add(k);
                boolean asked = org.webtrade.minecraftportsmod.worldgen.WorldPlanner.seaLane(from.getX(), from.getZ(), to.getX(), to.getZ(), p -> {
                    ASKED.remove(k);
                    VillageData d = VillageData.get(level.getServer());
                    if (p == null || p.length < 2) {
                        d.noSea.add(k);
                        Minecraftportsmod.LOGGER.info("[sea] no way over the sea between #{} and #{}", lo.id, hi.id);
                    } else {
                        int[] full = new int[p.length + 4];
                        full[0] = from.getX();
                        full[1] = from.getZ();
                        System.arraycopy(p, 0, full, 2, p.length);
                        full[full.length - 2] = to.getX();
                        full[full.length - 1] = to.getZ();
                        d.seaLanes.put(k, full);
                        Minecraftportsmod.LOGGER.info("[sea] way between #{} {} and #{} {}: {} blocks", lo.id, lo.name, hi.id, hi.name, (int) length(full));
                    }
                    d.changed();
                });
                if (!asked) ASKED.remove(k);
            }
        }
    }

    static double length(int[] p) {
        return TradeShipEntity.length(p);
    }

    /** A ship's pace where she is, going that way: her speed, and the current along her way (blocks a second). */
    static double pace(double x, double z, double dx, double dz) {
        double[] c = Currents.at(x, z);
        return Math.max(1.5, SPEED + (c[0] * dx + c[1] * dz) * 20);
    }

    // ------------------------------------------------------------------ setting out

    /** The voyage ship {@code i} of a village is on, or null (at her pier). */
    static Voyage voyageOf(VillageData data, int village, int i) {
        for (Voyage t : data.voyages) if (t.from == village && t.ship == i) return t;
        return null;
    }

    /** A new day: ships at their piers setting out (and, a day skipped, the ships at sea sail a day's way). */
    static void day(ServerLevel level, VillageData data, boolean skip) {
        askLanes(level, data);
        if (skip) {
            for (Voyage t : new ArrayList<>(data.voyages)) {
                if (BODIES.containsKey(bodyKey(t.from, t.ship))) continue;
                sail(level, data, t, PER_DAY);
            }
        }
        if (!Caravans.enabled) return;
        for (Village v : harbours(data)) {
            int ships = Harbour.ships(v);
            for (int i = 0; i < ships; i++) {
                if (voyageOf(data, v.id, i) != null) continue;
                if (data.day - data.lastVoyage.getOrDefault(v.id * 8 + i, Long.MIN_VALUE / 2) < REST) continue;
                Dweller s = freeSailor(data, v);
                if (s == null) continue;
                setOut(data, v, s, i, data.day);
            }
        }
        data.changed();
    }

    /** A skipper at home, not sailing a ship already. */
    private static Dweller freeSailor(VillageData data, Village v) {
        for (Dweller d : v.dwellers) {
            if (d.job != Job.SAILOR || d.away) continue;
            boolean busy = false;
            for (Voyage t : data.voyages) if (t.from == v.id && t.sailor == d.id) busy = true;
            if (!busy) return d;
        }
        return null;
    }

    private static void setOut(VillageData data, Village v, Dweller s, int ship, long today) {
        setOut(data, v, s, ship, today, null);
    }

    /** {@code first}: a harbour the ship sails to first whatever the trade there (a player's passage), or null. */
    private static Voyage setOut(VillageData data, Village v, Dweller s, int ship, long today, Village first) {
        int cap = Harbour.load(v, ship);
        EnumMap<Res, Integer> spare = new EnumMap<>(Res.class);
        for (Res r : Caravans.GOODS) if (Caravans.spare(v, r) > 0) spare.put(r, Caravans.spare(v, r));
        int money = v.emeralds;
        // (what the other ship of the village carries already is not loaded twice)
        Voyage other = null;
        for (Voyage o : data.voyages) if (o.from == v.id && o.ship != ship) other = o;

        List<Village> can = new ArrayList<>();
        Map<Integer, Double> worth = new HashMap<>();
        for (Village o : harbours(data)) {
            if (o.id == v.id) continue;
            if (other != null && (other.to == o.id || other.stops.contains(o.id))) continue;
            int[] lane = lane(data, v.id, o.id);
            if (lane == null || 2 * length(lane) > MAX_SAIL) continue;
            double w = Caravans.worth(v, o, spare, money);
            if (w < 1) continue;
            can.add(o);
            worth.put(o.id, w);
        }
        if (can.isEmpty() && first == null) {
            stayed++;
            if (today % 10 == 0) {
                int lanes = 0, spareAll = 0;
                for (Village o : harbours(data)) if (o.id != v.id && lane(data, v.id, o.id) != null) lanes++;
                for (int n : spare.values()) spareAll += n;
                Minecraftportsmod.LOGGER.info("[sea] #{} {} ship {} stays: {} harbours in reach, {} to spare, {} emeralds", v.id, v.name, ship, lanes,
                        spareAll, money);
            }
            return null;
        }
        can.sort((x, y) -> Double.compare(worth.get(y.id), worth.get(x.id)));
        if (first != null) can.remove(first);
        List<Village> chosen = new ArrayList<>(can.subList(0, Math.min(first != null ? MAX_STOPS - 1 : MAX_STOPS, can.size())));
        List<Village> round = new ArrayList<>();
        int at = v.id;
        double sailed_ = 0;
        if (first != null) {
            round.add(first);
            int[] l = lane(data, v.id, first.id);
            sailed_ = l == null ? 0 : length(l);
            at = first.id;
        }
        while (!chosen.isEmpty()) {
            Village next = null;
            double nearest = Double.MAX_VALUE;
            for (Village o : chosen) {
                int[] l = lane(data, at, o.id);
                if (l != null && length(l) < nearest) {
                    nearest = length(l);
                    next = o;
                }
            }
            if (next == null) break;
            chosen.remove(next);
            int[] home = lane(data, next.id, v.id);
            if (!round.isEmpty() && (home == null || sailed_ + nearest + length(home) > MAX_SAIL)) break;
            round.add(next);
            sailed_ += nearest;
            at = next.id;
        }
        if (round.isEmpty()) return null;
        EnumMap<Res, Integer> load = new EnumMap<>(Res.class);
        int total = 0;
        List<Res> byWorth = new ArrayList<>(List.of(Caravans.GOODS));
        byWorth.sort((x, y) -> Double.compare(Trade.base(y), Trade.base(x)));
        for (Res r : byWorth) {
            int wanted = 0;
            for (Village o : round) wanted += Caravans.short_(o, r);
            int n = Math.min(Math.min(spare.getOrDefault(r, 0), wanted), cap - total);
            if (n <= 0) continue;
            load.put(r, n);
            total += n;
        }
        double buy = 0;
        int toBuy = 0;
        for (Res r : Caravans.GOODS) {
            int there = 0;
            for (Village o : round) there += Caravans.spare(o, r);
            buy += Math.min(Caravans.short_(v, r), there) * Trade.base(r);
            toBuy += Math.min(Caravans.short_(v, r), there);
        }
        int purse = Math.min(v.emeralds, (int) Math.ceil(buy * 1.2));
        if (total + (purse > 0 ? toBuy : 0) < MIN_DEAL && first == null) {
            stayed++;
            return null;
        }
        for (var e : load.entrySet()) v.add(e.getKey(), -e.getValue());
        v.emeralds -= purse;
        s.away = true;
        Voyage t = new Voyage(v.id, ship, s.id, round.getFirst().id);
        t.cargo.putAll(load);
        t.purse = purse;
        for (int k = 1; k < round.size(); k++) t.stops.add(round.get(k).id);
        data.voyages.add(t);
        data.lastVoyage.put(v.id * 8 + ship, today);
        sailed++;
        MutableComponent names = Component.empty();
        for (int k = 0; k < round.size(); k++) names.append(k == 0 ? "" : ", ").append(round.get(k).name);
        v.log(today, (total > 0
                ? Component.translatable("minecraftportsmod.vlog.ship_round", s.name, names, Caravans.goods(load), purse)
                : Component.translatable("minecraftportsmod.vlog.ship_round_buy", s.name, names, purse)).withStyle(ChatFormatting.DARK_AQUA));
        Minecraftportsmod.LOGGER.info("[sea] #{} {} ship {} sets out for {} with {} and {} emeralds", v.id, v.name, ship, names.getString(), load, purse);
        return t;
    }

    // ------------------------------------------------------------------ passengers

    /** What a passage costs: an emerald for every 250 blocks of the way, two at least. */
    static int fare(int[] lane) {
        return Math.max(2, (int) Math.ceil(length(lane) / 250));
    }

    /** A ship of the village at its pier, free to sail now (-1: none). */
    private static int freeShip(VillageData data, Village v) {
        for (int i = 0; i < Harbour.ships(v); i++) if (voyageOf(data, v.id, i) == null) return i;
        return -1;
    }

    /** Where a skipper at home with a ship free at the pier will take a player, and for how much (the nearest first, six at most). */
    public static List<org.webtrade.minecraftportsmod.network.ColonyPayloads.Passage> passages(VillageData data, Village v, Dweller s) {
        List<org.webtrade.minecraftportsmod.network.ColonyPayloads.Passage> out = new ArrayList<>();
        if (s.away || freeShip(data, v) < 0) return out;
        for (Voyage t : data.voyages) if (t.from == v.id && t.sailor == s.id) return out;
        List<Village> hs = harbours(data);
        hs.sort(java.util.Comparator.comparingDouble(o -> {
            int[] l = lane(data, v.id, o.id);
            return l == null ? Double.MAX_VALUE : length(l);
        }));
        for (Village o : hs) {
            if (o.id == v.id) continue;
            int[] l = lane(data, v.id, o.id);
            if (l == null || out.size() >= 6) continue;
            out.add(new org.webtrade.minecraftportsmod.network.ColonyPayloads.Passage(o.id, o.name, fare(l)));
        }
        return out;
    }

    /**
     * A player pays for a passage to {@code to}: the skipper puts to sea with him at once, on a round that calls there
     * first (and trades as usual); the player stands on deck, and is put ashore on the jetty there.
     */
    public static boolean charter(net.minecraft.server.level.ServerPlayer player, VillageData data, Village v, Dweller s, int to) {
        Village target = data.get(to);
        int ship = freeShip(data, v);
        Building pier = Harbour.pier(v);
        int[] lane = target == null ? null : lane(data, v.id, target.id);
        if (target == null || ship < 0 || pier == null || lane == null || s.away || s.job != Job.SAILOR) return false;
        int fare = fare(lane);
        var inv = player.getInventory();
        if (inv.countItem(net.minecraft.world.item.Items.EMERALD) < fare) return false;
        Voyage t = setOut(data, v, s, ship, data.day, target);
        if (t == null) return false;
        int left = fare;
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            var st = inv.getItem(i);
            if (!st.is(net.minecraft.world.item.Items.EMERALD)) continue;
            int n = Math.min(left, st.getCount());
            st.shrink(n);
            left -= n;
        }
        v.emeralds += fare;
        ServerLevel level = (ServerLevel) player.level();
        TradeShipEntity e = org.webtrade.minecraftportsmod.registry.ModContent.TRADE_SHIP.create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
        if (e != null) {
            drop(MOORED.remove(bodyKey(v.id, ship)));
            e.sail(Harbour.tier(v, ship), lane, 0, level.getSeaLevel(), t.amount() > 0, a -> pace(a[0], a[1], a[2], a[3]));
            level.addFreshEntity(e);
            BODIES.put(bodyKey(v.id, ship), e);
            player.startRiding(e, true, true);
        }
        v.log(data.day, Component.translatable("minecraftportsmod.vlog.passage", player.getName(), target.name, fare).withStyle(ChatFormatting.GOLD));
        data.changed();
        return true;
    }

    /** Players on deck put ashore at a harbour: on its jetty (or its middle, with no jetty). */
    private static void ashore(TradeShipEntity body, Village there) {
        if (body == null || there == null || body.getPassengers().isEmpty()) return;
        Building pier = Harbour.pier(there);
        BlockPos to = there.center;
        if (pier != null) {
            net.minecraft.core.Direction out = Harbour.out(pier);
            int k = pier.type.half + Math.max(1, pier.jetty[1] - 1);
            to = new BlockPos(pier.origin.getX() + out.getStepX() * k, pier.origin.getY(), pier.origin.getZ() + out.getStepZ() * k);
        }
        for (var p : new ArrayList<>(body.getPassengers())) {
            p.stopRiding();
            p.teleportTo(to.getX() + 0.5, to.getY() + 0.1, to.getZ() + 0.5);
        }
    }

    // ------------------------------------------------------------------ at a harbour

    /** At a harbour of the round: sold what it is short of (as it can pay), bought what home is short of. On to the next, or home. */
    private static void arrive(VillageData data, Voyage t, Village home, Village there, Dweller s, long today) {
        arrivals++;
        int cap = Math.max(Harbour.load(home, t.ship), t.amount());
        EnumMap<Res, Integer> sold = new EnumMap<>(Res.class), bought = new EnumMap<>(Res.class);
        int earned = 0, paid = 0;
        for (Res r : Caravans.GOODS) {
            int n = Math.min(t.cargo.getOrDefault(r, 0), Caravans.short_(there, r));
            while (n > 0 && Trade.total(Caravans.cents(r), n) > there.emeralds) n -= Math.max(1, n / 8);
            if (n <= 0) continue;
            int price = Trade.total(Caravans.cents(r), n);
            there.add(r, n);
            there.emeralds -= price;
            t.purse += price;
            earned += price;
            t.cargo.merge(r, -n, Integer::sum);
            sold.put(r, n);
            there.log(today, Component.translatable("minecraftportsmod.vlog.ship_sold", s.name, home.name, n, r.displayName(), price)
                    .withStyle(ChatFormatting.GOLD));
        }
        t.cargo.values().removeIf(n -> n <= 0);
        List<Res> byWorth = new ArrayList<>(List.of(Caravans.GOODS));
        byWorth.sort((x, y) -> Double.compare(Trade.base(y), Trade.base(x)));
        for (Res r : byWorth) {
            int n = Math.min(Caravans.short_(home, r) - t.cargo.getOrDefault(r, 0), Caravans.spare(there, r));
            n = Math.min(n, cap - t.amount());
            while (n > 0 && Trade.total(Caravans.cents(r), n) > t.purse) n -= Math.max(1, n / 8);
            if (n <= 0) continue;
            int price = Trade.total(Caravans.cents(r), n);
            there.add(r, -n);
            there.emeralds += price;
            t.purse -= price;
            paid += price;
            t.cargo.merge(r, n, Integer::sum);
            bought.put(r, n);
            there.log(today, Component.translatable("minecraftportsmod.vlog.ship_bought", s.name, home.name, n, r.displayName(), price)
                    .withStyle(ChatFormatting.GOLD));
        }
        if (!sold.isEmpty() || !bought.isEmpty()) home.traded = today;
        sold.forEach((r, n) -> SOLD.merge(r, n, Integer::sum));
        bought.forEach((r, n) -> BOUGHT.merge(r, n, Integer::sum));
        Minecraftportsmod.LOGGER.info("[sea] #{} ship {} at #{} day {}: sold {} for {}, bought {} for {}", home.id, t.ship, there.id, today, sold, earned,
                bought, paid);
        home.log(today, Component.translatable("minecraftportsmod.vlog.ship_at", s.name, there.name, Caravans.goods(sold), earned,
                Caravans.goods(bought), paid).withStyle(ChatFormatting.GOLD));
        t.node = t.to;
        t.at = 0;
        if (!t.stops.isEmpty()) {
            t.to = t.stops.removeFirst();
        } else {
            t.back = true;
            t.to = t.from;
        }
    }

    private static void comeHome(VillageData data, Voyage t, Village home, Dweller s, long today) {
        t.cargo.forEach(home::add);
        home.emeralds += t.purse;
        s.away = false;
        s.arriving = true;
        data.voyages.remove(t);
        home.log(today, (t.amount() > 0
                ? Component.translatable("minecraftportsmod.vlog.ship_home", s.name, Caravans.goods(t.cargo), t.purse)
                : Component.translatable("minecraftportsmod.vlog.ship_home_empty", s.name, t.purse)).withStyle(ChatFormatting.DARK_AQUA));
        Minecraftportsmod.LOGGER.info("[sea] #{} ship {} home with {} and {} emeralds", home.id, t.ship, t.cargo, t.purse);
    }

    // ------------------------------------------------------------------ the sea

    /** The ships in the world: under way (by village and ship), and moored at their piers. */
    private static final Map<Long, TradeShipEntity> BODIES = new HashMap<>(), MOORED = new HashMap<>();

    private static long bodyKey(int village, int ship) {
        return village * 8L + ship;
    }

    /** A world closed. */
    static void reset() {
        SOLD.clear();
        BOUGHT.clear();
        sailed = 0;
        stayed = 0;
        arrivals = 0;
        BODIES.clear();
        MOORED.clear();
        ASKED.clear();
        Harbour.testWanted = false;
    }

    /** The leg a ship is sailing now (from the harbour she left to the one she is bound for). */
    private static int[] leg(VillageData data, Voyage t) {
        return lane(data, t.node, t.to);
    }

    /** Where a ship at sea is now, {x, z}; null when her way is gone. */
    public static double[] where(VillageData data, Voyage t) {
        int[] p = leg(data, t);
        return p == null ? null : TradeShipEntity.point(p, t.at);
    }

    private static boolean playerNear(ServerLevel level, double x, double z, int r) {
        for (var pl : level.players()) if (Math.hypot(pl.getX() - x, pl.getZ() - z) < r) return true;
        return false;
    }

    /**
     * Every second: the ships at sea go on (seen ones by themselves, the rest at the same pace unseen); one that gets
     * to a harbour trades there, or is home. The ships at their piers lie there to be seen.
     */
    static void tick(ServerLevel level, VillageData data) {
        for (Voyage t : new ArrayList<>(data.voyages)) {
            Village home = data.get(t.from), there = data.get(t.to);
            Dweller s = home == null ? null : home.dweller(t.sailor);
            int[] p = home == null || there == null ? null : leg(data, t);
            if (home == null || there == null || s == null || p == null) {
                Minecraftportsmod.LOGGER.info("[sea] voyage of #{} ship {} called off", t.from, t.ship);
                data.voyages.remove(t);
                drop(BODIES.remove(bodyKey(t.from, t.ship)));
                if (home != null) {
                    t.cargo.forEach(home::add);
                    home.emeralds += t.purse;
                } else data.burnt += t.purse;
                if (s != null) s.away = false;
                continue;
            }
            long k = bodyKey(t.from, t.ship);
            TradeShipEntity body = BODIES.get(k);
            if (body != null && (body.isRemoved() || !level.isPositionEntityTicking(body.blockPosition())
                    || !playerNear(level, body.getX(), body.getZ(), SEEN + 32))) {
                drop(body);
                BODIES.remove(k);
                body = null;
            }
            if (body != null) {
                body.tend();
                t.at = Math.max(t.at, body.at());
            } else {
                double[] here = TradeShipEntity.point(p, t.at), d = TradeShipEntity.heading(p, t.at);
                t.at += pace(here[0], here[1], d[0], d[1]);
                double[] now = TradeShipEntity.point(p, t.at);
                BlockPos pos = BlockPos.containing(now[0], level.getSeaLevel(), now[1]);
                if (t.at < length(p) - 12 && playerNear(level, now[0], now[1], SEEN) && level.hasChunkAt(pos) && level.isPositionEntityTicking(pos)) {
                    TradeShipEntity e = org.webtrade.minecraftportsmod.registry.ModContent.TRADE_SHIP.create(level,
                            net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
                    if (e != null) {
                        e.sail(Harbour.tier(home, t.ship), p, t.at, level.getSeaLevel(), t.amount() > 0, a -> pace(a[0], a[1], a[2], a[3]));
                        level.addFreshEntity(e);
                        BODIES.put(k, e);
                    }
                }
            }
            reach(level, data, t, p);
        }
        moored(level, data);
    }

    /** At the end of her leg: she gets there (the body that sailed it lies at the harbour until the next leg is seen). */
    private static void reach(ServerLevel level, VillageData data, Voyage t, int[] p) {
        if (t.at < length(p) - 2) return;
        Village home = data.get(t.from), there = data.get(t.to);
        Dweller s = home.dweller(t.sailor);
        TradeShipEntity body = BODIES.remove(bodyKey(t.from, t.ship));
        ashore(body, there);
        drop(body);

        if (!t.back) arrive(data, t, home, there, s, data.day);
        else {
            comeHome(data, t, home, s, data.day);
            data.lastVoyage.put(t.from * 8 + t.ship, data.day);
        }
        data.changed();
    }

    /** {@code d} blocks of sailing at once (a day skipped): legs ended on the way are sailed into the next. */
    private static void sail(ServerLevel level, VillageData data, Voyage t, double d) {
        for (int guard = 0; guard < 8 && d > 0 && data.voyages.contains(t); guard++) {
            int[] p = leg(data, t);
            if (p == null) return;
            double left = length(p) - t.at;
            if (d < left) {
                t.at += d;
                return;
            }
            t.at = length(p);
            d -= left;
            reach(level, data, t, p);
        }
    }

    private static void drop(TradeShipEntity e) {
        if (e != null && !e.isRemoved()) e.discard();
    }

    /** The ships lying at their piers, seen when a player is near the pier. */
    private static void moored(ServerLevel level, VillageData data) {
        java.util.Set<Long> keep = new java.util.HashSet<>();
        for (Village v : harbours(data)) {
            Building pier = Harbour.pier(v);
            if (pier == null || !playerNear(level, pier.origin.getX(), pier.origin.getZ(), PIER_SEEN)) continue;
            // the one being built: a bare hull at her berth
            if (Harbour.building(v) && v.ships < Harbour.MAX_SHIPS) {
                int i = v.ships;
                long k = bodyKey(v.id, i);
                Vec3 at = Harbour.berth(pier, i);
                BlockPos bp = BlockPos.containing(at);
                if (level.hasChunkAt(bp) && level.isPositionEntityTicking(bp)) {
                    keep.add(k);
                    TradeShipEntity e = MOORED.get(k);
                    if (e == null || e.isRemoved() || !e.onStocks()) {
                        drop(e);
                        e = org.webtrade.minecraftportsmod.registry.ModContent.TRADE_SHIP.create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
                        if (e != null) {
                            e.stocks(v.id, Harbour.tier(v, i), at, Harbour.berthYaw(pier));
                            level.addFreshEntity(e);
                            MOORED.put(k, e);
                        }
                    } else {
                        e.stocks(v.id, Harbour.tier(v, i), at, Harbour.berthYaw(pier));
                    }
                }
            }
            for (int i = 0; i < Harbour.ships(v); i++) {
                if (voyageOf(data, v.id, i) != null) continue;
                long k = bodyKey(v.id, i);
                Vec3 at = Harbour.berth(pier, i);
                BlockPos bp = BlockPos.containing(at);
                if (!level.hasChunkAt(bp) || !level.isPositionEntityTicking(bp)) continue;
                keep.add(k);
                TradeShipEntity e = MOORED.get(k);
                if (e == null || e.isRemoved() || e.onStocks()) {
                    drop(e);
                    e = org.webtrade.minecraftportsmod.registry.ModContent.TRADE_SHIP.create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
                    if (e == null) continue;
                    e.moor(Harbour.tier(v, i), at, Harbour.berthYaw(pier), false);
                    level.addFreshEntity(e);
                    MOORED.put(k, e);
                }
                e.moor(Harbour.tier(v, i), at, Harbour.berthYaw(pier), false);
            }
        }
        for (var it = MOORED.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!keep.contains(e.getKey())) {
                drop(e.getValue());
                it.remove();
            }
        }
    }

    /** Where the village's ships are: at the pier, or at sea (bound where, with what). */
    public static Component status(VillageData data, Village v) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < Harbour.ships(v); i++) {
            Voyage t = voyageOf(data, v.id, i);
            if (i > 0) out.append("; ");
            if (t == null) out.append(Component.translatable("minecraftportsmod.sea.at_pier"));
            else {
                Village to = data.get(t.to);
                out.append(t.back ? Component.translatable("minecraftportsmod.sea.coming_home", Caravans.goods(t.cargo), t.purse)
                        : Component.translatable("minecraftportsmod.sea.bound", to == null ? "?" : to.name, Caravans.goods(t.cargo), t.purse));
            }
        }
        return out;
    }
}
