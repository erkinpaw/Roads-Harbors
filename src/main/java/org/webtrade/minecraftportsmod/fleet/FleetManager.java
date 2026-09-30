package org.webtrade.minecraftportsmod.fleet;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.nav.DimensionNavCache;
import org.webtrade.minecraftportsmod.nav.NavCacheManager;
import org.webtrade.minecraftportsmod.nav.WaterPathfinder;
import org.webtrade.minecraftportsmod.port.BerthPlanner;
import org.webtrade.minecraftportsmod.port.Dock;
import org.webtrade.minecraftportsmod.port.Port;
import org.webtrade.minecraftportsmod.port.PortData;
import org.webtrade.minecraftportsmod.port.PortService;
import org.webtrade.minecraftportsmod.port.Route;
import org.webtrade.minecraftportsmod.registry.ModContent;
import org.webtrade.minecraftportsmod.vessel.VesselEntity;
import org.webtrade.minecraftportsmod.vessel.Voyage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs the fleet: keeps every vessel's record in sync with its in-world body, moves vessels "virtually" along
 * their voyage while nobody is near them, spawns their bodies when someone comes close, and carries out
 * what players ask for (build, send, summon, sail, stop).
 */
public final class FleetManager {

    /** Vessels a single player may own. */
    public static final int MAX_VESSELS_PER_PLAYER = 8;
    /** A vessel within this distance of a port's anchorage counts as being "at" that port when departing. */
    public static final double AT_PORT_RADIUS = 64;
    private static final int FLEET_TICK_INTERVAL = 20;

    private static MinecraftServer server;
    private static final Map<UUID, VesselEntity> LIVE = new HashMap<>();
    private static ExecutorService planner;

    private FleetManager() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            server = srv;
            LIVE.clear();
            planner = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Ports&Routes-VoyagePlanner");
                t.setDaemon(true);
                return t;
            });
            for (VesselRecord r : FleetData.get(srv).all()) r.planning = false;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            if (planner != null) planner.shutdownNow();
            planner = null;
            LIVE.clear();
            server = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(org.webtrade.minecraftportsmod.Perf.timed("FleetManager", FleetManager::tick));
        // a vessel isn't saved with its passenger: step off before the player's data is written
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            if (handler.player.getVehicle() instanceof VesselEntity) handler.player.stopRiding();
        });
    }

    // ------------------------------------------------------------------ lookups

    public static VesselRecord record(MinecraftServer srv, UUID id) {
        return FleetData.get(srv).get(id);
    }

    public static List<VesselRecord> vesselsOf(MinecraftServer srv, UUID owner) {
        return FleetData.get(srv).ownedBy(owner);
    }

    public static VesselEntity live(UUID id) {
        VesselEntity e = LIVE.get(id);
        return e == null || e.isRemoved() ? null : e;
    }

    /** The owned vessel the player is sitting in, if any. */
    public static VesselRecord riddenBy(ServerPlayer player) {
        if (player.getVehicle() instanceof VesselEntity e && e.record() != null && e.record().isOwnedBy(player.getUUID())) {
            return e.record();
        }
        return null;
    }

    // ------------------------------------------------------------------ fleet tick

    private static void tick(MinecraftServer srv) {
        if (server == null || srv.getTickCount() % FLEET_TICK_INTERVAL != 0) return;
        FleetData data = FleetData.get(srv);
        for (VesselRecord r : new ArrayList<>(data.all())) {
            ServerLevel level = srv.getLevel(r.dimension());
            if (level == null) continue;
            VesselEntity body = live(r.id());
            if (body == null) {
                LIVE.remove(r.id());
                if (r.state() == VesselRecord.State.SAILING) {
                    Voyage v = r.voyage();
                    v.setProgress(v.progress() + r.speed() * FLEET_TICK_INTERVAL);
                    double[] pose = v.pose();
                    r.setPose(pose[0], pose[1], (float) pose[2]);
                    data.changed();
                    if (v.finished()) {
                        arrive(level, r, null);
                        continue;
                    }
                }
                BlockPos pos = BlockPos.containing(r.x(), NavCacheManager.get(level).waterY(), r.z());
                boolean rough = r.state() == VesselRecord.State.SAILING && r.voyage().rough();
                if (!rough && level.isPositionEntityTicking(pos)) materialize(level, r);
            } else {
                data.changed();
                // sailing on alone into chunks nobody is simulating: continue as a virtual voyage
                if (r.state() == VesselRecord.State.SAILING && !body.hasPlayerAboard()
                        && !level.isPositionEntityTicking(body.blockPosition())) {
                    dematerialize(body);
                }
            }
        }
    }

    private static void materialize(ServerLevel level, VesselRecord r) {
        Entity existing = level.getEntity(r.id());
        if (existing instanceof VesselEntity e && !e.isRemoved()) {
            e.bind(r);
            LIVE.put(r.id(), e);
            return;
        }
        VesselEntity e = ModContent.VESSEL.create(level, EntitySpawnReason.LOAD);
        if (e == null) return;
        e.setUUID(r.id());
        e.bind(r);
        e.snapTo(r.x(), NavCacheManager.get(level).waterY() + 1.0, r.z(), r.yaw(), 0);
        if (level.addFreshEntity(e)) {
            LIVE.put(r.id(), e);
            unstow(level, r, e);
        }
    }

    private static void dematerialize(VesselEntity e) {
        LIVE.remove(e.getUUID());
        if (e.record() != null) stowPassengers(e, e.record(), true);
        e.releaseBody();
    }

    /**
     * Saves every non-player passenger into the record (they would otherwise vanish with the body, which is never
     * saved). With {@code remove}, the passengers are taken out of the world too; during a chunk unload vanilla
     * removes them itself.
     */
    public static void stowPassengers(VesselEntity body, VesselRecord r, boolean remove) {
        if (!(body.level() instanceof ServerLevel level)) return;
        for (Entity passenger : new ArrayList<>(body.getPassengers())) {
            if (passenger instanceof Player) continue;
            var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                    net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
            if (passenger.saveAsPassenger(out)) r.stowed().add(out.buildResult());
            if (remove) {
                passenger.stopRiding();
                passenger.discard();
            }
        }
        changed();
    }

    /** Seats the stowed passengers on a freshly spawned body. */
    private static void unstow(ServerLevel level, VesselRecord r, VesselEntity body) {
        if (r.stowed().isEmpty()) return;
        List<net.minecraft.nbt.CompoundTag> tags = new ArrayList<>(r.stowed());
        r.stowed().clear();
        for (var tag : tags) {
            var input = net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag);
            EntityType.create(input, level, new net.minecraft.world.entity.EntitySpawnRequest(EntitySpawnReason.LOAD, true)).ifPresent(passenger -> {
                if (level.getEntity(passenger.getUUID()) != null) return; // already in the world somehow
                passenger.snapTo(body.getX(), body.getY() + 0.5, body.getZ(), body.getYRot(), 0);
                if (level.addFreshEntity(passenger)) passenger.startRiding(body, true, true);
            });
        }
        changed();
    }

    /** Stowed passengers of a vessel with no body are put down next to a player (dismantling far away). */
    private static void releaseStowed(ServerPlayer player, VesselRecord r) {
        ServerLevel level = (ServerLevel) player.level();
        for (var tag : r.stowed()) {
            var input = net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag);
            EntityType.create(input, level, new net.minecraft.world.entity.EntitySpawnRequest(EntitySpawnReason.LOAD, true)).ifPresent(e -> {
                e.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0);
                level.addFreshEntity(e);
            });
        }
        r.stowed().clear();
    }

    /** Called by the entity when it leaves the world for any reason. */
    public static void onBodyRemoved(VesselEntity e, Entity.RemovalReason reason, boolean released) {
        LIVE.remove(e.getUUID(), e);
        if (server == null || released) return;
        if (reason == Entity.RemovalReason.KILLED) {
            // broken by its owner (or /kill): the vessel is gone for good
            VesselRecord r = FleetData.get(server).get(e.getUUID());
            if (r != null) {
                PortData.get(server).releaseDocksOf(r.id());
                FleetData.get(server).remove(r.id());
                notifyOwner(r, Component.translatable("minecraftportsmod.vessel.destroyed", r.name()).withStyle(ChatFormatting.RED));
            }
        }
    }

    // ------------------------------------------------------------------ state changes

    static void changed() {
        if (server != null) FleetData.get(server).changed();
    }

    /** The voyage is done: tie up at the reserved berth, or wait at the anchorage. */
    public static void arrive(ServerLevel level, VesselRecord r, VesselEntity body) {
        Voyage v = r.voyage();
        PortData ports = PortData.get(level.getServer());
        Port port = v == null ? null : ports.port(v.destPortId());
        Dock dock = v == null || v.destDockId() < 0 ? null : ports.dock(v.destDockId());
        String portName = port == null ? "?" : port.name();
        if (dock != null && (dock.occupant() == null || dock.occupant().equals(r.id()))) {
            ports.setOccupant(dock, r.id());
            r.moor(dock.portId(), dock.id());
            r.setPose(dock.berth().getX() + 0.5, dock.berth().getZ() + 0.5, r.yaw());
            if (body != null) body.snapToBerth(dock.berth());
            notifyOwner(r, Component.translatable("minecraftportsmod.voyage.arrived", r.name(), portName).withStyle(ChatFormatting.GOLD));
        } else if (port != null) {
            r.anchor(port.id());
            notifyOwner(r, Component.translatable("minecraftportsmod.voyage.arrived_roadstead", r.name(), portName).withStyle(ChatFormatting.GOLD));
        } else {
            r.drift();
        }
        if (body != null) body.onStateChanged();
        changed();
        Minecraftportsmod.LOGGER.info("Vessel {} ({}) arrived at {}", r.name(), r.id(), portName);
        if (r.settlement() >= 0) org.webtrade.minecraftportsmod.economy.EconomyManager.onVesselArrived(level.getServer(), r);
    }

    /** The owner rowed away from the berth / anchorage by hand. */
    public static void leftPort(VesselRecord r) {
        if (server == null) return;
        PortData.get(server).releaseDocksOf(r.id());
        r.drift();
        changed();
    }

    /** An adrift vessel left right next to a free berth ties up there by itself. */
    public static void tryAutoMoor(ServerLevel level, VesselRecord r, VesselEntity body) {
        PortData ports = PortData.get(level.getServer());
        for (Dock d : ports.docks()) {
            if (!d.isFree()) continue;
            Port p = ports.port(d.portId());
            if (p == null || !p.dimension().equals(level.dimension())) continue;
            double dx = d.berth().getX() + 0.5 - body.getX(), dz = d.berth().getZ() + 0.5 - body.getZ();
            if (dx * dx + dz * dz <= 3 * 3) {
                ports.setOccupant(d, r.id());
                r.moor(p.id(), d.id());
                body.snapToBerth(d.berth());
                body.onStateChanged();
                changed();
                return;
            }
        }
    }

    public static void stop(ServerPlayer player) {
        VesselRecord r = riddenBy(player);
        if (r == null || r.state() != VesselRecord.State.SAILING) return;
        PortData ports = PortData.get(player.level().getServer());
        ports.releaseDocksOf(r.id());
        // stopped next to a port: it is waiting at that port's roadstead, not lost at sea
        VesselEntity body = live(r.id());
        double x = body != null ? body.getX() : r.x(), z = body != null ? body.getZ() : r.z();
        Port near = nearPort(ports, r.dimension(), x, z);
        if (near != null) r.anchor(near.id());
        else r.drift();
        if (body != null) body.onStateChanged();
        changed();
        player.sendSystemMessage(Component.translatable("minecraftportsmod.voyage.stopped").withStyle(ChatFormatting.GRAY));
    }

    /** The port whose anchorage is within {@link #AT_PORT_RADIUS} of a point, if any. */
    public static Port nearPort(PortData ports, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim, double x, double z) {
        Port best = null;
        double bestSq = AT_PORT_RADIUS * AT_PORT_RADIUS;
        for (Port p : ports.ports(dim)) {
            if (p.anchorage() == null) continue;
            double dx = p.anchorage().getX() + 0.5 - x, dz = p.anchorage().getZ() + 0.5 - z;
            if (dx * dx + dz * dz < bestSq) {
                bestSq = dx * dx + dz * dz;
                best = p;
            }
        }
        return best;
    }

    /** Called when a vessel got stuck: plan the rest of the trip again from where it is. */
    public static void replan(ServerLevel level, VesselRecord r) {
        Voyage v = r.voyage();
        if (v == null || r.planning) return;
        Port dest = PortData.get(level.getServer()).port(v.destPortId());
        if (dest == null) {
            leftPort(r);
            return;
        }
        planVoyage(level, r, dest, null, ok -> {
            if (!ok) {
                leftPort(r);
                VesselEntity body = live(r.id());
                if (body != null) body.onStateChanged();
                notifyOwner(r, Component.translatable("minecraftportsmod.voyage.stuck", r.name()).withStyle(ChatFormatting.RED));
            }
        });
    }

    public static void onBerthMoved(ServerLevel level, Dock dock) {
        if (dock.occupant() == null) return;
        VesselRecord r = FleetData.get(level.getServer()).get(dock.occupant());
        if (r == null || r.state() != VesselRecord.State.MOORED || r.dockId() != dock.id()) return;
        // sail over to the new spot
        BlockPos from = BlockPos.containing(r.x(), dock.berth().getY(), r.z());
        int[] path = BerthPlanner.approachPath(NavCacheManager.get(level), from, dock.berth(),
                PortService.berthsOf(PortData.get(level.getServer()), dock.portId(), dock));
        if (path == null) {
            r.setPose(dock.berth().getX() + 0.5, dock.berth().getZ() + 0.5, r.yaw());
            VesselEntity body = live(r.id());
            if (body != null) body.snapToBerth(dock.berth());
        } else {
            r.sail(new Voyage(path, dock.portId(), dock.id()));
            VesselEntity body = live(r.id());
            if (body != null) body.onStateChanged();
        }
        changed();
    }

    /**
     * A berth is about to be removed. A vessel moored there moves to another free berth of the port,
     * or drops anchor where it is if there is none.
     */
    public static void onBerthRemoved(ServerLevel level, Dock dock) {
        if (dock.occupant() == null) return;
        MinecraftServer srv = level.getServer();
        VesselRecord r = FleetData.get(srv).get(dock.occupant());
        PortData ports = PortData.get(srv);
        ports.setOccupant(dock, null);
        if (r == null || r.state() != VesselRecord.State.MOORED || r.dockId() != dock.id()) return; // en route: anchors on arrival
        Dock other = ports.docksOf(dock.portId()).stream().filter(d -> d != dock && d.isFree())
                .min(Comparator.comparingDouble(d -> d.berth().distSqr(dock.berth()))).orElse(null);
        int[] path = null;
        if (other != null) {
            List<BlockPos> rest = PortService.berthsOf(ports, dock.portId(), other);
            rest.remove(dock.berth());
            path = BerthPlanner.approachPath(NavCacheManager.get(level), BlockPos.containing(r.x(), dock.berth().getY(), r.z()),
                    other.berth(), rest);
        }
        if (path != null) {
            ports.setOccupant(other, r.id());
            r.sail(new Voyage(path, dock.portId(), other.id()));
        } else {
            r.anchor(dock.portId());
        }
        VesselEntity body = live(r.id());
        if (body != null) body.onStateChanged();
        changed();
    }

    /**
     * Upgrades a vessel moored at this port's shipyard to the next hull class, paying with diamonds
     * from the player's inventory (free in creative).
     */
    public static void upgrade(ServerPlayer player, UUID vesselId, Port shipyard) {
        MinecraftServer srv = player.level().getServer();
        VesselRecord r = record(srv, vesselId);
        if (r == null || !r.isOwnedBy(player.getUUID())) return;
        VesselType next = r.type().next();
        if (next == null) {
            fail(player, Component.translatable("minecraftportsmod.shipyard.max", r.name()));
            return;
        }
        if (r.state() != VesselRecord.State.MOORED || r.portId() != shipyard.id()) {
            fail(player, Component.translatable("minecraftportsmod.shipyard.not_here", r.name(), shipyard.name()));
            return;
        }
        var inv = player.getInventory();
        if (!player.isCreative()) {
            int have = countItem(player, next.upgradeItem);
            if (have < next.upgradeCost) {
                fail(player, Component.translatable("minecraftportsmod.shipyard.not_enough", next.upgradeCost, have));
                return;
            }
            int toTake = next.upgradeCost;
            for (int i = 0; i < inv.getContainerSize() && toTake > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (s.is(next.upgradeItem)) {
                    int n = Math.min(toTake, s.getCount());
                    inv.removeItem(i, n);
                    toTake -= n;
                }
            }
        }
        r.setType(next);
        VesselEntity body = live(r.id());
        if (body != null) body.refreshType();
        changed();
        player.sendSystemMessage(Component.translatable("minecraftportsmod.shipyard.done", r.name(), next.displayName())
                .withStyle(ChatFormatting.GOLD));
        Minecraftportsmod.LOGGER.info("Vessel {} upgraded to {} by {}", r.name(), next, player.getGameProfile().name());
    }

    public static int countItem(ServerPlayer player, net.minecraft.world.item.Item item) {
        int n = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    /** Opens the cargo hold of a vessel whose body is right here. */
    public static void openHold(ServerPlayer player, VesselRecord r) {
        if (r == null || !r.isOwnedBy(player.getUUID()) || live(r.id()) == null) return;
        player.openMenu(new net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider<Integer>() {
            @Override
            public Integer getScreenOpeningData(ServerPlayer p) {
                return r.cargo().size();
            }

            @Override
            public Component getDisplayName() {
                return Component.translatable("minecraftportsmod.hold.title", r.name());
            }

            @Override
            public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, net.minecraft.world.entity.player.Inventory inv, Player p) {
                return new org.webtrade.minecraftportsmod.vessel.HoldMenu(id, inv, new HoldContainer(r));
            }
        });
    }

    public static void rename(ServerPlayer player, UUID vesselId, String name) {
        VesselRecord r = record(player.level().getServer(), vesselId);
        String clean = PortService.trimName(name);
        if (r == null || !r.isOwnedBy(player.getUUID()) || clean.isEmpty()) return;
        r.setName(clean);
        VesselEntity body = live(r.id());
        if (body != null) body.setCustomName(Component.literal(clean));
        changed();
    }

    /**
     * Admin: hands every port and vessel owned by {@code fromName} (the name they were created under) over to a
     * player. Returns {ports, vessels}.
     */
    public static int[] transfer(MinecraftServer srv, String fromName, ServerPlayer to) {
        int ports = 0, vessels = 0;
        PortData pd = PortData.get(srv);
        for (Port p : pd.ports()) {
            if (p.ownerName().equalsIgnoreCase(fromName)) {
                pd.setOwner(p, to.getUUID(), to.getGameProfile().name());
                ports++;
            }
        }
        for (VesselRecord r : FleetData.get(srv).all()) {
            if (r.ownerName().equalsIgnoreCase(fromName)) {
                r.setOwner(to.getUUID(), to.getGameProfile().name());
                vessels++;
            }
        }
        changed();
        return new int[]{ports, vessels};
    }

    /** Takes a vessel apart: it disappears wherever it is and its boat goes back to the owner. */
    public static void dismantle(ServerPlayer player, UUID vesselId) {
        MinecraftServer srv = player.level().getServer();
        VesselRecord r = record(srv, vesselId);
        if (r == null || !r.isOwnedBy(player.getUUID())) return;
        VesselEntity body = live(r.id());
        if (body != null) {
            LIVE.remove(r.id());
            body.releaseBody(); // passengers step off where the vessel is
        }
        releaseStowed(player, r);
        PortData.get(srv).releaseDocksOf(r.id());
        FleetData.get(srv).remove(r.id());
        List<ItemStack> give = new ArrayList<>();
        give.add(new ItemStack(r.hull()));
        for (ItemStack s : r.cargo()) if (!s.isEmpty()) give.add(s.copy());
        for (ItemStack s : give) {
            if (!player.getInventory().add(s)) player.drop(s, false);
        }
        player.sendSystemMessage(Component.translatable("minecraftportsmod.vessel.dismantled", r.name()).withStyle(ChatFormatting.GRAY));
    }

    public static void onPortRemoved(MinecraftServer srv, int portId) {
        for (VesselRecord r : FleetData.get(srv).all()) {
            boolean here = (r.state() == VesselRecord.State.MOORED || r.state() == VesselRecord.State.ANCHORED) && r.portId() == portId;
            boolean headingHere = r.state() == VesselRecord.State.SAILING && r.voyage().destPortId() == portId;
            if (here || headingHere) {
                r.drift();
                VesselEntity body = live(r.id());
                if (body != null) body.onStateChanged();
            }
        }
        changed();
    }

    // ------------------------------------------------------------------ player actions

    /** Builds a vessel at a free berth of the port, using a boat from the player's inventory. */
    public static void build(ServerPlayer player, Port port) {
        ServerLevel level = (ServerLevel) player.level();
        MinecraftServer srv = level.getServer();
        if (vesselsOf(srv, player.getUUID()).size() >= MAX_VESSELS_PER_PLAYER) {
            fail(player, Component.translatable("minecraftportsmod.vessel.too_many", MAX_VESSELS_PER_PLAYER));
            return;
        }
        int slot = -1;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(ItemTags.BOATS)) {
                slot = i;
                break;
            }
        }
        if (slot < 0 && !player.isCreative()) {
            fail(player, Component.translatable("minecraftportsmod.vessel.need_boat"));
            return;
        }
        PortData ports = PortData.get(srv);
        Dock dock = ports.docksOf(port.id()).stream().filter(Dock::isFree)
                .min(Comparator.comparingDouble(d -> d.berth().distSqr(port.office()))).orElse(null);
        if (dock == null) {
            fail(player, Component.translatable("minecraftportsmod.berth.none_free", port.name()));
            return;
        }
        ItemStack boat = slot >= 0 ? inv.getItem(slot) : new ItemStack(net.minecraft.world.item.Items.OAK_BOAT);
        Component custom = boat.get(DataComponents.CUSTOM_NAME);
        String name = custom != null ? PortService.trimName(custom.getString())
                : Names.vesselName(player, vesselsOf(srv, player.getUUID()).stream().map(VesselRecord::name).toList());
        VesselRecord r = new VesselRecord(UUID.randomUUID(), player.getUUID(), player.getGameProfile().name(), name,
                boat.getItem(), level.dimension());
        if (slot >= 0 && !player.isCreative()) inv.removeItem(slot, 1);
        ports.setOccupant(dock, r.id());
        r.moor(port.id(), dock.id());
        r.setPose(dock.berth().getX() + 0.5, dock.berth().getZ() + 0.5, 0);
        FleetData.get(srv).add(r);
        player.sendSystemMessage(Component.translatable("minecraftportsmod.vessel.built", name, port.name()).withStyle(ChatFormatting.GOLD));
    }

    /** Sends one of the player's vessels to a port, with or without the player aboard. */
    public static void send(ServerPlayer player, UUID vesselId, int destPortId, boolean fromBoat) {
        ServerLevel level = (ServerLevel) player.level();
        MinecraftServer srv = level.getServer();
        VesselRecord r = fromBoat ? riddenBy(player) : record(srv, vesselId);
        Port dest = PortData.get(srv).port(destPortId);
        if (r == null || !r.isOwnedBy(player.getUUID()) || dest == null) return;
        if (!r.dimension().equals(dest.dimension())) {
            fail(player, Component.translatable("minecraftportsmod.voyage.other_dimension"));
            return;
        }
        if (r.planning) return;
        if ((r.state() == VesselRecord.State.MOORED || r.state() == VesselRecord.State.ANCHORED) && r.portId() == destPortId) {
            fail(player, Component.translatable("minecraftportsmod.voyage.already_there", r.name(), dest.name()));
            return;
        }
        VesselEntity body = live(r.id());
        if (!fromBoat && body != null && body.hasPlayerAboard()) {
            fail(player, Component.translatable("minecraftportsmod.voyage.someone_aboard", r.name()));
            return;
        }
        ServerLevel vesselLevel = srv.getLevel(r.dimension());
        if (vesselLevel == null) return;
        planVoyage(vesselLevel, r, dest, player, ok -> {
            if (!ok) return;
            Voyage v = r.voyage();
            int seconds = (int) Math.round(v.remaining() / (r.speed() * 20));
            player.sendSystemMessage(Component.translatable(fromBoat ? "minecraftportsmod.voyage.departing" : "minecraftportsmod.voyage.sent",
                    r.name(), dest.name(), Math.round(v.remaining()), formatTime(seconds)).withStyle(ChatFormatting.GOLD));
            if (v.destDockId() < 0) {
                player.sendSystemMessage(Component.translatable("minecraftportsmod.voyage.no_free_dock", dest.name()).withStyle(ChatFormatting.YELLOW));
            }
        });
    }

    private static void fail(ServerPlayer player, Component message) {
        player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED));
    }

    static void notifyOwner(VesselRecord r, Component message) {
        if (server == null || r.owner() == null) return;
        ServerPlayer p = server.getPlayerList().getPlayer(r.owner());
        if (p != null) p.sendSystemMessage(message);
    }

    public static String formatTime(int seconds) {
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    // ------------------------------------------------------------------ voyage planning

    /**
     * Plans a voyage from wherever the vessel is to a free berth of {@code dest} and starts it.
     * If the vessel is at a port connected to {@code dest}, the stored sea route is used; otherwise the way is
     * searched from scratch on a background thread. The destination berth is reserved right away so two
     * vessels never head for the same one.
     */
    static void planVoyage(ServerLevel level, VesselRecord r, Port dest, ServerPlayer requester, Consumer<Boolean> done) {
        MinecraftServer srv = level.getServer();
        PortData ports = PortData.get(srv);
        if (dest.anchorage() == null) {
            if (requester != null) fail(requester, Component.translatable("minecraftportsmod.chart.no_anchorage"));
            done.accept(false);
            return;
        }

        // where are we starting from?
        VesselEntity body = live(r.id());
        double sx = body != null ? body.getX() : r.x();
        double sz = body != null ? body.getZ() : r.z();
        DimensionNavCache cache = NavCacheManager.get(level);
        BlockPos start = BlockPos.containing(sx, cache.waterY(), sz);

        Port origin = null;
        Dock originDock = null;
        if (r.state() == VesselRecord.State.MOORED || r.state() == VesselRecord.State.ANCHORED) {
            origin = ports.port(r.portId());
            originDock = r.dockId() >= 0 ? ports.dock(r.dockId()) : null;
        }
        if (origin == null) {
            Port near = ports.nearestPort(level.dimension(), start, AT_PORT_RADIUS + PortService.ANCHORAGE_RADIUS);
            if (near != null && near.anchorage() != null && Math.sqrt(near.anchorage().distSqr(start)) <= AT_PORT_RADIUS) {
                origin = near;
            }
        }
        Route route = origin == null || origin.id() == dest.id() ? null : ports.route(origin.id(), dest.id());
        int[] routePath = route != null && route.isUsable() ? route.waypointsFrom(origin.id()) : null;
        BlockPos originAnchorage = origin == null ? null : origin.anchorage();
        List<BlockPos> originBerths = origin == null ? List.of() : PortService.berthsOf(ports, origin.id(), originDock);

        // destination berth (reserve now)
        Dock destDock = ports.docksOf(dest.id()).stream()
                .filter(d -> d.occupant() == null || d.occupant().equals(r.id()))
                .min(Comparator.comparingDouble(d -> d.berth().distSqr(dest.anchorage()))).orElse(null);
        List<BlockPos> destBerths = PortService.berthsOf(ports, dest.id(), destDock);
        BlockPos destAnchorage = dest.anchorage();
        BlockPos destBerth = destDock == null ? null : destDock.berth();
        if (destDock != null) ports.setOccupant(destDock, r.id());
        final Dock reserved = destDock;
        r.planning = true;

        final BlockPos fStart = start;
        final Port fOrigin = origin;
        planner.execute(() -> {
            int[] path = null;
            try {
                int[] main = null;
                int[] leg1 = null;
                int[] s = new WaterPathfinder(cache).snapToWater(fStart.getX(), fStart.getZ(), 4);
                if (s != null) {
                    BlockPos from = new BlockPos(s[0], fStart.getY(), s[1]);
                    if (routePath != null && originAnchorage != null) {
                        leg1 = BerthPlanner.approachPath(cache, from, originAnchorage, originBerths);
                        main = leg1 == null ? null : routePath;
                    } else {
                        WaterPathfinder.Result res = new WaterPathfinder(cache).findRoute(s[0], s[1], destAnchorage.getX(), destAnchorage.getZ(),
                                () -> server == null);
                        main = res.found() ? res.waypoints() : null;
                    }
                }
                if (main != null) {
                    int[] leg3 = destBerth == null ? null : BerthPlanner.approachPath(cache, destAnchorage, destBerth, destBerths);
                    path = concat(leg1, main, leg3);
                    if (destBerth != null && leg3 == null) path = null;
                }
            } catch (Throwable t) {
                Minecraftportsmod.LOGGER.error("Voyage planning for {} failed", r.name(), t);
            }
            final int[] result = path;
            srv.execute(() -> {
                r.planning = false;
                if (result == null || result.length < 4) {
                    if (reserved != null && r.id().equals(reserved.occupant())) PortData.get(srv).setOccupant(reserved, null);
                    if (requester != null) fail(requester, Component.translatable("minecraftportsmod.voyage.no_way", r.name(), dest.name()));
                    done.accept(false);
                    return;
                }
                // leave the old berth, keep the reserved one
                PortData p = PortData.get(srv);
                p.releaseDocksOf(r.id());
                if (reserved != null) p.setOccupant(reserved, r.id());
                r.sail(new Voyage(result, dest.id(), reserved == null ? -1 : reserved.id()));
                VesselEntity b = live(r.id());
                if (b != null) b.onStateChanged();
                changed();
                Minecraftportsmod.LOGGER.info("Vessel {} sails {} -> {} ({} blocks, {} waypoints)", r.name(),
                        fOrigin == null ? "open sea" : fOrigin.name(), dest.name(), Math.round(r.voyage().totalLength()), result.length / 2);
                done.accept(true);
            });
        });
    }

    /** Joins path pieces, dropping the duplicated joint point between consecutive pieces. */
    static int[] concat(int[]... parts) {
        int[] out = new int[0];
        for (int[] part : parts) {
            if (part == null || part.length == 0) continue;
            int skip = 0;
            if (out.length >= 2 && out[out.length - 2] == part[0] && out[out.length - 1] == part[1]) skip = 2;
            int[] merged = Arrays.copyOf(out, out.length + part.length - skip);
            System.arraycopy(part, skip, merged, out.length, part.length - skip);
            out = merged;
        }
        return out;
    }

    // ------------------------------------------------------------------ admin / testing

    /** Creates an ownerless vessel at a free berth of the port (tests and admins). */
    public static VesselRecord spawnTestVessel(ServerLevel level, Port port) {
        PortData ports = PortData.get(level.getServer());
        Dock dock = ports.docksOf(port.id()).stream().filter(Dock::isFree).findFirst().orElse(null);
        if (dock == null) return null;
        VesselRecord r = new VesselRecord(UUID.randomUUID(), null, "", "Test " + (FleetData.get(level.getServer()).all().size() + 1),
                net.minecraft.world.item.Items.OAK_BOAT, level.dimension());
        ports.setOccupant(dock, r.id());
        r.moor(port.id(), dock.id());
        r.setPose(dock.berth().getX() + 0.5, dock.berth().getZ() + 0.5, 0);
        FleetData.get(level.getServer()).add(r);
        return r;
    }

    /**
     * Sends a vessel along a rough lane (waters nobody has charted yet): it leaves its berth at once and travels
     * virtually to a reserved berth of {@code dest} (or its roadstead if all are taken).
     */
    public static boolean dispatchRough(ServerLevel level, VesselRecord r, Port dest, int[] lane) {
        if (lane == null || lane.length < 2 || r.planning) return false;
        PortData ports = PortData.get(level.getServer());
        VesselEntity body = live(r.id());
        if (body != null) {
            if (body.hasPlayerAboard()) return false;
            dematerialize(body);
        }
        Dock destDock = dest.anchorage() == null ? null : ports.docksOf(dest.id()).stream()
                .filter(d -> d.occupant() == null || d.occupant().equals(r.id()))
                .min(Comparator.comparingDouble(d -> d.berth().distSqr(dest.anchorage()))).orElse(null);
        ports.releaseDocksOf(r.id());
        if (destDock != null) ports.setOccupant(destDock, r.id());
        int[] start = {(int) Math.floor(r.x()), (int) Math.floor(r.z())};
        BlockPos endPos = destDock != null ? destDock.berth() : dest.anchorage() != null ? dest.anchorage() : dest.office();
        int[] path = concat(start, lane, new int[]{endPos.getX(), endPos.getZ()});
        r.sail(new Voyage(path, dest.id(), destDock == null ? -1 : destDock.id(), true));
        changed();
        Minecraftportsmod.LOGGER.info("Vessel {} sails a rough lane to {} ({} blocks)", r.name(), dest.name(), Math.round(r.voyage().totalLength()));
        return true;
    }

    /** Builds a trade vessel for a settlement at a free berth of its port (null if every berth is taken). */
    public static VesselRecord spawnSettlementVessel(ServerLevel level, Port port, int settlementId, VesselType type,
                                                     java.util.Collection<String> takenNames) {
        PortData ports = PortData.get(level.getServer());
        Dock dock = ports.docksOf(port.id()).stream().filter(Dock::isFree)
                .min(Comparator.comparingDouble(d -> d.berth().distSqr(port.office()))).orElse(null);
        if (dock == null) return null;
        String name = Names.vesselName(Names.cyrillic(port.name()), takenNames);
        VesselRecord r = new VesselRecord(UUID.randomUUID(), null, port.name(), name,
                net.minecraft.world.item.Items.SPRUCE_BOAT, level.dimension());
        r.setSettlement(settlementId);
        r.setType(type);
        ports.setOccupant(dock, r.id());
        r.moor(port.id(), dock.id());
        r.setPose(dock.berth().getX() + 0.5, dock.berth().getZ() + 0.5, 0);
        FleetData.get(level.getServer()).add(r);
        return r;
    }

    /**
     * Puts someone aboard a vessel: seated if the vessel is in the world, otherwise stowed with it (the entity
     * leaves the world and comes back aboard when the vessel takes shape again).
     */
    public static void board(ServerLevel level, VesselRecord r, Entity passenger) {
        VesselEntity body = live(r.id());
        if (body != null && body.level() == passenger.level()) {
            passenger.snapTo(body.getX(), body.getY() + 0.5, body.getZ(), body.getYRot(), 0);
            passenger.startRiding(body, true, true);
            return;
        }
        var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
        if (passenger.saveAsPassenger(out)) r.stowed().add(out.buildResult());
        passenger.stopRiding();
        passenger.discard();
        changed();
    }

    /** Removes a vessel for good, wherever it is (its passengers step off, its cargo is lost). */
    public static void scrap(MinecraftServer srv, UUID id) {
        VesselRecord r = record(srv, id);
        if (r == null) return;
        VesselEntity body = live(id);
        if (body != null) {
            LIVE.remove(id);
            body.releaseBody();
        }
        PortData.get(srv).releaseDocksOf(id);
        FleetData.get(srv).remove(id);
    }

    public static void dispatch(ServerLevel level, VesselRecord r, Port dest, Consumer<Boolean> done) {
        planVoyage(level, r, dest, null, done);
    }

    public static boolean isOwner(VesselRecord r, Player p) {
        return r.isOwnedBy(p.getUUID());
    }
}
