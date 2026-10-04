package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The village's harbour: the pier (a skipper's house on the shore and a jetty out to water deep enough for a ship)
 * and its ships. A pier of the first level keeps a sloop, of the second a brig; at the third it keeps two brigs (the
 * village's fleet). Each ship is sailed by a skipper of the village; see {@link Voyages} for their trade.
 */
public final class Harbour {

    private Harbour() {
    }

    /** The longest jetty, and how far over dry land it may start (a strand before the water). */
    static final int MAX_JETTY = 22, DRY_START = 6;
    /** Water this deep at the jetty's end (a ship's keel). */
    static final int DEEP = 3;
    /** What a ship carries, by the pier's level: a sloop, a brig. */
    static final int SLOOP_LOAD = 1500, BRIG_LOAD = 2500;

    /**
     * Does the village want a pier: an island as soon as it has hands for it (its only way to trade); a village on the
     * mainland once it is big enough to send ships out besides its merchant.
     */
    static boolean wanted(Village v) {
        return testWanted || (v.island ? v.adults() >= 6 : v.adults() >= 12);
    }

    /** Tests: the pier opened for a village, and the store given what it takes to build it (and a fishers' hut grown). */
    public static void testReady(Village v) {
        v.unlocked.add(BuildingType.FISH_HUT);
        v.unlocked.add(BuildingType.PIER);
        v.add(Res.WOOD, 200);
        v.add(Res.PLANKS, 150);
        v.add(Res.STONE, 60);
        v.add(Res.COAL, 10);
        shipCost(0).forEach(v::add);
        testWanted = true;
    }

    /** Tests: every village wants a pier. */
    public static boolean testWanted;

    /** The village's standing pier, or null. */

    static Building pier(Village v) {
        for (Building b : v.buildings) if (b.type == BuildingType.PIER && b.state == Building.State.BUILT && b.jetty != null) return b;
        return null;
    }

    /** The most ships a village keeps, and the days a ship is on the stocks. */
    public static final int MAX_SHIPS = 3, SHIP_DAYS = 8;

    /** Berths at the pier: one a level (three at most). */
    static int berths(Village v) {
        Building p = pier(v);
        return p == null ? 0 : Math.min(MAX_SHIPS, p.level);
    }

    /** Ships the village has afloat at its pier (or at sea): those it has built, as many as it has berths for. */
    public static int ships(Village v) {
        return Math.min(v.ships, berths(v));
    }

    /** What ship {@code i} of the village carries: the first a sloop, the others brigs. */
    static int load(Village v, int i) {
        return i == 0 ? SLOOP_LOAD : BRIG_LOAD;
    }

    /** The model of ship {@code i}: 1 a sloop, 2 a brig. */
    static int tier(Village v, int i) {
        return i == 0 ? 1 : 2;
    }

    /**
     * What the next ship of the village costs (the n-th, from 0): a sloop is a great deal of timber, planks, rope and
     * tar; each brig after it half as much again and more, with wool for her sails, and iron fittings for the third.
     */
    public static java.util.Map<Res, Integer> shipCost(int n) {
        java.util.Map<Res, Integer> c = new java.util.EnumMap<>(Res.class);
        if (n == 0) {
            c.put(Res.WOOD, 150);
            c.put(Res.PLANKS, 250);
            c.put(Res.STICKS, 60);
            c.put(Res.COAL, 10);
        } else if (n == 1) {
            c.put(Res.WOOD, 240);
            c.put(Res.PLANKS, 400);
            c.put(Res.STICKS, 90);
            c.put(Res.COAL, 20);
            c.put(Res.WOOL, 30);
        } else {
            c.put(Res.WOOD, 330);
            c.put(Res.PLANKS, 550);
            c.put(Res.STICKS, 120);
            c.put(Res.COAL, 30);
            c.put(Res.WOOL, 45);
            c.put(Res.IRON, 20);
        }
        return c;
    }

    /**
     * A day at the pier: a ship on the stocks one day nearer launching; or, a berth free and the store able to pay
     * (and keep its reserve; an island, whose only way out is the sea, pays for its first ship out of what it has),
     * a new ship laid down. True if one was laid down today.
     */
    static boolean shipyard(Village v, long today) {
        Building site = site(v);
        if (site != null) {
            // everything brought: on the stocks, the shipwrights at her
            if (site.state == Building.State.BUILT) {
                v.buildings.remove(site);
                v.order.remove((Integer) site.id);
                v.shipWork = SHIP_DAYS;
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[sea] #{} {} day {}: ship {} on the stocks", v.id, v.name, today, v.ships);
                v.log(today, net.minecraft.network.chat.Component.translatable("minecraftportsmod.vlog.ship_stocks",
                        net.minecraft.network.chat.Component.translatable(v.ships == 0 ? "minecraftportsmod.sea.sloop" : "minecraftportsmod.sea.brig")));
            }
            return false;
        }
        if (v.shipWork > 0) {
            if (--v.shipWork == 0) {
                v.ships++;
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[sea] #{} {} day {}: ship {} launched", v.id, v.name, today, v.ships);
                v.log(today, net.minecraft.network.chat.Component.translatable("minecraftportsmod.vlog.ship_launched",
                        net.minecraft.network.chat.Component.translatable(v.ships == 1 ? "minecraftportsmod.sea.sloop" : "minecraftportsmod.sea.brig"))
                        .withStyle(net.minecraft.ChatFormatting.GOLD));
            }
            return false;
        }
        Building pier = pier(v);
        if (pier == null || v.ships >= berths(v)) return false;
        // (an island wants its ships as soon as it can; the mainland, a ship for every so many people)
        if (!v.island && v.adults() < 12 + 6 * v.ships) return false;
        // the site: its post at the foot of the jetty; what it costs brought bit by bit
        Direction out = out(pier);
        BlockPos at = pier.origin.relative(out, pier.type.half + 1);
        Building b = new Building(v.nextBuilding++, BuildingType.SHIP, at, out, today);
        VillageLife.add(v, b, today);
        b.price.clear();
        b.price.putAll(shipCost(v.ships));
        org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[sea] #{} {} day {}: ship {} laid down (site #{})", v.id, v.name, today, v.ships, b.id);
        v.log(today, net.minecraft.network.chat.Component.translatable("minecraftportsmod.vlog.ship_laid",
                net.minecraft.network.chat.Component.translatable(v.ships == 0 ? "minecraftportsmod.sea.sloop" : "minecraftportsmod.sea.brig")));
        return true;
    }

    /** The ship whose materials are being brought (a site of the village), or null. */
    static Building site(Village v) {
        for (Building b : v.buildings) if (b.type == BuildingType.SHIP) return b;
        return null;
    }

    /** Is a ship of the village being built (her materials brought, or she on the stocks)? */
    static boolean building(Village v) {
        return site(v) != null || v.shipWork > 0;
    }

    /** Players hand over materials to the ship being built at the pier: the site's screen. */
    public static boolean openSite(net.minecraft.server.level.ServerPlayer player, int village) {
        Village v = VillageData.get(player.level().getServer()).get(village);
        Building site = v == null ? null : site(v);
        if (site == null) return false;
        ColonyService.sendSite(player, v, site);
        return true;
    }

    /** The jetty's way out to sea. */
    static Direction out(Building pier) {
        return Direction.from2DDataValue(pier.jetty[0]);
    }

    /** The water at the end of the jetty (where the ships set out from and come in to), at the sea's level. */
    static BlockPos seaward(Building pier) {
        Direction out = out(pier);
        int k = pier.type.half + pier.jetty[1] + 2;
        return new BlockPos(pier.origin.getX() + out.getStepX() * k, pier.jetty[2], pier.origin.getZ() + out.getStepZ() * k);
    }

    /** Where ship {@code i} lies moored: alongside the jetty's end, one each side. */
    static Vec3 berth(Building pier, int i) {
        Direction out = out(pier), side = out.getClockWise();
        // two alongside the jetty's head, one each side; the third out beyond it
        int k = pier.type.half + pier.jetty[1] - 1 + (i == 2 ? 9 : 0);
        // (clear of the jetty's head, five planks wide: a brig is broad in the beam)
        double s = i == 0 ? 4.5 : i == 1 ? -4.5 : 0;
        return new Vec3(pier.origin.getX() + 0.5 + out.getStepX() * k + side.getStepX() * s, pier.jetty[2] - 0.45,
                pier.origin.getZ() + 0.5 + out.getStepZ() * k + side.getStepZ() * s);
    }

    /** The way a ship moored at the jetty lies (yaw, degrees): along the jetty, bow out to sea. */
    static float berthYaw(Building pier) {
        return out(pier).toYRot();
    }

    // ------------------------------------------------------------------ the water

    /**
     * How deep the water is over a column (0: dry land): the blocks in the world where they are loaded, the
     * generator's sea floor where not.
     */
    static int depth(ServerLevel level, int x, int z) {
        return depth(level, null, x, z);
    }

    /** The same, unloaded land read off what was read round the village (see {@link VillageTerrain}) where it can be. */
    static int depth(ServerLevel level, Village v, int x, int z) {
        int sea = level.getSeaLevel();
        if (!Construction.loaded(level, new BlockPos(x, 0, z)) && frozen(level, x, z)) return 0;
        if (v != null && !Construction.loaded(level, new BlockPos(x, 0, z))) {
            VillageTerrain.Grid g = VillageTerrain.grid(level, v);
            Integer d = g == null ? null : g.depth(x, z);
            if (d != null) return d;
        }
        if (Construction.loaded(level, new BlockPos(x, 0, z))) {
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
            int d = 0;
            while (d < 12 && !level.getBlockState(p).getFluidState().isEmpty()) {
                d++;
                p.move(0, -1, 0);
            }
            return d;
        }
        Integer isle = org.webtrade.minecraftportsmod.worldgen.RaisedIslands.floor(x, z, sea);
        int floor = isle != null ? isle : level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level,
                level.getChunkSource().randomState());
        return Math.max(0, sea - floor);

    }

    /** Frozen water by the world's biomes (ice over it, where the land is not loaded yet: no way for a ship). */
    static boolean frozen(ServerLevel level, int x, int z) {
        var gen = level.getChunkSource().getGenerator();
        var b = gen.getBiomeSource().getNoiseBiome(x >> 2, level.getSeaLevel() >> 2, z >> 2, level.getChunkSource().randomState().sampler());
        return b.unwrapKey().map(k -> k.identifier().getPath().contains("frozen")).orElse(false);
    }

    /**
     * The jetty of a pier standing at {@code origin}: {way out (2D data value), length beyond the plot, the sea's
     * level}, or null if no side of the plot has water deep enough within {@link #MAX_JETTY} (dry land at most
     * {@link #DRY_START} of it, then water all the way, and room for a ship at its end). The shortest one.
     */
    static int[] jetty(ServerLevel level, BlockPos origin, int half) {
        return jetty(level, null, origin, half);
    }

    static int[] jetty(ServerLevel level, Village v, BlockPos origin, int half) {
        int[] best = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int dry = 0;
            boolean wet = false;
            for (int k = 1; k <= MAX_JETTY; k++) {
                int x = origin.getX() + d.getStepX() * (half + k), z = origin.getZ() + d.getStepZ() * (half + k);
                int depth = depth(level, v, x, z);
                if (depth == 0) {
                    if (wet || ++dry > DRY_START) break;
                    continue;
                }
                wet = true;
                if (depth < DEEP) continue;
                // room for the ship beyond the end, and to either side of it
                boolean room = true;
                for (int e = 1; e <= 5 && room; e++) {
                    int ex = x + d.getStepX() * e, ez = z + d.getStepZ() * e;
                    room = depth(level, v, ex, ez) >= 2;
                }
                Direction side = d.getClockWise();
                if (room) room = depth(level, v, x + side.getStepX() * 4, z + side.getStepZ() * 4) >= 2
                        && depth(level, v, x - side.getStepX() * 4, z - side.getStepZ() * 4) >= 2;
                if (room && (best == null || k < best[1])) best = new int[]{d.get2DDataValue(), k, level.getSeaLevel()};
                break;
            }
        }
        return best;
    }
}
