package org.webtrade.minecraftportsmod.worldgen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.webtrade.minecraftportsmod.village.VillageLayout;

/** Lets a grown settlement add buildings to its village in the style it was built in. */
public final class VillageGrowth {

    private VillageGrowth() {
    }

    /** Builds one more house on a free plot; the new layout, or null if there is no room left. */
    public static VillageLayout growHouse(ServerLevel level, VillageLayout layout, int index, RandomSource rnd) {
        return VillageBuilder.growHouse(level, layout, index, rnd);
    }
}
