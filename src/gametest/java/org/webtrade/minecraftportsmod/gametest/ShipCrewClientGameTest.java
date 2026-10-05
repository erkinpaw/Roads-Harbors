package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.combat.MusketBallEntity;
import org.webtrade.minecraftportsmod.combat.Pirates;
import org.webtrade.minecraftportsmod.combat.SailorEntity;
import org.webtrade.minecraftportsmod.combat.WarshipEntity;
import org.webtrade.minecraftportsmod.registry.ModContent;

/**
 * Crews: a navy galleon and a pirate brig lie close. Their crews come up on deck (a captain at the wheel, gunners,
 * marines, hands); the marines fire their muskets across at the other deck; people fall; the gunners run to their
 * guns when a broadside goes off. The player fires a musket too; then hires a hand with an emerald.
 */
public class ShipCrewClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[crew] " + fmt, args);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        Pirates.enabled = false;
        context.getInput().resizeWindow(1920, 1080);
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 6000");
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 0 -40 0");
            context.waitTicks(40);
            ShipLookClientGameTest.pool(server, -80, -80, 80, 80);
            context.waitTicks(20);
            WarshipEntity[] ships = new WarshipEntity[2];
            server.runOnServer(s -> {
                ships[0] = ShipLookClientGameTest.ship(s, ModContent.GALLEON, 0, -61, 0, 0, false);
                ships[1] = ShipLookClientGameTest.ship(s, ModContent.WARSHIP, 20, -61, 0, 0, true);
                ships[0].setSails(0);
                ships[1].setSails(0);
            });
            context.waitTicks(60);
            server.runOnServer(s -> {
                for (WarshipEntity w : ships) {
                    var crew = s.overworld().getEntitiesOfClass(SailorEntity.class, w.getBoundingBox().inflate(20), c -> c.ship() == w);
                    log("{} {}: crew bodies {} (of {}), roles {}", w.isPirate() ? "pirate" : "navy", w.cls(), crew.size(), w.crewTotal(),
                            crew.stream().map(c -> c.role().name()).sorted().toList());
                    if (crew.isEmpty()) throw new AssertionError("no crew came up");
                }
                var p = s.getPlayerList().getPlayers().getFirst();
                Vec3 eye = ships[0].at(0, ships[0].cls().middle, ships[0].cls().deck() + 3);
                p.teleportTo(s.overworld(), eye.x - 6, eye.y + 4, eye.z - 10, java.util.Set.of(), -40, 25, false);
            });
            context.waitTicks(20);
            context.takeScreenshot("crew_a_aboard");
            // the fight: muskets across, a broadside each
            int[] before = new int[2];
            server.runOnServer(s -> {
                before[0] = ships[0].crewTotal();
                before[1] = ships[1].crewTotal();
                ships[0].fire(1, 2);
            });
            context.waitTicks(30);
            context.takeScreenshot("crew_b_broadside");
            int[] balls = {0};
            for (int k = 0; k < 12; k++) {
                context.waitTicks(20);
                server.runOnServer(s -> balls[0] = MusketBallEntity.fired);
            }
            context.takeScreenshot("crew_c_muskets");
            server.runOnServer(s -> {
                log("after the musketry: musket balls fired {}, hits {}; crew navy {} -> {}, pirate {} -> {}", balls[0], MusketBallEntity.struck, before[0], ships[0].crewTotal(),
                        before[1], ships[1].crewTotal());
                if (balls[0] == 0) throw new AssertionError("nobody fired a musket");
            });
            // the player's musket, and a hand hired with an emerald
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(ModContent.MUSKET_ITEM));
                int was = MusketBallEntity.fired;
                var r = p.getMainHandItem().getItem().use(s.overworld(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                int flying;
                flying = MusketBallEntity.fired - was;
                log("player's musket: {} balls fired {}; on cooldown {}", r, flying, p.getCooldowns().isOnCooldown(p.getMainHandItem()));
                if (flying == 0) throw new AssertionError("the player's musket did not fire");
                int had = ships[0].crewTotal();
                ships[0].crewLostForTest();
                // (an emerald out of the purse: crouching, empty-handed)
                p.getInventory().add(new ItemStack(net.minecraft.world.item.Items.EMERALD, 3));
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                p.setShiftKeyDown(true);
                ships[0].interact(p, net.minecraft.world.InteractionHand.MAIN_HAND, ships[0].position());
                p.setShiftKeyDown(false);
                log("hired: crew {} -> lost one -> {}", had, ships[0].crewTotal());
                if (ships[0].crewTotal() != had) throw new AssertionError("the emerald should hire the lost hand back");
            });
        }
    }
}
