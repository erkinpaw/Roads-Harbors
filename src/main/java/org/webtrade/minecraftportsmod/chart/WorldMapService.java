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

import java.util.ArrayList;
import java.util.List;

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
        ServerPlayNetworking.send(player, new WorldMapPayloads.View(refresh, ports, lines, villages, movers));
    }
}
