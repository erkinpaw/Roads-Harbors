package org.webtrade.minecraftportsmod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.util.ArrayList;
import java.util.List;

/**
 * The world map (the chart key, off a vessel): the land as far as it is known, the ports and their sea routes, the
 * villages and the trails between them, the merchants on the road and the player's own vessels.
 */
public final class WorldMapPayloads {

    private WorldMapPayloads() {
    }

    public record PortPin(int id, String name, int x, int z) {
    }

    /** A line on the map: a sea route, or a trail (its stretches made so far marked, one flag each). */
    public record Line(int kind, int[] path, byte[] made) {
        public static final int SEA = 0, TRAIL = 1;
    }

    public record VillagePin(int id, String name, int x, int z, int level, int people) {
    }

    /** Someone on the move: a village's merchant on his round, or a vessel of the player's. */
    public record Mover(int kind, String name, double x, double z, String home) {
        public static final int MERCHANT = 0, VESSEL = 1;
    }

    public record View(boolean refresh, List<PortPin> ports, List<Line> lines, List<VillagePin> villages, List<Mover> movers)
            implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(Minecraftportsmod.id("world_map"));
        public static final StreamCodec<FriendlyByteBuf, View> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeBoolean(v.refresh);
            buf.writeVarInt(v.ports.size());
            for (PortPin p : v.ports) {
                buf.writeVarInt(p.id);
                buf.writeUtf(p.name, 64);
                buf.writeVarInt(p.x);
                buf.writeVarInt(p.z);
            }
            buf.writeVarInt(v.lines.size());
            for (Line l : v.lines) {
                buf.writeByte(l.kind);
                buf.writeVarIntArray(l.path);
                buf.writeByteArray(l.made);
            }
            buf.writeVarInt(v.villages.size());
            for (VillagePin p : v.villages) {
                buf.writeVarInt(p.id);
                buf.writeUtf(p.name, 64);
                buf.writeVarInt(p.x);
                buf.writeVarInt(p.z);
                buf.writeVarInt(p.level);
                buf.writeVarInt(p.people);
            }
            buf.writeVarInt(v.movers.size());
            for (Mover m : v.movers) {
                buf.writeByte(m.kind);
                buf.writeUtf(m.name, 64);
                buf.writeDouble(m.x);
                buf.writeDouble(m.z);
                buf.writeUtf(m.home, 64);
            }
        }, buf -> {
            boolean refresh = buf.readBoolean();
            List<PortPin> ports = new ArrayList<>();
            for (int i = buf.readVarInt(); i > 0; i--) ports.add(new PortPin(buf.readVarInt(), buf.readUtf(64), buf.readVarInt(), buf.readVarInt()));
            List<Line> lines = new ArrayList<>();
            for (int i = buf.readVarInt(); i > 0; i--) lines.add(new Line(buf.readByte(), buf.readVarIntArray(1 << 20), buf.readByteArray(1 << 19)));
            List<VillagePin> villages = new ArrayList<>();
            for (int i = buf.readVarInt(); i > 0; i--) {
                villages.add(new VillagePin(buf.readVarInt(), buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
            }
            List<Mover> movers = new ArrayList<>();
            for (int i = buf.readVarInt(); i > 0; i--) movers.add(new Mover(buf.readByte(), buf.readUtf(64), buf.readDouble(), buf.readDouble(), buf.readUtf(64)));
            return new View(refresh, ports, lines, villages, movers);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The open world map asks for fresh data (the merchants and vessels move). */
    public record Request() implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Minecraftportsmod.id("request_world_map"));
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.of((buf, p) -> {
        }, buf -> new Request());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
