package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
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
                // (the joiner's workshop its joiners live at, if one: they work at their own)
                for (var d : v.dwellers()) {
                    Building home = v.building(d.home());
                    if (d.job() == org.webtrade.minecraftportsmod.colony.Job.JOINER && home != null && home.type == BuildingType.CARPENTER) ids[1] = home.id;
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
                if (kk == 2) {
                    context.setScreen(() -> null);
                    // the badges over the joiner at the bench and the workshop: seen from a little way off, above
                    server.runOnServer(s -> {
                        Village v = VillageData.get(s).get(ids[0]);
                        Building b = v.building(ids[1]);
                        var spot = b.origin;
                        var bd = org.webtrade.minecraftportsmod.colony.Badges.badge(v, b);
                        s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "gamemode spectator @a");
                        s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), String.format(java.util.Locale.ROOT,
                                "tp @a %.1f %.1f %.1f facing %.1f %.1f %.1f", bd.x() + 4, bd.y() - 1, bd.z() + 4, bd.x(), bd.y() - 2, bd.z()));
                        // (the trees' crowns out of the way)
                        int x = (int) bd.x(), y = (int) bd.y(), z = (int) bd.z();
                        s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), String.format("fill %d %d %d %d %d %d air replace #minecraft:leaves",
                                x - 4, y - 8, z - 4, x + 10, y + 6, z + 10));
                    });
                    context.waitTicks(30);
                    var badges = context.computeOnClient(mc -> org.webtrade.minecraftportsmod.client.render.BuildingBadges.badges());
                    context.runOnClient(mc -> log("camera at {}", mc.player.position()));
                    for (var bd : badges) log("badge: {} | {} | ring {} at {} {} {}", bd.title().getString(), bd.doing().getString(), Math.round(bd.progress() * 100), bd.x(), bd.y(), bd.z());
                    if (badges.isEmpty()) throw new AssertionError("no building badges");
                    context.takeScreenshot("workshop_badges");
                    // the building looked at marked out, each way there is, to choose from (seen from a little way off)
                    server.runOnServer(s -> {
                        Village v = VillageData.get(s).get(ids[0]);
                        var o = v.building(ids[1]).origin;
                        s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), String.format(java.util.Locale.ROOT,
                                "tp @a %.1f %.1f %.1f facing %.1f %.1f %.1f", o.getX() + 13.5, o.getY() + 7.0, o.getZ() + 13.5, o.getX() + 0.5,
                                o.getY() + 1.5, o.getZ() + 0.5));
                    });
                    context.waitTicks(30);
                    for (int m = 1; m <= 5; m++) {
                        final int mm = m;
                        context.runOnClient(mc -> org.webtrade.minecraftportsmod.client.render.BuildingHighlight.mode = mm);
                        context.waitTicks(30);
                        context.takeScreenshot("workshop_highlight_" + m);
                    }
                    context.runOnClient(mc -> org.webtrade.minecraftportsmod.client.render.BuildingHighlight.mode = 2);
                    // the joiner at the bench, close by
                    server.runOnServer(s -> {
                        Village v = VillageData.get(s).get(ids[0]);
                        var spot = v.building(ids[1]).origin;
                        var at = s.overworld().getEntitiesOfClass(org.webtrade.minecraftportsmod.village.ResidentEntity.class,
                                new net.minecraft.world.phys.AABB(spot).inflate(12), e -> e.colony() && e.colonyJob() == org.webtrade.minecraftportsmod.colony.Job.JOINER);
                        if (at.isEmpty()) return;
                        var c = at.getFirst().position();
                        s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), String.format(java.util.Locale.ROOT,
                                "tp @a %.1f %.1f %.1f facing %.1f %.1f %.1f", c.x + 2.5, c.y + 1.6, c.z + 2.5, c.x, c.y + 2.2, c.z));
                        log("joiner at the bench: progress {}", at.getFirst().progress());
                    });
                    context.waitTicks(20);
                    context.takeScreenshot("workshop_joiner");
                    server.runCommand("gamemode survival @a");
                }
                if (pr[1] > last[0]) moved++;
                last[0] = pr[1];
                if (pr[1] >= 20) break;
            }
            // the block of the joiner's trade at its front (no name plate): a click on it opens the building's menu
            boolean[] key = {false};
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(ids[0]);
                Building b = v.building(ids[1]);
                // (round the building: the crafting table a click on which opens its menu - not one inside it)
                var p = s.getPlayerList().getPlayers().getFirst();
                int r = b.type.half + 2;
                BlockPos at = null;
                for (BlockPos q : BlockPos.betweenClosed(b.origin.offset(-r, -4, -r), b.origin.offset(r, 4, r))) {
                    if (!s.overworld().getBlockState(q).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) continue;
                    if (ColonyService.openKey(p, q.immutable())) {
                        at = q.immutable();
                        break;
                    }
                }
                boolean onPath = at != null && s.overworld().getBlockState(at.below()).is(net.minecraft.world.level.block.Blocks.DIRT_PATH);
                log("the joiner's crafting table at its front: {}{}", at == null ? "none" : at.toShortString(), onPath ? " ON A PATH" : "");
                key[0] = at != null && !onPath;
            });
            if (!key[0]) throw new AssertionError("no crafting table at the joiner's front opening its menu");
            context.waitForScreen(org.webtrade.minecraftportsmod.client.chart.BuildingScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("workshop_key_menu");
            context.setScreen(() -> null);
            // the window: the queue, the bar, the tools
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(ids[0], ColonyPayloads.VillageAction.ORDERS, ids[1], 0));
            });
            context.waitForScreen(OrderScreen.class);
            context.waitTicks(30);
            context.takeScreenshot("workshop_window");
            context.setScreen(() -> null);
            // tasks taken, for the inventory's panel to show
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                var data = VillageData.get(s);
                Village v = data.get(ids[0]);
                var kinds = new org.webtrade.minecraftportsmod.colony.Quests.Kind[]{org.webtrade.minecraftportsmod.colony.Quests.Kind.BRING,
                        org.webtrade.minecraftportsmod.colony.Quests.Kind.ITEM, org.webtrade.minecraftportsmod.colony.Quests.Kind.HUNT};
                int k = 0;
                for (var d : v.dwellers()) {
                    if (k == kinds.length) break;
                    var q = org.webtrade.minecraftportsmod.colony.Quests.ask(data, v, d.id, kinds[k]);
                    if (q == null) continue;
                    k++;
                    ColonyService.handleAction(p, new ColonyPayloads.VillageAction(v.id, ColonyPayloads.VillageAction.QUEST_TAKE, q.id, 0));
                }
                log("tasks taken: {}", k);
                // a few planks at the stall: paid to the hundredth
                long before = org.webtrade.minecraftportsmod.colony.Wallet.cents(p);
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(v.id, ColonyPayloads.VillageAction.BUY, Res.PLANKS.ordinal(), 3));
                log("3 planks at the stall: purse {} -> {} hundredths, merchants {}", before, org.webtrade.minecraftportsmod.colony.Wallet.cents(p),
                        v.workers(org.webtrade.minecraftportsmod.colony.Job.MERCHANT));
            });
            // the inventory with the purse beside it
            context.setScreen(() -> new net.minecraft.client.gui.screens.inventory.InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
            context.waitTicks(30);
            var panel = context.computeOnClient(mc -> org.webtrade.minecraftportsmod.client.chart.InventoryPanel.view());
            log("purse in the panel: {}", panel == null ? "none" : panel.purse());
            context.takeScreenshot("workshop_inventory");
            // the mouse over the first task: all of it in the tip
            var rows = context.computeOnClient(mc -> org.webtrade.minecraftportsmod.client.chart.InventoryPanel.rows());
            if (rows.isEmpty()) throw new AssertionError("no tasks in the panel");
            double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
            int[] r0 = rows.getFirst();
            context.getInput().setCursorPos((r0[0] + r0[2]) / 2.0 * scale, (r0[1] + r0[3]) / 2.0 * scale);
            context.waitTicks(5);
            context.takeScreenshot("workshop_inventory_tip");
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
            // the player at the bench, at night (the joiners at home): crouching, using the workshop, the order goes on
            double[] bench = {0, 0};
            server.runOnServer(s -> {
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "time set 18000");
                Village v = VillageData.get(s).get(ids[0]);
                var p = s.getPlayerList().getPlayers().getFirst();
                Building b = v.building(ids[1]);
                int fence = -1;
                for (var r : Orders.recipes(b)) if (r.out() == Res.FENCE) fence = r.index();
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(v.id, ColonyPayloads.VillageAction.ORDER, b.id, fence * 1000 + 6));
                p.teleportTo(b.origin.getX() + 0.5, b.origin.getY() + 1, b.origin.getZ() + b.type.half + 2.5);
            });
            context.waitTicks(40);
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(ids[0]);
                bench[0] = work(v, s.getPlayerList().getPlayers().getFirst(), v.building(ids[1]));
                log("at night, before the bench: {}", Workshops.describe(v));
            });
            for (int k = 0; k < 20; k++) {
                context.waitTicks(4);
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(ids[0]);
                    var p = s.getPlayerList().getPlayers().getFirst();
                    p.setShiftKeyDown(true);
                    org.webtrade.minecraftportsmod.colony.Helping.bench(p, s.overworld(), v.building(ids[1]).origin);
                });
            }
            context.takeScreenshot("workshop_bench");
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(ids[0]);
                s.getPlayerList().getPlayers().getFirst().setShiftKeyDown(false);
                bench[1] = work(v, s.getPlayerList().getPlayers().getFirst(), v.building(ids[1]));
                log("after 4 s at the bench: work {} -> {}; {}", String.format("%.2f", bench[0]), String.format("%.2f", bench[1]), Workshops.describe(v));
            });
            if (bench[1] <= bench[0]) throw new AssertionError("the player's work at the bench did not move the order on");
            // the village's overview: the workshops, what each makes now
            server.runOnServer(s -> ColonyService.sendVillage(s.getPlayerList().getPlayers().getFirst(), ids[0], false));
            context.waitTicks(30);
            context.takeScreenshot("workshop_overview");
            context.setScreen(() -> null);
            // the fields' timbers (the logs round the beds) stand through the village's tidying
            int[] logs = {0, 0};
            server.runOnServer(s -> logs[0] = fieldLogs(s, ids[0]));
            // a day: the village's own orders for what it keeps
            server.runCommand("village day");
            context.waitTicks(40);
            server.runOnServer(s -> log("after a day: {}", Workshops.describe(VillageData.get(s).get(ids[0]))));
            server.runCommand("tp @a ~ ~10 ~");
            context.waitTicks(40);
            context.takeScreenshot("workshop_village");
            server.runOnServer(s -> logs[1] = fieldLogs(s, ids[0]));
            log("the fields' logs: {} before the day, {} after", logs[0], logs[1]);
            if (logs[1] < logs[0]) throw new AssertionError("the logs round a field's beds were taken away (" + logs[0] + " -> " + logs[1] + ")");
            if (moved < 2) throw new AssertionError("the order did not move on (made " + (int) last[0] + ")");
        }
    }

    /** How far the player's orders at a workshop have come: fences made, and the making in hand (three a making). */
    private static double work(Village v, net.minecraft.server.level.ServerPlayer p, Building b) {
        double n = 0;
        for (var o : Orders.of(v, p.getUUID(), b.id)) n += o.made();
        return n + 3 * Workshops.progress(v, b)[1];
    }

    /** The logs of the village's fields that stand in the world (of those their blueprints have). */
    private static int fieldLogs(net.minecraft.server.MinecraftServer s, int village) {
        Village v = VillageData.get(s).get(village);
        int n = 0;
        for (Building b : v.buildings()) {
            if (b.type != BuildingType.FIELD) continue;
            // (the logs round the beds are the field's ground: laid in it, a block under its floor)
            var bp = b.blueprint(v.wood);
            int y = bp.frame.origin().getY() - 1;
            for (var e : bp.ground.entrySet()) {
                if (!e.getValue().is(net.minecraft.tags.BlockTags.LOGS)) continue;
                int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
                var st = s.overworld().getBlockState(new BlockPos(x, y, z));
                if (st.is(net.minecraft.tags.BlockTags.LOGS)) n++;
                else log("field #{}: no log at {} {} {}: {} (above {})", b.id, x, y, z, st, s.overworld().getBlockState(new BlockPos(x, y + 1, z)));
            }
        }
        return n;
    }
}
