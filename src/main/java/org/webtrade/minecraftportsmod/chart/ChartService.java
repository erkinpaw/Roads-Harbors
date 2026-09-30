package org.webtrade.minecraftportsmod.chart;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.webtrade.minecraftportsmod.nav.ChunkNav;
import org.webtrade.minecraftportsmod.nav.DimensionNavCache;
import org.webtrade.minecraftportsmod.nav.NavCacheManager;
import org.webtrade.minecraftportsmod.network.ChartPayloads;
import org.webtrade.minecraftportsmod.port.Dock;
import org.webtrade.minecraftportsmod.port.Port;
import org.webtrade.minecraftportsmod.port.PortData;
import org.webtrade.minecraftportsmod.port.Route;
import org.webtrade.minecraftportsmod.route.RouteManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.webtrade.minecraftportsmod.fleet.FleetManager;
import org.webtrade.minecraftportsmod.fleet.VesselRecord;
import org.webtrade.minecraftportsmod.port.PortService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;

/**
 * Builds what the chart screen shows: port/route overview and the map imagery tiles.
 */
public final class ChartService {

    /** Blocks per chart tile edge (8 chunks). */
    public static final int TILE_BLOCKS = NavCacheManager.TILE_CHUNKS * 16;
    /** Per tile pixel flag: the chunk has been scanned. */
    public static final int FLAG_KNOWN = 0x80;
    /** Max tiles a single player may pull per second. */
    private static final int TILES_PER_SECOND = 96;
    /** Budget of route waypoint ints sent with one chart (routes of the origin port are always sent). */
    private static final int ROUTE_PATH_BUDGET = 400_000;

    private static final Map<UUID, long[]> RATE = new HashMap<>();
    private static ExecutorService tileExecutor;

    private ChartService() {
    }

    // ------------------------------------------------------------------ overview

    /** What a player's open chart is about, so refreshes and actions use the same context. */
    private record Context(int mode, int portId) {
    }

    private static final Map<UUID, Context> CONTEXT = new HashMap<>();

    /** Right-click on a port office. */
    public static void openOffice(ServerPlayer player, Port port) {
        CONTEXT.put(player.getUUID(), new Context(ChartPayloads.MODE_OFFICE, port.id()));
        send(player, false);
    }

    /** Key binding: steer your vessel if you sit in one, otherwise just look at the chart. */
    public static void openFromKey(ServerPlayer player) {
        if (FleetManager.riddenBy(player) == null) {
            player.sendOverlayMessage(Component.translatable("minecraftportsmod.chart.key_needs_vessel").withStyle(ChatFormatting.GRAY));
            return;
        }
        CONTEXT.put(player.getUUID(), new Context(ChartPayloads.MODE_BOAT, -1));
        send(player, false);
    }

    public static void refresh(ServerPlayer player) {
        if (CONTEXT.containsKey(player.getUUID())) send(player, true);
    }

    private static void send(ServerPlayer player, boolean refresh) {
        ServerLevel level = (ServerLevel) player.level();
        MinecraftServer server = level.getServer();
        PortData data = PortData.get(server);
        Context ctx = CONTEXT.get(player.getUUID());
        int mode = ctx.mode;
        if (mode == ChartPayloads.MODE_BOAT && FleetManager.riddenBy(player) == null) mode = ChartPayloads.MODE_VIEW;
        Port office = mode == ChartPayloads.MODE_OFFICE ? data.port(ctx.portId) : null;
        if (mode == ChartPayloads.MODE_OFFICE && office == null) mode = ChartPayloads.MODE_VIEW;
        int originId = office == null ? -1 : office.id();
        if (mode == ChartPayloads.MODE_BOAT) {
            // aboard at a port: that port is "here", so routes and travel times are shown from it
            VesselRecord ridden = FleetManager.riddenBy(player);
            Port at = ridden.state() == VesselRecord.State.MOORED || ridden.state() == VesselRecord.State.ANCHORED
                    ? data.port(ridden.portId()) : null;
            if (at == null && ridden.state() != VesselRecord.State.SAILING) {
                at = FleetManager.nearPort(data, level.dimension(), player.getX(), player.getZ());
            }
            if (at != null) originId = at.id();
        }

        var settlements = org.webtrade.minecraftportsmod.economy.SettlementData.get(server);
        List<ChartPayloads.PortInfo> ports = new ArrayList<>();
        for (Port p : data.ports(level.dimension())) {
            List<Dock> docks = data.docksOf(p.id());
            int stride = ChartPayloads.PortInfo.BERTH_STRIDE;
            int[] berths = new int[docks.size() * stride];
            for (int i = 0; i < docks.size(); i++) {
                Dock d = docks.get(i);
                berths[i * stride] = d.id();
                berths[i * stride + 1] = d.berth().getX();
                berths[i * stride + 2] = d.berth().getZ();
                berths[i * stride + 3] = d.isFree() ? 0 : 1;
                berths[i * stride + 4] = d.isLocked() ? 1 : 0;
            }
            var a = p.anchorage();
            var settlement = settlements.get(p.id());
            ports.add(new ChartPayloads.PortInfo(p.id(), p.name(), p.ownerName(), p.office().getX(), p.office().getZ(),
                    a != null, a == null ? 0 : a.getX(), a == null ? 0 : a.getZ(), berths,
                    settlement == null ? -1 : settlement.spec().ordinal(), settlement == null ? 0 : settlement.population()));
        }

        final int focusId = originId;
        List<ChartPayloads.RouteInfo> routes = new ArrayList<>();
        int budget = ROUTE_PATH_BUDGET;
        List<Route> ordered = new ArrayList<>(data.routes());
        ordered.sort((x, y) -> Boolean.compare(!x.connects(focusId), !y.connects(focusId)));
        for (Route r : ordered) {
            Port a = data.port(r.portA());
            if (a == null || !a.dimension().equals(level.dimension())) continue;
            int[] path = new int[0];
            if (r.isUsable() && (r.connects(originId) || budget >= r.waypoints().length)) {
                path = r.waypoints();
                budget -= path.length;
            }
            routes.add(new ChartPayloads.RouteInfo(r.portA(), r.portB(), r.status().ordinal(),
                    RouteManager.isCalculating(r), (float) r.length(), path));
        }

        List<ChartPayloads.VesselInfo> fleet = new ArrayList<>();
        for (VesselRecord v : FleetManager.vesselsOf(server, player.getUUID())) {
            if (!v.dimension().equals(level.dimension())) continue;
            var voyage = v.voyage();
            fleet.add(new ChartPayloads.VesselInfo(v.id(), v.name(), v.state().ordinal(), v.portId(), v.dockId(),
                    v.x(), v.z(), v.yaw(), voyage == null ? -1 : voyage.destPortId(),
                    voyage == null ? 0 : (float) voyage.remaining(), (float) v.speed(), v.isPlanning(), v.type().tier()));
        }

        List<ChartPayloads.TraderInfo> traders = new ArrayList<>();
        for (var s : settlements.all()) {
            for (java.util.UUID id : s.vessels()) {
                VesselRecord v = FleetManager.record(server, id);
                if (v == null || !v.dimension().equals(level.dimension())) continue;
                var voyage = v.voyage();
                var run = settlements.run(id);
                int[] cargo = new int[0];
                if (run != null) {
                    List<Integer> c = new ArrayList<>();
                    run.cargo().forEach((g, u) -> {
                        if (u >= 1) {
                            c.add(g.ordinal());
                            c.add((int) Math.round(u));
                        }
                    });
                    cargo = c.stream().mapToInt(Integer::intValue).toArray();
                }
                traders.add(new ChartPayloads.TraderInfo(v.name(), v.x(), v.z(), v.yaw(), s.portId(),
                        voyage == null ? -1 : voyage.destPortId(), v.type().tier(), cargo));
            }
        }

        VesselRecord riding = mode == ChartPayloads.MODE_BOAT ? FleetManager.riddenBy(player) : null;
        boolean manage = office != null && PortService.canManage(office, player);
        ServerPlayNetworking.send(player, new ChartPayloads.OpenChart(refresh, mode, originId, manage,
                riding == null ? null : riding.id(), ports, routes, fleet, traders,
                FleetManager.countItem(player, net.minecraft.world.item.Items.DIAMOND), player.isCreative()));
    }

    public static void handleRename(ServerPlayer player, ChartPayloads.Rename rename) {
        Context ctx = CONTEXT.get(player.getUUID());
        if (rename.vessel() != null) {
            FleetManager.rename(player, rename.vessel(), rename.name());
        } else if (ctx != null && ctx.mode == ChartPayloads.MODE_OFFICE && ctx.portId == rename.portId()) {
            Port port = PortData.get(player.level().getServer()).port(rename.portId());
            if (port != null && PortService.canManage(port, player) && !rename.name().isBlank()) {
                PortService.renamePort((ServerLevel) player.level(), port, rename.name());
            }
        }
        if (ctx != null) send(player, true);
    }

    public static void handleAction(ServerPlayer player, ChartPayloads.FleetAction action) {
        Context ctx = CONTEXT.get(player.getUUID());
        MinecraftServer server = player.level().getServer();
        PortData data = PortData.get(server);
        Port office = ctx != null && ctx.mode == ChartPayloads.MODE_OFFICE ? data.port(ctx.portId) : null;
        ServerLevel level = (ServerLevel) player.level();
        switch (action.action()) {
            case BUILD -> {
                if (office != null) FleetManager.build(player, office);
            }
            case SEND -> {
                if (office != null && action.vessel() != null) FleetManager.send(player, action.vessel(), action.portId(), false);
            }
            case SUMMON -> {
                if (office != null && action.vessel() != null) FleetManager.send(player, action.vessel(), office.id(), false);
            }
            case SAIL -> FleetManager.send(player, null, action.portId(), true);
            case STOP -> FleetManager.stop(player);
            case ADD_BERTH -> {
                if (office != null && PortService.canManage(office, player)) {
                    Dock d = PortService.addBerth(level, office);
                    player.sendSystemMessage(d == null
                            ? Component.translatable("minecraftportsmod.berth.no_space").withStyle(ChatFormatting.RED)
                            : Component.translatable("minecraftportsmod.berth.added", office.name()).withStyle(ChatFormatting.GOLD));
                }
            }
            case REMOVE_BERTH -> {
                if (office != null && PortService.canManage(office, player)) {
                    if (PortService.removeBerth(level, office, action.dockId())) {
                        player.sendSystemMessage(Component.translatable("minecraftportsmod.berth.removed").withStyle(ChatFormatting.GRAY));
                    }
                }
            }
            case LOCK_BERTH -> {
                if (office != null && PortService.canManage(office, player)) {
                    PortService.setBerthLocked(level, office, action.dockId(), action.x() != 0);
                }
            }
            case MOVE_BERTH -> {
                if (office != null && PortService.canManage(office, player)) {
                    String why = PortService.moveBerthManually(level, office, action.dockId(), action.x(), action.z());
                    player.sendSystemMessage(why == null
                            ? Component.translatable("minecraftportsmod.berth.moved").withStyle(ChatFormatting.GOLD)
                            : Component.translatable(why).withStyle(ChatFormatting.RED));
                }
            }
            case OPEN_HOLD -> {
                FleetManager.openHold(player, FleetManager.riddenBy(player));
                return;
            }
            case UPGRADE -> {
                if (office != null && action.vessel() != null) FleetManager.upgrade(player, action.vessel(), office);
            }
            case DISMANTLE -> {
                if (office != null && action.vessel() != null) FleetManager.dismantle(player, action.vessel());
            }
        }
        if (ctx != null) send(player, true);
    }

    // ------------------------------------------------------------------ tiles

    public static void sendTiles(ServerPlayer player, int[] request) {
        if (request.length % 3 != 0) return;
        ServerLevel level = (ServerLevel) player.level();
        MinecraftServer server = level.getServer();
        DimensionNavCache cache = NavCacheManager.get(level);

        long[] bucket = RATE.computeIfAbsent(player.getUUID(), k -> new long[]{0, TILES_PER_SECOND});
        long now = System.currentTimeMillis();
        long refill = (now - bucket[0]) * TILES_PER_SECOND / 1000;
        if (refill > 0) {
            bucket[1] = Math.min(TILES_PER_SECOND, bucket[1] + refill);
            bucket[0] = now;
        }

        List<int[]> todo = new ArrayList<>();
        for (int i = 0; i < request.length; i += 3) {
            int tx = request[i], tz = request[i + 1], known = request[i + 2];
            int rev = NavCacheManager.tileRevision(level.dimension(), tx, tz);
            if (rev == known) continue;
            if (bucket[1] <= 0) break;
            bucket[1]--;
            todo.add(new int[]{tx, tz, rev});
        }
        if (todo.isEmpty()) return;

        executor().execute(() -> {
            for (int[] t : todo) {
                byte[] data = buildTile(cache, t[0], t[1]);
                ChartPayloads.ChartTile payload = new ChartPayloads.ChartTile(t[0], t[1], t[2], data);
                server.execute(() -> {
                    if (!player.hasDisconnected()) ServerPlayNetworking.send(player, payload);
                });
            }
        });
    }

    private static synchronized ExecutorService executor() {
        if (tileExecutor == null) {
            tileExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Ports&Routes-ChartTiles");
                t.setDaemon(true);
                return t;
            });
        }
        return tileExecutor;
    }

    /**
     * Tile layout before compression: {@code TILE_BLOCKS²} packed map colours, then the same number of flag bytes
     * (bit 0 navigable, bit 1 surface water, bit 7 known), row-major with z as the row.
     */
    public static byte[] buildTile(DimensionNavCache cache, int tileX, int tileZ) {
        int n = TILE_BLOCKS * TILE_BLOCKS;
        byte[] raw = new byte[n * 2];
        int baseCx = tileX * NavCacheManager.TILE_CHUNKS;
        int baseCz = tileZ * NavCacheManager.TILE_CHUNKS;
        for (int ccz = 0; ccz < NavCacheManager.TILE_CHUNKS; ccz++) {
            for (int ccx = 0; ccx < NavCacheManager.TILE_CHUNKS; ccx++) {
                ChunkNav nav = cache.getChunk(baseCx + ccx, baseCz + ccz);
                if (nav == null || nav.isPredicted()) continue;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int idx = ChunkNav.index(lx, lz);
                        int px = ccx * 16 + lx;
                        int pz = ccz * 16 + lz;
                        int o = pz * TILE_BLOCKS + px;
                        raw[o] = (byte) nav.packedColor(idx);
                        raw[n + o] = (byte) (nav.flags(idx) | FLAG_KNOWN);
                    }
                }
            }
        }
        Deflater deflater = new Deflater(6);
        deflater.setInput(raw);
        deflater.finish();
        byte[] buf = new byte[raw.length + 64];
        int len = deflater.deflate(buf);
        deflater.end();
        byte[] out = new byte[len];
        System.arraycopy(buf, 0, out, 0, len);
        return out;
    }
}
