package org.webtrade.minecraftportsmod.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.webtrade.minecraftportsmod.economy.EconomyManager;
import org.webtrade.minecraftportsmod.economy.Good;
import org.webtrade.minecraftportsmod.economy.Market;
import org.webtrade.minecraftportsmod.economy.Settlement;
import org.webtrade.minecraftportsmod.economy.SettlementData;
import org.webtrade.minecraftportsmod.fleet.Names;
import org.webtrade.minecraftportsmod.port.Port;
import org.webtrade.minecraftportsmod.port.PortData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Makes a village's economy visible in the world while someone is near: the stock yard's piles follow the
 * stores, the price board shows today's prices, new houses go up when the settlement grows and its people
 * appear (or leave) with it.
 */
public final class VillageLife {

    /** People walking about a village at most. */
    public static final int MAX_VISIBLE = 16;
    /** How close a player must be for the village to be kept up to date in the world. */
    private static final int WATCH_RANGE = 160;

    private VillageLife() {
    }

    /** Every few seconds: refresh the villages players are close to. */
    public static void tick(MinecraftServer srv) {
        SettlementData data = SettlementData.get(srv);
        for (Settlement s : data.all()) {
            VillageLayout l = s.layout();
            if (l == null) continue;
            Port port = PortData.get(srv).port(s.portId());
            if (port == null) continue;
            ServerLevel level = srv.getLevel(port.dimension());
            if (level == null || !watched(level, l.square()) || !loaded(level, l.square(), 3)) continue;
            grow(level, data, s);
            people(level, s);
            refresh(level, s, data.day());
        }
    }

    private static boolean watched(ServerLevel level, BlockPos p) {
        for (var pl : level.players()) if (pl.blockPosition().distSqr(p) < (long) WATCH_RANGE * WATCH_RANGE) return true;
        return false;
    }

    private static boolean loaded(ServerLevel level, BlockPos p, int radius) {
        int cx = p.getX() >> 4, cz = p.getZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ growth

    /** The settlement built a house in its accounts: put it up in the world. One per visit. */
    private static void grow(ServerLevel level, SettlementData data, Settlement s) {
        VillageLayout l = s.layout();
        if (!l.canGrow()) return;
        if (s.housesBuilt() < 0) s.setHousesBuilt(s.houses());
        if (s.housesBuilt() >= s.houses()) return;
        VillageLayout grown = org.webtrade.minecraftportsmod.worldgen.VillageGrowth.growHouse(level, l, s.housesBuilt(),
                RandomSource.create(level.getSeed() ^ s.id() * 31L ^ s.housesBuilt()));
        if (grown == null) {
            // no room left: the house is counted as built so the village stops trying every visit
            s.setHousesBuilt(s.houses());
            return;
        }
        s.setLayout(grown);
        s.setHousesBuilt(s.housesBuilt() + 1);
        data.changed();
    }

    // ------------------------------------------------------------------ people

    /** As many residents about as the village has people and beds; newcomers arrive, leavers go. */
    private static void people(ServerLevel level, Settlement s) {
        VillageLayout l = s.layout();
        int want = Math.min(Math.min(s.population(), MAX_VISIBLE), l.beds().isEmpty() ? 4 : l.beds().size() + 1);
        List<ResidentEntity> here = level.getEntitiesOfClass(ResidentEntity.class,
                new net.minecraft.world.phys.AABB(l.square()).inflate(96), r -> r.settlement() == s.id() && !r.isSkipper());
        if (here.size() < want) {
            Port port = PortData.get(level.getServer()).port(s.portId());
            boolean ru = port != null && Names.cyrillic(port.name());
            Residents.spawnOne(level, s, here, ru, level.getRandom());
        } else if (here.size() > want && !here.isEmpty()) {
            // someone moved away
            here.getLast().discard();
        }
    }

    // ------------------------------------------------------------------ the stock yard and the board

    /** Piles and price board as the stores and prices are now. */
    public static void refresh(ServerLevel level, Settlement s, long day) {
        VillageLayout l = s.layout();
        if (l == null) return;
        piles(level, s, l);
        board(level, s, l, day);
    }

    /** The goods most worth seeing (by value in store), one pile each, 1 to 3 blocks high by the amount. */
    private static void piles(ServerLevel level, Settlement s, VillageLayout l) {
        if (l.piles().isEmpty()) return;
        List<Good> goods = new ArrayList<>();
        for (Good g : Good.values()) if (s.stock(g) >= 4) goods.add(g);
        goods.sort(Comparator.comparingDouble(g -> -s.stock(g) * g.basePrice));
        for (int i = 0; i < l.piles().size(); i++) {
            BlockPos base = l.piles().get(i);
            Good g = i < goods.size() ? goods.get(i) : null;
            int height = g == null ? 0 : (int) Math.max(1, Math.min(3, 1 + Math.floor(Math.log(s.stock(g) / 16.0) / Math.log(4))));
            BlockState pile = g == null ? Blocks.AIR.defaultBlockState() : pileBlock(g);
            for (int y = 0; y < 3; y++) {
                BlockPos p = base.above(y);
                BlockState want = y < height ? pile : Blocks.AIR.defaultBlockState();
                if (!level.getBlockState(p).equals(want)) level.setBlock(p, want, Block.UPDATE_CLIENTS);
            }
        }
    }

    /** How a good looks in the yard. */
    static BlockState pileBlock(Good g) {
        String id = switch (g) {
            case STONE -> "cobblestone";
            case GRANITE, DIORITE, ANDESITE, SAND, GLASS, CLAY, BRICKS, TERRACOTTA -> g.id();
            case COAL -> "coal_block";
            case IRON_ORE -> "raw_iron_block";
            case COPPER_ORE -> "raw_copper_block";
            case GOLD_ORE -> "raw_gold_block";
            case WHEAT -> "hay_block";
            case WOOL -> "white_wool";
            case POTS -> "decorated_pot";
            case IRON, COPPER, GOLD, TOOLS -> "chest";
            case BREAD, FISH, MUTTON -> "barrel";
            default -> g.id();   // logs and planks by their own names
        };
        BlockState st = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(id)).defaultBlockState();
        if (st.hasProperty(BlockStateProperties.AXIS)) st = st.setValue(BlockStateProperties.AXIS, Direction.Axis.X);
        return st;
    }

    /**
     * The board: a title sign, then four goods — what the village sells most of and what it needs most — with the
     * price of a stack and whether it is rising or falling.
     */
    private static void board(ServerLevel level, Settlement s, VillageLayout l, long day) {
        if (l.board().isEmpty()) return;
        List<Good> shown = new ArrayList<>();
        List<Good> surplus = new ArrayList<>(), short_ = new ArrayList<>();
        for (Good g : Good.values()) {
            double t = Market.target(s, g);
            if (s.stock(g) > t * 1.5 + 16) surplus.add(g);
            else if (s.demand(g) > 0.3 && s.stock(g) < t * 0.7) short_.add(g);
        }
        surplus.sort(Comparator.comparingDouble(g -> -(s.stock(g) - Market.target(s, g)) * g.basePrice));
        short_.sort(Comparator.comparingDouble(g -> -(Market.target(s, g) - s.stock(g)) * g.basePrice));
        for (int i = 0; shown.size() < 2 && i < surplus.size(); i++) shown.add(surplus.get(i));
        for (int i = 0; shown.size() < 4 && i < short_.size(); i++) shown.add(short_.get(i));
        for (int i = 0; shown.size() < 4 && i < surplus.size(); i++) if (!shown.contains(surplus.get(i))) shown.add(surplus.get(i));

        // the title in the middle, goods on both sides: reads right whichever way the wall faces
        int mid = l.board().size() / 2;
        sign(level, l.board().get(mid), new Component[]{
                Component.translatable("minecraftportsmod.board.title"),
                Component.translatable("minecraftportsmod.board.day", day),
                Component.translatable("minecraftportsmod.board.per_stack"),
                Component.empty()});
        for (int k = 0; k + 1 < l.board().size(); k++) {
            int i = k < mid ? k : k + 1;
            Good g = k < shown.size() ? shown.get(k) : null;
            if (g == null) {
                sign(level, l.board().get(i), new Component[]{Component.empty(), Component.empty(), Component.empty(), Component.empty()});
                continue;
            }
            double price = EconomyManager.price(s, g) * 64;
            List<Float> h = s.history(g);
            float then = h.size() >= 4 ? h.get(h.size() - 4) : (float) EconomyManager.price(s, g);
            double change = then <= 0 ? 0 : (EconomyManager.price(s, g) - then) / then;
            Component trend = change > 0.08 ? Component.translatable("minecraftportsmod.board.up")
                    : change < -0.08 ? Component.translatable("minecraftportsmod.board.down") : Component.translatable("minecraftportsmod.board.flat");
            boolean selling = s.stock(g) > Market.target(s, g);
            sign(level, l.board().get(i), new Component[]{
                    g.displayName(),
                    Component.literal(String.format(Locale.ROOT, "%.1f ◆", price)),
                    trend,
                    Component.translatable(selling ? "minecraftportsmod.board.selling" : "minecraftportsmod.board.buying")});
        }
    }

    private static void sign(ServerLevel level, BlockPos pos, Component[] lines) {
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;
        SignText text = sign.getFrontText();
        boolean changed = false;
        for (int i = 0; i < 4; i++) {
            if (!text.getMessage(i, false).equals(lines[i])) {
                text = text.setMessage(i, lines[i]);
                changed = true;
            }
        }
        if (changed) {
            sign.setText(text, true);
            sign.setWaxed(true);
        }
    }
}
