package org.webtrade.minecraftportsmod.economy;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.webtrade.minecraftportsmod.fleet.FleetManager;
import org.webtrade.minecraftportsmod.fleet.VesselRecord;
import org.webtrade.minecraftportsmod.network.SettlementPayloads;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Builds the town hall view of a settlement for a player. */
public final class TownHallService {

    private TownHallService() {
    }

    public static void send(ServerPlayer player, int portId) {
        MinecraftServer srv = player.level().getServer();
        SettlementData data = SettlementData.get(srv);
        Settlement s = data.get(portId);
        if (s == null) return;

        List<Integer> workers = new ArrayList<>();
        s.workers().forEach((p, n) -> {
            workers.add(p.ordinal());
            workers.add(n);
        });
        float[] needs = new float[SettlementPayloads.NEEDS.length];
        for (int i = 0; i < needs.length; i++) needs[i] = (float) s.need(SettlementPayloads.NEEDS[i]);

        List<SettlementPayloads.GoodRow> goods = new ArrayList<>();
        for (Good g : Good.values()) {
            double stock = s.stock(g), prod = s.produced(g), cons = s.consumed(g), demand = s.demand(g);
            if (stock < 0.5 && prod < 0.05 && cons < 0.05 && demand < 0.05) continue;
            List<Float> h = s.history(g);
            float[] hist = new float[h.size()];
            for (int i = 0; i < hist.length; i++) hist[i] = h.get(i);
            goods.add(new SettlementPayloads.GoodRow(g.ordinal(), (float) stock, (float) prod, (float) cons, (float) demand,
                    (float) Simulation.price(s, g), hist));
        }

        // what is in the stores first, then what is only wanted
        goods.sort(java.util.Comparator.comparing((SettlementPayloads.GoodRow r) -> r.stock() < 0.5));

        List<SettlementPayloads.TradeLine> trade = new ArrayList<>();
        for (UUID id : s.vessels()) {
            VesselRecord r = FleetManager.record(srv, id);
            if (r == null) continue;
            TradeRun run = data.run(id);
            if (run == null) {
                trade.add(new SettlementPayloads.TradeLine(r.name(), "", -1, false, Component.empty()));
            } else {
                Settlement partner = data.get(run.partner);
                trade.add(new SettlementPayloads.TradeLine(r.name(), partner == null ? "?" : EconomyManager.name(srv, partner),
                        run.phase.ordinal(), false, EconomyManager.cargoText(run.cargo)));
            }
        }
        for (TradeRun run : data.runs()) {
            if (run.partner != s.id() || run.phase == TradeRun.Phase.RETURN) continue;
            VesselRecord r = FleetManager.record(srv, run.vessel);
            Settlement home = data.get(run.home);
            if (r == null || home == null) continue;
            trade.add(new SettlementPayloads.TradeLine(r.name(), EconomyManager.name(srv, home), run.phase.ordinal(), true,
                    EconomyManager.cargoText(run.cargo)));
        }

        List<SettlementPayloads.Market> markets = new ArrayList<>();
        for (Settlement.Knowledge k : s.knowledge.values()) {
            Settlement other = data.get(k.settlement());
            if (other == null) continue;
            markets.add(new SettlementPayloads.Market(EconomyManager.name(srv, other), (int) Math.max(0, data.day - k.day()),
                    EconomyManager.connected(srv, s, other), (float) s.relation(other.id())));
        }

        List<SettlementPayloads.LogLine> log = new ArrayList<>();
        for (Settlement.LogEntry e : s.log()) log.add(new SettlementPayloads.LogLine(e.day(), e.text()));

        int[] w = workers.stream().mapToInt(Integer::intValue).toArray();
        ServerPlayNetworking.send(player, new SettlementPayloads.View(s.id(), EconomyManager.name(srv, s), s.spec().ordinal(),
                s.level().ordinal(), s.population(), s.houses(), (float) s.happiness(), (float) s.treasury(), data.day,
                (float) data.dayTicks / data.dayLength, w, needs, goods, trade, markets, log, s.levelDays(), effects(s, data.day)));
    }

    private static int[] effects(Settlement s, long day) {
        int[] out = new int[s.effects().size() * 2];
        for (int i = 0; i < s.effects().size(); i++) {
            out[i * 2] = s.effects().get(i).type().ordinal();
            out[i * 2 + 1] = (int) Math.max(0, s.effects().get(i).until() - day + 1);
        }
        return out;
    }

    /** The world's news for the chart's news screen. */
    public static void sendNews(ServerPlayer player) {
        MinecraftServer srv = player.level().getServer();
        SettlementData data = SettlementData.get(srv);
        List<SettlementPayloads.NewsLine> lines = new ArrayList<>();
        for (SettlementData.News n : data.news()) {
            Settlement s = data.get(n.portId());
            lines.add(new SettlementPayloads.NewsLine(n.day(), s == null ? "?" : EconomyManager.name(srv, s), n.text()));
        }
        ServerPlayNetworking.send(player, new SettlementPayloads.News(lines));
    }
}
