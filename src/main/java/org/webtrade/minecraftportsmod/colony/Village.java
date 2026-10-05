package org.webtrade.minecraftportsmod.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A village: its people, its buildings (standing and planned), its stores, its level. Grows a day at a time; see
 * {@link VillageLife}.
 */
public final class Village {

    /** What a village is called by its size: a camp, a hamlet... (a title only: it opens nothing). */
    public enum Level {
        CAMP(3, 0), HAMLET(4, 2), VILLAGE(6, 3), SETTLEMENT(8, 4), TOWN(10, 5);

        /** People needed to reach this level, and houses standing. */
        public final int people, houses;

        Level(int people, int houses) {
            this.people = people;
            this.houses = houses;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public Component displayName() {
            return Component.translatable("minecraftportsmod.vlevel." + id());
        }

        public Level next() {
            return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null;
        }

        /** 1-based number, as players count levels. */
        public int number() {
            return ordinal() + 1;
        }

        /** The title a village of this many people goes by. */
        public static Level of(int people) {
            Level out = CAMP;
            for (Level l : values()) if (people >= l.people) out = l;
            return out;
        }
    }

    public record LogLine(long day, Component text) {
        static final Codec<LogLine> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("day").forGetter(LogLine::day),
                ComponentSerialization.CODEC.fieldOf("text").forGetter(LogLine::text)
        ).apply(i, LogLine::new));
    }

    private record Core(int id, String name, BlockPos center, Direction front, String wood, BlockPos board, boolean russian) {
        static final Codec<Core> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(Core::id),
                Codec.STRING.fieldOf("name").forGetter(Core::name),
                BlockPos.CODEC.fieldOf("center").forGetter(Core::center),
                Direction.CODEC.fieldOf("front").forGetter(Core::front),
                Codec.STRING.fieldOf("wood").forGetter(Core::wood),
                BlockPos.CODEC.fieldOf("board").forGetter(Core::board),
                Codec.BOOL.fieldOf("russian").forGetter(Core::russian)
        ).apply(i, Core::new));
    }

    private record Life(String level, long founded, int mood, long lastGrowth, int nextDweller, int nextBuilding,
                        Map<String, Integer> stock, Map<String, Integer> made, int eaten, String priority, int mineStep, int emeralds,
                        List<String> unlocked, String focus, int raiseFirst, Map<String, Double> wear) {
        static final Codec<Life> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("level").forGetter(Life::level),
                Codec.LONG.fieldOf("founded").forGetter(Life::founded),
                Codec.INT.fieldOf("mood").forGetter(Life::mood),
                Codec.LONG.fieldOf("last_growth").forGetter(Life::lastGrowth),
                Codec.INT.fieldOf("next_dweller").forGetter(Life::nextDweller),
                Codec.INT.fieldOf("next_building").forGetter(Life::nextBuilding),
                Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("stock").forGetter(Life::stock),
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("made", Map.of()).forGetter(Life::made),
                Codec.INT.optionalFieldOf("eaten", 0).forGetter(Life::eaten),
                Codec.STRING.optionalFieldOf("priority", "").forGetter(Life::priority),
                Codec.INT.optionalFieldOf("mine_step", 0).forGetter(Life::mineStep),
                Codec.INT.optionalFieldOf("emeralds", 10).forGetter(Life::emeralds),
                Codec.STRING.listOf().optionalFieldOf("unlocked", List.of()).forGetter(Life::unlocked),
                Codec.STRING.optionalFieldOf("focus", "").forGetter(Life::focus),
                Codec.INT.optionalFieldOf("raise_first", -1).forGetter(Life::raiseFirst),
                Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("wear", Map.of()).forGetter(Life::wear)
        ).apply(i, Life::new));
    }

    static final Codec<Village> CODEC = RecordCodecBuilder.create(i -> i.group(
            Core.CODEC.fieldOf("core").forGetter(v -> new Core(v.id, v.name, v.center, v.front, v.wood, v.board, v.russian)),
            Life.CODEC.fieldOf("life").forGetter(v -> new Life(v.level.name(), v.founded, v.mood, v.lastGrowth, v.nextDweller,
                    v.nextBuilding, toIds(v.stock), toIds(v.made), v.eaten, v.priority == null ? "" : v.priority.id(), v.mineStep, v.emeralds,
                    v.unlocked.stream().map(BuildingType::id).toList(), v.focus == null ? "" : v.focus.id(), v.raiseFirst,
                    wearIds(v.wear))),
            Dweller.CODEC.listOf().fieldOf("dwellers").forGetter(v -> v.dwellers),
            Building.CODEC.listOf().fieldOf("buildings").forGetter(v -> v.buildings),
            LogLine.CODEC.listOf().optionalFieldOf("log", List.of()).forGetter(v -> new ArrayList<>(v.log)),
            Chart.CODEC.optionalFieldOf("chart", Chart.EMPTY).forGetter(Village::chartData),
            Orders.Order.CODEC.listOf().optionalFieldOf("orders", List.of()).forGetter(v -> v.orders),
            Economy.CODEC.optionalFieldOf("economy", Economy.NONE).forGetter(v -> new Economy(v.rewarded, v.mined, v.dig, v.ready, v.traded)),
            Plans.CODEC.optionalFieldOf("plans", Plans.NONE).forGetter(v -> new Plans(List.copyOf(v.order), v.research == null ? "" : v.research.id(),
                    v.sub == null ? "" : v.sub.id(), v.style, v.island, v.ships, v.shipWork)),
            Quests.Board.CODEC.optionalFieldOf("tasks").forGetter(v -> java.util.Optional.of(v.tasks))
    ).apply(i, (core, life, dwellers, buildings, log, chart, orders, eco, plans, tasks) -> {
        Village v = new Village(core.id, core.name, core.center, core.front, core.wood, core.board, core.russian);
        try {
            v.level = Level.valueOf(life.level);
        } catch (IllegalArgumentException e) {
            v.level = Level.CAMP;
        }
        v.founded = life.founded;
        v.mood = life.mood;
        v.lastGrowth = life.lastGrowth;
        v.nextDweller = life.nextDweller;
        v.nextBuilding = life.nextBuilding;
        fromIds(life.stock, v.stock);
        fromIds(life.made, v.made);
        v.eaten = life.eaten;
        v.priority = BuildingType.byId(life.priority);
        v.mineStep = life.mineStep;
        v.emeralds = life.emeralds;
        for (String t : life.unlocked) {
            BuildingType bt = BuildingType.byId(t);
            if (bt != null) v.unlocked.add(bt);
        }
        v.focus = BuildingType.Branch.byId(life.focus);
        life.wear.forEach((k, w) -> {
            Job j = Job.byId(k);
            if (j != null) v.wear.put(j, w);
        });
        v.raiseFirst = life.raiseFirst;
        // (a village from before levels were rewarded: the levels it has are not rewarded again)
        v.rewarded = eco.rewarded() < 0 ? v.level.ordinal() : eco.rewarded();
        v.mined = eco.mined();
        v.dig = eco.dig();
        v.ready = eco.ready();
        v.traded = eco.traded();
        v.order.addAll(plans.order());
        v.research = BuildingType.byId(plans.research());
        v.sub = BuildingType.Sub.byId(plans.sub());
        v.style = plans.style();
        v.island = plans.island();
        v.ships = plans.ships();
        v.shipWork = plans.shipWork();
        v.dwellers.addAll(dwellers);
        v.buildings.addAll(buildings);
        v.log.addAll(log);
        v.orders.addAll(orders);
        tasks.ifPresent(b -> v.tasks = b);
        for (int k = 0; k < Math.min(chart.keys.size(), chart.colors.size()); k++) v.chart.put(chart.keys.get(k), chart.colors.get(k));
        chart.known.forEach((id, day) -> {
            try {
                v.known.put(Integer.parseInt(id), day);
            } catch (NumberFormatException ignored) {
            }
        });
        // a village from before the tree had to be opened: what it has built counts as open
        if (life.unlocked.isEmpty()) {
            for (Building b : v.buildings) if (b.type.isNode() && !b.type.free) v.unlocked.add(b.type);
        }
        return v;
    }));

    /** The village's queue: the order its building sites are worked in (building ids), and the research in hand. */
    private record Plans(List<Integer> order, String research, String sub, int style, boolean island, int ships, int shipWork) {
        static final Plans NONE = new Plans(List.of(), "", "", -1, false, 0, 0);
        static final Codec<Plans> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.listOf().optionalFieldOf("order", List.of()).forGetter(Plans::order),
                Codec.STRING.optionalFieldOf("research", "").forGetter(Plans::research),
                Codec.STRING.optionalFieldOf("sub", "").forGetter(Plans::sub),
                Codec.INT.optionalFieldOf("style", -1).forGetter(Plans::style),
                Codec.BOOL.optionalFieldOf("island", false).forGetter(Plans::island),
                Codec.INT.optionalFieldOf("ships", 0).forGetter(Plans::ships),
                Codec.INT.optionalFieldOf("ship_work", 0).forGetter(Plans::shipWork)
        ).apply(i, Plans::new));
    }

    /** The village's own emeralds: the highest level it was rewarded for, the emeralds its miners found, and how near the next is. */
    private record Economy(int rewarded, int mined, double dig, double ready, long traded) {
        static final Economy NONE = new Economy(-1, 0, 0, 0, -100);
        static final Codec<Economy> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("rewarded", -1).forGetter(Economy::rewarded),
                Codec.INT.optionalFieldOf("mined", 0).forGetter(Economy::mined),
                Codec.DOUBLE.optionalFieldOf("dig", 0.0).forGetter(Economy::dig),
                Codec.DOUBLE.optionalFieldOf("ready", 0.0).forGetter(Economy::ready),
                Codec.LONG.optionalFieldOf("traded", -100L).forGetter(Economy::traded)
        ).apply(i, Economy::new));
    }

    /** What the scouts have mapped: the land (32-block cells, a colour each) and the villages they found (id, day). */
    private record Chart(List<Long> keys, List<Integer> colors, Map<String, Long> known) {
        static final Chart EMPTY = new Chart(List.of(), List.of(), Map.of());
        static final Codec<Chart> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.listOf().optionalFieldOf("keys", List.of()).forGetter(Chart::keys),
                Codec.INT.listOf().optionalFieldOf("colors", List.of()).forGetter(Chart::colors),
                Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("known", Map.of()).forGetter(Chart::known)
        ).apply(i, Chart::new));
    }

    private Chart chartData() {
        List<Long> keys = new ArrayList<>(chart.size());
        List<Integer> colors = new ArrayList<>(chart.size());
        chart.forEach((k, c) -> {
            keys.add(k);
            colors.add(c);
        });
        Map<String, Long> kn = new HashMap<>();
        known.forEach((id, day) -> kn.put(String.valueOf(id), day));
        return new Chart(keys, colors, kn);
    }

    private static Map<String, Double> wearIds(EnumMap<Job, Double> m) {
        Map<String, Double> out = new HashMap<>();
        m.forEach((j, w) -> out.put(j.id(), w));
        return out;
    }

    private static Map<String, Integer> toIds(EnumMap<Res, Integer> m) {
        Map<String, Integer> out = new HashMap<>();
        m.forEach((r, n) -> out.put(r.id(), n));
        return out;
    }

    private static void fromIds(Map<String, Integer> in, EnumMap<Res, Integer> out) {
        in.forEach((k, n) -> {
            // (stores of old wares are summed into what they are now: stairs, slabs... into joinery)
            Res r = Res.byId(k);
            if (r != null) out.merge(r, n, Integer::sum);
        });
    }

    public static final int LOG_SIZE = 80;

    public final int id;
    public final String name;
    /** The middle of the village (the campfire, later the well), at the height people stand. */
    public final BlockPos center;
    /** Towards the water. */
    public final Direction front;
    /** The wood the village builds with. */
    public final String wood;
    /** The village board by the campfire. */
    public final BlockPos board;
    /** Names are Russian (else English). */
    final boolean russian;

    Level level = Level.CAMP;
    long founded;
    /** 0..100. */
    int mood = 60;
    long lastGrowth;
    int nextDweller = 1, nextBuilding = 1;
    final EnumMap<Res, Integer> stock = new EnumMap<>(Res.class);
    /** What the village made yesterday, and how much food was eaten. */
    final EnumMap<Res, Integer> made = new EnumMap<>(Res.class);
    int eaten;
    /** What the players asked the village to build next (null: the village decides). */
    BuildingType priority;
    /** A building the players asked to be raised a level first (-1: none). */
    int raiseFirst = -1;
    /** The nodes of the tree opened (the free ones aside). */
    final java.util.EnumSet<BuildingType> unlocked = java.util.EnumSet.noneOf(BuildingType.class);
    /** The trade the village goes deep into (cheaper to open there), or null until chosen. */
    BuildingType.Branch focus;
    /** How far each trade's tools are worn (a whole one: one to replace). */
    final EnumMap<Job, Double> wear = new EnumMap<>(Job.class);
    /** The trades whose worn tools could not be replaced today (they work with worse). */
    final java.util.EnumSet<Job> toolsShort = java.util.EnumSet.noneOf(Job.class);
    /** The land the scouts have mapped: 32-block cells (Scouting.key) and their colour on the map. */
    final Map<Long, Integer> chart = new HashMap<>();
    /** Where the players asked the next expedition to go (degrees, 0 east, 90 south), -1: the scout chooses. */
    int scoutAim = -1;
    /** The other villages the scouts have found: id → the day they were found. */
    final Map<Integer, Long> known = new java.util.LinkedHashMap<>();
    /** What the village used up yesterday (tools). */
    final EnumMap<Res, Integer> used = new EnumMap<>(Res.class);
    /** What the workshops (the sawmill, the smithy, the joiner's) made and used yesterday, by workshop. */
    final java.util.Map<BuildingType, EnumMap<Res, Integer>> workshopMade = new java.util.EnumMap<>(BuildingType.class);
    final java.util.Map<BuildingType, EnumMap<Res, Integer>> workshopUsed = new java.util.EnumMap<>(BuildingType.class);

    void workshop(BuildingType t, Res r, int made, int used) {
        if (made > 0) workshopMade.computeIfAbsent(t, k -> new EnumMap<>(Res.class)).merge(r, made, Integer::sum);
        if (used > 0) workshopUsed.computeIfAbsent(t, k -> new EnumMap<>(Res.class)).merge(r, used, Integer::sum);
    }

    /** What went to the building sites yesterday. */
    final EnumMap<Res, Integer> built = new EnumMap<>(Res.class);
    /** How far along its plan the village mine is dug. */
    int mineStep;
    /** The village's purse, in emeralds (its merchant pays and is paid in them). */
    int emeralds = 10;
    /** The order the village's building sites are seen to in: building ids, first first (see {@link VillageLife#queue}). */
    final List<Integer> order = new ArrayList<>();
    /** The sub-branch of its speciality the village is known for (null until it has a speciality). */
    BuildingType.Sub sub;
    /** How the village builds (see {@link Blueprint#TIMBER}...), -1 until chosen by its land. */
    int style = -1;
    /** It stands on an island: no trail reaches it, its trade goes by sea once it has a harbour. */
    boolean island;
    /** The ships the village has built (see {@link Harbour}), and the days of work left on the one on the stocks (0: none). */
    int ships, shipWork;

    public boolean island() {
        return island;
    }

    public int style() {
        return style;
    }

    /** The node of the tree the village is saving up to open (null: none in hand). */
    BuildingType research;
    /**
     * Goods the village cannot make that the nodes and levels it wants ask for (worked out each day, not saved): kept
     * as a want, so that its merchant buys them from the villages that make them.
     */
    final EnumMap<Res, Integer> wishes = new EnumMap<>(Res.class);

    /** Steps the players took out of the queue, and the day: not put back by the village for a few days. */
    final Map<String, Long> declined = new HashMap<>();

    /** The highest level the village was rewarded for reaching (see {@link VillageLife#reward}). */
    int rewarded;
    /** Emeralds the village's miners have found in its vein so far, and the share of the next one found. */
    int mined;
    double dig;
    /** How ready the village is for a child: the chance of one tonight; grows night by night, back to nothing when one is born. */
    double ready;
    /** The day its merchant last did business on a round (sold or bought something). */
    long traded = -100;
    final List<Dweller> dwellers = new ArrayList<>();
    final List<Building> buildings = new ArrayList<>();
    /** What players ordered from the buildings' people (see {@link Orders}). */
    final List<Orders.Order> orders = new ArrayList<>();
    /** The share of each trade's day that went on orders today (not saved). */
    java.util.Map<Job, Double> orderLoad = new java.util.EnumMap<>(Job.class);
    /** What the village's people asked the players for, and what the players did for it (see {@link Quests}). */
    Quests.Board tasks = new Quests.Board();

    public Quests.Board tasks() {
        return tasks;
    }

    /** Newest first. */
    final Deque<LogLine> log = new ArrayDeque<>();

    Village(int id, String name, BlockPos center, Direction front, String wood, BlockPos board, boolean russian) {
        this.id = id;
        this.name = name;
        this.center = center;
        this.front = front;
        this.wood = wood;
        this.board = board;
        this.russian = russian;
    }

    // ------------------------------------------------------------------ reading

    public Level level() {
        return level;
    }

    public int mood() {
        return mood;
    }

    public long founded() {
        return founded;
    }

    public int stock(Res r) {
        return stock.getOrDefault(r, 0);
    }

    public int made(Res r) {
        return made.getOrDefault(r, 0);
    }

    public int eaten() {
        return eaten;
    }

    public List<Dweller> dwellers() {
        return dwellers;
    }

    public List<Building> buildings() {
        return buildings;
    }

    public List<LogLine> log() {
        return new ArrayList<>(log);
    }

    public int population() {
        return dwellers.size();
    }

    public Dweller dweller(int id) {
        for (Dweller d : dwellers) if (d.id == id) return d;
        return null;
    }

    public Building building(int id) {
        for (Building b : buildings) if (b.id == id) return b;
        return null;
    }

    public Dweller elder() {
        for (Dweller d : dwellers) if (d.elder) return d;
        return dwellers.isEmpty() ? null : dwellers.getFirst();
    }

    /** Other villages this one knows of. */
    public int knownCount() {
        return known.size();
    }

    /** The ids of the villages this one knows of. */
    public java.util.Set<Integer> knownIds() {
        return java.util.Collections.unmodifiableSet(known.keySet());
    }

    public int emeralds() {
        return emeralds;
    }

    /** The node of the tree the village is saving up to open, or null. */
    public BuildingType research() {
        return research;
    }

    public BuildingType priority() {
        return priority;
    }

    public int raiseFirst() {
        return raiseFirst;
    }

    public BuildingType.Sub sub() {
        return sub;
    }

    public BuildingType.Branch focus() {
        return focus;
    }

    public boolean unlocked(BuildingType t) {
        return Tree.unlocked(this, t);
    }

    /** Does the village have this building standing (the middle: this step or a later one)? */
    public boolean has(BuildingType type) {
        for (Building b : buildings) if (b.standing() && b.type.atLeast(type)) return true;
        return false;
    }

    /** How many of these (or better) stand. */
    public int countAtLeast(BuildingType type) {
        int n = 0;
        for (Building b : buildings) if (b.standing() && b.type.atLeast(type)) n++;
        return n;
    }

    /** The tools of a trade: the best the village has in store (0: none, bare hands; 1 wooden; 2 stone; 3 iron). */
    public int toolLevel(Job job) {
        int best = VillageLife.toolTier(this);
        // a fisher cuts himself a rod: never bare-handed
        if (job == Job.FISHER) best = Math.max(1, best);
        return best;
    }

    public boolean toolsShort(Job job) {
        return toolsShort.contains(job);
    }

    public int used(Res r) {
        return used.getOrDefault(r, 0);
    }

    public int count(BuildingType type, boolean standingOnly) {
        int n = 0;
        for (Building b : buildings) if (b.type == type && b.owner == null && (!standingOnly || b.standing())) n++;
        return n;
    }

    /** Houses (not tents, not the trades' houses) that stand. */
    public int houses() {
        int n = 0;
        for (Building b : buildings) if (b.standing() && b.type.branch == BuildingType.Branch.HOME && b.type != BuildingType.TENT) n++;
        return n;
    }

    public int beds() {
        int n = 0;
        for (Building b : buildings) if (b.standing()) n += b.type.beds;
        return n;
    }

    public int freeBeds() {
        return beds() - population();
    }

    public int workers(Job job) {
        int n = 0;
        for (Dweller d : dwellers) if (d.job == job) n++;
        return n;
    }

    public int adults() {
        int n = 0;
        for (Dweller d : dwellers) if (d.job != null) n++;
        return n;
    }

    /** Buildings being planned, built, raised a level or pulled down. */
    public List<Building> projects() {
        List<Building> out = new ArrayList<>();
        for (Building b : buildings) if (b.project()) out.add(b);
        return out;
    }

    /** Where the stores are kept: the storehouse, or the campfire while there is none. */
    public BlockPos storeSpot() {
        for (Building b : buildings) {
            if ((b.type == BuildingType.STOREHOUSE || b.type == BuildingType.STOREHOUSE_2) && b.standing()) return b.blueprint(wood).workSpot;
        }
        for (Building b : buildings) {
            if (b.type.isCenter() && b.standing()) return b.blueprint(wood).workSpot;
        }
        return center;
    }

    // ------------------------------------------------------------------ changing

    /** What the village keeps without a storehouse: a pile by the fire, a lean-to. */
    public static final int HEAP = 300;

    /**
     * The room in the village's store, for everything together (food, wood, iron: all of it takes room in the same
     * storehouses): the pile by the fire, and each storehouse (every level of it adding as much again).
     */
    public int capacity() {
        int total = HEAP;
        for (Building b : buildings) {
            if (!b.standing()) continue;
            if (b.type == BuildingType.STOREHOUSE) total += 240 * (b.level + 1);
            if (b.type == BuildingType.STOREHOUSE_2) total += 480 * (b.level + 1);
        }
        return total;
    }

    /** All the village has in store, everything together. */
    public int stored() {
        int n = 0;
        for (int x : stock.values()) n += Math.max(0, x);
        return n;
    }

    /** The room left in the store. */
    public int room() {
        return Math.max(0, capacity() - stored());
    }

    /**
     * The room there is for a resource: the free room, and what the village has of other things beyond what it
     * means to keep of them (that is moved out to make room: see {@link #add}).
     */
    public int free(Res r) {
        int n = room();
        for (Res q : Res.values()) if (q != r) n += Math.max(0, stock(q) - VillageLife.target(this, q));
        return n;
    }

    /** How much of a resource there could be in store now: what there is, and all the room it could have. */
    public int capacity(Res r) {
        return stock(r) + free(r);
    }

    public boolean full(Res r) {
        return free(r) <= 0;
    }

    /**
     * Adds to (or takes from) the stores. Where there is no room, what the village has more of than it means to keep
     * (the largest heap beyond its mark first) is moved out for it; what still does not fit is lost.
     */
    void add(Res r, int n) {
        if (n > 0 && room() < n) {
            int need = n - room();
            while (need > 0) {
                Res most = null;
                int over = 0;
                for (Res q : Res.values()) {
                    if (q == r) continue;
                    int o = stock(q) - VillageLife.target(this, q);
                    if (o > over) {
                        over = o;
                        most = q;
                    }
                }
                if (most == null) break;
                int out = Math.min(need, over);
                stock.merge(most, -out, Integer::sum);
                need -= out;
            }
            n = Math.min(n, room());
        }
        stock.merge(r, n, Integer::sum);
        if (stock(r) < 0) stock.put(r, 0);
    }

    void log(long day, Component text) {
        log.addFirst(new LogLine(day, text));
        while (log.size() > LOG_SIZE) log.removeLast();
    }
}
