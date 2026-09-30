package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.client.chart.BuildingScreen;
import org.webtrade.minecraftportsmod.client.chart.VillageScreen;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Crop;
import org.webtrade.minecraftportsmod.colony.Tree;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Trade;
import org.webtrade.minecraftportsmod.colony.VillageManager;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

/**
 * The development tree: a camp grown day by day into a village, its tree screen, a node picked and put first, a
 * building's own menu, a field sown with something else; and what the new buildings look like.
 */
public class TreeClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[tree] " + fmt, args);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 7000");
            server.runCommand("fill -45 -61 -45 45 -61 45 stone");
            server.runCommand("fill -30 -61 -45 30 -62 -22 water");
            server.runCommand("fill 30 -60 -10 40 -56 10 stone");
            for (int[] t : new int[][]{{-34, 10}, {-38, 16}, {-32, 22}, {-40, 4}, {-36, -2}}) {
                server.runCommand("place feature minecraft:oak " + t[0] + " -60 " + t[1]);
            }
            server.runCommand("tp @a 0 -60 0 180 0");
            context.waitTicks(20);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(40);
            server.runCommand("tp @a 0 -35 30 180 45");
            for (int day = 1; day <= 30; day++) {
                server.runCommand("village day");
                context.waitTicks(20 * 4);
                boolean[] done = new boolean[1];
                final int d = day;
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(1);
                    StringBuilder b = new StringBuilder();
                    for (Building x : v.buildings()) b.append(x.type.id()).append(':').append(x.state().id()).append(' ');
                    log("day {} {} people {} iron {} | {}", d, v.level(), v.population(), v.stock(org.webtrade.minecraftportsmod.colony.Res.IRON), b);
                    done[0] = v.level().ordinal() >= Village.Level.VILLAGE.ordinal() && v.has(BuildingType.MINE_HOUSE)
                            && v.has(BuildingType.MARKET) && d >= 18;
                });
                if (done[0]) break;
            }
            server.runCommand("village give 1 iron 30");
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                int born = 0, came = 0;
                for (var l : v.log()) {
                    String t = l.text().getString();
                    if (t.contains("baby")) born++;
                    if (t.contains("newcomer")) came++;
                }
                log("BIRTHS born={} arrived={} people={} mood={}", born, came, v.population(), v.mood());
            });
            context.waitTicks(20 * 10);
            server.runCommand("tp @a 0 -30 38 180 50");
            context.waitTicks(40);
            context.takeScreenshot("tree_00_village_air");

            // the tree
            server.runOnServer(s -> ColonyService.openBoard(s.getPlayerList().getPlayers().getFirst(), VillageData.get(s).get(1).board));
            context.waitForScreen(VillageScreen.class);
            context.waitTicks(20);
            context.runOnClient(mc -> ((VillageScreen) mc.gui.screen()).showTab("tree"));
            context.waitTicks(10);
            context.takeScreenshot("tree_01_tree");
            context.runOnClient(mc -> ((VillageScreen) mc.gui.screen()).select(BuildingType.MINE_HOUSE.ordinal()));
            context.waitTicks(10);
            context.takeScreenshot("tree_02_node");
            // put it first
            server.runOnServer(s -> ColonyService.handleAction(s.getPlayerList().getPlayers().getFirst(),
                    new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.PIN, BuildingType.MINE_HOUSE.ordinal(), 0)));
            context.waitTicks(40);
            context.takeScreenshot("tree_03_pinned");
            context.setScreen(() -> null);
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                log("priority {} status {}", v.priority(), Tree.node(v, BuildingType.MINE_HOUSE));
            });

            // a field's own menu: sow potatoes
            int[] field = new int[1];
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                for (Building b : v.buildings()) if (b.type == BuildingType.FIELD && b.state() == Building.State.BUILT) field[0] = b.id;
                ColonyService.handleAction(s.getPlayerList().getPlayers().getFirst(),
                        new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.OPEN, field[0], 0));
            });
            context.waitForScreen(BuildingScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("tree_04_field_menu");
            server.runOnServer(s -> ColonyService.handleAction(s.getPlayerList().getPlayers().getFirst(),
                    new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.CROP, field[0], Crop.POTATO.ordinal())));
            context.waitTicks(10);
            context.takeScreenshot("tree_05_field_potato");
            context.setScreen(() -> null);
            // the miners' house menu
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                for (Building b : v.buildings()) {
                    if (b.type.branch == BuildingType.Branch.MINE && b.state() == Building.State.BUILT) {
                        ColonyService.handleAction(s.getPlayerList().getPlayers().getFirst(),
                                new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.OPEN, b.id, 0));
                    }
                }
            });
            context.waitTicks(20);
            context.takeScreenshot("tree_06_mine_menu");
            context.setScreen(() -> null);

            // a morning in real time: the miners in their mine, the green spreading
            int[] grass0 = new int[1];
            server.runOnServer(s -> grass0[0] = grass(s));
            server.runCommand("time set 1500");
            for (int i = 0; i < 12; i++) {
                context.waitTicks(20 * 10);
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(1);
                    StringBuilder b = new StringBuilder();
                    for (var e : residents(s, v)) {
                        if (e.colonyJob() == Job.MINER || e.colonyJob() == Job.MERCHANT) {
                            b.append(e.getCustomName().getString()).append(' ').append(e.activity().getString()).append(" @")
                                    .append(e.blockPosition().toShortString()).append(" | ");
                        }
                    }
                    log("MINE step {} iron {} stone {} :: {}", VillageManager.mineStepOf(v), v.stock(Res.IRON), v.stock(Res.STONE), b);
                });
                if (i == 6) {
                    server.runOnServer(s -> {
                        Village v = VillageData.get(s).get(1);
                        for (var e : residents(s, v)) {
                            if (e.colonyJob() == Job.MINER) {
                                s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), e.getX() + 3, e.getY() + 2.5, e.getZ() + 3,
                                        java.util.Set.of(), 135, 30, false);
                                break;
                            }
                        }
                    });
                    context.waitTicks(15);
                    context.takeScreenshot("tree_07_mine");
                }
            }
            server.runOnServer(s -> log("GREEN grass {} -> {}", grass0[0], grass(s)));
            // the stall: sell the village wood and stone, buy from it
            server.runCommand("give @a oak_log 64");
            server.runCommand("give @a cobblestone 64");
            server.runCommand("give @a emerald 20");
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                Village v = VillageData.get(s).get(1);
                p.teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 1, v.center.getZ() + 0.5, java.util.Set.of(), 0, 0, false);
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.TRADE, 0, 0));
            });
            context.waitTicks(20);
            context.takeScreenshot("tree_08_trade");
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                Village v = VillageData.get(s).get(1);
                int purse = v.emeralds();
                for (Res r : Res.values()) {
                    Trade.Ware w = Trade.WARES.get(r.ordinal());
                    log("TRADE {} stock {} buys at {}c sells at {}c", r.id(), v.stock(r), Trade.buyCents(v, w), Trade.sellCents(v, w));
                    ColonyService.handleAction(p, new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.SELL, r.ordinal(), 10));
                    ColonyService.handleAction(p, new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.BUY, r.ordinal(), 10));
                }
                log("TRADE purse {} -> {}, player emeralds {}", purse, v.emeralds(), Trade.emeralds(p));
            });
            context.waitTicks(10);
            context.takeScreenshot("tree_09_trade_after");
            context.setScreen(() -> null);

            // a few more days: the pinned building goes up, the field is resown
            for (int day = 0; day < 4; day++) {
                server.runCommand("village day");
                context.waitTicks(20 * 5);
            }
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                for (Building b : v.buildings()) log("  #{} {} {} at {} crop {}", b.id, b.type.id(), b.state().id(), b.origin.toShortString(), b.option());
                for (var l : v.log()) log("log {}: {}", l.day(), l.text().getString());
            });
            // each building, up close
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                java.util.List<Building> list = new java.util.ArrayList<>(v.buildings());
                shots.clear();
                for (Building b : list) if (b.state() == Building.State.BUILT) shots.add(new int[]{b.origin.getX(), b.origin.getY(), b.origin.getZ(), b.type.ordinal()});
            });
            for (int[] b : shots) {
                server.runOnServer(s -> {
                    var p = s.getPlayerList().getPlayers().getFirst();
                    p.teleportTo(s.overworld(), b[0] + 7.5, b[1] + 5, b[2] + 7.5, java.util.Set.of(), 135, 30, false);
                });
                context.waitTicks(20);
                context.takeScreenshot("tree_b_" + BuildingType.values()[b[3]].id());
            }
            server.runCommand("tp @a 0 -25 42 180 55");
            context.waitTicks(40);
            context.takeScreenshot("tree_99_village_air");
        }
    }

    private static final java.util.List<int[]> shots = new java.util.ArrayList<>();

    private static java.util.List<ResidentEntity> residents(net.minecraft.server.MinecraftServer s, Village v) {
        return s.overworld().getEntitiesOfClass(ResidentEntity.class, new net.minecraft.world.phys.AABB(v.center).inflate(80), ResidentEntity::colony);
    }

    /** Grass around the village (the greening). */
    private static int grass(net.minecraft.server.MinecraftServer s) {
        Village v = VillageData.get(s).get(1);
        int n = 0;
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                if (s.overworld().getBlockState(v.center.offset(x, -1, z)).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)) n++;
            }
        }
        return n;
    }
}
