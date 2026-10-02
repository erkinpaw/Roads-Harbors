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
 * The animals of the farmyard: real hens, sheep, pigs and cows, each kind in a fenced run of its own, as many as the
 * run keeps. The farmyard's levels add runs: hens first; sheep and pigs; then cows. A new run is stocked with grown
 * animals (brought from the market, as it were); after that a herd keeps itself up by its young, one now and then,
 * while someone is near to see it.
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

    /** The farmyard's runs, all of them (each stands from its level on). */
    static List<Run> runs(int h) {
        List<Run> out = new ArrayList<>();
        out.add(new Run(0, 1, EntityTypes.CHICKEN, 5, -h, 0, MIDDLE, FRONT));
        out.add(new Run(1, 2, EntityTypes.SHEEP, 3, 0, h, MIDDLE, FRONT));
        out.add(new Run(2, 2, EntityTypes.PIG, 3, -h, 0, -h, MIDDLE));
        out.add(new Run(3, 3, EntityTypes.COW, 3, 0, h, -h, MIDDLE));
        return out;
    }

    /** The runs a farmyard has at its level. */
    static List<Run> runs(Building b) {
        List<Run> out = new ArrayList<>();
        for (Run r : runs(b.type.half)) if (r.level <= Math.max(1, b.level)) out.add(r);
        return out;
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
            for (Run r : runs(b)) {
                List<Animal> herd = animals(level, v, b, r);
                if (herd.size() >= r.head) continue;
                // a new run: stocked once with grown animals (brought from the market); a herd short of its number: a
                // young one now and then; a run left with none (killed, run off): a young one bought in, rarely
                String mark = "stocked" + r.index;
                boolean stock = herd.isEmpty() && (b.option == null || !b.option.contains(mark));
                int n = stock ? r.head : RND.nextInt(herd.isEmpty() ? 30 : 6) == 0 ? 1 : 0;
                if (stock) {
                    b.option = (b.option == null ? "" : b.option) + mark + ";";
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
