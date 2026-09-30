package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.client.chart.BuildingScreen;
import org.webtrade.minecraftportsmod.client.chart.TradeScreen;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Trade;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * The sawmill's wares and the merchant's stall: a village with a sawmill cuts stairs, slabs, doors and fences; the
 * buildings' menus say what they make and use; the stall sorts the goods by kind, and a deal is struck with the
 * slider's count (bought and sold, the stores and purses checked).
 */
public class StallClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[stall] " + fmt, args);
    }

    private static String stock(Village v) {
        StringBuilder b = new StringBuilder();
        for (Res r : Res.values()) b.append(r.id()).append('=').append(v.stock(r)).append(' ');
        return b.toString();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            // a monitor's window: the screens as they will be seen
            context.getInput().resizeWindow(1920, 1080);
            context.waitTicks(5);
            server.runCommand("gamemode creative @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 6000");
            server.runCommand("fill -30 -61 -50 30 -62 -26 water");
            server.runCommand("tp @a 0 -60 -10 0 0");
            context.waitTicks(40);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(20);
            for (String c : new String[]{"grow 1 woodcutter", "grow 1 woodcutter", "grow 1 miner", "grow 1 fisher", "grow 1 sawyer",
                    "grow 1 merchant", "build 1 storehouse 3", "build 1 storehouse 3", "build 1 wood_hut 3", "build 1 sawmill 3",
                    "build 1 mine_house 2", "build 1 market 1", "build 1 hut 1", "build 1 hut 1"}) {
                server.runCommand("village " + c);
            }
            for (String r : new String[]{"wood 400", "stone 300", "planks 200", "sticks 60", "iron 30", "coal 60", "food 300"}) {
                server.runCommand("village give 1 " + r);
            }
            for (int day = 1; day <= 4; day++) {
                server.runCommand("village day");
                context.waitTicks(20);
                final int d = day;
                server.runOnServer(s -> log("day {}: {}", d, stock(VillageData.get(s).get(1))));
            }

            // the sawmill's menu: what it makes and uses up
            int[] mill = {-1}, hut = {-1};
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                for (Building b : v.buildings()) {
                    if (b.type == BuildingType.SAWMILL) mill[0] = b.id;
                    if (b.type == BuildingType.WOOD_HUT) hut[0] = b.id;
                }
            });
            for (int[] b : new int[][]{mill, hut}) {
                server.runOnServer(s -> {
                    var p = s.getPlayerList().getPlayers().getFirst();
                    Village v = VillageData.get(s).get(1);
                    p.teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 1, v.center.getZ() + 0.5, java.util.Set.of(), 0, 0, false);
                    ColonyService.handleAction(p, new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.OPEN, b[0], 0));
                });
                context.waitForScreen(BuildingScreen.class);
                context.waitTicks(10);
                context.takeScreenshot("stall_menu_" + (b == mill ? "sawmill" : "wood_hut"));
                context.setScreen(() -> null);
            }

            // the stall: the player brings logs and fish, and emeralds
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                p.getInventory().clearContent();
                p.getInventory().add(new ItemStack(Items.OAK_LOG, 48));
                p.getInventory().add(new ItemStack(Items.COOKED_COD, 20));
                p.getInventory().add(new ItemStack(Items.IRON_INGOT, 5));
                p.getInventory().add(new ItemStack(Items.EMERALD, 40));
                Village v = VillageData.get(s).get(1);
                log("purse {} before | {}", v.emeralds(), stock(v));
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.TRADE, 0, 0));
            });
            context.waitForScreen(TradeScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("stall_a_all");
            for (int t = 1; t <= Res.Kind.values().length; t++) {
                final int tab = t;
                context.runOnClient(mc -> ((TradeScreen) mc.gui.screen()).showTab(tab));
                context.waitTicks(5);
                context.takeScreenshot("stall_b_tab_" + Res.Kind.values()[t - 1].name().toLowerCase());
            }
            // buy: 12 stairs (building goods)
            context.runOnClient(mc -> {
                TradeScreen ts = (TradeScreen) mc.gui.screen();
                ts.showTab(Res.Kind.BUILDING.ordinal() + 1);
                ts.pick(Res.STAIRS.ordinal(), false, 12);
            });
            context.waitTicks(5);
            context.takeScreenshot("stall_c_buy_stairs");
            context.runOnClient(mc -> ((TradeScreen) mc.gui.screen()).press());
            context.waitTicks(10);
            context.takeScreenshot("stall_d_bought");
            // sell: 30 logs
            context.runOnClient(mc -> {
                TradeScreen ts = (TradeScreen) mc.gui.screen();
                ts.showTab(Res.Kind.RAW.ordinal() + 1);
                ts.pick(Res.WOOD.ordinal(), true, 30);
            });
            context.waitTicks(5);
            context.takeScreenshot("stall_e_sell_logs");
            context.runOnClient(mc -> ((TradeScreen) mc.gui.screen()).press());
            context.waitTicks(10);
            context.takeScreenshot("stall_f_sold");
            // a tool: a stone axe
            int axe = -1;
            for (int i = 0; i < Trade.WARES.size(); i++) {
                Trade.Ware w = Trade.WARES.get(i);
                if (w.tool() == Trade.Tool.AXE && w.tier() == 2) axe = i;
            }
            final int stoneAxe = axe;
            context.runOnClient(mc -> {
                TradeScreen ts = (TradeScreen) mc.gui.screen();
                ts.showTab(Res.Kind.TOOLS.ordinal() + 1);
                ts.pick(stoneAxe, false, 1);
                ts.press();
            });
            context.waitTicks(10);
            context.takeScreenshot("stall_g_tools");
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                Village v = VillageData.get(s).get(1);
                int stairs = 0, logs = 0, axes = 0;
                var inv = p.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack st = inv.getItem(i);
                    if (Res.STAIRS.unitsOf(st) > 0) stairs += st.getCount();
                    if (st.is(Items.OAK_LOG)) logs += st.getCount();
                    if (st.is(Items.STONE_AXE)) axes++;
                }
                log("after: purse {}, player emeralds {}, stairs {}, logs {}, stone axes {} | {}", v.emeralds(), Trade.emeralds(p), stairs, logs,
                        axes, stock(v));
                for (var l : v.log()) if (l.text().getString().contains("emerald") || l.text().getString().contains("изумр")) log("log: {}", l.text().getString());
                if (stairs != 12) throw new AssertionError("expected 12 stairs bought, got " + stairs);
                if (logs != 18) throw new AssertionError("expected 18 logs left after selling 30, got " + logs);
            });
            context.setScreen(() -> null);
        }
    }
}
