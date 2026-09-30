package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

/**
 * A camp by a pond with no rock anywhere: the miners must dig a quarry pit on dry land, and never wander into the
 * water.
 */
public class MinerClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            var server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 1500");
            server.runCommand("fill -30 -61 -40 30 -62 -14 water");
            server.runCommand("tp @a 0 -60 0 180 0");
            context.waitTicks(20);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(40);
            server.runCommand("village grow 1 miner");
            int[] wet = new int[2];
            for (int i = 0; i < 40; i++) {
                context.waitTicks(20 * 3);
                server.runOnServer(s -> {
                    for (ResidentEntity e : s.overworld().getEntitiesOfClass(ResidentEntity.class,
                            new net.minecraft.world.phys.AABB(BlockPos.ZERO).inflate(80), ResidentEntity::colony)) {
                        if (e.colonyJob() != Job.MINER) continue;
                        boolean inWater = e.isInWater();
                        wet[0] += inWater ? 1 : 0;
                        wet[1]++;
                        Minecraftportsmod.LOGGER.info("[miner] {} {} at {} water={}", e.getCustomName().getString(), e.activity().getString(),
                                e.blockPosition().toShortString(), inWater);
                    }
                });
                if (i == 15 || i == 39) {
                    server.runOnServer(s -> {
                        for (ResidentEntity e : s.overworld().getEntitiesOfClass(ResidentEntity.class,
                                new net.minecraft.world.phys.AABB(BlockPos.ZERO).inflate(80), ResidentEntity::colony)) {
                            if (e.colonyJob() != Job.MINER) continue;
                            var p = s.getPlayerList().getPlayers().getFirst();
                            double x = e.getX() + 5, y = e.getY() + 5, z = e.getZ() + 5;
                            p.teleportTo(s.overworld(), x, y, z, java.util.Set.of(), 135, 40, false);
                            break;
                        }
                    });
                    context.waitTicks(15);
                    context.takeScreenshot("miner_" + i);
                }
            }
            Minecraftportsmod.LOGGER.info("[miner] RESULT in water {} of {} looks", wet[0], wet[1]);
        }
    }
}
