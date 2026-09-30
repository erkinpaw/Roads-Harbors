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

    /** A player's mark: {@code mine} when the viewer may change it (its maker, or an operator). */
    public record MarkPin(int id, String name, String icon, int x, int z, long time, String owner, boolean mine, int[] shots) {
    }

    public record ShotPin(int id, int x, int z, long time, String owner, int mark) {
    }

    public record View(boolean refresh, List<PortPin> ports, List<Line> lines, List<VillagePin> villages, List<Mover> movers,
                       List<MarkPin> marks, List<ShotPin> shots) implements CustomPacketPayload {
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
            buf.writeVarInt(v.marks.size());
            for (MarkPin m : v.marks) {
                buf.writeVarInt(m.id);
                buf.writeUtf(m.name, 64);
                buf.writeUtf(m.icon, 32);
                buf.writeVarInt(m.x);
                buf.writeVarInt(m.z);
                buf.writeLong(m.time);
                buf.writeUtf(m.owner, 64);
                buf.writeBoolean(m.mine);
                buf.writeVarIntArray(m.shots);
            }
            buf.writeVarInt(v.shots.size());
            for (ShotPin p : v.shots) {
                buf.writeVarInt(p.id);
                buf.writeVarInt(p.x);
                buf.writeVarInt(p.z);
                buf.writeLong(p.time);
                buf.writeUtf(p.owner, 64);
                buf.writeVarInt(p.mark);
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
            List<MarkPin> marks = new ArrayList<>();
            for (int i = buf.readVarInt(); i > 0; i--) {
                marks.add(new MarkPin(buf.readVarInt(), buf.readUtf(64), buf.readUtf(32), buf.readVarInt(), buf.readVarInt(), buf.readLong(),
                        buf.readUtf(64), buf.readBoolean(), buf.readVarIntArray(4096)));
            }
            List<ShotPin> shots = new ArrayList<>();
            for (int i = buf.readVarInt(); i > 0; i--) {
                shots.add(new ShotPin(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readLong(), buf.readUtf(64), buf.readVarInt()));
            }
            return new View(refresh, ports, lines, villages, movers, marks, shots);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Something done to the marks: a new one (at x, z, with an icon and a name), a changed one (its name and icon),
     * one taken away, or a screenshot taken off its mark (and away).
     */
    public record MarkAction(int action, int id, int x, int z, String icon, String name) implements CustomPacketPayload {
        public static final int CREATE = 0, EDIT = 1, DELETE = 2, DELETE_SHOT = 3;
        public static final Type<MarkAction> TYPE = new Type<>(Minecraftportsmod.id("mark_action"));
        public static final StreamCodec<FriendlyByteBuf, MarkAction> CODEC = StreamCodec.of((buf, a) -> {
            buf.writeVarInt(a.action);
            buf.writeVarInt(a.id);
            buf.writeVarInt(a.x);
            buf.writeVarInt(a.z);
            buf.writeUtf(a.icon, 32);
            buf.writeUtf(a.name, 64);
        }, buf -> new MarkAction(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(32), buf.readUtf(64)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A screenshot sent to the server, a part at a time (a JPEG): for a mark ({@code mark}), or (-1) where the player
     * stands: to the nearest mark of the player, or a new one.
     */
    public record ShotPart(int upload, int mark, int part, int parts, byte[] data) implements CustomPacketPayload {
        public static final int MAX_PART = 30000;
        public static final Type<ShotPart> TYPE = new Type<>(Minecraftportsmod.id("shot_part"));
        public static final StreamCodec<FriendlyByteBuf, ShotPart> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.upload);
            buf.writeVarInt(p.mark);
            buf.writeVarInt(p.part);
            buf.writeVarInt(p.parts);
            buf.writeByteArray(p.data);
        }, buf -> new ShotPart(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(MAX_PART)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ShotRequest(int id) implements CustomPacketPayload {
        public static final Type<ShotRequest> TYPE = new Type<>(Minecraftportsmod.id("shot_request"));
        public static final StreamCodec<FriendlyByteBuf, ShotRequest> CODEC = StreamCodec.of((buf, p) -> buf.writeVarInt(p.id),
                buf -> new ShotRequest(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A screenshot's picture (a JPEG; empty when there is none). */
    public record ShotData(int id, byte[] jpeg) implements CustomPacketPayload {
        public static final int MAX = 1 << 20;
        public static final Type<ShotData> TYPE = new Type<>(Minecraftportsmod.id("shot_data"));
        public static final StreamCodec<FriendlyByteBuf, ShotData> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.id);
            buf.writeByteArray(p.jpeg);
        }, buf -> new ShotData(buf.readVarInt(), buf.readByteArray(MAX)));

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
