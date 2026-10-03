package org.webtrade.minecraftportsmod.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A building of a village, standing or planned. A building goes PLANNED (the plot is marked, materials are
 * brought) → BUILDING (put up a block at a time by whoever works on it) → BUILT, and eventually DEMOLISHING when the
 * village outgrows it.
 * <p>
 * {@code work} is the progress the village has made (in blocks); {@code placed} is how many of the blueprint's
 * blocks actually stand in the world. They differ while nobody is near: the village keeps working, and the blocks
 * catch up when the place is loaded again.
 */
public final class Building {

    public enum State {
        PLANNED, BUILDING, BUILT, DEMOLISHING;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The part of a building added with the levels: its level, the one it is growing to, kept from demolition. */
    private record Grade(int level, int goal, boolean keep) {
        static final com.mojang.serialization.MapCodec<Grade> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.optionalFieldOf("level", 1).forGetter(Grade::level),
                Codec.INT.optionalFieldOf("goal", 0).forGetter(Grade::goal),
                Codec.BOOL.optionalFieldOf("keep", false).forGetter(Grade::keep)
        ).apply(i, Grade::new));
    }

    /** Kinds of buildings that are gone: what they are now, and at which level. */
    private static final Map<String, Object[]> LEGACY = Map.of(
            "mine_house_2", new Object[]{BuildingType.MINE_HOUSE, 3},
            "wood_hut_2", new Object[]{BuildingType.WOOD_HUT, 3},
            "fish_hut_2", new Object[]{BuildingType.FISH_HUT, 3},
            "farm_2", new Object[]{BuildingType.FARM, 3});

    private static final com.mojang.serialization.MapCodec<Building> BASE = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(b -> b.id),
            Codec.STRING.fieldOf("type").forGetter(b -> b.type.id()),
            BlockPos.CODEC.fieldOf("origin").forGetter(b -> b.origin),
            Direction.CODEC.fieldOf("front").forGetter(b -> b.front),
            Codec.STRING.fieldOf("state").forGetter(b -> b.state.name()),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("delivered", Map.of()).forGetter(b -> {
                Map<String, Integer> m = new HashMap<>();
                b.delivered.forEach((r, n) -> m.put(r.id(), n));
                return m;
            }),
            Codec.INT.optionalFieldOf("work", 0).forGetter(b -> b.work),
            Codec.INT.optionalFieldOf("placed", 0).forGetter(b -> b.placed),
            Codec.BOOL.optionalFieldOf("levelled", false).forGetter(b -> b.levelled),
            Codec.INT.optionalFieldOf("replaces", -1).forGetter(b -> b.replaces),
            Codec.LONG.optionalFieldOf("created", 0L).forGetter(b -> b.created),
            Codec.LONG.optionalFieldOf("seed", 0L).forGetter(b -> b.seed),
            Codec.BOOL.optionalFieldOf("finished", false).forGetter(b -> b.finished),
            Codec.BOOL.optionalFieldOf("paths", false).forGetter(b -> b.paths),
            Codec.STRING.optionalFieldOf("option", "").forGetter(b -> b.option),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("price", Map.of()).forGetter(b -> {
                Map<String, Integer> m = new HashMap<>();
                b.price.forEach((r, n) -> m.put(r.id(), n));
                return m;
            })
    ).apply(i, (id, type, origin, front, state, delivered, work, placed, levelled, replaces, created, seed, finished, paths, option, price) -> {
        BuildingType t = BuildingType.byId(type);
        Object[] old = LEGACY.get(type);
        if (old != null) t = (BuildingType) old[0];
        Building b = new Building(id, t == null ? BuildingType.TENT : t, origin, front, seed);
        if (old != null) b.level = (Integer) old[1];
        try {
            b.state = State.valueOf(state);
        } catch (IllegalArgumentException e) {
            b.state = State.BUILT;
        }
        delivered.forEach((k, v) -> {
            Res r = Res.byId(k);
            if (r != null) b.delivered.merge(r, v, Integer::sum);
        });
        b.work = work;
        b.placed = placed;
        b.levelled = levelled;
        b.replaces = replaces;
        b.created = created;
        b.finished = finished;
        b.paths = paths;
        b.option = option;
        price.forEach((k, v) -> {
            Res r = Res.byId(k);
            if (r != null) b.price.merge(r, v, Integer::sum);
        });
        return b;
    }));

    static final Codec<Building> CODEC = com.mojang.serialization.Codec.mapPair(BASE, Grade.CODEC).xmap(pair -> {
        Building b = pair.getFirst();
        Grade g = pair.getSecond();
        // an old save's level (a "second step" building) wins over the default
        if (g.level() > 1 || b.level == 1) b.level = Math.max(1, Math.min(b.type.maxLevel, g.level()));
        b.goal = g.goal() > b.level ? Math.min(b.type.maxLevel, g.goal()) : 0;
        b.keep = g.keep();
        return b;
    }, b -> com.mojang.datafixers.util.Pair.of(b, new Grade(b.level, b.goal, b.keep))).codec();

    public final int id;
    public final BuildingType type;
    public final BlockPos origin;
    public final Direction front;
    final long seed;
    State state = State.PLANNED;
    final EnumMap<Res, Integer> delivered = new EnumMap<>(Res.class);
    /** What it costs (fixed when it was planned); empty: the type's plain cost. */
    final EnumMap<Res, Integer> price = new EnumMap<>(Res.class);
    int work, placed;
    boolean levelled;
    /** A building this one takes the place of (the campfire the well replaces): it comes down first. */
    int replaces = -1;
    long created;
    /** The progress at the start of the day (not saved): a site that did not move all day gets a push. */
    transient int workMark = -1;
    /** A demolition that is over as far as the village is concerned (the blocks may still be coming down). */
    boolean finished;
    /** The paths from its door to the rest of the village are laid. */
    boolean paths;
    /** A setting chosen in the building's menu: what a field is sown with. */
    String option = "";
    /** How far the building has grown (1..the type's max). */
    int level = 1;
    /** The level it is being raised to (0: none): materials are brought, then the additions go up. */
    int goal;
    /** The players asked for it never to be pulled down. */
    boolean keep;

    private transient Blueprint blueprint;
    private transient String blueprintWood;
    private transient int blueprintLevel;

    Building(int id, BuildingType type, BlockPos origin, Direction front, long seed) {
        this.id = id;
        this.type = type;
        this.origin = origin;
        this.front = front;
        this.seed = seed;
    }

    /** The blueprint as it will stand when the work under way is done (with the next level's additions, if growing). */
    public Blueprint blueprint(String wood) {
        int lv = upgrading() ? goal : level;
        if (blueprint == null || !wood.equals(blueprintWood) || blueprintLevel != lv) {
            blueprint = Blueprint.of(type, new Blueprint.Frame(origin, front), wood, seed, crop(), lv);
            blueprintWood = wood;
            blueprintLevel = lv;
        }
        return blueprint;
    }

    public int level() {
        return level;
    }

    /** Blocks of it standing in the world. */
    public int placed() {
        return placed;
    }

    /** Blocks of it in all (at its level). */
    public int pieces(Village v) {
        return blueprint(v.wood).pieces.size();
    }

    /** The level it is being raised to, 0 if none. */
    public int goal() {
        return goal;
    }

    public boolean keep() {
        return keep;
    }

    /** Standing, and being raised to its next level. */
    public boolean upgrading() {
        return state == State.BUILT && goal > level;
    }

    /** Raising it: everything is brought, the additions are going up. */
    public boolean upgradeWork() {
        return upgrading() && supplied();
    }

    /** Is there anything left to do at it: materials, building, raising, pulling down? */
    public boolean project() {
        return state != State.BUILT || upgrading();
    }

    /** What a field is sown with. */
    public Crop crop() {
        return Crop.byId(option);
    }

    public String option() {
        return option;
    }

    /** A new setting from the building's menu (what a field is sown with): the blueprint follows it. */
    void setOption(String option) {
        this.option = option;
        this.blueprint = null;
    }

    public State state() {
        return state;
    }

    public int work() {
        return work;
    }

    public int delivered(Res r) {
        return delivered.getOrDefault(r, 0);
    }

    /** What is still to be brought. */
    public int missing(Res r) {
        return Math.max(0, cost(r) - delivered(r));
    }

    /** What it costs in this resource. */
    public int cost(Res r) {
        return price.isEmpty() ? type.cost(r) : price.getOrDefault(r, 0);
    }

    public boolean supplied() {
        for (Res r : Res.values()) if (missing(r) > 0) return false;
        return true;
    }

    /** Is it (or will it be) a place to live that stands? */
    public boolean standing() {
        return state == State.BUILT;
    }

    /** Does the plot take room (anything but a demolished building that is already gone)? */
    public boolean occupies() {
        return true;
    }

    /** Blocks that should stand now, going by the progress. */
    int target(int total) {
        return switch (state) {
            case PLANNED -> 0;
            case BUILDING -> Math.min(work, total);
            // while it is raised a level, only the old blocks stand until the materials are all there
            case BUILT -> upgrading() ? (supplied() ? Math.min(work, total) : blueprint.upTo(level)) : total;
            case DEMOLISHING -> Math.max(0, total - work);
        };
    }

    /** True if the plots of these two overlap, with {@code gap} blocks kept between them. */
    public boolean overlaps(BlockPos center, int half, int gap) {
        int d = type.half + half + gap;
        return Math.abs(center.getX() - origin.getX()) <= d && Math.abs(center.getZ() - origin.getZ()) <= d;
    }
}
