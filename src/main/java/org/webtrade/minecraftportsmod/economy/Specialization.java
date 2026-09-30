package org.webtrade.minecraftportsmod.economy;

import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a settlement's land is rich in. Deposits are "richness" multipliers on the matching producers
 * (1.0 = an ordinary spot; a specialised village has 1.5–2.2 in its field and little elsewhere).
 */
public enum Specialization {
    LUMBER, MINING, POTTERY, FISHING, FARMING, QUARRY;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Specialization byId(String id) {
        try {
            return valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Component displayName() {
        return Component.translatable("minecraftportsmod.spec." + id());
    }

    private static final Good[] WOODS = {Good.OAK_LOG, Good.SPRUCE_LOG, Good.BIRCH_LOG, Good.DARK_OAK_LOG,
            Good.ACACIA_LOG, Good.JUNGLE_LOG, Good.CHERRY_LOG, Good.MANGROVE_LOG};

    /** Deposits of a new settlement with this specialisation (a little random, so no two are identical). */
    public Map<Good, Double> rollDeposits(RandomSource random) {
        Map<Good, Double> d = new EnumMap<>(Good.class);
        // every coastal village has a bit of farmland, fish and some trees
        d.put(Good.WHEAT, 0.5 + random.nextDouble() * 0.2);
        d.put(Good.FISH, 0.5 + random.nextDouble() * 0.2);
        d.put(WOODS[random.nextInt(3)], 0.4 + random.nextDouble() * 0.2);
        switch (this) {
            case LUMBER -> {
                List<Good> species = new ArrayList<>(List.of(WOODS));
                for (int i = 0; i < 3; i++) {
                    Good wood = species.remove(random.nextInt(species.size()));
                    d.put(wood, 1.2 + random.nextDouble() * 0.8);
                }
            }
            case MINING -> {
                d.put(Good.COAL, 1.3 + random.nextDouble() * 0.5);
                d.put(Good.IRON_ORE, 1.1 + random.nextDouble() * 0.6);
                d.put(Good.COPPER_ORE, 0.8 + random.nextDouble() * 0.5);
                d.put(Good.GOLD_ORE, 0.15 + random.nextDouble() * 0.2);
                d.put(Good.STONE, 1.0);
            }
            case POTTERY -> {
                d.put(Good.CLAY, 1.8 + random.nextDouble() * 0.5);
                d.put(Good.SAND, 1.0 + random.nextDouble() * 0.4);
                d.put(Good.COAL, 0.3);
            }
            case FISHING -> d.put(Good.FISH, 2.0 + random.nextDouble() * 0.4);
            case FARMING -> {
                d.put(Good.WHEAT, 1.8 + random.nextDouble() * 0.4);
                d.put(Good.WOOL, 1.4 + random.nextDouble() * 0.4);
            }
            case QUARRY -> {
                d.put(Good.STONE, 1.4 + random.nextDouble() * 0.3);
                d.put(Good.GRANITE, 1.0 + random.nextDouble() * 0.4);
                d.put(Good.DIORITE, 1.0 + random.nextDouble() * 0.4);
                d.put(Good.ANDESITE, 1.0 + random.nextDouble() * 0.4);
                d.put(Good.SAND, 0.6);
            }
        }
        return d;
    }

    /** Workers a new settlement starts with. */
    public Map<Profession, Integer> startingWorkforce() {
        Map<Profession, Integer> w = new EnumMap<>(Profession.class);
        w.put(Profession.FARMER, 2);
        w.put(Profession.FISHER, 2);
        w.put(Profession.BAKER, 1);
        w.put(Profession.MERCHANT, 1);
        switch (this) {
            case LUMBER -> {
                w.put(Profession.WOODCUTTER, 4);
                w.put(Profession.SAWYER, 2);
            }
            case MINING -> {
                w.put(Profession.MINER, 4);
                w.put(Profession.SMELTER, 2);
                w.put(Profession.SMITH, 1);
            }
            case POTTERY -> {
                w.put(Profession.POTTER, 4);
                w.put(Profession.WOODCUTTER, 1);
            }
            case FISHING -> w.merge(Profession.FISHER, 4, Integer::sum);
            case FARMING -> {
                w.merge(Profession.FARMER, 3, Integer::sum);
                w.put(Profession.SHEPHERD, 2);
                w.merge(Profession.BAKER, 1, Integer::sum);
            }
            case QUARRY -> w.put(Profession.MASON, 5);
        }
        return w;
    }
}
