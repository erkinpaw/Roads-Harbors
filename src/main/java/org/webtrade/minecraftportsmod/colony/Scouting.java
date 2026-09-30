package org.webtrade.minecraftportsmod.colony;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The village's scouts. A scout sets out from the cartographer's house for some days: out along a heading, round an
 * arc at the far end, and home along another line. What they saw on the way is drawn on the village's map, and the
 * villages they passed are known from then on.
 * <p>
 * Nothing of the world is loaded for it: the map is worked out from the world generator (the land's height, its
 * water and its biomes), on a thread of its own, while the scout is away. So even many scouts at once cost the
 * server next to nothing, and the map shows the land as it will be when someone goes there.
 */
public final class Scouting {

    /** A cell of the map: this many blocks each way. */
    public static final int CELL = 32;
    /** How far a scout sees from the way: cells round each point of it. */
    private static final int SIGHT = 3;
    /** A village this close to the scout's way is found. */
    private static final int FIND = 180;
    /** Blocks a scout covers in a day. */
    private static final int PER_DAY = 450;
    /** Days a scout stays home between two expeditions. */
    private static final int REST = 2;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Ports&Routes scouting");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /** Expeditions being worked out: village id * 1000 + dweller id. */
    private static final Map<Long, Future<Result>> RUNNING = new ConcurrentHashMap<>();

    /** A world closed: what its scouts saw is dropped. */
    static void reset() {
        for (Future<Result> f : RUNNING.values()) f.cancel(true);
        RUNNING.clear();
    }

    /** What an expedition brought back: the cells seen (key → colour), the villages found (id → distance). */
    record Result(Map<Long, Integer> cells, Map<Integer, Integer> found) {
    }

    private Scouting() {
    }

    public static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /**
     * How far out a scout of a cartographer's house of this level goes. The villages of a world stand some 600 to 1300
     * blocks apart: the first house's scout reaches the nearest (and a little past it, what he sees from his way).
     */
    public static int range(int level) {
        return switch (level) {
            case 1 -> 1000;
            case 2 -> 1400;
            default -> 1800;
        };
    }

    /** Days away for an expedition of this reach. */
    static int days(int range) {
        return (int) Math.ceil(2.0 * range / PER_DAY) + 1;
    }

    /** How many scouts the cartographer's house keeps: two once it is at the top. */
    static int scouts(Village v) {
        Building b = house(v);
        if (b == null) return 0;
        // one to begin with; more (as many as the house's level) only for a village whose food feeds another scout
        // with some over: a farming village sends them out, a mining or a logging one keeps to the one
        int n = 1;
        int spare = VillageLife.production(v, Res.FOOD) - VillageLife.foodNeed(v, Long.MAX_VALUE / 2);
        int more = v.workers(Job.SCOUT) >= 1 ? 0 : VillageLife.SCOUT_EATS;
        // (and a store of it: a village living on bought food keeps to its one)
        boolean stocked = v.stock(Res.FOOD) >= VillageLife.foodNeed(v, Long.MAX_VALUE / 2) * 5;
        while (stocked && n < b.level && spare - more >= VillageLife.SCOUT_EATS + VillageLife.ADULT_EATS) {
            n++;
            spare -= VillageLife.SCOUT_EATS;
        }
        return n;
    }

    /** The level of the village's cartographer's house (0: none standing). */
    public static int houseLevel(Village v) {
        Building b = house(v);
        return b == null ? 0 : b.level;
    }

    static Building house(Village v) {
        for (Building b : v.buildings) if (b.type == BuildingType.CARTOGRAPHER && b.standing()) return b;
        return null;
    }

    // ------------------------------------------------------------------ the day

    /** A village day: scouts back from the road, rested scouts setting out. */
    static void day(ServerLevel level, Village v, long today) {
        Building house = house(v);
        for (Dweller d : v.dwellers) {
            if (d.job != Job.SCOUT) continue;
            if (d.away) {
                if (today >= d.back && ready(level, v, d)) comeBack(level, v, d, today);
                continue;
            }
            if (house == null || today - d.back < REST) continue;
            depart(level, v, d, house, today);
        }
    }

    private static final String[] WINDS = {"east", "south_east", "south", "south_west", "west", "north_west", "north", "north_east"};

    /** The name of the wind a heading (degrees, 0 east, 90 south) points to. */
    static Component wind(int heading) {
        int i = Math.floorMod(Math.round(heading / 45F), 8);
        return Component.translatable("minecraftportsmod.wind." + WINDS[i]);
    }

    /**
     * The way the scout sets out: the one the players asked for; else, while the village knows no one yet (and now
     * and then after), towards the nearest village it has not found (travellers speak of smoke that way, a trodden
     * path: the scout goes to see, and may miss it by a little); else the way least known yet, of eight.
     */
    private static int heading(ServerLevel level, Village v, int range, net.minecraft.util.RandomSource rnd) {
        // the way the players asked for, if they did
        if (v.scoutAim >= 0) {
            int aim = v.scoutAim;
            v.scoutAim = -1;
            return aim;
        }
        Village near = null;
        for (Village o : VillageData.get(level.getServer()).all()) {
            if (o.id == v.id || v.known.containsKey(o.id)) continue;
            double d = Math.sqrt(o.center.distSqr(v.center));
            if (d > range + FIND) continue;
            if (near == null || d < Math.sqrt(near.center.distSqr(v.center))) near = o;
        }
        if (near != null && (v.known.isEmpty() || rnd.nextInt(2) == 0)) {
            double a = Math.toDegrees(Math.atan2(near.center.getZ() - v.center.getZ(), near.center.getX() - v.center.getX()));
            return (int) Math.round(a) + rnd.nextInt(31) - 15;
        }
        int best = 0, bestKnown = Integer.MAX_VALUE;
        int cx = Math.floorDiv(v.center.getX(), CELL), cz = Math.floorDiv(v.center.getZ(), CELL);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45 + rnd.nextInt(21) - 10);
            int seen = 0;
            for (int t = CELL * 2; t <= range; t += CELL * 2) {
                int x = cx + (int) Math.round(Math.cos(a) * t / CELL), z = cz + (int) Math.round(Math.sin(a) * t / CELL);
                if (v.chart.containsKey(key(x, z))) seen++;
            }
            if (seen < bestKnown || seen == bestKnown && rnd.nextBoolean()) {
                bestKnown = seen;
                best = (int) Math.round(Math.toDegrees(a));
            }
        }
        return best;
    }

    private static void depart(ServerLevel level, Village v, Dweller d, Building house, long today) {
        d.range = range(house.level);
        d.heading = heading(level, v, d.range, level.getRandom());
        d.away = true;
        d.back = today + days(d.range);
        start(level, v, d);
        v.log(today, Component.translatable("minecraftportsmod.vlog.scout_out", d.name, wind(d.heading), d.range, days(d.range)));
    }

    /** Works the expedition out on the scouting thread. */
    private static Future<Result> start(ServerLevel level, Village v, Dweller d) {
        List<int[]> others = new ArrayList<>();
        for (Village o : VillageData.get(level.getServer()).all()) {
            if (o.id != v.id) others.add(new int[]{o.id, o.center.getX(), o.center.getZ()});
        }
        Set<Long> seen = new HashSet<>(v.chart.keySet());
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        int x0 = v.center.getX(), z0 = v.center.getZ(), heading = d.heading, range = d.range;
        Future<Result> f = POOL.submit(() -> survey(gen, random, level, x0, z0, heading, range, seen, others));
        RUNNING.put(v.id * 1000L + d.id, f);
        return f;
    }

    /**
     * Is what the scout saw worked out: he comes home only then (the server does not wait for it; a day skipped over
     * many villages at once no longer stalls on it). After a restart it is worked out again first.
     */
    private static boolean ready(ServerLevel level, Village v, Dweller d) {
        Future<Result> f = RUNNING.get(v.id * 1000L + d.id);
        if (f == null) {
            start(level, v, d);
            return false;
        }
        return f.isDone();
    }

    private static void comeBack(ServerLevel level, Village v, Dweller d, long today) {
        Future<Result> f = RUNNING.remove(v.id * 1000L + d.id);
        // (after a restart the expedition is worked out again: the same way, the same land)
        if (f == null) f = start(level, v, d);
        RUNNING.remove(v.id * 1000L + d.id);
        Result res;
        try {
            res = f.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            Minecraftportsmod.LOGGER.warn("Scouting of {} failed: {}", v.name, e.toString());
            res = new Result(Map.of(), Map.of());
        }
        Minecraftportsmod.LOGGER.info("Scout {} of {} back: {} cells, found {}", d.name, v.name, res.cells().size(), res.found());
        int before = v.chart.size();
        v.chart.putAll(res.cells());
        int fresh = v.chart.size() - before;
        d.away = false;
        d.back = today;
        d.arriving = true;
        String km2 = String.format(java.util.Locale.ROOT, "%.1f", fresh * (double) CELL * CELL / 1_000_000);
        v.log(today, Component.translatable("minecraftportsmod.vlog.scout_back", d.name, km2).withStyle(ChatFormatting.DARK_AQUA));
        for (var e : res.found().entrySet()) {
            if (v.known.containsKey(e.getKey())) continue;
            Village o = VillageData.get(level.getServer()).get(e.getKey());
            if (o == null) continue;
            v.known.put(o.id, today);
            int dx = o.center.getX() - v.center.getX(), dz = o.center.getZ() - v.center.getZ();
            int dist = (int) Math.round(Math.hypot(dx, dz));
            int dir = (int) Math.round(Math.toDegrees(Math.atan2(dz, dx)));
            v.log(today, Component.translatable("minecraftportsmod.vlog.scout_found", d.name, o.name, dist, wind(dir)).withStyle(ChatFormatting.GOLD));
            // the scout was seen there, and told where he came from: they know of us from now on (no scout of their own needed)
            if (o.known.putIfAbsent(v.id, today) == null) {
                o.log(today, Component.translatable("minecraftportsmod.vlog.scout_visited", d.name, v.name).withStyle(ChatFormatting.GOLD));
            }
        }
    }

    // ------------------------------------------------------------------ the land, from the world generator

    /**
     * The expedition's way (out along the heading, an arc at the far end, home along another heading), and all it
     * saw: cells within sight of the way, and villages close to it.
     */
    static Result survey(ChunkGenerator gen, RandomState random, LevelHeightAccessor heights, int x0, int z0, int heading, int range,
                         Set<Long> seen, List<int[]> others) {
        List<int[]> way = new ArrayList<>();
        double a0 = Math.toRadians(heading), a1 = a0 + Math.toRadians(20);
        for (int t = CELL; t <= range; t += CELL) way.add(new int[]{x0 + (int) (Math.cos(a0) * t), z0 + (int) (Math.sin(a0) * t)});
        for (double a = a0; a <= a1; a += (double) CELL / range) way.add(new int[]{x0 + (int) (Math.cos(a) * range), z0 + (int) (Math.sin(a) * range)});
        for (int t = range; t >= CELL; t -= CELL) way.add(new int[]{x0 + (int) (Math.cos(a1) * t), z0 + (int) (Math.sin(a1) * t)});
        // and round the village itself
        way.add(new int[]{x0, z0});
        Map<Long, Integer> cells = new HashMap<>();
        int sea = gen.getSeaLevel();
        for (int[] p : way) {
            int pcx = Math.floorDiv(p[0], CELL), pcz = Math.floorDiv(p[1], CELL);
            for (int dx = -SIGHT; dx <= SIGHT; dx++) {
                for (int dz = -SIGHT; dz <= SIGHT; dz++) {
                    if (dx * dx + dz * dz > SIGHT * SIGHT + 1) continue;
                    long k = key(pcx + dx, pcz + dz);
                    if (seen.contains(k) || cells.containsKey(k)) continue;
                    int x = (pcx + dx) * CELL + CELL / 2, z = (pcz + dz) * CELL + CELL / 2;
                    cells.put(k, color(gen, random, heights, sea, x, z));
                }
            }
        }
        Map<Integer, Integer> found = new HashMap<>();
        for (int[] o : others) {
            for (int[] p : way) {
                long dx = o[1] - p[0], dz = o[2] - p[1];
                if (dx * dx + dz * dz <= (long) FIND * FIND) {
                    found.put(o[0], (int) Math.hypot(o[1] - x0, o[2] - z0));
                    break;
                }
            }
        }
        return new Result(cells, found);
    }

    /** The colour of the land at a point, as a map would show it: water by its depth, the land by its growth and height. */
    static int color(ChunkGenerator gen, RandomState random, LevelHeightAccessor heights, int sea, int x, int z) {
        Holder<Biome> b = gen.getBiomeSource().getNoiseBiome(x >> 2, sea >> 2, z >> 2, random.sampler());
        int h = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, random);
        Biome biome = b.value();
        if (h < sea) {
            int depth = sea - h;
            int base = b.is(BiomeTags.IS_RIVER) ? 0x4E84AB : 0x3C6C93;
            return shade(base, depth > 12 ? -40 : depth > 5 ? -18 : 10);
        }
        int c;
        if (b.is(BiomeTags.IS_BEACH)) c = 0xDCCB90;
        else if (b.is(BiomeTags.IS_BADLANDS)) c = 0xB8683A;
        else if (b.is(BiomeTags.IS_SAVANNA)) c = 0xA8A45A;
        else if (b.is(BiomeTags.IS_JUNGLE)) c = 0x3E8A2E;
        else if (biome.getBaseTemperature() >= 1.9F) c = 0xDBCB8F;   // the deserts
        else if (b.is(BiomeTags.IS_FOREST) || b.is(BiomeTags.IS_TAIGA)) c = mix(biome.getFoliageColor(), 0x1E3A12, 0.35);
        else c = biome.getGrassColor(x, z);
        if (biome.getBaseTemperature() < 0.15F) c = mix(c, 0xF4F7FA, 0.6);
        int above = h - sea;
        if (above > 60) c = mix(c, above > 100 ? 0xF0F0F0 : 0x8A8A8A, Math.min(0.8, (above - 60) / 60.0));
        return shade(c, Math.min(30, above / 3) - 10);
    }

    private static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int shade(int rgb, int d) {
        int r = Math.max(0, Math.min(255, ((rgb >> 16) & 255) + d));
        int g = Math.max(0, Math.min(255, ((rgb >> 8) & 255) + d));
        int b = Math.max(0, Math.min(255, (rgb & 255) + d));
        return (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ the map table

    /** The map for a player at the table: what is mapped, the villages found, where the scouts are. */
    static void send(ServerPlayer player, Village v) {
        VillageData data = VillageData.get(player.level().getServer());
        long[] keys = new long[v.chart.size()];
        int[] colors = new int[v.chart.size()];
        int i = 0;
        for (var e : v.chart.entrySet()) {
            keys[i] = e.getKey();
            colors[i++] = e.getValue();
        }
        List<ColonyPayloads.PlaceRow> places = new ArrayList<>();
        for (var e : v.known.entrySet()) {
            Village o = data.get(e.getKey());
            if (o == null) continue;
            places.add(new ColonyPayloads.PlaceRow(o.name, o.center.getX(), o.center.getZ(), o.level.ordinal(), o.focus == null ? -1 : o.focus.ordinal(),
                    e.getValue()));
        }
        List<ColonyPayloads.ScoutRow> scouts = new ArrayList<>();
        for (Dweller d : v.dwellers) {
            if (d.job == Job.SCOUT) scouts.add(new ColonyPayloads.ScoutRow(d.name, d.away, d.back, d.heading, d.range));
        }
        Building house = house(v);
        ServerPlayNetworking.send(player, new ColonyPayloads.MapView(v.id, v.name, v.center.getX(), v.center.getZ(), CELL, keys, colors, places,
                scouts, data.day, house == null ? 0 : range(house.level), v.scoutAim));
    }
}
