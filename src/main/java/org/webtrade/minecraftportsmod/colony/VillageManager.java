package org.webtrade.minecraftportsmod.colony;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.AABB;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.registry.ModContent;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the villages: the village clock (a day of village life per Minecraft day), and for the villages players
 * are near, the world side of it — blocks going up where people build, construction plots outlined, the bodies of
 * the people kept in line with the village's records.
 */
public final class VillageManager {

    /** A village counts as watched while a player is this close to its middle. */
    public static final int WATCH_RANGE = 128;
    /** Blocks a catching-up building site may change per upkeep (every 10 ticks). */
    private static final int CATCH_UP = 12;
    /** Construction plots are outlined for players this close. */
    private static final int OUTLINE_RANGE = 48;

    private static final RandomSource RND = RandomSource.create();

    private VillageManager() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(org.webtrade.minecraftportsmod.Perf.timed("Villages", VillageManager::tick));
        // crouching at a building site, using its blocks: building by hand
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (hand != net.minecraft.world.InteractionHand.MAIN_HAND || !(player instanceof net.minecraft.server.level.ServerPlayer sp)
                    || !(world instanceof ServerLevel sl)) return net.minecraft.world.InteractionResult.PASS;
            return Helping.build(sp, sl, hit.getBlockPos()) ? net.minecraft.world.InteractionResult.SUCCESS : net.minecraft.world.InteractionResult.PASS;
        });
        // a boundary stone: only its owner takes it up
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) ->
                !state.is(org.webtrade.minecraftportsmod.registry.ModContent.PLOT_MARKER) || !(player instanceof net.minecraft.server.level.ServerPlayer sp)
                        || !(world instanceof ServerLevel sl) || Plots.mayBreak(sp, sl, pos));
        // a monster killed by a player: the hunts the villages round there asked for
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof net.minecraft.world.entity.monster.Enemy && source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
                Quests.killed(p, entity.blockPosition());
            }
        });
        // what was learnt about one world's land means nothing in the next one
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(srv -> {
            lastTimeOfDay = -1;
            DwellerGoals.clearCaches();
            Tidy.clear();
            Territory.clear();
            Reach.clear();
            PlotFinder.clear();
            PATH_RETRY.clear();
            // (worked out on other threads, keyed by the villages' ids: they would be taken for the next world's)
            Trails.reset();
            Scouting.reset();
            VillageTerrain.reset();
            Caravans.reset();
            Voyages.reset();
            Roadworks.reset();
            Land.reset();
            Plots.clear();
            Helping.clear();
        });
    }

    /** Time of day seen last tick (for the sunrise), -1 before the first tick. */
    private static long lastTimeOfDay = -1;

    private static void tick(MinecraftServer srv) {
        VillageData data = VillageData.get(srv);
        if (data.all().isEmpty()) return;
        ServerLevel level = srv.overworld();
        Plots.tick(srv);
        Helping.tick(srv);
        if (srv.getTickCount() % 10 == 0) Wallet.tick(srv);
        Badges.tick(srv);
        if (data.dayLength == VillageData.DEFAULT_DAY_LENGTH) {
            // a village day is a Minecraft day: it starts at sunrise (and when the time is set back)
            long tod = Math.floorMod(level.getOverworldClockTime(), 24000L);
            if (lastTimeOfDay >= 0 && tod < lastTimeOfDay) newDay(level, data, false);
            lastTimeOfDay = tod;
            data.dayTicks = (int) tod;
        } else {
            data.dayTicks++;
            if (data.dayTicks >= data.dayLength) {
                data.dayTicks = 0;
                newDay(level, data, false);
            }
        }
        int t = srv.getTickCount();
        if (t % 20 == 0) {
            try {
                Caravans.tick(level, data);
            } catch (Throwable e) {
                Minecraftportsmod.LOGGER.error("Caravans failed", e);
            }
            try {
                Roadworks.tick(level, data);
            } catch (Throwable e) {
                Minecraftportsmod.LOGGER.error("Roadworks failed", e);
            }
            try {
                Voyages.tick(level, data);
            } catch (Throwable e) {
                Minecraftportsmod.LOGGER.error("Voyages failed", e);
            }
            try {
                Workshops.tick(level, data);
            } catch (Throwable e) {
                Minecraftportsmod.LOGGER.error("Workshops failed", e);
            }
        }
        if (t % 100 == 0) {
            try {
                Trails.tick(level, data);
            } catch (Throwable e) {
                Minecraftportsmod.LOGGER.error("Trails failed", e);
            }
        }
        if (t % 10 == 0) {
            for (Village v : data.all()) {
                if (!watched(level, v)) continue;
                upkeep(level, data, v);
                if (t % 40 == 0) bodies(level, data, v);
                if (t % 200 == 0) Greening.step(level, v, 60);
                if (t % 100 == 0) Tidy.survey(level, v, RND, 120);
                if (t % 20 == 0) Tidy.catchUp(level, v, RND);
                if (t % 200 == 100) Herds.step(level, v);
            }
        }
    }

    /** A new day for every village. */
    static void newDay(ServerLevel level, VillageData data, boolean skip) {
        data.day++;
        for (Village v : new ArrayList<>(data.all())) {
            try {
                boolean seen = !skip && watched(level, v);
                // (a day skipped: the workshops' day of work at once, before the new day plans the next)
                if (skip) Workshops.skipDay(data, v);
                VillageLife.day(level, data, v, seen);
                if (!seen) Tidy.owe(v, v.adults());
            } catch (Throwable e) {
                Minecraftportsmod.LOGGER.error("Village {} day failed", v.name, e);
            }
        }
        try {
            Roadworks.day(level, data, skip);
        } catch (Throwable e) {
            Minecraftportsmod.LOGGER.error("Roadworks failed", e);
        }
        try {
            Caravans.day(level, data, skip);
        } catch (Throwable e) {
            Minecraftportsmod.LOGGER.error("Caravans failed", e);
        }
        try {
            Voyages.day(level, data, skip);
        } catch (Throwable e) {
            Minecraftportsmod.LOGGER.error("Voyages failed", e);
        }
        data.changed();
    }

    /**
     * Skips to the next day at once (command, tests). The day's building work is done as if nobody watched, and the
     * blocks catch up in the world right after.
     */
    public static void advanceDay(MinecraftServer srv) {
        VillageData data = VillageData.get(srv);
        data.dayTicks = 0;
        newDay(srv.overworld(), data, true);
    }

    /** Two villages come to know of each other, and a trail between them is worked out (command, tests). */
    public static void meet(MinecraftServer srv, Village v, Village o) {
        VillageData data = VillageData.get(srv);
        v.known.put(o.id, data.day);
        o.known.put(v.id, data.day);
        // (a "no way" found before is looked for again)
        var old = data.trails.get(Trails.key(v.id, o.id));
        if (old != null && old.none()) data.trails.remove(Trails.key(v.id, o.id));
        Trails.plan(srv.overworld(), data, v, o);
    }

    /** A building site of this type with all its materials there, going up now (tests). Returns it, or null if no room. */
    public static Building siteNow(ServerLevel level, Village v, BuildingType type) {
        long today = VillageData.get(level.getServer()).day;
        if (!VillageLife.start(level, v, type, today)) return null;
        Building b = null;
        for (Building x : v.buildings) if (x.type == type && x.state == Building.State.PLANNED) b = x;
        if (b == null) return null;
        for (Res r : Res.values()) {
            int n = b.missing(r);
            if (n > 0) {
                v.add(r, n);
                VillageLife.deliver(v, b, r, n);
            }
        }
        VillageData.get(level.getServer()).changed();
        return b;
    }

    /** Sets how the village builds from now on (tests, commands). */
    public static void style(MinecraftServer srv, Village v, int style) {
        v.style = style;
        VillageData.get(srv).changed();
    }

    public static void give(MinecraftServer srv, Village v, Res r, int n) {
        v.add(r, n);
        VillageData.get(srv).changed();
    }

    /** Puts a building up at once on a free plot (command, tests). Returns it, or null if there is no room. */
    public static Building buildNow(ServerLevel level, Village v, BuildingType type) {
        Blueprint.Frame f = PlotFinder.find(level, v, type, -1);
        if (f == null) return null;
        Building b = new Building(v.nextBuilding++, type, f.origin(), f.front(), RND.nextLong()).look(VillageLife.look(v));
        b.created = VillageData.get(level.getServer()).day;
        if (type.isNode() && !type.free) v.unlocked.add(type);
        standUp(level, v, b);
        VillageLife.rehouse(v, b.created);
        VillageData.get(level.getServer()).changed();
        return b;
    }

    /** Puts a building up at once, already grown to a level (command, tests). */
    public static Building buildNow(ServerLevel level, Village v, BuildingType type, int lv) {
        Blueprint.Frame f = PlotFinder.find(level, v, type, -1);
        if (f == null) return null;
        Building b = new Building(v.nextBuilding++, type, f.origin(), f.front(), RND.nextLong()).look(VillageLife.look(v));
        b.level = Math.max(1, Math.min(type.maxLevel, lv));
        b.created = VillageData.get(level.getServer()).day;
        if (type.isNode() && !type.free) v.unlocked.add(type);
        standUp(level, v, b);
        VillageLife.rehouse(v, b.created);
        VillageData.get(level.getServer()).changed();
        return b;
    }

    /** Opens a node of the tree (command, tests); {@code pay}: from the store, as the village would. */
    public static boolean unlock(MinecraftServer srv, Village v, BuildingType t, boolean pay) {
        boolean ok;
        if (pay) ok = Tree.unlock(v, t);
        else ok = t.isNode() && v.unlocked.add(t);
        if (ok) VillageData.get(srv).changed();
        return ok;
    }

    public static void focus(MinecraftServer srv, Village v, BuildingType.Branch br) {
        v.focus = br;
        if (v.sub == null || v.sub.branch != br) v.sub = BuildingType.Sub.first(br);
        VillageData.get(srv).changed();
    }

    /** Makes a sub-branch (and its branch) what a village is known for (command, tests). */
    public static void sub(MinecraftServer srv, Village v, BuildingType.Sub s) {
        v.focus = s.branch;
        v.sub = s;
        VillageData.get(srv).changed();
    }

    /** Wears a trade's tools right now by {@code worn} of a tool each (tests). */
    public static void wearNow(MinecraftServer srv, Village v, Job job, double worn) {
        VillageData data = VillageData.get(srv);
        v.wear.merge(job, worn, Double::sum);
        VillageLife.wear(v, data.day);
        data.changed();
    }

    /** Starts raising a building a level (command, tests). */
    public static boolean raise(MinecraftServer srv, Village v, Building b) {
        VillageData data = VillageData.get(srv);
        boolean ok = Tree.raise(v, b, data.day);
        if (ok) data.changed();
        return ok;
    }

    /** Someone new with a given trade (command, tests). */
    public static Dweller newcomer(MinecraftServer srv, Village v, Job job) {
        Dweller d = newcomer(srv, v);
        if (job != null) d.job = job;
        return d;
    }

    /** A baby is born (command, tests). */
    public static Dweller baby(MinecraftServer srv, Village v) {
        VillageData data = VillageData.get(srv);
        Dweller d = VillageLife.addPerson(v, data.day, true);
        data.changed();
        return d;
    }

    /** Someone new walks into the village (command, tests). */
    public static Dweller newcomer(MinecraftServer srv, Village v) {
        VillageData data = VillageData.get(srv);
        Dweller d = VillageLife.addPerson(v, data.day, false);
        data.changed();
        return d;
    }

    public static boolean watched(ServerLevel level, Village v) {
        if (!Construction.loaded(level, v.center)) return false;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - v.center.getX(), dz = p.getZ() - v.center.getZ();
            if (dx * dx + dz * dz < WATCH_RANGE * WATCH_RANGE) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the world side

    /**
     * Does this building have a post at its corner (a site's plan, or a finished building's name plate)? Tents and
     * the camp fire don't; while a building is being rebuilt into its next step, the post is the new site's.
     */
    private static boolean wantsPost(Village v, Building b) {
        if (b.state == Building.State.DEMOLISHING || b.type == BuildingType.TENT || b.type == BuildingType.CAMPFIRE) return false;
        for (Building o : v.buildings) if (o.replaces == b.id && o.state != Building.State.BUILT) return false;
        return true;
    }

    /** When each building whose path could not be laid is tried again (game time). */
    private static final Map<Long, Long> PATH_RETRY = new HashMap<>();

    private static long pathKey(Village v, Building b) {
        return ((long) v.id << 32) | (b.id & 0xFFFFFFFFL);
    }

    /** A building with no path at its door any more (or never had one): checked now and then. */
    private static boolean relink(ServerLevel level, Village v, Building b) {
        if (b.type.isCenter() || (level.getGameTime() / 10 + b.id * 7L) % 60 != 0) return false;
        return !Paths.connected(level, v, Paths.doorstep(v, b));
    }

    private static void upkeep(ServerLevel level, VillageData data, Village v) {
        java.util.Set<BlockPos> posts = new java.util.HashSet<>();
        for (Building b : v.buildings) if (wantsPost(v, b)) posts.add(b.blueprint(v.wood).post);
        for (Building b : new ArrayList<>(v.buildings)) {
            if (!Construction.plotLoaded(level, b)) continue;
            // a building taking the place of another waits until the old one is gone
            if (b.replaces >= 0 && v.building(b.replaces) != null && b.state != Building.State.PLANNED) continue;
            // a site across the water from the square: the bridge (and the path over it) goes up first
            if (b.state == Building.State.PLANNED && !b.paths && !b.type.isCenter()) {
                BlockPos door = Paths.doorstep(v, b);
                Long next = PATH_RETRY.get(pathKey(v, b));
                if ((next == null || level.getGameTime() >= next)
                        && !Reach.ok(level, v, new BlockPos(door.getX(), PlotFinder.floorAt(level, door.getX(), door.getZ()), door.getZ()))) {
                    if (Paths.lay(level, v, b)) {
                        b.paths = true;
                        Reach.forget(v);
                        data.changed();
                    } else {
                        PATH_RETRY.put(pathKey(v, b), level.getGameTime() + 1200);
                    }
                }
            }
            Construction.advance(level, v, b, CATCH_UP);
            if (b.state == Building.State.BUILT && b.placed >= b.blueprint(v.wood).pieces.size() && (!b.paths || relink(level, v, b))) {
                // (no way found now: tried again a little later)
                long now = level.getGameTime();
                Long next = PATH_RETRY.get(pathKey(v, b));
                if (next == null || now >= next) {
                    boolean ok = Paths.lay(level, v, b);
                    if (ok) PATH_RETRY.remove(pathKey(v, b));
                    else PATH_RETRY.put(pathKey(v, b), now + 1200);
                    b.paths = true;
                    data.changed();
                }
            }
            // a building site's post, and later the building's name plate; a post no one wants any more comes away
            if (wantsPost(v, b)) Construction.post(level, v, b, true);
            else if (!posts.contains(b.blueprint(v.wood).post)) Construction.post(level, v, b, false);
            if (b.state == Building.State.DEMOLISHING && b.finished && b.placed == 0) {
                Construction.restoreGround(level, b.blueprint(v.wood));
                v.buildings.remove(b);
                // the land round the neighbours could not be eased while this stood: now it can
                for (Building o : v.buildings) {
                    if (o.standing() && o.levelled && o.overlaps(b.origin, b.type.half, 4)) Construction.blend(level, v, o);
                }
                data.changed();
            }
        }
        outline(level, v);
        Plots.paths(level, v);
    }

    /** Glowing outlines around building plots: gold for a planned one, green while it goes up, red coming down. */
    private static void outline(ServerLevel level, Village v) {
        List<ServerPlayer> near = new ArrayList<>();
        for (ServerPlayer p : level.players()) if (p.blockPosition().distSqr(v.center) < 90 * 90) near.add(p);
        if (near.isEmpty()) return;
        for (Building b : v.buildings) {
            if (b.state == Building.State.BUILT || b.state == Building.State.DEMOLISHING && b.finished) continue;
            int color = switch (b.state) {
                case PLANNED -> 0xFFD24A;
                case BUILDING -> 0x5BE06A;
                default -> 0xE0503A;
            };
            DustParticleOptions dust = new DustParticleOptions(color, 1.1F);
            int h = b.type.half;
            double y = b.origin.getY() + 0.15;
            List<double[]> points = new ArrayList<>();
            for (int i = -h; i <= h; i++) {
                points.add(new double[]{b.origin.getX() + i + 0.5, b.origin.getZ() - h + 0.05});
                points.add(new double[]{b.origin.getX() + i + 0.5, b.origin.getZ() + h + 0.95});
                points.add(new double[]{b.origin.getX() - h + 0.05, b.origin.getZ() + i + 0.5});
                points.add(new double[]{b.origin.getX() + h + 0.95, b.origin.getZ() + i + 0.5});
            }
            for (ServerPlayer p : near) {
                if (p.blockPosition().distSqr(b.origin) > OUTLINE_RANGE * OUTLINE_RANGE) continue;
                for (double[] pt : points) level.sendParticles(p, dust, false, false, pt[0], y, pt[1], 1, 0, 0, 0, 0);
                // corner posts of light
                for (int[] c : new int[][]{{-h, -h}, {h + 1, -h}, {-h, h + 1}, {h + 1, h + 1}}) {
                    for (double dy = 0.4; dy < 2.6; dy += 0.45) {
                        level.sendParticles(p, dust, false, false, b.origin.getX() + c[0] + (c[0] > 0 ? -0.05 : 0.05), y + dy,
                                b.origin.getZ() + c[1] + (c[1] > 0 ? -0.05 : 0.05), 1, 0, 0, 0, 0);
                    }
                }
            }
        }
    }

    /** The people of the village as entities: missing ones are made, strangers claiming to be ours are removed. */
    private static void bodies(ServerLevel level, VillageData data, Village v) {
        long today = data.day;
        Map<Integer, ResidentEntity> found = new HashMap<>();
        AABB box = new AABB(v.center).inflate(96, 48, 96);
        for (ResidentEntity e : level.getEntitiesOfClass(ResidentEntity.class, box, e -> e.colonyVillage() == v.id)) {
            Dweller d = v.dweller(e.colonyDweller());
            if (d == null || found.containsKey(d.id)) {
                e.discard();
                continue;
            }
            found.put(d.id, e);
            d.body = e.getUUID();
            e.syncColony(d, v, today);
            // (a crew at the end of a trail, or a merchant on the road, near the village: theirs to look after, not the village's)
            if (d.away && (Roadworks.onCrew(data, v, d) || d.job == Job.MERCHANT)) continue;
            // stuck in the rock (a slip on a stair, a block put where they stood): up to the ground above
            // (really inside it: on a path or farmland, a block lower than a full one, the feet are "in" that block
            // and were taken for stuck, and put somewhere else every two seconds)
            BlockPos at = e.blockPosition();
            if (!level.noCollision(e, e.getBoundingBox().deflate(0.08))
                    && (level.getBlockState(at).isSuffocating(level, at) || level.getBlockState(at.above()).isSuffocating(level, at.above()))) {
                BlockPos to = PlotFinder.standAt(level, at.getX(), at.getZ(), at.getY());
                if (to == null) to = hop(level, v, at);
                if (to != null) snap(level, e, to, "stuck in a block");
            }
            // out where the world stands still (too far from any player for anything to move): the miner is taken
            // on to his mine, anyone else home, so that no one stands frozen half way
            if (!level.isPositionEntityTicking(at) && !d.away) {
                BlockPos to = null;
                if (d.job == Job.MINER) {
                    for (Building b : v.buildings) if (b.type == BuildingType.MINE_HOUSE && b.standing()) to = b.blueprint(v.wood).workSpot;
                    if (to != null && to.distSqr(at) < 16 * 16) to = null;
                } else {
                    to = spawnPoint(level, v, d);
                }
                if (to != null && Construction.loaded(level, to)) {
                    e.getNavigation().stop();
                    snap(level, e, to, "out where the world stands still");
                    continue;
                }
            }
            // up on a roof (climbed, or put there): down to the building's door
            for (Building b : v.buildings) {
                if (Math.abs(at.getX() - b.origin.getX()) > b.type.half || Math.abs(at.getZ() - b.origin.getZ()) > b.type.half) continue;
                if (b.type != BuildingType.TENT && at.getY() >= b.origin.getY() + 3 && level.canSeeSky(at) && b.standing() && onBuilding(b, v, at.below())) {
                    BlockPos door = b.blueprint(v.wood).workSpot;
                    BlockPos to = PlotFinder.standAt(level, door.getX(), door.getZ(), door.getY());
                    e.getNavigation().stop();
                    snap(level, e, to != null ? to : door, "on the roof of " + b.type.id());
                }
                break;
            }
            // trying to walk and not getting anywhere (a pit, a gap between blocks): a hop out to open ground near by
            long[] still = STILL.computeIfAbsent(e.getUUID(), k -> new long[]{Long.MIN_VALUE, 0});
            long here = at.asLong();
            if (level.getGameTime() - e.walking > 60 || here != still[0]) {
                still[0] = here;
                still[1] = 0;
            } else if (++still[1] >= 12) {
                still[1] = 0;
                BlockPos out = hop(level, v, at);
                if (out != null) {
                    e.getNavigation().stop();
                    snap(level, e, out, "not getting anywhere");
                }
            }
        }
        for (Dweller d : v.dwellers) {
            // (a scout on the road has no body here until he comes back)
            if (found.containsKey(d.id) || d.away) continue;
            // the body may just be out of range (wandered off, in an unloaded chunk): look it up first
            if (d.body != null && level.getEntity(d.body) instanceof ResidentEntity e && e.isAlive()) {
                e.syncColony(d, v, today);
                continue;
            }
            ResidentEntity e = ModContent.RESIDENT.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (e == null) continue;
            BlockPos at = d.arriving ? VillageLife.edge(level, v) : spawnPoint(level, v, d);
            e.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, RND.nextFloat() * 360, 0);
            e.syncColony(d, v, today);
            level.addFreshEntity(e);
            d.body = e.getUUID();
            data.changed();
        }
    }

    /** Where each body stood the last few looks, and for how many looks (the stuck ones). */
    private static final Map<java.util.UUID, long[]> STILL = new HashMap<>();

    /** Open, dry ground a few blocks from a stuck body, as level with it as can be found; else the store's door. */
    private static BlockPos hop(ServerLevel level, Village v, BlockPos at) {
        BlockPos best = null;
        int bestDy = Integer.MAX_VALUE;
        for (int i = 0; i < 24; i++) {
            int x = at.getX() + RND.nextInt(9) - 4, z = at.getZ() + RND.nextInt(9) - 4;
            if (Math.abs(x - at.getX()) + Math.abs(z - at.getZ()) < 2 || !Construction.loaded(level, new BlockPos(x, 0, z))) continue;
            // (never onto a building: its roof would do as well as ground)
            boolean onPlot = false;
            for (Building b : v.buildings) {
                if (Math.abs(x - b.origin.getX()) <= b.type.half && Math.abs(z - b.origin.getZ()) <= b.type.half) onPlot = true;
            }
            if (onPlot) continue;
            BlockPos p = PlotFinder.standAt(level, x, z, at.getY());
            if (p == null || !level.getBlockState(p.below()).getFluidState().isEmpty()) continue;
            int dy = Math.abs(p.getY() - at.getY());
            if (dy < bestDy) {
                bestDy = dy;
                best = p;
            }
        }
        return best != null && bestDy <= 2 ? best : null;
    }

    /** Is the block one of the building's own (its walls, its roof)? */
    private static boolean onBuilding(Building b, Village v, BlockPos p) {
        for (Blueprint.Piece piece : b.blueprint(v.wood).pieces) if (piece.pos().equals(p)) return true;
        return false;
    }

    /** Every time someone was put elsewhere by hand while a player was near: the reason, for the tests. */
    public static final java.util.List<String> SNAPS = new java.util.ArrayList<>();

    /** Puts a body elsewhere (a stuck one), and notes it if a player could have seen it. */
    static void snap(ServerLevel level, net.minecraft.world.entity.Entity e, BlockPos to, String why) {
        BlockPos was = e.blockPosition();
        e.snapTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, e.getYRot(), 0);
        if (level.getNearestPlayer(e, 48) != null) {
            String line = (e.hasCustomName() ? e.getCustomName().getString() : e.getName().getString()) + ": " + why + " " + was.toShortString()
                    + " -> " + to.toShortString();
            SNAPS.add(line);
            Minecraftportsmod.LOGGER.info("[snap] {}", line);
        }
    }

    private static BlockPos spawnPoint(ServerLevel level, Village v, Dweller d) {
        Building home = v.building(d.home);
        if (home != null && home.standing()) return home.blueprint(v.wood).workSpot;
        return v.storeSpot();
    }

    // ------------------------------------------------------------------ what people do

    /** A resident did a bit of building work at a site. */
    public static void work(ServerLevel level, Village v, Building b, int blocks) {
        if (b.state != Building.State.BUILDING && b.state != Building.State.DEMOLISHING && !b.upgradeWork()) return;
        b.work += blocks;
        VillageLife.progress(level, VillageData.get(level.getServer()), v, b);
        VillageData.get(level.getServer()).changed();
    }

    /** A resident carried materials from the store to a site. Returns what was actually brought. */
    public static int carry(ServerLevel level, Village v, Building b, Res r, int max) {
        int n = Math.min(max, Math.min(b.missing(r), v.stock(r)));
        if (n <= 0) return 0;
        VillageLife.deliver(v, b, r, n);
        VillageData.get(level.getServer()).changed();
        return n;
    }

    /** Someone brings the fruit of their work to the store: logs, stone, fish, wheat. */
    public static void deposit(ServerLevel level, Village v, Dweller d, Res r, int n) {
        if (n <= 0 || r == null) return;
        v.add(r, n);
        d.earned += n;
        VillageData.get(level.getServer()).changed();
    }

    /** A miner brings the iron ore found while digging. */
    public static void depositIron(ServerLevel level, Village v, Dweller d, int n) {
        if (n <= 0) return;
        v.add(Res.IRON, n);
        d.found += n;
        VillageData.get(level.getServer()).changed();
    }

    /** The mine is one block further along. */
    public static void mineStep(ServerLevel level, Village v) {
        v.mineStep++;
        VillageData.get(level.getServer()).changed();
    }

    /** The pit's plan was made anew (the house raised, the world loaded): its steps are gone over again from the first. */
    static void mineReset(Village v) {
        v.mineStep = 0;
    }

    public static int mineStepOf(Village v) {
        return v.mineStep;
    }

    public static void arrived(ServerLevel level, Village v, Dweller d) {
        if (!d.arriving) return;
        d.arriving = false;
        VillageData.get(level.getServer()).changed();
    }

    // ------------------------------------------------------------------ a player helps

    /** A player hands over materials for a building site from their inventory. Returns units given per resource. */
    public static Map<Res, Integer> donate(ServerPlayer player, Village v, Building b) {
        Map<Res, Integer> given = new java.util.EnumMap<>(Res.class);
        if (b.state != Building.State.PLANNED && !(b.upgrading() && !b.supplied())) return given;
        var inv = player.getInventory();
        for (Res r : Res.values()) {
            int missing = b.missing(r);
            for (int i = 0; i < inv.getContainerSize() && missing > 0; i++) {
                var stack = inv.getItem(i);
                int per = r.unitsOf(stack);
                if (per <= 0) continue;
                int take = Math.min(stack.getCount(), (missing + per - 1) / per);
                inv.removeItem(i, take);
                int units = Math.min(missing, take * per);
                missing -= units;
                given.merge(r, units, Integer::sum);
                b.delivered.merge(r, units, Integer::sum);
            }
        }
        if (!given.isEmpty()) {
            inv.setChanged();
            VillageData data = VillageData.get(player.level().getServer());
            Component what = VillageText.amounts(given);
            v.log(data.day, Component.translatable("minecraftportsmod.vlog.donated", player.getName(), what, b.type.displayName()));
            if (b.supplied()) VillageLife.deliver(v, b, Res.WOOD, 0);
            data.changed();
        }
        return given;
    }

    // ------------------------------------------------------------------ founding

    /**
     * Sets up a camp: a campfire, two tents, the village board, three people (someone for food, a woodcutter, a
     * miner). {@code toWater} points at the water the camp lies by.
     */
    public static Village foundCamp(ServerLevel level, BlockPos near, Direction toWater, String name, boolean russian, String wood) {
        return foundCamp(level, near, toWater, name, russian, wood, false);
    }

    /** A camp, on an island or not. */
    public static Village foundCamp(ServerLevel level, BlockPos near, Direction toWater, String name, boolean russian, String wood, boolean island) {
        VillageData data = VillageData.get(level.getServer());
        // the camp stands a little back from the shore
        BlockPos c = near.relative(toWater.getOpposite(), 8);
        // (low in the lie of the land: a bump cut away, not the fire heaped up on earth)
        Integer floor = PlotFinder.lowFloor(level, c, 2);
        if (floor == null) floor = PlotFinder.floor(level, c, 2);
        if (floor == null) floor = PlotFinder.floorAt(level, c.getX(), c.getZ());
        BlockPos center = new BlockPos(c.getX(), floor, c.getZ());
        BlockPos board = center.relative(toWater.getOpposite(), 4).relative(toWater.getClockWise(), 2);
        BlockPos boardAt = new BlockPos(board.getX(), center.getY(), board.getZ());
        Village placed = new Village(data.newId(), name, center, toWater, wood, boardAt, russian);
        placed.founded = data.day;
        placed.lastGrowth = data.day;
        placed.island = island;
        placed.add(Res.FOOD, VillageLife.START_FOOD);
        placed.add(Res.WOOD, VillageLife.START_WOOD);
        placed.add(Res.STONE, VillageLife.START_STONE);
        placed.add(Res.WHEAT, VillageLife.START_WHEAT);

        // what the village will go deep into, by the land around it (before its trees are cleared)
        placed.focus = VillageLife.chooseFocus(level, placed);
        // a clearing: the trees of the camp's ground come down (their wood goes to the store)
        int logs = Construction.clearTrees(level, center, 14);
        placed.add(Res.WOOD, logs);

        Building fire = new Building(placed.nextBuilding++, BuildingType.CAMPFIRE, center, toWater, RND.nextLong()).look(VillageLife.look(placed));
        standUp(level, placed, fire);
        for (int i = 0; i < 2; i++) {
            Blueprint.Frame f = PlotFinder.find(level, placed, BuildingType.TENT, -1);
            if (f == null) break;
            standUp(level, placed, new Building(placed.nextBuilding++, BuildingType.TENT, f.origin(), f.front(), RND.nextLong()).look(VillageLife.look(placed)));
        }
        // the board, on a patch of ground by the fire at the camp's own height
        pad(level, boardAt, 1);
        level.setBlock(boardAt, ModContent.VILLAGE_BOARD.defaultBlockState()
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, toWater), 3);

        Job[] jobs = {Job.GATHERER, Job.WOODCUTTER, Job.MINER};
        for (Job j : jobs) {
            Dweller d = new Dweller(placed.nextDweller++, VillageLife.freshName(placed),
                    RND.nextInt(ResidentEntity.SKINS));
            d.job = j;
            d.joined = data.day;
            placed.dwellers.add(d);
        }
        placed.dwellers.getFirst().elder = true;
        VillageLife.rehouse(placed, data.day);
        placed.log(data.day, Component.translatable("minecraftportsmod.vlog.founded", placed.name));
        data.add(placed);
        Minecraftportsmod.LOGGER.info("Founded camp {} at {} ({} buildings)", placed.name, center.toShortString(), placed.buildings.size());
        return placed;
    }

    /** A small levelled patch around a point: ground up to its height, air above. */
    private static void pad(ServerLevel level, BlockPos at, int r) {
        var dirt = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = at.getX() + dx, z = at.getZ() + dz;
                int top = PlotFinder.floorAt(level, x, z) - 1;
                for (int y = Math.min(top, at.getY() - 2); y < at.getY() - 1; y++) level.setBlock(new BlockPos(x, y, z), dirt, 3);
                level.setBlock(new BlockPos(x, at.getY() - 1, z), dx == 0 && dz == 0
                        ? net.minecraft.world.level.block.Blocks.COARSE_DIRT.defaultBlockState()
                        : net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                for (int y = at.getY(); y <= at.getY() + 3; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    /** A building that is there from the start: put up at once. */
    private static void standUp(ServerLevel level, Village v, Building b) {
        b.state = Building.State.BUILT;
        b.work = b.blueprint(v.wood).pieces.size();
        for (Res r : Res.values()) if (b.type.cost(r) > 0) b.delivered.put(r, b.type.cost(r));
        v.buildings.add(b);
        Construction.advance(level, v, b, Integer.MAX_VALUE);
    }

    public static void remove(ServerLevel level, Village v) {
        for (ResidentEntity e : level.getEntitiesOfClass(ResidentEntity.class, new AABB(v.center).inflate(128, 64, 128),
                e -> e.colonyVillage() == v.id)) {
            e.discard();
        }
        VillageData.get(level.getServer()).remove(v.id);
    }
}
