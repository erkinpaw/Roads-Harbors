package org.webtrade.minecraftportsmod.port;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.webtrade.minecraftportsmod.fleet.FleetManager;
import org.webtrade.minecraftportsmod.nav.DimensionNavCache;
import org.webtrade.minecraftportsmod.nav.NavCacheManager;
import org.webtrade.minecraftportsmod.nav.WaterPathfinder;
import org.webtrade.minecraftportsmod.route.RouteManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-thread logic for founding and dissolving ports, and for managing their berths.
 */
public final class PortService {

    /** How far from the office an anchorage may be. */
    public static final int ANCHORAGE_RADIUS = 48;
    /** Max name length. */
    public static final int MAX_NAME = 32;

    private PortService() {
    }

    public static Port createPort(ServerLevel level, BlockPos office, Player placer, String customName) {
        PortData data = PortData.get(level.getServer());
        Port existing = data.portAt(level.dimension(), office);
        if (existing != null) return existing;

        String name = customName != null && !customName.isBlank()
                ? trimName(customName)
                : org.webtrade.minecraftportsmod.fleet.Names.portName(
                        placer instanceof net.minecraft.server.level.ServerPlayer sp ? sp : null,
                        data.ports().stream().map(Port::name).toList());
        Port port = data.createPort(name, level.dimension(), office,
                placer == null ? null : placer.getUUID(),
                placer == null ? "" : placer.getGameProfile().name(),
                level.getGameTime());

        refreshAnchorage(level, port);
        Dock first = port.anchorage() == null ? null : addBerth(level, port);

        if (placer != null) {
            placer.sendSystemMessage(Component.translatable("minecraftportsmod.port.created", name).withStyle(ChatFormatting.GOLD));
            if (port.anchorage() == null) {
                placer.sendSystemMessage(Component.translatable("minecraftportsmod.port.no_water").withStyle(ChatFormatting.RED));
            } else if (first == null) {
                placer.sendSystemMessage(Component.translatable("minecraftportsmod.berth.no_space").withStyle(ChatFormatting.YELLOW));
            }
        }
        RouteManager.onPortChanged(level.getServer(), port);
        return port;
    }

    public static void removePort(ServerLevel level, BlockPos office) {
        PortData data = PortData.get(level.getServer());
        Port port = data.portAt(level.dimension(), office);
        if (port == null) return;
        data.removePort(port.id());
        RouteManager.onPortRemoved(port.id());
        org.webtrade.minecraftportsmod.economy.EconomyManager.onPortRemoved(level.getServer(), port.id());
        FleetManager.onPortRemoved(level.getServer(), port.id());
    }

    public static void renamePort(ServerLevel level, Port port, String name) {
        PortData.get(level.getServer()).renamePort(port, trimName(name));
    }

    public static String trimName(String name) {
        String n = name.strip();
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME) : n;
    }

    public static boolean canManage(Port port, Player player) {
        return port.owner() == null || port.owner().equals(player.getUUID()) || net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(player.permissions());
    }

    // ------------------------------------------------------------------ anchorage

    /** Re-picks the open-water anchorage of a port; returns true if it changed. */
    public static boolean refreshAnchorage(ServerLevel level, Port port) {
        NavCacheManager.scanLoadedArea(level, port.office(), (ANCHORAGE_RADIUS >> 4) + 1);
        DimensionNavCache cache = NavCacheManager.get(level);
        WaterPathfinder pf = new WaterPathfinder(cache);
        BlockPos o = port.office();

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -ANCHORAGE_RADIUS; dx <= ANCHORAGE_RADIUS; dx++) {
            for (int dz = -ANCHORAGE_RADIUS; dz <= ANCHORAGE_RADIUS; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > ANCHORAGE_RADIUS) continue;
                int c = pf.clearance(o.getX() + dx, o.getZ() + dz);
                if (c < 2) continue;
                // prefer close, but really prefer open water
                double score = dist + (5 - c) * 10.0;
                if (score < bestScore) {
                    bestScore = score;
                    best = new BlockPos(o.getX() + dx, cache.waterY(), o.getZ() + dz);
                }
            }
        }
        if (best == null ? port.anchorage() == null : best.equals(port.anchorage())) return false;
        PortData.get(level.getServer()).setAnchorage(port, best);
        return true;
    }

    public static boolean anchorageStillValid(ServerLevel level, Port port) {
        BlockPos a = port.anchorage();
        return a != null && NavCacheManager.get(level).isNavigable(a.getX(), a.getZ());
    }

    // ------------------------------------------------------------------ berths

    public static List<BlockPos> berthsOf(PortData data, int portId, Dock except) {
        List<BlockPos> list = new ArrayList<>();
        for (Dock d : data.docksOf(portId)) {
            if (d != except) list.add(d.berth());
        }
        return list;
    }

    /** Adds a berth in the closest valid water to the office; null if there is no room. */
    public static Dock addBerth(ServerLevel level, Port port) {
        PortData data = PortData.get(level.getServer());
        if (data.docksOf(port.id()).size() >= BerthPlanner.MAX_BERTHS) return null;
        if (!anchorageStillValid(level, port)) refreshAnchorage(level, port);
        BlockPos pos = BerthPlanner.find(level, port, berthsOf(data, port.id(), null), port.office());
        return pos == null ? null : data.createDock(port.id(), pos);
    }

    /** Removes a berth. A vessel moored there moves to another berth (or anchors). */
    public static boolean removeBerth(ServerLevel level, Port port, int dockId) {
        PortData data = PortData.get(level.getServer());
        Dock dock = data.dock(dockId);
        if (dock == null || dock.portId() != port.id()) return false;
        FleetManager.onBerthRemoved(level, dock);
        data.removeDock(dockId);
        return true;
    }

    /**
     * Checks every berth of the port against the rules (after the water around it changed, e.g. a pier was built)
     * and moves the ones that break them to the nearest valid spot. Vessels moored there follow.
     */
    public static void revalidateBerths(ServerLevel level, Port port) {
        PortData data = PortData.get(level.getServer());
        List<Dock> docks = new ArrayList<>(data.docksOf(port.id()));
        // closest to the office first, so the best spots are taken in a stable order
        docks.sort(java.util.Comparator.comparingDouble(d -> d.berth().distSqr(port.office())));
        for (Dock dock : docks) {
            if (dock.isLocked()) continue; // the player put it there on purpose
            List<BlockPos> others = berthsOf(data, port.id(), dock);
            if (!BerthPlanner.isValid(level, port, dock.berth(), others)) {
                BlockPos moved = BerthPlanner.find(level, port, others, dock.berth());
                if (moved != null) {
                    data.setBerth(dock, moved);
                    FleetManager.onBerthMoved(level, dock);
                } else if (dock.isFree()) {
                    data.removeDock(dock.id());
                }
                continue;
            }
            // still fine, but maybe a better spot opened up (a pier was taken down): drift back towards the office
            BlockPos best = BerthPlanner.find(level, port, others, port.office());
            if (best != null && Math.sqrt(best.distSqr(port.office())) + 2 < Math.sqrt(dock.berth().distSqr(port.office()))) {
                data.setBerth(dock, best);
                FleetManager.onBerthMoved(level, dock);
            }
        }
    }

    /** Puts a berth exactly where the player asked and locks it. Returns null on success or a reason key. */
    public static String moveBerthManually(ServerLevel level, Port port, int dockId, int x, int z) {
        PortData data = PortData.get(level.getServer());
        Dock dock = data.dock(dockId);
        if (dock == null || dock.portId() != port.id()) return "minecraftportsmod.berth.manual_unknown";
        BlockPos pos = new BlockPos(x, NavCacheManager.get(level).waterY(), z);
        String why = BerthPlanner.checkManual(level, port, pos, berthsOf(data, port.id(), dock));
        if (why != null) return why;
        data.setBerth(dock, pos);
        data.setLocked(dock, true);
        FleetManager.onBerthMoved(level, dock);
        return null;
    }

    public static void setBerthLocked(ServerLevel level, Port port, int dockId, boolean locked) {
        PortData data = PortData.get(level.getServer());
        Dock dock = data.dock(dockId);
        if (dock == null || dock.portId() != port.id()) return;
        data.setLocked(dock, locked);
        if (!locked) revalidateBerths(level, port);
    }
}
