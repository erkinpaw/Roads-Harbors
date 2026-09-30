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
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Tree;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.colony.VillageManager;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

import java.util.ArrayList;
import java.util.List;

/**
 * The tree as a circle and the buildings' levels: a camp left to grow on its own for a few weeks (what it opens,
 * builds, raises, pulls down), then every kind of building at each of its levels, a hut raised a level in place, the
 * tree screen (the middle, a node to open, a node dragged with the mouse and remembered) and a building's menu.
 */
public class LevelsClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[levels] " + fmt, args);
    }

    private static Village village(net.minecraft.server.MinecraftServer s) {
        return VillageData.get(s).get(1);
    }

    private static String describe(Village v) {
        StringBuilder b = new StringBuilder();
        for (Building x : v.buildings()) {
            b.append(x.type.id()).append(" L").append(x.level());
            if (x.goal() > 0) b.append('>').append(x.goal());
            if (x.state() != Building.State.BUILT) b.append(':').append(x.state().id());
            b.append(", ");
        }
        return b.toString();
    }

    private static String unlocked(Village v) {
        StringBuilder b = new StringBuilder();
        for (BuildingType t : BuildingType.values()) if (t.isNode() && !t.free && v.unlocked(t)) b.append(t.id()).append(' ');
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
            // a lake south of the camp, some rock to the east and a wood to the west
            server.runCommand("fill -30 -61 -45 30 -62 -22 water");
            server.runCommand("fill 34 -60 -12 46 -57 12 stone");
            for (int[] t : new int[][]{{-40, 10}, {-44, 16}, {-38, 22}, {-46, 4}, {-42, -2}, {-50, 12}}) {
                server.runCommand("place feature minecraft:oak " + t[0] + " -60 " + t[1]);
            }
            server.runCommand("tp @a 0 -60 0 180 0");
            context.waitTicks(20);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(40);
            server.runOnServer(s -> log("focus chosen by the land: {}", village(s).focus()));

            // ---- A: a few weeks on its own
            server.runCommand("tp @a 0 -30 40 180 50");
            for (int day = 1; day <= 26; day++) {
                server.runCommand("village day");
                context.waitTicks(20 * 3);
                final int d = day;
                server.runOnServer(s -> {
                    Village v = village(s);
                    log("day {} {} people {} mood {} stock w{} s{} f{} i{} | open: {}| {}", d, v.level(), v.population(), v.mood(),
                            v.stock(Res.WOOD), v.stock(Res.STONE), v.stock(Res.FOOD), v.stock(Res.IRON), unlocked(v), describe(v));
                });
            }
            server.runOnServer(s -> {
                for (var l : village(s).log()) log("log {}: {}", l.day(), l.text().getString());
            });
            context.takeScreenshot("levels_00_after_weeks");
            // the tree as the village left it: open, can be opened, waiting, hidden
            context.runOnClient(mc -> mc.options.guiScale().set(2));
            context.getInput().resizeWindow(1280, 720);
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                Village v = village(s);
                p.teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 1, v.center.getZ() + 0.5, java.util.Set.of(), 0, 0, false);
                ColonyService.openBoard(p, v.board);
            });
            context.waitForScreen(VillageScreen.class);
            context.waitTicks(20);
            context.runOnClient(mc -> ((VillageScreen) mc.gui.screen()).showTab("tree"));
            context.waitTicks(10);
            context.takeScreenshot("levels_a1_tree_natural");
            int[] pick = {-1, -1};
            server.runOnServer(s -> {
                Village v = village(s);
                for (BuildingType t : BuildingType.values()) {
                    if (!t.isNode()) continue;
                    Tree.Node n = Tree.node(v, t);
                    log("natural node {} {} unlock {}", t.id(), n, Tree.unlockCost(v, t));
                    if (n == Tree.Node.READY && pick[0] < 0) pick[0] = t.ordinal();
                    if (n == Tree.Node.WAIT && pick[1] < 0) pick[1] = t.ordinal();
                }
            });
            for (int k = 0; k < 2; k++) {
                if (pick[k] < 0) continue;
                final int sel = pick[k];
                context.runOnClient(mc -> ((VillageScreen) mc.gui.screen()).select(sel));
                context.waitTicks(10);
                context.takeScreenshot("levels_a2_tree_" + (k == 0 ? "ready" : "wait"));
            }
            context.setScreen(() -> null);
            context.runOnClient(mc -> mc.options.guiScale().set(0));
            context.getInput().resizeWindow(854, 480);
            server.runCommand("tp @a 0 -30 40 180 50");

            // ---- B: every kind of building at its levels
            server.runCommand("village build 1 storehouse_2 3");
            server.runCommand("village give 1 wood 900");
            server.runCommand("village give 1 stone 900");
            server.runCommand("village give 1 iron 120");
            String[][] show = {
                    {"hut", "1"}, {"hut", "2"}, {"hut", "3"},
                    {"house", "1"}, {"house", "3"}, {"house_tall", "1"}, {"house_tall", "3"},
                    {"stone_house", "3"}, {"stone_house_tall", "3"},
                    {"storehouse", "1"}, {"storehouse", "3"}, {"mine_house", "3"}, {"wood_hut", "3"}, {"fish_hut", "3"},
                    {"farm", "3"}, {"field", "3"}, {"market", "3"}};
            List<int[]> shots = new ArrayList<>();
            for (String[] b : show) {
                int before = shots.size();
                server.runOnServer(s -> {
                    Village v = village(s);
                    Building x = VillageManager.buildNow(s.overworld(), v, BuildingType.byId(b[0]), Integer.parseInt(b[1]));
                    if (x == null) log("NO ROOM for {} L{}", b[0], b[1]);
                    else {
                        shots.add(new int[]{x.origin.getX(), x.origin.getY(), x.origin.getZ(), x.type.ordinal(), x.level(), x.front.get2DDataValue()});
                        log("built {} L{} #{} at {} pieces {}", b[0], x.level(), x.id, x.origin.toShortString(), x.blueprint(v.wood).pieces.size());
                    }
                });
                context.waitTicks(5);
                if (shots.size() == before) continue;
            }
            context.waitTicks(40);
            for (int[] b : shots) {
                server.runOnServer(s -> {
                    var p = s.getPlayerList().getPlayers().getFirst();
                    net.minecraft.core.Direction f = net.minecraft.core.Direction.from2DDataValue(b[5]);
                    // stand in front of the door, a little to the side and up
                    double x = b[0] + 0.5 + f.getStepX() * 9 + f.getClockWise().getStepX() * 4;
                    double z = b[2] + 0.5 + f.getStepZ() * 9 + f.getClockWise().getStepZ() * 4;
                    float yaw = (float) Math.toDegrees(Math.atan2(-(b[0] + 0.5 - x), b[2] + 0.5 - z));
                    p.teleportTo(s.overworld(), x, b[1] + 4, z, java.util.Set.of(), yaw, 22, false);
                });
                context.waitTicks(15);
                context.takeScreenshot("levels_b_" + BuildingType.values()[b[3]].id() + "_L" + b[4]);
            }
            // inside the two-storey house: the stair and the beds upstairs
            server.runOnServer(s -> {
                Village v = village(s);
                for (Building x : v.buildings()) {
                    if (x.type == BuildingType.HOUSE_TALL) {
                        var bp = x.blueprint(v.wood);
                        log("tall house beds {}", bp.beds);
                        var p = s.getPlayerList().getPlayers().getFirst();
                        var at = bp.frame.at(1, 4, 2);
                        var look = bp.frame.at(-2, 1, -2);
                        float yaw = (float) Math.toDegrees(Math.atan2(-(look.getX() - at.getX()), look.getZ() - at.getZ()));
                        p.teleportTo(s.overworld(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, java.util.Set.of(), yaw, 25, false);
                        return;
                    }
                }
            });
            context.waitTicks(15);
            context.takeScreenshot("levels_c_tall_inside");

            // ---- C: a hut raised a level in place, by the village's own hands (days pass as if nobody watched)
            int[] hut = {-1};
            server.runOnServer(s -> {
                Village v = village(s);
                for (Building x : v.buildings()) {
                    if (x.type == BuildingType.HUT && x.level() == 1 && x.standing()) {
                        hut[0] = x.id;
                        log("raise hut #{}: {}", x.id, VillageManager.raise(s, v, x));
                        break;
                    }
                }
            });
            for (int i = 0; i < 4; i++) {
                server.runCommand("village day");
                context.waitTicks(40);
                server.runOnServer(s -> {
                    Building x = village(s).building(hut[0]);
                    log("hut #{} level {} goal {} work {} state {}", hut[0], x == null ? -1 : x.level(), x == null ? -1 : x.goal(),
                            x == null ? -1 : x.work(), x == null ? "-" : x.state().id());
                });
            }

            // ---- C2: a house raised a level while someone watches: the villagers bring what it takes and build it
            int[] house = {-1};
            server.runOnServer(s -> {
                Village v = village(s);
                for (Building x : v.buildings()) {
                    if (x.type == BuildingType.HOUSE && x.level() == 1 && x.standing()) {
                        house[0] = x.id;
                        log("raise house #{} watched: {}", x.id, VillageManager.raise(s, v, x));
                        var p = s.getPlayerList().getPlayers().getFirst();
                        p.teleportTo(s.overworld(), x.origin.getX() + 9.5, x.origin.getY() + 6, x.origin.getZ() + 9.5, java.util.Set.of(), 135, 25, false);
                        break;
                    }
                }
            });
            for (int i = 0; i < 9; i++) {
                context.waitTicks(20 * 10);
                final int t = (i + 1) * 10;
                server.runOnServer(s -> {
                    Village v = village(s);
                    Building x = v.building(house[0]);
                    StringBuilder who = new StringBuilder();
                    for (var e : s.overworld().getEntitiesOfClass(org.webtrade.minecraftportsmod.village.ResidentEntity.class,
                            new net.minecraft.world.phys.AABB(v.center).inflate(90), org.webtrade.minecraftportsmod.village.ResidentEntity::colony)) {
                        who.append(e.getCustomName().getString()).append(": ").append(e.activity().getString()).append(" | ");
                    }
                    StringBuilder pr = new StringBuilder();
                    for (Building y : v.projects()) pr.append(y.type.id()).append('#').append(y.id).append(' ');
                    log("projects: {} time {}", pr, s.overworld().getOverworldClockTime());
                    log("t={}s house L{} goal {} work {} delivered w{} s{} of w{} s{} :: {}", t, x.level(), x.goal(), x.work(),
                            x.delivered(Res.WOOD), x.delivered(Res.STONE), x.cost(Res.WOOD), x.cost(Res.STONE), who);
                });
                if (i == 3) context.takeScreenshot("levels_c2_raising");
            }

            // ---- D: the tree screen
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                Village v = village(s);
                p.teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 1, v.center.getZ() + 0.5, java.util.Set.of(), 0, 0, false);
                ColonyService.openBoard(p, v.board);
            });
            context.waitForScreen(VillageScreen.class);
            context.waitTicks(20);
            context.runOnClient(mc -> ((VillageScreen) mc.gui.screen()).showTab("tree"));
            context.waitTicks(10);
            context.takeScreenshot("levels_d0_tree_small");
            // a common window: 1280×720, the interface at ×2
            context.runOnClient(mc -> mc.options.guiScale().set(2));
            context.getInput().resizeWindow(1280, 720);
            context.waitTicks(10);
            context.setScreen(() -> null);
            server.runOnServer(s -> ColonyService.openBoard(s.getPlayerList().getPlayers().getFirst(), village(s).board));
            context.waitForScreen(VillageScreen.class);
            context.waitTicks(10);
            context.runOnClient(mc -> ((VillageScreen) mc.gui.screen()).showTab("tree"));
            context.waitTicks(10);
            context.takeScreenshot("levels_d1_tree");
            // zoomed in with the wheel: the names show
            context.getInput().setCursorPos(640, 420);
            context.waitTicks(2);
            for (int i = 0; i < 3; i++) {
                context.getInput().scroll(1);
                context.waitTicks(2);
            }
            context.waitTicks(5);
            context.takeScreenshot("levels_d1_tree_zoomed");
            context.runOnClient(mc -> ((VillageScreen) mc.gui.screen()).select(1000));
            context.waitTicks(10);
            context.takeScreenshot("levels_d1_tree_center");
            // a node that can be opened: which, and open it
            int[] ready = {-1};
            server.runOnServer(s -> {
                Village v = village(s);
                for (BuildingType t : BuildingType.values()) {
                    if (t.isNode()) log("node {} {} unlock {}", t.id(), Tree.node(v, t), Tree.unlockCost(v, t));
                    if (t.isNode() && Tree.node(v, t) == Tree.Node.READY && ready[0] < 0) ready[0] = t.ordinal();
                }
            });
            if (ready[0] >= 0) {
                context.runOnClient(mc -> ((VillageScreen) mc.gui.screen()).select(ready[0]));
                context.waitTicks(10);
                context.takeScreenshot("levels_d2_node_ready");
                server.runOnServer(s -> {
                    ColonyService.handleAction(s.getPlayerList().getPlayers().getFirst(),
                            new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.UNLOCK, ready[0], 0));
                    log("after UNLOCK {}: {}", BuildingType.values()[ready[0]].id(), Tree.node(village(s), BuildingType.values()[ready[0]]));
                });
                context.waitTicks(20);
                context.takeScreenshot("levels_d3_node_opened");
            }
            context.runOnClient(mc -> ((VillageScreen) mc.gui.screen()).select(BuildingType.HUT.ordinal()));
            context.waitTicks(10);
            context.takeScreenshot("levels_d4_node_hut");
            // drag the woodcutters' hut with the mouse to another place
            double[][] from = new double[1][];
            double[] scale = new double[1];
            context.runOnClient(mc -> {
                from[0] = ((VillageScreen) mc.gui.screen()).nodeOnScreen(BuildingType.WOOD_HUT);
                scale[0] = mc.getWindow().getGuiScale();
            });
            log("wood hut on screen at {} (scale {})", from[0] == null ? "?" : from[0][0] + "," + from[0][1], scale[0]);
            if (from[0] != null) {
                context.getInput().setCursorPos(from[0][0] * scale[0], from[0][1] * scale[0]);
                context.waitTicks(2);
                context.getInput().holdMouse(0);
                context.waitTicks(2);
                for (int i = 0; i < 10; i++) {
                    context.getInput().moveCursor(-6 * scale[0], 5 * scale[0]);
                    context.waitTicks(1);
                }
                context.getInput().releaseMouse(0);
                context.waitTicks(5);
                context.runOnClient(mc -> {
                    double[] m = VillageScreen.nodeMoved(BuildingType.WOOD_HUT);
                    log("wood hut moved to {}", m == null ? "nowhere" : m[0] + "," + m[1]);
                });
                context.takeScreenshot("levels_d5_dragged");
            }
            context.setScreen(() -> null);
            context.waitTicks(5);
            // open it again: the node stays where it was put
            server.runOnServer(s -> ColonyService.openBoard(s.getPlayerList().getPlayers().getFirst(), village(s).board));
            context.waitForScreen(VillageScreen.class);
            context.waitTicks(10);
            context.runOnClient(mc -> ((VillageScreen) mc.gui.screen()).showTab("tree"));
            context.waitTicks(10);
            context.takeScreenshot("levels_d6_reopened");
            java.nio.file.Path cfg = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("minecraftportsmod-tree.json");
            try {
                log("tree file: {}", java.nio.file.Files.exists(cfg) ? java.nio.file.Files.readString(cfg) : "missing");
            } catch (java.io.IOException e) {
                log("tree file unreadable {}", e.toString());
            }
            context.setScreen(() -> null);

            // ---- E: a building's menu
            server.runOnServer(s -> {
                Village v = village(s);
                for (Building x : v.buildings()) {
                    if (x.type == BuildingType.HOUSE && x.standing()) {
                        ColonyService.handleAction(s.getPlayerList().getPlayers().getFirst(),
                                new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.OPEN, x.id, 0));
                        return;
                    }
                }
            });
            context.waitForScreen(BuildingScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("levels_e1_house_menu");
            context.setScreen(() -> null);
            server.runOnServer(s -> {
                Village v = village(s);
                for (Building x : v.buildings()) {
                    if (x.type == BuildingType.MINE_HOUSE && x.standing()) {
                        ColonyService.handleAction(s.getPlayerList().getPlayers().getFirst(),
                                new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.OPEN, x.id, 0));
                        return;
                    }
                }
            });
            context.waitForScreen(BuildingScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("levels_e2_mine_menu");
            context.setScreen(() -> null);
            // ---- F: night: everyone to bed, the upper floor of the two-storey houses too
            server.runCommand("time set 13500");
            for (int i = 0; i < 4; i++) {
                context.waitTicks(20 * 10);
                final int t = (i + 1) * 10;
                server.runOnServer(s -> {
                    Village v = village(s);
                    int sleeping = 0, all = 0;
                    StringBuilder up = new StringBuilder();
                    for (var e : s.overworld().getEntitiesOfClass(org.webtrade.minecraftportsmod.village.ResidentEntity.class,
                            new net.minecraft.world.phys.AABB(v.center).inflate(90), org.webtrade.minecraftportsmod.village.ResidentEntity::colony)) {
                        all++;
                        if (e.isSleeping()) sleeping++;
                        if (e.getY() > v.center.getY() + 3) up.append(e.getCustomName().getString()).append(e.isSleeping() ? " asleep" : " awake").append(" | ");
                    }
                    log("night t={}s asleep {} of {}; upstairs: {}", t, sleeping, all, up);
                });
            }
            server.runCommand("time set 6000");
            server.runCommand("tp @a 0 -20 50 180 45");
            context.waitTicks(40);
            context.takeScreenshot("levels_99_air");
        }
    }
}
