package org.webtrade.minecraftportsmod.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.ToDoubleFunction;

/**
 * One economy day of one settlement: work, eat, burn, build, grow, reprice, change jobs. Pure data, server thread.
 *
 * <p>Prices follow scarcity: {@code base × (target / (stock + 1))^0.6}, where the target is a week of yesterday's
 * demand plus building reserves. Demand counts what was consumed and what was wanted but missing, so a shortage
 * raises the price of every substitute of the missing good, and a glut of something nobody here uses sinks to a
 * quarter of its base price. Workers drift, one a day, towards the job that earns most — valued at the better of
 * the local price and what merchants know the good fetches elsewhere. That is why a forest village keeps felling
 * trees even when its own yards are full of logs: it knows they sell well across the sea.
 */
final class Simulation {

    static final double ELASTICITY = 0.6;
    static final double MIN_PRICE = 0.25, MAX_PRICE = 4.0;
    static final int TARGET_DAYS = 7;

    static final double FOOD_PER_RESIDENT = 1.0;
    static final double FUEL_PER_RESIDENT = 0.3;
    static final double TOOLS_PER_WORKER = 0.03;
    static final double UPKEEP_PLANKS = 0.15, UPKEEP_STONE = 0.1;
    static final double HOUSE_PLANKS = 32, HOUSE_STONE = 16, HOUSE_BRICKS = 8, HOUSE_GLASS = 4;
    /** Share of the price a good fetches elsewhere that counts when choosing jobs (freight, risk). */
    static final double EXPORT_PARITY = 0.8;

    /**
     * Needs by tier (per resident per day). Survival — food and fuel — is counted apart. Everyday needs are wanted
     * everywhere; prosperity is what a village that has made it wants next. Rising a level asks for the tier.
     */
    static final Map<Good, Double> EVERYDAY = new EnumMap<>(Good.class);
    static final Map<Good, Double> PROSPERITY = new EnumMap<>(Good.class);

    static {
        EVERYDAY.put(Good.WOOL, 0.06);
        EVERYDAY.put(Good.POTS, 0.03);
        PROSPERITY.put(Good.GLASS, 0.03);
        PROSPERITY.put(Good.TERRACOTTA, 0.03);
        PROSPERITY.put(Good.BRICKS, 0.04);
        PROSPERITY.put(Good.COPPER, 0.015);
        PROSPERITY.put(Good.GOLD, 0.006);
    }

    /** Days in a row a settlement must qualify before it rises a level, and fail before it falls. */
    static final int RISE_DAYS = 3, FALL_DAYS = 6;

    static final List<Good> LOGS = new ArrayList<>(), PLANKS = new ArrayList<>(), FOOD = new ArrayList<>(),
            FUEL = new ArrayList<>(), BUILD_STONE = List.of(Good.STONE, Good.GRANITE, Good.DIORITE, Good.ANDESITE),
            ORES = List.of(Good.COAL, Good.IRON_ORE, Good.COPPER_ORE, Good.GOLD_ORE),
            SMELTABLE = List.of(Good.IRON_ORE, Good.COPPER_ORE, Good.GOLD_ORE),
            QUARRIED = List.of(Good.STONE, Good.GRANITE, Good.DIORITE, Good.ANDESITE, Good.SAND);

    static {
        for (Good g : Good.values()) {
            if (g.isLog()) LOGS.add(g);
            else if (g.group == Good.Group.WOOD) PLANKS.add(g);
            if (g.foodValue() > 0) FOOD.add(g);
            if (g.fuelValue() > 0) FUEL.add(g);
        }
    }

    private Simulation() {
    }

    // ------------------------------------------------------------------ prices

    /** Stock the settlement wants to hold of a good. */
    static double target(Settlement s, Good g) {
        return TARGET_DAYS * s.demand(g) + reserve(s, g);
    }

    /** Building reserve: kept while the settlement needs a new house. */
    static double reserve(Settlement s, Good g) {
        if (!needsHouse(s)) return 0;
        if (g.group == Good.Group.WOOD && !g.isLog()) return HOUSE_PLANKS / PLANKS.size() * 2;
        if (BUILD_STONE.contains(g)) return HOUSE_STONE / BUILD_STONE.size() * 2;
        if (g == Good.BRICKS) return HOUSE_BRICKS;
        if (g == Good.GLASS) return HOUSE_GLASS;
        return 0;
    }

    static double priceMultiplier(double target, double stock) {
        double m = Math.pow((target + 1) / (stock + 1), ELASTICITY);
        return Math.max(MIN_PRICE, Math.min(MAX_PRICE, m));
    }

    /** Local price in emeralds per unit. */
    static double price(Settlement s, Good g) {
        return g.basePrice * priceMultiplier(target(s, g), s.stock(g));
    }

    static Map<Good, Double> prices(Settlement s) {
        Map<Good, Double> p = new EnumMap<>(Good.class);
        for (Good g : Good.values()) p.put(g, price(s, g));
        return p;
    }

    /**
     * What a good is worth to a producer here: the local price, or what it fetches in a known market — unless the
     * yards are already full of it (more than 10 days of output waiting), then the far market stops counting:
     * the merchants obviously can't ship it away fast enough.
     */
    static Map<Good, Double> effectivePrices(Settlement s, Map<Good, Double> local) {
        Map<Good, Double> e = new EnumMap<>(local);
        for (Settlement.Knowledge k : s.knowledge.values()) {
            k.prices().forEach((g, p) -> {
                double glutDays = s.stock(g) / Math.max(1, s.produced(g));
                double f = Math.max(0, Math.min(1, (20 - glutDays) / 10));
                e.merge(g, p * EXPORT_PARITY * f, Math::max);
            });
        }
        return e;
    }

    /** How much of each good the settlement would gladly take in: up to one and a half of its target stock. */
    static double want(Settlement s, Good g) {
        return Math.max(0, target(s, g) * 1.5 - s.stock(g));
    }

    static Map<Good, Double> wants(Settlement s) {
        Map<Good, Double> w = new EnumMap<>(Good.class);
        for (Good g : Good.values()) {
            double v = want(s, g);
            if (v >= 1) w.put(g, v);
        }
        return w;
    }

    static int startingHouses(int population) {
        return (population + Settlement.RESIDENTS_PER_HOUSE - 1) / Settlement.RESIDENTS_PER_HOUSE + 1;
    }

    static boolean needsHouse(Settlement s) {
        return s.population() + 1 >= s.houses * Settlement.RESIDENTS_PER_HOUSE && s.houses < s.level.maxHouses;
    }

    /** Can a settlement with these satisfactions hold (or rise to) a level? */
    static boolean meets(Settlement.Level level, double survival, double everyday, double prosperity) {
        return switch (level) {
            case HAMLET -> true;
            case VILLAGE -> survival >= 0.9;
            case TOWN -> survival >= 0.9 && everyday >= 0.75;
            case CITY -> survival >= 0.9 && everyday >= 0.8 && prosperity >= 0.6;
        };
    }

    /** Output multiplier the settlement's events put on a good. */
    static double eventMultiplier(Settlement s, Good g) {
        double m = 1;
        for (Settlement.Effect e : s.effects) m *= e.type().production(g);
        return m;
    }

    // ------------------------------------------------------------------ the day

    /** What happened today, for the settlement's chronicle and the economy manager. */
    static final class Outcome {
        int newcomers;
        int emigrants;
        boolean houseBuilt;
        boolean famineStarted;
        Settlement.Level levelUp, levelDown;
        Profession jobFrom, jobTo;
    }

    private static final class Day {
        final Settlement s;
        final Map<Good, Double> price;
        final Map<Good, Double> produced = new EnumMap<>(Good.class);
        final Map<Good, Double> consumed = new EnumMap<>(Good.class);
        final Map<Good, Double> demand = new EnumMap<>(Good.class);

        Day(Settlement s) {
            this.s = s;
            this.price = prices(s);
        }

        void produce(Good g, double amount) {
            amount *= eventMultiplier(s, g);
            if (amount <= 0) return;
            s.add(g, amount);
            produced.merge(g, amount, Double::sum);
        }

        double available(List<Good> goods) {
            double n = 0;
            for (Good g : goods) n += s.stock(g);
            return n;
        }

        /**
         * Takes up to {@code amount} (in {@code measure} units, e.g. food value) from the goods, in the given order;
         * what is taken counts as consumed and as demand. Returns the amount taken, in measure units.
         */
        double take(List<Good> goods, double amount, ToDoubleFunction<Good> measure, Comparator<Good> order,
                    BiConsumer<Good, Double> taken) {
            if (amount <= 1e-9) return 0;
            List<Good> sorted = new ArrayList<>(goods);
            sorted.sort(order);
            double left = amount;
            for (Good g : sorted) {
                double have = s.stock(g);
                if (have <= 1e-9) continue;
                double m = measure.applyAsDouble(g);
                double units = Math.min(have, left / m);
                s.add(g, -units);
                consumed.merge(g, units, Double::sum);
                demand.merge(g, units, Double::sum);
                if (taken != null) taken.accept(g, units);
                left -= units * m;
                if (left <= 1e-9) break;
            }
            return amount - Math.max(0, left);
        }

        double take(List<Good> goods, double amount, ToDoubleFunction<Good> measure) {
            return take(goods, amount, measure, cheapestPer(measure), null);
        }

        /** Records demand that could not be met, spread over the substitutes. */
        void want(List<Good> goods, double amount, ToDoubleFunction<Good> measure) {
            if (amount <= 1e-9 || goods.isEmpty()) return;
            for (Good g : goods) demand.merge(g, amount / goods.size() / measure.applyAsDouble(g), Double::sum);
        }

        Comparator<Good> cheapestPer(ToDoubleFunction<Good> measure) {
            return Comparator.comparingDouble(g -> price.get(g) / measure.applyAsDouble(g));
        }
    }

    private static final ToDoubleFunction<Good> UNIT = g -> 1.0;

    static Outcome step(Settlement s) {
        return step(s, true);
    }

    /** @param people whether residents may arrive, leave and change jobs (not during the founding warm-up) */
    static Outcome step(Settlement s, boolean people) {
        Day d = new Day(s);
        Outcome out = new Outcome();
        int pop = s.population();

        double tools = s.need("tools"), food = s.need("food"), everydayYesterday = s.need("everyday");
        double productivity = (0.6 + 0.4 * tools) * (0.75 + 0.25 * s.happiness) * (food < 0.5 ? 0.7 : 1.0)
                * (0.9 + 0.1 * everydayYesterday);

        work(d, productivity);

        // --- needs
        double foodGot = d.take(FOOD, pop * FOOD_PER_RESIDENT, Good::foodValue);
        d.want(FOOD, pop * FOOD_PER_RESIDENT - foodGot, Good::foodValue);
        double foodSat = pop == 0 ? 1 : foodGot / (pop * FOOD_PER_RESIDENT);

        double fuelGot = d.take(FUEL, pop * FUEL_PER_RESIDENT, Good::fuelValue);
        d.want(FUEL, pop * FUEL_PER_RESIDENT - fuelGot, Good::fuelValue);
        double fuelSat = pop == 0 ? 1 : fuelGot / (pop * FUEL_PER_RESIDENT);

        int labour = pop - s.workers(Profession.MERCHANT);
        double toolsNeed = labour * TOOLS_PER_WORKER;
        double toolsGot = d.take(List.of(Good.TOOLS), toolsNeed, UNIT);
        d.want(List.of(Good.TOOLS), toolsNeed - toolsGot, UNIT);
        double toolsSat = toolsNeed <= 0 ? 1 : toolsGot / toolsNeed;

        // a fair makes everybody want nice things
        double festive = s.has(EventType.FAIR) ? 3 : 1;
        double everydaySat = tier(d, EVERYDAY, pop * festive, toolsSat);
        // prosperity only matters to a village that has made it past the hamlet
        double prosperitySat = s.level.ordinal() >= Settlement.Level.VILLAGE.ordinal() ? tier(d, PROSPERITY, pop * festive, -1) : 0;
        double luxSat = everydaySat;

        // --- houses: upkeep and building
        double pl = d.take(PLANKS, s.houses * UPKEEP_PLANKS, UNIT);
        d.want(PLANKS, s.houses * UPKEEP_PLANKS - pl, UNIT);
        double st = d.take(BUILD_STONE, s.houses * UPKEEP_STONE, UNIT);
        d.want(BUILD_STONE, s.houses * UPKEEP_STONE - st, UNIT);
        if (needsHouse(s)) {
            double bricks = Math.min(HOUSE_BRICKS, s.stock(Good.BRICKS));
            double stoneNeeded = HOUSE_STONE + (HOUSE_BRICKS - bricks);
            if (d.available(PLANKS) >= HOUSE_PLANKS && d.available(BUILD_STONE) >= stoneNeeded) {
                d.take(PLANKS, HOUSE_PLANKS, UNIT);
                d.take(BUILD_STONE, stoneNeeded, UNIT);
                d.take(List.of(Good.BRICKS), bricks, UNIT);
                d.take(List.of(Good.GLASS), Math.min(HOUSE_GLASS, s.stock(Good.GLASS)), UNIT);
                s.houses++;
                out.houseBuilt = true;
            }
        }
        double housingSat = pop == 0 ? 1 : Math.min(1, (double) s.houses * Settlement.RESIDENTS_PER_HOUSE / pop);

        // --- spoilage
        for (Good g : Good.values()) {
            double rate = g.spoilage();
            if (rate > 0 && s.stock(g) > 0) s.add(g, -s.stock(g) * rate);
        }

        // --- mood, growth, hunger
        double survivalSat = 0.7 * foodSat + 0.3 * fuelSat;
        boolean aspiring = s.level.ordinal() >= Settlement.Level.VILLAGE.ordinal();
        double moodTarget = 0.4 * survivalSat + 0.15 * housingSat + 0.2 * everydaySat + 0.15 * (aspiring ? prosperitySat : 1)
                + 0.1 * toolsSat + (s.has(EventType.FAIR) ? 0.15 : 0) - (s.has(EventType.SICKNESS) ? 0.1 : 0);
        s.happiness = Math.max(0, Math.min(1, 0.7 * s.happiness + 0.3 * moodTarget));

        if (foodSat >= 0.95 && s.happiness >= 0.55 && pop < s.houses * Settlement.RESIDENTS_PER_HOUSE) {
            s.growth += (0.2 + 0.3 * (s.happiness - 0.55) / 0.45) * (1 + 0.5 * everydaySat);
        }

        // --- rank: rise when the next level's needs are met for some days, fall when even survival fails
        Settlement.Level next = s.level.next();
        if (next != s.level && pop >= next.population && meets(next, survivalSat, everydaySat, prosperitySat)) {
            s.levelDays = Math.max(1, s.levelDays + 1);
            if (s.levelDays >= RISE_DAYS) {
                s.level = next;
                s.levelDays = 0;
                out.levelUp = next;
            }
        } else if (s.level != Settlement.Level.HAMLET && !meets(s.level, survivalSat * 1.2, everydaySat * 1.3, prosperitySat * 1.5)) {
            s.levelDays = Math.min(-1, s.levelDays - 1);
            if (s.levelDays <= -FALL_DAYS) {
                s.level = Settlement.Level.values()[s.level.ordinal() - 1];
                s.levelDays = 0;
                out.levelDown = s.level;
            }
        } else {
            s.levelDays = 0;
        }
        if (foodSat < 0.7) {
            s.hungryDays++;
            if (s.hungryDays == 1) out.famineStarted = true;
        } else {
            s.hungryDays = 0;
        }

        s.needs.put("food", foodSat);
        s.needs.put("fuel", fuelSat);
        s.needs.put("tools", toolsSat);
        s.needs.put("luxury", luxSat);
        s.needs.put("survival", survivalSat);
        s.needs.put("everyday", everydaySat);
        s.needs.put("prosperity", aspiring ? prosperitySat : 0);
        s.needs.put("housing", housingSat);
        s.needs.put("productivity", productivity);

        s.produced.clear();
        s.produced.putAll(d.produced);
        s.consumed.clear();
        s.consumed.putAll(d.consumed);
        // demand is smoothed so one bad day doesn't whip prices around
        Map<Good, Double> newDemand = new EnumMap<>(Good.class);
        for (Good g : Good.values()) {
            double v = 0.6 * s.demand(g) + 0.4 * d.demand.getOrDefault(g, 0.0);
            if (v > 1e-4) newDemand.put(g, v);
        }
        s.demand.clear();
        s.demand.putAll(newDemand);

        // --- people come and go
        for (Good g : Good.values()) s.recordPrice(g, price(s, g));
        if (!people) return out;
        Map<Good, Double> eff = effectivePrices(s, prices(s));
        if (s.growth >= 1) {
            s.growth -= 1;
            Profession best = bestJob(s, eff);
            s.workers.merge(best, 1, Integer::sum);
            out.newcomers++;
        }
        if (s.hungryDays >= 2 && pop > 3) {
            Profession worst = worstJob(s, eff);
            if (worst != null) {
                removeWorker(s, worst);
                out.emigrants++;
            }
        }
        rebalanceMerchants(s, eff);
        reallocate(s, eff, out);
        return out;
    }

    /**
     * Consumes one tier of needs and returns how well it was met (by value). {@code extra} (if not negative) is
     * another satisfaction counted in with the same weight as one good (tools, for the everyday tier).
     */
    private static double tier(Day d, Map<Good, Double> needs, double people, double extra) {
        double wanted = 0, got = 0;
        for (var e : needs.entrySet()) {
            double need = people * e.getValue();
            double have = d.take(List.of(e.getKey()), need, UNIT);
            d.want(List.of(e.getKey()), need - have, UNIT);
            wanted += need * e.getKey().basePrice;
            got += have * e.getKey().basePrice;
        }
        double sat = wanted <= 0 ? 1 : got / wanted;
        if (extra >= 0) sat = (sat * needs.size() + extra) / (needs.size() + 1);
        return sat;
    }

    // ------------------------------------------------------------------ work

    private static void work(Day d, double productivity) {
        Settlement s = d.s;
        Map<Good, Double> e = effectivePrices(s, d.price);

        // extractors
        extract(d, e, LOGS, s.workers(Profession.WOODCUTTER) * 16 * productivity);
        int miners = s.workers(Profession.MINER);
        extract(d, e, ORES, miners * 12 * productivity);
        d.produce(Good.STONE, miners * 3 * productivity);
        extract(d, e, QUARRIED, s.workers(Profession.MASON) * 12 * productivity);
        d.produce(Good.WHEAT, s.workers(Profession.FARMER) * 14 * productivity * s.deposit(Good.WHEAT));
        d.produce(Good.FISH, s.workers(Profession.FISHER) * 10 * productivity * s.deposit(Good.FISH));
        double pasture = s.deposit(Good.WOOL);
        int shepherds = s.workers(Profession.SHEPHERD);
        d.produce(Good.WOOL, shepherds * 4 * productivity * pasture);
        d.produce(Good.MUTTON, shepherds * 2 * productivity * pasture);

        // crafters
        craft(d, LOGS, s.workers(Profession.SAWYER) * 12 * productivity, 0,
                byMargin(d, e, g -> 4 * e.get(g.planks())), (g, a) -> d.produce(g.planks(), a * 4));
        craft(d, SMELTABLE, s.workers(Profession.SMELTER) * 4 * productivity, 0.5,
                byMargin(d, e, g -> e.get(ingot(g))), (g, a) -> d.produce(ingot(g), a));
        smith(d, s.workers(Profession.SMITH) * 2 * productivity);
        potter(d, e, s.workers(Profession.POTTER) * 8 * productivity);
        craft(d, List.of(Good.WHEAT), s.workers(Profession.BAKER) * 9 * productivity, 0.055,
                d.cheapestPer(UNIT), (g, a) -> d.produce(Good.BREAD, a * 6 / 9));
    }

    /** Workers split their day between the goods of their deposits, favouring what pays. */
    private static void extract(Day d, Map<Good, Double> e, List<Good> goods, double effort) {
        if (effort <= 0) return;
        double total = 0;
        for (Good g : goods) total += weight(d.s, e, g);
        if (total <= 0) return;
        for (Good g : goods) {
            double w = weight(d.s, e, g);
            if (w > 0) d.produce(g, effort * (w / total) * d.s.deposit(g));
        }
    }

    private static double weight(Settlement s, Map<Good, Double> e, Good g) {
        double rich = s.deposit(g);
        return rich <= 0 ? 0 : rich * e.get(g);
    }

    private static void craft(Day d, List<Good> inputs, double capacity, double fuelPerUnit, Comparator<Good> order,
                              BiConsumer<Good, Double> output) {
        if (capacity <= 0) return;
        double run = Math.min(capacity, d.available(inputs));
        if (fuelPerUnit > 0 && run > 0) {
            double fuel = d.take(FUEL, run * fuelPerUnit, Good::fuelValue);
            d.want(FUEL, run * fuelPerUnit - fuel, Good::fuelValue);
            run = Math.min(run, fuel / fuelPerUnit);
        }
        d.take(inputs, run, UNIT, order, output);
        d.want(inputs, capacity - run, UNIT);
    }

    private static void smith(Day d, double capacity) {
        if (capacity <= 0) return;
        double k = Math.min(capacity, Math.min(d.s.stock(Good.IRON) / 3, d.available(PLANKS)));
        d.take(List.of(Good.IRON), k * 3, UNIT);
        d.take(PLANKS, k, UNIT);
        d.produce(Good.TOOLS, k);
        d.want(List.of(Good.IRON), (capacity - k) * 3, UNIT);
        d.want(PLANKS, capacity - k, UNIT);
    }

    /** Potters fire clay into bricks, terracotta or pots (and melt sand into glass), whatever pays best. */
    private static void potter(Day d, Map<Good, Double> e, double capacity) {
        if (capacity <= 0) return;
        double clay = d.price.get(Good.CLAY), sand = d.price.get(Good.SAND);
        double[] margin = {
                e.get(Good.BRICKS) - clay,
                e.get(Good.TERRACOTTA) / 4 - clay,
                e.get(Good.POTS) * 2.5 / 8 - clay,
                d.s.stock(Good.SAND) > 0 ? e.get(Good.GLASS) - sand : 0};
        Good[] product = {Good.BRICKS, Good.TERRACOTTA, Good.POTS, Good.GLASS};
        double[] yield = {1, 0.25, 2.5 / 8, 1};
        double total = 0;
        for (int i = 0; i < 4; i++) total += Math.pow(Math.max(0, margin[i]), 3);
        if (total <= 0) {
            d.want(List.of(Good.CLAY), capacity, UNIT);
            return;
        }
        for (int i = 0; i < 4; i++) {
            double share = Math.pow(Math.max(0, margin[i]), 3) / total;
            if (share <= 0) continue;
            final int k = i;
            craft(d, List.of(i == 3 ? Good.SAND : Good.CLAY), capacity * share, 0.19, d.cheapestPer(UNIT),
                    (g, a) -> d.produce(product[k], a * yield[k]));
        }
    }

    private static Comparator<Good> byMargin(Day d, Map<Good, Double> e, ToDoubleFunction<Good> outputValue) {
        return Comparator.comparingDouble(g -> -(outputValue.applyAsDouble(g) - d.price.get(g)));
    }

    static Good ingot(Good ore) {
        return switch (ore) {
            case IRON_ORE -> Good.IRON;
            case COPPER_ORE -> Good.COPPER;
            case GOLD_ORE -> Good.GOLD;
            default -> ore;
        };
    }

    // ------------------------------------------------------------------ jobs

    /**
     * How much the settlement worries about food: 1 when it feeds itself or has plenty in store, up to 3 when
     * it eats more than it grows and the stores are running low. Food jobs are valued that much higher —
     * people don't wait for the famine to go fishing.
     */
    static double foodPressure(Settlement s) {
        int pop = s.population();
        if (pop == 0) return 1;
        double need = pop * FOOD_PER_RESIDENT;
        double made = s.produced(Good.BREAD) + s.produced(Good.FISH) * Good.FISH.foodValue()
                + s.produced(Good.MUTTON) + Math.max(0, s.produced(Good.WHEAT) - s.produced(Good.BREAD) * 1.5) * Good.WHEAT.foodValue();
        double stored = 0;
        for (Good g : FOOD) stored += s.stock(g) * g.foodValue();
        double deficit = Math.max(0, need - made) / need;
        double urgency = Math.max(0, Math.min(1, (10 - stored / need) / 10));
        return 1 + 2 * deficit * urgency;
    }

    /** Emeralds a worker of this profession would earn here per day, at effective prices. */
    static double jobValue(Settlement s, Profession p, Map<Good, Double> e) {
        double v = rawJobValue(s, p, e);
        return p.producesFood() && v > 0 ? v * foodPressure(s) : v;
    }

    private static double rawJobValue(Settlement s, Profession p, Map<Good, Double> e) {
        Map<Good, Double> local = prices(s);
        return switch (p) {
            case WOODCUTTER -> 16 * extractValue(s, e, LOGS);
            case MINER -> 12 * extractValue(s, e, ORES) + 3 * e.get(Good.STONE);
            case MASON -> 12 * extractValue(s, e, QUARRIED);
            case FARMER -> 14 * s.deposit(Good.WHEAT) * e.get(Good.WHEAT);
            case FISHER -> 10 * s.deposit(Good.FISH) * e.get(Good.FISH);
            case SHEPHERD -> s.deposit(Good.WOOL) * (4 * e.get(Good.WOOL) + 2 * e.get(Good.MUTTON));
            case SAWYER -> {
                double best = 0;
                for (Good g : LOGS) best = Math.max(best, (4 * e.get(g.planks()) - local.get(g)) * supply(s, List.of(g), 12, p));
                yield 12 * best;
            }
            case SMELTER -> {
                double best = 0;
                for (Good g : SMELTABLE) best = Math.max(best, (e.get(ingot(g)) - local.get(g)) * supply(s, List.of(g), 4, p));
                yield 4 * best - 2 * local.get(Good.COAL);
            }
            case SMITH -> 2 * (e.get(Good.TOOLS) - 3 * local.get(Good.IRON) - local.get(Good.OAK_PLANKS))
                    * supply(s, List.of(Good.IRON), 6, p);
            case POTTER -> 8 * Math.max(e.get(Good.BRICKS) - local.get(Good.CLAY), e.get(Good.POTS) * 2.5 / 8 - local.get(Good.CLAY))
                    * supply(s, List.of(Good.CLAY), 8, p) - 1.5 * local.get(Good.COAL);
            case BAKER -> (6 * e.get(Good.BREAD) - 9 * local.get(Good.WHEAT)) * supply(s, List.of(Good.WHEAT), 9, p);
            case MERCHANT -> 0;
        };
    }

    private static double extractValue(Settlement s, Map<Good, Double> e, List<Good> goods) {
        double total = 0, value = 0;
        for (Good g : goods) {
            double w = weight(s, e, g);
            total += w;
            value += w * s.deposit(g) * e.get(g);
        }
        return total <= 0 ? 0 : value / total;
    }

    /** 0..1: how well the inputs would keep one more worker of a crafting profession busy. */
    private static double supply(Settlement s, List<Good> inputs, double perWorker, Profession p) {
        double have = 0;
        for (Good g : inputs) have += s.stock(g) + s.produced(g);
        double need = perWorker * (s.workers(p) + 1);
        return Math.min(1, have / need);
    }

    static Profession bestJob(Settlement s, Map<Good, Double> e) {
        Profession best = Profession.FISHER;
        double bestV = -Double.MAX_VALUE;
        for (Profession p : Profession.values()) {
            if (p == Profession.MERCHANT) continue;
            double v = jobValue(s, p, e);
            if (v > bestV) {
                bestV = v;
                best = p;
            }
        }
        return best;
    }

    static Profession worstJob(Settlement s, Map<Good, Double> e) {
        Profession worst = null;
        double worstV = Double.MAX_VALUE;
        for (var w : s.workers.entrySet()) {
            if (w.getValue() <= 0 || w.getKey() == Profession.MERCHANT) continue;
            double v = jobValue(s, w.getKey(), e);
            if (v < worstV) {
                worstV = v;
                worst = w.getKey();
            }
        }
        return worst;
    }

    static void removeWorker(Settlement s, Profession p) {
        int n = s.workers(p) - 1;
        if (n <= 0) s.workers.remove(p);
        else s.workers.put(p, n);
    }

    /** One merchant per 15 residents (at least one) runs the trade. */
    private static void rebalanceMerchants(Settlement s, Map<Good, Double> e) {
        int want = 1 + s.population() / 15;
        int have = s.workers(Profession.MERCHANT);
        if (have < want && s.population() > want) {
            Profession from = worstJob(s, e);
            if (from != null) {
                removeWorker(s, from);
                s.workers.merge(Profession.MERCHANT, 1, Integer::sum);
            }
        } else if (have > want) {
            removeWorker(s, Profession.MERCHANT);
            s.workers.merge(bestJob(s, e), 1, Integer::sum);
        }
    }

    /** At most one worker a day changes jobs, and only for a clearly better one. */
    private static void reallocate(Settlement s, Map<Good, Double> e, Outcome out) {
        Profession worst = worstJob(s, e), best = bestJob(s, e);
        if (worst == null || worst == best) return;
        double lo = jobValue(s, worst, e), hi = jobValue(s, best, e);
        if (hi > lo * 1.25 + 0.05) {
            removeWorker(s, worst);
            s.workers.merge(best, 1, Integer::sum);
            out.jobFrom = worst;
            out.jobTo = best;
        }
    }

    // ------------------------------------------------------------------ founding

    /** Fills a new settlement with people, houses, deposits and a few days' worth of stores. */
    static void seed(Settlement s, net.minecraft.util.RandomSource random) {
        seed(s, random, List.of(), -1);
    }

    /**
     * @param woods  wood species growing around the village, most first (empty: rolled at random)
     * @param houses houses actually standing (-1: as many as the people need)
     */
    static void seed(Settlement s, net.minecraft.util.RandomSource random, List<String> woods, int houses) {
        s.deposits.putAll(s.spec().rollDeposits(random));
        if (!woods.isEmpty()) {
            // the forest is the one that really grows here
            s.deposits.keySet().removeIf(Good::isLog);
            for (int i = 0; i < woods.size() && i < 3; i++) {
                Good log = Good.byId(woods.get(i) + "_log");
                if (log == null) continue;
                double rich = s.spec() == Specialization.LUMBER ? 1.9 - i * 0.35 : 0.6 - i * 0.15;
                s.deposits.put(log, rich + random.nextDouble() * 0.2);
            }
        }
        s.workers.putAll(s.spec().startingWorkforce());
        int pop = s.population();
        s.houses = houses >= 0 ? houses : startingHouses(pop);
        s.treasury = 80 + pop * 4;
        s.add(Good.BREAD, pop * 3);
        s.add(Good.FISH, pop * 3);
        s.add(Good.WHEAT, pop * 4);
        s.add(Good.COAL, pop);
        s.add(Good.OAK_LOG, pop * 2);
        s.add(Good.OAK_PLANKS, 40);
        s.add(Good.STONE, 30);
        s.add(Good.TOOLS, Math.max(2, pop * 0.4));
        s.add(Good.WOOL, pop * 0.5);
        s.add(Good.POTS, pop * 0.3);
        // a warm-up so prices and demand mean something from the first moment
        for (int i = 0; i < 4; i++) step(s, false);
    }
}
