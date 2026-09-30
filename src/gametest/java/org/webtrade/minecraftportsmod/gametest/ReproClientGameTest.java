package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

/**
 * Plans changing under people's feet: a farmer foraging when the field gets built, jobs changing while days are
 * skipped several at a time. Nothing may crash.
 */
public class ReproClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            var server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 2000");
            server.runCommand("tp @a 0 -60 0 180 0");
            context.waitTicks(20);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(40);
            server.runCommand("village grow 1 farmer");
            server.runCommand("village grow 1 farmer");
            context.waitTicks(20 * 15);   // foraging now
            server.runCommand("village build 1 field");
            context.waitTicks(20 * 10);
            for (int i = 0; i < 6; i++) {
                server.runCommand("village day 3");
                context.waitTicks(20 * 3);
                server.runCommand("village day 5");
                context.waitTicks(20 * 5);
            }
            server.runCommand("time set 7000");
            context.waitTicks(20 * 20);
            server.runCommand("time set 1000");
            context.waitTicks(20 * 20);
            Minecraftportsmod.LOGGER.info("[repro] survived");
        }
    }
}
