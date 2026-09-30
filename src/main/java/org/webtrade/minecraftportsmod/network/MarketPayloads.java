package org.webtrade.minecraftportsmod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.util.ArrayList;
import java.util.List;

/** A settlement's market and orders, opened by talking to one of its residents. */
public final class MarketPayloads {

    private MarketPayloads() {
    }

    /**
     * One good on the market. The client prices any quantity itself from {@code stock} and {@code target}
     * (see Market#buyTotal / #sellTotal); the server checks again when the deal is made.
     *
     * @param available units the settlement will sell
     * @param have      units the player carries
     */
    public record Row(int good, float stock, float target, int available, float unitBuy, float unitSell, int have) {
        static void write(FriendlyByteBuf buf, Row r) {
            buf.writeVarInt(r.good);
            buf.writeFloat(r.stock);
            buf.writeFloat(r.target);
            buf.writeVarInt(r.available);
            buf.writeFloat(r.unitBuy);
            buf.writeFloat(r.unitSell);
            buf.writeVarInt(r.have);
        }

        static Row read(FriendlyByteBuf buf) {
            return new Row(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readFloat(), buf.readFloat(),
                    buf.readVarInt());
        }
    }

    public record OrderRow(int id, int good, int amount, int delivered, int reward, int daysLeft, int have) {
        static void write(FriendlyByteBuf buf, OrderRow o) {
            buf.writeVarInt(o.id);
            buf.writeVarInt(o.good);
            buf.writeVarInt(o.amount);
            buf.writeVarInt(o.delivered);
            buf.writeVarInt(o.reward);
            buf.writeVarInt(o.daysLeft);
            buf.writeVarInt(o.have);
        }

        static OrderRow read(FriendlyByteBuf buf) {
            return new OrderRow(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt());
        }
    }

    /**
     * @param refresh    an update for an open market screen (never opens a closed one)
     * @param resident   name of the resident spoken to
     * @param profession Profession ordinal of the resident
     */
    public record View(boolean refresh, int portId, String name, int spec, String resident, int profession, float treasury,
                       int emeralds, List<Row> rows, List<OrderRow> orders) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(Minecraftportsmod.id("market_view"));
        public static final StreamCodec<FriendlyByteBuf, View> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeBoolean(v.refresh);
            buf.writeVarInt(v.portId);
            buf.writeUtf(v.name, 64);
            buf.writeVarInt(v.spec);
            buf.writeUtf(v.resident, 64);
            buf.writeVarInt(v.profession);
            buf.writeFloat(v.treasury);
            buf.writeVarInt(v.emeralds);
            buf.writeVarInt(v.rows.size());
            v.rows.forEach(r -> Row.write(buf, r));
            buf.writeVarInt(v.orders.size());
            v.orders.forEach(o -> OrderRow.write(buf, o));
        }, buf -> {
            boolean refresh = buf.readBoolean();
            int port = buf.readVarInt();
            String name = buf.readUtf(64);
            int spec = buf.readVarInt();
            String resident = buf.readUtf(64);
            int prof = buf.readVarInt();
            float treasury = buf.readFloat();
            int emeralds = buf.readVarInt();
            int n = buf.readVarInt();
            List<Row> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) rows.add(Row.read(buf));
            n = buf.readVarInt();
            List<OrderRow> orders = new ArrayList<>(n);
            for (int i = 0; i < n; i++) orders.add(OrderRow.read(buf));
            return new View(refresh, port, name, spec, resident, prof, treasury, emeralds, rows, orders);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public enum Kind {BUY, SELL, DELIVER}

    /**
     * @param what Good ordinal (BUY, SELL) or order id (DELIVER)
     * @param qty  units (BUY, SELL); ignored for DELIVER (everything the player has, up to what is still needed)
     */
    public record Action(Kind kind, int portId, int what, int qty) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Minecraftportsmod.id("market_action"));
        public static final StreamCodec<FriendlyByteBuf, Action> CODEC = StreamCodec.of((buf, a) -> {
            buf.writeEnum(a.kind);
            buf.writeVarInt(a.portId);
            buf.writeVarInt(a.what);
            buf.writeVarInt(a.qty);
        }, buf -> new Action(buf.readEnum(Kind.class), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
