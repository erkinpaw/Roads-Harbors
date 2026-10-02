package org.webtrade.minecraftportsmod.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The trails are made by the villages' own people. Once the way between two villages is worked out (the day a scout
 * found the other), the next morning each village sends a crew of two, who walk to their end of it and make it
 * stretch by stretch towards the other crew: they fell the trees in the way (the whole tree, and the bushes), clear
 * the brush and tread the path. They work by day; at dusk they pitch a tent by the way where they stopped and sleep
 * there, strike it in the morning and go on, day after day, until the two crews meet. Then they walk home, and the
 * trail can be walked: merchants go along it from then on (not before).
 * <p>
 * It is all real, not written off: every crew knows how far it has got, and gets on at the same pace whether anybody
 * watches or not. Where a player is near, its people are there in the world at the end of the way (and the way is
 * made only while they are at it), and their tent stands there at night.
 */
public final class Roadworks {

    /** Blocks of trail one worker makes in a working day: the woods cut seven wide, the brush cleared, the way trodden. */
    public static final int PER_WORKER = 80;
    /** The crew a village sends. */
    public static final int CREW = 2;
    /** Blocks walked in a working day, to the end of the way and home again. */
    static final int WALK_PER_DAY = 2400;
    /** The working day, as a share of the day (sunrise is 0): from the morning to the dusk. */
    static final double WORK_FROM = 1000 / 24000.0, WORK_TO = 11500 / 24000.0;
    /** A crew is put into the world where it works when a player is this near. */
    static final int SEEN = 80;
    /** A worker this near the stretch in hand is at work there. */
    static final int AT_WORK = 5;
    /** The way is made a stretch of this many blocks at a time (the crew stands at it until it is done). */
    static final int STRETCH = 6;

    public static final int WAITING = 0, GOING = 1, WORKING = 2, RETURNING = 3, HOME = 4;

    private Roadworks() {
    }

    /** One village's part of the work: its crew, how far they have got, where they are. */
    public static final class Side {
        static final Codec<Side> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("village").forGetter(s -> s.village),
                Codec.INT.listOf().optionalFieldOf("crew", List.of()).forGetter(s -> s.crew),
                Codec.INT.optionalFieldOf("state", WAITING).forGetter(s -> s.state),
                Codec.DOUBLE.optionalFieldOf("done", 0.0).forGetter(s -> s.done),
                Codec.DOUBLE.optionalFieldOf("walk", 0.0).forGetter(s -> s.walk),
                Codec.INT.listOf().optionalFieldOf("camp", List.of()).forGetter(s -> s.camp == null ? List.of() : List.of(s.camp[0], s.camp[1])),
                Codec.INT.listOf().optionalFieldOf("tent", List.of()).forGetter(s -> s.tent == null ? List.of()
                        : List.of(s.tent[0], s.tent[1], s.tent[2], s.tent[3], s.tent[4]))
        ).apply(i, (village, crew, state, done, walk, camp, tent) -> {
            Side s = new Side(village);
            s.crew.addAll(crew);
            s.state = state;
            s.done = done;
            s.walk = walk;
            s.camp = camp.size() >= 2 ? new int[]{camp.get(0), camp.get(1)} : null;
            s.tent = tent.size() >= 5 ? new int[]{tent.get(0), tent.get(1), tent.get(2), tent.get(3), tent.get(4)} : null;
            return s;
        }));

        public final int village;
        final List<Integer> crew = new ArrayList<>();
        int state = WAITING;
        /** Blocks of the way made from this end. */
        double done;
        /** Blocks still to walk (to the end of the way, or home). */
        double walk;
        /** Where the crew camps tonight (x, z), null by day. */
        int[] camp;
        /** The tent standing in the world: x, y, z, the way it faces, its seed; null if none stands. */
        int[] tent;
        /** Stretches of the way from this end marked made (not saved: marked again after a restart). */
        int marked;

        Side(int village) {
            this.village = village;
        }

        public List<Integer> crew() {
            return List.copyOf(crew);
        }

        public int state() {
            return state;
        }

        public double done() {
            return done;
        }

        public int[] camp() {
            return camp == null ? null : camp.clone();
        }

        public int[] tent() {
            return tent == null ? null : tent.clone();
        }
    }

    /** A trail being made: its way (from village {@code a}'s end to {@code b}'s), and the two crews. */
    public static final class Work {
        static final Codec<Work> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("a").forGetter(w -> w.a),
                Codec.INT.fieldOf("b").forGetter(w -> w.b),
                Codec.INT.fieldOf("na").forGetter(w -> w.na),
                Codec.INT.fieldOf("nb").forGetter(w -> w.nb),
                Codec.INT.listOf().fieldOf("points").forGetter(w -> {
                    List<Integer> l = new ArrayList<>(w.points.length);
                    for (int x : w.points) l.add(x);
                    return l;
                }),
                Codec.LONG.fieldOf("planned").forGetter(w -> w.planned),
                Codec.LONG.optionalFieldOf("started", -1L).forGetter(w -> w.started),
                Codec.LONG.optionalFieldOf("finished", -1L).forGetter(w -> w.finished),
                Side.CODEC.fieldOf("side_a").forGetter(w -> w.sa),
                Side.CODEC.fieldOf("side_b").forGetter(w -> w.sb)
        ).apply(i, (a, b, na, nb, points, planned, started, finished, sa, sb) -> {
            Work w = new Work(a, b, na, nb, points.stream().mapToInt(Integer::intValue).toArray(), planned, sa, sb);
            w.started = started;
            w.finished = finished;
            return w;
        }));

        /** The village that found the other, and the one found. */
        public final int a, b;
        /** The nodes of the network the way runs between (a village, or a fork), a's end first. */
        final int na, nb;
        final int[] points;
        /** The day the way was worked out, the day the first crew set out, the day the crews met (-1: not yet). */
        public final long planned;
        long started = -1, finished = -1;
        final Side sa, sb;
        /** Blocks along the way to each of its points (not saved). */
        private double[] cum;

        Work(int a, int b, int na, int nb, int[] points, long planned, Side sa, Side sb) {
            this.a = a;
            this.b = b;
            this.na = na;
            this.nb = nb;
            this.points = points;
            this.planned = planned;
            this.sa = sa;
            this.sb = sb;
        }

        double[] cum() {
            if (cum == null) {
                cum = new double[points.length / 2];
                for (int k = 1; k < cum.length; k++) {
                    cum[k] = cum[k - 1] + Math.hypot(points[2 * k] - points[2 * k - 2], points[2 * k + 1] - points[2 * k - 1]);
                }
            }
            return cum;
        }

        public double length() {
            double[] c = cum();
            return c.length == 0 ? 0 : c[c.length - 1];
        }

        public long started() {
            return started;
        }

        public long finished() {
            return finished;
        }

        public Side side(int village) {
            return village == a ? sa : village == b ? sb : null;
        }

        public Side sideA() {
            return sa;
        }

        public Side sideB() {
            return sb;
        }

        public int[] points() {
            return points.clone();
        }

        /** Blocks along the way (from a's end) where a side's crew is at work. */
        public double front(Side s) {
            return s == sa ? Math.min(length(), sa.done) : Math.max(0, length() - sb.done);
        }

        /** The point {@code d} blocks along the way. */
        public int[] at(double d) {
            double[] c = cum();
            for (int k = 1; k < c.length; k++) {
                if (d <= c[k]) {
                    double seg = c[k] - c[k - 1], f = seg == 0 ? 0 : (d - c[k - 1]) / seg;
                    return new int[]{(int) Math.round(points[2 * k - 2] + (points[2 * k] - points[2 * k - 2]) * f),
                            (int) Math.round(points[2 * k - 1] + (points[2 * k + 1] - points[2 * k - 1]) * f)};
                }
            }
            return new int[]{points[points.length - 2], points[points.length - 1]};
        }

        /** Blocks along the way (from a's end) of the middle of the stretch a side's crew has in hand. */
        public double stretch(Side s) {
            if (s == sa) return Math.min(length(), (Math.floor(sa.done / STRETCH) + 0.5) * STRETCH);
            return Math.max(0, length() - (Math.floor(sb.done / STRETCH) + 0.5) * STRETCH);
        }

        /** The way's heading (degrees) at {@code d} blocks along it. */
        double heading(double d) {
            int[] p = at(Math.max(0, d - 4)), q = at(Math.min(length(), d + 4));
            return Math.toDegrees(Math.atan2(q[1] - p[1], q[0] - p[0]));
        }

        boolean done() {
            return sa.done + sb.done >= length() - 0.5;
        }
    }

    // ------------------------------------------------------------------ the day

    /** The way between two villages is worked out: it is to be made, starting tomorrow. */
    static void begin(VillageData data, Village v, Village o, int first, int node, int[] pts, long today) {
        Work w = new Work(v.id, o.id, first, node, pts.clone(), today, new Side(v.id), new Side(o.id));
        data.works.add(w);
        v.log(today, Component.translatable("minecraftportsmod.vlog.road_planned", o.name, (int) w.length()).withStyle(ChatFormatting.DARK_AQUA));
        o.log(today, Component.translatable("minecraftportsmod.vlog.road_planned", v.name, (int) w.length()).withStyle(ChatFormatting.DARK_AQUA));
        data.changed();
    }

    /**
     * A new day (sunrise): crews set out for the ways worked out before today; a village that could spare no one
     * yesterday may today. A day skipped: every crew gets through a whole day (walks, works, camps where it stopped).
     */
    static void day(ServerLevel level, VillageData data, boolean skip) {
        long today = data.day;
        for (Work w : new ArrayList<>(data.works)) {
            Village va = data.get(w.a), vb = data.get(w.b);
            if (va == null || vb == null) {
                abandon(level, data, w);
                continue;
            }
            if (w.finished < 0 && today > w.planned) {
                for (Side s : new Side[]{w.sa, w.sb}) if (s.state == WAITING) setOut(data, w, s, today);
            }
            if (skip) {
                // the morning: the tents struck; the day's walk and work; the evening: camp where they stopped
                for (Side s : new Side[]{w.sa, w.sb}) {
                    strike(level, data, w, s);
                    s.camp = null;
                    progress(level, data, w, s, 1.0);
                }
                if (w.finished < 0 && w.done()) finish(level, data, w);
                for (Side s : new Side[]{w.sa, w.sb}) if (s.state == WORKING) camp(level, data, w, s);
            }
            settle(level, data, w);
        }
    }

    /** A village's crew sets out for its end of the way (if it can spare the people). */
    private static void setOut(VillageData data, Work w, Side s, long today) {
        Village v = data.get(s.village);
        if (v == null) return;
        List<Dweller> crew = pick(v);
        if (crew.isEmpty()) return;
        int node = s == w.sa ? w.na : w.nb;
        int[] start = s == w.sa ? new int[]{w.points[0], w.points[1]} : new int[]{w.points[w.points.length - 2], w.points[w.points.length - 1]};
        int heading = (int) Math.round(Math.toDegrees(Math.atan2(start[1] - v.center.getZ(), start[0] - v.center.getX())));
        for (Dweller d : crew) {
            d.away = true;
            d.heading = heading;
            s.crew.add(d.id);
        }
        s.walk = approach(data, v, node, start);
        s.state = s.walk <= 0 ? WORKING : GOING;
        if (w.started < 0) w.started = today;
        Village other = data.get(s == w.sa ? w.b : w.a);
        StringBuilder names = new StringBuilder();
        for (Dweller d : crew) names.append(names.length() == 0 ? "" : ", ").append(d.name);
        v.log(today, Component.translatable("minecraftportsmod.vlog.road_out", names.toString(), other == null ? "?" : other.name)
                .withStyle(ChatFormatting.DARK_AQUA));
        data.changed();
    }

    /**
     * Who goes: the village keeps three at home at the least; woodcutters first (it is felling, mostly), then the ones
     * whose trade's store is full anyway, then any other worker of the land (never the smith, the merchant, the scout).
     */
    static List<Dweller> pick(Village v) {
        // (the grown-ups at home: not the ones already away, on another trail, trading, scouting)
        int home = 0;
        for (Dweller d : v.dwellers) if (d.job != null && !d.away) home++;
        int spare = Math.min(CREW, home - 3);
        if (spare <= 0) return List.of();
        List<Dweller> can = new ArrayList<>();
        for (Dweller d : v.dwellers) {
            if (d.away || d.job == null || d.arriving) continue;
            if (d.job != Job.WOODCUTTER && d.job != Job.MINER && d.job != Job.FARMER && d.job != Job.FISHER) continue;
            can.add(d);
        }
        can.sort(java.util.Comparator.comparingInt(d -> (d.job == Job.WOODCUTTER ? 0 : 2) + (VillageLife.plenty(v, d.job.makes) ? 0 : 1)));
        return can.subList(0, Math.min(spare, can.size()));
    }

    /** Blocks from the village to the end of the way it makes: over the trails that can be walked, else across country. */
    private static double approach(VillageData data, Village v, int node, int[] end) {
        if (node == v.id) return 0;
        int[] r = Trails.route(data, v.id, node);
        if (r != null) {
            double len = 0;
            for (int i = 2; i + 1 < r.length; i += 2) len += Math.hypot(r[i] - r[i - 2], r[i + 1] - r[i - 1]);
            return len;
        }
        return Math.hypot(end[0] - v.center.getX(), end[1] - v.center.getZ()) * 1.3;
    }

    /**
     * {@code share} of a working day for one side: walking there first, then making the way (so many blocks a
     * worker); or walking home.
     */
    private static void progress(ServerLevel level, VillageData data, Work w, Side s, double share) {
        if (s.state == GOING) {
            s.walk -= WALK_PER_DAY * share;
            if (s.walk > 0) return;
            // there: what is left of the day goes on the work
            share = Math.min(share, -s.walk / WALK_PER_DAY);
            s.walk = 0;
            s.state = WORKING;
        }
        if (s.state == WORKING) {
            Side other = s == w.sa ? w.sb : w.sa;
            double gap = w.length() - s.done - other.done;
            s.done += Math.max(0, Math.min(gap, PER_WORKER * s.crew.size() * share));
            mark(level, data, w, s);
        } else if (s.state == RETURNING) {
            s.walk -= WALK_PER_DAY * share;
            if (s.walk <= 0) home(data, w, s);
        }
    }

    /** The stretches a crew has got past are made (and laid in the world at once where it is loaded: a bridge grows under their feet). */
    private static void mark(ServerLevel level, VillageData data, Work w, Side s) {
        double[] c = w.cum();
        int segs = c.length - 1;
        while (s.marked < segs) {
            int k = s == w.sa ? s.marked : segs - 1 - s.marked;
            boolean past = s == w.sa ? c[k + 1] <= s.done + 1e-6 : c[k] >= w.length() - s.done - 1e-6;
            if (!past) break;
            Trails.markBuilt(data, w.points[2 * k], w.points[2 * k + 1], w.points[2 * k + 2], w.points[2 * k + 3]);
            Trails.layNow(level, data, w.points[2 * k], w.points[2 * k + 1], w.points[2 * k + 2], w.points[2 * k + 3]);
            s.marked++;
        }
    }

    /** The crews have met: the whole way is made; they go home, their tents come down. */
    private static void finish(ServerLevel level, VillageData data, Work w) {
        long today = data.day;
        w.finished = today;
        double[] c = w.cum();
        for (int k = 0; k + 1 < c.length; k++) Trails.markBuilt(data, w.points[2 * k], w.points[2 * k + 1], w.points[2 * k + 2], w.points[2 * k + 3]);
        for (Side s : new Side[]{w.sa, w.sb}) {
            Village v = data.get(s.village);
            if (s.state == WAITING) {
                s.state = HOME;
                continue;
            }
            if (v == null) continue;
            // home: back along what they made to their end of it, and on from there
            int node = s == w.sa ? w.na : w.nb;
            int[] start = s == w.sa ? new int[]{w.points[0], w.points[1]} : new int[]{w.points[w.points.length - 2], w.points[w.points.length - 1]};
            s.walk = s.done + approach(data, v, node, start);
            s.state = RETURNING;
            strike(level, data, w, s);
            s.camp = null;
            Village other = data.get(s == w.sa ? w.b : w.a);
            v.log(today, Component.translatable("minecraftportsmod.vlog.road_done", other == null ? "?" : other.name, (int) w.length(),
                    today - Math.max(w.started, w.planned)).withStyle(ChatFormatting.GOLD));
        }
        Minecraftportsmod.LOGGER.info("Road #{} - #{} made: {} blocks, planned day {}, started {}, finished {} ({} by #{}, {} by #{})", w.a, w.b,
                (int) w.length(), w.planned, w.started, w.finished, (int) w.sa.done, w.a, (int) w.sb.done, w.b);
        data.changed();
    }

    /** A crew is home. */
    private static void home(VillageData data, Work w, Side s) {
        Village v = data.get(s.village);
        s.state = HOME;
        s.walk = 0;
        if (v == null) return;
        for (int id : s.crew) {
            Dweller d = v.dweller(id);
            if (d == null) continue;
            d.away = false;
            d.arriving = true;
        }
        v.log(data.day, Component.translatable("minecraftportsmod.vlog.road_home").withStyle(ChatFormatting.DARK_AQUA));
        data.changed();
    }

    /** Everyone of a work at home at once (the ways all made by command). */
    static void callHome(VillageData data, Work w) {
        for (Side s : new Side[]{w.sa, w.sb}) if (s.state != HOME && s.state != WAITING) home(data, w, s);
    }

    /** A way done with (both crews home, no tent left standing) is forgotten. */
    private static void settle(ServerLevel level, VillageData data, Work w) {
        if (w.finished >= 0 && w.sa.state == HOME && w.sb.state == HOME && w.sa.tent == null && w.sb.tent == null) {
            data.works.remove(w);
            data.changed();
        }
    }

    /** A village gone: its people come home (the other's), the tents come down when they can. */
    private static void abandon(ServerLevel level, VillageData data, Work w) {
        for (Side s : new Side[]{w.sa, w.sb}) {
            strike(level, data, w, s);
            if (s.state != HOME && s.state != WAITING) home(data, w, s);
            s.state = HOME;
        }
        if (w.sa.tent == null && w.sb.tent == null) data.works.remove(w);
        data.changed();
    }

    // ------------------------------------------------------------------ the clock

    /** The share of the day gone by (0 at sunrise). */
    static double time(VillageData data) {
        return data.dayTicks / (double) data.dayLength;
    }

    /** Is it the working day now (else: evening and night, when the crews camp and sleep). */
    public static boolean working(VillageData data) {
        double t = time(data);
        return t >= WORK_FROM && t < WORK_TO;
    }

    /** Seconds of work in a working day. */
    private static double workSeconds(VillageData data) {
        return (WORK_TO - WORK_FROM) * data.dayLength / 20.0;
    }

    /**
     * Every second: in the working day the crews walk and work (a share of a day's worth each second; where their
     * people are in the world, only while they are at the end of the way); at dusk they camp, in the morning they
     * strike the tents; a tent is pitched or struck when its land is loaded.
     */
    static void tick(ServerLevel level, VillageData data) {
        boolean day = working(data);
        double share = 1.0 / workSeconds(data);
        for (Work w : new ArrayList<>(data.works)) {
            for (Side s : new Side[]{w.sa, w.sb}) {
                bodies(level, data, w, s, day);
                if (day) {
                    if (s.camp != null || s.tent != null) {
                        strike(level, data, w, s);
                        s.camp = null;
                    }
                    // (where its people are in the world, the work goes on only while they are at it; the pace of a
                    // day's work counts the walks from stretch to stretch, so at it they get on half again as fast)
                    if (s.state == WORKING && present(w, s)) {
                        if (!atWork(level, w, s)) continue;
                        progress(level, data, w, s, share * 1.5);
                        continue;
                    }
                    progress(level, data, w, s, share);
                } else {
                    if (s.state == WORKING && s.camp == null) camp(level, data, w, s);
                    if (s.camp != null && s.tent == null) pitch(level, data, w, s);
                }
                // (a tent left standing where the crew is gone: down as soon as its land is loaded)
                if (s.camp == null && s.tent != null) strike(level, data, w, s);
            }
            if (w.finished < 0 && w.done()) finish(level, data, w);
            settle(level, data, w);
        }
    }

    // ------------------------------------------------------------------ the camp

    /** Evening: the crew stops where it got to and camps by the way (a tent is pitched if its land is loaded). */
    private static void camp(ServerLevel level, VillageData data, Work w, Side s) {
        double d = w.front(s);
        int[] p = w.at(d);
        double h = Math.toRadians(w.heading(d) + 90);
        // beside the way, off the cutting
        s.camp = new int[]{p[0] + (int) Math.round(Math.cos(h) * 8), p[1] + (int) Math.round(Math.sin(h) * 8)};
        pitch(level, data, w, s);
        data.changed();
    }

    /** The tent, if its land is loaded: on a patch of level, dry, clear ground near the camp. */
    private static void pitch(ServerLevel level, VillageData data, Work w, Side s) {
        if (s.camp == null || s.tent != null) return;
        Village v = data.get(s.village);
        if (v == null || !level.hasChunkAt(new BlockPos(s.camp[0], 0, s.camp[1]))) return;
        double h = Math.toRadians(w.heading(w.front(s)) + 90);
        // the nearest good ground: first across the way from the camp, then in rings round it (a lake, a wood, a
        // slope where it stopped)
        java.util.List<int[]> tries = new java.util.ArrayList<>();
        for (int r = 0; r <= 8; r += 2) for (int side : new int[]{1, -1}) tries.add(new int[]{
                s.camp[0] + (int) Math.round(Math.cos(h) * r * side), s.camp[1] + (int) Math.round(Math.sin(h) * r * side), side});
        for (int r = 4; r <= 16; r += 3) {
            for (int a = 0; a < 12; a++) {
                double ang = a * Math.PI / 6;
                tries.add(new int[]{s.camp[0] + (int) Math.round(Math.cos(ang) * r), s.camp[1] + (int) Math.round(Math.sin(ang) * r), 1});
            }
        }
        // (open ground first; failing that, ground under trees, which the crew clears)
        for (int pass = 0; pass < 2; pass++) for (int[] t : tries) {
            {
                int x = t[0], z = t[1], side = t[2];
                Integer y = site(level, x, z, pass == 1);
                if (y == null) continue;
                if (pass == 1) {
                    int logs = clearCamp(level, new BlockPos(x, y, z));
                    if (logs > 0) {
                        v.add(Res.WOOD, logs);
                        v.made.merge(Res.WOOD, logs, Integer::sum);
                    }
                }
                // the door towards the way
                Direction facing = Direction.fromYRot(Math.toDegrees(h) + (side > 0 ? 90 : -90));
                if (facing.getAxis() == Direction.Axis.Y) facing = Direction.NORTH;
                int seed = (int) (w.planned * 31 + s.village * 7 + x);
                Blueprint bp = Blueprint.of(BuildingType.TENT, new Blueprint.Frame(new BlockPos(x, y, z), facing), v.wood, seed, null);
                for (Blueprint.Piece piece : bp.pieces) {
                    if (level.getBlockState(piece.pos()).canBeReplaced()) level.setBlock(piece.pos(), piece.state(), Block.UPDATE_ALL);
                }
                s.tent = new int[]{x, y, z, facing.get2DDataValue(), seed};
                data.changed();
                return;
            }
        }
    }

    /**
     * Where a tent can stand (the floor it would stand on), or null: loaded, dry, level, nothing built; with
     * {@code underTrees}, ground with trees on it too (the crew fells them first), else only open ground.
     */
    private static Integer site(ServerLevel level, int x, int z, boolean underTrees) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos col = new BlockPos(x + dx, 0, z + dz);
                if (!level.hasChunkAt(col)) return null;
                // (the ground itself, trees and bushes aside)
                int top = Trails.groundAt(level, x + dx, z + dz);
                BlockState st = level.getBlockState(new BlockPos(x + dx, top, z + dz));
                if (!st.getFluidState().isEmpty() || st.is(BlockTags.LOGS) || st.is(BlockTags.PLANKS) || st.is(Blocks.DIRT_PATH) || st.is(BlockTags.FENCES)
                        || st.is(BlockTags.BEDS) || st.is(BlockTags.WOOL)) return null;
                for (int k = 1; k <= 4; k++) {
                    BlockState up = level.getBlockState(new BlockPos(x + dx, top + k, z + dz));
                    boolean growing = up.is(BlockTags.LOGS) || up.is(BlockTags.LEAVES) || up.is(Blocks.VINE);
                    if (growing && !underTrees) return null;
                    if (!up.canBeReplaced() && !growing) return null;
                }
                lo = Math.min(lo, top);
                hi = Math.max(hi, top);
            }
        }
        return hi - lo <= 1 ? hi + 1 : null;
    }

    /**
     * The crew clears its camp ground before the tent goes up: the trees on it felled whole, the leaves and bushes
     * over it cut. Returns the logs felled (they go to the village's store).
     */
    private static int clearCamp(ServerLevel level, BlockPos floor) {
        int logs = Construction.clearTrees(level, floor, 4);
        for (BlockPos p : BlockPos.betweenClosed(floor.offset(-3, 0, -3), floor.offset(3, 8, 3))) {
            BlockState st = level.getBlockState(p);
            if (st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS) || st.is(Blocks.VINE) || !st.isAir() && st.canBeReplaced() && st.getFluidState().isEmpty()) {
                if (st.is(BlockTags.LOGS)) logs++;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        return logs;
    }

    /** The tent comes down (if its land is loaded; else it waits till it is). */
    private static void strike(ServerLevel level, VillageData data, Work w, Side s) {
        if (s.tent == null) return;
        BlockPos o = new BlockPos(s.tent[0], s.tent[1], s.tent[2]);
        if (!level.hasChunkAt(o)) return;
        Village v = data.get(s.village);
        Blueprint bp = Blueprint.of(BuildingType.TENT, new Blueprint.Frame(o, Direction.from2DDataValue(s.tent[3])), v == null ? "oak" : v.wood, s.tent[4], null);
        for (Blueprint.Piece piece : bp.pieces) {
            if (level.getBlockState(piece.pos()).is(piece.state().getBlock())) {
                level.setBlock(piece.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
        s.tent = null;
        data.changed();
    }

    /** The bed a crew member sleeps in tonight (the tent's n-th), or null. */
    static BlockPos bed(ServerLevel level, VillageData data, Village v, Dweller d) {
        Work w = workOf(data, v, d);
        Side s = w == null ? null : w.side(v.id);
        if (s == null || s.tent == null) return null;
        Blueprint bp = Blueprint.of(BuildingType.TENT, new Blueprint.Frame(new BlockPos(s.tent[0], s.tent[1], s.tent[2]),
                Direction.from2DDataValue(s.tent[3])), v.wood, s.tent[4], null);
        int n = Math.max(0, s.crew.indexOf(d.id));
        return bp.beds.isEmpty() ? null : bp.beds.get(Math.min(n, bp.beds.size() - 1));
    }

    // ------------------------------------------------------------------ the crews in the world

    private static final Map<Long, ResidentEntity> BODIES = new HashMap<>();
    /** Where crew members were stuck on the way to their work, and were put at it (tests: it should hardly ever happen). */
    private static final List<BlockPos> SNAPS = new ArrayList<>();

    public static List<BlockPos> snaps() {
        return List.copyOf(SNAPS);
    }
    /** Since when (game time) a crew's people have been kept from the end of the way (not getting there). */
    private static final Map<Long, long[]> HELD = new HashMap<>();

    static void reset() {
        BODIES.clear();
        HELD.clear();
        SNAPS.clear();
    }

    private static long key(int village, int dweller) {
        return village * 1_000_000L + dweller;
    }

    /** The work a crew member is on, or null. */
    static Work workOf(VillageData data, Village v, Dweller d) {
        if (v == null || d == null || !d.away) return null;
        for (Work w : data.works) {
            Side s = w.side(v.id);
            if (s != null && s.crew.contains(d.id) && (s.state == WORKING || s.state == GOING)) return w;
        }
        return null;
    }

    /** Is this person at the end of a way with a crew (working there by day, camping by it at night)? */
    static boolean atWay(VillageData data, Village v, Dweller d) {
        Work w = workOf(data, v, d);
        Side s = w == null ? null : w.side(v.id);
        return s != null && s.state == WORKING;
    }

    /** Is this person away on a crew (at the end of the way, or on the way there or home)? */
    static boolean onCrew(VillageData data, Village v, Dweller d) {
        if (v == null || d == null || !d.away) return false;
        for (Work w : data.works) {
            Side s = w.side(v.id);
            if (s != null && s.crew.contains(d.id) && s.state != HOME) return true;
        }
        return false;
    }

    /** Where a crew member works (a little apart from the other), or null. */
    static BlockPos front(ServerLevel level, VillageData data, Village v, Dweller d) {
        Work w = workOf(data, v, d);
        Side s = w == null ? null : w.side(v.id);
        if (s == null || s.state != WORKING) return null;
        // (the stretch in hand: the trees to fell stand there; they stay at it until it is made. Water there, or a
        // gap (a pit, a quarry, a ravine): they work from the end of what is made, the bank or the edge, or the deck
        // of the bridge as far as it is laid)
        double at = w.stretch(s);
        int[] p = w.at(at);
        if (level.hasChunkAt(new BlockPos(p[0], 0, p[1]))) {
            double end = w.front(s);
            double stand = standing(level, w, s, end);
            int[] q = w.at(stand);
            if (wet(level, p[0], p[1]) || Trails.groundAt(level, p[0], p[1]) <= Trails.groundAt(level, q[0], q[1]) - Trails.GAP) {
                at = stand;
                p = q;
            }
        }
        // (side by side, within the way's three blocks: on a bridge, on its deck)
        double h = Math.toRadians(w.heading(at) + 90);
        int n = s.crew.indexOf(d.id);
        int off = n == 0 ? -1 : 1;
        int x = p[0] + (int) Math.round(Math.cos(h) * off), z = p[1] + (int) Math.round(Math.sin(h) * off);
        if (!level.hasChunkAt(new BlockPos(x, 0, z))) return null;
        return new BlockPos(x, PlotFinder.floorAt(level, x, z), z);
    }

    /** A crew member's place in his crew (0 the first), or -1. */
    static int crewIndex(VillageData data, Village v, Dweller d) {
        Work w = workOf(data, v, d);
        Side s = w == null ? null : w.side(v.id);
        return s == null ? -1 : s.crew.indexOf(d.id);
    }

    /**
     * What a crew member works at: the ground a little ahead of the end of what is made (by his side of the way), or
     * the end of the deck where a bridge goes out over water or a gap. Null if nothing is loaded there.
     */
    static BlockPos workBlock(ServerLevel level, VillageData data, Village v, Dweller d) {
        Work w = workOf(data, v, d);
        Side s = w == null ? null : w.side(v.id);
        if (s == null || s.state != WORKING) return null;
        double dir = s == w.sa ? 1 : -1, end = w.front(s);
        double ahead = Math.max(0, Math.min(w.length(), end + dir * 2));
        int[] p = w.at(ahead);
        double h = Math.toRadians(w.heading(ahead) + 90);
        int off = s.crew.indexOf(d.id) == 0 ? -1 : 1;
        int x = p[0] + (int) Math.round(Math.cos(h) * off), z = p[1] + (int) Math.round(Math.sin(h) * off);
        if (!level.hasChunkAt(new BlockPos(x, 0, z))) return null;
        if (wet(level, x, z)) {
            // over water: the end of the deck
            int[] q = w.at(standing(level, w, s, end));
            return new BlockPos(q[0], PlotFinder.floorAt(level, q[0], q[1]) - 1, q[1]);
        }
        return new BlockPos(x, Trails.groundAt(level, x, z), z);
    }

    /**
     * The next few steps of a crew member towards where he works: along the way made (so over its bridges, never
     * down into the gap or the water beside them); first back onto the way if he is off it.
     */
    static BlockPos step(ServerLevel level, VillageData data, Village v, Dweller d, double ex, double ez) {
        BlockPos to = front(level, data, v, d);
        Work w = workOf(data, v, d);
        Side s = w == null ? null : w.side(v.id);
        if (to == null || s == null) return to;
        if (Math.hypot(ex - to.getX() - 0.5, ez - to.getZ() - 0.5) <= 6) return to;
        // where along the way he is (looking along what is made, near its end)
        double end = w.front(s), best = Double.MAX_VALUE, along = end;
        double lo = s == w.sa ? Math.max(0, end - 60) : end - 10, hi = s == w.sa ? end + 10 : Math.min(w.length(), end + 60);
        for (double a = Math.max(0, lo); a <= Math.min(w.length(), hi); a += 1) {
            int[] q = w.at(a);
            double dd = Math.hypot(ex - q[0], ez - q[1]);
            if (dd < best) {
                best = dd;
                along = a;
            }
        }
        int[] q;
        if (best > 3) {
            // off the way: back onto it
            q = w.at(along);
        } else {
            // on it: a few blocks on towards the work
            double target = s == w.sa ? Math.min(end, along + 4) : Math.max(end, along - 4);
            q = w.at(target);
        }
        if (!level.hasChunkAt(new BlockPos(q[0], 0, q[1]))) return to;
        return new BlockPos(q[0], PlotFinder.floorAt(level, q[0], q[1]), q[1]);
    }

    /** The name of the village the way goes to, for a crew member's activity. */
    static String towards(VillageData data, Village v, Dweller d) {
        Work w = workOf(data, v, d);
        if (w == null) return "";
        Village o = data.get(w.a == v.id ? w.b : w.a);
        return o == null ? "" : o.name;
    }

    private static boolean playerNear(ServerLevel level, int x, int z, int r) {
        for (var pl : level.players()) if (Math.hypot(pl.getX() - x, pl.getZ() - z) < r) return true;
        return false;
    }

    /** Are a side's people in the world now? */
    private static boolean present(Work w, Side s) {
        for (int id : s.crew) {
            ResidentEntity e = BODIES.get(key(s.village, id));
            if (e != null && !e.isRemoved()) return true;
        }
        return false;
    }

    /** Is any of a side's people (in the world) at the stretch in hand (or at the end of what is made: a bridge is built from the bank)? */
    private static boolean atWork(ServerLevel level, Work w, Side s) {
        int[] p = w.at(w.stretch(s)), q = w.at(w.front(s)), r = w.at(standing(level, w, s, w.front(s)));
        for (int id : s.crew) {
            ResidentEntity e = BODIES.get(key(s.village, id));
            if (e == null || e.isRemoved()) continue;
            if (Math.hypot(e.getX() - p[0], e.getZ() - p[1]) <= AT_WORK || Math.hypot(e.getX() - q[0], e.getZ() - q[1]) <= AT_WORK
                    || Math.hypot(e.getX() - r[0], e.getZ() - r[1]) <= AT_WORK) return true;
        }
        return false;
    }

    /**
     * Where to stand at the end of what is made: the end itself, or back along it to the first place with a footing
     * (a deck already laid, or dry ground not fallen away below the way behind it).
     */
    private static double standing(ServerLevel level, Work w, Side s, double end) {
        double step = s == w.sa ? -1 : 1;
        for (int k = 0; k < 16; k++) {
            double d = end + step * k;
            if (d < 0 || d > w.length()) break;
            int[] q = w.at(d);
            if (!level.hasChunkAt(new BlockPos(q[0], 0, q[1]))) return end;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, q[0], q[1]) - 1;
            if (level.getBlockState(new BlockPos(q[0], top, q[1])).is(BlockTags.PLANKS)) return d;
            if (wet(level, q[0], q[1])) continue;
            // (not down in a hole: about as high as the way made behind it, the highest of it within twelve blocks)
            int ref = Integer.MIN_VALUE;
            for (int b = 2; b <= 12; b += 2) {
                double e = d + step * b;
                if (e < 0 || e > w.length()) break;
                int[] r = w.at(e);
                if (level.hasChunkAt(new BlockPos(r[0], 0, r[1]))) ref = Math.max(ref, Trails.groundAt(level, r[0], r[1]));
            }
            if (ref == Integer.MIN_VALUE || Trails.groundAt(level, q[0], q[1]) > ref - Trails.GAP) return d;
        }
        return end;
    }

    /** Is a crew member in the world away from where he works (the stretch in hand, or where he stands to make it)? */
    private static boolean far(ServerLevel level, VillageData data, Village v, Dweller d, ResidentEntity e) {
        BlockPos to = front(level, data, v, d);
        return to != null && Math.hypot(e.getX() - to.getX() - 0.5, e.getZ() - to.getZ() - 0.5) > AT_WORK;
    }

    /** Is there water at the top of this column (loaded)? */
    private static boolean wet(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        return !level.getBlockState(new BlockPos(x, top, z)).getFluidState().isEmpty();
    }

    /**
     * A side's people in the world where a player is near: at the end of the way by day, at their camp by night; gone
     * again (on unseen) when no one is near. One killed is lost to the village.
     */
    private static void bodies(ServerLevel level, VillageData data, Work w, Side s, boolean day) {
        Village v = data.get(s.village);
        if (v == null) return;
        boolean out = s.state == WORKING && (day || s.camp != null);
        int[] where = !out ? null : day ? w.at(w.front(s)) : s.camp;
        for (int id : new ArrayList<>(s.crew)) {
            long k = key(s.village, id);
            ResidentEntity e = BODIES.get(k);
            Dweller d = v.dweller(id);
            if (e != null && e.isRemoved()) {
                BODIES.remove(k);
                if (e.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.KILLED && d != null) {
                    lost(data, w, s, v, d);
                    continue;
                }
                e = null;
            }
            if (d == null) {
                s.crew.remove((Integer) id);
                continue;
            }
            if (e != null) {
                // (a copy of him saved with the world: there is only one of him)
                Caravans.single(level, e, s.village, id);
                if (where == null || !level.isPositionEntityTicking(e.blockPosition()) || !playerNear(level, e.getBlockX(), e.getBlockZ(), SEEN + 32)) {
                    e.discard();
                    BODIES.remove(k);
                    HELD.remove(k);
                } else if (day && d != null && far(level, data, v, d, e)) {
                    // kept from the stretch in hand (no path to it: water, a pit, a cliff): not getting any nearer for ten
                    // seconds, there all the same
                    long[] held = HELD.get(k);
                    if (held == null) HELD.put(k, held = new long[]{level.getGameTime(), e.blockPosition().asLong()});
                    if (BlockPos.of(held[1]).distManhattan(e.blockPosition()) > 2) {
                        held[0] = level.getGameTime();
                        held[1] = e.blockPosition().asLong();
                    } else if (level.getGameTime() - held[0] > 200) {
                        BlockPos to = front(level, data, v, d);
                        // (there after all: the stretch in hand moved on meanwhile)
                        if (to != null && Math.hypot(e.getX() - to.getX() - 0.5, e.getZ() - to.getZ() - 0.5) <= AT_WORK) to = null;
                        if (to != null) {
                            BlockPos was = e.blockPosition();
                            e.getNavigation().stop();
                            VillageManager.snap(level, e, to, "crew stuck");
                            SNAPS.add(was);
                            Minecraftportsmod.LOGGER.info("Crew member {} of {} stuck at {} ({}): put at {}", d.name, v.name, was.toShortString(),
                                    level.getBlockState(was).getBlock(), to.toShortString());
                        }
                        HELD.remove(k);
                    }
                } else {
                    HELD.remove(k);
                }
                continue;
            }
            if (where == null || !playerNear(level, where[0], where[1], SEEN)) continue;
            // (still in the world from home: the end of the way is by the village; that body is theirs)
            if (d.body != null && level.getEntity(d.body) instanceof ResidentEntity mine && mine.isAlive()) {
                BODIES.put(k, mine);
                continue;
            }
            BlockPos at = new BlockPos(where[0], 0, where[1]);
            if (!level.hasChunkAt(at) || !level.isPositionEntityTicking(at)) continue;
            var body = org.webtrade.minecraftportsmod.registry.ModContent.RESIDENT.create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
            if (body == null) continue;
            // (by day a few steps behind the end of the way, on what is made: they walk up to it)
            int[] p = day ? w.at(s == w.sa ? Math.max(0, w.front(s) - 6) : Math.min(w.length(), w.front(s) + 6)) : where;
            BlockPos stand = PlotFinder.ground(level, p[0], p[1]);
            body.snapTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
            body.syncColony(d, v, data.day);
            level.addFreshEntity(body);
            d.body = body.getUUID();
            BODIES.put(k, body);
        }
    }

    /** Killed at the work: the worker is gone. */
    private static void lost(VillageData data, Work w, Side s, Village v, Dweller d) {
        s.crew.remove((Integer) d.id);
        v.dwellers.remove(d);
        Village other = data.get(w.a == v.id ? w.b : w.a);
        v.log(data.day, Component.translatable("minecraftportsmod.vlog.road_lost", d.name, other == null ? "?" : other.name).withStyle(ChatFormatting.RED));
        VillageLife.rehouse(v, data.day);
        // (no one left of the crew: the work stops until the village sends another)
        if (s.crew.isEmpty() && s.state != HOME) {
            s.state = WAITING;
            s.camp = null;
        }
        data.changed();
    }
}
