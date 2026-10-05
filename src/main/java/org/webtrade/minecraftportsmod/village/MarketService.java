package org.webtrade.minecraftportsmod.village;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.economy.EconomyManager;
import org.webtrade.minecraftportsmod.economy.Good;
import org.webtrade.minecraftportsmod.economy.Market;
import org.webtrade.minecraftportsmod.economy.Order;
import org.webtrade.minecraftportsmod.economy.Settlement;
import org.webtrade.minecraftportsmod.economy.SettlementData;
import org.webtrade.minecraftportsmod.network.MarketPayloads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server side of the market screen: what a resident offers, and the deals themselves. */
public final class MarketService {

    /** How close to the resident the player must stay to keep trading. */
    private static final double REACH = 8;

    private record Context(int portId, int residentId) {
    }

    private static final Map<UUID, Context> CONTEXT = new HashMap<>();

    private MarketService() {
    }

    public static void open(ServerPlayer player, ResidentEntity resident) {
        Settlement s = SettlementData.get(player.level().getServer()).get(resident.settlement());
        if (s == null) {
            player.sendOverlayMessage(Component.translatable("minecraftportsmod.market.no_village").withStyle(ChatFormatting.GRAY));
            return;
        }
        CONTEXT.put(player.getUUID(), new Context(s.id(), resident.getId()));
        send(player, false);
    }

    private static ResidentEntity resident(ServerPlayer player, Context ctx) {
        Entity e = player.level().getEntity(ctx.residentId);
        if (e instanceof ResidentEntity r && r.isAlive() && r.distanceTo(player) <= REACH) return r;
        return null;
    }

    private static void send(ServerPlayer player, boolean refresh) {
        Context ctx = CONTEXT.get(player.getUUID());
        if (ctx == null) return;
        MinecraftServer srv = player.level().getServer();
        Settlement s = SettlementData.get(srv).get(ctx.portId);
        ResidentEntity r = resident(player, ctx);
        if (s == null || r == null) return;

        List<MarketPayloads.Row> rows = new ArrayList<>();
        for (Good g : Good.values()) {
            int have = count(player, g.item);
            if (s.stock(g) < 1 && s.demand(g) < 0.05 && have == 0) continue;
            rows.add(new MarketPayloads.Row(g.ordinal(), (float) s.stock(g), (float) Market.target(s, g), Market.available(s, g),
                    (float) (EconomyManager.price(s, g) * Market.MARKUP), (float) (EconomyManager.price(s, g) * Market.MARKDOWN), have));
        }
        // what they sell first, then what they only buy
        rows.sort(java.util.Comparator.comparing((MarketPayloads.Row row) -> row.available() < 1));

        long day = SettlementData.get(srv).day();
        List<MarketPayloads.OrderRow> orders = new ArrayList<>();
        for (Order o : Market.orders(s)) {
            orders.add(new MarketPayloads.OrderRow(o.id(), o.good().ordinal(), o.amount(), o.delivered(), o.reward(),
                    (int) Math.max(0, o.expires() - day), count(player, o.good().item)));
        }
        ServerPlayNetworking.send(player, new MarketPayloads.View(refresh, s.id(), EconomyManager.name(srv, s), s.spec().ordinal(),
                r.getCustomName() == null ? "" : r.getCustomName().getString(), r.profession().ordinal(), (float) s.treasury(),
                org.webtrade.minecraftportsmod.colony.Trade.emeralds(player), rows, orders));
    }

    public static void handle(ServerPlayer player, MarketPayloads.Action a) {
        Context ctx = CONTEXT.get(player.getUUID());
        if (ctx == null || ctx.portId != a.portId()) return;
        MinecraftServer srv = player.level().getServer();
        SettlementData data = SettlementData.get(srv);
        Settlement s = data.get(ctx.portId);
        if (s == null || resident(player, ctx) == null) {
            fail(player, "minecraftportsmod.market.too_far");
            return;
        }
        String who = player.getGameProfile().name();
        switch (a.kind()) {
            case BUY -> {
                if (a.what() < 0 || a.what() >= Good.values().length || a.qty() <= 0 || a.qty() > 64 * 36) return;
                Good g = Good.values()[a.what()];
                int cost = Market.buyQuote(s, g, a.qty());
                if (cost < 0) {
                    fail(player, "minecraftportsmod.market.not_enough_stock");
                } else if (org.webtrade.minecraftportsmod.colony.Trade.emeralds(player) < cost) {
                    fail(player, "minecraftportsmod.market.not_enough_emeralds");
                } else {
                    org.webtrade.minecraftportsmod.colony.Wallet.pay(player, cost);
                    give(player, g.item, a.qty());
                    Market.bought(s, g, a.qty(), cost, data.day(), who);
                    player.sendSystemMessage(Component.translatable("minecraftportsmod.market.bought", a.qty(), g.displayName(), cost)
                            .withStyle(ChatFormatting.GREEN));
                }
            }
            case SELL -> {
                if (a.what() < 0 || a.what() >= Good.values().length || a.qty() <= 0) return;
                Good g = Good.values()[a.what()];
                if (count(player, g.item) < a.qty()) {
                    fail(player, "minecraftportsmod.market.not_enough_goods");
                    return;
                }
                int pay = Market.sellQuote(s, g, a.qty());
                if (pay < 0) {
                    fail(player, "minecraftportsmod.market.wont_buy");
                } else {
                    remove(player, g.item, a.qty());
                    org.webtrade.minecraftportsmod.colony.Wallet.add(player, pay);
                    Market.sold(s, g, a.qty(), pay, data.day(), who);
                    player.sendSystemMessage(Component.translatable("minecraftportsmod.market.sold", a.qty(), g.displayName(), pay)
                            .withStyle(ChatFormatting.GREEN));
                }
            }
            case DELIVER -> {
                Order o = Market.order(s, a.what());
                if (o == null) return;
                int units = Math.min(count(player, o.good().item), o.remaining());
                if (units <= 0) {
                    fail(player, "minecraftportsmod.market.not_enough_goods");
                    return;
                }
                remove(player, o.good().item, units);
                int pay = Market.deliver(s, o, units, data.day(), who);
                if (pay > 0) org.webtrade.minecraftportsmod.colony.Wallet.add(player, pay);
                player.sendSystemMessage(Component.translatable(o.remaining() == 0 ? "minecraftportsmod.market.order_done"
                        : "minecraftportsmod.market.delivered", units, o.good().displayName(), pay).withStyle(ChatFormatting.GOLD));
            }
        }
        data.changed();
        send(player, true);
    }

    private static void fail(ServerPlayer player, String key) {
        player.sendOverlayMessage(Component.translatable(key).withStyle(ChatFormatting.RED));
    }

    // ------------------------------------------------------------------ inventory

    /** Undamaged items of this kind the player carries. */
    static int count(ServerPlayer player, Item item) {
        Inventory inv = player.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item) && !s.isDamaged()) n += s.getCount();
        }
        return n;
    }

    private static void remove(ServerPlayer player, Item item, int n) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && n > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(item) || s.isDamaged()) continue;
            int take = Math.min(n, s.getCount());
            s.shrink(take);
            n -= take;
        }
        inv.setChanged();
    }

    private static void give(ServerPlayer player, Item item, int n) {
        int max = new ItemStack(item).getMaxStackSize();
        while (n > 0) {
            int c = Math.min(n, max);
            ItemStack stack = new ItemStack(item, c);
            if (!player.getInventory().add(stack)) player.drop(stack, false);
            n -= c;
        }
    }
}
