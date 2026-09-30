package org.webtrade.minecraftportsmod.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All villages and the village clock, stored with the world. Server thread only. */
public final class VillageData extends SavedData {

    /** A village day is a Minecraft day. */
    public static final int DEFAULT_DAY_LENGTH = 24000;

    public static final Codec<VillageData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Village.CODEC.listOf().optionalFieldOf("villages", List.of()).forGetter(d -> new ArrayList<>(d.villages.values())),
            Codec.LONG.optionalFieldOf("day", 0L).forGetter(d -> d.day),
            Codec.INT.optionalFieldOf("day_ticks", 0).forGetter(d -> d.dayTicks),
            Codec.INT.optionalFieldOf("day_length", DEFAULT_DAY_LENGTH).forGetter(d -> d.dayLength),
            Codec.INT.optionalFieldOf("next_id", 1).forGetter(d -> d.nextId),
            Trails.Trail.CODEC.listOf().optionalFieldOf("trails", List.of()).forGetter(d -> new ArrayList<>(d.trails.values())),
            Caravans.Trip.CODEC.listOf().optionalFieldOf("trips", List.of()).forGetter(d -> d.trips),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("last_trip", Map.of()).forGetter(d -> {
                Map<String, Long> m = new java.util.HashMap<>();
                d.lastTrip.forEach((k, v) -> m.put(String.valueOf(k), v));
                return m;
            }),
            Codec.INT.listOf().listOf().optionalFieldOf("forks", List.of()).forGetter(d -> {
                List<List<Integer>> l = new ArrayList<>();
                d.junctions.forEach((k, v) -> l.add(List.of(k, v[0], v[1])));
                return l;
            }),
            Roadworks.Work.CODEC.listOf().optionalFieldOf("roadworks", List.of()).forGetter(d -> d.works),
            Codec.LONG.optionalFieldOf("minted", 0L).forGetter(d -> d.minted),
            Codec.LONG.optionalFieldOf("burnt", 0L).forGetter(d -> d.burnt)
    ).apply(i, (list, day, dayTicks, dayLength, nextId, trails, trips, lastTrip, forks, works, minted, burnt) -> {
        VillageData d = new VillageData();
        list.forEach(v -> d.villages.put(v.id, v));
        d.day = day;
        d.dayTicks = dayTicks;
        d.dayLength = Math.max(200, dayLength);
        d.nextId = nextId;
        // (a trail still being worked out when the world was saved: worked out again)
        for (Trails.Trail t : trails) if (t.ready() || t.none()) d.trails.put(Trails.key(t.a, t.b), t);
        d.trips.addAll(trips);
        for (List<Integer> f : forks) {
            if (f.size() < 3) continue;
            d.junctions.put(f.get(0), new int[]{f.get(1), f.get(2)});
            d.lastJunction = Math.min(d.lastJunction, f.get(0));
        }
        d.works.addAll(works);
        d.minted = minted;
        d.burnt = burnt;
        lastTrip.forEach((k, v) -> {
            try {
                d.lastTrip.put(Integer.parseInt(k), v);
            } catch (NumberFormatException ignored) {
            }
        });
        return d;
    }));

    public static final SavedDataType<VillageData> TYPE = new SavedDataType<>(
            Minecraftportsmod.id("villages"), VillageData::new, CODEC, null);

    private final Map<Integer, Village> villages = new LinkedHashMap<>();
    long day;
    int dayTicks;
    int dayLength = DEFAULT_DAY_LENGTH;
    int nextId = 1;
    /** The trails between villages (key: the two ids), see {@link Trails}. */
    final Map<Long, Trails.Trail> trails = new LinkedHashMap<>();
    /** The forks of the trail network (negative ids) and where they are. */
    final Map<Integer, int[]> junctions = new LinkedHashMap<>();
    /** The last fork's id (they count down from -1). */
    int lastJunction = 0;
    /** Merchants on the road between villages, see {@link Caravans}. */
    final List<Caravans.Trip> trips = new ArrayList<>();
    /** The day each village's merchant last set out. */
    final Map<Integer, Long> lastTrip = new java.util.HashMap<>();
    /** Trails being built by the villages' crews, see {@link Roadworks}. */
    final List<Roadworks.Work> works = new ArrayList<>();
    /**
     * Emeralds that came into the villages' hands other than from a player or from one another (a new village's
     * purse, a level reached, a vein found), and emeralds gone out of them (with a merchant lost on the road): all
     * the villages hold, and all their merchants carry, is the one less the other (and what players paid or took).
     */
    long minted, burnt;

    public Collection<Trails.Trail> trails() {
        return Collections.unmodifiableCollection(trails.values());
    }

    /** The forks of the trail network: id (negative) → x, z. */
    public Map<Integer, int[]> junctions() {
        return Collections.unmodifiableMap(junctions);
    }

    public List<Caravans.Trip> trips() {
        return Collections.unmodifiableList(trips);
    }

    public List<Roadworks.Work> works() {
        return Collections.unmodifiableList(works);
    }

    public long minted() {
        return minted;
    }

    public long burnt() {
        return burnt;
    }

    public VillageData() {
    }

    public static VillageData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public Collection<Village> all() {
        return Collections.unmodifiableCollection(villages.values());
    }

    public Village get(int id) {
        return villages.get(id);
    }

    /** The village whose ground this is (within {@code radius} of its middle), or null. */
    public Village near(BlockPos pos, int radius) {
        Village best = null;
        double bestD = (double) radius * radius;
        for (Village v : villages.values()) {
            double d = v.center.distSqr(pos);
            if (d <= bestD) {
                bestD = d;
                best = v;
            }
        }
        return best;
    }

    public long day() {
        return day;
    }

    public int dayTicks() {
        return dayTicks;
    }

    public int dayLength() {
        return dayLength;
    }

    /** Sets the time of the village day (tests: the evening, the morning). */
    public void setDayTicks(int ticks) {
        dayTicks = Math.floorMod(ticks, dayLength);
        setDirty();
    }

    public void setDayLength(int ticks) {
        dayLength = Math.max(200, ticks);
        dayTicks = Math.min(dayTicks, dayLength - 1);
        setDirty();
    }

    int newId() {
        setDirty();
        return nextId++;
    }

    void add(Village v) {
        villages.put(v.id, v);
        // (a new village's purse: its settlers' savings)
        minted += v.emeralds;
        setDirty();
    }

    void remove(int id) {
        if (villages.remove(id) != null) setDirty();
    }

    public void changed() {
        setDirty();
    }
}
