package org.webtrade.minecraftportsmod.economy;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.fleet.FleetManager;
import org.webtrade.minecraftportsmod.fleet.VesselRecord;
import org.webtrade.minecraftportsmod.fleet.VesselType;
import org.webtrade.minecraftportsmod.port.Port;
import org.webtrade.minecraftportsmod.port.PortData;
import org.webtrade.minecraftportsmod.port.Route;
import org.webtrade.minecraftportsmod.worldgen.WorldPlanner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs the settlements: the economy clock, the daily {@link Simulation} step and the trade trips of the
 * settlements' own vessels. Merchants only trade where they expect a profit, and only with prices they have seen —
 * prices travel home with the ships.
 */
public final class EconomyManager {

    /** Abstract goods units per hold slot. */
    static final double UNITS_PER_SLOT = 32;
    /** Wages and wear per block sailed, emeralds. */
    static final double FREIGHT_PER_BLOCK = 0.0015;
    /** A trip must promise at least this much. */
    static final double MIN_PROFIT = 1.5;
    /** Ticks a trader spends loading or trading in port. */
    static final int PORT_STAY = 600;
    /** How long a departure waits for a skipper who is still ashore. */
    static final int CREW_WAIT = 1200;
    /** Ticks an idle trader waits before looking for a deal again. */
    static final int IDLE_RECHECK = 1200;
    /** Share of the treasury a merchant takes along to buy goods. */
    static final double PURSE_SHARE = 0.3;
    static final double PURSE_MAX = 80;
    /** A merchant buys only where a good is at least this much cheaper than at home (and sells vice versa). */
    static final double MARGIN = 1.2;
    /** A second trade vessel is built at home from these (hull planks, iron fittings, wool sails). */
    static final double SHIP_PLANKS = 160, SHIP_IRON = 20, SHIP_WOOL = 30;

    private static MinecraftServer server;
    /** Transient: vessel -> game time of its next trade planning. */
    private static final Map<UUID, Long> NEXT_PLAN = new HashMap<>();

    private EconomyManager() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            server = srv;
            NEXT_PLAN.clear();
            for (TradeRun r : SettlementData.get(srv).runs()) r.dispatching = false;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> server = null);
        ServerTickEvents.END_SERVER_TICK.register(org.webtrade.minecraftportsmod.Perf.timed("EconomyManager", EconomyManager::tick));
    }

    private static void tick(MinecraftServer srv) {
        if (server == null) return;
        SettlementData data = SettlementData.get(srv);
        if (data.all().isEmpty()) return;
        data.dayTicks++;
        if (data.dayTicks >= data.dayLength) {
            data.dayTicks = 0;
            advanceDay(srv, data, 1);
        }
        if (srv.getTickCount() % 20 == 0) {
            tickTrade(srv, data);
            data.changed();
        }
        if (srv.getTickCount() % 200 == 100) introduce(srv, data);
        // the villages players are near show their economy: yard, board, new houses, their people
        if (srv.getTickCount() % 100 == 30) org.webtrade.minecraftportsmod.village.VillageLife.tick(srv);
    }

    /** Does this vessel carry goods right now (drawn as barrels and crates on deck)? */
    public static boolean hasCargo(MinecraftServer srv, UUID vessel) {
        TradeRun run = SettlementData.get(srv).run(vessel);
        return run != null && run.cargoUnits() >= 1;
    }

    public static double price(Settlement s, Good g) {
        return Simulation.price(s, g);
    }

    public static java.util.Map<Good, Double> prices(Settlement s) {
        return Simulation.prices(s);
    }

    public static void setDayLength(MinecraftServer srv, int ticks) {
        SettlementData data = SettlementData.get(srv);
        data.dayLength = Math.max(200, ticks);
        data.dayTicks = Math.min(data.dayTicks, data.dayLength - 1);
        data.changed();
    }

    public static long now(MinecraftServer srv) {
        return srv.overworld().getGameTime();
    }

    // ------------------------------------------------------------------ settlements

    public static Settlement found(MinecraftServer srv, Port port, Specialization spec) {
        return found(srv, port, spec, List.of(), -1);
    }

    /** Houses a new settlement of this kind needs for its first residents. */
    public static int startingHouses(Specialization spec) {
        int pop = 0;
        for (int n : spec.startingWorkforce().values()) pop += n;
        return Simulation.startingHouses(pop);
    }

    /**
     * @param woods  wood species growing around, most first (empty: rolled at random)
     * @param houses houses actually built (-1: as many as needed)
     */
    public static Settlement found(MinecraftServer srv, Port port, Specialization spec, List<String> woods, int houses) {
        SettlementData data = SettlementData.get(srv);
        if (data.get(port.id()) != null) return null;
        Settlement s = new Settlement(port.id(), spec, data.day);
        Simulation.seed(s, RandomSource.create(), woods, houses);
        data.add(s);
        s.log(data.day, Component.translatable("minecraftportsmod.log.founded", port.name(), spec.displayName(), s.population()));
        // news spreads: neighbours on the sea routes learn each other's prices
        for (Settlement other : data.all()) {
            if (other == s || !connected(srv, s, other)) continue;
            s.knowledge.put(other.id(), Settlement.Knowledge.of(other, data.day));
            other.knowledge.put(s.id(), Settlement.Knowledge.of(s, data.day));
        }
        ensureVessels(srv, s);
        Market.updateOrders(s, data.day);
        Minecraftportsmod.LOGGER.info("Settlement {} founded at port {} ({})", port.name(), port.id(), spec.id());
        return s;
    }

    public static void remove(MinecraftServer srv, int id) {
        SettlementData data = SettlementData.get(srv);
        Settlement s = data.get(id);
        if (s == null) return;
        for (UUID v : new ArrayList<>(s.vessels)) {
            data.removeRun(v);
            FleetManager.scrap(srv, v);
        }
        for (TradeRun r : new ArrayList<>(data.runs())) {
            if (r.partner == id && r.phase != TradeRun.Phase.RETURN) {
                r.phase = TradeRun.Phase.RETURN;
                r.departAt = now(srv);
            }
        }
        for (Settlement o : data.all()) o.knowledge.remove(id);
        data.remove(id);
    }

    public static void onPortRemoved(MinecraftServer srv, int portId) {
        remove(srv, portId);
    }

    public static String name(MinecraftServer srv, Settlement s) {
        Port p = PortData.get(srv).port(s.portId());
        return p == null ? "#" + s.id() : p.name();
    }

    /** Can their ships reach each other: a charted sea route, or a rough lane from the world plan. */
    static boolean connected(MinecraftServer srv, Settlement a, Settlement b) {
        return distance(srv, a.portId(), b.portId()) >= 0;
    }

    /** Sailing distance between two ports: the charted route if there is one, else the rough lane; -1 if none. */
    static double distance(MinecraftServer srv, int portA, int portB) {
        Route r = PortData.get(srv).route(portA, portB);
        if (r != null && r.isUsable()) return r.length();
        return WorldPlanner.laneLength(srv, portA, portB);
    }

    private static boolean charted(MinecraftServer srv, int portA, int portB) {
        Route r = PortData.get(srv).route(portA, portB);
        return r != null && r.isUsable();
    }

    /** Neighbours whose waters connected since (a new route or lane) hear of each other. */
    private static void introduce(MinecraftServer srv, SettlementData data) {
        List<Settlement> all = new ArrayList<>(data.all());
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Settlement a = all.get(i), b = all.get(j);
                if (a.knowledge.containsKey(b.id()) && b.knowledge.containsKey(a.id())) continue;
                if (!connected(srv, a, b)) continue;
                a.knowledge.putIfAbsent(b.id(), Settlement.Knowledge.of(b, data.day));
                b.knowledge.putIfAbsent(a.id(), Settlement.Knowledge.of(a, data.day));
                a.log(data.day, Component.translatable("minecraftportsmod.log.news", name(srv, b)));
                b.log(data.day, Component.translatable("minecraftportsmod.log.news", name(srv, a)));
            }
        }
    }

    /** Runs {@code days} economy days right now (the /village step command and the clock). */
    public static void advanceDay(MinecraftServer srv, SettlementData data, int days) {
        for (int i = 0; i < days; i++) {
            data.day++;
            for (Settlement s : new ArrayList<>(data.all())) {
                events(srv, data, s);
                Simulation.Outcome o = Simulation.step(s);
                chronicle(srv, data, s, o);
                Market.updateOrders(s, data.day);
                ensureVessels(srv, s);
                // goodwill fades a little unless it is kept up by trade and help
                s.relations.replaceAll((k, v) -> v * 0.99);
            }
            sendAid(srv, data);
        }
        data.changed();
    }

    // ------------------------------------------------------------------ the world's news

    /** Publishes a piece of news; players near that village hear it at once. */
    static void news(MinecraftServer srv, SettlementData data, Settlement s, Component text) {
        data.news.addFirst(new SettlementData.News(data.day, s.portId(), text));
        while (data.news.size() > SettlementData.NEWS_SIZE) data.news.removeLast();
        Port port = PortData.get(srv).port(s.portId());
        if (port == null) return;
        Component line = Component.literal("⚓ ").withStyle(net.minecraft.ChatFormatting.GOLD)
                .append(text.copy().withStyle(net.minecraft.ChatFormatting.YELLOW));
        for (var p : srv.getPlayerList().getPlayers()) {
            if (p.level().dimension() == port.dimension() && p.blockPosition().distSqr(port.office()) < 220 * 220) p.sendSystemMessage(line);
        }
    }

    // ------------------------------------------------------------------ events

    private static final RandomSource EVENT_RANDOM = RandomSource.create();

    /** Ends events that ran their course and maybe starts one. */
    private static void events(MinecraftServer srv, SettlementData data, Settlement s) {
        s.effects.removeIf(e -> e.until() < data.day);
        if (EVENT_RANDOM.nextDouble() > 0.2) return;  // most days are quiet
        List<EventType> possible = new ArrayList<>();
        for (EventType t : EventType.values()) if (!s.has(t) && t.fits(s)) possible.add(t);
        java.util.Collections.shuffle(possible, new java.util.Random(EVENT_RANDOM.nextLong()));
        for (EventType t : possible) {
            if (EVENT_RANDOM.nextDouble() > t.chance * 5) continue;
            startEvent(srv, s, t);
            return;
        }
    }

    /** Makes an event happen in a settlement now (also used by /village event). */
    public static void startEvent(MinecraftServer srv, Settlement s, EventType t) {
        SettlementData data = SettlementData.get(srv);
        switch (t) {
            case FIRE -> {
                for (Good g : Good.values()) if (g.group == Good.Group.WOOD && s.stock(g) > 0) s.add(g, -s.stock(g) * 0.35);
            }
            case NEW_VEIN -> {
                for (Good g : Simulation.ORES) if (s.deposit(g) > 0) s.deposits.put(g, s.deposit(g) * 1.3);
            }
            default -> {
                s.effects.removeIf(e -> e.type() == t);
                s.effects.add(new Settlement.Effect(t, data.day + t.days));
            }
        }
        Component text = Component.translatable("minecraftportsmod.news." + t.id(), name(srv, s));
        s.log(data.day, text);
        news(srv, data, s, text);
        data.changed();
    }

    // ------------------------------------------------------------------ neighbours

    static void befriend(Settlement a, Settlement b, double amount) {
        a.relations.merge(b.id(), amount, (x, y) -> Math.min(100, x + y));
        b.relations.merge(a.id(), amount, (x, y) -> Math.min(100, x + y));
    }

    /** Trade partners: settlements that trade a lot with each other deal on better terms. */
    static boolean partners(Settlement a, Settlement b) {
        return a.relation(b.id()) >= 30;
    }

    /**
     * Neighbours help each other: a settlement in famine gets food from the best-disposed neighbour that has plenty
     * and a trader at home, sold at half price (or given, if the hungry have no money).
     */
    private static void sendAid(MinecraftServer srv, SettlementData data) {
        long now = now(srv);
        for (Settlement hungry : data.all()) {
            if (hungry.hungryDays < 1) continue;
            boolean helped = false;
            for (TradeRun r : data.runs()) if (r.aid && r.partner == hungry.id()) helped = true;
            if (helped) continue;
            Settlement donor = null;
            VesselRecord ship = null;
            double best = -1;
            for (Settlement d : data.all()) {
                if (d == hungry || d.hungryDays > 0 || !connected(srv, d, hungry) || d.has(EventType.STORM)) continue;
                double spare = 0;
                for (Good g : Simulation.FOOD) spare += Math.max(0, d.stock(g) - Simulation.target(d, g) * 1.2) * g.foodValue();
                if (spare < 40) continue;
                VesselRecord idle = null;
                for (UUID v : d.vessels) {
                    VesselRecord r = FleetManager.record(srv, v);
                    if (r != null && data.run(v) == null && !r.isPlanning() && r.portId() == d.portId()
                            && r.state() != VesselRecord.State.SAILING) idle = r;
                }
                if (idle == null) continue;
                double score = spare * (1 + d.relation(hungry.id()) / 50);
                if (score > best) {
                    best = score;
                    donor = d;
                    ship = idle;
                }
            }
            if (donor == null) continue;
            TradeRun run = new TradeRun(ship.id(), donor.id(), hungry.id());
            run.aid = true;
            run.homePrices.putAll(Simulation.prices(donor));
            double sent = 0;
            double capacity = ship.type().holdSlots * UNITS_PER_SLOT;
            double room = capacity;
            for (Good g : Simulation.FOOD) {
                double spare = Math.floor(Math.max(0, donor.stock(g) - Simulation.target(donor, g) * 1.2));
                double units = Math.min(spare, room);
                if (units < 1) continue;
                donor.add(g, -units);
                run.cargo.put(g, units);
                run.exported.add(g);
                room -= units;
                sent += units;
            }
            if (sent < 1) continue;
            run.departAt = now + PORT_STAY;
            data.putRun(run);
            Component text = Component.translatable("minecraftportsmod.news.aid", name(srv, donor), cargoText(run.cargo), name(srv, hungry));
            donor.log(data.day, text);
            hungry.log(data.day, text);
            news(srv, data, donor, text);
        }
    }

    private static void chronicle(MinecraftServer srv, SettlementData data, Settlement s, Simulation.Outcome o) {
        long day = data.day;
        if (o.houseBuilt) s.log(day, Component.translatable("minecraftportsmod.log.house", s.houses));
        if (o.newcomers > 0) s.log(day, Component.translatable("minecraftportsmod.log.newcomer", s.population()));
        if (o.famineStarted) {
            s.log(day, Component.translatable("minecraftportsmod.log.famine"));
            news(srv, data, s, Component.translatable("minecraftportsmod.news.famine", name(srv, s)));
        }
        if (o.levelUp != null) {
            Component text = Component.translatable("minecraftportsmod.news.level_up", name(srv, s), o.levelUp.displayName());
            s.log(day, text);
            news(srv, data, s, text);
        }
        if (o.levelDown != null) {
            Component text = Component.translatable("minecraftportsmod.news.level_down", name(srv, s), o.levelDown.displayName());
            s.log(day, text);
            news(srv, data, s, text);
        }
        if (o.jobFrom != null) {
            s.log(day, Component.translatable("minecraftportsmod.log.job", o.jobFrom.displayName(), o.jobTo.displayName()));
        }
        for (int i = 0; i < o.emigrants; i++) {
            // the family moves to the happiest well-fed neighbour it can sail to, if any
            Settlement to = data.all().stream()
                    .filter(t -> t != s && t.need("food") >= 0.9 && connected(srv, s, t)
                            && t.population() < t.houses() * Settlement.RESIDENTS_PER_HOUSE)
                    .max(Comparator.comparingDouble(Settlement::happiness)).orElse(null);
            if (to != null) {
                Profession job = Simulation.bestJob(to, Simulation.effectivePrices(to, Simulation.prices(to)));
                to.workers.merge(job, 1, Integer::sum);
                s.log(day, Component.translatable("minecraftportsmod.log.emigrated", name(srv, to)));
                to.log(day, Component.translatable("minecraftportsmod.log.immigrated", name(srv, s), job.displayName()));
                news(srv, data, s, Component.translatable("minecraftportsmod.news.moved", name(srv, s), name(srv, to)));
            } else {
                s.log(day, Component.translatable("minecraftportsmod.log.left"));
            }
        }
    }

    /** Every settlement keeps one trade vessel; a rich, big one buys a second. */
    private static void ensureVessels(MinecraftServer srv, Settlement s) {
        s.vessels.removeIf(id -> FleetManager.record(srv, id) == null);
        Port port = PortData.get(srv).port(s.portId());
        if (port == null) return;
        ServerLevel level = srv.getLevel(port.dimension());
        if (level == null) return;
        for (UUID id : s.vessels) {
            VesselRecord v = FleetManager.record(srv, id);
            if (v != null && v.crew() == null) {
                var skipper = org.webtrade.minecraftportsmod.village.Residents.skipper(level, s, v,
                        org.webtrade.minecraftportsmod.fleet.Names.cyrillic(port.name()));
                if (skipper != null) {
                    v.setCrew(skipper.getUUID());
                    FleetManager.board(level, v, skipper);
                }
            }
        }
        int want = 1;
        boolean canBuild = Simulation.PLANKS.stream().mapToDouble(s::stock).sum() >= SHIP_PLANKS
                && s.stock(Good.IRON) >= SHIP_IRON && s.stock(Good.WOOL) >= SHIP_WOOL;
        if (s.population() >= 20 && s.treasury >= 150 && canBuild) want = 2;
        if (s.vessels.size() >= want) return;
        boolean paid = !s.vessels.isEmpty();
        List<String> taken = new ArrayList<>();
        SettlementData.get(srv).all().forEach(o -> o.vessels.forEach(v -> {
            VesselRecord r = FleetManager.record(srv, v);
            if (r != null) taken.add(r.name());
        }));
        VesselRecord r = FleetManager.spawnSettlementVessel(level, port, s.id(), VesselType.SLOOP, taken);
        if (r == null) return; // no free berth now; try again tomorrow
        s.vessels.add(r.id());
        if (paid) {
            double planks = SHIP_PLANKS;
            for (Good g : Simulation.PLANKS) {
                double t = Math.min(planks, s.stock(g));
                s.add(g, -t);
                planks -= t;
            }
            s.add(Good.IRON, -SHIP_IRON);
            s.add(Good.WOOL, -SHIP_WOOL);
            s.log(SettlementData.get(srv).day, Component.translatable("minecraftportsmod.log.vessel_bought", r.name()));
        }
    }

    // ------------------------------------------------------------------ trade

    private static void tickTrade(MinecraftServer srv, SettlementData data) {
        long now = now(srv);
        // trips in progress
        for (TradeRun run : new ArrayList<>(data.runs())) {
            VesselRecord r = FleetManager.record(srv, run.vessel);
            Settlement home = data.get(run.home);
            if (r == null || home == null) {
                lost(srv, data, run);
                continue;
            }
            if (run.dispatching || r.isPlanning()) continue;
            switch (run.phase) {
                case LOADING -> {
                    if (home.has(EventType.STORM)) break;
                    if (now >= run.departAt && crewAboard(srv, r, now - run.departAt)) {
                        depart(srv, data, run, r, run.partner, TradeRun.Phase.OUTBOUND);
                    }
                }
                case TRADING -> {
                    Settlement here = data.get(run.partner);
                    if (here != null && here.has(EventType.STORM)) break;
                    if (now >= run.departAt && crewAboard(srv, r, now - run.departAt)) {
                        depart(srv, data, run, r, run.home, TradeRun.Phase.RETURN);
                    }
                }
                case OUTBOUND, RETURN -> {
                    // not sailing any more but not where it should be (a lost voyage): head on again
                    if (r.state() != VesselRecord.State.SAILING && now >= run.retryAt) {
                        int dest = run.phase == TradeRun.Phase.OUTBOUND ? run.partner : run.home;
                        if (r.portId() == dest) onVesselArrived(srv, r);
                        else depart(srv, data, run, r, dest, run.phase);
                    }
                }
            }
        }
        // idle traders look for a deal
        for (Settlement s : data.all()) {
            for (UUID v : s.vessels) {
                if (data.run(v) != null || now < NEXT_PLAN.getOrDefault(v, 0L)) continue;
                VesselRecord r = FleetManager.record(srv, v);
                if (r == null || r.isPlanning()) continue;
                NEXT_PLAN.put(v, now + IDLE_RECHECK);
                boolean atHome = (r.state() == VesselRecord.State.MOORED || r.state() == VesselRecord.State.ANCHORED)
                        && r.portId() == s.portId();
                if (!atHome) {
                    Port home = PortData.get(srv).port(s.portId());
                    ServerLevel level = srv.getLevel(r.dimension());
                    if (home != null && level != null && r.state() != VesselRecord.State.SAILING) {
                        if (r.portId() >= 0 && !charted(srv, r.portId(), home.id())) {
                            FleetManager.dispatchRough(level, r, home, WorldPlanner.lanePath(srv, r.portId(), home.id()));
                        } else {
                            FleetManager.dispatch(level, r, home, ok -> {
                            });
                        }
                    }
                    continue;
                }
                plan(srv, data, s, r);
            }
        }
    }

    /**
     * Is the skipper aboard (or nowhere to be seen, i.e. travelling with the vessel)? After waiting too long, the
     * skipper is called aboard at once.
     */
    private static boolean crewAboard(MinecraftServer srv, VesselRecord r, long waited) {
        if (r.crew() == null) return true;
        ServerLevel level = srv.getLevel(r.dimension());
        if (level == null) return true;
        net.minecraft.world.entity.Entity crew = level.getEntity(r.crew());
        if (crew == null || !crew.isAlive()) return true;
        var body = FleetManager.live(r.id());
        if (body != null && crew.getVehicle() == body) return true;
        if (waited < CREW_WAIT) return false;
        FleetManager.board(level, r, crew);
        return true;
    }

    private static void depart(MinecraftServer srv, SettlementData data, TradeRun run, VesselRecord r, int destId,
                               TradeRun.Phase phase) {
        Port dest = PortData.get(srv).port(destId);
        ServerLevel level = srv.getLevel(r.dimension());
        if (dest == null || level == null) {
            lost(srv, data, run);
            return;
        }
        int from = r.portId();
        if (from >= 0 && !charted(srv, from, destId)) {
            // no charted route yet: sail the rough lane of the world plan
            boolean ok = FleetManager.dispatchRough(level, r, dest, WorldPlanner.lanePath(srv, from, destId));
            if (ok) run.phase = phase;
            else departFailed(srv, data, run, phase);
            data.changed();
            return;
        }
        run.dispatching = true;
        FleetManager.dispatch(level, r, dest, ok -> {
            run.dispatching = false;
            if (ok) run.phase = phase;
            else departFailed(srv, data, run, phase);
            data.changed();
        });
    }

    private static void departFailed(MinecraftServer srv, SettlementData data, TradeRun run, TradeRun.Phase phase) {
        if (phase == TradeRun.Phase.OUTBOUND) {
            // can't get there: unload again and think of something else later
            Settlement home = data.get(run.home);
            if (home != null) {
                run.cargo.forEach(home::add);
                home.treasury += run.purse;
            }
            data.removeRun(run.vessel);
            NEXT_PLAN.put(run.vessel, now(srv) + IDLE_RECHECK * 2);
        } else {
            run.retryAt = now(srv) + IDLE_RECHECK;
        }
    }

    /** The vessel or its home vanished: the trip is over, its goods are gone. */
    private static void lost(MinecraftServer srv, SettlementData data, TradeRun run) {
        data.removeRun(run.vessel);
        Settlement home = data.get(run.home);
        if (home != null) {
            home.log(data.day, Component.translatable("minecraftportsmod.log.lost", cargoText(run.cargo)));
        }
    }

    /** The price gap a merchant needs to bother: partners trust each other and trade on thinner margins. */
    static double margin(Settlement a, Settlement b) {
        return partners(a, b) ? 1.1 : MARGIN;
    }

    /** Picks the most promising partner for an idle trader at home and loads it. */
    private static void plan(MinecraftServer srv, SettlementData data, Settlement a, VesselRecord vessel) {
        PortData ports = PortData.get(srv);
        Map<Good, Double> pA = Simulation.prices(a);
        double capacity = vessel.type().holdSlots * UNITS_PER_SLOT;

        Settlement bestB = null;
        Map<Good, Double> bestCargo = null;
        double bestScore = MIN_PROFIT;
        for (Settlement b : data.all()) {
            if (b == a) continue;
            Settlement.Knowledge know = a.knowledge.get(b.id());
            double dist = distance(srv, a.portId(), b.portId());
            if (know == null || dist < 0) continue;
            double freight = dist * 2 * FREIGHT_PER_BLOCK;

            // what we could sell there
            List<Good> exports = new ArrayList<>();
            for (Good g : Good.values()) {
                double surplus = a.stock(g) - Simulation.target(a, g);
                if (surplus >= 1 && know.prices().getOrDefault(g, 0.0) > pA.get(g) * margin(a, b)) exports.add(g);
            }
            exports.sort(Comparator.comparingDouble(g -> -(know.prices().get(g) - pA.get(g))));
            Map<Good, Double> cargo = new EnumMap<>(Good.class);
            double room = capacity, exportGain = 0;
            for (Good g : exports) {
                if (room < 1) break;
                // only as much as they were short of (old knowledge without that: half a hold at most)
                double theyWant = know.wants().isEmpty() ? capacity * 0.5 : know.wants().getOrDefault(g, 0.0);
                double units = Math.min(Math.min(a.stock(g) - Simulation.target(a, g), Math.min(capacity * 0.5, theyWant)), room);
                units = Math.floor(units);
                if (units < 1) continue;
                cargo.put(g, units);
                room -= units;
                exportGain += units * (know.prices().get(g) - pA.get(g)) * 0.5; // prices move as we sell
            }
            // what we could bring back
            double importGain = 0;
            for (Good g : Good.values()) {
                double shortage = Simulation.target(a, g) - a.stock(g);
                double there = know.prices().getOrDefault(g, Double.MAX_VALUE);
                if (shortage >= 1 && pA.get(g) > there * MARGIN) {
                    importGain += Math.min(shortage, capacity * 0.5) * (pA.get(g) - there) * 0.5;
                }
            }
            double score = exportGain + importGain - freight;
            if (score > bestScore) {
                bestScore = score;
                bestB = b;
                bestCargo = cargo;
            }
        }
        if (bestB == null) return;

        TradeRun run = new TradeRun(vessel.id(), a.id(), bestB.id());
        run.homePrices.putAll(pA);
        bestCargo.forEach((g, u) -> {
            a.add(g, -u);
            run.cargo.put(g, u);
            run.exported.add(g);
        });
        run.purse = Math.min(PURSE_MAX, Math.max(0, a.treasury * PURSE_SHARE));
        a.treasury -= run.purse;
        run.departAt = now(srv) + PORT_STAY;
        data.putRun(run);
        a.log(data.day, Component.translatable("minecraftportsmod.log.departs", vessel.name(), name(srv, bestB),
                run.cargo.isEmpty() ? Component.translatable("minecraftportsmod.log.ballast") : cargoText(run.cargo)));
    }

    /** Called by the fleet when a settlement's vessel ties up (or anchors) somewhere. */
    public static void onVesselArrived(MinecraftServer srv, VesselRecord r) {
        SettlementData data = SettlementData.get(srv);
        TradeRun run = data.run(r.id());
        if (run == null) return;
        long now = now(srv);
        if (run.phase == TradeRun.Phase.OUTBOUND && r.portId() == run.partner) {
            Settlement a = data.get(run.home), b = data.get(run.partner);
            if (a == null || b == null) {
                run.phase = TradeRun.Phase.TRADING;
                run.departAt = now;
                return;
            }
            if (run.aid) handOver(srv, data, run, a, b);
            else tradeAt(srv, data, run, r, a, b);
            run.phase = TradeRun.Phase.TRADING;
            run.departAt = now + PORT_STAY;
        } else if (run.phase == TradeRun.Phase.RETURN && r.portId() == run.home) {
            Settlement a = data.get(run.home);
            if (a != null) {
                run.cargo.forEach(a::add);
                a.treasury += run.purse;
                if (!run.partnerPrices.isEmpty()) {
                    a.knowledge.put(run.partner, new Settlement.Knowledge(run.partner, data.day, new EnumMap<>(run.partnerPrices),
                            new EnumMap<>(run.partnerWants)));
                }
                Settlement b = data.get(run.partner);
                a.log(data.day, Component.translatable("minecraftportsmod.log.returned", r.name(),
                        b == null ? "?" : name(srv, b), String.format(java.util.Locale.ROOT, "%.1f", run.soldFor),
                        run.cargo.isEmpty() ? Component.translatable("minecraftportsmod.log.nothing") : cargoText(run.cargo)));
            }
            data.removeRun(run.vessel);
            NEXT_PLAN.put(run.vessel, now + PORT_STAY);
        }
        data.changed();
    }

    /** Food for a starving neighbour: all of it stays, paid at half price if they can afford it. */
    private static void handOver(MinecraftServer srv, SettlementData data, TradeRun run, Settlement donor, Settlement hungry) {
        double worth = 0;
        for (var e : run.cargo.entrySet()) {
            hungry.add(e.getKey(), e.getValue());
            worth += e.getValue() * run.homePrices.getOrDefault(e.getKey(), e.getKey().basePrice);
        }
        double pay = Math.min(worth * 0.5, Math.max(0, hungry.treasury * 0.5));
        hungry.treasury -= pay;
        run.purse += pay;
        run.soldFor += pay;
        Component text = Component.translatable("minecraftportsmod.log.aid_arrived", cargoText(run.cargo), name(srv, donor));
        hungry.log(data.day, text);
        run.cargo.clear();
        befriend(donor, hungry, 20);
        run.partnerPrices.clear();
        run.partnerPrices.putAll(Simulation.prices(hungry));
    }

    /** Sell the outbound cargo at the partner, then buy what is cheap there and dear at home. */
    private static void tradeAt(MinecraftServer srv, SettlementData data, TradeRun run, VesselRecord vessel,
                                Settlement a, Settlement b) {
        Map<Good, Double> sold = new EnumMap<>(Good.class);
        for (Good g : new ArrayList<>(run.cargo.keySet())) {
            double units = run.cargo.get(g);
            double floor = run.homePrices.getOrDefault(g, g.basePrice) * 1.05;
            double batch = Math.max(1, units / 8);
            while (units >= 0.5) {
                double p = Simulation.price(b, g);
                if (p < floor) break;
                double n = Math.min(Math.min(batch, units), Simulation.want(b, g));
                if (b.treasury < n * p) n = Math.floor(b.treasury / p);
                if (n < 0.5) break;
                b.add(g, n);
                b.treasury -= n * p;
                run.purse += n * p;
                run.soldFor += n * p;
                units -= n;
                sold.merge(g, n, Double::sum);
            }
            if (units < 0.5) run.cargo.remove(g);
            else run.cargo.put(g, units);
        }

        double capacity = vessel.type().holdSlots * UNITS_PER_SLOT;
        double room = capacity - run.cargoUnits();
        Map<Good, Double> bought = new EnumMap<>(Good.class);
        List<Good> wanted = new ArrayList<>(List.of(Good.values()));
        wanted.sort(Comparator.comparingDouble(g -> -run.homePrices.getOrDefault(g, 0.0) / Simulation.price(b, g)));
        for (Good g : wanted) {
            // never carry home what home makes more of than it uses (that would only be bought back cheaply)
            if (a.produced(g) > a.consumed(g) || run.exported.contains(g)) continue;
            double ceiling = run.homePrices.getOrDefault(g, 0.0) / MARGIN;
            double want = Math.min(Simulation.target(a, g) - a.stock(g) - run.cargo.getOrDefault(g, 0.0), capacity * 0.5);
            double batch = Math.max(1, want / 8);
            while (want >= 1 && room >= 1) {
                double p = Simulation.price(b, g);
                if (p > ceiling || b.stock(g) < 1) break;
                double n = Math.min(Math.min(batch, want), Math.min(room, b.stock(g)));
                if (run.purse < n * p) n = Math.floor(run.purse / p);
                if (n < 1) break;
                b.add(g, -n);
                b.treasury += n * p;
                run.purse -= n * p;
                run.cargo.merge(g, n, Double::sum);
                bought.merge(g, n, Double::sum);
                want -= n;
                room -= n;
            }
        }
        run.partnerPrices.clear();
        run.partnerPrices.putAll(Simulation.prices(b));
        run.partnerWants.clear();
        run.partnerWants.putAll(Simulation.wants(b));
        boolean wereFriends = partners(a, b);
        befriend(a, b, 2 + Math.min(8, run.soldFor / 4));
        if (!wereFriends && partners(a, b)) {
            Component text = Component.translatable("minecraftportsmod.news.partners", name(srv, a), name(srv, b));
            a.log(data.day, text);
            b.log(data.day, text);
            news(srv, data, a, text);
        }
        b.knowledge.put(a.id(), Settlement.Knowledge.of(a, data.day));
        b.log(data.day, Component.translatable("minecraftportsmod.log.visit", vessel.name(), name(srv, a),
                sold.isEmpty() ? Component.translatable("minecraftportsmod.log.nothing") : cargoText(sold),
                bought.isEmpty() ? Component.translatable("minecraftportsmod.log.nothing") : cargoText(bought)));
    }

    /** "120 Oak Planks, 40 Cobblestone, …" */
    static Component cargoText(Map<Good, Double> goods) {
        List<Map.Entry<Good, Double>> list = new ArrayList<>(goods.entrySet());
        list.sort(Comparator.comparingDouble(e -> -e.getValue() * e.getKey().basePrice));
        MutableComponent c = Component.empty();
        int shown = 0;
        for (var e : list) {
            if (e.getValue() < 0.5) continue;
            if (shown == 4) {
                c.append(", …");
                break;
            }
            if (shown > 0) c.append(", ");
            c.append(Math.round(e.getValue()) + " ").append(e.getKey().displayName());
            shown++;
        }
        return c;
    }
}
