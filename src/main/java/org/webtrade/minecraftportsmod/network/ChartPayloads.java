package org.webtrade.minecraftportsmod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Everything the nautical chart / port office screen talks about over the network.
 */
public final class ChartPayloads {

    private ChartPayloads() {
    }

    // ------------------------------------------------------------------ shared records

    /**
     * @param berths     interleaved {dockId, x, z, occupied(0/1), locked(0/1)} groups of {@link #BERTH_STRIDE}
     * @param spec       Specialization ordinal of the port's settlement, or -1 if nobody lives there
     * @param population residents of the settlement
     */
    public record PortInfo(int id, String name, String owner, int x, int z, boolean hasAnchorage, int anchorX, int anchorZ,
                           int[] berths, int spec, int population) {
        public static final int BERTH_STRIDE = 5;

        public int docks() {
            return berths.length / BERTH_STRIDE;
        }

        public int freeDocks() {
            int n = 0;
            for (int i = 0; i < berths.length; i += BERTH_STRIDE) if (berths[i + 3] == 0) n++;
            return n;
        }

        static void write(FriendlyByteBuf buf, PortInfo p) {
            buf.writeVarInt(p.id);
            buf.writeUtf(p.name, 64);
            buf.writeUtf(p.owner, 64);
            buf.writeInt(p.x);
            buf.writeInt(p.z);
            buf.writeBoolean(p.hasAnchorage);
            buf.writeInt(p.anchorX);
            buf.writeInt(p.anchorZ);
            buf.writeVarIntArray(p.berths);
            buf.writeVarInt(p.spec + 1);
            buf.writeVarInt(p.population);
        }

        static PortInfo read(FriendlyByteBuf buf) {
            return new PortInfo(buf.readVarInt(), buf.readUtf(64), buf.readUtf(64), buf.readInt(), buf.readInt(),
                    buf.readBoolean(), buf.readInt(), buf.readInt(), buf.readVarIntArray(4096), buf.readVarInt() - 1, buf.readVarInt());
        }

        public boolean settled() {
            return spec >= 0;
        }
    }

    /** status: 0 pending, 1 ok, 2 blocked, 3 no path (see Route.Status ordinal). */
    public record RouteInfo(int portA, int portB, int status, boolean calculating, float length, int[] path) {
        static void write(FriendlyByteBuf buf, RouteInfo r) {
            buf.writeVarInt(r.portA);
            buf.writeVarInt(r.portB);
            buf.writeByte(r.status);
            buf.writeBoolean(r.calculating);
            buf.writeFloat(r.length);
            buf.writeVarIntArray(r.path);
        }

        static RouteInfo read(FriendlyByteBuf buf) {
            return new RouteInfo(buf.readVarInt(), buf.readVarInt(), buf.readByte(), buf.readBoolean(), buf.readFloat(),
                    buf.readVarIntArray(1 << 20));
        }
    }

    /**
     * One of the player's vessels.
     *
     * @param state     VesselRecord.State ordinal: 0 moored, 1 anchored, 2 sailing, 3 adrift
     * @param portId    port it is moored/anchored at, or -1
     * @param destPortId destination while sailing, or -1
     * @param remaining blocks left to sail
     */
    public record VesselInfo(UUID id, String name, int state, int portId, int dockId, double x, double z, float yaw,
                             int destPortId, float remaining, float speed, boolean planning, int tier) {
        static void write(FriendlyByteBuf buf, VesselInfo v) {
            buf.writeUUID(v.id);
            buf.writeUtf(v.name, 64);
            buf.writeByte(v.state);
            buf.writeVarInt(v.portId + 1);
            buf.writeVarInt(v.dockId + 1);
            buf.writeDouble(v.x);
            buf.writeDouble(v.z);
            buf.writeFloat(v.yaw);
            buf.writeVarInt(v.destPortId + 1);
            buf.writeFloat(v.remaining);
            buf.writeFloat(v.speed);
            buf.writeBoolean(v.planning);
            buf.writeVarInt(v.tier);
        }

        static VesselInfo read(FriendlyByteBuf buf) {
            return new VesselInfo(buf.readUUID(), buf.readUtf(64), buf.readByte(), buf.readVarInt() - 1, buf.readVarInt() - 1,
                    buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readVarInt() - 1, buf.readFloat(), buf.readFloat(),
                    buf.readBoolean(), buf.readVarInt());
        }
    }

    /**
     * A settlement's trade vessel, shown on everyone's chart.
     *
     * @param home  port of its settlement
     * @param dest  port it is sailing to, or -1
     */
    public record TraderInfo(String name, double x, double z, float yaw, int home, int dest, int tier, int[] cargo) {
        /** @param cargo interleaved {Good ordinal, units} of what is in the hold */
        static void write(FriendlyByteBuf buf, TraderInfo t) {
            buf.writeUtf(t.name, 64);
            buf.writeDouble(t.x);
            buf.writeDouble(t.z);
            buf.writeFloat(t.yaw);
            buf.writeVarInt(t.home + 1);
            buf.writeVarInt(t.dest + 1);
            buf.writeVarInt(t.tier);
            buf.writeVarIntArray(t.cargo);
        }

        static TraderInfo read(FriendlyByteBuf buf) {
            return new TraderInfo(buf.readUtf(64), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readVarInt() - 1,
                    buf.readVarInt() - 1, buf.readVarInt(), buf.readVarIntArray(128));
        }
    }

    // ------------------------------------------------------------------ S2C: open / refresh the chart

    public static final int MODE_VIEW = 0;
    public static final int MODE_OFFICE = 1;
    public static final int MODE_BOAT = 2;

    /**
     * @param refresh      an update for a chart that should already be open (never opens a closed one)
     * @param mode         {@link #MODE_OFFICE} opened at a port office, {@link #MODE_BOAT} sitting in an own vessel,
     *                     {@link #MODE_VIEW} anywhere else
     * @param originPortId the office's port (office mode), else -1
     * @param canManage    the player may add/remove berths of the office's port
     * @param riding       the vessel the player sits in (boat mode), else null
     */
    public record OpenChart(boolean refresh, int mode, int originPortId, boolean canManage, UUID riding, List<PortInfo> ports,
                            List<RouteInfo> routes, List<VesselInfo> fleet, List<TraderInfo> traders, int diamonds,
                            boolean creative) implements CustomPacketPayload {
        public static final Type<OpenChart> TYPE = new Type<>(Minecraftportsmod.id("open_chart"));
        public static final StreamCodec<FriendlyByteBuf, OpenChart> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeBoolean(p.refresh);
            buf.writeByte(p.mode);
            buf.writeVarInt(p.originPortId + 1);
            buf.writeBoolean(p.canManage);
            buf.writeBoolean(p.riding != null);
            if (p.riding != null) buf.writeUUID(p.riding);
            buf.writeVarInt(p.ports.size());
            p.ports.forEach(i -> PortInfo.write(buf, i));
            buf.writeVarInt(p.routes.size());
            p.routes.forEach(r -> RouteInfo.write(buf, r));
            buf.writeVarInt(p.fleet.size());
            p.fleet.forEach(v -> VesselInfo.write(buf, v));
            buf.writeVarInt(p.traders.size());
            p.traders.forEach(t -> TraderInfo.write(buf, t));
            buf.writeVarInt(p.diamonds);
            buf.writeBoolean(p.creative);
        }, buf -> {
            boolean refresh = buf.readBoolean();
            int mode = buf.readByte();
            int origin = buf.readVarInt() - 1;
            boolean manage = buf.readBoolean();
            UUID riding = buf.readBoolean() ? buf.readUUID() : null;
            int pc = buf.readVarInt();
            List<PortInfo> ports = new ArrayList<>(pc);
            for (int i = 0; i < pc; i++) ports.add(PortInfo.read(buf));
            int rc = buf.readVarInt();
            List<RouteInfo> routes = new ArrayList<>(rc);
            for (int i = 0; i < rc; i++) routes.add(RouteInfo.read(buf));
            int fc = buf.readVarInt();
            List<VesselInfo> fleet = new ArrayList<>(fc);
            for (int i = 0; i < fc; i++) fleet.add(VesselInfo.read(buf));
            int tc = buf.readVarInt();
            List<TraderInfo> traders = new ArrayList<>(tc);
            for (int i = 0; i < tc; i++) traders.add(TraderInfo.read(buf));
            int diamonds = buf.readVarInt();
            boolean creative = buf.readBoolean();
            return new OpenChart(refresh, mode, origin, manage, riding, ports, routes, fleet, traders, diamonds, creative);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------ S2C: one map tile

    /** 128x128 blocks of chart imagery, deflate-compressed (see ChartService#buildTile). */
    public record ChartTile(int tileX, int tileZ, int revision, byte[] data) implements CustomPacketPayload {
        public static final Type<ChartTile> TYPE = new Type<>(Minecraftportsmod.id("chart_tile"));
        public static final StreamCodec<FriendlyByteBuf, ChartTile> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeInt(p.tileX);
            buf.writeInt(p.tileZ);
            buf.writeInt(p.revision);
            buf.writeByteArray(p.data);
        }, buf -> new ChartTile(buf.readInt(), buf.readInt(), buf.readInt(), buf.readByteArray(1 << 17)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------ C2S

    /**
     * Open the chart from the key binding ({@code refresh = false}), or re-send the data of the chart that is
     * already open, in the same mode ({@code refresh = true}).
     */
    public record RequestChart(boolean refresh) implements CustomPacketPayload {
        public static final Type<RequestChart> TYPE = new Type<>(Minecraftportsmod.id("request_chart"));
        public static final StreamCodec<FriendlyByteBuf, RequestChart> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBoolean(p.refresh), buf -> new RequestChart(buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Interleaved {tileX, tileZ, knownRevision} triples. */
    public record RequestTiles(int[] tiles) implements CustomPacketPayload {
        public static final int MAX_TILES = 48;
        public static final Type<RequestTiles> TYPE = new Type<>(Minecraftportsmod.id("request_tiles"));
        public static final StreamCodec<FriendlyByteBuf, RequestTiles> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarIntArray(p.tiles),
                buf -> new RequestTiles(buf.readVarIntArray(MAX_TILES * 3)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Rename the office's port ({@code vessel == null}) or one of the player's vessels. */
    public record Rename(int portId, UUID vessel, String name) implements CustomPacketPayload {
        public static final Type<Rename> TYPE = new Type<>(Minecraftportsmod.id("rename"));
        public static final StreamCodec<FriendlyByteBuf, Rename> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.portId + 1);
            buf.writeBoolean(p.vessel != null);
            if (p.vessel != null) buf.writeUUID(p.vessel);
            buf.writeUtf(p.name, 64);
        }, buf -> new Rename(buf.readVarInt() - 1, buf.readBoolean() ? buf.readUUID() : null, buf.readUtf(64)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Something the player asks the fleet / the port to do. */
    public record FleetAction(Action action, UUID vessel, int portId, int dockId, int x, int z) implements CustomPacketPayload {
        public FleetAction(Action action, UUID vessel, int portId, int dockId) {
            this(action, vessel, portId, dockId, 0, 0);
        }

        public enum Action {
            /** Build a vessel at the office's port (uses a boat from the inventory). */
            BUILD,
            /** Send {@code vessel} (crewless) to {@code portId}. */
            SEND,
            /** Call {@code vessel} to the office's port. */
            SUMMON,
            /** Sail the vessel the player sits in to {@code portId}. */
            SAIL,
            /** Stop the vessel the player sits in. */
            STOP,
            /** Add a berth to the office's port. */
            ADD_BERTH,
            /** Remove berth {@code dockId} of the office's port. */
            REMOVE_BERTH,
            /** Take {@code vessel} apart; its boat goes back into the player's inventory. */
            DISMANTLE,
            /** Upgrade {@code vessel} (moored at the office's port) to the next hull class. */
            UPGRADE,
            /** Open the hold of the vessel the player sits in. */
            OPEN_HOLD,
            /** Lock berth {@code dockId} in place (x = 1) or unlock it (x = 0). */
            LOCK_BERTH,
            /** Move berth {@code dockId} to ({@code x}, {@code z}) and lock it. */
            MOVE_BERTH
        }

        private static final UUID NONE = new UUID(0, 0);

        public static FleetAction of(Action action) {
            return new FleetAction(action, null, -1, -1);
        }

        public static final Type<FleetAction> TYPE = new Type<>(Minecraftportsmod.id("fleet_action"));
        public static final StreamCodec<FriendlyByteBuf, FleetAction> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeEnum(p.action);
            buf.writeUUID(p.vessel == null ? NONE : p.vessel);
            buf.writeVarInt(p.portId + 1);
            buf.writeVarInt(p.dockId + 1);
            buf.writeInt(p.x);
            buf.writeInt(p.z);
        }, buf -> {
            Action a = buf.readEnum(Action.class);
            UUID v = buf.readUUID();
            return new FleetAction(a, NONE.equals(v) ? null : v, buf.readVarInt() - 1, buf.readVarInt() - 1, buf.readInt(), buf.readInt());
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
