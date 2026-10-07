package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.client.chart.GiveScreen;
import org.webtrade.minecraftportsmod.client.chart.JobScreen;
import org.webtrade.minecraftportsmod.client.chart.QuestScreen;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Helping;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.colony.Quests;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * The player's part in a village: each person offers a few tasks, all of what the village needs now; what the player
 * carries given where they choose (a site, the store); a trade chosen from a list, the most wanted first, its day's
 * work going to the store as it is picked up; at the smithy's anvil, crouching, the bench (never the anvil's own use).
 */
public class RoleClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[role] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static Village village(MinecraftServer s) {
        return VillageData.get(s).get(1);
    }

    private static void act(MinecraftServer s, int kind, int a, int b) {
        ColonyService.handleAction(player(s), new ColonyPayloads.VillageAction(1, kind, a, b));
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
            server.runCommand("tp @a 0 -60 -10 0 0");
            context.waitTicks(40);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(20);
            for (String c : new String[]{"grow 1 woodcutter", "grow 1 miner", "grow 1 farmer", "build 1 storehouse 1", "build 1 smithy 1"}) {
                server.runCommand("village " + c);
            }
            server.runCommand("village give 1 food 300");
            server.runCommand("village give 1 sticks 40");
            server.runCommand("village day");
            context.waitTicks(120);

            // 1. the head of the village: a few tasks to choose from, every one of what the village needs now
            server.runOnServer(s -> {
                Village v = village(s);
                v.tasks().quests().clear();
                ColonyService.sendQuest(player(s), v, v.elder());
                var all = Quests.all(v, v.elder().id);
                StringBuilder what = new StringBuilder();
                for (var q : all) what.append(q.kind).append(' ').append(q.what).append(' ').append(q.count).append("; ");
                log("the head offers {}: {}", all.size(), what);
                if (all.isEmpty()) throw new AssertionError("the head of the village offers nothing");
                for (var q : all) {
                    if (q.kind == Quests.Kind.ITEM || q.kind == Quests.Kind.HUNT) throw new AssertionError("a task of no need now: " + q.kind);
                }
            });
            context.waitForScreen(QuestScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("role_a_tasks");
            int[] taken = {0};
            server.runOnServer(s -> {
                Village v = village(s);
                for (var q : Quests.all(v, v.elder().id)) {
                    act(s, ColonyPayloads.VillageAction.QUEST_TAKE, q.id, 0);
                    taken[0]++;
                    break;
                }
                ColonyService.sendQuest(player(s), v, v.elder());
            });
            context.waitTicks(10);
            context.takeScreenshot("role_b_task_taken");
            context.setScreen(() -> null);

            // 2. giving: logs to the hut's site, the rest stays with the player
            int[] siteId = {-1};
            server.runOnServer(s -> {
                Village v = village(s);
                var p = player(s);
                Building b = org.webtrade.minecraftportsmod.colony.VillageManager.siteNow(s.overworld(), v, BuildingType.HUT);
                if (b != null) siteId[0] = b.id;
                p.getInventory().clearContent();
                p.getInventory().add(new ItemStack(Items.OAK_LOG, 64));
                p.getInventory().add(new ItemStack(Items.OAK_PLANKS, 32));
                p.getInventory().add(new ItemStack(Items.COBBLESTONE, 40));
                p.getInventory().add(new ItemStack(Items.WHEAT, 20));
                act(s, ColonyPayloads.VillageAction.DEPOSIT, v.elder().id, 0);
            });
            context.waitForScreen(GiveScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("role_c_give");
            server.runOnServer(s -> {
                Village v = village(s);
                var p = player(s);
                Building b = siteId[0] < 0 ? null : v.building(siteId[0]);
                int stone = v.stock(Res.STONE);
                act(s, ColonyPayloads.VillageAction.GIVE, -1, Res.STONE.ordinal() * 100000 + 10);
                log("store: stone {} -> {}, cobble left {}", stone, v.stock(Res.STONE), p.getInventory().countItem(Items.COBBLESTONE));
                if (v.stock(Res.STONE) != stone + 10 || p.getInventory().countItem(Items.COBBLESTONE) != 30) throw new AssertionError("ten stone to the store");
                if (b != null) {
                    Res r = null;
                    for (Res x : Res.values()) if (b.missing(x) > 0 && Helping.carried(p, x) > 0) r = x;
                    if (r != null) {
                        int before = b.missing(r);
                        act(s, ColonyPayloads.VillageAction.GIVE, b.id, r.ordinal() * 100000 + 5);
                        log("site {}: {} missing {} -> {}", b.type.id(), r, before, b.missing(r));
                        if (b.missing(r) != Math.max(0, before - 5)) throw new AssertionError("five to the site");
                    }
                }
            });
            context.waitTicks(10);
            context.takeScreenshot("role_d_given");
            context.setScreen(() -> null);

            // 3. a trade: the list, the most wanted first; the woodcutter's logs picked up go to the store
            server.runOnServer(s -> {
                Village v = village(s);
                act(s, ColonyPayloads.VillageAction.JOBS, v.elder().id, 0);
                StringBuilder all = new StringBuilder();
                for (Job j : Helping.jobs(v)) all.append(j.id()).append(String.format(" %.2f; ", Helping.demand(v, j)));
                log("trades: {}", all);
            });
            context.waitForScreen(JobScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("role_e_jobs");
            int[] wood = {0};
            server.runOnServer(s -> {
                Village v = village(s);
                var p = player(s);
                act(s, ColonyPayloads.VillageAction.HIRE, v.elder().id, Job.WOODCUTTER.ordinal());
                if (Helping.hired(v, p.getUUID()) != Job.WOODCUTTER) throw new AssertionError("not hired");
                p.getInventory().clearContent();
                wood[0] = v.stock(Res.WOOD);
                ItemEntity e = new ItemEntity(s.overworld(), p.getX(), p.getY() + 0.5, p.getZ(), new ItemStack(Items.SPRUCE_LOG, 6));
                e.setNoPickUpDelay();
                s.overworld().addFreshEntity(e);
                ColonyService.sendJobs(p, v, v.elder().id);
            });
            context.waitTicks(30);
            context.takeScreenshot("role_f_hired");
            server.runOnServer(s -> {
                Village v = village(s);
                var p = player(s);
                log("picked up: wood {} -> {}, logs carried {}, brought {}/{}", wood[0], v.stock(Res.WOOD), p.getInventory().countItem(Items.SPRUCE_LOG),
                        Helping.brought(v, p.getUUID()), Helping.quota(v, Job.WOODCUTTER));
                if (p.getInventory().countItem(Items.SPRUCE_LOG) != 0 || Helping.brought(v, p.getUUID()) != 6) throw new AssertionError("the logs should go to the store");
            });
            context.setScreen(() -> null);

            // 4. the smithy's anvil, crouching: the bench (a bar), not the anvil's screen
            boolean[] bench = {false};
            server.runOnServer(s -> {
                Village v = village(s);
                var p = player(s);
                Building smithy = null;
                for (Building b : v.buildings()) if (b.type == BuildingType.SMITHY && b.standing()) smithy = b;
                if (smithy == null) throw new AssertionError("no smithy");
                p.teleportTo(s.overworld(), smithy.origin.getX() + 0.5, smithy.origin.getY() + 1, smithy.origin.getZ() + smithy.type.half + 2.5,
                        java.util.Set.of(), 180, 30, false);
                p.setShiftKeyDown(true);
                bench[0] = Helping.bench(p, s.overworld(), smithy.origin);
                p.setShiftKeyDown(false);
                log("crouching at the smithy: bench {}", bench[0]);
            });
            // a few strokes at the bench: the making goes on
            for (int k = 0; k < 40; k++) {
                server.runOnServer(s -> {
                    Village v = village(s);
                    var p = player(s);
                    for (Building b : v.buildings()) {
                        if (b.type != BuildingType.SMITHY) continue;
                        p.setShiftKeyDown(true);
                        Helping.bench(p, s.overworld(), b.origin);
                        p.setShiftKeyDown(false);
                    }
                });
                context.waitTicks(4);
            }
            context.takeScreenshot("role_g_smithy_bench");
            if (!bench[0]) throw new AssertionError("crouching at the smithy should be its bench");

            // 5. a hut of the player's own: bought in the tree, supplied and built by them; the village keeps off it
            server.runOnServer(s -> {
                org.webtrade.minecraftportsmod.colony.Wallet.add(player(s), 50);
                ColonyService.openBoard(player(s), village(s).board);
            });
            context.waitForScreen(org.webtrade.minecraftportsmod.client.chart.VillageScreen.class);
            context.waitTicks(20);
            context.runOnClient(mc -> ((org.webtrade.minecraftportsmod.client.chart.VillageScreen) mc.gui.screen()).showTab("tree"));
            context.waitTicks(5);
            context.runOnClient(mc -> ((org.webtrade.minecraftportsmod.client.chart.VillageScreen) mc.gui.screen()).select(BuildingType.HUT.ordinal()));
            context.waitTicks(10);
            context.takeScreenshot("role_h_tree_buy");
            int[] mine = {-1};
            server.runOnServer(s -> {
                Village v = village(s);
                var p = player(s);
                long purse = org.webtrade.minecraftportsmod.colony.Wallet.cents(p);
                act(s, ColonyPayloads.VillageAction.BUY_BUILD, BuildingType.HUT.ordinal(), 0);
                for (Building b : v.buildings()) if (p.getUUID().equals(b.builder())) mine[0] = b.id;
                log("bought a hut: site {}, purse {} -> {}", mine[0], purse, org.webtrade.minecraftportsmod.colony.Wallet.cents(p));
                if (mine[0] < 0) throw new AssertionError("no site of the player's");
            });
            context.setScreen(() -> null);
            server.runCommand("village give 1 wood 300");
            server.runCommand("village give 1 wheat 100");
            server.runCommand("village day");
            context.waitTicks(40);
            server.runOnServer(s -> {
                Village v = village(s);
                Building b = v.building(mine[0]);
                int got = 0;
                for (Res r : Res.values()) got += b.delivered(r);
                log("after a day: the village brought {} to the player's site ({})", got, b.state());
                if (got != 0) throw new AssertionError("the village supplied the player's site");
                var p = player(s);
                p.getInventory().clearContent();
                for (Res r : Res.values()) {
                    int k = b.missing(r);
                    while (k > 0) {
                        int n = Math.min(64, k);
                        p.getInventory().add(new ItemStack(r.icon, n));
                        k -= n;
                    }
                }
                act(s, ColonyPayloads.VillageAction.DEPOSIT, v.elder().id, 0);
            });
            context.waitForScreen(GiveScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("role_i_give_own_site");
            server.runOnServer(s -> {
                Village v = village(s);
                Building b = v.building(mine[0]);
                for (Res r : Res.values()) if (b.missing(r) > 0) act(s, ColonyPayloads.VillageAction.GIVE, b.id, r.ordinal() * 100000 + b.missing(r));
                log("supplied by the player: {}", b.state());
                if (b.state() != Building.State.BUILDING) throw new AssertionError("the site should go up once supplied");
                var p = player(s);
                p.teleportTo(s.overworld(), b.origin.getX() + 0.5, b.origin.getY() + 1, b.origin.getZ() + b.type.half + 3.5, java.util.Set.of(), 180, 30, false);
            });
            context.setScreen(() -> null);
            for (int k = 0; k < 60; k++) {
                server.runOnServer(s -> {
                    Village v = village(s);
                    Building b = v.building(mine[0]);
                    var p = player(s);
                    p.setShiftKeyDown(true);
                    for (int j = 0; j < 4 && b.state() == Building.State.BUILDING; j++) Helping.build(p, s.overworld(), b.origin);
                    p.setShiftKeyDown(false);
                });
                context.waitTicks(2);
            }
            context.takeScreenshot("role_j_own_hut");
            server.runOnServer(s -> {
                Village v = village(s);
                Building b = v.building(mine[0]);
                org.webtrade.minecraftportsmod.colony.VillageLife.class.getName();
                log("the player's hut: {}, builder {}", b.state(), b.builder());
            });
            server.runCommand("village day");
            context.waitTicks(20);
            server.runOnServer(s -> {
                Village v = village(s);
                Building b = v.building(mine[0]);
                log("next day: the player's hut {}", b.state());
                if (b.state() != Building.State.BUILT) throw new AssertionError("the hut should stand");
                for (Building x : v.buildings()) if (x.type == BuildingType.STOREHOUSE && x.standing()) ColonyService.sendBuilding(player(s), v, x);
            });
            context.waitTicks(20);
            context.takeScreenshot("role_k_raise_buy");
        }
    }
}
