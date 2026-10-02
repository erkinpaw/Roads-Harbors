package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * The animals of the village's runs: real hens, sheep and cows inside the fence, as many as the run's level keeps.
 * A new run is stocked with grown animals (brought from the market, as it were); after that the herd keeps itself
 * up by its young, one now and then, while someone is near to see it.
 */
final class Herds {

    private Herds() {
    }

    private static final RandomSource RND = RandomSource.create();
    /** The run's setting once it has had its first animals. */
    static final String STOCKED = "stocked";

    /** The animals a run of this kind keeps at a level. */
    static int size(BuildingType t, int level) {
        int l = Math.max(1, level);
        return switch (t) {
            case COOP -> 4 + 2 * (l - 1);
            case SHEEP_PEN -> 3 + 2 * (l - 1);
            case CATTLE_BARN -> 2 + 2 * (l - 1);
            default -> 0;
        };
    }

    static EntityType<? extends Animal> kind(BuildingType t) {
        return switch (t) {
            case COOP -> EntityTypes.CHICKEN;
            case SHEEP_PEN -> EntityTypes.SHEEP;
            default -> EntityTypes.COW;
        };
    }

    /** The mark the animals of a run carry. */
    static String tag(Village v, Building b) {
        return "mpm_herd_" + v.id + "_" + b.id;
    }

    /**
     * The fenced run inside a plot, in local coordinates: x from {@code -h+1} to {@code h-1}, z from {@code -h+1} to
     * {@code h-6} (the keeper's lodge and the yard are in front of it).
     */
    static boolean inRun(Building b, int lx, int lz) {
        int h = b.type.half;
        return lx > -h && lx < h && lz > -h && lz < h - 5;
    }

    static List<Animal> animals(ServerLevel level, Village v, Building b) {
        String tag = tag(v, b);
        return level.getEntitiesOfClass(Animal.class, new AABB(b.origin).inflate(40, 16, 40), a -> a.isAlive() && a.entityTags().contains(tag));
    }

    /** A place inside the run (in the world), or null. */
    static BlockPos spot(ServerLevel level, Village v, Building b) {
        Blueprint.Frame f = new Blueprint.Frame(b.origin, b.front);
        int h = b.type.half;
        for (int i = 0; i < 12; i++) {
            int lx = -h + 2 + RND.nextInt(2 * h - 3), lz = -h + 2 + RND.nextInt(Math.max(1, 2 * h - 8));
            if (!inRun(b, lx, lz)) continue;
            BlockPos p = f.at(lx, 0, lz);
            BlockPos stand = PlotFinder.standAt(level, p.getX(), p.getZ(), b.origin.getY());
            if (stand != null) return stand;
        }
        return null;
    }

    /** Every few seconds while the village is watched: each standing run stocked, or a young one born to it. */
    static void step(ServerLevel level, Village v) {
        for (Building b : v.buildings) {
            if (!b.type.isPen() || !b.standing() || !Construction.loaded(level, b.origin)) continue;
            List<Animal> herd = animals(level, v, b);
            int want = size(b.type, b.level);
            if (herd.size() >= want) continue;
            // a new run: stocked once with grown animals (brought from the market); a herd short of its number: a
            // young one now and then; a run left with none (killed, run off): a young one bought in, rarely
            boolean stock = herd.isEmpty() && !STOCKED.equals(b.option);
            int n = stock ? want : RND.nextInt(herd.isEmpty() ? 30 : 6) == 0 ? 1 : 0;
            if (stock) {
                b.option = STOCKED;
                VillageData.get(level.getServer()).changed();
            }
            for (int i = 0; i < n; i++) {
                BlockPos at = spot(level, v, b);
                if (at == null) break;
                Animal a = kind(b.type).create(level, EntitySpawnReason.BREEDING);
                if (a == null) break;
                a.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, RND.nextFloat() * 360, 0);
                a.setPersistenceRequired();
                a.addTag(tag(v, b));
                if (!stock) a.setAge(-24000);
                level.addFreshEntity(a);
            }
        }
    }
}
