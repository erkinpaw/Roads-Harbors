package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.Vec3;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.combat.Pirates;
import org.webtrade.minecraftportsmod.combat.WarshipEntity;
import org.webtrade.minecraftportsmod.registry.ModContent;

/**
 * Walking on a warship's deck: the player stands on a galleon's deck while she sails and turns, and stays aboard
 * (carried along with her); walks to the bow and back; climbs to the quarterdeck.
 */
public class ShipDeckClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[deck] " + fmt, args);
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
            server.runCommand("gamemode survival @a");
            server.runCommand("tp @a 0 -40 0");
            context.waitTicks(40);
            ShipLookClientGameTest.pool(server, -120, -120, 120, 120);
            context.waitTicks(20);
            WarshipEntity[] ship = {null};
            server.runOnServer(s -> ship[0] = ShipLookClientGameTest.ship(s, ModContent.GALLEON, 0, -61, -60, 0, false));
            context.waitTicks(20);
            // onto her main deck, amidships
            server.runOnServer(s -> {
                WarshipEntity w = ship[0];
                Vec3 p = w.at(0, w.cls().middle + 2, w.cls().deck() + 0.3);
                s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), p.x, p.y, p.z, java.util.Set.of(), w.getYRot(), 20, false);
            });
            context.waitTicks(40);
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                log("standing: player y {} ship y {} deck {} on deck {}; slabs {} first {} removed {}", p.getY(), ship[0].getY(), ship[0].cls().deck(),
                        ship[0].onDeck(p.position()), ship[0].deckSlabs().size(), ship[0].deckSlabs().isEmpty() ? "-" : ship[0].deckSlabs().getFirst().getBoundingBox(),
                        ship[0].deckSlabs().isEmpty() ? "-" : ship[0].deckSlabs().getFirst().isRemoved());
                if (!ship[0].onDeck(p.position())) throw new AssertionError("the player fell off a ship lying still");
                ship[0].cruise(3, 0);
            });
            for (int k = 0; k < 26; k++) {
                if (k == 10) {
                    context.takeScreenshot("deck_a_sailing");
                    server.runOnServer(s -> ship[0].cruise(3, 1));
                }
                context.waitTicks(10);
                final int kk = k;
                server.runOnServer(s -> {
                    var p = s.getPlayerList().getPlayers().getFirst();
                    WarshipEntity w = ship[0];
                    Vec3 d = p.position().subtract(w.position());
                    log("t{}: ship yaw {} speed {} | player side {} along {} up {} ground {} on deck {}", kk * 10, String.format("%.1f", w.getYRot()),
                            String.format("%.3f", w.speed()), String.format("%.2f", d.dot(w.starboard())), String.format("%.2f", d.dot(w.forward())),
                            String.format("%.2f", d.y), p.onGround(), w.onDeck(p.position()));
                });
            }
            context.takeScreenshot("deck_b_turning");
            boolean[] ok = {false};
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                WarshipEntity w = ship[0];
                ok[0] = w.onDeck(p.position());
                log("after sailing and turning: ship at {} speed {}, player at {} on deck {}", w.position(), w.speed(), p.position(), ok[0]);
            });
            if (!ok[0]) throw new AssertionError("the player was left behind (or fell overboard)");
            // walk forward a bit while she turns
            context.getInput().holdKeyFor(org.lwjgl.glfw.GLFW.GLFW_KEY_W, 30);
            context.waitTicks(10);
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                log("after walking: on deck {}", ship[0].onDeck(p.position()));
                if (!ship[0].onDeck(p.position())) throw new AssertionError("walking on the deck, the player fell off");
            });
            context.takeScreenshot("deck_c_walked");
        }
    }
}
