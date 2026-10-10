package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * The animals of the farmyard: four fenced runs of four animals each, hens, sheep, pigs or cows, which kind in each
 * the player's choice (or, left to it, the village's: what it is short of first, then what the others want of it).
 * The farmyard's levels open the runs: two, three, four. A run given over to another kind stands empty a day (the
 * new animals are looked for and brought), then is stocked with grown ones; after that a herd keeps itself up by its
 * young, one now and then, while someone is near to see it.
 */
final class Herds {

    private Herds() {
    }

    private static final RandomSource RND = RandomSource.create();

    /**
     * One run of the farmyard, in the plot's own coordinates (its fence: x from {@code x0} to {@code x1}, z from
     * {@code z0} to {@code z1}), the level that adds it, its animals and how many it keeps.
     */
    record Run(int index, int level, EntityType<? extends Animal> kind, int head, int x0, int x1, int z0, int z1) {
        /** Is a point (the plot's coordinates) inside the fence? */
        boolean inside(double lx, double lz) {
            return lx > x0 && lx < x1 && lz > z0 && lz < z1;
        }
    }

    /** The front edge of the runs (the yard and the lodge are in front of it). */
    static final int FRONT = 1, MIDDLE = -3;

    /** The runs of a farmyard (its slots), whether open or not yet. */
    public static final int SLOTS = 4;
    /** The animals a run keeps. */
    static final int HEAD = 4;
    /** The kinds a run can keep: 1 hens, 2 sheep, 3 pigs, 4 cows (0: none yet). */
    public static final int KINDS = 4;

    /** The four runs, in the plot's own coordinates. */
    static List<Run> places(int h) {
        List<Run> out = new ArrayList<>();
        out.add(new Run(0, 1, EntityTypes.CHICKEN, HEAD, -h, 0, MIDDLE, FRONT));
        out.add(new Run(1, 1, EntityTypes.CHICKEN, HEAD, 0, h, MIDDLE, FRONT));
        out.add(new Run(2, 2, EntityTypes.CHICKEN, HEAD, -h, 0, -h, MIDDLE));
        out.add(new Run(3, 3, EntityTypes.CHICKEN, HEAD, 0, h, -h, MIDDLE));
        return out;
    }

    /** The runs open at a farmyard's level: two, three, four. */
    public static int open(int level) {
        return Math.min(SLOTS, 1 + Math.max(1, level));
    }

    /**
     * A farmyard's runs: what each keeps ({@code kind}, 0 none), what it is being given over to ({@code next}, -1:
     * nothing; ready at the village tick {@code ready}, a whole day after it was chosen), whether a player chose it ({@code picked}: else the village does), and
     * whether it has been stocked since. Kept in the building's setting.
     */
    record Slots(int[] kind, int[] next, long[] ready, boolean[] picked, boolean[] stocked) {
        static Slots of(Building b) {
            Slots s = new Slots(new int[SLOTS], new int[]{-1, -1, -1, -1}, new long[SLOTS], new boolean[SLOTS], new boolean[SLOTS]);
            String o = b.option();
            if (o == null) return s;
            for (String part : o.split(";")) {
                String[] kv = part.split(":", 2);
                if (kv.length < 2 || !kv[0].startsWith("run") || kv[0].length() != 4 || !Character.isDigit(kv[0].charAt(3))) continue;
                int i = kv[0].charAt(3) - '0';
                if (i >= SLOTS) continue;
                String[] f = kv[1].split(",");
                try {
                    s.kind[i] = Math.max(0, Math.min(KINDS, Integer.parseInt(f[0])));
                    s.next[i] = f.length > 1 ? Integer.parseInt(f[1]) : -1;
                    s.ready[i] = f.length > 2 ? Long.parseLong(f[2]) : 0;
                    s.picked[i] = f.length > 3 && f[3].equals("1");
                    s.stocked[i] = f.length > 4 && f[4].equals("1");
                } catch (NumberFormatException ignored) {
                }
            }
            return s;
        }

        void save(Building b) {
            StringBuilder o = new StringBuilder();
            for (int i = 0; i < SLOTS; i++) {
                o.append("run").append(i).append(':').append(kind[i]).append(',').append(next[i]).append(',').append(ready[i]).append(',')
                        .append(picked[i] ? 1 : 0).append(',').append(stocked[i] ? 1 : 0).append(';');
            }
            b.setOption(o.toString());
        }

        /** Is a run being given over to another kind (empty meanwhile)? */
        boolean changing(int i) {
            return next[i] >= 0;
        }
    }

    /** Every run of a farmyard given a kind at once, as the village's choice (the sandbox). */
    static void fill(Building b, int kind) {
        Slots s = Slots.of(b);
        java.util.Arrays.fill(s.kind, kind);
        s.save(b);
    }

    /**
     * For the farmyard's window, each run's {kind (-1: not open at its level), the kind it is being given over to (-1:
     * none), the seconds till then, chosen by a player (1) or the village (0)}, one after another.
     */
    static int[] view(VillageData data, Building b) {
        Slots s = Slots.of(b);
        int[] out = new int[SLOTS * 4];
        for (int i = 0; i < SLOTS; i++) {
            boolean open = i < open(b.level);
            out[i * 4] = open ? s.kind[i] : -1;
            out[i * 4 + 1] = open ? s.next[i] : -1;
            long ticks = s.changing(i) ? s.ready[i] - now(data) : 0;
            out[i * 4 + 2] = (int) Math.max(0, ticks / 20);
            out[i * 4 + 3] = s.picked[i] ? 1 : 0;
        }
        return out;
    }

    /** The runs a farmyard keeps animals in now: open at its level, with a kind, not being given over to another. */
    static List<Run> runs(Building b) {
        List<Run> out = new ArrayList<>();
        Slots s = Slots.of(b);
        int open = open(b.level);
        for (Run r : places(b.type.half)) {
            if (r.index >= open || s.kind[r.index] == 0 || s.changing(r.index)) continue;
            out.add(new Run(r.index, r.level, kind(s.kind[r.index]), HEAD, r.x0, r.x1, r.z0, r.z1));
        }
        return out;
    }

    /** The fences a farmyard stands with: the runs open at its level, whatever they keep. */
    static List<Run> fences(Building b) {
        List<Run> out = new ArrayList<>();
        for (Run r : places(b.type.half)) if (r.index < open(b.level)) out.add(r);
        return out;
    }

    static EntityType<? extends Animal> kind(int k) {
        return switch (k) {
            case 1 -> EntityTypes.CHICKEN;
            case 2 -> EntityTypes.SHEEP;
            case 3 -> EntityTypes.PIG;
            case 4 -> EntityTypes.COW;
            default -> null;
        };
    }

    /** What a run of four of a kind gives in a day: {food, wool, hides, grain eaten}. */
    static int[] yield(EntityType<?> kind) {
        if (kind == EntityTypes.CHICKEN) return new int[]{3, 0, 0, 3};
        if (kind == EntityTypes.SHEEP) return new int[]{0, 4, 0, 4};
        if (kind == EntityTypes.PIG) return new int[]{5, 0, 0, 4};
        return new int[]{4, 0, 2, 6};
    }

    /** What a kind gives (for choosing one): its first good, and its second (null: none). */
    static Res[] goods(int k) {
        return switch (k) {
            case 2 -> new Res[]{Res.WOOL, null};
            case 4 -> new Res[]{Res.LEATHER, Res.FOOD};
            default -> new Res[]{Res.FOOD, null};
        };
    }

    /**
     * A player gives a run over to a kind: not while it is being given over already, nor to what it keeps. The run
     * stands empty till the next day. True if done.
     */
    static boolean choose(VillageData data, Building b, int slot, int kind) {
        Slots s = Slots.of(b);
        if (slot < 0 || slot >= open(b.level) || kind < 1 || kind > KINDS || s.changing(slot) || s.kind[slot] == kind) return false;
        s.next[slot] = kind;
        s.ready[slot] = now(data) + data.dayLength();
        s.picked[slot] = true;
        s.save(b);
        return true;
    }

    /**
     * Every morning: the runs given over yesterday take their new kind (stocked once someone is near); a run open with
     * nothing chosen for it, or one the village chose whose goods it has plenty of while it wants another's, the
     * village gives over to what it is short of first, then to what its neighbours are short of (to sell them).
     */
    static void day(VillageData data, Village v, Building b) {
        Slots s = Slots.of(b);
        boolean changed = arrive(data, s);
        // (what the village is choosing today counts against choosing it again)
        int[] taken = new int[KINDS + 1];
        for (int i = 0; i < open(b.level); i++) taken[s.changing(i) ? s.next[i] : s.kind[i]]++;
        for (int i = 0; i < open(b.level); i++) {
            if (s.picked[i] || s.changing(i)) continue;
            int best = bestKind(data, v, taken);
            boolean empty = s.kind[i] == 0;
            if (best == 0 || best == s.kind[i]) continue;
            // (a run already kept is given over only when its goods are not wanted at all and another's are)
            if (!empty && (score(data, v, s.kind[i], taken) > 0.05 || score(data, v, best, taken) < 0.3)) continue;
            taken[s.kind[i]]--;
            taken[best]++;
            s.next[i] = best;
            s.ready[i] = now(data) + data.dayLength();
            changed = true;
        }
        if (changed) s.save(b);
    }

    /** The village's clock, in ticks from its first day. */
    static long now(VillageData data) {
        return data.day() * data.dayLength() + data.dayTicks();
    }

    /** The runs whose new animals have come (a day after they were chosen) take them. True if any did. */
    private static boolean arrive(VillageData data, Slots s) {
        boolean changed = false;
        for (int i = 0; i < SLOTS; i++) {
            if (s.changing(i) && now(data) >= s.ready[i]) {
                s.kind[i] = s.next[i];
                s.next[i] = -1;
                s.stocked[i] = false;
                changed = true;
            }
        }
        return changed;
    }

    private static int bestKind(VillageData data, Village v, int[] taken) {
        int best = 0;
        double top = 0;
        for (int k = 1; k <= KINDS; k++) {
            double sc = score(data, v, k, taken);
            if (sc > top) {
                top = sc;
                best = k;
            }
        }
        // (nothing wanted anywhere: hens, the plainest)
        return best == 0 ? 1 : best;
    }

    /**
     * How much a kind is wanted: what the village itself is short of first (its want of the kind's goods); else how
     * many of its neighbours are short of them (a tenth each), to be sold them: a food village's herds only (the
     * others keep animals for their own wants); less for each run of it already.
     */
    private static double score(VillageData data, Village v, int k, int[] taken) {
        boolean seller = v.focus == BuildingType.Branch.FOOD;
        double own = 0, sale = 0;
        for (Res r : goods(k)) {
            if (r == null) continue;
            own = Math.max(own, VillageLife.want(v, r));
            int short_ = 0;
            for (int id : v.known.keySet()) {
                Village o = data.get(id);
                if (o != null && Caravans.short_(o, r) > 0) short_++;
            }
            if (seller) sale = Math.max(sale, 0.1 * short_);
        }
        double sc = own > 0.3 ? 1 + own : Math.min(0.9, sale);
        return sc / (1 + taken[k]);
    }

    /** The mark the animals of a run carry. */
    static String tag(Village v, Building b, Run r) {
        return "mpm_herd_" + v.id + "_" + b.id + "_" + r.index;
    }

    private static String prefix(Village v, Building b) {
        return "mpm_herd_" + v.id + "_" + b.id + "_";
    }

    /** All the animals of a farmyard. */
    static List<Animal> animals(ServerLevel level, Village v, Building b) {
        String p = prefix(v, b);
        return level.getEntitiesOfClass(Animal.class, new AABB(b.origin).inflate(40, 16, 40), a -> a.isAlive() && hasTag(a, p));
    }

    private static boolean hasTag(Animal a, String prefix) {
        for (String t : a.entityTags()) if (t.startsWith(prefix)) return true;
        return false;
    }

    /** The run an animal of the farmyard belongs to (null: none of its). */
    static Run runOf(Village v, Building b, Animal a) {
        for (Run r : runs(b)) if (a.entityTags().contains(tag(v, b, r))) return r;
        return null;
    }

    static List<Animal> animals(ServerLevel level, Village v, Building b, Run r) {
        String tag = tag(v, b, r);
        return level.getEntitiesOfClass(Animal.class, new AABB(b.origin).inflate(40, 16, 40), a -> a.isAlive() && a.entityTags().contains(tag));
    }

    /** A place inside a run (in the world), or null. */
    static BlockPos spot(ServerLevel level, Building b, Run r) {
        Blueprint.Frame f = new Blueprint.Frame(b.origin, b.front);
        for (int i = 0; i < 12; i++) {
            int lx = r.x0 + 1 + RND.nextInt(Math.max(1, r.x1 - r.x0 - 1)), lz = r.z0 + 1 + RND.nextInt(Math.max(1, r.z1 - r.z0 - 1));
            BlockPos p = f.at(lx, 0, lz);
            BlockPos stand = PlotFinder.standAt(level, p.getX(), p.getZ(), b.origin.getY());
            if (stand != null) return stand;
        }
        return null;
    }

    /**
     * Where the keeper stands to call an animal of a run over: outside the fence, on the side of the run that is not
     * another run's (the plot's edge, or the yard in front), the nearest to the animal. Plot coordinates {x, z}.
     */
    static int[] keeperSpot(Building b, Run r, double lx, double lz) {
        int h = b.type.half;
        List<int[]> sides = new ArrayList<>();
        int midX = (int) Math.round(Math.max(r.x0 + 1, Math.min(r.x1 - 1, lx))), midZ = (int) Math.round(Math.max(r.z0 + 1, Math.min(r.z1 - 1, lz)));
        if (r.x0 == -h) sides.add(new int[]{-h - 1, midZ});
        if (r.x1 == h) sides.add(new int[]{h + 1, midZ});
        if (r.z0 == -h) sides.add(new int[]{midX, -h - 1});
        if (r.z1 == FRONT) sides.add(new int[]{midX, FRONT + 1});
        int[] best = sides.getFirst();
        double bestD = Double.MAX_VALUE;
        for (int[] s : sides) {
            double d = (s[0] - lx) * (s[0] - lx) + (s[1] - lz) * (s[1] - lz);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    /** Every few seconds while the village is watched: each run of a standing farmyard stocked, or a young one born to it. */
    static void step(ServerLevel level, Village v) {
        for (Building b : v.buildings) {
            if (!b.type.isPen() || !b.standing() || !Construction.loaded(level, b.origin)) continue;
            VillageData data = VillageData.get(level.getServer());
            Slots due = Slots.of(b);
            if (arrive(data, due)) {
                due.save(b);
                data.changed();
            }
            // (a run being given over to another kind, or with none: its animals sold off, the run empty)
            java.util.Set<String> kept = new java.util.HashSet<>();
            for (Run r : runs(b)) kept.add(tag(v, b, r));
            for (Animal a : animals(level, v, b)) {
                boolean ours = false;
                for (String t : a.entityTags()) ours |= kept.contains(t);
                if (!ours) a.discard();
            }
            Slots slots = Slots.of(b);
            for (Run r : runs(b)) {
                List<Animal> herd = animals(level, v, b, r);
                // (a run given over to another herd: the animals it had are sold off)
                for (Animal a : new ArrayList<>(herd)) {
                    if (a.getType() != r.kind) {
                        a.discard();
                        herd.remove(a);
                    }
                }
                if (herd.size() >= r.head) continue;
                // a new run: stocked once with grown animals (brought from the market); a herd short of its number: a
                // young one now and then; a run left with none (killed, run off): a young one bought in, rarely
                boolean stock = !slots.stocked()[r.index];
                int n = stock ? r.head - herd.size() : RND.nextInt(herd.isEmpty() ? 30 : 6) == 0 ? 1 : 0;
                if (stock) {
                    slots.stocked()[r.index] = true;
                    slots.save(b);
                    VillageData.get(level.getServer()).changed();
                }
                for (int i = 0; i < n; i++) {
                    BlockPos at = spot(level, b, r);
                    if (at == null) break;
                    Animal a = r.kind.create(level, EntitySpawnReason.BREEDING);
                    if (a == null) break;
                    a.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, RND.nextFloat() * 360, 0);
                    a.setPersistenceRequired();
                    a.addTag(tag(v, b, r));
                    if (!stock) a.setAge(-24000);
                    level.addFreshEntity(a);
                }
            }
        }
    }
}
