package org.webtrade.minecraftportsmod.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.Heightmap;
import org.webtrade.minecraftportsmod.economy.Profession;
import org.webtrade.minecraftportsmod.economy.Settlement;
import org.webtrade.minecraftportsmod.fleet.VesselRecord;
import org.webtrade.minecraftportsmod.registry.ModContent;

import java.util.ArrayList;
import java.util.List;

/** Puts a settlement's people into the world, each with a bed and a place to work. */
public final class Residents {

    /** At most this many residents walk about a village; the rest of its people live in the numbers. */
    public static final int MAX_VISIBLE = 10;

    private static final String[] NAMES_RU = {"Иван", "Пётр", "Фёдор", "Степан", "Григорий", "Матвей", "Никита", "Савва",
            "Тимофей", "Прохор", "Кузьма", "Остап", "Ефим", "Лука", "Демьян", "Захар", "Трофим", "Игнат", "Макар", "Яков",
            "Марья", "Дарья", "Настасья", "Аграфена", "Василиса", "Ульяна", "Прасковья", "Евдокия", "Арина", "Любава",
            "Аксинья", "Варвара", "Глафира", "Олеся", "Милана", "Фёкла", "Злата", "Мирон", "Всеслав", "Добрыня"};
    private static final String[] NAMES_EN = {"John", "Peter", "Thomas", "William", "Hugh", "Walter", "Robert", "Simon",
            "Giles", "Edmund", "Roger", "Martin", "Godfrey", "Ralph", "Alan", "Henry", "Geoffrey", "Nicholas", "Adam", "Miles",
            "Alice", "Agnes", "Maud", "Joan", "Emma", "Isabel", "Margery", "Cecily", "Edith", "Rose", "Beatrice", "Mabel",
            "Juliana", "Amice", "Avice", "Sybil", "Helen", "Ada", "Elena", "Muriel"};

    private Residents() {
    }

    public static String name(boolean russian, RandomSource rnd) {
        String[] names = russian ? NAMES_RU : NAMES_EN;
        return names[rnd.nextInt(names.length)];
    }

    /**
     * Spawns up to {@code count} residents with jobs drawn from the settlement's workforce (the merchant always
     * among them), each given a bed and a workplace from the village layout.
     */
    public static int spawn(ServerLevel level, Settlement s, int count, boolean russian, RandomSource rnd) {
        VillageLayout layout = s.layout();
        if (layout == null) return 0;
        List<Profession> jobs = new ArrayList<>();
        s.workers().forEach((p, n) -> {
            for (int i = 0; i < n; i++) jobs.add(p);
        });
        java.util.Collections.shuffle(jobs, new java.util.Random(rnd.nextLong()));
        jobs.remove(Profession.MERCHANT);
        jobs.addFirst(Profession.MERCHANT);
        // no more people in sight than there are beds for (plus the merchant)
        int visible = Math.min(count, Math.min(jobs.size(), Math.max(2, layout.beds().size() + 1)));
        int spawned = 0;
        for (int i = 0; i < visible; i++) {
            Profession p = jobs.get(i);
            ResidentEntity r = ModContent.RESIDENT.create(level, EntitySpawnReason.STRUCTURE);
            if (r == null) continue;
            BlockPos at = layout.square().offset(rnd.nextInt(7) - 3, 0, rnd.nextInt(7) - 3);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
            r.snapTo(at.getX() + 0.5, y, at.getZ() + 0.5, rnd.nextFloat() * 360, 0);
            r.setup(s.id(), p, rnd.nextInt(ResidentEntity.SKINS), name(russian, rnd));
            assignWork(r, p, layout, i);
            if (!layout.beds().isEmpty()) r.setHome(layout.beds().get(i % layout.beds().size()));
            if (level.addFreshEntity(r)) spawned++;
        }
        return spawned;
    }

    /**
     * One more resident for a village that grew: a trade that is short of hands among those walking about, a bed
     * nobody sleeps in yet.
     */
    public static void spawnOne(ServerLevel level, Settlement s, List<ResidentEntity> present, boolean russian, RandomSource rnd) {
        VillageLayout layout = s.layout();
        if (layout == null) return;
        java.util.Map<Profession, Integer> shown = new java.util.EnumMap<>(Profession.class);
        java.util.Set<BlockPos> takenBeds = new java.util.HashSet<>();
        for (ResidentEntity r : present) {
            shown.merge(r.profession(), 1, Integer::sum);
            if (r.home != null) takenBeds.add(r.home);
        }
        Profession pick = Profession.MERCHANT;
        double bestGap = -1;
        for (var e : s.workers().entrySet()) {
            double gap = e.getValue() - shown.getOrDefault(e.getKey(), 0);
            if (gap > bestGap) {
                bestGap = gap;
                pick = e.getKey();
            }
        }
        ResidentEntity r = ModContent.RESIDENT.create(level, EntitySpawnReason.STRUCTURE);
        if (r == null) return;
        // newcomers arrive by the pier (or the square)
        BlockPos at = layout.pierTip().map(BlockPos::above).orElse(layout.square());
        r.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, rnd.nextFloat() * 360, 0);
        r.setup(s.id(), pick, rnd.nextInt(ResidentEntity.SKINS), name(russian, rnd));
        assignWork(r, pick, layout, present.size());
        for (BlockPos bed : layout.beds()) {
            if (!takenBeds.contains(bed)) {
                r.setHome(bed);
                break;
            }
        }
        level.addFreshEntity(r);
    }

    /** Picks the workplace of a trade from what the village has. */
    static void assignWork(ResidentEntity r, Profession p, VillageLayout l, int index) {
        BlockPos workshop = l.workshop().orElse(null);
        BlockPos block = l.workBlocks().isEmpty() ? null : l.workBlocks().get(index % l.workBlocks().size());
        switch (p) {
            case FARMER -> {
                if (!l.fields().isEmpty()) {
                    r.setWork(ResidentEntity.Work.FIELD, l.fields().get(index % l.fields().size()), null);
                    return;
                }
            }
            case FISHER -> {
                if (l.pierTip().isPresent()) {
                    BlockPos tip = l.pierTip().get();
                    // cast out over the water, away from the shore
                    int dx = Integer.signum(tip.getX() - l.office().getX()), dz = Integer.signum(tip.getZ() - l.office().getZ());
                    r.setWork(ResidentEntity.Work.FISH, tip.above(), tip.offset(dx * 4, -1, dz * 4));
                    return;
                }
            }
            case WOODCUTTER -> {
                r.setWork(ResidentEntity.Work.TREE, workshop != null ? workshop : l.square(), null);
                return;
            }
            case SHEPHERD -> {
                if (l.pen().isPresent()) {
                    BlockPos pen = l.pen().get();
                    r.setWork(ResidentEntity.Work.PEN, pen, pen.offset(0, 0, 6));
                    return;
                }
                if (!l.fields().isEmpty()) {
                    r.setWork(ResidentEntity.Work.FIELD, l.fields().getFirst(), null);
                    return;
                }
            }
            case MERCHANT -> {
                r.setWork(ResidentEntity.Work.OFFICE, standBeside(l.office(), l.square()), l.office());
                return;
            }
            default -> {
            }
        }
        if (block != null) {
            r.setWork(ResidentEntity.Work.WORKSHOP, standBeside(block, l.square()), block);
        } else {
            r.setWork(ResidentEntity.Work.SQUARE, l.square(), null);
        }
    }

    /** The block next to {@code target} on the side facing {@code towards}. */
    private static BlockPos standBeside(BlockPos target, BlockPos towards) {
        int dx = Integer.signum(towards.getX() - target.getX()), dz = Integer.signum(towards.getZ() - target.getZ());
        if (Math.abs(towards.getX() - target.getX()) > Math.abs(towards.getZ() - target.getZ())) dz = 0;
        else dx = 0;
        return target.offset(dx, 0, dz);
    }

    /** A skipper for a settlement's vessel; the caller puts it aboard. */
    public static ResidentEntity skipper(ServerLevel level, Settlement s, VesselRecord vessel, boolean russian) {
        ResidentEntity r = ModContent.RESIDENT.create(level, EntitySpawnReason.STRUCTURE);
        if (r == null) return null;
        RandomSource rnd = level.getRandom();
        r.snapTo(vessel.x(), level.getSeaLevel(), vessel.z(), 0, 0);
        r.setup(s.id(), Profession.MERCHANT, rnd.nextInt(ResidentEntity.SKINS), name(russian, rnd));
        r.setVessel(vessel.id());
        return r;
    }
}
