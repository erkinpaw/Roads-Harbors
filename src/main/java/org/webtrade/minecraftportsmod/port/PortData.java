package org.webtrade.minecraftportsmod.port;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * World-wide registry of ports, docks and routes. Lives in the server's global saved data,
 * is only touched from the server thread.
 */
public final class PortData extends SavedData {

    public static final Codec<PortData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Port.CODEC.listOf().optionalFieldOf("ports", List.of()).forGetter(d -> new ArrayList<>(d.ports.values())),
            Dock.CODEC.listOf().optionalFieldOf("docks", List.of()).forGetter(d -> new ArrayList<>(d.docks.values())),
            Route.CODEC.listOf().optionalFieldOf("routes", List.of()).forGetter(d -> new ArrayList<>(d.routes.values())),
            Codec.INT.optionalFieldOf("next_port", 1).forGetter(d -> d.nextPortId),
            Codec.INT.optionalFieldOf("next_dock", 1).forGetter(d -> d.nextDockId)
    ).apply(i, PortData::new));

    public static final SavedDataType<PortData> TYPE = new SavedDataType<>(
            Minecraftportsmod.id("ports"), PortData::new, CODEC, null);

    private final Map<Integer, Port> ports = new LinkedHashMap<>();
    private final Map<Integer, Dock> docks = new LinkedHashMap<>();
    private final Map<Long, Route> routes = new LinkedHashMap<>();
    private int nextPortId;
    private int nextDockId;

    public PortData() {
        this(List.of(), List.of(), List.of(), 1, 1);
    }

    private PortData(List<Port> ports, List<Dock> docks, List<Route> routes, int nextPort, int nextDock) {
        ports.forEach(p -> this.ports.put(p.id(), p));
        docks.forEach(d -> this.docks.put(d.id(), d));
        routes.forEach(r -> this.routes.put(r.key(), r));
        this.nextPortId = nextPort;
        this.nextDockId = nextDock;
    }

    public static PortData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    // ------------------------------------------------------------------ ports

    public Collection<Port> ports() {
        return Collections.unmodifiableCollection(ports.values());
    }

    public List<Port> ports(ResourceKey<Level> dimension) {
        List<Port> list = new ArrayList<>();
        for (Port p : ports.values()) {
            if (p.dimension().equals(dimension)) list.add(p);
        }
        return list;
    }

    public Port port(int id) {
        return ports.get(id);
    }

    public Port portAt(ResourceKey<Level> dimension, BlockPos office) {
        for (Port p : ports.values()) {
            if (p.dimension().equals(dimension) && p.office().equals(office)) return p;
        }
        return null;
    }

    public Port nearestPort(ResourceKey<Level> dimension, BlockPos pos, double maxDistance) {
        Port best = null;
        double bestSq = maxDistance * maxDistance;
        for (Port p : ports.values()) {
            if (!p.dimension().equals(dimension)) continue;
            double sq = p.office().distSqr(pos);
            if (sq <= bestSq) {
                bestSq = sq;
                best = p;
            }
        }
        return best;
    }

    public Port createPort(String name, ResourceKey<Level> dimension, BlockPos office, UUID owner, String ownerName, long time) {
        Port port = new Port(nextPortId++, name, dimension, office, owner, ownerName, time);
        ports.put(port.id(), port);
        setDirty();
        return port;
    }

    public void removePort(int id) {
        if (ports.remove(id) == null) return;
        routes.values().removeIf(r -> r.connects(id));
        docks.values().removeIf(d -> d.portId() == id);
        setDirty();
    }

    public void renamePort(Port port, String name) {
        port.setName(name);
        setDirty();
    }

    public void setOwner(Port port, UUID owner, String ownerName) {
        port.setOwner(owner, ownerName);
        setDirty();
    }

    public void setAnchorage(Port port, BlockPos anchorage) {
        port.setAnchorage(anchorage);
        setDirty();
    }

    public int defaultPortNumber() {
        return nextPortId;
    }

    // ------------------------------------------------------------------ docks

    public Collection<Dock> docks() {
        return Collections.unmodifiableCollection(docks.values());
    }

    public Dock dock(int id) {
        return docks.get(id);
    }

    public List<Dock> docksOf(int portId) {
        List<Dock> list = new ArrayList<>();
        for (Dock d : docks.values()) {
            if (d.portId() == portId) list.add(d);
        }
        return list;
    }

    public Dock dockOccupiedBy(UUID vessel) {
        for (Dock d : docks.values()) {
            if (vessel.equals(d.occupant())) return d;
        }
        return null;
    }

    public Dock createDock(int portId, BlockPos berth) {
        Dock dock = new Dock(nextDockId++, portId, berth);
        docks.put(dock.id(), dock);
        setDirty();
        return dock;
    }

    public void removeDock(int id) {
        if (docks.remove(id) != null) setDirty();
    }

    public void setBerth(Dock dock, BlockPos berth) {
        dock.setBerth(berth);
        setDirty();
    }

    public void setLocked(Dock dock, boolean locked) {
        dock.setLocked(locked);
        setDirty();
    }

    public void setOccupant(Dock dock, UUID vessel) {
        dock.setOccupant(vessel);
        setDirty();
    }

    /** Frees every dock held by this vessel (it left, was destroyed, or moved elsewhere). */
    public void releaseDocksOf(UUID vessel) {
        for (Dock d : docks.values()) {
            if (vessel.equals(d.occupant())) {
                d.setOccupant(null);
                setDirty();
            }
        }
    }

    // ------------------------------------------------------------------ routes

    public Collection<Route> routes() {
        return Collections.unmodifiableCollection(routes.values());
    }

    public Route route(int portX, int portY) {
        return routes.get(Route.key(portX, portY));
    }

    public Route getOrCreateRoute(int portX, int portY) {
        Route r = routes.computeIfAbsent(Route.key(portX, portY), k -> new Route(portX, portY));
        setDirty();
        return r;
    }

    public void updateRoute(Route route, Route.Status status, int[] waypoints, double length, long time) {
        route.setStatus(status);
        if (waypoints != null) {
            route.setPath(waypoints, length, time);
        }
        setDirty();
    }

    public void setRouteStatus(Route route, Route.Status status) {
        route.setStatus(status);
        setDirty();
    }
}
