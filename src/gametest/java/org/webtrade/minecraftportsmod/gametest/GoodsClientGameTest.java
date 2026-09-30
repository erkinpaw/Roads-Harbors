package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.client.chart.BuildingScreen;
import org.webtrade.minecraftportsmod.client.chart.ScoutMapScreen;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Dweller;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.colony.VillageManager;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;
import org.webtrade.minecraftportsmod.registry.ModContent;

/**
 * Goods and scouts: planks and sticks made by hand, then at the sawmill; tools wearing out and their replacement
 * (and what happens with nothing to replace them with); a scout's expeditions finding a village far off; the map on
 * the cartographer's table.
 */
public class GoodsClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[goods] " + fmt, args);
    }

    private static Village village(MinecraftServer s) {
        return VillageData.get(s).get(1);
    }

    private static String stock(Village v) {
        StringBuilder b = new StringBuilder();
        for (Res r : Res.values()) b.append(r.id()).append('=').append(v.stock(r)).append(' ');
        b.append("| made ");
        for (Res r : Res.values()) if (v.made(r) > 0) b.append(r.id()).append('+').append(v.made(r)).append(' ');
        b.append("| used ");
        for (Res r : Res.values()) if (v.used(r) > 0) b.append(r.id()).append('-').append(v.used(r)).append(' ');
        b.append("| short ");
        for (Job j : Job.values()) if (v.toolsShort(j)) b.append(j.id()).append(' ');
        return b.toString();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 6000");
            // another village far to the east, to be found
            server.runCommand("fill 440 -61 -30 500 -62 -10 water");
            server.runCommand("tp @a 470 -60 0 180 0");
            context.waitTicks(40);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(20);
            // ours
            server.runCommand("fill -30 -61 -50 30 -62 -26 water");
            for (int[] t : new int[][]{{-40, 10}, {-44, 16}, {-38, 22}, {-46, 4}, {-42, -2}, {-50, 12}}) {
                server.runCommand("place feature minecraft:oak " + t[0] + " -60 " + t[1]);
            }
            server.runCommand("tp @a 0 -60 -10 0 0");
            context.waitTicks(40);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(20);
            server.runOnServer(s -> {
                for (Village v : VillageData.get(s).all()) log("village #{} {} at {}", v.id, v.name, v.center.toShortString());
            });
            // ours is #2 now: make it the one we look at
            int[] us = new int[1];
            server.runOnServer(s -> {
                for (Village v : VillageData.get(s).all()) if (Math.abs(v.center.getX()) < 100) us[0] = v.id;
            });
            final int id = us[0];
            server.runCommand("tp @a 0 -25 45 180 50");

            // ---- A: by hand, without a sawmill: the tools wear, a few planks and sticks are made
            for (String c : new String[]{"grow %d miner", "grow %d woodcutter", "grow %d fisher", "build %d storehouse 3", "build %d mine_house 2"}) {
                server.runCommand("village " + String.format(c, id));
            }
            server.runCommand("village give " + id + " wood 150");
            server.runCommand("village give " + id + " stone 120");
            for (int day = 1; day <= 4; day++) {
                server.runCommand("village day");
                context.waitTicks(30);
                final int d = day;
                server.runOnServer(s -> log("by hand day {}: {}", d, stock(VillageData.get(s).get(id))));
            }
            // ---- B: no sticks, no stone: the miners' stone tools can't be replaced
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(id);
                VillageManager.give(s, v, Res.STICKS, -9999);
                VillageManager.give(s, v, Res.STONE, -9999);
                VillageManager.give(s, v, Res.WOOD, -9999);
            });
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(id);
                log("before wear: miner tools {} short {}", v.toolLevel(Job.MINER), v.toolsShort(Job.MINER));
                VillageManager.wearNow(s, v, Job.MINER, 1.0);
                log("worn out, nothing to mend with: miner tools {} short {} | {}", v.toolLevel(Job.MINER), v.toolsShort(Job.MINER), stock(v));
                VillageManager.give(s, v, Res.STICKS, 10);
                VillageManager.give(s, v, Res.STONE, 10);
                VillageManager.wearNow(s, v, Job.MINER, 0);
                log("sticks and stone brought: miner tools {} short {} | {}", v.toolLevel(Job.MINER), v.toolsShort(Job.MINER), stock(v));
            });
            // ---- C: the sawmill and the cartographer's house
            server.runCommand("village give " + id + " wood 400");
            server.runCommand("village give " + id + " stone 200");
            server.runCommand("village build " + id + " wood_hut 3");
            server.runCommand("village build " + id + " sawmill 1");
            server.runCommand("village build " + id + " cartographer 2");
            for (int i = 0; i < 3; i++) server.runCommand("village grow " + id + " woodcutter");
            for (int day = 1; day <= 22; day++) {
                // from the second expedition on: east, where the other village is
                if (day == 6) {
                    server.runOnServer(s -> ColonyService.handleAction(s.getPlayerList().getPlayers().getFirst(),
                            new ColonyPayloads.VillageAction(id, ColonyPayloads.VillageAction.SCOUT, 0, 0)));
                    context.waitTicks(10);
                    context.setScreen(() -> null);
                }
                server.runCommand("village day");
                context.waitTicks(30);
                final int d = day;
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(id);
                    StringBuilder sc = new StringBuilder();
                    for (Dweller w : v.dwellers()) {
                        if (w.job() == Job.SCOUT || w.job() == Job.SAWYER) sc.append(w.name).append(' ').append(w.job().id())
                                .append(w.away() ? " away till " + w.back() : " home").append(" | ");
                    }
                    log("day {}: {} || {}", d, stock(v), sc);
                });
            }
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(id);
                for (var l : v.log()) log("log {}: {}", l.day(), l.text().getString());
            });

            // ---- D: the buildings, the sawyer at work, the menus, the map
            int[] mill = {0, 0, 0, 0}, carto = {0, 0, 0, 0};
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(id);
                for (Building b : v.buildings()) {
                    int[] t = b.type == BuildingType.SAWMILL ? mill : b.type == BuildingType.CARTOGRAPHER ? carto : null;
                    if (t == null) continue;
                    t[0] = b.origin.getX();
                    t[1] = b.origin.getY();
                    t[2] = b.origin.getZ();
                    t[3] = b.id;
                }
            });
            for (int[] b : new int[][]{mill, carto}) {
                server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), b[0] + 8.5, b[1] + 5, b[2] + 8.5,
                        java.util.Set.of(), 135, 25, false));
                context.waitTicks(40);
                context.takeScreenshot("goods_b_" + (b == mill ? "sawmill" : "cartographer"));
            }
            server.runOnServer(s -> ColonyService.handleAction(s.getPlayerList().getPlayers().getFirst(),
                    new ColonyPayloads.VillageAction(id, ColonyPayloads.VillageAction.OPEN, mill[3], 0)));
            context.waitForScreen(BuildingScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("goods_c_sawmill_menu");
            context.setScreen(() -> null);
            // the table: find it in the cartographer's house
            BlockPos[] table = new BlockPos[1];
            server.runOnServer(s -> {
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = -1; dy <= 2; dy++) {
                            BlockPos p = new BlockPos(carto[0] + dx, carto[1] + dy, carto[2] + dz);
                            if (s.overworld().getBlockState(p).is(ModContent.MAP_TABLE)) table[0] = p;
                        }
                    }
                }
                log("map table at {}", table[0]);
                if (table[0] != null) ColonyService.openMap(s.getPlayerList().getPlayers().getFirst(), table[0]);
            });
            if (table[0] != null) {
                context.waitForScreen(ScoutMapScreen.class);
                context.waitTicks(10);
                context.takeScreenshot("goods_d_map");
                context.setScreen(() -> null);
            }
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(id);
                log("known villages {}", v.log().stream().filter(l -> l.text().getString().contains("found a village")).count());
            });
            server.runCommand("tp @a 0 -25 45 180 50");
            context.waitTicks(40);
            context.takeScreenshot("goods_99_air");
        }
    }
}
