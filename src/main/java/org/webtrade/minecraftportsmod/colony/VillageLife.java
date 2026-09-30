package org.webtrade.minecraftportsmod.colony;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.webtrade.minecraftportsmod.village.Residents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A village's day: work brings in food, wood and stone, people eat, the mood follows, materials go to the
 * building sites, children grow up, newcomers arrive (or are born) when there is a bed and food to spare,
 * people change jobs when the village needs it, and the village decides what to build next. Also: the level.
 */
public final class VillageLife {

    public static final int ADULT_EATS = 8, CHILD_EATS = 4;
    /** A scout eats for three: long roads, and all he eats is carried. */
    public static final int SCOUT_EATS = ADULT_EATS * 3;
    /** Days between two newcomers at the most. */
    public static final int GROWTH_DAYS = 2;
    /** Blocks a grown-up puts up in a day while no one is there to watch. */
    static final int OFFLINE_WORK = 300;
    /** Food kept in store at the start of a camp. */
    static final int START_FOOD = 50, START_WOOD = 12, START_STONE = 4, START_WHEAT = 12;

    /** Wheat a farmer brings in from the fields in a day (with the crops), more in a village known for its farming. */
    static int wheatDaily(Village v) {
        return (int) Math.round(6 * subFactor(v, BuildingType.Sub.FARMING));
    }

    static final RandomSource RND = RandomSource.create();

    private VillageLife() {
    }

    // ------------------------------------------------------------------ the day

    /** @param watched a player is near: building is done by the people in the world, not on paper */
    static void day(ServerLevel level, VillageData data, Village v, boolean watched) {
        long today = data.day;
        // (the land round it: read once, off the server thread; until then the usual, and no speciality chosen by it)
        Land.Shares land = Land.of(level, v);
        if (v.focus == null && (land != null || Construction.loaded(level, v.center))) v.focus = chooseFocus(level, v);
        // (the sub-branch it is known for: of its speciality; the first of it until someone chooses)
        if (v.focus != null && (v.sub == null || v.sub.branch != v.focus)) v.sub = BuildingType.Sub.first(v.focus);
        growUp(v, today);
        v.made.clear();
        v.used.clear();
        v.built.clear();
        v.workshopMade.clear();
        v.workshopUsed.clear();
        // the players' orders first; what is left of the day goes to the village's own work
        v.orderLoad = Orders.work(v);
        produce(data, v, today);
        craft(v);
        smith(v);
        joinery(v);
        wear(v, today);
        boolean hungry = eat(v, today);
        mood(v, hungry);
        supply(v, today);
        if (!watched) workOffline(level, data, v, false);
        else workOffline(level, data, v, true);
        for (Building b : new ArrayList<>(v.buildings)) progress(level, data, v, b);
        merchant(v, today);
        specialists(v, today);
        Scouting.day(level, v, today);
        if (Construction.loaded(level, v.center)) WorkGoal.growGrove(level, v);
        grow(data, v, today, hungry);
        rehouse(v, today);
        levelUp(level, v, today);
        plan(level, v, today);
        data.changed();
    }

    private static void growUp(Village v, long today) {
        for (Dweller d : v.dwellers) {
            if (d.job == null && !d.child(today)) {
                d.job = chooseJob(v);
                v.log(today, Component.translatable("minecraftportsmod.vlog.grew_up", d.name, d.job.displayName()));
            }
        }
    }

    /** What one person brings in a day at their trade: better tools, better crops, less when unhappy. */
    static int daily(Village v, Job job) {
        return (int) Math.round(dailyBase(v, job) * own(v, branchOf(job)));
    }

    /** The branch a trade's plain work belongs to (its fifth more in a village of that speciality). */
    static BuildingType.Branch branchOf(Job job) {
        return switch (job) {
            case WOODCUTTER -> BuildingType.Branch.WOOD;
            case MINER -> BuildingType.Branch.MINE;
            case FARMER, FISHER, GATHERER -> BuildingType.Branch.FOOD;
            default -> null;
        };
    }

    /** What the speciality gives: a fifth more of its branch's plain work (logs, stone, food). */
    public static final double OWN = 1.2, FOREIGN = 0.7;

    static double own(Village v, BuildingType.Branch b) {
        return b != null && b == v.focus ? OWN : 1.0;
    }

    /**
     * What a sub-branch's own goods come to in this village: a fifth more in its own sub-branch; as anyone in the other
     * of its branch; less by three tenths in another branch's (it can make them, slowly).
     */
    public static double subFactor(Village v, BuildingType.Sub s) {
        if (s == null) return 1.0;
        if (s == v.sub) return OWN;
        if (s.branch == v.focus) return 1.0;
        return FOREIGN;
    }

    /** A day's work at a trade, before the speciality's share. */
    static double dailyBase(Village v, Job job) {
        boolean field = v.count(BuildingType.FIELD, true) > 0;
        // a farmer with no field gathers what he can
        Job j = job == Job.FARMER && !field ? Job.GATHERER : job;
        // (a gatherer has no tools: his hands and a basket; and what grows wild round a village is soon picked)
        double n = j.perDay * (j == Job.GATHERER ? wildShare(v) : Job.TOOL_SPEED[v.toolLevel(job)]);
        if (j == Job.FARMER) n *= cropFactor(v);
        // what the land round the village gives
        Land.Shares land = Land.known(v);
        if (j == Job.WOODCUTTER) n *= land.wood();
        if (j == Job.MINER) n *= land.stone();
        if (j == Job.FARMER) n *= land.farm();
        // (the farmstead's fields: the farming sub-branch's)
        if (j == Job.FARMER && v.count(BuildingType.FARM, true) > 0) n *= subFactor(v, BuildingType.Sub.FARMING);
        if (v.mood < 30) n *= 0.75;
        return n;
    }

    /** Gatherers who get the full share of what grows wild; each one more finds much less (it is soon picked). */
    static final int WILD_GATHERERS = 2;
    static final double WILD_MORE = 0.35;

    /** The share of a full day's gathering each gatherer brings in, as many as there are picking the same woods. */
    static double wildShare(Village v) {
        boolean field = v.count(BuildingType.FIELD, true) > 0;
        int n = v.workers(Job.GATHERER) + (field ? 0 : v.workers(Job.FARMER));
        if (n <= WILD_GATHERERS) return 1.0;
        return (WILD_GATHERERS + WILD_MORE * (n - WILD_GATHERERS)) / n;
    }

    /** How much the crops sown feed compared to wheat. */
    static double cropFactor(Village v) {
        double sum = 0;
        int n = 0;
        for (Building b : v.buildings) {
            if (b.type == BuildingType.FIELD && b.standing()) {
                // a field's levels (a scarecrow, then lamps and bees) make it yield more
                sum += b.crop().food * (1 + 0.15 * (b.level - 1));
                n++;
            }
        }
        return n == 0 ? 1.0 : sum / n / Crop.WHEAT.food;
    }

    /** The chance of iron ore in every block of rock a miner breaks: none with a wooden pick. */
    public static float oreChance(Village v) {
        return switch (v.toolLevel(Job.MINER)) {
            case 1 -> 0.08F;
            case 2 -> 0.16F;
            case 3 -> 0.25F;
            default -> 0F;
        };
    }

    /** The chance of coal in every block of rock a miner breaks (better picks, deeper veins). */
    public static float coalChance(Village v) {
        return 0.10F + 0.03F * v.toolLevel(Job.MINER);
    }

    /** Coal a miner finds in a day, on average. */
    static int coalDaily(Village v) {
        // (the ore is the mining sub-branch's: more to a village known for it, less to one of another branch)
        return (int) Math.round(dailyBase(v, Job.MINER) * coalChance(v) * Land.known(v).ore() / Land.known(v).stone() * subFactor(v, BuildingType.Sub.MINING));
    }

    /** Iron a miner finds in a day, on average (every block of rock broken has its chance). */
    static int ironDaily(Village v) {
        // (the ore is in the rock: under the plains a miner breaks stone and finds next to none)
        return (int) Math.round(dailyBase(v, Job.MINER) * oreChance(v) * Land.known(v).ore() / Land.known(v).stone() * subFactor(v, BuildingType.Sub.MINING));
    }

    /** Emeralds a miner finds in a day in the village's vein (a share of one), while there are any left in it. */
    static double emeraldDaily(Village v) {
        Land.Shares land = Land.known(v);
        if (v.mined >= land.vein()) return 0;
        return 0.25 * land.mountain() * (v.toolLevel(Job.MINER) >= 2 ? 1 : 0.4);
    }

    /** Emeralds for reaching a level (the newcomers bring their savings, the village's fame brings custom): once each. */
    static int reward(Village.Level l) {
        return switch (l) {
            case CAMP -> 0;
            case HAMLET -> 3;
            case VILLAGE -> 5;
            case SETTLEMENT -> 8;
            case TOWN -> 12;
        };
    }

    /** What a day of work brings in with these people. */
    static int production(Village v, Res res) {
        int n = 0;
        for (Dweller d : v.dwellers) {
            if (d.job == null) continue;
            Job j = d.job == Job.FARMER && v.count(BuildingType.FIELD, true) == 0 ? Job.GATHERER : d.job;
            if (j.makes == res) n += daily(v, d.job);
            if (res == Res.IRON && d.job == Job.MINER) n += ironDaily(v);
            if (res == Res.COAL && d.job == Job.MINER) n += coalDaily(v);
        }
        return n;
    }

    public static int foodNeed(Village v, long today) {
        int n = 0;
        // a scout on the road eats for two
        for (Dweller d : v.dwellers) n += d.child(today) || d.job == null ? CHILD_EATS : d.job == Job.SCOUT ? SCOUT_EATS : ADULT_EATS;
        return n;
    }

    /**
     * The day's work: what people brought to the store with their own hands (while someone watched) is already
     * there; the rest of each one's day is added now.
     */
    private static void produce(VillageData data, Village v, long today) {
        boolean field = v.count(BuildingType.FIELD, true) > 0;
        for (Dweller d : v.dwellers) {
            // (away on the road, building a trail: no work at the trade today)
            if (d.job == null || d.job.makes == null || d.away) {
                d.earned = 0;
                continue;
            }
            Job j = d.job == Job.FARMER && !field ? Job.GATHERER : d.job;
            // (the share of the day that went on orders is not the village's)
            double free = 1 - v.orderLoad.getOrDefault(d.job, 0.0);
            int day = (int) Math.round(daily(v, d.job) * free);
            int rest = Math.max(0, day - d.earned);
            // (a store with plenty gets no more: the day goes on the building sites instead)
            if (plenty(v, j.makes)) rest = 0;
            if (rest > 0) v.add(j.makes, rest);
            v.made.merge(j.makes, Math.max(day, d.earned), Integer::sum);
            if (d.job == Job.MINER && ironDaily(v) > 0) {
                // the ore not already found and brought today by digging
                int iron = Math.max(0, ironDaily(v) - d.found);
                if (iron > 0) v.add(Res.IRON, iron);
                v.made.merge(Res.IRON, Math.max(ironDaily(v), d.found), Integer::sum);
            }
            if (d.job == Job.MINER && coalDaily(v) > 0) {
                v.add(Res.COAL, coalDaily(v));
                v.made.merge(Res.COAL, coalDaily(v), Integer::sum);
            }
            if (d.job == Job.MINER && emeraldDaily(v) > 0) {
                v.dig += emeraldDaily(v);
                while (v.dig >= 1 && v.mined < Land.known(v).vein()) {
                    v.dig -= 1;
                    v.mined++;
                    v.emeralds++;
                    data.minted++;
                    v.log(today, Component.translatable("minecraftportsmod.vlog.emerald_found", d.name).withStyle(ChatFormatting.GREEN));
                }
            }
            d.earned = 0;
            d.found = 0;
        }
        // wheat: the fields give it with their crops; the gatherers bring a little in from the wild
        int wheat = 0;
        for (Dweller d : v.dwellers) {
            if (d.job == null || d.away) continue;
            if (d.job == Job.FARMER && field) wheat += wheatDaily(v);
            else if (d.job == Job.GATHERER || d.job == Job.FARMER) wheat += 1;
        }
        if (wheat > 0 && !v.full(Res.WHEAT)) {
            v.add(Res.WHEAT, wheat);
            v.made.merge(Res.WHEAT, wheat, Integer::sum);
        }
        // going hungry: everyone at home who can spares half a day to fish and gather (each keeps his trade)
        int need = foodNeed(v, today);
        if (v.eaten < need && v.stock(Res.FOOD) < need) {
            int helped = 0, got = 0;
            for (Dweller d : v.dwellers) {
                if (d.job == null || d.away || d.job.makes == Res.FOOD) continue;
                got += (int) Math.round(daily(v, Job.GATHERER) * 0.5);
                helped++;
            }
            if (got > 0) {
                v.add(Res.FOOD, got);
                v.made.merge(Res.FOOD, got, Integer::sum);
                v.log(today, Component.translatable("minecraftportsmod.vlog.hunger_help", helped, got).withStyle(ChatFormatting.YELLOW));
            }
        }
    }

    private static boolean eat(Village v, long today) {
        int need = foodNeed(v, today);
        int eaten = Math.min(need, v.stock(Res.FOOD));
        v.add(Res.FOOD, -eaten);
        v.eaten = eaten;
        if (eaten < need) {
            v.log(today, Component.translatable("minecraftportsmod.vlog.hungry").withStyle(ChatFormatting.RED));
            return true;
        }
        return false;
    }

    private static void mood(Village v, boolean hungry) {
        int m = 55;
        if (hungry) m -= 35;
        int homeless = Math.max(0, v.population() - v.beds());
        m -= 15 * homeless;
        int inTents = 0;
        for (Dweller d : v.dwellers) {
            Building home = v.building(d.home);
            if (home != null && home.type == BuildingType.TENT) inTents++;
        }
        m -= 2 * inTents;
        if (v.level.ordinal() >= Village.Level.HAMLET.ordinal()) m += 10;
        // better homes: each level above the first, and stone walls, make people happier
        int homes = 0, comfort = 0;
        for (Building b : v.buildings) {
            if (!b.standing() || b.type.branch != BuildingType.Branch.HOME || b.type == BuildingType.TENT) continue;
            homes++;
            comfort += b.level - 1 + (b.type == BuildingType.STONE_HOUSE || b.type == BuildingType.STONE_HOUSE_TALL ? 1 : 0);
        }
        if (homes > 0) m += Math.round(12F * comfort / (homes * 3F));
        if (v.stock(Res.FOOD) >= foodNeed(v, 0) * 3) m += 10;
        if (v.count(BuildingType.STOREHOUSE, true) > 0) m += 5;
        m = Math.max(0, Math.min(100, m));
        v.mood = (v.mood * 2 + m) / 3;
    }

    /** The most steps a village has in its queue (building sites, levels to raise), by its size. */
    static int queueSize(Village v) {
        return v.population() < 4 ? 2 : v.population() < 7 ? 3 : 5;
    }

    /**
     * The village's queue: its building sites and the buildings being raised a level, in the order they are seen to
     * (the order the players set, then the oldest first). The first gets the materials first.
     */
    public static List<Building> queue(Village v) {
        List<Building> out = new ArrayList<>();
        v.order.removeIf(id -> v.building(id) == null || !v.building(id).project());
        for (int id : v.order) out.add(v.building(id));
        List<Building> rest = new ArrayList<>();
        for (Building b : v.buildings) if (b.project() && !v.order.contains(b.id)) rest.add(b);
        rest.sort(Comparator.comparingInt(b -> b.id));
        for (Building b : rest) {
            v.order.add(b.id);
            out.add(b);
        }
        return out;
    }

    /** Whatever is in store goes to the building sites that still lack it (the first in the queue first). */
    private static void supply(Village v, long today) {
        for (Building b : queue(v)) {
            if (b.state != Building.State.PLANNED && !b.upgrading()) continue;
            for (Res r : Res.values()) {
                int give = Math.min(b.missing(r), v.stock(r));
                if (give > 0) deliver(v, b, r, give);
            }
        }
    }

    /** Materials reach a building site. */
    static void deliver(Village v, Building b, Res r, int n) {
        v.add(r, -n);
        v.built.merge(r, n, Integer::sum);
        b.delivered.merge(r, n, Integer::sum);
        if (b.state == Building.State.PLANNED && b.supplied()) start(v, b);
    }

    /** Everything is there: the building goes up (a replaced building comes down first). */
    private static void start(Village v, Building b) {
        b.state = Building.State.BUILDING;
        Building old = v.building(b.replaces);
        if (old != null && old.state != Building.State.DEMOLISHING) {
            old.state = Building.State.DEMOLISHING;
            old.work = old.blueprint(v.wood).pieces.size();
            old.finished = true;
        }
    }

    /**
     * Building work done "on paper": all of it while nobody watches; while someone does, only for sites that did
     * not move at all that day (people could not get to them), so that nothing stays stuck.
     */
    private static void workOffline(ServerLevel level, VillageData data, Village v, boolean onlyStuck) {
        List<Building> busy = new ArrayList<>();
        for (Building b : v.buildings) {
            boolean active = b.state == Building.State.BUILDING || b.state == Building.State.DEMOLISHING && !b.finished || b.upgradeWork();
            if (active && (!onlyStuck || b.work <= b.workMark)) busy.add(b);
        }
        // (no more than a crew of three builds at a time)
        // (and whoever has plenty of what their trade brings in lends a hand)
        int crew = Math.min(DwellerGoals.Build.CREW + Math.min(idleHands(v), DwellerGoals.Build.EXTRA), v.adults());
        for (Building b : busy) b.work += OFFLINE_WORK * Math.max(1, crew) / busy.size();
        for (Building b : v.buildings) b.workMark = b.work;
    }

    /** Checks whether a building is done (built, or pulled down). */
    static void progress(ServerLevel level, VillageData data, Village v, Building b) {
        int total = b.blueprint(v.wood).pieces.size();
        long today = data.day;
        if (b.upgradeWork() && b.work >= total) {
            b.level = b.goal;
            b.goal = 0;
            b.work = b.blueprint(v.wood).pieces.size();
            v.log(today, Component.translatable("minecraftportsmod.vlog.raised", b.type.displayName(), b.level).withStyle(ChatFormatting.DARK_GREEN));
            data.changed();
        } else if (b.state == Building.State.BUILDING && b.work >= total) {
            b.state = Building.State.BUILT;
            b.work = total;
            v.log(today, Component.translatable("minecraftportsmod.vlog.built", b.type.displayName()).withStyle(ChatFormatting.DARK_GREEN));
            rehouse(v, today);
            data.changed();
        } else if (b.state == Building.State.DEMOLISHING && b.work >= total && !b.finished) {
            b.finished = true;
            for (Res r : Res.values()) {
                int back = b.type.cost(r) / 2;
                if (back > 0) v.add(r, back);
            }
            v.log(today, Component.translatable("minecraftportsmod.vlog.demolished", b.type.displayName()));
            data.changed();
        }
    }

    // ------------------------------------------------------------------ people

    /**
     * How much of a resource the village wants to keep: food for five days, wood and stone for what is on order plus
     * a reserve for the next building.
     */
    public static int target(Village v, Res r) {
        // no use wanting more than the store can hold (and a few of anything, but tools: those are kept as the work needs)
        int t = Math.min(wanted(v, r), v.capacity() * 3 / 4);
        return r.toolLevel() > 0 ? t : Math.max(10, t);
    }

    /**
     * What the village uses of a resource in a day, taken out of its store: the food eaten; tools worn out, and what
     * the smith makes new ones of.
     */
    static int dailyUse(Village v, Res r) {
        if (r == Res.FOOD) return foodNeed(v, Long.MAX_VALUE / 2);
        if (r.toolLevel() > 0) return (int) Math.ceil(toolUsers(v) / Job.TOOL_DAYS[r.toolLevel()]);
        return smithNeeds(v, r);
    }

    /** The room the village needs for a resource: the reserve it keeps of it, and what passes through the store in a day. */
    public static int storageNeed(Village v, Res r) {
        return wanted(v, r) + dailyUse(v, r);
    }

    /** The room the village needs in its store, everything together (see {@link #storageNeed}). */
    public static int storageNeed(Village v) {
        int n = 0;
        for (Res r : Res.values()) n += storageNeed(v, r);
        return n;
    }

    /**
     * Is the village short of room in its store? Its storehouses (all of them, the levels they are raised to) give
     * one room for everything; it is short only where all it needs to keep (the reserve of each thing, what its
     * building sites are to have, a day's eating and wear: see {@link #storageNeed}) does not fit in it, and the store
     * is really filling up (near full before the day's eating: what is made keeps up with what is used). A heap of
     * something it has more of than it needs (wood from the woods round it, iron and coal from the rock) is no
     * reason to build: that is moved out when the room is wanted.
     */
    public static boolean storageShort(Village v) {
        int cap = v.capacity();
        int held = v.stored() + v.eaten;
        return storageNeed(v) > cap && held >= cap * 85 / 100;
    }

    /** What the village would keep of a resource if the store were big enough. */
    static int wanted(Village v, Res r) {
        int needed = 0;
        for (Building b : v.projects()) needed += b.missing(r);
        // (the research in hand: the store keeps back what it will cost)
        if (v.research != null) needed += Tree.unlockCost(v, v.research).getOrDefault(r, 0);
        int t = switch (r) {
            case FOOD -> Math.max(30, foodNeed(v, Long.MAX_VALUE / 2) * 5);
            case WOOD -> 60 + needed + smithNeeds(v, r);
            case STONE -> 40 + needed + smithNeeds(v, r);
            case IRON -> needed + smithNeeds(v, r);
            case PLANKS -> 60 + needed + joinerNeeds(v, r);
            case STICKS -> 8 + needed + smithNeeds(v, r) + joinerNeeds(v, r);
            case COAL -> 12 + needed + smithNeeds(v, r);
            case WHEAT -> 20 + needed;
            case JOINERY -> 10 + needed;
            case TOOLS1, TOOLS2, TOOLS3 -> toolsKept(v, r.toolLevel());
        };
        return t;
    }

    /**
     * How badly the village wants more of a resource: 1 when it has none, 0 at the target, below zero with more
     * than enough (-1 at twice the target). Food made each day that doesn't cover the eating counts as a want.
     */
    /**
     * Does the village have plenty of a resource: twice what it wants to keep. Those whose trade brings it in then
     * stop bringing more (it would only lie about, or be lost to a full store) and help at the building sites.
     */
    public static boolean plenty(Village v, Res r) {
        return r != null && (v.full(r) || want(v, r) < -1);
    }

    /** Grown-ups whose trade's store has plenty: free to build. */
    static int idleHands(Village v) {
        int n = 0;
        for (Dweller d : v.dwellers) if (d.job != null && d.job.makes != null && !d.away && plenty(v, d.job.makes)) n++;
        return n;
    }

    public static double want(Village v, Res r) {
        double t = target(v, r);
        double w = (t - v.stock(r)) / t;
        // (food made in a day short of the eating is a want: while the store is under its mark; over it, the fishers
        // and farmers rest from their trade on purpose, and making little then is no shortage)
        if (r == Res.FOOD && v.stock(r) < t && production(v, Res.FOOD) - foodNeed(v, Long.MAX_VALUE / 2) < 3) w = Math.max(w, 0.8);
        return w;
    }

    private static Job jobFor(Village v, Res r) {
        return switch (r) {
            case FOOD -> foodJob(v);
            case WHEAT -> foodJob(v);
            case WOOD, PLANKS, STICKS, JOINERY, TOOLS1, TOOLS2, TOOLS3 -> Job.WOODCUTTER;
            case STONE, IRON, COAL -> Job.MINER;
        };
    }

    /**
     * How many hands each resource should have: enough for food to feed everyone with one to spare; the rest
     * split two to one between wood and stone.
     */
    static int ideal(Village v, Res r) {
        int adults = Math.max(1, v.adults() + 1);
        double perFood = v.count(BuildingType.FIELD, true) > 0 ? (Job.FARMER.perDay + Job.GATHERER.perDay) / 2.0 : Job.GATHERER.perDay;
        // a village's own trade has a hand or so more than it strictly needs
        boolean foodTrade = v.focus == BuildingType.Branch.FOOD;
        int food = Math.min(adults, (int) Math.ceil(foodNeed(v, Long.MAX_VALUE / 2) / perFood) + 1);
        int rest = adults - food;
        // the hands left over: a third to stone, a third to wood, and the last third to the village's own trade
        int third = rest / 3, own = rest - 2 * third;
        int stone = third + (v.focus == BuildingType.Branch.MINE ? own : 0);
        int wood = third + (v.focus == BuildingType.Branch.WOOD || v.focus == null ? own : 0);
        if (foodTrade) food += own;
        return switch (r) {
            case FOOD -> food;
            case WOOD -> wood;
            case STONE -> stone;
            default -> 0;
        };
    }

    private static int hands(Village v, Res r) {
        return r == Res.FOOD ? v.workers(Job.FISHER) + v.workers(Job.FARMER) + v.workers(Job.GATHERER) : v.workers(jobFor(v, r));
    }

    /**
     * The resources a trade is chosen for. Iron is left out: it comes by itself with the miners' better picks, and
     * wanting it must not send people into the quarry while the village goes hungry.
     */
    private static final Res[] WORKED = {Res.FOOD, Res.WOOD, Res.STONE};

    /** The job a new pair of hands is most needed at: a shortage first, else where the mix of hands is short. */
    /** How many days of a food shortfall a store must cover for the new hands to go to other trades. */
    static final int FOOD_WEEKS = 20;

    static Job chooseJob(Village v) {
        // people keep the trade they took up: an empty place in a building goes to the next one who grows up or arrives
        Job place = emptyPlace(v);
        if (place != null) return place;
        // food first: while what is caught and grown does not feed everyone (and the one now taking up a trade) with
        // a little over, the new hands go to it, whatever the building sites want; unless the store would cover the
        // shortfall for weeks (a full granary is eaten into before anyone more is sent to the fields)
        double shortfall = (foodNeed(v, Long.MAX_VALUE / 2) + ADULT_EATS) * 1.15 - production(v, Res.FOOD);
        if (shortfall > 0 && v.stock(Res.FOOD) < target(v, Res.FOOD) + shortfall * FOOD_WEEKS) return foodJob(v);
        Res best = null;
        double bestW = 0.2;
        for (Res r : WORKED) {
            // a resource nobody makes yet is wanted a bit more
            double w = want(v, r) + (hands(v, r) == 0 ? 0.3 : 0);
            if (w > bestW && !v.full(r)) {
                bestW = w;
                best = r;
            }
        }
        if (best == null) {
            // everything well stocked: the store the village wants most (or, below, the trade short of hands)
            double most = -1e9;
            for (Res r : WORKED) {
                if (want(v, r) > most) {
                    most = want(v, r);
                    best = r;
                }
            }
            // (every store is well stocked: the trade chosen for life is the one short of hands in the village's mix,
            // not the store that happens to be least over its mark; a full store takes nobody new)
            int gap = Integer.MIN_VALUE;
            for (Res r : WORKED) {
                if (v.full(r)) continue;
                int g = ideal(v, r) - hands(v, r);
                if (g > gap || g == gap && best != null && want(v, r) > want(v, best)) {
                    gap = g;
                    best = r;
                }
            }
        }
        return jobFor(v, best);
    }

    /** A building that stands waiting for its worker: the forge first, then the stall, the saw, the map table. */
    private static Job emptyPlace(Village v) {
        int adults = v.adults();
        if (v.has(BuildingType.SMITHY) && v.workers(Job.SMITH) == 0) return Job.SMITH;
        if (v.workers(Job.FISHER) < v.count(BuildingType.FISH_HUT, true) && adults >= 4) return Job.FISHER;
        if (v.has(BuildingType.MARKET) && v.workers(Job.MERCHANT) == 0 && adults >= 4) return Job.MERCHANT;
        if (v.has(BuildingType.SAWMILL) && v.workers(Job.SAWYER) == 0 && adults >= 4) return Job.SAWYER;
        if (v.has(BuildingType.CARPENTER) && v.workers(Job.JOINER) == 0 && adults >= 4) return Job.JOINER;
        if (v.has(BuildingType.CARTOGRAPHER) && v.workers(Job.SCOUT) < Scouting.scouts(v) && adults >= 5) return Job.SCOUT;
        return null;
    }

    private static Job foodJob(Village v) {
        int fields = v.count(BuildingType.FIELD, true);
        // the wild is picked by two: after them, the fields (one is laid out for the new farmer)
        if (fields * 2 > v.workers(Job.FARMER) || v.workers(Job.GATHERER) >= WILD_GATHERERS) return Job.FARMER;
        return Job.GATHERER;
    }

    /** A smithy, sawmill or map table that is gone sends its people back to a trade. */
    private static void specialists(Village v, long today) {
        specialist(v, today, BuildingType.SMITHY, Job.SMITH, 1, 2, "minecraftportsmod.vlog.smith");
        specialist(v, today, BuildingType.SAWMILL, Job.SAWYER, 1, 4, "minecraftportsmod.vlog.sawyer");
        specialist(v, today, BuildingType.CARPENTER, Job.JOINER, 1, 4, "minecraftportsmod.vlog.joiner");
        specialist(v, today, BuildingType.CARTOGRAPHER, Job.SCOUT, Scouting.scouts(v), 5, "minecraftportsmod.vlog.scout");
    }

    private static void specialist(Village v, long today, BuildingType where, Job job, int count, int minAdults, String news) {
        if (!v.has(where)) {
            for (Dweller d : v.dwellers) if (d.job == job && !d.away) d.job = chooseJob(v);
            return;
        }
        // (the place stands empty until someone grows up or arrives: see emptyPlace)
    }

    // ------------------------------------------------------------------ what is made from logs, and what wears out

    /** The logs a village turns into planks and sticks in a day, and how many of each a log gives. */
    static int[] sawing(Village v) {
        Building mill = null;
        for (Building b : v.buildings) if (b.type == BuildingType.SAWMILL && b.standing()) mill = b;
        if (mill != null && v.workers(Job.SAWYER) > 0) {
            // (the sawmill is the logging sub-branch's: more logs through it in a village known for it)
            return new int[]{(int) Math.round((10 + 6 * mill.level) * subFactor(v, BuildingType.Sub.LOGGING)), 4, 8};
        }
        // by hand: fewer logs, and half of each lost to planks (sticks are split from a log easily enough)
        return new int[]{10, 2, 8};
    }

    /**
     * The day's sawing: the sticks the tools want first, then the planks the building sites and the reserve want;
     * a sawmill with logs to spare saws them into planks too (they keep, and sell better).
     */
    static void craft(Village v) {
        int[] saw = sawing(v);
        // (what the sawyer spent on orders is not sawn for the village)
        saw[0] = (int) (saw[0] * (1 - v.orderLoad.getOrDefault(Job.SAWYER, 0.0)));
        int free = Math.max(0, Math.min(saw[0], v.stock(Res.WOOD) - 10));
        int sticks = Math.min(free, (Math.max(0, target(v, Res.STICKS) - v.stock(Res.STICKS)) + saw[2] - 1) / saw[2]);
        free -= sticks;
        int planks = Math.min(free, (Math.max(0, target(v, Res.PLANKS) - v.stock(Res.PLANKS)) + saw[1] - 1) / saw[1]);
        free -= planks;
        boolean mill = saw[1] == 4;
        // (a store of planks three times what is wanted is enough)
        if (mill && !v.full(Res.PLANKS) && v.stock(Res.PLANKS) < target(v, Res.PLANKS) * 3) planks += Math.min(free, Math.max(0, (v.stock(Res.WOOD) - sticks - planks - target(v, Res.WOOD)) / 2));
        if (sticks + planks <= 0) return;
        v.add(Res.WOOD, -(sticks + planks));
        v.used.merge(Res.WOOD, sticks + planks, Integer::sum);
        v.workshop(BuildingType.SAWMILL, Res.WOOD, 0, sticks + planks);
        if (sticks > 0) {
            v.add(Res.STICKS, sticks * saw[2]);
            v.made.merge(Res.STICKS, sticks * saw[2], Integer::sum);
            v.workshop(BuildingType.SAWMILL, Res.STICKS, sticks * saw[2], 0);
        }
        if (planks > 0) {
            v.add(Res.PLANKS, planks * saw[1]);
            v.made.merge(Res.PLANKS, planks * saw[1], Integer::sum);
            v.workshop(BuildingType.SAWMILL, Res.PLANKS, planks * saw[1], 0);
        }
    }

    // ------------------------------------------------------------------ the smith's tools and the joiner's work

    /** Days of work the village keeps tools (and what the smith makes them of) for. */
    static final int TOOL_RESERVE_DAYS = 2;
    /** Tools a smith makes in a day, by their level. */
    static final int[] TOOLS_A_DAY = {0, 30, 8, 5};
    /** Joinery a joiner makes in a day at his workshop's first level, and more with each level; of two planks and a stick each. */
    static final int JOINERY_A_DAY = 6, JOINERY_PER_LEVEL = 4;

    /** The grown-ups whose trade wears out tools. */
    static int toolUsers(Village v) {
        int n = 0;
        for (Job j : Job.values()) if (j.usesTools()) n += v.workers(j);
        return n;
    }

    /** The level of the village's smithy while a smith works it (0: none). */
    static int smithyLevel(Village v) {
        if (v.workers(Job.SMITH) == 0) return 0;
        int best = 0;
        for (Building b : v.buildings) if (b.standing() && b.type == BuildingType.SMITHY) best = Math.max(best, b.level);
        return best;
    }

    /** The tools the village would have its people work with: the best its smith makes, stone ones at least (bought if need be). */
    static int toolAim(Village v) {
        return Math.min(3, Math.max(2, smithyLevel(v)));
    }

    /** Days of work the tools in store are good for, of those of a level and better. */
    static double toolDaysFrom(Village v, int level) {
        double d = 0;
        for (int l = level; l <= 3; l++) d += v.stock(Res.tools(l)) * Job.TOOL_DAYS[l];
        return d;
    }

    /** How many tools of a level the village keeps: enough, with the better ones it has, for some days of all its work. */
    static int toolsKept(Village v, int level) {
        int users = toolUsers(v);
        if (users == 0) return 0;
        double want = users * (double) TOOL_RESERVE_DAYS;
        if (level > toolAim(v)) return Math.min(v.stock(Res.tools(level)), (int) Math.ceil(want / Job.TOOL_DAYS[level]));
        double better = level < 3 ? toolDaysFrom(v, level + 1) : 0;
        return (int) Math.ceil(Math.max(0, want - better) / Job.TOOL_DAYS[level]);
    }

    /** The level of tools the smith makes now: the best his smithy allows that he has the makings of (0: none). */
    static int smithMakes(Village v) {
        for (int l = smithyLevel(v); l >= 1; l--) if (Tree.affordable(v, Job.toolRecipe(l))) return l;
        return 0;
    }

    /** What the village keeps for the smith: the makings of a day of tools, at the level his smithy makes. */
    static int smithNeeds(Village v, Res r) {
        int l = smithyLevel(v);
        if (l == 0) return 0;
        int tools = (int) Math.ceil(toolUsers(v) / Job.TOOL_DAYS[l]);
        return tools * Job.toolRecipe(l).getOrDefault(r, 0);
    }

    /** What the village keeps for the joiner: two days of his planks and sticks. */
    static int joinerNeeds(Village v, Res r) {
        if (v.workers(Job.JOINER) == 0) return 0;
        int batches = 2 * (JOINERY_A_DAY + JOINERY_PER_LEVEL * houseLevel(v, Job.JOINER));
        return r == Res.PLANKS ? 2 * batches : r == Res.STICKS ? batches : 0;
    }

    /**
     * The smith's day: tools of the best level he can make, while the tools in store are good for less than a few
     * days of the village's work (the others of his trade's day go on the players' orders).
     */
    static void smith(Village v) {
        int users = toolUsers(v);
        if (users == 0 || v.workers(Job.SMITH) == 0) return;
        double free = 1 - v.orderLoad.getOrDefault(Job.SMITH, 0.0);
        for (int made = 0; ; ) {
            int l = smithMakes(v);
            if (l == 0 || toolDaysFrom(v, 1) >= users * (double) TOOL_RESERVE_DAYS || v.full(Res.tools(l))) return;
            int cap = (int) Math.round(TOOLS_A_DAY[l] * v.workers(Job.SMITH) * subFactor(v, BuildingType.Sub.METALWORK) * free);
            if (made >= cap) return;
            java.util.Map<Res, Integer> recipe = Job.toolRecipe(l);
            recipe.forEach((r, n) -> {
                v.add(r, -n);
                v.used.merge(r, n, Integer::sum);
                v.workshop(BuildingType.SMITHY, r, 0, n);
            });
            v.add(Res.tools(l), 1);
            v.made.merge(Res.tools(l), 1, Integer::sum);
            v.workshop(BuildingType.SMITHY, Res.tools(l), 1, 0);
            made++;
        }
    }

    /** The joiner's day: joinery from the planks and sticks over what the village keeps, up to twice what it wants of it. */
    static void joinery(Village v) {
        if (v.workers(Job.JOINER) == 0 || !v.has(BuildingType.CARPENTER)) return;
        double free = 1 - v.orderLoad.getOrDefault(Job.JOINER, 0.0);
        int batches = (int) Math.round((JOINERY_A_DAY + JOINERY_PER_LEVEL * houseLevel(v, Job.JOINER)) * v.workers(Job.JOINER)
                * subFactor(v, BuildingType.Sub.JOINERY) * free);
        int made = 0;
        for (int i = 0; i < batches; i++) {
            if (v.stock(Res.JOINERY) >= target(v, Res.JOINERY) * 2 || v.full(Res.JOINERY)) break;
            if (v.stock(Res.PLANKS) < 2 || v.stock(Res.STICKS) < 1) break;
            v.add(Res.PLANKS, -2);
            v.add(Res.STICKS, -1);
            v.add(Res.JOINERY, 1);
            made++;
        }
        if (made == 0) return;
        v.used.merge(Res.PLANKS, 2 * made, Integer::sum);
        v.used.merge(Res.STICKS, made, Integer::sum);
        v.made.merge(Res.JOINERY, made, Integer::sum);
        v.workshop(BuildingType.CARPENTER, Res.PLANKS, 0, 2 * made);
        v.workshop(BuildingType.CARPENTER, Res.STICKS, 0, made);
        v.workshop(BuildingType.CARPENTER, Res.JOINERY, made, 0);
    }

    /**
     * What a building makes and uses up in a day, in tenths of a unit per resource: {produced, consumed}. A trade's
     * house makes what its trade brings in and wears out its tools; the sawmill turns logs into planks and sticks and
     * planks into its wares (as it did the last day); everyone who lives in a building eats there.
     */
    public static int[][] flows(Village v, Building b) {
        int n = Res.values().length;
        int[] made = new int[n], used = new int[n];
        long today = Long.MAX_VALUE / 2;
        for (Dweller d : v.dwellers) {
            if (d.home != b.id) continue;
            int eats = d.child(today) || d.job == null ? CHILD_EATS : d.job == Job.SCOUT ? SCOUT_EATS : ADULT_EATS;
            used[Res.FOOD.ordinal()] += eats * 10;
        }
        Job job = b.type.job;
        if (job != null && job.makes != null && b.standing()) {
            int workers = v.workers(job);
            Job j = job == Job.FARMER && v.count(BuildingType.FIELD, true) == 0 ? Job.GATHERER : job;
            made[j.makes.ordinal()] += daily(v, job) * workers * 10;
            if (job == Job.MINER) {
                made[Res.IRON.ordinal()] += ironDaily(v) * workers * 10;
                made[Res.COAL.ordinal()] += coalDaily(v) * workers * 10;
            }
            int lv = toolTier(v);
            if (job.usesTools() && lv > 0) used[Res.tools(lv).ordinal()] += (int) Math.round(workers * 10 / Job.TOOL_DAYS[lv]);
            if (job == Job.FARMER && v.count(BuildingType.FIELD, true) > 0) made[Res.WHEAT.ordinal()] += wheatDaily(v) * workers * 10;
        }
        if (b.standing() && (b.type == BuildingType.SAWMILL || b.type == BuildingType.SMITHY || b.type == BuildingType.CARPENTER)) {
            v.workshopMade.getOrDefault(b.type, new java.util.EnumMap<>(Res.class)).forEach((r, k) -> made[r.ordinal()] += k * 10);
            v.workshopUsed.getOrDefault(b.type, new java.util.EnumMap<>(Res.class)).forEach((r, k) -> used[r.ordinal()] += k * 10);
        }
        return new int[][]{made, used};
    }

    /** The tools the village works with: the best it has in store (0: none, bare hands). */
    static int toolTier(Village v) {
        for (int l = 3; l >= 1; l--) if (v.stock(Res.tools(l)) > 0) return l;
        return 0;
    }

    /** The level of a trade's own house (0: none). */
    static int houseLevel(Village v, Job job) {
        int best = 0;
        for (Building b : v.buildings) if (b.standing() && b.type.job == job) best = Math.max(best, b.level);
        return best;
    }

    /**
     * Tools wear out: every worker uses up a share of one a day (wooden ones soonest, iron ones last). A worn-out one
     * is replaced from the store; if the store hasn't what it takes, the trade works with what is left of the old
     * ones (a level of tools lower) until it has.
     */
    static void wear(Village v, long today) {
        java.util.EnumSet<Job> wasShort = java.util.EnumSet.noneOf(Job.class);
        wasShort.addAll(v.toolsShort);
        v.toolsShort.clear();
        for (Job j : Job.values()) {
            if (!j.usesTools() || v.workers(j) == 0) continue;
            int lv = toolTier(v);
            if (lv == 0) {
                // (none in store: bare hands, nothing to wear out)
                v.toolsShort.add(j);
                v.wear.put(j, 0.0);
                continue;
            }
            double w = v.wear.getOrDefault(j, 0.0) + v.workers(j) / Job.TOOL_DAYS[lv];
            while (w >= 1) {
                lv = toolTier(v);
                if (lv == 0) {
                    v.toolsShort.add(j);
                    w = 0;
                    break;
                }
                v.add(Res.tools(lv), -1);
                v.used.merge(Res.tools(lv), 1, Integer::sum);
                w -= 1;
            }
            v.wear.put(j, w);
        }
        for (Job j : v.toolsShort) {
            if (!wasShort.contains(j)) {
                v.log(today, Component.translatable("minecraftportsmod.vlog.no_tools", j.displayName()).withStyle(ChatFormatting.RED));
            }
        }
    }

    /** A stall that is gone sends its merchant back to a trade (a new stall gets the next one who grows up). */
    private static void merchant(Village v, long today) {
        if (v.has(BuildingType.MARKET)) return;
        for (Dweller d : v.dwellers) if (d.job == Job.MERCHANT) d.job = chooseJob(v);
    }

    /** A newcomer or a newborn, now and then, when there is a bed, food to spare and people are content. */
    /** Below this mood the families have no more children. */
    public static final int BIRTH_MOOD = 55;

    /**
     * The village grows by its families: when there is a bed for a child, food to spare and people are content,
     * a couple has a baby (the more couples, the likelier). Only a village too small for a couple takes in
     * someone from the road, and a traveller stays now and then.
     */
    /** A child's chance grows each night by this much: at the lowest mood a child is born, at the best. */
    static final double READY_LOW = 0.03, READY_HIGH = 0.06;
    /** More each night for some days after the village's merchant did business on a round. */
    static final double READY_TRADE = 0.03;
    static final int TRADE_DAYS = 5;
    /** More each night for each village a finished trail joins it to (up to three). */
    static final double READY_LINK = 0.01;
    static final int LINKS = 3;

    /**
     * Children: each night the village grows readier for one (by its mood, and more when it trades), and one is born
     * with that chance; then it starts again from nothing. No free bed: it waits (what was built up is kept). Hungry,
     * short of food or unhappy: it falls away by half. Nobody walks in from elsewhere.
     */
    private static void grow(VillageData data, Village v, long today, boolean hungry) {
        if (v.adults() < 2) return;
        boolean fed = !hungry && v.stock(Res.FOOD) >= Math.min(foodNeed(v, today) * 2, v.capacity() / 4);
        if (!fed || v.mood < BIRTH_MOOD) {
            v.ready /= 2;
            return;
        }
        if (v.freeBeds() <= 0) return;
        v.ready = Math.min(1, v.ready + readyGain(data, v, today));
        if (RND.nextDouble() >= v.ready) return;
        v.ready = 0;
        addPerson(v, today, true);
    }

    /** How much readier for a child the village grows tonight. */
    static double readyGain(VillageData data, Village v, long today) {
        double f = Math.max(0, Math.min(1, (v.mood - BIRTH_MOOD) / (double) (100 - BIRTH_MOOD)));
        double gain = READY_LOW + (READY_HIGH - READY_LOW) * f;
        if (today - v.traded < TRADE_DAYS) gain += READY_TRADE;
        return gain + READY_LINK * links(data, v);
    }

    /** The villages finished trails join this one to (up to {@link #LINKS}). */
    static int links(VillageData data, Village v) {
        int n = 0;
        for (int id : Trails.reachWalkable(data, v.id)) if (id >= 0 && id != v.id && data.get(id) != null) n++;
        return Math.min(LINKS, n);
    }

    /** Adds someone to the village: a baby, or a grown-up walking in from the road. */
    static Dweller addPerson(Village v, long today, boolean baby) {
        Dweller d = new Dweller(v.nextDweller++, freshName(v), RND.nextInt(org.webtrade.minecraftportsmod.village.ResidentEntity.SKINS));
        d.joined = today;
        v.dwellers.add(d);
        if (baby) {
            d.born = today;
            // the parents: two grown-ups of the village
            List<Dweller> adults = new ArrayList<>();
            for (Dweller o : v.dwellers) if (o.job != null) adults.add(o);
            if (adults.size() >= 2) {
                java.util.Collections.shuffle(adults, new java.util.Random(RND.nextLong()));
                v.log(today, Component.translatable("minecraftportsmod.vlog.born_to", adults.get(0).name, adults.get(1).name, d.name)
                        .withStyle(ChatFormatting.DARK_GREEN));
            } else {
                v.log(today, Component.translatable("minecraftportsmod.vlog.born", d.name).withStyle(ChatFormatting.DARK_GREEN));
            }
        } else {
            d.arriving = true;
            d.job = chooseJob(v);
            v.log(today, Component.translatable("minecraftportsmod.vlog.arrived", d.name, d.job.displayName()).withStyle(ChatFormatting.DARK_GREEN));
        }
        v.lastGrowth = today;
        rehouse(v, today);
        return d;
    }

    /** A name nobody in the village has yet (if there is one left). */
    static String freshName(Village v) {
        String name = Residents.name(v.russian, RND);
        for (int i = 0; i < 20; i++) {
            boolean taken = false;
            for (Dweller d : v.dwellers) if (d.name.equals(name)) taken = true;
            if (!taken) return name;
            name = Residents.name(v.russian, RND);
        }
        return name;
    }

    /** Everyone gets a bed if there is one: houses first, then tents. People in tents move into houses. */
    /**
     * Everyone gets a bed if there is one. People of a trade live in their trade's house; the others in houses and
     * huts, then tents. People in tents move into houses as soon as there is room.
     */
    static void rehouse(Village v, long today) {
        List<Building> homes = new ArrayList<>();
        for (Building b : v.buildings) if (b.standing() && b.type.isHome()) homes.add(b);
        homes.sort(Comparator.comparingInt((Building b) -> b.type == BuildingType.TENT ? 1 : 0).thenComparingInt(b -> b.id));
        java.util.Map<Integer, Integer> free = new java.util.HashMap<>();
        for (Building b : homes) free.put(b.id, b.type.beds);
        List<Dweller> moving = new ArrayList<>();
        for (Dweller d : v.dwellers) {
            Building h = v.building(d.home);
            boolean fits = h != null && h.standing() && h.type != BuildingType.TENT && free.getOrDefault(h.id, 0) > 0
                    && (h.type.job == null || h.type.job == d.job);
            // a worker living elsewhere while their trade's house has a bed moves there
            boolean ownHouseFree = false;
            for (Building b : homes) if (b.type.job != null && b.type.job == d.job && b != h && free.getOrDefault(b.id, 0) > 0) ownHouseFree = true;
            if (fits && !(ownHouseFree && (h.type.job == null))) {
                free.merge(h.id, -1, Integer::sum);
            } else {
                moving.add(d);
            }
        }
        for (Dweller d : moving) {
            int before = d.home;
            d.home = -1;
            Building pick = null;
            // their trade's house first, then any house for everybody, then a tent
            for (Building b : homes) if (pick == null && b.type.job != null && b.type.job == d.job && free.getOrDefault(b.id, 0) > 0) pick = b;
            for (Building b : homes) if (pick == null && b.type.job == null && free.getOrDefault(b.id, 0) > 0) pick = b;
            if (pick != null) {
                free.merge(pick.id, -1, Integer::sum);
                d.home = pick.id;
            }
            Building from = v.building(before), to = pick;
            // worth telling: out of a tent into a house, or into the house of one's trade
            boolean news = from == null || from.type == BuildingType.TENT || to != null && to.type.job != null && from.type.job == null;
            if (to != null && before != -1 && to.id != before && to.type != BuildingType.TENT && news) {
                v.log(today, Component.translatable("minecraftportsmod.vlog.moved_in", d.name, to.type.displayName()));
            }
        }
    }

    // ------------------------------------------------------------------ levels

    /** One thing a level asks for. */
    public record Requirement(Component label, int have, int need) {
        public boolean ok() {
            return have >= need;
        }
    }

    public static List<Requirement> requirements(Village v, Village.Level next) {
        List<Requirement> out = new ArrayList<>();
        for (Tree.Condition c : Tree.level(v, next)) out.add(new Requirement(c.label(), c.have(), c.need()));
        return out;
    }

    /** The village's title follows its size; a bigger one is news. */
    private static void levelUp(ServerLevel level, Village v, long today) {
        Village.Level now = Village.Level.of(v.population());
        if (now.ordinal() <= v.level.ordinal()) {
            v.level = now;
            return;
        }
        Village.Level from = v.level, next = now;
        v.level = next;
        // (each level rewarded once: a village that shrank and grew again gets nothing twice)
        int gift = 0;
        for (Village.Level l : Village.Level.values()) if (l.ordinal() > v.rewarded && l.ordinal() <= next.ordinal()) gift += reward(l);
        if (next.ordinal() > v.rewarded) v.rewarded = next.ordinal();
        if (gift > 0) {
            v.emeralds += gift;
            VillageData.get(level.getServer()).minted += gift;
            v.log(today, Component.translatable("minecraftportsmod.vlog.level_reward", gift).withStyle(ChatFormatting.GREEN));
        }
        v.log(today, Component.translatable("minecraftportsmod.vlog.level_up", from.displayName(), next.displayName()).withStyle(ChatFormatting.GOLD));
        Component msg = Component.literal("⚑ ").withStyle(ChatFormatting.GOLD)
                .append(Component.translatable("minecraftportsmod.vlog.level_up_news", v.name, next.displayName()).withStyle(ChatFormatting.YELLOW));
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(v.center) < 160 * 160) {
                p.sendSystemMessage(msg);
                level.playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.6F, 1.0F);
            }
        }
    }

    // ------------------------------------------------------------------ what to build next

    /** The homes, best first: the most beds. */
    /** Homes (not tents), standing or on the way. */
    static int homes(Village v) {
        int n = 0;
        for (Building b : v.buildings) {
            if (b.state != Building.State.DEMOLISHING && b.type.branch == BuildingType.Branch.HOME && b.type != BuildingType.TENT) n++;
        }
        return n;
    }

    /** The trades' houses, standing or on the way. */
    static int workshops(Village v) {
        int n = 0;
        for (Building b : v.buildings) if (b.state != Building.State.DEMOLISHING && b.type.isWorkshop()) n++;
        return n;
    }

    /** Beds kept free for the next newcomers. */
    private static final int SPARE_BEDS = 2;

    private static final BuildingType[] HOMES = {BuildingType.STONE_HOUSE_TALL, BuildingType.HOUSE_TALL, BuildingType.STONE_HOUSE,
            BuildingType.HOUSE, BuildingType.HUT};

    /**
     * Decides what to do next (when the land around the village is loaded, to find a plot): open a node of the tree
     * the village can afford, then what the players asked for, homes, room in the store, the middle's next step, the
     * trades' houses and fields, raising buildings a level, and pulling down homes nobody needs any more.
     */
    static void plan(ServerLevel level, Village v, long today) {
        // (a village nobody is near plans too: its plots are read off the world's generator, see PlotFinder)
        autoUnlock(v, today);
        int active = 0;
        for (Building b : v.buildings) if (b.project() && !(b.state == Building.State.DEMOLISHING && b.finished)) active++;
        // (the queue: a few steps ahead, the materials for them kept back and bought for; not more)
        if (active >= queueSize(v)) return;
        boolean camp = v.population() < 4;

        // the players' wishes
        if (v.priority != null) {
            BuildingType wish = v.priority;
            if (wish.isCenter() ? Tree.centerReady(v) && startCenter(v, today) : Tree.open(v, wish) && start(level, v, wish, today)) {
                v.priority = null;
                return;
            }
        }
        if (v.raiseFirst >= 0) {
            Building b = v.building(v.raiseFirst);
            v.raiseFirst = -1;
            if (b != null && Tree.raise(v, b, today)) return;
        }
        int bedsToCome = 0, hutBeds = 0;
        for (Building b : v.buildings) {
            if (b.state == Building.State.DEMOLISHING) continue;
            bedsToCome += b.type.beds;
            if (b.type != BuildingType.TENT) hutBeds += b.type.beds;
        }
        boolean homeQueued = false;
        for (Building b : v.projects()) if (b.type.branch == BuildingType.Branch.HOME && b.state != Building.State.DEMOLISHING) homeQueued = true;

        if (!camp) {
            // everybody out of the tents, and the empty tents come down
            if (hutBeds < v.population() && !homeQueued && home(level, v, today)) return;
            if (v.houses() > 0 && demolishEmpty(v, BuildingType.TENT, today)) return;
        }
        // room for the next newcomer
        if (bedsToCome - v.population() < SPARE_BEDS && !homeQueued && home(level, v, today)) return;
        // never more trades' houses than homes: a home first
        if (!camp && !homeQueued && homes(v) <= workshops(v) && home(level, v, today)) return;
        // room in the store, if the village's needs do not fit in it (see storageShort): a storehouse it has raised a
        // level first, else a new one
        if (storageShort(v)) {
            for (Building b : v.buildings) {
                if (b.type.branch == BuildingType.Branch.STORE && Tree.canRaise(v, b) && Tree.affordable(v, Tree.levelCost(b.type, b.level + 1))) {
                    Tree.raise(v, b, today);
                    return;
                }
            }
            for (BuildingType t : new BuildingType[]{BuildingType.STOREHOUSE_2, BuildingType.STOREHOUSE}) {
                if (Tree.open(v, t) && start(level, v, t, today)) return;
            }
        }
        if (camp) return;
        // the middle's next step, once there are people enough and the store can pay for it
        if (Tree.centerReady(v) && Tree.affordable(v, Tree.centerNext(v).cost()) && startCenter(v, today)) return;
        // a first field, so that there can be farmers
        if (v.count(BuildingType.FIELD, false) == 0 && Tree.open(v, BuildingType.FIELD) && start(level, v, BuildingType.FIELD, today)) return;
        // a smithy: without one everybody works with bare hands
        if (v.count(BuildingType.SMITHY, false) == 0 && Tree.open(v, BuildingType.SMITHY) && start(level, v, BuildingType.SMITHY, today)) return;
        // the trades' houses, for the trades the village has people in
        for (BuildingType t : new BuildingType[]{BuildingType.MINE_HOUSE, BuildingType.WOOD_HUT, BuildingType.FARM}) {
            if (v.workers(t.job) > 0 && Tree.open(v, t) && start(level, v, t, today)) return;
        }
        // a fishers' hut on the shore, for a village of some size (its fisher comes with the next one to grow up)
        if (v.adults() >= 6 && v.count(BuildingType.FISH_HUT, false) == 0 && Tree.open(v, BuildingType.FISH_HUT)
                && start(level, v, BuildingType.FISH_HUT, today)) return;
        // a stall for trading
        if (v.adults() >= 4 && Tree.open(v, BuildingType.MARKET) && start(level, v, BuildingType.MARKET, today)) return;
        // a sawmill once planks or sticks are short (by hand they come slowly)
        if ((want(v, Res.PLANKS) > 0.3 || want(v, Res.STICKS) > 0.3) && Tree.open(v, BuildingType.SAWMILL)
                && start(level, v, BuildingType.SAWMILL, today)) return;
        // a joiner's workshop once joinery is short (the houses are built with it)
        if (want(v, Res.JOINERY) > 0.3 && Tree.open(v, BuildingType.CARPENTER) && start(level, v, BuildingType.CARPENTER, today)) return;
        // the cartographer's house, for a village big enough to spare a scout
        if (v.adults() >= 5 && Tree.open(v, BuildingType.CARTOGRAPHER) && start(level, v, BuildingType.CARTOGRAPHER, today)) return;
        // more fields for the farmers
        if (v.workers(Job.FARMER) > v.count(BuildingType.FIELD, false) * 2 && Tree.open(v, BuildingType.FIELD)
                && start(level, v, BuildingType.FIELD, today)) return;
        // a building raised a level, once the store can pay for it and keep a reserve
        Building raise = toRaise(v);
        if (raise != null && Tree.raise(v, raise, today)) return;
        // a village with plenty to spare builds its people better homes: the huts they leave come down after
        // (only until the beds outside the huts are enough for everyone)
        int betterBeds = 0;
        for (Building b : v.buildings) {
            // (the trades' houses have beds too: they count)
            if (b.state != Building.State.DEMOLISHING && b.type != BuildingType.HUT && b.type != BuildingType.TENT) betterBeds += b.type.beds;
        }
        if (!homeQueued && v.count(BuildingType.HUT, true) > 0) {
            for (BuildingType t : HOMES) {
                if (t == BuildingType.HUT || !Tree.open(v, t) || !spare(v, Tree.price(v, t))) continue;
                // a hut rebuilt into the better house where it stands (the middle of the village stays lived in)
                if (rebuildHut(level, v, t, today)) return;
                // no hut with room round it for that: a house on a new plot, while beds are short
                if (betterBeds < v.population() + SPARE_BEDS && start(level, v, t, today)) return;
                break;
            }
        }
    }

    /**
     * One hut (the nearest the middle first) rebuilt into a house of this kind on its own plot: the hut comes down
     * once the materials are there, its people sleep elsewhere meanwhile (so only while there are beds to spare).
     */
    private static boolean rebuildHut(ServerLevel level, Village v, BuildingType t, long today) {
        List<Building> huts = new ArrayList<>();
        for (Building b : v.buildings) {
            if (b.type == BuildingType.HUT && b.standing() && !b.keep && !b.upgrading() && b.state == Building.State.BUILT) huts.add(b);
        }
        huts.sort(Comparator.comparingDouble(b -> b.origin.distSqr(v.center)));
        for (Building hut : huts) {
            if (v.freeBeds() < hut.type.beds) return false;
            Blueprint.Frame f = PlotFinder.inPlace(level, v, t, hut);
            if (f == null) continue;
            Building b = new Building(v.nextBuilding++, t, f.origin(), f.front(), RND.nextLong());
            b.replaces = hut.id;
            add(v, b, today);
            return true;
        }
        return false;
    }

    /**
     * Pulls down one building of this kind that nobody would miss: empty, or its people can move elsewhere; never one
     * the players asked to keep.
     */
    private static boolean demolishEmpty(Village v, BuildingType type, long today) {
        // (a hut is a home: not taken down if the trades' houses would then be as many)
        if (type == BuildingType.HUT && homes(v) - 1 <= workshops(v)) return false;
        for (Building b : v.buildings) {
            if (b.type != type || !b.standing() || b.keep || b.upgrading()) continue;
            if (v.freeBeds() - b.type.beds < 0) continue;
            b.state = Building.State.DEMOLISHING;
            b.work = 0;
            v.log(today, Component.translatable("minecraftportsmod.vlog.demolish", b.type.displayName()));
            rehouse(v, today);
            return true;
        }
        return false;
    }

    /** What to spend on raising a level: materials over what the village wants to keep in store. */
    private static boolean spare(Village v, java.util.Map<Res, Integer> cost) {
        for (var e : cost.entrySet()) {
            // (planks and sticks are made to be used: no reserve of them is kept back)
            int keep = e.getKey() == Res.PLANKS || e.getKey() == Res.STICKS ? 0 : target(v, e.getKey()) / 2;
            if (v.stock(e.getKey()) - e.getValue() < keep) return false;
        }
        return true;
    }

    /**
     * The building most worth raising a level that the village can spare the materials for: the trade of its
     * speciality first, then the other trades, the homes, the stores and fields.
     */
    static Building toRaise(Village v) {
        Building best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Building b : v.buildings) {
            if (!Tree.canRaise(v, b) || !spare(v, Tree.levelCost(b.type, b.level + 1))) continue;
            int score = b.type.branch == v.focus ? 40 : b.type.isWorkshop() ? 30 : b.type.branch == BuildingType.Branch.HOME ? 20 : 10;
            // short of planks and no sawmill yet: the woodcutters' hut grown to the top is what opens it
            if (b.type == BuildingType.WOOD_HUT && !v.unlocked(BuildingType.SAWMILL) && (want(v, Res.PLANKS) > 0.2 || want(v, Res.JOINERY) > 0.3)) score = 55;
            // wooden tools only: the smithy grown a level makes stone ones, that last six times as long
            if (b.type == BuildingType.SMITHY && b.level < 2 && toolUsers(v) >= 4) score = 52;
            // short of beds and the next kind of house not open yet: a home grown to the top is what opens it
            if (b.type.branch == BuildingType.Branch.HOME && v.freeBeds() <= 2) {
                boolean nextShut = false;
                for (BuildingType k : b.type.children()) if (!v.unlocked(k)) nextShut = true;
                if (nextShut) score = 50;
            }
            score -= 5 * b.level;
            if (score > bestScore) {
                bestScore = score;
                best = b;
            }
        }
        return best;
    }

    /**
     * The village opens a node of the tree by itself when it can pay and still keep a reserve: its homes and stores,
     * the first house of a trade it has people in, and anything in the trade it goes deep into. The rest of the tree
     * is left to the players (or to trade).
     */
    static void autoUnlock(Village v, long today) {
        // the research in hand: opened once the store has all it costs (the building sites have had theirs first)
        if (v.research != null) {
            if (Tree.unlocked(v, v.research) || Tree.node(v, v.research) != Tree.Node.READY) {
                v.research = null;
            } else if (Tree.affordable(v, Tree.unlockCost(v, v.research))) {
                BuildingType t = v.research;
                v.research = null;
                if (Tree.unlock(v, t)) v.log(today, Component.translatable("minecraftportsmod.vlog.unlocked", t.displayName()).withStyle(ChatFormatting.GOLD));
            }
            return;
        }
        BuildingType best = null;
        int bestScore = Integer.MIN_VALUE;
        for (BuildingType t : BuildingType.values()) {
            if (!t.isNode() || Tree.node(v, t) != Tree.Node.READY || declined(v, "research:" + t.id(), today)) continue;
            boolean mine = t.branch == v.focus || t.branch == BuildingType.Branch.HOME || t.branch == BuildingType.Branch.STORE
                    || t.parent == null && (t.job != null && v.workers(t.job) > 0 || t == BuildingType.MARKET && v.adults() >= 4)
                    || t == BuildingType.FARM && v.workers(Job.FARMER) > 0
                    || t == BuildingType.MINE_HOUSE && v.workers(Job.MINER) > 0
                    || t == BuildingType.WOOD_HUT && v.workers(Job.WOODCUTTER) > 0
                    || t == BuildingType.FISH_HUT && v.adults() >= 6
                    || t == BuildingType.SAWMILL && (want(v, Res.PLANKS) > 0.3 || want(v, Res.STICKS) > 0.3 || want(v, Res.JOINERY) > 0.3)
                    || t == BuildingType.CARPENTER && want(v, Res.JOINERY) > 0.3
                    || t == BuildingType.CARTOGRAPHER && v.adults() >= 5;
            if (!mine) continue;
            int score = (t.branch == BuildingType.Branch.HOME && v.freeBeds() <= 1 ? 50 : 0) + (t.branch == v.focus ? 30 : 0) - t.depth() * 5;
            // (the stall and the map table open the way to the other villages: early, once there are people for them)
            if (t == BuildingType.MARKET) score += 35;
            if (t == BuildingType.CARTOGRAPHER && v.known.isEmpty()) score += 40;
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        if (best == null) return;
        // (a step of the queue: what it costs is kept back, and bought for, from now on)
        v.research = best;
        v.log(today, Component.translatable("minecraftportsmod.vlog.research_planned", best.displayName()).withStyle(ChatFormatting.GRAY));
    }

    /**
     * The trade a village goes deep into, going by the land around it: rock to quarry, a forest, the water, open
     * meadows. (A village that isn't loaded: by what its people do.)
     */
    static BuildingType.Branch chooseFocus(ServerLevel level, Village v) {
        int stone = 0, wood = 0, water = 0, grass = 0;
        if (Construction.loaded(level, v.center)) {
            for (int x = -32; x <= 32; x += 2) {
                for (int z = -32; z <= 32; z += 2) {
                    int wx = v.center.getX() + x, wz = v.center.getZ() + z;
                    if (!Construction.loaded(level, new BlockPos(wx, 0, wz))) continue;
                    int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, wx, wz) - 1;
                    var st = level.getBlockState(new BlockPos(wx, top, wz));
                    if (st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.LOGS)) wood++;
                    else if (!st.getFluidState().isEmpty()) water++;
                    else if (DwellerGoals.rock(st)) stone++;
                    else if (st.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)) grass++;
                }
            }
        }
        // the water is by every camp: it counts for less; meadows are the most common land, too
        int[] score = {stone * 3, wood * 2, water, grass};
        if (stone + wood + water + grass == 0) {
            // nobody near: the land as the world's generator has it
            Land.Shares land = Land.known(v);
            score = new int[]{(int) (land.rock() * 300), (int) (land.forest() * 200), (int) (land.water() * 100), (int) (land.meadow() * 100)};
        }
        // (the water and the meadows both mean food)
        BuildingType.Branch[] branches = {BuildingType.Branch.MINE, BuildingType.Branch.WOOD, BuildingType.Branch.FOOD, BuildingType.Branch.FOOD};
        int best = 0;
        for (int i = 1; i < 4; i++) if (score[i] > score[best]) best = i;
        return branches[best];
    }

    /** A new home: the best kind of house open to the village that it can best pay for. */
    private static boolean home(ServerLevel level, Village v, long today) {
        BuildingType pick = null;
        int bestBeds = 0, bestShort = Integer.MAX_VALUE;
        for (BuildingType t : HOMES) {
            if (!Tree.open(v, t)) continue;
            int shortfall = 0;
            // (what it costs this village: the tenth hut dear enough that a house is the better buy)
            java.util.Map<Res, Integer> price = Tree.price(v, t);
            for (Res r : Res.values()) shortfall += Math.max(0, price.getOrDefault(r, 0) - v.stock(r));
            if (t.beds > bestBeds || t.beds == bestBeds && shortfall < bestShort) {
                bestBeds = t.beds;
                bestShort = shortfall;
                pick = t;
            }
        }
        return pick != null && start(level, v, pick, today);
    }

    /** The middle of the village is rebuilt into its next step, in place. */
    static boolean startCenter(Village v, long today) {
        Building old = Tree.center(v);
        BuildingType next = Tree.centerNext(v);
        if (old == null || next == null) return false;
        if (next.half > old.type.half) {
            for (Building o : v.buildings) if (o != old && o.overlaps(old.origin, next.half, 0)) return false;
        }
        Building b = new Building(v.nextBuilding++, next, old.origin, old.front, RND.nextLong());
        b.replaces = old.id;
        add(v, b, today);
        return true;
    }

    /**
     * A step taken out of the queue by the players, if it has not begun: a site not built on yet (it goes, what was
     * brought to it back to the store), or a level not begun to raise. Not put back by the village for a few days.
     */
    static boolean cancel(Village v, Building b, long today) {
        if (b == null) return false;
        if (b.state == Building.State.PLANNED) {
            for (Res r : Res.values()) if (b.delivered(r) > 0) v.add(r, b.delivered(r));
            v.buildings.remove(b);
            v.order.remove((Integer) b.id);
            v.declined.put("build:" + b.type.id(), today);
            return true;
        }
        if (b.upgrading() && !b.supplied()) {
            for (Res r : Res.values()) if (b.delivered(r) > 0) v.add(r, b.delivered(r));
            b.delivered.clear();
            b.price.clear();
            b.goal = 0;
            b.work = b.blueprint(v.wood).pieces.size();
            v.order.remove((Integer) b.id);
            v.declined.put("raise:" + b.id, today);
            return true;
        }
        return false;
    }

    /** Did the players take this step out of the queue lately? */
    static boolean declined(Village v, String step, long today) {
        Long day = v.declined.get(step);
        return day != null && today - day < DECLINED_DAYS;
    }

    /** Days a step the players took out of the queue is not put back by the village. */
    static final int DECLINED_DAYS = 5;

    /** Starts a building of this kind on a new plot. */
    static boolean start(ServerLevel level, Village v, BuildingType type, long today) {
        // (one the players took out of the queue: not again for a few days)
        if (declined(v, "build:" + type.id(), today)) return false;
        if (type.isCenter()) return startCenter(v, today);
        // a trade's house only while the homes outnumber them
        if (type.isWorkshop() && homes(v) <= workshops(v)) return false;
        Blueprint.Frame f = PlotFinder.find(level, v, type, -1);
        if (f == null) return false;
        add(v, new Building(v.nextBuilding++, type, f.origin(), f.front(), RND.nextLong()), today);
        return true;
    }

    private static void add(Village v, Building b, long today) {
        b.created = today;
        v.order.add(b.id);
        b.price.putAll(Tree.price(v, b.type));
        v.buildings.add(b);
        v.log(today, Component.translatable(b.replaces >= 0 ? "minecraftportsmod.vlog.planned_upgrade" : "minecraftportsmod.vlog.planned",
                b.type.displayName()));
    }

    /** Days until a building site is done, roughly: materials still to come, then the work. -1 if never. */
    public static int eta(Village v, Building b) {
        if (b.state == Building.State.BUILT) return 0;
        double days = 0;
        if (b.state == Building.State.PLANNED) {
            for (Res r : Res.values()) {
                int missing = b.missing(r) - v.stock(r);
                if (missing <= 0) continue;
                int per = production(v, r);
                if (per <= 0) return -1;
                days = Math.max(days, (double) missing / per);
            }
        }
        int total = b.blueprint(v.wood).pieces.size();
        int left = b.state == Building.State.DEMOLISHING || b.state == Building.State.BUILDING ? Math.max(0, total - b.work) : total;
        days += (double) left / (OFFLINE_WORK * Math.max(1, Math.min(DwellerGoals.Build.CREW, v.adults())));
        return (int) Math.ceil(days);
    }

    /** Where a village has room for someone walking in: a point on the land at the edge. */
    static BlockPos edge(ServerLevel level, Village v) {
        for (int i = 0; i < 12; i++) {
            double a = RND.nextDouble() * Math.PI * 2;
            int x = v.center.getX() + (int) (Math.cos(a) * 30), z = v.center.getZ() + (int) (Math.sin(a) * 30);
            if (!Construction.loaded(level, new BlockPos(x, 0, z))) continue;
            int y = PlotFinder.floorAt(level, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (level.getBlockState(p.below()).getFluidState().isEmpty()) return p;
        }
        return v.center;
    }
}
