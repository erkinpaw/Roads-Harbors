package org.webtrade.minecraftportsmod.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * A player's own plot in a village: a square of the village's land marked by a boundary stone, for the player to
 * build a home on. The village builds nothing there, clears nothing there, and its paths go round it, one of them to
 * the plot's front. A boundary stone is bought from the head of the village, or given by the village to one who has
 * done much for it; set down, it marks the square (corner posts, and an outline while one looks at it).
 */
public final class Plots {

    private Plots() {
    }

    /** Half the side of a plot (11 × 11). */
    public static final int HALF = 5;
    /** How far from the village's middle a plot may be. */
    static final int NEAR = 12, FAR = 110;
    /** What a boundary stone costs at the head of the village, and how much less for each task done for the village. */
    static final int PRICE = 24, PRICE_LESS = 3, PRICE_MIN = 4;
    /** Tasks done for a village after which it gives a stone. */
    static final int GIFT_AT = 5;

    /**
     * @param marker the plot's middle (where the stone was set down)
     * @param home   a bed and a door stand on it: the player lives in the village
     * @param stone  where the stone stands now (the middle, or the front edge once a house is built on it)
     * @param house  the house the village is building on it for the player (-1: none)
     */
    public record Plot(UUID owner, String ownerName, BlockPos marker, boolean home, BlockPos stone, int house) {
        static final Codec<Plot> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Plot::owner),
                Codec.STRING.optionalFieldOf("owner_name", "").forGetter(Plot::ownerName),
                BlockPos.CODEC.fieldOf("marker").forGetter(Plot::marker),
                Codec.BOOL.optionalFieldOf("home", false).forGetter(Plot::home),
                BlockPos.CODEC.optionalFieldOf("stone").forGetter(p -> java.util.Optional.of(p.stone)),
                Codec.INT.optionalFieldOf("house", -1).forGetter(Plot::house)
        ).apply(i, (owner, name, marker, home, stone, house) -> new Plot(owner, name, marker, home, stone.orElse(marker), house)));

        Plot(UUID owner, String ownerName, BlockPos marker, boolean home) {
            this(owner, ownerName, marker, home, marker, -1);
        }

        Plot with(boolean home, BlockPos stone, int house) {
            return new Plot(owner, ownerName, marker, home, stone, house);
        }

        public boolean contains(int x, int z, int margin) {
            return Math.abs(x - marker.getX()) <= HALF + margin && Math.abs(z - marker.getZ()) <= HALF + margin;
        }
    }

    /** Is the column in some player's plot (with a margin round it)? */
    public static boolean inside(Village v, int x, int z, int margin) {
        for (Plot p : v.tasks.plots) if (p.contains(x, z, margin)) return true;
        return false;
    }

    public static Plot of(Village v, UUID player) {
        for (Plot p : v.tasks.plots) if (p.owner.equals(player)) return p;
        return null;
    }

    /** The plot whose stone stands here. */
    static Plot at(Village v, BlockPos stone) {
        for (Plot p : v.tasks.plots) if (p.stone.equals(stone)) return p;
        return null;
    }

    private static void replace(Village v, Plot old, Plot now) {
        int i = v.tasks.plots.indexOf(old);
        if (i >= 0) v.tasks.plots.set(i, now);
    }

    /** What the head of the village asks for a stone (0: given). */
    public static int price(Village v, UUID player) {
        return Math.max(PRICE_MIN, PRICE - PRICE_LESS * v.tasks.thanks(player));
    }

    /** Where the plot's path starts: a step out of the square, on the side towards the village's middle. */
    static BlockPos front(Village v, Plot p) {
        int dx = v.center.getX() - p.marker.getX(), dz = v.center.getZ() - p.marker.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return p.marker.offset(Integer.signum(dx) * (HALF + 1), 0, 0);
        return p.marker.offset(0, 0, Integer.signum(dz) * (HALF + 1));
    }

    // ------------------------------------------------------------------ the stone

    /** A boundary stone of a village. */
    public static ItemStack stone(Village v) {
        ItemStack s = new ItemStack(org.webtrade.minecraftportsmod.registry.ModContent.PLOT_MARKER_ITEM);
        CompoundTag t = new CompoundTag();
        t.putInt("mpm_village", v.id);
        CustomData.set(DataComponents.CUSTOM_DATA, s, t);
        s.set(DataComponents.CUSTOM_NAME, Component.translatable("minecraftportsmod.plot.stone_of", v.name).withStyle(ChatFormatting.GOLD));
        return s;
    }

    static int villageOf(ItemStack s) {
        CustomData c = s.get(DataComponents.CUSTOM_DATA);
        return c == null ? -1 : c.copyTag().getIntOr("mpm_village", -1);
    }

    /** The head of the village sells a player a stone. */
    static boolean buy(ServerPlayer p, VillageData data, Village v) {
        if (of(v, p.getUUID()) != null) return false;
        int price = price(v, p.getUUID());
        if (Trade.emeralds(p) < price || !Wallet.pay(p, price)) return false;
        v.emeralds += price;
        Quests.give(p, stone(v));
        data.changed();
        return true;
    }

    /** A task done: the village gives a stone to one who has done enough for it (once). */
    static void thank(ServerPlayer p, Village v) {
        if (v.tasks.thanks(p.getUUID()) < GIFT_AT || of(v, p.getUUID()) != null || !v.tasks.gifted.add(p.getUUID())) return;
        Quests.give(p, stone(v));
        p.sendSystemMessage(Component.translatable("minecraftportsmod.plot.gift", v.name).withStyle(ChatFormatting.GOLD));
    }

    // ------------------------------------------------------------------ claiming

    /**
     * A stone set down: the square round it becomes the player's plot, if it may (in reach of the village, clear of
     * its buildings, streets, water and other plots). Returns null if claimed, else why not.
     */
    public static Component claim(ServerPlayer p, ServerLevel level, BlockPos pos, ItemStack stone) {
        VillageData data = VillageData.get(level.getServer());
        int vid = villageOf(stone);
        Village v = vid >= 0 ? data.get(vid) : data.near(pos, FAR + HALF);
        if (v == null) return Component.translatable("minecraftportsmod.plot.no_village");
        double d = Math.hypot(pos.getX() - v.center.getX(), pos.getZ() - v.center.getZ());
        if (d > FAR) return Component.translatable("minecraftportsmod.plot.too_far", v.name);
        if (d < NEAR) return Component.translatable("minecraftportsmod.plot.too_near");
        if (of(v, p.getUUID()) != null) return Component.translatable("minecraftportsmod.plot.have_one", v.name);
        for (Building b : v.buildings) if (b.overlaps(pos, HALF, 1)) return Component.translatable("minecraftportsmod.plot.building", b.type.displayName());
        for (Plot o : v.tasks.plots) if (o.contains(pos.getX(), pos.getZ(), HALF + 1)) return Component.translatable("minecraftportsmod.plot.taken", o.ownerName);
        if (Mine.pitClash(v, pos, HALF, 1)) return Component.translatable("minecraftportsmod.plot.building", BuildingType.MINE_HOUSE.displayName());
        if (Math.abs(pos.getX() - v.board.getX()) <= HALF + 1 && Math.abs(pos.getZ() - v.board.getZ()) <= HALF + 1)
            return Component.translatable("minecraftportsmod.plot.too_near");
        int wet = 0;
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                int cx = pos.getX() + x, cz = pos.getZ() + z;
                if (!Construction.loaded(level, new BlockPos(cx, 0, cz))) return Component.translatable("minecraftportsmod.plot.too_far", v.name);
                int y = PlotFinder.floorAt(level, cx, cz);
                BlockState top = level.getBlockState(new BlockPos(cx, y - 1, cz));
                if (top.is(Blocks.DIRT_PATH)) return Component.translatable("minecraftportsmod.plot.street");
                if (!top.getFluidState().isEmpty() || !level.getBlockState(new BlockPos(cx, y, cz)).getFluidState().isEmpty()) wet++;
            }
        }
        if (wet > (2 * HALF + 1) * (2 * HALF + 1) / 5) return Component.translatable("minecraftportsmod.plot.wet");
        Plot plot = new Plot(p.getUUID(), p.getName().getString(), pos.immutable(), false);
        v.tasks.plots.add(plot);
        corners(level, plot, true);
        show(p, plot, 200);
        v.log(data.day, Component.translatable("minecraftportsmod.vlog.plot", p.getName()).withStyle(ChatFormatting.GOLD));
        Achievements.plot(p);
        // the village's land grows round it; its next look for a way lays a path to the plot's front
        Reach.forget(v);
        PATHS.remove(key(v, plot));
        data.changed();
        return null;
    }

    /** The stone taken up by its owner: the plot is given back to the village. */
    public static void release(ServerLevel level, BlockPos pos) {
        VillageData data = VillageData.get(level.getServer());
        for (Village v : data.all()) {
            Plot p = at(v, pos);
            if (p == null) continue;
            v.tasks.plots.remove(p);
            corners(level, p, false);
            data.changed();
            return;
        }
    }

    /** May this player break the stone (its owner, or one in creative)? */
    public static boolean mayBreak(ServerPlayer p, ServerLevel level, BlockPos pos) {
        if (p.isCreative()) return true;
        for (Village v : VillageData.get(level.getServer()).all()) {
            Plot plot = at(v, pos);
            // (not while the village builds a house on it)
            if (plot != null) return plot.owner.equals(p.getUUID()) && plot.house < 0;
        }
        return true;
    }

    /** The village a stone at a place belongs to (null: none). */
    public static Village villageAt(ServerLevel level, BlockPos pos) {
        for (Village v : VillageData.get(level.getServer()).all()) if (at(v, pos) != null) return v;
        return null;
    }

    /** The stone used: its outline shown; the owner's home looked at (a bed and a door in the plot). */
    public static void use(ServerPlayer p, ServerLevel level, BlockPos pos) {
        for (Village v : VillageData.get(level.getServer()).all()) {
            Plot plot = at(v, pos);
            if (plot == null) continue;
            show(p, plot, 200);
            if (!plot.owner.equals(p.getUUID())) {
                p.sendSystemMessage(Component.translatable("minecraftportsmod.plot.whose", plot.ownerName, v.name), true);
                return;
            }
            // the house the village builds there: its site
            Building site = plot.house >= 0 ? v.building(plot.house) : null;
            if (site != null) {
                ColonyService.sendSite(p, v, site);
                return;
            }
            boolean bed = false, door = false;
            for (int x = -HALF; x <= HALF; x++) {
                for (int z = -HALF; z <= HALF; z++) {
                    for (int y = -4; y <= 12; y++) {
                        BlockState s = level.getBlockState(pos.offset(x, y, z));
                        bed |= s.is(BlockTags.BEDS);
                        door |= s.is(BlockTags.DOORS);
                    }
                }
            }
            if (bed != plot.home || bed && door != plot.home) {
                // (a home on it, or none any more: the village counts the player among its people, or not)
                replace(v, plot, plot.with(bed && door, plot.stone, plot.house));
                VillageData.get(level.getServer()).changed();
            }
            if (bed && door) {
                Achievements.home(p);
                p.sendSystemMessage(Component.translatable("minecraftportsmod.plot.home", v.name).withStyle(ChatFormatting.GREEN), true);
            } else if (plot.stone.equals(plot.marker)) {
                // an empty plot: the houses the village would build on it
                offer(p, v);
            } else {
                p.sendSystemMessage(Component.translatable("minecraftportsmod.plot.yours", v.name), true);
            }
            return;
        }
    }

    // ------------------------------------------------------------------ a house built by the village

    /** The houses the village can build on a plot: its homes for everybody (but the tent) that fit the square, of the kinds it builds. */
    public static java.util.List<BuildingType> houses(Village v) {
        java.util.List<BuildingType> out = new java.util.ArrayList<>();
        for (BuildingType t : BuildingType.values()) {
            if (!t.isHome() || t == BuildingType.TENT || t.job != null || t.half >= HALF) continue;
            if (t == BuildingType.HUT || v.unlocked(t)) out.add(t);
        }
        return out;
    }

    /** What a house takes (what the village will put into it). */
    static Map<Res, Integer> cost(Village v, BuildingType t) {
        Map<Res, Integer> m = new java.util.EnumMap<>(Res.class);
        m.putAll(Tree.price(v, t));
        return m;
    }

    /** The owner shown the houses to choose from. */
    static void offer(ServerPlayer p, Village v) {
        java.util.List<org.webtrade.minecraftportsmod.network.ColonyPayloads.PlotHouse> hs = new java.util.ArrayList<>();
        for (BuildingType t : houses(v)) {
            Map<Res, Integer> c = cost(v, t);
            int[] cost = new int[Res.values().length];
            c.forEach((r, n) -> cost[r.ordinal()] = n);
            hs.add(new org.webtrade.minecraftportsmod.network.ColonyPayloads.PlotHouse(t.ordinal(), t.beds, cost, Orders.price(v, c)));
        }
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p,
                new org.webtrade.minecraftportsmod.network.ColonyPayloads.PlotView(v.id, v.name, hs, Trade.purse(p)));
    }

    /**
     * The player has chosen a house: paid for, it goes up on the plot as one of the village's building sites (first in
     * its queue), facing the village; the stone moves to the plot's front edge. Null if begun, else why not.
     */
    static Component build(ServerPlayer p, VillageData data, Village v, BuildingType t) {
        Plot plot = of(v, p.getUUID());
        if (plot == null || plot.house >= 0 || !plot.stone.equals(plot.marker) || !houses(v).contains(t)) {
            return Component.translatable("minecraftportsmod.plot.no_house");
        }
        ServerLevel level = (ServerLevel) p.level();
        Map<Res, Integer> c = cost(v, t);
        int price = Orders.price(v, c);
        if (!Wallet.pay(p, price)) return Component.translatable("minecraftportsmod.plot.no_money", String.valueOf(price));
        v.emeralds += price;
        // the door to the village's side
        BlockPos m = plot.marker;
        int dx = v.center.getX() - m.getX(), dz = v.center.getZ() - m.getZ();
        Direction front = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        Building b = new Building(v.nextBuilding++, t, m, front, level.getRandom().nextLong()).look(VillageLife.look(v));
        b.owner = p.getUUID();
        VillageLife.add(v, b, data.day);
        // (paid for: first in the queue)
        v.order.remove((Integer) b.id);
        v.order.add(0, b.id);
        // the stone out of the way: to the front edge, beside where the path comes in
        Direction side = front.getClockWise();
        int sx = m.getX() + front.getStepX() * HALF + side.getStepX() * 2, sz = m.getZ() + front.getStepZ() * HALF + side.getStepZ() * 2;
        BlockPos stone = new BlockPos(sx, PlotFinder.floorAt(level, sx, sz), sz);
        level.setBlockAndUpdate(m, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(stone, org.webtrade.minecraftportsmod.registry.ModContent.PLOT_MARKER.defaultBlockState());
        replace(v, plot, plot.with(false, stone, b.id));
        v.log(data.day, Component.translatable("minecraftportsmod.vlog.plot_house", p.getName(), t.displayName()).withStyle(ChatFormatting.GOLD));
        data.changed();
        ColonyService.sendSite(p, v, b);
        return null;
    }

    /** A player's house is up, all of it: the village lets it go (the player's, to change as they please). */
    static void handOver(ServerLevel level, VillageData data, Village v, Building b) {
        v.buildings.remove(b);
        v.order.remove((Integer) b.id);
        for (Plot plot : new java.util.ArrayList<>(v.tasks.plots)) {
            if (plot.house != b.id) continue;
            replace(v, plot, plot.with(true, plot.stone, -1));
            v.log(data.day, Component.translatable("minecraftportsmod.vlog.plot_house_done", plot.ownerName, b.type.displayName())
                    .withStyle(ChatFormatting.GOLD));
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(plot.owner);
            if (p != null) {
                p.sendSystemMessage(Component.translatable("minecraftportsmod.plot.house_done", v.name).withStyle(ChatFormatting.GREEN));
                Achievements.home(p);
            }
        }
        data.changed();
    }

    // ------------------------------------------------------------------ the look of it

    /** The four corner posts: a fence post with a lantern on it (put up, or taken away). */
    static void corners(ServerLevel level, Plot p, boolean up) {
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int x = p.marker.getX() + sx * HALF, z = p.marker.getZ() + sz * HALF;
                if (!Construction.loaded(level, new BlockPos(x, 0, z))) continue;
                int y = PlotFinder.floorAt(level, x, z);
                BlockPos post = new BlockPos(x, y, z), lamp = post.above();
                if (up) {
                    BlockState here = level.getBlockState(post);
                    if (!here.isAir() && !here.canBeReplaced()) continue;
                    level.setBlockAndUpdate(post, Blocks.SPRUCE_FENCE.defaultBlockState());
                    level.setBlockAndUpdate(lamp, Blocks.LANTERN.defaultBlockState());
                } else {
                    // (the posts may have sunk a block with the ground: the top fence and lantern found)
                    for (int dy = -2; dy <= 1; dy++) {
                        BlockPos q = post.offset(0, dy, 0);
                        if (level.getBlockState(q).is(Blocks.SPRUCE_FENCE) && level.getBlockState(q.above()).is(Blocks.LANTERN)) {
                            level.setBlockAndUpdate(q.above(), Blocks.AIR.defaultBlockState());
                            level.setBlockAndUpdate(q, Blocks.AIR.defaultBlockState());
                        }
                    }
                }
            }
        }
    }

    private record Showing(Plot plot, UUID player, int[] left) {
    }

    private static final Map<UUID, Showing> SHOWING = new HashMap<>();

    /** Shows a player the plot's outline for a while (ticks). */
    static void show(ServerPlayer p, Plot plot, int ticks) {
        SHOWING.put(p.getUUID(), new Showing(plot, p.getUUID(), new int[]{ticks}));
    }

    /** Every few ticks: the outlines being shown, as sparks along the edge, each to its own player. */
    static void tick(net.minecraft.server.MinecraftServer srv) {
        if (SHOWING.isEmpty() || srv.getTickCount() % 5 != 0) return;
        ServerLevel level = srv.overworld();
        for (Iterator<Showing> it = SHOWING.values().iterator(); it.hasNext(); ) {
            Showing s = it.next();
            s.left[0] -= 5;
            ServerPlayer p = srv.getPlayerList().getPlayer(s.player);
            if (s.left[0] <= 0 || p == null || p.level() != level) {
                it.remove();
                continue;
            }
            BlockPos m = s.plot.marker;
            for (int t = -HALF; t <= HALF; t++) {
                spark(level, p, m.getX() + t, m.getZ() - HALF);
                spark(level, p, m.getX() + t, m.getZ() + HALF);
                spark(level, p, m.getX() - HALF, m.getZ() + t);
                spark(level, p, m.getX() + HALF, m.getZ() + t);
            }
        }
    }

    private static void spark(ServerLevel level, ServerPlayer p, int x, int z) {
        if (!Construction.loaded(level, new BlockPos(x, 0, z))) return;
        double y = PlotFinder.floorAt(level, x, z) + 0.2;
        level.sendParticles(p, ParticleTypes.HAPPY_VILLAGER, true, false, x + 0.5, y, z + 0.5, 1, 0.1, 0.05, 0.1, 0);
    }

    /** Where one stands on the ground at a column (tests). */
    public static BlockPos ground(ServerLevel level, int x, int z) {
        return PlotFinder.ground(level, x, z);
    }

    static void clear() {
        SHOWING.clear();
        PATHS.clear();
    }

    // ------------------------------------------------------------------ the path to it

    /** The game time each plot's path is looked at next. */
    private static final Map<Long, Long> PATHS = new HashMap<>();

    private static long key(Village v, Plot p) {
        return (long) v.id * 31 + p.marker.asLong();
    }

    /** Now and then: a plot with no path at its front gets one to the square. */
    static void paths(ServerLevel level, Village v) {
        long now = level.getGameTime();
        for (Plot p : v.tasks.plots) {
            Long next = PATHS.get(key(v, p));
            if (next != null && now < next) continue;
            BlockPos door = front(v, p);
            if (!Construction.loaded(level, door)) continue;
            door = new BlockPos(door.getX(), PlotFinder.floorAt(level, door.getX(), door.getZ()), door.getZ());
            boolean ok = Paths.connected(level, v, door) || Paths.layFrom(level, v, door, "plot of " + p.ownerName);
            PATHS.put(key(v, p), now + (ok ? 6000 : 1200));
        }
    }
}
