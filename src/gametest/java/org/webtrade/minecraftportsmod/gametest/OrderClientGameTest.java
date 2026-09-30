package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.client.chart.OrderScreen;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Orders;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Trade;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * Orders at the joiner's workshop: his window lists what he makes by the tree's recipes; the player orders 40
 * stairs (paid at once), the days go by and the order is made from the village's planks, the window shows how far
 * along; the order survives the village being saved and read back; ready, it is taken.
 */
public class OrderClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[orders] " + fmt, args);
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
            for (String c : new String[]{"grow 1 woodcutter", "grow 1 miner", "grow 1 fisher", "grow 1 sawyer", "grow 1 joiner", "grow 1 smith",
                    "build 1 storehouse 3", "build 1 wood_hut 3", "build 1 sawmill 2", "build 1 carpenter 2", "build 1 smithy 2", "build 1 hut 1", "build 1 hut 1"}) {
                server.runCommand("village " + c);
            }
            for (String r : new String[]{"wood 300", "stone 100", "planks 200", "sticks 60", "coal 40", "food 300"}) server.runCommand("village give 1 " + r);
            server.runCommand("village day");
            context.waitTicks(20);

            int[] mill = {-1}, saw = {-1};
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                for (Building b : v.buildings()) if (b.type == BuildingType.CARPENTER) mill[0] = b.id;
                for (Building b : v.buildings()) if (b.type == BuildingType.SAWMILL) saw[0] = b.id;
                var p = s.getPlayerList().getPlayers().getFirst();
                p.getInventory().clearContent();
                p.getInventory().add(new ItemStack(Items.EMERALD, 30));
                p.teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 1, v.center.getZ() + 0.5, java.util.Set.of(), 0, 0, false);
                log("joiner's workshop #{} level {}, joiners {}, recipes {}", mill[0], v.building(mill[0]).level(), v.workers(org.webtrade.minecraftportsmod.colony.Job.JOINER),
                        Orders.recipes(v.building(mill[0])).size());
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.ORDERS, mill[0], 0));
            });
            context.waitForScreen(OrderScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("order_a_window");

            // 40 stairs
            context.runOnClient(mc -> {
                OrderScreen os = (OrderScreen) mc.gui.screen();
                int stairs = os.find("stairs");
                log("stairs recipe {}", stairs);
                os.pick(stairs, 40);
            });
            context.waitTicks(5);
            context.takeScreenshot("order_b_picked");
            context.runOnClient(mc -> ((OrderScreen) mc.gui.screen()).press());
            context.waitTicks(10);
            context.takeScreenshot("order_c_placed");

            int[] planksBefore = {0};
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                var p = s.getPlayerList().getPlayers().getFirst();
                var mine = Orders.of(v, p.getUUID(), -1);
                log("placed: orders {}, player emeralds {}, purse {}, planks {}", mine.size(), Trade.emeralds(p), v.emeralds(), v.stock(Res.PLANKS));
                if (mine.size() != 1 || mine.getFirst().count != 40) throw new AssertionError("expected one order of 40, got " + mine.size());
                planksBefore[0] = v.stock(Res.PLANKS);
                // saved and read back: the order is still there
                var tag = VillageData.CODEC.encodeStart(NbtOps.INSTANCE, VillageData.get(s)).getOrThrow();
                VillageData back = VillageData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
                int kept = Orders.of(back.get(1), p.getUUID(), -1).size();
                log("saved and read back: {} order(s)", kept);
                if (kept != 1) throw new AssertionError("the order was lost in saving");
            });

            for (int day = 1; day <= 6; day++) {
                server.runCommand("village day");
                context.waitTicks(10);
                final int d = day;
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(1);
                    var p = s.getPlayerList().getPlayers().getFirst();
                    var mine = Orders.of(v, p.getUUID(), -1);
                    log("day {}: {} | planks {}", d, mine.isEmpty() ? "none" : mine.getFirst().made() + "/" + mine.getFirst().count + " eta "
                            + Orders.eta(v, mine.getFirst()), v.stock(Res.PLANKS));
                });
                if (day == 2) {
                    server.runOnServer(s -> ColonyService.handleAction(s.getPlayerList().getPlayers().getFirst(),
                            new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.ORDERS, mill[0], 0)));
                    context.waitTicks(10);
                    context.takeScreenshot("order_d_under_way");
                }
            }
            server.runOnServer(s -> ColonyService.handleAction(s.getPlayerList().getPlayers().getFirst(),
                    new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.ORDERS, mill[0], 0)));
            context.waitTicks(10);
            context.takeScreenshot("order_e_ready");
            server.runOnServer(s -> ColonyService.handleAction(s.getPlayerList().getPlayers().getFirst(),
                    new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.COLLECT, mill[0], 0)));
            context.waitTicks(10);
            context.takeScreenshot("order_f_taken");
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                Village v = VillageData.get(s).get(1);
                int stairs = 0;
                var inv = p.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).getItem().toString().contains("stairs")) stairs += inv.getItem(i).getCount();
                log("taken: stairs {}, orders left {}", stairs, Orders.of(v, p.getUUID(), -1).size());
                if (stairs != 40) throw new AssertionError("expected 40 stairs, got " + stairs);
            });
            // bought now: 12 planks from what the village has to spare (it keeps what its own plans need: planks
            // over that are given first, so there is something to spare whatever it is building just now)
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                int keep = org.webtrade.minecraftportsmod.colony.VillageLife.target(v, Res.PLANKS);
                log("planks {}, kept for the village's own needs {}", v.stock(Res.PLANKS), keep);
                int more = keep + 40 - v.stock(Res.PLANKS);
                if (more > 0) org.webtrade.minecraftportsmod.colony.VillageManager.give(s, v, Res.PLANKS, more);
            });
            server.runOnServer(s -> ColonyService.handleAction(s.getPlayerList().getPlayers().getFirst(),
                    new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.ORDERS, saw[0], 0)));
            context.waitTicks(10);
            context.runOnClient(mc -> {
                OrderScreen os = (OrderScreen) mc.gui.screen();
                os.pick(os.find("planks"), 12);
            });
            context.waitTicks(5);
            context.takeScreenshot("order_f2_buy_now");
            context.runOnClient(mc -> ((OrderScreen) mc.gui.screen()).pressBuy());
            context.waitTicks(10);
            context.takeScreenshot("order_f3_bought");
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                int planks = 0;
                var inv = p.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).getItem().toString().contains("planks")) planks += inv.getItem(i).getCount();
                log("bought now: planks {}", planks);
                if (planks != 12) throw new AssertionError("expected 12 planks bought now, got " + planks);
            });
            context.setScreen(() -> null);

            // the other trades: a woodcutter (the Trade button of his window), and the smith
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                for (var e : s.overworld().getEntitiesOfClass(org.webtrade.minecraftportsmod.village.ResidentEntity.class, p.getBoundingBox().inflate(80),
                        org.webtrade.minecraftportsmod.village.ResidentEntity::colony)) {
                    if (e.colonyJob() == org.webtrade.minecraftportsmod.colony.Job.WOODCUTTER) {
                        ColonyService.openDweller(p, e);
                        break;
                    }
                }
            });
            context.waitTicks(20);
            context.takeScreenshot("order_g_person");
            context.setScreen(() -> null);
            int[] ids = {-1, -1};
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                var p = s.getPlayerList().getPlayers().getFirst();
                p.getInventory().add(new ItemStack(Items.EMERALD, 30));
                for (Building b : v.buildings()) {
                    if (b.type == BuildingType.WOOD_HUT) ids[0] = b.id;
                    if (b.type == BuildingType.SMITHY) ids[1] = b.id;
                }
                order(p, v, ids[0], "oak_log", 32);
                order(p, v, ids[1], "stone_pickaxe", 4);
                log("ordered logs and picks: orders {}", Orders.of(v, p.getUUID(), -1).size());
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.ORDERS, ids[1], 0));
            });
            context.waitForScreen(OrderScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("order_h_smithy");
            context.setScreen(() -> null);
            for (int day = 1; day <= 4; day++) {
                server.runCommand("village day");
                context.waitTicks(10);
                final int d = day;
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(1);
                    var p = s.getPlayerList().getPlayers().getFirst();
                    StringBuilder b = new StringBuilder();
                    for (var o : Orders.of(v, p.getUUID(), -1)) b.append(o.made()).append('/').append(o.count).append(' ');
                    log("day {}: {}| wood {} stone {}", d, b, v.stock(Res.WOOD), v.stock(Res.STONE));
                });
            }
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                Village v = VillageData.get(s).get(1);
                for (int id : ids) ColonyService.handleAction(p, new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.COLLECT, id, 0));
                int logs = 0, picks = 0;
                var inv = p.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    if (inv.getItem(i).is(Items.OAK_LOG)) logs += inv.getItem(i).getCount();
                    if (inv.getItem(i).is(Items.STONE_PICKAXE)) picks += inv.getItem(i).getCount();
                }
                log("taken: logs {}, stone picks {}, orders left {}", logs, picks, Orders.of(v, p.getUUID(), -1).size());
                if (logs != 32 || picks != 4) throw new AssertionError("expected 32 logs and 4 stone picks, got " + logs + " and " + picks);
            });
            context.setScreen(() -> null);
        }
    }

    /** Orders so many pieces of an item (by its id in the tree) at a building, as the window would. */
    private static void order(net.minecraft.server.level.ServerPlayer p, Village v, int building, String item, int n) {
        for (var r : Orders.recipes(v.building(building))) {
            if (item.equals(r.item())) {
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.ORDER, building, r.index() * 1000 + n));
                return;
            }
        }
        throw new AssertionError("no recipe for " + item);
    }
}
