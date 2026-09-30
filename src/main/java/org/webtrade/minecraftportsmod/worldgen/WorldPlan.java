package org.webtrade.minecraftportsmod.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * The plan of the world's settlements, made from the terrain generator before the land is ever loaded: where each
 * village will stand, and the rough water lanes between them. Villages are built on their sites as soon as the
 * chunks there can be generated. Overworld only; server thread only.
 */
public final class WorldPlan extends SavedData {

    /** Side of a planning cell; one settlement at most per cell. */
    public static final int CELL = 820;

    public enum State implements StringRepresentable {
        /** Chosen from the generator; nothing built yet. */
        PLANNED("planned"),
        /** Built: a port office and a settlement stand there ({@link Site#portId}). */
        BUILT("built"),
        /** The real terrain turned out unsuitable. */
        FAILED("failed");

        public static final Codec<State> CODEC = StringRepresentable.fromEnum(State::values);
        private final String name;

        State(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final class Site {
        static final Codec<Site> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(s -> s.id),
                Codec.INT.fieldOf("x").forGetter(s -> s.x),
                Codec.INT.fieldOf("z").forGetter(s -> s.z),
                Codec.STRING.fieldOf("name").forGetter(s -> s.name),
                Codec.BOOL.optionalFieldOf("river", false).forGetter(s -> s.river),
                State.CODEC.fieldOf("state").forGetter(s -> s.state),
                Codec.INT.optionalFieldOf("port", -1).forGetter(s -> s.portId)
        ).apply(i, (id, x, z, name, river, state, port) -> {
            Site s = new Site(id, x, z, name, river);
            s.state = state;
            s.portId = port;
            return s;
        }));

        public final int id;
        public final int x, z;
        public final String name;
        /** By a river rather than the sea. */
        public final boolean river;
        State state = State.PLANNED;
        int portId = -1;

        Site(int id, int x, int z, String name, boolean river) {
            this.id = id;
            this.x = x;
            this.z = z;
            this.name = name;
            this.river = river;
        }

        public State state() {
            return state;
        }

        public int portId() {
            return portId;
        }
    }

    /** A rough water lane between two sites, found on the generator's biome map. */
    public record Lane(int siteA, int siteB, int[] path, double length) {
        private static final Codec<int[]> INTS = Codec.INT_STREAM.xmap(IntStream::toArray, Arrays::stream);
        static final Codec<Lane> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("a").forGetter(Lane::siteA),
                Codec.INT.fieldOf("b").forGetter(Lane::siteB),
                INTS.fieldOf("path").forGetter(Lane::path),
                Codec.DOUBLE.fieldOf("length").forGetter(Lane::length)
        ).apply(i, Lane::new));

        /** The path as sailed from {@code fromSite}. */
        public int[] pathFrom(int fromSite) {
            if (fromSite == siteA) return path;
            int[] r = new int[path.length];
            for (int i = 0; i < path.length; i += 2) {
                r[path.length - 2 - i] = path[i];
                r[path.length - 1 - i] = path[i + 1];
            }
            return r;
        }
    }

    public static final Codec<WorldPlan> CODEC = RecordCodecBuilder.create(i -> i.group(
            Site.CODEC.listOf().optionalFieldOf("sites", List.of()).forGetter(p -> new ArrayList<>(p.sites.values())),
            Codec.LONG.listOf().optionalFieldOf("cells", List.of()).forGetter(p -> new ArrayList<>(p.cells)),
            Lane.CODEC.listOf().optionalFieldOf("lanes", List.of()).forGetter(p -> new ArrayList<>(p.lanes.values())),
            Codec.LONG.listOf().optionalFieldOf("no_lane", List.of()).forGetter(p -> new ArrayList<>(p.noLane)),
            Codec.STRING.optionalFieldOf("lang", "en").forGetter(p -> p.lang),
            Codec.INT.optionalFieldOf("mode", 0).forGetter(p -> p.mode),
            Codec.LONG.listOf().optionalFieldOf("predicted", List.of()).forGetter(p -> new ArrayList<>(p.predicted))
    ).apply(i, (sites, cells, lanes, noLane, lang, mode, predicted) -> {
        WorldPlan p = new WorldPlan();
        sites.forEach(s -> p.sites.put(s.id, s));
        p.cells.addAll(cells);
        lanes.forEach(l -> p.lanes.put(pair(l.siteA, l.siteB), l));
        p.noLane.addAll(noLane);
        p.lang = lang;
        p.mode = mode;
        p.predicted.addAll(predicted);
        return p;
    }));

    public static final SavedDataType<WorldPlan> TYPE = new SavedDataType<>(
            Minecraftportsmod.id("world_plan"), WorldPlan::new, CODEC, null);

    private final Map<Integer, Site> sites = new LinkedHashMap<>();
    private final Set<Long> cells = new HashSet<>();
    private final Map<Long, Lane> lanes = new LinkedHashMap<>();
    /** Lanes whose waters have been predicted into the navigation cache. */
    final Set<Long> predicted = new HashSet<>();
    /** Pairs of sites already searched without finding a lane. */
    private final Set<Long> noLane = new HashSet<>();
    /** Language of generated names: "ru" or "en". */
    String lang = "en";
    /**
     * 0 = not decided yet, 1 = villages are planned and built, 2 = off. Decided on the first start: on for a new
     * world, off for a world that existed before (its land is already shaped by players).
     */
    int mode;

    public boolean enabled() {
        return mode == 1;
    }

    public WorldPlan() {
    }

    public static WorldPlan get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static long cellKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    public static long pair(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    public Collection<Site> sites() {
        return Collections.unmodifiableCollection(sites.values());
    }

    public Site site(int id) {
        return sites.get(id);
    }

    public Site siteOfPort(int portId) {
        for (Site s : sites.values()) if (s.portId == portId && portId >= 0) return s;
        return null;
    }

    public boolean cellPlanned(int cx, int cz) {
        return cells.contains(cellKey(cx, cz));
    }

    public int predictedCount() {
        return predicted.size();
    }

    public int cellCount() {
        return cells.size();
    }

    public Collection<Lane> lanes() {
        return Collections.unmodifiableCollection(lanes.values());
    }

    public Lane lane(int siteA, int siteB) {
        return lanes.get(pair(siteA, siteB));
    }

    boolean laneSearched(int a, int b) {
        long k = pair(a, b);
        return lanes.containsKey(k) || noLane.contains(k);
    }

    int nextSiteId() {
        int max = 0;
        for (int id : sites.keySet()) max = Math.max(max, id);
        return max + 1;
    }

    void markCell(int cx, int cz) {
        cells.add(cellKey(cx, cz));
        setDirty();
    }

    void addSite(Site s) {
        sites.put(s.id, s);
        setDirty();
    }

    void setState(Site s, State state, int portId) {
        s.state = state;
        s.portId = portId;
        setDirty();
    }

    void addLane(Lane lane) {
        lanes.put(pair(lane.siteA, lane.siteB), lane);
        setDirty();
    }

    void addNoLane(int a, int b) {
        noLane.add(pair(a, b));
        setDirty();
    }
}
