package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;
import org.webtrade.minecraftportsmod.chart.ChartService;
import org.webtrade.minecraftportsmod.client.chart.ChartScreen;
import org.webtrade.minecraftportsmod.port.Port;
import org.webtrade.minecraftportsmod.port.PortData;
import org.webtrade.minecraftportsmod.port.PortService;
import org.webtrade.minecraftportsmod.port.Route;
import org.webtrade.minecraftportsmod.fleet.FleetManager;

/**
 * Builds a small archipelago at sea level, founds three ports, waits for the routes, then opens the
 * nautical chart and takes screenshots (build/run/clientGameTest/screenshots).
 */
public class ChartClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1920, 1080);
            var server = sp.getServer();
            sp.getConnection().waitForChunksRender();

            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 0 -40 0");
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            server.runCommand("forceload add -128 -128 127 127");

            // the test world is superflat: build relative to its real sea level
            int w = server.computeOnServer(s -> s.overworld().getSeaLevel()) - 1; // water surface y
            int top = w + 1;                                                     // y of blocks standing on land

            // flatten a sea one block deep, air above (fill is limited to 32768 blocks)
            for (int x = -128; x < 128; x += 64) {
                for (int z = -128; z < 128; z += 64) {
                    String xz = x + " %d " + z + " " + (x + 63) + " %d " + (z + 63);
                    server.runCommand("fill " + xz.formatted(w, w) + " water");
                    server.runCommand("fill " + xz.formatted(w + 1, w + 6) + " air");
                }
            }
            // a long cape between the ports, a wooded island in the middle, three small harbours
            server.runCommand(fill(-90, w, -4, 70, w, 4, "grass_block"));
            server.runCommand(fill(-30, w, 30, -5, w, 50, "sand"));
            server.runCommand(fill(-20, w + 1, 38, -16, w + 5, 42, "oak_leaves"));
            server.runCommand(fill(-8, w, -80, 8, w, -60, "grass_block"));
            server.runCommand(fill(-8, w, 60, 8, w, 80, "grass_block"));
            server.runCommand(fill(100, w, -30, 127, w, -10, "grass_block"));

            server.runCommand(set(0, top, -70, "minecraftportsmod:port_office"));
            server.runCommand(set(0, top, 70, "minecraftportsmod:port_office"));
            server.runCommand(set(110, top, -20, "minecraftportsmod:port_office"));

            context.waitTicks(100); // let the nav scanner pick up all edits

            server.runCommand("execute positioned 0 " + top + " -70 run ports register 12");
            server.runCommand("execute positioned 0 " + top + " 70 run ports register 12");
            server.runCommand("execute positioned 110 " + top + " -20 run ports register 12");
            server.runOnServer(s -> {
                PortData data = PortData.get(s);
                String[] names = {"Северная гавань", "Южный причал", "Восточный форт"};
                int i = 0;
                for (Port p : data.ports()) {
                    if (i < names.length) data.renamePort(p, names[i++]);
                }
            });
            server.waitFor(s -> PortData.get(s).routes().size() >= 3
                    && PortData.get(s).routes().stream().allMatch(r -> r.status() != Route.Status.PENDING), 20 * 30);

            // a second berth at the first port, then two vessels there
            server.runCommand("gamemode creative @a");
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                Port home = PortData.get(s).port(1);
                PortService.addBerth(s.overworld(), home);
                FleetManager.build(player, home);
                FleetManager.build(player, home);
                // shipyard: first vessel becomes a sloop, second a brig (creative: no diamonds needed)
                var fleet = FleetManager.vesselsOf(s, player.getUUID());
                FleetManager.upgrade(player, fleet.get(0).id(), home);
                FleetManager.upgrade(player, fleet.get(1).id(), home);
                FleetManager.upgrade(player, fleet.get(1).id(), home);
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[test] types: {} {} names: {} / {}",
                        fleet.get(0).type(), fleet.get(1).type(), fleet.get(0).name(), fleet.get(1).name());
            });
            server.runCommand("tp @p 14 " + (top + 7) + " -52 140 30");
            sp.getConnection().waitForChunksRender();
            context.waitTicks(60);
            context.takeScreenshot("ports_world");
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                var sloop = FleetManager.live(FleetManager.vesselsOf(s, player.getUUID()).get(0).id());
                if (sloop != null) lookAt(player, sloop.getX(), sloop.getY() + 0.5, sloop.getZ(), 4, 1.2, 4.5);
            });
            context.waitTicks(30);
            context.takeScreenshot("sloop_moored");
            // close-ups from three sides, relative to the hull's heading
            context.runOnClient(mc -> { if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle(); });
            for (int view = 0; view < 3; view++) {
                final int v = view;
                server.runOnServer(s -> {
                    ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                    player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                    var sloop = FleetManager.live(FleetManager.vesselsOf(s, player.getUUID()).getFirst().id());
                    double yaw = Math.toRadians(sloop.getYRot());
                    double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // bow direction
                    double[][] offs = {{-fz * 6, 1.5, fx * 6}, {fx * 6 - fz * 3, 2.5, fz * 6 + fx * 3}, {-fx * 5 + fz * 4, 3.5, -fz * 5 - fx * 4}};
                    lookAt(player, sloop.getX(), sloop.getY() + 2.2, sloop.getZ(), offs[v][0], offs[v][1], offs[v][2]);
                });
                context.waitTicks(15);
                context.takeScreenshot("sloop_view" + view);
            }
            // the brig close up, too
            for (int view = 0; view < 2; view++) {
                final int v = view;
                server.runOnServer(s -> {
                    ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                    var brig = FleetManager.live(FleetManager.vesselsOf(s, player.getUUID()).get(1).id());
                    double yaw = Math.toRadians(brig.getYRot());
                    double fx = -Math.sin(yaw), fz = Math.cos(yaw);
                    double[][] offs = {{-fz * 9, 2.5, fx * 9}, {fx * 8 + fz * 5, 3.5, fz * 8 - fx * 5}};
                    lookAt(player, brig.getX(), brig.getY() + 3, brig.getZ(), offs[v][0], offs[v][1], offs[v][2]);
                });
                context.waitTicks(15);
                context.takeScreenshot("brig_view" + view);
            }
            context.runOnClient(mc -> { if (mc.gui.hud.isHidden()) mc.gui.hud.toggle(); });
            server.runCommand("gamemode creative @a");
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                var brig = FleetManager.live(FleetManager.vesselsOf(s, player.getUUID()).get(1).id());
                if (brig != null) lookAt(player, brig.getX(), brig.getY() + 0.6, brig.getZ(), -5, 1.6, -5.5);
            });
            context.waitTicks(30);
            context.takeScreenshot("brig_moored");

            // port office: ports, fleet and berths tabs
            server.runOnServer(s -> ChartService.openOffice(s.getPlayerList().getPlayers().getFirst(), PortData.get(s).port(1)));
            context.waitForScreen(ChartScreen.class);
            context.waitTicks(80);
            context.takeScreenshot("office_ports");
            context.runOnClient(mc -> ((ChartScreen) mc.gui.screen()).showTab("fleet"));
            context.waitTicks(10);
            context.takeScreenshot("office_fleet");
            context.runOnClient(mc -> mc.gui.setScreen(new org.webtrade.minecraftportsmod.client.chart.RenameScreen(mc.gui.screen(),
                    net.minecraft.network.chat.Component.literal("Rename vessel"), "Golden Comet", n -> {})));
            context.waitTicks(10);
            context.takeScreenshot("rename");
            context.runOnClient(mc -> mc.gui.screen().onClose());
            context.waitTicks(5);
            context.runOnClient(mc -> {
                ChartScreen chart = (ChartScreen) mc.gui.screen();
                chart.showTab("berths");
                chart.toggleBerthHighlight();
            });
            context.waitTicks(10);
            context.takeScreenshot("office_berths");
            context.runOnClient(mc -> ((ChartScreen) mc.gui.screen()).showTab("shipyard"));
            context.waitTicks(10);
            context.takeScreenshot("office_shipyard");

            // send the second vessel to the east fort; its course shows on the chart
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                var fleet = FleetManager.vesselsOf(s, player.getUUID());
                FleetManager.send(player, fleet.get(1).id(), 3, false);
            });
            context.waitTicks(100);
            context.setScreen(() -> null);
            // look at the brig under sail from a little way off
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                var brig = FleetManager.live(FleetManager.vesselsOf(s, player.getUUID()).get(1).id());
                if (brig != null) {
                    // stand ahead of it and a little to the side, aim where it will be in a moment
                    double fx = -Math.sin(Math.toRadians(brig.getYRot())), fz = Math.cos(Math.toRadians(brig.getYRot()));
                    double tx = brig.getX() + fx * 4, tz = brig.getZ() + fz * 4;
                    lookAt(player, tx, brig.getY() + 3, tz, fx * 13 - fz * 8, 3, fz * 13 + fx * 8);
                }
            });
            context.waitTicks(6);
            context.takeScreenshot("brig_sailing");
            server.runOnServer(s -> ChartService.openOffice(s.getPlayerList().getPlayers().getFirst(), PortData.get(s).port(1)));
            context.waitForScreen(ChartScreen.class);
            context.waitTicks(40);
            context.runOnClient(mc -> ((ChartScreen) mc.gui.screen()).showTab("fleet"));
            context.waitTicks(50);
            context.takeScreenshot("office_fleet_sailing");
            context.setScreen(() -> null);

            // --- walkable deck: stand on the moored sloop, then a sheep boards by itself
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                var sloop = FleetManager.live(FleetManager.vesselsOf(s, player.getUUID()).getFirst().id());
                player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                player.teleportTo(s.overworld(), sloop.getX(), sloop.getY() + 1.5, sloop.getZ() - 1.3, java.util.Set.of(), sloop.getYRot(), 20, false);
            });
            context.waitTicks(40);
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                var sloop = FleetManager.live(FleetManager.vesselsOf(s, player.getUUID()).getFirst().id());
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[test] deck: player y={} ship y={} (deck top expected {}), onGround={}",
                        player.getY(), sloop.getY(), sloop.getY() + 0.6125, player.onGround());
                player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                var record = FleetManager.vesselsOf(s, player.getUUID()).getFirst();
                record.cargo().set(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT, 32));
                record.cargo().set(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 16));
            });
            String sheepAt = server.computeOnServer(s -> {
                var sloop = FleetManager.live(FleetManager.vesselsOf(s, s.getPlayerList().getPlayers().getFirst().getUUID()).getFirst().id());
                return String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f", sloop.getX(), sloop.getY() + 1.2, sloop.getZ());
            });
            server.runCommand("summon minecraft:sheep " + sheepAt);
            context.waitTicks(40);
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                var sloop = FleetManager.live(FleetManager.vesselsOf(s, player.getUUID()).getFirst().id());
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[test] sheep aboard: passengers={}", sloop.getPassengers());
                player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                lookAt(player, sloop.getX(), sloop.getY() + 1, sloop.getZ(), 4, 2.5, 4);
            });
            context.waitTicks(20);
            context.takeScreenshot("sloop_with_sheep");
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                var sloop = FleetManager.live(FleetManager.vesselsOf(s, player.getUUID()).getFirst().id());
                player.teleportTo(s.overworld(), sloop.getX(), sloop.getY() + 0.7, sloop.getZ() - 1.3, java.util.Set.of(), sloop.getYRot(), 20, false);
                player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                FleetManager.openHold(player, FleetManager.vesselsOf(s, player.getUUID()).getFirst());
            });
            context.waitTicks(20);
            context.takeScreenshot("hold");
            context.setScreen(() -> null);
            server.runCommand("gamemode spectator @a");

            // --- berths: move one by hand (it gets locked)
            server.runOnServer(s -> {
                Port home = PortData.get(s).port(1);
                var docks = PortData.get(s).docksOf(home.id());
                var target = docks.getLast();
                String why = PortService.moveBerthManually(s.overworld(), home, target.id(), home.office().getX() - 12, home.office().getZ() + 12);
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[test] manual berth move: {} -> {} locked={} ({})", target.id(), target.berth(), target.isLocked(), why);
            });

            // remove the berth Vessel 1 is moored at: it must move to the berth Vessel 2 just left
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                var first = FleetManager.vesselsOf(s, player.getUUID()).getFirst();
                int before = first.dockId();
                PortService.removeBerth(s.overworld(), PortData.get(s).port(1), before);
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[test] removed berth {} -> vessel state={} dock={} voyageTo={}",
                        before, first.state(), first.dockId(), first.voyage() == null ? -1 : first.voyage().destDockId());
            });
            context.waitTicks(120);
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                var fleet = FleetManager.vesselsOf(s, player.getUUID());
                var first = fleet.getFirst();
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[test] after move: state={} dock={} pos={},{}",
                        first.state(), first.dockId(), first.x(), first.z());
                FleetManager.dismantle(player, fleet.get(1).id());
                int boats = 0;
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    if (player.getInventory().getItem(i).is(net.minecraft.world.item.Items.OAK_BOAT)) boats++;
                }
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[test] dismantled: fleet={} boatsInInventory={} bodyGone={}",
                        FleetManager.vesselsOf(s, player.getUUID()).size(), boats, FleetManager.live(fleet.get(1).id()) == null);
            });

            // board the first vessel and open the chart with the key binding's server path
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                var first = FleetManager.vesselsOf(s, player.getUUID()).getFirst();
                var body = FleetManager.live(first.id());
                player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                if (body != null) player.teleportTo(s.overworld(), body.getX(), body.getY() + 0.7, body.getZ(), java.util.Set.of(), body.getYRot(), 0, false);
                boolean ok = body != null && player.startRiding(body, true, true);
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[test] boarding body={} ok={} vehicle={} dist={}",
                        body, ok, player.getVehicle(), body == null ? -1 : player.distanceTo(body));
                ChartService.openFromKey(player);
            });
            context.waitForScreen(ChartScreen.class);
            context.waitTicks(40);
            context.takeScreenshot("boat_chart_at_port");
            context.runOnClient(mc -> ((ChartScreen) mc.gui.screen()).selectPort(2));
            context.waitTicks(30);
            context.takeScreenshot("boat_chart");
            context.setScreen(() -> null);
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[test] before sail: vehicle={} ridden={}",
                        player.getVehicle(), FleetManager.riddenBy(player) != null);
                FleetManager.send(player, null, 2, true);
            });
            context.waitTicks(160);
            context.takeScreenshot("boat_sailing");
            // it must actually get there (no circling): wait for arrival
            int waited = server.waitFor(s -> {
                var list = FleetManager.vesselsOf(s, s.getPlayerList().getPlayers().getFirst().getUUID());
                return !list.isEmpty() && list.getFirst().state() != org.webtrade.minecraftportsmod.fleet.VesselRecord.State.SAILING;
            }, 20 * 90);
            server.runOnServer(s -> {
                var v = FleetManager.vesselsOf(s, s.getPlayerList().getPlayers().getFirst().getUUID()).getFirst();
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[test] voyage with sheep ended after {} ticks: state={} port={}", waited, v.state(), v.portId());
            });
            // step off and fly far away so the sloop has no body while it sails, then send it to the east fort
            server.runCommand("forceload remove all");
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                player.stopRiding();
                player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                player.teleportTo(s.overworld(), 2000, 0, 2000, java.util.Set.of(), 0, 0, false);
                var v = FleetManager.vesselsOf(s, player.getUUID()).getFirst();
                FleetManager.send(player, v.id(), 3, false);
            });
            context.waitTicks(200);
            server.runOnServer(s -> {
                var v = FleetManager.vesselsOf(s, s.getPlayerList().getPlayers().getFirst().getUUID()).getFirst();
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[test] away: state={} body={} stowed={}",
                        v.state(), FleetManager.live(v.id()) != null, v.stowed().size());
            });
            server.waitFor(s -> FleetManager.vesselsOf(s, s.getPlayerList().getPlayers().getFirst().getUUID()).getFirst().state()
                    == org.webtrade.minecraftportsmod.fleet.VesselRecord.State.MOORED, 20 * 60);
            // come back: the body reappears with the sheep seated again
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                var v = FleetManager.vesselsOf(s, player.getUUID()).getFirst();
                player.teleportTo(s.overworld(), v.x() + 3, -60, v.z() + 3, java.util.Set.of(), 0, 30, false);
            });
            context.waitTicks(100);
            server.runOnServer(s -> {
                var v = FleetManager.vesselsOf(s, s.getPlayerList().getPlayers().getFirst().getUUID()).getFirst();
                var body = FleetManager.live(v.id());
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info("[test] back: state={} port={} body={} passengers={} stowed={}",
                        v.state(), v.portId(), body != null, body == null ? "-" : body.getPassengers(), v.stowed().size());
            });

            context.setScreen(() -> null);
        }
    }

    /** Puts the (spectating/flying) player at target+offset, looking at target. */
    private static void lookAt(ServerPlayer player, double tx, double ty, double tz, double ox, double oy, double oz) {
        double x = tx + ox, y = ty + oy, z = tz + oz;
        double dx = tx - x, dy = ty - y, dz = tz - z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        player.teleportTo((net.minecraft.server.level.ServerLevel) player.level(), x, y, z, java.util.Set.of(), yaw, pitch, false);
    }

    private static String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        return "fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + block;
    }

    private static String set(int x, int y, int z, String block) {
        return "setblock " + x + " " + y + " " + z + " " + block;
    }
}
