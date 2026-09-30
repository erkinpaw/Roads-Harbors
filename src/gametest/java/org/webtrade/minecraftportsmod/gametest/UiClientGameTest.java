package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.client.chart.BuildingScreen;
import org.webtrade.minecraftportsmod.client.chart.ScoutMapScreen;
import org.webtrade.minecraftportsmod.client.chart.TradeScreen;
import org.webtrade.minecraftportsmod.client.chart.VillageScreen;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;
import org.webtrade.minecraftportsmod.registry.ModContent;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

/**
 * The village's screens as a player sees them on a 1920×1080 monitor: the village's board (every tab), a
 * building's menu, a building site, a resident, the stall, the cartographer's map. Screenshots only (a look, not
 * a check), at the auto scale and at a coarser one.
 */
public class UiClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[ui] " + fmt, args);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
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
            for (String c : new String[]{"grow 1 woodcutter", "grow 1 miner", "grow 1 fisher", "grow 1 farmer", "grow 1 sawyer", "grow 1 merchant",
                    "grow 1 scout", "build 1 storehouse 3", "build 1 wood_hut 3", "build 1 sawmill 2", "build 1 mine_house 2", "build 1 market 1",
                    "build 1 cartographer 2", "build 1 field 2", "build 1 hut 2", "build 1 hut 1"}) {
                server.runCommand("village " + c);
            }
            for (String r : new String[]{"wood 300", "stone 200", "planks 120", "sticks 40", "iron 20", "coal 40", "food 200"}) {
                server.runCommand("village give 1 " + r);
            }
            for (int day = 1; day <= 3; day++) {
                server.runCommand("village day");
                context.waitTicks(20);
            }
            server.runOnServer(s -> {
                // a building site too: a house planned
                org.webtrade.minecraftportsmod.colony.VillageManager.unlock(s, VillageData.get(s).get(1), BuildingType.HOUSE, false);
            });
            server.runCommand("village day");
            context.waitTicks(20);

            // the village's board, tab by tab
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                Village v = VillageData.get(s).get(1);
                p.teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 1, v.center.getZ() + 0.5, java.util.Set.of(), 0, 0, false);
                ColonyService.sendVillage(p, 1, true);
            });
            context.waitForScreen(VillageScreen.class);
            context.waitTicks(20);
            for (String tab : new String[]{"overview", "people", "store", "trade", "tree", "map", "tasks", "log"}) {
                context.runOnClient(mc -> ((VillageScreen) mc.gui.screen()).showTab(tab));
                context.waitTicks(10);
                context.takeScreenshot("ui_village_" + tab);
            }
            context.setScreen(() -> null);

            // a building's menu, and a building site
            int[] ids = {-1, -1, -1};
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                for (Building b : v.buildings()) {
                    if (b.type == BuildingType.SAWMILL) ids[0] = b.id;
                    if (b.type == BuildingType.FIELD) ids[1] = b.id;
                    if (b.state() != Building.State.BUILT && ids[2] < 0) ids[2] = b.id;
                }
                log("sawmill {} field {} site {}", ids[0], ids[1], ids[2]);
            });
            for (int i = 0; i < 2; i++) {
                final int id = ids[i];
                server.runOnServer(s -> ColonyService.handleAction(s.getPlayerList().getPlayers().getFirst(),
                        new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.OPEN, id, 0)));
                context.waitForScreen(BuildingScreen.class);
                context.waitTicks(10);
                context.takeScreenshot("ui_building_" + (i == 0 ? "sawmill" : "field"));
                context.setScreen(() -> null);
            }
            if (ids[2] >= 0) {
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(1);
                    ColonyService.sendSite(s.getPlayerList().getPlayers().getFirst(), v, v.building(ids[2]));
                });
                context.waitTicks(20);
                context.takeScreenshot("ui_site");
                context.setScreen(() -> null);
            }
            // a resident
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                for (ResidentEntity e : s.overworld().getEntitiesOfClass(ResidentEntity.class, p.getBoundingBox().inflate(80), ResidentEntity::colony)) {
                    ColonyService.openDweller(p, e);
                    break;
                }
            });
            context.waitTicks(20);
            context.takeScreenshot("ui_person");
            context.setScreen(() -> null);
            // the stall
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                p.getInventory().add(new ItemStack(Items.OAK_LOG, 32));
                p.getInventory().add(new ItemStack(Items.EMERALD, 20));
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.TRADE, 0, 0));
            });
            context.waitForScreen(TradeScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("ui_trade");
            context.setScreen(() -> null);
            // the cartographer's map
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                for (Building b : v.buildings()) {
                    if (b.type != BuildingType.CARTOGRAPHER) continue;
                    for (BlockPos q : BlockPos.betweenClosed(b.origin.offset(-3, -1, -3), b.origin.offset(3, 2, 3))) {
                        if (s.overworld().getBlockState(q).is(ModContent.MAP_TABLE)) {
                            ColonyService.openMap(s.getPlayerList().getPlayers().getFirst(), q.immutable());
                            return;
                        }
                    }
                }
            });
            context.waitTicks(20);
            if (context.computeOnClient(mc -> mc.gui.screen() instanceof ScoutMapScreen)) context.takeScreenshot("ui_map");
            context.setScreen(() -> null);

            // a small window: the same board, coarser
            context.getInput().resizeWindow(1280, 720);
            context.waitTicks(5);
            server.runOnServer(s -> ColonyService.sendVillage(s.getPlayerList().getPlayers().getFirst(), 1, true));
            context.waitForScreen(VillageScreen.class);
            context.waitTicks(20);
            context.takeScreenshot("ui_village_720p");
            context.setScreen(() -> null);
        }
    }
}
