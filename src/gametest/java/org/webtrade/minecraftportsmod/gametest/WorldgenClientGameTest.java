package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.chart.ChartService;
import org.webtrade.minecraftportsmod.client.chart.ChartScreen;
import org.webtrade.minecraftportsmod.client.chart.TownHallScreen;
import org.webtrade.minecraftportsmod.economy.SettlementData;
import org.webtrade.minecraftportsmod.fleet.FleetManager;
import org.webtrade.minecraftportsmod.port.Port;
import org.webtrade.minecraftportsmod.port.PortData;
import org.webtrade.minecraftportsmod.worldgen.WorldPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * A normal (not superflat) world: waits until the world plan has built a few villages, then photographs them from
 * the air and from the ground, and opens the chart and a town hall.
 */
public class WorldgenClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(ui -> ui.setSeed("12345"))
                .create()) {
            var server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("time set noon");
            server.runCommand("gamerule advance_time false");
            server.runCommand("weather clear");

            server.runCommand("settlement daylength 600");
            server.waitFor(s -> WorldPlan.get(s).sites().stream().filter(x -> x.state() == WorldPlan.State.BUILT).count() >= 5, 20 * 600);
            server.waitFor(s -> WorldPlan.get(s).lanes().size() >= 3, 20 * 300);
            context.waitTicks(20 * 30);
            server.runOnServer(s -> {
                var data = SettlementData.get(s);
                Minecraftportsmod.LOGGER.info("[test] lanes={} runs={} day={}", WorldPlan.get(s).lanes().size(), data.runs().size(), data.day());
                for (var st : data.all()) {
                    Minecraftportsmod.LOGGER.info("[test] {} knows {} markets, vessels {}, log: {}", st.portId(), st.knowledgeCount(),
                            st.vessels().size(), st.log().isEmpty() ? "-" : st.log().getFirst().text().getString());
                }
            });
            List<int[]> offices = new ArrayList<>();
            server.runOnServer(s -> {
                WorldPlan plan = WorldPlan.get(s);
                for (var site : plan.sites()) {
                    Port p = PortData.get(s).port(site.portId());
                    var st = SettlementData.get(s).get(site.portId());
                    Minecraftportsmod.LOGGER.info("[test] site {} {} {},{} port={} spec={} houses={}", site.id, site.state().getSerializedName(),
                            site.x, site.z, site.portId(), st == null ? "-" : st.spec().id(), st == null ? 0 : st.houses());
                    if (p != null && offices.size() < 3) offices.add(new int[]{p.office().getX(), p.office().getY(), p.office().getZ(), p.id()});
                }
            });
            // life in the first village: work by day, beds at night, the skipper aboard
            if (!offices.isEmpty()) {
                final int[] o0 = offices.getFirst();
                server.runOnServer(s -> look(s.getPlayerList().getPlayers().getFirst(), o0[0], o0[1], o0[2], 14, 16, 14));
                context.waitTicks(20 * 40);
                server.runOnServer(s -> {
                    var list = s.overworld().getEntitiesOfClass(org.webtrade.minecraftportsmod.village.ResidentEntity.class,
                            new net.minecraft.world.phys.AABB(o0[0] - 80, o0[1] - 30, o0[2] - 80, o0[0] + 80, o0[1] + 40, o0[2] + 80));
                    for (var r : list) Minecraftportsmod.LOGGER.info("[test] day: {}", r.describe());
                    for (var v : org.webtrade.minecraftportsmod.fleet.FleetData.get(s).all()) {
                        if (v.settlement() < 0) continue;
                        var crew = v.crew() == null ? null : s.overworld().getEntity(v.crew());
                        Minecraftportsmod.LOGGER.info("[test] vessel {} state={} port={} crew={} stowed={} body={}", v.name(), v.state(),
                                v.portId(), crew == null ? "-" : ((org.webtrade.minecraftportsmod.village.ResidentEntity) crew).describe(),
                                v.stowed().size(), FleetManager.live(v.id()) != null);
                    }
                });
                context.takeScreenshot("life_day");
                server.runOnServer(s -> look(s.getPlayerList().getPlayers().getFirst(), o0[0], o0[1], o0[2], -6, 5, -8));
                context.waitTicks(40);
                context.takeScreenshot("life_day_close");
                // the price board and the stockyard of the first village
                int[][] spots = new int[2][];
                server.runOnServer(s -> {
                    var st = SettlementData.get(s).get(o0[3]);
                    var l = st == null ? null : st.layout();
                    if (l == null) return;
                    if (!l.board().isEmpty()) {
                        BlockPos b = l.board().get(2);
                        spots[0] = new int[]{b.getX(), b.getY(), b.getZ()};
                    }
                    l.stockyard().ifPresent(y -> spots[1] = new int[]{y.getX(), y.getY(), y.getZ()});
                    Minecraftportsmod.LOGGER.info("[test] layout board={} stockyard={} piles={} plots={}", l.board().size(),
                            l.stockyard().orElse(null), l.piles().size(), l.plots().size());
                });
                if (spots[0] != null) {
                    server.runOnServer(s -> {
                        // the board signs face the square: stand in front of them
                        BlockPos b = new BlockPos(spots[0][0], spots[0][1], spots[0][2]);
                        var state = s.overworld().getBlockState(b);
                        net.minecraft.core.Direction f = state.hasProperty(net.minecraft.world.level.block.WallSignBlock.FACING)
                                ? state.getValue(net.minecraft.world.level.block.WallSignBlock.FACING) : net.minecraft.core.Direction.SOUTH;
                        look(s.getPlayerList().getPlayers().getFirst(), b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5,
                                f.getStepX() * 3.5, 0.3, f.getStepZ() * 3.5);
                    });
                    context.waitTicks(40);
                    context.takeScreenshot("life_board");
                }
                if (spots[1] != null) {
                    server.runOnServer(s -> look(s.getPlayerList().getPlayers().getFirst(), spots[1][0], spots[1][1], spots[1][2], 6, 7, 6));
                    context.waitTicks(40);
                    context.takeScreenshot("life_stockyard");
                }
                // lunch on the square
                server.runCommand("time set 6000");
                server.runOnServer(s -> look(s.getPlayerList().getPlayers().getFirst(), o0[0], o0[1], o0[2], 10, 9, 10));
                context.waitTicks(20 * 15);
                context.takeScreenshot("life_lunch");
                server.runCommand("settlement event " + o0[3] + " fair");
                server.runCommand("settlement event " + o0[3] + " poor_harvest");
                server.runCommand("time set 14000");
                context.waitTicks(20 * 25);
                server.runOnServer(s -> {
                    var list = s.overworld().getEntitiesOfClass(org.webtrade.minecraftportsmod.village.ResidentEntity.class,
                            new net.minecraft.world.phys.AABB(o0[0] - 80, o0[1] - 30, o0[2] - 80, o0[0] + 80, o0[1] + 40, o0[2] + 80));
                    for (var r : list) Minecraftportsmod.LOGGER.info("[test] night: {}", r.describe());
                });
                server.runCommand("time set noon");
            }
            // watch a settlement's vessel in port: the skipper goes ashore to trade, then back aboard
            java.util.UUID[] watched = new java.util.UUID[1];
            for (int tries = 0; tries < 20 && watched[0] == null; tries++) {
                server.runOnServer(s -> {
                    long now = org.webtrade.minecraftportsmod.economy.EconomyManager.now(s);
                    for (var run : org.webtrade.minecraftportsmod.economy.SettlementData.get(s).runs()) {
                        var v = org.webtrade.minecraftportsmod.fleet.FleetData.get(s).get(run.vessel());
                        boolean inPort = run.phase() == org.webtrade.minecraftportsmod.economy.TradeRun.Phase.LOADING
                                || run.phase() == org.webtrade.minecraftportsmod.economy.TradeRun.Phase.TRADING;
                        if (v != null && inPort && v.crew() != null && run.departAt() - now > 450
                                && v.state() == org.webtrade.minecraftportsmod.fleet.VesselRecord.State.MOORED) {
                            watched[0] = v.id();
                            look(s.getPlayerList().getPlayers().getFirst(), v.x(), s.overworld().getSeaLevel(), v.z(), 7, 6, 7);
                            break;
                        }
                    }
                });
                if (watched[0] == null) context.waitTicks(40);
            }
            Minecraftportsmod.LOGGER.info("[test] watching {}", watched[0]);
            if (watched[0] != null) {
                for (int i = 0; i < 6; i++) {
                    context.waitTicks(100);
                    final int step = i;
                    server.runOnServer(s -> {
                        var v = org.webtrade.minecraftportsmod.fleet.FleetData.get(s).get(watched[0]);
                        var crew = v.crew() == null ? null : s.overworld().getEntity(v.crew());
                        var run = org.webtrade.minecraftportsmod.economy.SettlementData.get(s).run(v.id());
                        Minecraftportsmod.LOGGER.info("[test] watch {}: vessel {} {} body={} cargo={} run={} crew={}", step, v.name(), v.state(),
                                FleetManager.live(v.id()) != null, FleetManager.live(v.id()) != null && FleetManager.live(v.id()).hasCargo(),
                                run == null ? "-" : run.phase() + " departIn=" + (run.departAt() - org.webtrade.minecraftportsmod.economy.EconomyManager.now(s)),
                                crew instanceof org.webtrade.minecraftportsmod.village.ResidentEntity r ? r.describe() : "-");
                    });
                    if (i == 1 || i == 5) context.takeScreenshot("skipper_" + i);
                }
            }
            int n = 0;
            for (int[] o : offices) {
                n++;
                final int[] office = o;
                // from the air, then from the square
                server.runOnServer(s -> look(s.getPlayerList().getPlayers().getFirst(), office[0], office[1], office[2], 18, 26, 18));
                context.waitTicks(40);
                sp.getConnection().waitForChunksRender();
                context.waitTicks(40);
                context.takeScreenshot("village" + n + "_air");
                server.runOnServer(s -> look(s.getPlayerList().getPlayers().getFirst(), office[0], office[1] + 1, office[2], -12, 6, -10));
                context.waitTicks(30);
                sp.getConnection().waitForChunksRender();
                context.waitTicks(20);
                context.takeScreenshot("village" + n + "_ground");
            }
            if (!offices.isEmpty()) {
                int port = offices.getFirst()[3];
                server.runOnServer(s -> ChartService.openOffice(s.getPlayerList().getPlayers().getFirst(), PortData.get(s).port(port)));
                context.waitForScreen(ChartScreen.class);
                context.waitTicks(60);
                context.runOnClient(mc -> {
                    ChartScreen chart = (ChartScreen) mc.gui.screen();
                    chart.zoomOut(4);
                });
                context.waitTicks(40);
                context.takeScreenshot("world_chart");
                context.runOnClient(mc -> ((ChartScreen) mc.gui.screen()).openTownHall(port));
                context.waitForScreen(TownHallScreen.class);
                context.waitTicks(30);
                context.takeScreenshot("world_hall");
                for (String tab : new String[]{"people", "trade", "chronicle"}) {
                    context.runOnClient(mc -> ((TownHallScreen) mc.gui.screen()).showTab(tab));
                    context.waitTicks(10);
                    context.takeScreenshot("world_hall_" + tab);
                }
                context.setScreen(() -> null);
                context.setScreen(() -> new org.webtrade.minecraftportsmod.client.chart.NewsScreen(null));
                context.waitTicks(40);
                context.takeScreenshot("world_news");
                context.setScreen(() -> null);
                server.runOnServer(s -> {
                    for (var line : SettlementData.get(s).news()) Minecraftportsmod.LOGGER.info("[test] news day {} #{}: {}", line.day(), line.portId(), line.text().getString());
                    for (var st : SettlementData.get(s).all()) {
                        Minecraftportsmod.LOGGER.info("[test] #{} level={} days={} pop={} houses={} built={} effects={}", st.portId(),
                                st.level(), st.levelDays(), st.population(), st.houses(), st.housesBuilt(), st.effects().size());
                    }
                });
            }
        }
    }

    /** Puts the spectating player at target+offset, looking at the target. */
    private static void look(ServerPlayer player, double tx, double ty, double tz, double ox, double oy, double oz) {
        double x = tx + ox, y = ty + oy, z = tz + oz;
        double dx = tx - x, dy = ty - y, dz = tz - z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        player.teleportTo((net.minecraft.server.level.ServerLevel) player.level(), x, y, z, java.util.Set.of(), yaw, pitch, false);
    }
}
