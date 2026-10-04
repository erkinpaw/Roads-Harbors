package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.combat.Pirates;
import org.webtrade.minecraftportsmod.combat.SailorEntity;
import org.webtrade.minecraftportsmod.combat.WarshipEntity;
import org.webtrade.minecraftportsmod.registry.ModContent;

/**
 * The captain's screen: at the helm of a galleon that has lost gunners, a broadside fired: the reloading bar of that
 * side fills from empty within its frame; the chart with the currents running (two pictures a moment apart).
 */
public class ShipHudClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[shiphud] " + fmt, args);
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
            ShipLookClientGameTest.pool(server, -100, -100, 100, 100);
            context.waitTicks(20);
            WarshipEntity[] ship = {null};
            server.runOnServer(s -> {
                ship[0] = ShipLookClientGameTest.ship(s, ModContent.GALLEON, 0, -61, 0, 30, false);
                var p = s.getPlayerList().getPlayers().getFirst();
                p.startRiding(ship[0], true, false);
            });
            context.waitTicks(40);
            server.runOnServer(s -> {
                for (int i = 0; i < 3; i++) ship[0].crewLostForTestRole(SailorEntity.Role.GUNNER);
                ship[0].fire(1, 2);
                log("gunners {}, reload {} loaded {}", ship[0].crew(SailorEntity.Role.GUNNER), ship[0].reload(1), ship[0].loaded(1));
            });
            context.waitTicks(30);
            context.takeScreenshot("shiphud_a_reloading");
            context.waitTicks(10);
            context.takeScreenshot("shiphud_b_chart_later");
            server.runOnServer(s -> {
                float l = ship[0].loaded(1);
                log("after 40 ticks: loaded {}", l);
                if (l < 0 || l > 1) throw new AssertionError("the loading share out of its bounds: " + l);
            });
        }
    }
}
