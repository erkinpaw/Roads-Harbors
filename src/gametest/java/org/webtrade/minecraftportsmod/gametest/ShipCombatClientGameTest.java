package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.combat.Pirates;
import org.webtrade.minecraftportsmod.combat.WarshipEntity;
import org.webtrade.minecraftportsmod.registry.ModContent;

/**
 * A sea fight: the player's warship (from its spawn egg's entity) against a pirate in a real ocean. The pirate
 * closes in and fires; the player's ship fires broadsides back (its captain's orders given straight to it); one of
 * them goes down. Screenshots from the third-person camera aboard; a pirate put to sea by the spawner too.
 */
public class ShipCombatClientGameTest implements FabricClientGameTest {

    private static final String SEED = "4242";

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[seafight] " + fmt, args);
    }

    private static WarshipEntity own(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst().getVehicle() instanceof WarshipEntity w ? w : null;
    }

    private static WarshipEntity pirate(MinecraftServer s) {
        var p = s.getPlayerList().getPlayers().getFirst();
        var list = s.overworld().getEntitiesOfClass(WarshipEntity.class, p.getBoundingBox().inflate(200), WarshipEntity::isPirate);
        return list.isEmpty() ? null : list.getFirst();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        Pirates.enabled = false;
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed(SEED)).create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("time set 5000");
            server.runCommand("gamemode creative @a");
            // the egg spawns a warship
            server.runOnServer(s -> {
                if (SpawnEggItem.getType(new ItemStack(ModContent.WARSHIP_SPAWN_EGG)) != ModContent.WARSHIP) throw new AssertionError("the egg does not spawn a warship");
                if (SpawnEggItem.getType(new ItemStack(ModContent.GALLEON_SPAWN_EGG)) != ModContent.GALLEON) throw new AssertionError("the egg does not spawn a galleon");
                if (SpawnEggItem.getType(new ItemStack(ModContent.SHIP_OF_THE_LINE_SPAWN_EGG)) != ModContent.SHIP_OF_THE_LINE) throw new AssertionError("the egg does not spawn a ship of the line");
            });
            // the open sea: the nearest deep ocean
            int[] sea = {0, 0, 0};
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                var found = s.overworld().findClosestBiome3d(h -> h.is(BiomeTags.IS_DEEP_OCEAN), p.blockPosition(), 6400, 32, 64);
                if (found == null) found = s.overworld().findClosestBiome3d(h -> h.is(BiomeTags.IS_OCEAN), p.blockPosition(), 6400, 32, 64);
                if (found == null) return;
                sea[0] = found.getFirst().getX();
                sea[1] = found.getFirst().getZ();
                sea[2] = 1;
                p.teleportTo(s.overworld(), sea[0] + 0.5, s.overworld().getSeaLevel() + 20, sea[1] + 0.5, java.util.Set.of(), 0, 30, false);
            });
            if (sea[2] == 0) throw new AssertionError("no ocean found");
            context.waitTicks(100);
            sp.getConnection().waitForChunksRender();
            // the player's ship, the player at her helm; a pirate some way off on her beam
            server.runOnServer(s -> {
                var level = s.overworld();
                var p = s.getPlayerList().getPlayers().getFirst();
                WarshipEntity ship = ModContent.WARSHIP.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
                ship.snapTo(sea[0] + 0.5, level.getSeaLevel() - 0.4, sea[1] + 0.5, 0, 0);
                level.addFreshEntity(ship);
                p.startRiding(ship, true, false);
                WarshipEntity pirate = ModContent.WARSHIP.create(level, EntitySpawnReason.EVENT);
                pirate.snapTo(sea[0] + 45.5, level.getSeaLevel() - 0.4, sea[1] + 20.5, 90, 0);
                pirate.makePirate();
                level.addFreshEntity(pirate);
                log("ships at {} {}: own #{}, pirate #{}", sea[0], sea[1], ship.getId(), pirate.getId());
            });
            context.waitTicks(100);
            sp.getConnection().waitForChunksRender();
            server.runOnServer(s -> {
                if (own(s) == null) throw new AssertionError("the player is not aboard");
                // afloat: at the sea's surface, not gone down
                for (WarshipEntity w : s.overworld().getEntitiesOfClass(WarshipEntity.class, own(s).getBoundingBox().inflate(100))) {
                    log("{} at y {} (sea level {})", w.cls(), String.format("%.2f", w.getY()), s.overworld().getSeaLevel());
                    if (w.getY() < s.overworld().getSeaLevel() - 1.5) throw new AssertionError(w.cls() + " sank at y " + w.getY());
                }
            });
            context.takeScreenshot("seafight_a_aboard");
            context.runOnClient(mc -> org.webtrade.minecraftportsmod.client.CombatClient.fight(true, -1));
            context.waitTicks(40);
            context.takeScreenshot("seafight_a2_port_side");
            context.runOnClient(mc -> org.webtrade.minecraftportsmod.client.CombatClient.fight(true, 1));
            context.waitTicks(40);
            context.takeScreenshot("seafight_a3_starboard_side");
            // the fight: the player's ship under sail, firing its broadside at the pirate whenever the guns bear
            boolean[] done = {false};
            float[] ownHull = {0}, pirateHull = {0};
            for (int t = 0; t < 20 * 150 && !done[0]; t += 10) {
                context.waitTicks(10);
                final int tt = t;
                server.runOnServer(s -> {
                    WarshipEntity ship = own(s), pirate = pirate(s);
                    if (ship == null || pirate == null || pirate.sinking() > 0 || ship.sinking() > 0) {
                        done[0] = true;
                        var pl = s.getPlayerList().getPlayers().getFirst();
                        log("fight over: own {} pirate {} sinking {} {} player vehicle {} at {}", ship, pirate, ship == null ? -1 : ship.sinking(),
                                pirate == null ? -1 : pirate.sinking(), pl.getVehicle(), pl.blockPosition().toShortString());
                        if (pirate != null) pirateHull[0] = pirate.hull();
                        if (ship != null) ownHull[0] = ship.hull();
                        return;
                    }
                    var p = s.getPlayerList().getPlayers().getFirst();
                    var to = pirate.position().subtract(ship.position());
                    double d = Math.sqrt(to.x * to.x + to.z * to.z);
                    float bearing = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
                    float rel = net.minecraft.util.Mth.wrapDegrees(bearing - ship.getYRot());
                    // keep her side to the pirate at fighting range
                    float want = Math.abs(net.minecraft.util.Mth.wrapDegrees(bearing + 90 - ship.getYRot())) < 90 ? bearing + 90 : bearing - 90;
                    if (d > 32) want = bearing;
                    float delta = net.minecraft.util.Mth.wrapDegrees(want - ship.getYRot());
                    int side = rel > 0 ? 1 : -1;
                    boolean bears = Math.abs(Math.abs(rel) - 90) < 30 && d < 40;
                    ship.order(p, ship.sails() < 2 ? 1 : 0, Math.abs(delta) < 5 ? 0 : delta > 0 ? 1 : -1, bears ? side : 0,
                            WarshipEntity.elevationFor(d));
                    ownHull[0] = ship.hull();
                    pirateHull[0] = pirate.hull();
                    if (tt % 200 == 0) log("t {}s: distance {} own hull {} pirate hull {} pirate sails {}", tt / 20, Math.round(d), ship.hull(), pirate.hull(), pirate.sails());
                });
                if (t == 20 * 8 || t == 20 * 20 || t == 20 * 45 || t == 20 * 80) {
                    // the camera turned to the pirate (on the client: a teleport would put the player ashore), its side chosen
                    context.runOnClient(mc -> {
                        var ship = org.webtrade.minecraftportsmod.client.CombatClient.helm(mc);
                        if (ship == null) return;
                        WarshipEntity pirate = null;
                        for (var e : mc.level.entitiesForRendering()) if (e instanceof WarshipEntity w && w.isPirate()) pirate = w;
                        if (pirate == null) return;
                        var to = pirate.position().subtract(ship.position());
                        float bearing = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
                        mc.player.setYRot(bearing);
                        mc.player.setXRot(12);
                        float rel = net.minecraft.util.Mth.wrapDegrees(bearing - ship.getYRot());
                        org.webtrade.minecraftportsmod.client.CombatClient.fight(true, rel > 0 ? 1 : -1);
                    });
                    context.waitTicks(10);
                    context.takeScreenshot("seafight_b_fight_" + t / 20);
                }
            }
            log("after the fight: own hull {}, pirate hull {}", ownHull[0], pirateHull[0]);
            context.takeScreenshot("seafight_c_end");
            context.waitTicks(60);
            context.takeScreenshot("seafight_d_sinking");
            if (ownHull[0] >= 100) throw new AssertionError("the pirate never hit the player's ship");
            if (pirateHull[0] >= 100) throw new AssertionError("the player's broadsides never hit the pirate");
            // the spawner finds open water for a pirate round the player
            boolean[] spawned = {false};
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                WarshipEntity w = Pirates.spawn(s.overworld(), p.getX(), p.getZ(), net.minecraft.util.RandomSource.create(7));
                spawned[0] = w != null;
                log("spawner: pirate {}", w == null ? "none" : w.blockPosition().toShortString());
            });
            if (!spawned[0]) throw new AssertionError("no pirate could be put to sea round the player");
            // the three classes side by side (and a pirate galleon), looked at from close by, low over the water: no water on their decks
            server.runOnServer(s -> {
                var level = s.overworld();
                var p = s.getPlayerList().getPlayers().getFirst();
                p.stopRiding();
                for (WarshipEntity w : level.getEntitiesOfClass(WarshipEntity.class, p.getBoundingBox().inflate(300))) w.discard();
                int i = 0;
                for (var type : new net.minecraft.world.entity.EntityType<?>[]{ModContent.WARSHIP, ModContent.GALLEON, ModContent.SHIP_OF_THE_LINE, ModContent.GALLEON}) {
                    WarshipEntity w = (WarshipEntity) type.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
                    w.snapTo(sea[0] + 0.5 + i * 16, level.getSeaLevel() - 0.4, sea[1] + 0.5, 0, 0);
                    if (i == 3) w.makePirate();
                    w.setSails(3);
                    level.addFreshEntity(w);
                    log("{}: hull {}, guns a side {}", w.cls(), w.hull(), w.cls().guns());
                    i++;
                }
            });
            server.runCommand("gamemode spectator @a");
            context.waitTicks(100);
            server.runOnServer(s -> {
                for (WarshipEntity w : s.overworld().getEntitiesOfClass(WarshipEntity.class, s.getPlayerList().getPlayers().getFirst().getBoundingBox().inflate(120))) {
                    log("{} afloat at y {}", w.cls(), String.format("%.2f", w.getY()));
                    if (w.getY() < s.overworld().getSeaLevel() - 1.5) throw new AssertionError(w.cls() + " sank at y " + w.getY());
                }
            });
            for (int k = 0; k < 4; k++) {
                final int kk = k;
                server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), sea[0] + 8.5 + kk * 16,
                        s.overworld().getSeaLevel() + 4, sea[1] - 14.5, java.util.Set.of(), -30, 12, false));
                context.waitTicks(40);
                sp.getConnection().waitForChunksRender();
                context.takeScreenshot("seafight_e_class_" + kk);
            }
            server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), sea[0] + 24.5,
                    s.overworld().getSeaLevel() + 26, sea[1] - 40.5, java.util.Set.of(), 0, 25, false));
            context.waitTicks(40);
            sp.getConnection().waitForChunksRender();
            context.takeScreenshot("seafight_f_fleet");
        } finally {
            Pirates.enabled = true;
        }
    }
}
