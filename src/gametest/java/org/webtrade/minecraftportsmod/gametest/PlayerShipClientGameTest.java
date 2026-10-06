package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biomes;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Harbour;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Trade;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.colony.Wallet;
import org.webtrade.minecraftportsmod.combat.WarshipEntity;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * A ship of the player's own: the test village ("/village sandbox") set down on a beach, with a pier; the player
 * orders a brig from its skipper (paid out of the purse), her site goes up at the jetty's foot, her materials are
 * brought, she is on the stocks some days and is launched at the pier, the player's. Then the stall trades with her
 * hold: iron in the hold sold, what the village has to spare bought into it.
 */
public class PlayerShipClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[playership] " + fmt, args);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("4242")).create()) {
            var server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            // (creative: the player taken high up over the land to look for it does not fall to death)
            server.runCommand("gamemode creative @a");
            server.runCommand("time set 3000");
            // a coast with room for a pier: beaches about the world, the village set down a little inland from each
            // (a few tries: a marshy shore, shallow far out, has no water deep enough at a jetty's end)
            int[] ids = {-1, -1};
            int[][] origins = {{0, 0}, {1500, 0}, {0, 1500}, {-1500, 0}, {0, -1500}};
            for (int attempt = 0; attempt < origins.length && ids[0] < 0; attempt++) {
                BlockPos[] beach = {null};
                final int[] o = origins[attempt];
                server.runOnServer(s -> {
                    var found = s.overworld().findClosestBiome3d(h -> h.is(Biomes.BEACH), new BlockPos(o[0], 64, o[1]), 2000, 32, 64);
                    beach[0] = found == null ? null : found.getFirst();
                    log("beach near {} {}: {}", o[0], o[1], beach[0] == null ? "none" : beach[0].toShortString());
                });
                if (beach[0] == null) continue;
                server.runCommand("tp @a " + beach[0].getX() + " 120 " + beach[0].getZ());
                context.waitTicks(100);
                int[] inland = {beach[0].getX(), beach[0].getZ()};
                server.runOnServer(s -> {
                    var level = s.overworld();
                    int best = Integer.MIN_VALUE;
                    for (int k = 0; k < 8; k++) {
                        double ang = k * Math.PI / 4;
                        int x = beach[0].getX() + (int) Math.round(Math.cos(ang) * 36), z = beach[0].getZ() + (int) Math.round(Math.sin(ang) * 36);
                        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                        if (!level.getBlockState(new BlockPos(x, y, z)).getFluidState().isEmpty()) continue;
                        if (y > best) {
                            best = y;
                            inland[0] = x;
                            inland[1] = z;
                        }
                    }
                    log("inland at {} {} (ground {})", inland[0], inland[1], best);
                });
                server.runCommand("spreadplayers " + inland[0] + " " + inland[1] + " 0 6 false @a");
                context.waitTicks(100);
                server.runCommand("execute as @p at @p run village sandbox");
                context.waitTicks(40);
                server.runOnServer(s -> {
                    Village v = null;
                    for (Village x : VillageData.get(s).all()) if ((x.name.contains("Тест") || x.name.contains("Test")) && (v == null || x.id > v.id)) v = x;
                    if (v == null) return;
                    boolean pier = false;
                    for (Building b : v.buildings()) pier |= b.type == BuildingType.PIER;
                    int skipper = -1;
                    for (var d : v.dwellers()) if (d.job() == Job.SAILOR) skipper = d.id;
                    log("test village #{} {}: {} buildings, pier {}, skipper #{}, can order {} for {}", v.id, v.name, v.buildings().size(), pier, skipper,
                            Harbour.canOrder(v), Harbour.playerShipPrice(v));
                    if (pier) {
                        ids[0] = v.id;
                        ids[1] = skipper;
                    }
                });
            }
            if (ids[0] < 0) throw new AssertionError("no test village with a pier on any coast tried");
            server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().getInventory().add(new ItemStack(Items.EMERALD_BLOCK, 30)));
            context.waitTicks(20);
            boolean[] ordered = {false};
            long[] purse = {0};
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(ids[0]);
                var p = s.getPlayerList().getPlayers().getFirst();
                Wallet.absorb(p);
                long before = Wallet.cents(p);
                if (!Harbour.canOrder(v) || ids[1] < 0) return;
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(v.id, ColonyPayloads.VillageAction.SHIP_ORDER, ids[1], 0));
                for (Building b : v.buildings()) if (b.type == BuildingType.SHIP && p.getUUID().equals(b.owner())) ordered[0] = true;
                purse[0] = Wallet.cents(p);
                log("ordered {}: purse {} -> {}", ordered[0], before, purse[0]);
            });
            WarshipEntity[] ship = {null};
            if (ordered[0]) {
                // the days: her materials brought, the stocks, the launch (with the player by the pier)
                for (int day = 1; day <= 6 && ship[0] == null; day++) {
                    server.runCommand("village day");
                    context.waitTicks(60);
                    final int dd = day;
                    server.runOnServer(s -> {
                        Village v = VillageData.get(s).get(ids[0]);
                        var p = s.getPlayerList().getPlayers().getFirst();
                        ship[0] = Harbour.playerShip(s.overworld(), v, p.getUUID());
                        StringBuilder site = new StringBuilder();
                        for (Building b : v.buildings()) {
                            if (b.type != BuildingType.SHIP) continue;
                            site.append(b.state()).append(" owner ").append(b.owner() != null).append(" missing");
                            for (Res r : Res.values()) if (b.missing(r) > 0) site.append(' ').append(r.id()).append(' ').append(b.missing(r));
                            site.append(" | store wool ").append(v.stock(Res.WOOL)).append(" planks ").append(v.stock(Res.PLANKS)).append(" wood ").append(v.stock(Res.WOOD))
                                    .append("; queue ").append(org.webtrade.minecraftportsmod.colony.Workshops.describe(v).length() > 0 ? "" : "");
                        }
                        log("day {}: site {}, ship {}", dd, site, ship[0] == null ? "none" : ship[0].blockPosition().toShortString());
                    });
                }
                if (ship[0] == null) throw new AssertionError("the ship ordered was not launched");
            } else {
                // (no pier on this coast: the ship given to the player at the village, to see the stall trade with her hold)
                log("no pier to order at: a ship put by the village for the hold's trade");
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(ids[0]);
                    var p = s.getPlayerList().getPlayers().getFirst();
                    WarshipEntity w = new WarshipEntity(org.webtrade.minecraftportsmod.registry.ModContent.WARSHIP, s.overworld());
                    w.setPos(v.center.getX() + 6.5, v.center.getY() + 1, v.center.getZ() + 6.5);
                    w.setOwner(p.getUUID());
                    s.overworld().addFreshEntity(w);
                    ship[0] = w;
                });
            }
            // the ship from above (a spectator: not fallen into the sea)
            server.runCommand("gamemode spectator @a");
            server.runOnServer(s -> {
                var at = ship[0].position();
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), String.format(java.util.Locale.ROOT,
                        "tp @a %.1f %.1f %.1f facing %.1f %.1f %.1f", at.x + 10, at.y + 9, at.z + 10, at.x, at.y, at.z));
            });
            context.waitTicks(40);
            context.takeScreenshot("playership_a_launched");
            server.runCommand("gamemode creative @a");
            // the stall and the hold: iron sold out of the hold, planks bought into it
            int[] counts = new int[4];
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(ids[0]);
                var p = s.getPlayerList().getPlayers().getFirst();
                // (the ship by the village, whatever coast she was launched at)
                ship[0].setPos(v.center.getX() + 6.5, ship[0].getY(), v.center.getZ() + 6.5);
                ship[0].hold().setItem(0, new ItemStack(Items.IRON_INGOT, 32));
                counts[0] = Trade.carried(p, v, Trade.WARES.get(Res.IRON.ordinal()));
                long before = Wallet.cents(p);
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(v.id, ColonyPayloads.VillageAction.SELL, Res.IRON.ordinal(), 20));
                int left = 0;
                for (int i = 0; i < ship[0].hold().getContainerSize(); i++) if (ship[0].hold().getItem(i).is(Items.IRON_INGOT)) left += ship[0].hold().getItem(i).getCount();
                counts[1] = left;
                // (what the village has most to spare of, bought: into the hold)
                Res buy = Res.WOOD;
                for (Res r : new Res[]{Res.WOOD, Res.STONE, Res.PLANKS, Res.WHEAT}) {
                    if (Trade.available(v, Trade.WARES.get(r.ordinal())) > Trade.available(v, Trade.WARES.get(buy.ordinal()))) buy = r;
                }
                int want = Math.min(40, Trade.available(v, Trade.WARES.get(buy.ordinal())));
                counts[3] = want;
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(v.id, ColonyPayloads.VillageAction.BUY, buy.ordinal(), want));
                int got = 0;
                for (int i = 0; i < ship[0].hold().getContainerSize(); i++) got += buy.unitsOf(ship[0].hold().getItem(i)) * ship[0].hold().getItem(i).getCount();
                counts[2] = got;
                log("bought {} {} into the hold: {}", want, buy.id(), got);
                log("iron for sale (inventory + hold) {}, iron left in the hold {}, planks in the hold {}; purse {} -> {}", counts[0], counts[1], counts[2],
                        before, Wallet.cents(p));
                ship[0].openHold(p);
            });
            context.waitTicks(20);
            context.takeScreenshot("playership_b_hold");
            context.setScreen(() -> null);
            // her badge, looked at: the hold's fill, loading at the village
            server.runCommand("gamemode spectator @a");
            server.runOnServer(s -> {
                var at = ship[0].position();
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), String.format(java.util.Locale.ROOT,
                        "tp @a %.1f %.1f %.1f facing %.1f %.1f %.1f", at.x + 14, at.y + 10, at.z + 14, at.x, at.y + 6, at.z));
            });
            context.waitTicks(60);
            var badges = context.computeOnClient(mc -> org.webtrade.minecraftportsmod.client.render.BuildingBadges.badges());
            String shipBadge = "";
            for (var b : badges) if (b.title().getString().contains("/" + WarshipEntity.HOLD)) shipBadge = b.title().getString() + " | " + b.doing().getString();
            log("the ship's badge: {}", shipBadge.isEmpty() ? "none" : shipBadge);
            context.takeScreenshot("playership_c_badge");
            server.runCommand("gamemode creative @a");
            if (shipBadge.isEmpty()) throw new AssertionError("no badge over the ship");
            if (counts[0] < 32) throw new AssertionError("the iron in the hold was not counted for sale (" + counts[0] + ")");
            if (counts[1] > 12) throw new AssertionError("the iron sold did not come out of the hold (" + counts[1] + " left)");
            if (counts[3] <= 0) throw new AssertionError("the village had nothing to spare to buy");
            if (counts[2] < counts[3]) throw new AssertionError("what was bought did not go into the hold (" + counts[2] + " of " + counts[3] + ")");
        }
    }
}
