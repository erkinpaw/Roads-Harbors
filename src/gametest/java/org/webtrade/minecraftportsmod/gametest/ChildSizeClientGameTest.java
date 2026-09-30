package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.colony.VillageManager;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

/** A quick look: a child next to a grown-up, and the camp as set up by /village camp on flat land. */
public class ChildSizeClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder()
                .create()) {
            var server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("time set 2000");
            server.runCommand("gamerule advance_time false");
            server.runCommand("tp @a 0 -60 0 0 0");
            context.waitTicks(20);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(20 * 8);
            server.runCommand("village baby 1");
            context.waitTicks(20 * 6);
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                var level = s.overworld();
                ResidentEntity baby = null, adult = null;
                for (ResidentEntity e : level.getEntitiesOfClass(ResidentEntity.class, new net.minecraft.world.phys.AABB(v.center).inflate(64))) {
                    if (e.isBaby()) baby = e;
                    else adult = e;
                }
                if (baby != null && adult != null) {
                    // side by side, facing the camera
                    baby.setNoAi(true);
                    adult.setNoAi(true);
                    baby.snapTo(v.center.getX() + 0.5, v.center.getY(), v.center.getZ() + 6.5, 0, 0);
                    adult.snapTo(v.center.getX() + 2.0, v.center.getY(), v.center.getZ() + 6.5, 0, 0);
                    var p = s.getPlayerList().getPlayers().getFirst();
                    p.teleportTo(level, v.center.getX() + 1.2, v.center.getY() + 1.2, v.center.getZ() + 11.5, java.util.Set.of(), 180, 5, false);
                    Minecraftportsmod.LOGGER.info("[colony] baby height {} adult height {}", baby.getBbHeight(), adult.getBbHeight());
                }
            });
            context.waitTicks(40);
            context.takeScreenshot("child_vs_adult");
        }
    }
}
