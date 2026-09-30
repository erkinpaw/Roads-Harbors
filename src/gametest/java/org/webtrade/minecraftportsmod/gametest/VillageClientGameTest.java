package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.chart.ChartService;
import org.webtrade.minecraftportsmod.client.chart.ChartScreen;
import org.webtrade.minecraftportsmod.client.chart.TownHallScreen;
import org.webtrade.minecraftportsmod.economy.EconomyManager;
import org.webtrade.minecraftportsmod.economy.Settlement;
import org.webtrade.minecraftportsmod.economy.SettlementData;
import org.webtrade.minecraftportsmod.economy.Specialization;
import org.webtrade.minecraftportsmod.port.PortData;
import org.webtrade.minecraftportsmod.port.PortService;
import org.webtrade.minecraftportsmod.port.Route;

/**
 * Three islands with a lumber, a mining and a farming settlement: lets them trade for a while, then takes
 * screenshots of the chart (trade vessels under way) and every tab of a town hall.
 */
public class VillageClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1920, 1080);
            var server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 0 -30 0");
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            server.runCommand("forceload add -128 -128 127 127");

            int w = server.computeOnServer(s -> s.overworld().getSeaLevel()) - 1;
            int top = w + 1;
            for (int x = -128; x < 128; x += 64) {
                for (int z = -128; z < 128; z += 64) {
                    String xz = x + " %d " + z + " " + (x + 63) + " %d " + (z + 63);
                    server.runCommand("fill " + xz.formatted(w, w) + " water");
                    server.runCommand("fill " + xz.formatted(w + 1, w + 6) + " air");
                }
            }
            int[][] islands = {{-80, -60}, {80, -60}, {0, 80}};
            for (int[] c : islands) {
                server.runCommand("fill " + (c[0] - 8) + " " + w + " " + (c[1] - 8) + " " + (c[0] + 8) + " " + w + " " + (c[1] + 8) + " grass_block");
                server.runCommand("setblock " + c[0] + " " + top + " " + c[1] + " minecraftportsmod:port_office");
            }
            context.waitTicks(100);
            for (int[] c : islands) server.runCommand("execute positioned " + c[0] + " " + top + " " + c[1] + " run ports register 4");
            server.runOnServer(s -> {
                PortData data = PortData.get(s);
                String[] names = {"Сосновая бухта", "Рудная гавань", "Хлебный причал"};
                int i = 0;
                for (var p : data.ports()) data.renamePort(p, names[i++]);
            });
            server.waitFor(s -> PortData.get(s).routes().size() >= 3
                    && PortData.get(s).routes().stream().allMatch(r -> r.status() != Route.Status.PENDING), 20 * 30);
            server.runOnServer(s -> {
                PortData data = PortData.get(s);
                for (var p : data.ports()) PortService.addBerth(s.overworld(), p);
                Specialization[] specs = {Specialization.LUMBER, Specialization.MINING, Specialization.FARMING};
                int i = 0;
                for (var p : data.ports()) EconomyManager.found(s, p, specs[i++]);
                EconomyManager.setDayLength(s, 300);
            });

            // let them trade for a while (a day is 15 s here)
            context.waitTicks(20 * 90);
            server.runOnServer(s -> {
                for (Settlement st : SettlementData.get(s).all()) {
                    Minecraftportsmod.LOGGER.info("[test] {} pop={} treasury={} vessels={} log={}", EconomyManager.name(s, st),
                            st.population(), Math.round(st.treasury()), st.vessels().size(), st.log().size());
                }
                Minecraftportsmod.LOGGER.info("[test] runs={}", SettlementData.get(s).runs().size());
            });

            server.runOnServer(s -> ChartService.openOffice(s.getPlayerList().getPlayers().getFirst(), PortData.get(s).port(1)));
            context.waitForScreen(ChartScreen.class);
            context.waitTicks(60);
            context.takeScreenshot("village_chart");
            context.runOnClient(mc -> ((ChartScreen) mc.gui.screen()).selectPort(2));
            context.waitTicks(20);
            context.takeScreenshot("village_chart_selected");
            context.runOnClient(mc -> ((ChartScreen) mc.gui.screen()).openTownHall(1));
            context.waitForScreen(TownHallScreen.class);
            context.waitTicks(30);
            context.takeScreenshot("hall_goods");
            for (String tab : new String[]{"people", "trade", "chronicle"}) {
                context.runOnClient(mc -> ((TownHallScreen) mc.gui.screen()).showTab(tab));
                context.waitTicks(10);
                context.takeScreenshot("hall_" + tab);
            }
            context.runOnClient(mc -> mc.gui.setScreen(new TownHallScreen(null, 3)));
            context.waitTicks(30);
            context.takeScreenshot("hall_farm_goods");
            context.setScreen(() -> null);

            // residents look like players; talking to one opens the market
            server.runCommand("settlement residents 3 6");
            server.runCommand("gamemode creative @a");
            server.runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                var office = PortData.get(s).port(3).office();
                player.getInventory().clearContent();
                player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 64));
                player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 64));
                player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64));
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(s.overworld(), office.getX() + 5.5, office.getY() + 1.5, office.getZ() + 5.5, java.util.Set.of(), 135, 15, false);
            });
            context.waitTicks(60);
            server.runOnServer(s -> Minecraftportsmod.LOGGER.info("[test] server skins: {}", s.overworld().getEntitiesOfClass(
                    org.webtrade.minecraftportsmod.village.ResidentEntity.class, new net.minecraft.world.phys.AABB(-200, -100, -200, 200, 100, 200))
                    .stream().map(org.webtrade.minecraftportsmod.village.ResidentEntity::skin).toList()));
            context.runOnClient(mc -> {
                java.util.List<Integer> skins = new java.util.ArrayList<>();
                for (var e : mc.level.entitiesForRendering()) {
                    if (e instanceof org.webtrade.minecraftportsmod.village.ResidentEntity r) skins.add(r.skin());
                }
                Minecraftportsmod.LOGGER.info("[test] client skins: {}", skins);
            });
            context.takeScreenshot("residents");
            server.runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                var r = s.overworld().getEntitiesOfClass(org.webtrade.minecraftportsmod.village.ResidentEntity.class,
                        player.getBoundingBox().inflate(16)).stream().findFirst().orElse(null);
                Minecraftportsmod.LOGGER.info("[test] resident near: {}", r == null ? "none" : r.getCustomName().getString() + " " + r.profession());
                if (r != null) {
                    r.teleportTo(player.getX() + 2, player.getY(), player.getZ() + 2);
                    org.webtrade.minecraftportsmod.village.MarketService.open(player, r);
                }
            });
            context.waitForScreen(org.webtrade.minecraftportsmod.client.chart.MarketScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("market");
            // buy 16 wheat, sell 8 planks, deliver to the first order
            server.runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                var st = SettlementData.get(s).get(3);
                double before = st.treasury();
                org.webtrade.minecraftportsmod.village.MarketService.handle(player, new org.webtrade.minecraftportsmod.network.MarketPayloads.Action(
                        org.webtrade.minecraftportsmod.network.MarketPayloads.Kind.BUY, 3, org.webtrade.minecraftportsmod.economy.Good.WHEAT.ordinal(), 16));
                org.webtrade.minecraftportsmod.village.MarketService.handle(player, new org.webtrade.minecraftportsmod.network.MarketPayloads.Action(
                        org.webtrade.minecraftportsmod.network.MarketPayloads.Kind.SELL, 3, org.webtrade.minecraftportsmod.economy.Good.OAK_PLANKS.ordinal(), 8));
                var orders = org.webtrade.minecraftportsmod.economy.Market.orders(st);
                Minecraftportsmod.LOGGER.info("[test] orders: {}", orders.stream().map(o -> o.amount() + "x" + o.good().id() + " for " + o.reward()).toList());
                for (var o : orders) {
                    player.getInventory().add(new net.minecraft.world.item.ItemStack(o.good().item, Math.min(64, o.remaining())));
                    org.webtrade.minecraftportsmod.village.MarketService.handle(player, new org.webtrade.minecraftportsmod.network.MarketPayloads.Action(
                            org.webtrade.minecraftportsmod.network.MarketPayloads.Kind.DELIVER, 3, o.id(), 0));
                    break;
                }
                int emeralds = 0;
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    var it = player.getInventory().getItem(i);
                    if (it.is(net.minecraft.world.item.Items.EMERALD)) emeralds += it.getCount();
                }
                Minecraftportsmod.LOGGER.info("[test] market: treasury {} -> {}, player emeralds {}, log: {}", Math.round(before),
                        Math.round(st.treasury()), emeralds, st.log().subList(0, Math.min(3, st.log().size())).stream().map(e -> e.text().getString()).toList());
            });
            context.waitTicks(10);
            context.takeScreenshot("market_after");
            context.runOnClient(mc -> ((org.webtrade.minecraftportsmod.client.chart.MarketScreen) mc.gui.screen()).showTab("orders"));
            context.waitTicks(10);
            context.takeScreenshot("market_orders");
            context.setScreen(() -> null);
        }
    }
}
