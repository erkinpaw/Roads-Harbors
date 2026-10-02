package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.phys.AABB;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.client.chart.VillageScreen;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.colony.VillageLife;

/**
 * The branches: the farmyard (its runs of real hens, sheep, pigs and cows, its herders), the weaver's cloth, the
 * smelter's iron, the glassworks' glass, the locksmith's metalware and the joiner's furniture; what each makes and
 * uses in a few days.
 */
public class BranchesClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[branches] " + fmt, args);
    }

    private static Village village(MinecraftServer s) {
        return VillageData.get(s).get(1);
    }

    private static String stocks(Village v) {
        StringBuilder b = new StringBuilder();
        for (Res r : new Res[]{Res.FOOD, Res.WHEAT, Res.WOOL, Res.CLOTH, Res.LEATHER, Res.IRON, Res.COAL, Res.GLASS, Res.METALWARE, Res.PLANKS, Res.FURNITURE, Res.JOINERY}) {
            b.append(r.id()).append(' ').append(v.stock(r)).append(" (+").append(v.made(r)).append(" -").append(v.used(r)).append(") ");
        }
        return b.toString();
    }

    private static String herds(MinecraftServer s, Village v) {
        StringBuilder b = new StringBuilder();
        for (Building x : v.buildings()) {
            if (!x.type.isPen()) continue;
            var list = herd(s, v, x);
            int sheared = 0, young = 0;
            java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
            for (Animal a : list) {
                if (a instanceof Sheep sh && sh.isSheared()) sheared++;
                if (a.isBaby()) young++;
                kinds.merge(a.getType().toShortString(), 1, Integer::sum);
            }
            b.append(x.type.id()).append(" L").append(x.level()).append(": ").append(list.size()).append(" animals ").append(kinds).append(" (").append(young)
                    .append(" young, ").append(sheared).append(" shorn); ");
        }
        return b.toString();
    }

    private static java.util.List<Animal> herd(MinecraftServer s, Village v, Building x) {
        String prefix = "mpm_herd_" + v.id + "_" + x.id + "_";
        return s.overworld().getEntitiesOfClass(Animal.class, new AABB(x.origin).inflate(40, 16, 40),
                a -> a.entityTags().stream().anyMatch(t -> t.startsWith(prefix)));
    }

    /** The kinds of animals in the farmyards. */
    private static java.util.Set<String> kinds(MinecraftServer s, Village v) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (Building x : v.buildings()) if (x.type.isPen()) for (Animal a : herd(s, v, x)) out.add(a.getType().toShortString());
        return out;
    }

    private static int animals(MinecraftServer s, Village v) {
        int n = 0;
        for (Building x : v.buildings()) if (x.type.isPen()) n += herd(s, v, x).size();
        return n;
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("time set 2000");
            server.runCommand("tp @a 0 -60 0 180 0");
            context.waitTicks(20);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(40);
            server.runCommand("village build 1 storehouse_2 3");
            for (String g : new String[]{"wood 900", "stone 900", "planks 400", "sticks 100", "joinery 200", "iron 120", "coal 100", "wheat 300", "food 600"}) {
                server.runCommand("village give 1 " + g);
            }
            for (String b : new String[]{"hut 1", "hut 1", "hut 1", "field 1", "farm 1", "smithy 1", "mine_house 2", "locksmith 1", "wood_hut 1",
                    "sawmill 1", "carpenter 2", "farmyard 3", "weaver 1", "smelter 1", "glassworks 1"}) {
                server.runCommand("village build 1 " + b);
            }
            for (String j : new String[]{"farmer", "farmer", "herder", "herder", "herder", "locksmith", "joiner", "smith", "miner", "miner", "woodcutter",
                    "weaver", "smelter", "glassblower"}) {
                server.runCommand("village grow 1 " + j);
            }
            server.runOnServer(s -> {
                Village v = village(s);
                StringBuilder b = new StringBuilder();
                for (Building x : v.buildings()) b.append(x.type.id()).append(" L").append(x.level()).append(" at ").append(x.origin.toShortString()).append("; ");
                log("built: {}", b);
                log("people: {}", v.population());
            });
            server.runCommand("tp @a 0 -35 30 180 50");
            context.waitTicks(300);
            sp.getConnection().waitForChunksRender();
            server.runOnServer(s -> log("herds after stocking: {}", herds(s, village(s))));
            context.takeScreenshot("branches_a_overview");
            // the runs close up
            int[][] pens = new int[3][];
            server.runOnServer(s -> {
                int i = 0;
                for (Building x : village(s).buildings()) if (x.type.isPen() && i < 3) pens[i++] = new int[]{x.origin.getX(), x.origin.getY(), x.origin.getZ()};
            });
            for (int i = 0; i < 3; i++) {
                if (pens[i] == null) continue;
                final int[] p = pens[i];
                server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), p[0] + 11.5, p[1] + 9, p[2] + 11.5,
                        java.util.Set.of(), 135, 35, false));
                context.waitTicks(60);
                sp.getConnection().waitForChunksRender();
                context.takeScreenshot("branches_b_pen" + i);
            }
            // the herders at work, watched for two minutes
            server.runCommand("tp @a 0 -35 30 180 50");
            for (int k = 0; k < 6; k++) {
                context.waitTicks(400);
                server.runOnServer(s -> {
                    Village v = village(s);
                    StringBuilder b = new StringBuilder();
                    for (var e : s.overworld().getEntitiesOfClass(org.webtrade.minecraftportsmod.village.ResidentEntity.class,
                            new AABB(v.center).inflate(60), org.webtrade.minecraftportsmod.village.ResidentEntity::colony)) {
                        if (e.colonyJob() == org.webtrade.minecraftportsmod.colony.Job.HERDER || e.colonyJob() == org.webtrade.minecraftportsmod.colony.Job.LOCKSMITH) {
                            b.append(e.getName().getString()).append(" @").append(e.blockPosition().toShortString()).append(' ')
                                    .append(e.activity().getString()).append("; ");
                        }
                    }
                    log("watch: {} | {}", herds(s, v), b);
                });
                if (k == 2) context.takeScreenshot("branches_c_herding");
            }
            // a few days: what is made and used
            for (int day = 1; day <= 6; day++) {
                server.runCommand("village day");
                context.waitTicks(40);
                final int d = day;
                server.runOnServer(s -> log("day {}: {}", d, stocks(village(s))));
            }
            server.runOnServer(s -> {
                Village v = village(s);
                for (Building x : v.buildings()) {
                    if (!x.type.isPen() && x.type.job == null) continue;
                    int[][] f = VillageLife.flows(v, x);
                    StringBuilder b = new StringBuilder();
                    for (Res r : Res.values()) {
                        if (f[0][r.ordinal()] > 0) b.append('+').append(r.id()).append(' ').append(f[0][r.ordinal()] / 10.0).append(' ');
                        if (f[1][r.ordinal()] > 0) b.append('-').append(r.id()).append(' ').append(f[1][r.ordinal()] / 10.0).append(' ');
                    }
                    log("flows {}: {}", x.type.id(), b);
                }
                for (var l : v.log()) log("log {}: {}", l.day(), l.text().getString());
                log("herds at the end: {}", herds(s, v));
                if (animals(s, v) == 0) throw new AssertionError("no animals in the runs");
                log("kinds of animals: {}", kinds(s, v));
                if (kinds(s, v).size() < 4) throw new AssertionError("a farmyard at level 3 keeps hens, sheep, pigs and cows: " + kinds(s, v));
                if (v.made(Res.CLOTH) == 0 && v.stock(Res.CLOTH) == 0) throw new AssertionError("no cloth");
                if (v.stock(Res.GLASS) == 0) throw new AssertionError("no glass");
                if (v.stock(Res.WOOL) + v.used(Res.WOOL) == 0 && v.made(Res.WOOL) == 0) throw new AssertionError("no wool");
                if (v.stock(Res.METALWARE) == 0) throw new AssertionError("no metalware");
                if (v.stock(Res.FURNITURE) == 0) throw new AssertionError("no furniture");
            });
            // the tree with the new nodes, and the store
            context.runOnClient(mc -> mc.options.guiScale().set(2));
            context.getInput().resizeWindow(1280, 720);
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                Village v = village(s);
                ColonyService.openBoard(p, v.board);
            });
            context.waitForScreen(VillageScreen.class);
            context.waitTicks(20);
            context.runOnClient(mc -> ((VillageScreen) mc.gui.screen()).showTab("tree"));
            context.waitTicks(10);
            context.takeScreenshot("branches_d_tree");
            context.runOnClient(mc -> ((VillageScreen) mc.gui.screen()).showTab("store"));
            context.waitTicks(10);
            context.takeScreenshot("branches_e_store");
            context.setScreen(() -> null);
        }
    }
}
