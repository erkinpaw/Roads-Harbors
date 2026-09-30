package org.webtrade.minecraftportsmod.chart;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.webtrade.minecraftportsmod.colony.Caravans;
import org.webtrade.minecraftportsmod.colony.Dweller;
import org.webtrade.minecraftportsmod.colony.Trails;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.fleet.FleetManager;
import org.webtrade.minecraftportsmod.fleet.VesselRecord;
import org.webtrade.minecraftportsmod.network.WorldMapPayloads;
import org.webtrade.minecraftportsmod.port.Port;
import org.webtrade.minecraftportsmod.port.PortData;
import org.webtrade.minecraftportsmod.port.Route;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The world map for a player: what is on it (the land itself comes as the chart's tiles). */
public final class WorldMapService {

    private WorldMapService() {
    }

    public static void send(ServerPlayer player, boolean refresh) {
        ServerLevel level = (ServerLevel) player.level();
        MinecraftServer server = level.getServer();
        boolean overworld = level.dimension() == net.minecraft.world.level.Level.OVERWORLD;

        List<WorldMapPayloads.PortPin> ports = new ArrayList<>();
        List<WorldMapPayloads.Line> lines = new ArrayList<>();
        PortData ports0 = PortData.get(server);
        for (Port p : ports0.ports(level.dimension())) ports.add(new WorldMapPayloads.PortPin(p.id(), p.name(), p.office().getX(), p.office().getZ()));
        for (Route r : ports0.routes()) {
            Port a = ports0.port(r.portA());
            if (a == null || !a.dimension().equals(level.dimension()) || !r.isUsable()) continue;
            lines.add(new WorldMapPayloads.Line(WorldMapPayloads.Line.SEA, r.waypoints(), new byte[0]));
        }

        List<WorldMapPayloads.VillagePin> villages = new ArrayList<>();
        List<WorldMapPayloads.Mover> movers = new ArrayList<>();
        if (overworld) {
            VillageData data = VillageData.get(server);
            for (Village v : data.all()) {
                villages.add(new WorldMapPayloads.VillagePin(v.id, v.name, v.center.getX(), v.center.getZ(), v.level().ordinal(), v.population()));
            }
            for (Trails.Trail t : data.trails()) {
                if (!t.ready()) continue;
                byte[] made = new byte[t.segments()];
                for (int s = 0; s < made.length; s++) made[s] = (byte) (t.built(s) ? 1 : 0);
                lines.add(new WorldMapPayloads.Line(WorldMapPayloads.Line.TRAIL, t.points(), made));
            }
            for (Caravans.Trip t : data.trips()) {
                int[] at = Caravans.where(data, t);
                Village home = data.get(t.from);
                if (at == null || home == null) continue;
                Dweller m = home.dweller(t.merchant);
                movers.add(new WorldMapPayloads.Mover(WorldMapPayloads.Mover.MERCHANT, m == null ? "?" : m.name, at[0], at[1], home.name));
            }
        }
        for (VesselRecord v : FleetManager.vesselsOf(server, player.getUUID())) {
            if (!v.dimension().equals(level.dimension())) continue;
            movers.add(new WorldMapPayloads.Mover(WorldMapPayloads.Mover.VESSEL, v.name(), v.x(), v.z(), ""));
        }
        // the players' marks and screenshots of this world
        MapMarks marks = MapMarks.get(server);
        String dim = level.dimension().identifier().toString();
        List<WorldMapPayloads.MarkPin> markPins = new ArrayList<>();
        for (MapMarks.Mark m : marks.marks()) {
            if (!m.dim.equals(dim)) continue;
            markPins.add(new WorldMapPayloads.MarkPin(m.id, m.name, m.icon, m.x, m.z, m.time, m.ownerName, mayChange(player, m.owner),
                    m.shots.stream().mapToInt(Integer::intValue).toArray()));
        }
        List<WorldMapPayloads.ShotPin> shotPins = new ArrayList<>();
        for (MapMarks.Shot sh : marks.shots()) {
            if (sh.dim().equals(dim)) shotPins.add(new WorldMapPayloads.ShotPin(sh.id(), sh.x(), sh.z(), sh.time(), sh.ownerName(), sh.mark()));
        }
        ServerPlayNetworking.send(player, new WorldMapPayloads.View(refresh, ports, lines, villages, movers, markPins, shotPins));
    }

    /** May the player change or take away what another made: their own, or anything for an operator. */
    static boolean mayChange(ServerPlayer player, UUID owner) {
        return player.getUUID().equals(owner) || net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(player.permissions());
    }

    // ------------------------------------------------------------------ marks

    public static void handle(ServerPlayer player, WorldMapPayloads.MarkAction a) {
        MinecraftServer server = player.level().getServer();
        MapMarks marks = MapMarks.get(server);
        String name = a.name().strip();
        if (name.length() > 40) name = name.substring(0, 40);
        switch (a.action()) {
            case WorldMapPayloads.MarkAction.CREATE -> marks.addMark(player.getUUID(), player.getGameProfile().name(),
                    player.level().dimension().identifier().toString(), a.x(), a.z(), a.icon(), name);
            case WorldMapPayloads.MarkAction.EDIT -> {
                MapMarks.Mark m = marks.mark(a.id());
                if (m == null || !mayChange(player, m.owner)) return;
                m.name = name;
                if (MapMarks.ICONS.contains(a.icon())) m.icon = a.icon();
                marks.changed();
            }
            case WorldMapPayloads.MarkAction.DELETE -> {
                MapMarks.Mark m = marks.mark(a.id());
                if (m == null || !mayChange(player, m.owner)) return;
                for (int shot : List.copyOf(m.shots)) dropShot(server, marks, shot);
                marks.removeMark(m.id);
            }
            case WorldMapPayloads.MarkAction.DELETE_SHOT -> {
                MapMarks.Shot sh = marks.shot(a.id());
                if (sh == null || !mayChange(player, sh.owner())) return;
                dropShot(server, marks, sh.id());
            }
            default -> {
                return;
            }
        }
        send(player, true);
    }

    private static void dropShot(MinecraftServer server, MapMarks marks, int id) {
        marks.removeShot(id);
        try {
            Files.deleteIfExists(MapMarks.shotFile(server, id));
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ screenshots

    /** A screenshot on its way in: the parts come one by one. */
    private record Upload(int mark, byte[][] parts) {
    }

    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
    /** No more than this much of a picture. */
    private static final int MAX_SHOT = 900 * 1024;
    /** A shot taken with the key goes to the player's own mark this near, else to a new mark. */
    private static final int NEAR_MARK = 24;

    public static void part(ServerPlayer player, WorldMapPayloads.ShotPart p) {
        if (p.parts() <= 0 || p.parts() > MAX_SHOT / 1000 || p.part() < 0 || p.part() >= p.parts()) return;
        Upload u = UPLOADS.get(player.getUUID());
        if (u == null || p.part() == 0 || u.parts.length != p.parts()) {
            u = new Upload(p.mark(), new byte[p.parts()][]);
            UPLOADS.put(player.getUUID(), u);
        }
        u.parts[p.part()] = p.data();
        for (byte[] b : u.parts) if (b == null) return;
        UPLOADS.remove(player.getUUID());
        int size = 0;
        for (byte[] b : u.parts) size += b.length;
        if (size > MAX_SHOT) return;
        byte[] jpeg = new byte[size];
        int at = 0;
        for (byte[] b : u.parts) {
            System.arraycopy(b, 0, jpeg, at, b.length);
            at += b.length;
        }
        // (a JPEG, nothing else)
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return;
        MinecraftServer server = player.level().getServer();
        MapMarks marks = MapMarks.get(server);
        if (marks.shotsOf(player.getUUID()) >= MapMarks.SHOTS_PER_PLAYER) {
            player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("minecraftportsmod.worldmap.too_many", MapMarks.SHOTS_PER_PLAYER));
            return;
        }
        String dim = player.level().dimension().identifier().toString();
        MapMarks.Mark mark = marks.mark(u.mark());
        if (mark == null) {
            // taken with the key: the nearest mark of the player's here, or a new one
            int px = player.getBlockX(), pz = player.getBlockZ();
            for (MapMarks.Mark m : marks.marks()) {
                if (!m.dim.equals(dim) || !m.owner.equals(player.getUUID())) continue;
                if (Math.abs(m.x - px) <= NEAR_MARK && Math.abs(m.z - pz) <= NEAR_MARK) mark = m;
            }
            if (mark == null) mark = marks.addMark(player.getUUID(), player.getGameProfile().name(), dim, px, pz, "camera", "");
        }
        MapMarks.Shot shot = marks.addShot(player.getUUID(), player.getGameProfile().name(), dim, mark.x, mark.z, mark.id);
        try {
            Path file = MapMarks.shotFile(server, shot.id());
            Files.createDirectories(file.getParent());
            Files.write(file, jpeg);
        } catch (IOException e) {
            org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.warn("screenshot {} not saved: {}", shot.id(), e.toString());
            marks.removeShot(shot.id());
            return;
        }
        player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("minecraftportsmod.worldmap.shot_saved"));
        send(player, true);
    }

    public static void sendShot(ServerPlayer player, int id) {
        MinecraftServer server = player.level().getServer();
        byte[] data = new byte[0];
        if (MapMarks.get(server).shot(id) != null) {
            try {
                data = Files.readAllBytes(MapMarks.shotFile(server, id));
            } catch (IOException ignored) {
            }
        }
        if (data.length > WorldMapPayloads.ShotData.MAX) data = new byte[0];
        ServerPlayNetworking.send(player, new WorldMapPayloads.ShotData(id, data));
    }
}
