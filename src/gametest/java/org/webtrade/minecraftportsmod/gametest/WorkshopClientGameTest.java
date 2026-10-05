package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.client.chart.OrderScreen;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Orders;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.colony.Workshops;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * The workshops' queues, in the test village ("/village sandbox"): a player orders twenty fences at the joiner's; the
 * joiner at his bench, the bar going on only while he is at it, the fences made one making at a time from the
 * village's planks and sticks, the tools in the slot worn by a use a making; the player's own tools put in the slot;
 * the village's own orders for what it keeps; the window with the queue, the bar and the tools.
 */
public class WorkshopClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[workshop] " + fmt, args);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("4242")).create()) {
            var server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("weather clear");
            // somewhere flat and dry away from the planned villages
            server.runCommand("tp @a 300 100 -300");
            sp.getConnection().waitForChunksRender();
            server.runCommand("spreadplayers 300 -300 0 40 false @a");
            sp.getConnection().waitForChunksRender();
            context.waitTicks(40);
            server.runCommand("execute as @p at @p run village sandbox");
            context.waitTicks(20);
            int[] ids = {-1, -1, -1};
            server.runOnServer(s -> {
                Village v = null;
                for (Village x : VillageData.get(s).all()) if (x.name.contains("Тест") || x.name.contains("Test")) v = x;
                if (v == null) throw new AssertionError("no test village");
                ids[0] = v.id;
                log("test village #{} {}: {} buildings, {} people", v.id, v.name, v.buildings().size(), v.population());
                for (Building b : v.buildings()) {
                    if (b.type == BuildingType.CARPENTER && ids[1] < 0) ids[1] = b.id;
                    log("  {}#{} level {} at {}", b.type.id(), b.id, b.level(), b.origin.toShortString());
                }
                // work time (a village day is the world's day)
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "time set 3000");
                var p = s.getPlayerList().getPlayers().getFirst();
                p.getInventory().add(new ItemStack(Items.EMERALD, 64));
            });
            if (ids[1] < 0) throw new AssertionError("no joiner's in the test village");
            context.waitTicks(20 * 20);
            // twenty fences ordered
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(ids[0]);
                var p = s.getPlayerList().getPlayers().getFirst();
                Building b = v.building(ids[1]);
                int fence = -1;
                for (var r : Orders.recipes(b)) if (r.out() == Res.FENCE) fence = r.index();
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(v.id, ColonyPayloads.VillageAction.ORDER, b.id, fence * 1000 + 20));
                log("ordered: {}", Workshops.describe(v));
                if (Orders.of(v, p.getUUID(), b.id).isEmpty()) throw new AssertionError("the order was not taken");
                // stand by the joiner's
                p.teleportTo(b.origin.getX() + 6.5, b.origin.getY() + 3, b.origin.getZ() + 6.5);
            });
            sp.getConnection().waitForChunksRender();
            double[] last = {-1};
            int moved = 0;
            for (int k = 0; k < 12; k++) {
                context.waitTicks(20 * 5);
                double[] pr = new double[2];
                final int kk = k;
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(ids[0]);
                    Building b = v.building(ids[1]);
                    double[] p = Workshops.progress(v, b);
                    pr[0] = p[1];
                    var mine = Orders.of(v, s.getPlayerList().getPlayers().getFirst().getUUID(), b.id);
                    pr[1] = mine.isEmpty() ? 20 : mine.getFirst().made();
                    StringBuilder joiners = new StringBuilder();
                    var spot = b.origin;
                    for (var e : s.overworld().getEntitiesOfClass(org.webtrade.minecraftportsmod.village.ResidentEntity.class, new net.minecraft.world.phys.AABB(spot).inflate(60),
                            e -> e.colony() && e.colonyJob() == org.webtrade.minecraftportsmod.colony.Job.JOINER)) {
                        joiners.append(e.getName().getString()).append(" ").append((int) Math.sqrt(e.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(spot)))).append(" from the bench, ")
                                .append(e.activity().getString()).append("; ");
                    }
                    log("{} s: made {}/20, bar {}, left {} s, tools {}/{}/{}, planks {} sticks {}, day time {} | joiners: {}", (kk + 1) * 5, (int) pr[1],
                            Math.round(p[1] * 100), (int) p[0], b.tools()[0], b.tools()[1], b.tools()[2], v.stock(Res.PLANKS), v.stock(Res.STICKS),
                            VillageData.get(s).dayTicks(), joiners);
                });
                if (pr[1] > last[0]) moved++;
                last[0] = pr[1];
                if (pr[1] >= 20) break;
            }
            // the window: the queue, the bar, the tools
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(ids[0], ColonyPayloads.VillageAction.ORDERS, ids[1], 0));
            });
            context.waitForScreen(OrderScreen.class);
            context.waitTicks(30);
            context.takeScreenshot("workshop_window");
            context.setScreen(() -> null);
            // the player's tools in the slot
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(ids[0]);
                var p = s.getPlayerList().getPlayers().getFirst();
                Building b = v.building(ids[1]);
                int before = b.tools()[2];
                p.getInventory().add(new ItemStack(Items.IRON_AXE, 1));
                p.getInventory().add(new ItemStack(Items.IRON_PICKAXE, 1));
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(v.id, ColonyPayloads.VillageAction.TOOLS, b.id, 0));
                log("iron tools in the slot: {} -> {}", before, b.tools()[2]);
                if (b.tools()[2] < before + 2 * Workshops.USES[3]) throw new AssertionError("the player's tools were not put in the slot");
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(v.id, ColonyPayloads.VillageAction.COLLECT, b.id, 0));
                int fences = 0;
                for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                    if (p.getInventory().getItem(i).getItem().toString().contains("fence")) fences += p.getInventory().getItem(i).getCount();
                }
                log("fences taken: {}", fences);
            });
            // a day: the village's own orders for what it keeps
            server.runCommand("village day");
            context.waitTicks(40);
            server.runOnServer(s -> log("after a day: {}", Workshops.describe(VillageData.get(s).get(ids[0]))));
            server.runCommand("tp @a ~ ~10 ~");
            context.waitTicks(40);
            context.takeScreenshot("workshop_village");
            if (moved < 2) throw new AssertionError("the order did not move on (made " + (int) last[0] + ")");
        }
    }
}
