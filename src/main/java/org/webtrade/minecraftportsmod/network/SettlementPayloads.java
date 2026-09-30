package org.webtrade.minecraftportsmod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.util.ArrayList;
import java.util.List;

/** The town hall screen: a settlement's stores, people, trade and chronicle. */
public final class SettlementPayloads {

    private SettlementPayloads() {
    }

    /** Needs, in the order of {@link View#needs}. */
    public static final String[] NEEDS = {"food", "fuel", "tools", "luxury", "housing", "productivity", "survival", "everyday", "prosperity"};

    /**
     * One good of the settlement.
     *
     * @param good    Good ordinal
     * @param history daily prices, oldest first
     */
    public record GoodRow(int good, float stock, float produced, float consumed, float demand, float price, float[] history) {
        static void write(FriendlyByteBuf buf, GoodRow r) {
            buf.writeVarInt(r.good);
            buf.writeFloat(r.stock);
            buf.writeFloat(r.produced);
            buf.writeFloat(r.consumed);
            buf.writeFloat(r.demand);
            buf.writeFloat(r.price);
            buf.writeVarInt(r.history.length);
            for (float f : r.history) buf.writeFloat(f);
        }

        static GoodRow read(FriendlyByteBuf buf) {
            int good = buf.readVarInt();
            float stock = buf.readFloat(), produced = buf.readFloat(), consumed = buf.readFloat(), demand = buf.readFloat(), price = buf.readFloat();
            float[] h = new float[Math.min(buf.readVarInt(), 64)];
            for (int i = 0; i < h.length; i++) h[i] = buf.readFloat();
            return new GoodRow(good, stock, produced, consumed, demand, price, h);
        }
    }

    /**
     * A trade vessel of this settlement, or a visitor heading here.
     *
     * @param phase TradeRun.Phase ordinal, or -1 when idle at home
     */
    public record TradeLine(String vessel, String partner, int phase, boolean visitor, Component cargo) {
        static void write(RegistryFriendlyByteBuf buf, TradeLine t) {
            buf.writeUtf(t.vessel, 64);
            buf.writeUtf(t.partner, 64);
            buf.writeVarInt(t.phase + 1);
            buf.writeBoolean(t.visitor);
            ComponentSerialization.STREAM_CODEC.encode(buf, t.cargo);
        }

        static TradeLine read(RegistryFriendlyByteBuf buf) {
            return new TradeLine(buf.readUtf(64), buf.readUtf(64), buf.readVarInt() - 1, buf.readBoolean(),
                    ComponentSerialization.STREAM_CODEC.decode(buf));
        }
    }

    /** A market the settlement's merchants know about. */
    /** @param relation goodwill 0..100 (30 and more: trade partners) */
    public record Market(String name, int daysAgo, boolean reachable, float relation) {
    }

    public record LogLine(long day, Component text) {
    }

    /**
     * @param spec     Specialization ordinal
     * @param level    Settlement.Level ordinal
     * @param workers  interleaved {Profession ordinal, count}
     * @param needs    satisfaction 0..1 per {@link #NEEDS}
     * @param progress  share of the current economy day already passed
     * @param levelDays days qualified for the next level (positive) or failing the current one (negative)
     * @param effects   interleaved {EventType ordinal, days left}
     */
    public record View(int portId, String name, int spec, int level, int population, int houses, float happiness,
                       float treasury, long day, float progress, int[] workers, float[] needs, List<GoodRow> goods,
                       List<TradeLine> trade, List<Market> markets, List<LogLine> log, int levelDays, int[] effects)
            implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(Minecraftportsmod.id("settlement_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.portId);
            buf.writeUtf(v.name, 64);
            buf.writeVarInt(v.spec);
            buf.writeVarInt(v.level);
            buf.writeVarInt(v.population);
            buf.writeVarInt(v.houses);
            buf.writeFloat(v.happiness);
            buf.writeFloat(v.treasury);
            buf.writeVarLong(v.day);
            buf.writeFloat(v.progress);
            buf.writeVarIntArray(v.workers);
            buf.writeVarInt(v.needs.length);
            for (float f : v.needs) buf.writeFloat(f);
            buf.writeVarInt(v.goods.size());
            v.goods.forEach(r -> GoodRow.write(buf, r));
            buf.writeVarInt(v.trade.size());
            v.trade.forEach(t -> TradeLine.write(buf, t));
            buf.writeVarInt(v.markets.size());
            v.markets.forEach(m -> {
                buf.writeUtf(m.name, 64);
                buf.writeVarInt(m.daysAgo);
                buf.writeBoolean(m.reachable);
                buf.writeFloat(m.relation);
            });
            buf.writeVarInt(v.log.size());
            v.log.forEach(l -> {
                buf.writeVarLong(l.day);
                ComponentSerialization.STREAM_CODEC.encode(buf, l.text);
            });
            buf.writeVarInt(v.levelDays + 1000);
            buf.writeVarIntArray(v.effects);
        }, buf -> {
            int portId = buf.readVarInt();
            String name = buf.readUtf(64);
            int spec = buf.readVarInt(), level = buf.readVarInt(), pop = buf.readVarInt(), houses = buf.readVarInt();
            float happiness = buf.readFloat(), treasury = buf.readFloat();
            long day = buf.readVarLong();
            float progress = buf.readFloat();
            int[] workers = buf.readVarIntArray(256);
            float[] needs = new float[Math.min(buf.readVarInt(), 16)];
            for (int i = 0; i < needs.length; i++) needs[i] = buf.readFloat();
            int n = buf.readVarInt();
            List<GoodRow> goods = new ArrayList<>(n);
            for (int i = 0; i < n; i++) goods.add(GoodRow.read(buf));
            n = buf.readVarInt();
            List<TradeLine> trade = new ArrayList<>(n);
            for (int i = 0; i < n; i++) trade.add(TradeLine.read(buf));
            n = buf.readVarInt();
            List<Market> markets = new ArrayList<>(n);
            for (int i = 0; i < n; i++) markets.add(new Market(buf.readUtf(64), buf.readVarInt(), buf.readBoolean(), buf.readFloat()));
            n = buf.readVarInt();
            List<LogLine> log = new ArrayList<>(n);
            for (int i = 0; i < n; i++) log.add(new LogLine(buf.readVarLong(), ComponentSerialization.STREAM_CODEC.decode(buf)));
            int levelDays = buf.readVarInt() - 1000;
            int[] effects = buf.readVarIntArray(64);
            return new View(portId, name, spec, level, pop, houses, happiness, treasury, day, progress, workers, needs,
                    goods, trade, markets, log, levelDays, effects);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One piece of the world's news: the day, the village it's about, what happened. */
    public record NewsLine(long day, String place, Component text) {
    }

    /** The world's news, newest first. */
    public record News(List<NewsLine> lines) implements CustomPacketPayload {
        public static final Type<News> TYPE = new Type<>(Minecraftportsmod.id("news"));
        public static final StreamCodec<RegistryFriendlyByteBuf, News> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.lines.size());
            v.lines.forEach(l -> {
                buf.writeVarLong(l.day);
                buf.writeUtf(l.place, 64);
                ComponentSerialization.STREAM_CODEC.encode(buf, l.text);
            });
        }, buf -> {
            int n = buf.readVarInt();
            List<NewsLine> lines = new ArrayList<>(n);
            for (int i = 0; i < n; i++) lines.add(new NewsLine(buf.readVarLong(), buf.readUtf(64), ComponentSerialization.STREAM_CODEC.decode(buf)));
            return new News(lines);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RequestNews() implements CustomPacketPayload {
        public static final Type<RequestNews> TYPE = new Type<>(Minecraftportsmod.id("request_news"));
        public static final StreamCodec<FriendlyByteBuf, RequestNews> CODEC = StreamCodec.of((buf, p) -> {
        }, buf -> new RequestNews());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Ask for (or refresh) the town hall view of a port's settlement. */
    public record Request(int portId) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Minecraftportsmod.id("request_settlement"));
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarInt(p.portId), buf -> new Request(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
