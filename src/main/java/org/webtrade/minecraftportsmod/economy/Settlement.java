package org.webtrade.minecraftportsmod.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A living settlement attached to a port: what it has, who works where, what things cost there, what happened.
 * Its id is the id of its port. Everything here changes only on the server thread (see {@link EconomyManager}).
 */
public final class Settlement {

    public static final int LOG_SIZE = 60;
    public static final int HISTORY_DAYS = 30;
    public static final int RESIDENTS_PER_HOUSE = 4;

    public enum Level {
        HAMLET(0, 4), VILLAGE(12, 8), TOWN(28, 14), CITY(50, 22);

        /** Residents needed to rise to this level. */
        public final int population;
        /** Most houses a settlement of this level may have: to grow further it has to rise a level. */
        public final int maxHouses;

        Level(int population, int maxHouses) {
            this.population = population;
            this.maxHouses = maxHouses;
        }

        public Level next() {
            return this == CITY ? CITY : values()[ordinal() + 1];
        }

        public static Level of(int population) {
            Level l = HAMLET;
            for (Level v : values()) if (population >= v.population) l = v;
            return l;
        }

        public Component displayName() {
            return Component.translatable("minecraftportsmod.settlement.level." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** One line of the settlement's chronicle. */
    public record LogEntry(long day, Component text) {
        static final Codec<LogEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("day").forGetter(LogEntry::day),
                ComponentSerialization.CODEC.fieldOf("text").forGetter(LogEntry::text)
        ).apply(i, LogEntry::new));
    }

    /**
     * What merchants know about another settlement, brought home by a trader (or learned at founding): its prices
     * and how much of each good it was short of.
     */
    public record Knowledge(int settlement, long day, Map<Good, Double> prices, Map<Good, Double> wants) {
        static final Codec<Knowledge> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("settlement").forGetter(Knowledge::settlement),
                Codec.LONG.fieldOf("day").forGetter(Knowledge::day),
                goodMap(Codec.DOUBLE).fieldOf("prices").forGetter(Knowledge::prices),
                goodMap(Codec.DOUBLE).optionalFieldOf("wants", Map.of()).forGetter(Knowledge::wants)
        ).apply(i, Knowledge::new));

        static Knowledge of(Settlement s, long day) {
            return new Knowledge(s.id(), day, Simulation.prices(s), Simulation.wants(s));
        }
    }

    /** Last day's statistics, kept for the town hall screen and for pricing. */
    private record Stats(Map<Good, Double> produced, Map<Good, Double> consumed, Map<Good, Double> demand,
                         Map<String, Double> needs, Map<Good, List<Float>> history) {
        static final Codec<Stats> CODEC = RecordCodecBuilder.create(i -> i.group(
                goodMap(Codec.DOUBLE).optionalFieldOf("produced", Map.of()).forGetter(Stats::produced),
                goodMap(Codec.DOUBLE).optionalFieldOf("consumed", Map.of()).forGetter(Stats::consumed),
                goodMap(Codec.DOUBLE).optionalFieldOf("demand", Map.of()).forGetter(Stats::demand),
                Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("needs", Map.of()).forGetter(Stats::needs),
                goodMap(Codec.FLOAT.listOf()).optionalFieldOf("history", Map.of()).forGetter(Stats::history)
        ).apply(i, Stats::new));
    }

    /** A temporary event acting on the settlement (a poor harvest, a fair...) until a day. */
    public record Effect(EventType type, long until) {
        static final Codec<Effect> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.xmap(EventType::byId, EventType::id).fieldOf("type").forGetter(Effect::type),
                Codec.LONG.fieldOf("until").forGetter(Effect::until)
        ).apply(i, Effect::new));
    }

    private record Life(int level, int levelDays, List<Effect> effects, Map<String, Double> relations, int housesBuilt) {
        static final Codec<Life> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("level", 0).forGetter(Life::level),
                Codec.INT.optionalFieldOf("level_days", 0).forGetter(Life::levelDays),
                Effect.CODEC.listOf().optionalFieldOf("effects", List.of()).forGetter(Life::effects),
                Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("relations", Map.of()).forGetter(Life::relations),
                Codec.INT.optionalFieldOf("houses_built", -1).forGetter(Life::housesBuilt)
        ).apply(i, Life::new));
    }

    private record Core(int id, Specialization spec, long founded, double treasury, int houses, double happiness,
                        double growth, int hungryDays, int nextOrderId) {
        static final Codec<Core> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(Core::id),
                Codec.STRING.xmap(Specialization::byId, Specialization::id).fieldOf("spec").forGetter(Core::spec),
                Codec.LONG.optionalFieldOf("founded", 0L).forGetter(Core::founded),
                Codec.DOUBLE.optionalFieldOf("treasury", 0.0).forGetter(Core::treasury),
                Codec.INT.optionalFieldOf("houses", 0).forGetter(Core::houses),
                Codec.DOUBLE.optionalFieldOf("happiness", 0.7).forGetter(Core::happiness),
                Codec.DOUBLE.optionalFieldOf("growth", 0.0).forGetter(Core::growth),
                Codec.INT.optionalFieldOf("hungry_days", 0).forGetter(Core::hungryDays),
                Codec.INT.optionalFieldOf("next_order", 1).forGetter(Core::nextOrderId)
        ).apply(i, Core::new));
    }

    public static final Codec<Settlement> CODEC = RecordCodecBuilder.create(i -> i.group(
            Core.CODEC.fieldOf("core").forGetter(s -> new Core(s.id, s.spec, s.founded, s.treasury, s.houses, s.happiness, s.growth, s.hungryDays, s.nextOrderId)),
            goodMap(Codec.DOUBLE).optionalFieldOf("stock", Map.of()).forGetter(s -> s.stock),
            goodMap(Codec.DOUBLE).optionalFieldOf("deposits", Map.of()).forGetter(s -> s.deposits),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("workers", Map.of()).forGetter(Settlement::workersById),
            Stats.CODEC.optionalFieldOf("stats").forGetter(s -> java.util.Optional.of(
                    new Stats(s.produced, s.consumed, s.demand, s.needs, s.historyAsLists()))),
            LogEntry.CODEC.listOf().optionalFieldOf("log", List.of()).forGetter(s -> new ArrayList<>(s.log)),
            Knowledge.CODEC.listOf().optionalFieldOf("knowledge", List.of()).forGetter(s -> new ArrayList<>(s.knowledge.values())),
            UUIDUtil.CODEC.listOf().optionalFieldOf("vessels", List.of()).forGetter(s -> s.vessels),
            Order.CODEC.listOf().optionalFieldOf("orders", List.of()).forGetter(s -> s.orders),
            org.webtrade.minecraftportsmod.village.VillageLayout.CODEC.optionalFieldOf("layout")
                    .forGetter(s -> java.util.Optional.ofNullable(s.layout)),
            Life.CODEC.optionalFieldOf("life").forGetter(s -> java.util.Optional.of(new Life(s.level.ordinal(), s.levelDays,
                    new ArrayList<>(s.effects), s.relationsById(), s.housesBuilt)))
    ).apply(i, (core, stock, deposits, workers, stats, log, knowledge, vessels, orders, layout, life) -> {
        Settlement s = new Settlement(core.id, core.spec == null ? Specialization.FISHING : core.spec, core.founded);
        s.treasury = core.treasury;
        s.houses = core.houses;
        s.happiness = core.happiness;
        s.growth = core.growth;
        s.hungryDays = core.hungryDays;
        s.nextOrderId = core.nextOrderId;
        s.layout = layout.orElse(null);
        life.ifPresent(l -> {
            s.level = Level.values()[Math.max(0, Math.min(l.level, Level.values().length - 1))];
            s.levelDays = l.levelDays;
            l.effects.stream().filter(e -> e.type() != null).forEach(s.effects::add);
            l.relations.forEach((k, v) -> {
                try {
                    s.relations.put(Integer.parseInt(k), v);
                } catch (NumberFormatException ignored) {
                }
            });
            s.housesBuilt = l.housesBuilt;
        });
        if (life.isEmpty()) s.level = Level.of(s.population());
        orders.stream().filter(o -> o.good != null).forEach(s.orders::add);
        s.stock.putAll(stock);
        s.deposits.putAll(deposits);
        workers.forEach((k, v) -> {
            Profession p = Profession.byId(k);
            if (p != null && v > 0) s.workers.put(p, v);
        });
        stats.ifPresent(st -> {
            s.produced.putAll(st.produced);
            s.consumed.putAll(st.consumed);
            s.demand.putAll(st.demand);
            s.needs.putAll(st.needs);
            st.history.forEach((g, list) -> s.history.put(g, new ArrayDeque<>(list)));
        });
        s.log.addAll(log);
        knowledge.forEach(k -> s.knowledge.put(k.settlement, k));
        s.vessels.addAll(vessels);
        return s;
    }));

    /** A map keyed by goods, stored with the goods' ids; unknown ids (removed goods) are dropped. */
    static <V> Codec<Map<Good, V>> goodMap(Codec<V> values) {
        return Codec.unboundedMap(Codec.STRING, values).xmap(m -> {
            Map<Good, V> out = new EnumMap<>(Good.class);
            m.forEach((k, v) -> {
                Good g = Good.byId(k);
                if (g != null) out.put(g, v);
            });
            return out;
        }, m -> {
            Map<String, V> out = new HashMap<>();
            m.forEach((g, v) -> out.put(g.id(), v));
            return out;
        });
    }

    private final int id;
    private final Specialization spec;
    private final long founded;
    double treasury;
    int houses;
    double happiness = 0.7;
    /** Progress towards the next newcomer (1.0 = one more resident). */
    double growth;
    int hungryDays;

    final Map<Good, Double> stock = new EnumMap<>(Good.class);
    final Map<Good, Double> deposits = new EnumMap<>(Good.class);
    final Map<Profession, Integer> workers = new EnumMap<>(Profession.class);

    final Map<Good, Double> produced = new EnumMap<>(Good.class);
    final Map<Good, Double> consumed = new EnumMap<>(Good.class);
    /** Daily demand (consumption + what was wanted but missing); drives prices. */
    final Map<Good, Double> demand = new EnumMap<>(Good.class);
    /** Satisfaction of each need yesterday, 0..1: food, fuel, tools, luxury, housing. */
    final Map<String, Double> needs = new HashMap<>();
    final Map<Good, Deque<Float>> history = new EnumMap<>(Good.class);

    final Deque<LogEntry> log = new ArrayDeque<>();
    final Map<Integer, Knowledge> knowledge = new HashMap<>();
    /** Trade vessels owned by the settlement. */
    final List<UUID> vessels = new ArrayList<>();
    /** Orders for the player (see {@link Market}). */
    final List<Order> orders = new ArrayList<>();
    int nextOrderId = 1;
    /** The settlement's rank; it rises when its people's needs are met (see Simulation). */
    Level level = Level.HAMLET;
    /** Days in a row the settlement has qualified to rise (positive) or failed its level (negative). */
    int levelDays;
    final List<Effect> effects = new ArrayList<>();
    /** Goodwill towards other settlements, 0..100, grown by trade and help. */
    final Map<Integer, Double> relations = new HashMap<>();
    /** Houses actually standing in the world; -1 when the settlement has no village of its own. */
    int housesBuilt = -1;
    /** Where things are in the village (null for a settlement founded by command on a bare port). */
    org.webtrade.minecraftportsmod.village.VillageLayout layout;

    Settlement(int id, Specialization spec, long founded) {
        this.id = id;
        this.spec = spec;
        this.founded = founded;
    }

    // ------------------------------------------------------------------ read access

    public int id() {
        return id;
    }

    /** Same as the port's id. */
    public int portId() {
        return id;
    }

    public Specialization spec() {
        return spec;
    }

    public long founded() {
        return founded;
    }

    public double treasury() {
        return treasury;
    }

    public int houses() {
        return houses;
    }

    public double happiness() {
        return happiness;
    }

    public int population() {
        int n = 0;
        for (int w : workers.values()) n += w;
        return n;
    }

    public Level level() {
        return level;
    }

    public int levelDays() {
        return levelDays;
    }

    public List<Effect> effects() {
        return java.util.Collections.unmodifiableList(effects);
    }

    public boolean has(EventType type) {
        for (Effect e : effects) if (e.type() == type) return true;
        return false;
    }

    public double relation(int other) {
        return relations.getOrDefault(other, 0.0);
    }

    public int housesBuilt() {
        return housesBuilt;
    }

    public void setHousesBuilt(int n) {
        housesBuilt = n;
    }

    public int workers(Profession p) {
        return workers.getOrDefault(p, 0);
    }

    public Map<Profession, Integer> workers() {
        return java.util.Collections.unmodifiableMap(workers);
    }

    public double stock(Good g) {
        return stock.getOrDefault(g, 0.0);
    }

    public Map<Good, Double> deposits() {
        return java.util.Collections.unmodifiableMap(deposits);
    }

    public double deposit(Good g) {
        return deposits.getOrDefault(g, 0.0);
    }

    public double produced(Good g) {
        return produced.getOrDefault(g, 0.0);
    }

    public double consumed(Good g) {
        return consumed.getOrDefault(g, 0.0);
    }

    public double demand(Good g) {
        return demand.getOrDefault(g, 0.0);
    }

    public double need(String need) {
        return needs.getOrDefault(need, 1.0);
    }

    public List<Float> history(Good g) {
        Deque<Float> d = history.get(g);
        return d == null ? List.of() : new ArrayList<>(d);
    }

    public List<LogEntry> log() {
        return new ArrayList<>(log);
    }

    public List<UUID> vessels() {
        return java.util.Collections.unmodifiableList(vessels);
    }

    public org.webtrade.minecraftportsmod.village.VillageLayout layout() {
        return layout;
    }

    public void setLayout(org.webtrade.minecraftportsmod.village.VillageLayout layout) {
        this.layout = layout;
    }

    public int knowledgeCount() {
        return knowledge.size();
    }

    public Knowledge knowledgeOf(int other) {
        return knowledge.get(other);
    }

    // ------------------------------------------------------------------ helpers

    void add(Good g, double amount) {
        if (amount == 0) return;
        stock.merge(g, amount, Double::sum);
        if (stock.get(g) < 1e-6) stock.remove(g);
    }

    void log(long day, Component text) {
        log.addFirst(new LogEntry(day, text));
        while (log.size() > LOG_SIZE) log.removeLast();
    }

    void recordPrice(Good g, double price) {
        Deque<Float> d = history.computeIfAbsent(g, k -> new ArrayDeque<>());
        d.addLast((float) price);
        while (d.size() > HISTORY_DAYS) d.removeFirst();
    }

    private Map<String, Double> relationsById() {
        Map<String, Double> m = new HashMap<>();
        relations.forEach((k, v) -> m.put(String.valueOf(k), v));
        return m;
    }

    private Map<String, Integer> workersById() {
        Map<String, Integer> m = new HashMap<>();
        workers.forEach((p, n) -> m.put(p.id(), n));
        return m;
    }

    private Map<Good, List<Float>> historyAsLists() {
        Map<Good, List<Float>> m = new EnumMap<>(Good.class);
        history.forEach((g, d) -> m.put(g, new ArrayList<>(d)));
        return m;
    }
}
