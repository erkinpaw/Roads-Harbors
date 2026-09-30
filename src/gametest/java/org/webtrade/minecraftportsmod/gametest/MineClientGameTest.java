package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.AABB;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.colony.VillageManager;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

/** The miners' house by a mass of rock: the miners dig their mine (a stair down, tunnels, torches) and find iron. */
public class MineClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[mine] " + fmt, args);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            var server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 1500");
            // a rock mass east of the camp, the ground under it all stone too
            server.runCommand("fill 18 -63 -24 60 -52 24 stone");
            server.runCommand("fill -40 -63 -40 17 -62 40 stone");
            server.runCommand("tp @a 0 -60 0 180 0");
            context.waitTicks(20);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(40);
            server.runCommand("village build 1 mine_house");
            server.runCommand("village grow 1 miner");
            int[] house = new int[3];
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                for (Building b : v.buildings()) {
                    if (b.type == BuildingType.MINE_HOUSE) {
                        house[0] = b.origin.getX();
                        house[1] = b.origin.getY();
                        house[2] = b.origin.getZ();
                        log("miners' house at {} facing {}", b.origin.toShortString(), b.front);
                    }
                }
            });
            for (int i = 0; i < 12; i++) {
                context.waitTicks(20 * 10);
                final int secs = (i + 1) * 10;
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(1);
                    StringBuilder b = new StringBuilder();
                    for (ResidentEntity e : s.overworld().getEntitiesOfClass(ResidentEntity.class, new AABB(v.center).inflate(90), ResidentEntity::colony)) {
                        if (e.colonyJob() == Job.MINER) {
                            b.append(e.getCustomName().getString()).append(' ').append(e.activity().getString()).append(" @")
                                    .append(e.blockPosition().toShortString()).append(" | ");
                        }
                    }
                    log("t={}s step {} iron {} stone {} :: {}", secs, VillageManager.mineStepOf(v), v.stock(Res.IRON), v.stock(Res.STONE), b);
                });
                if (i == 5 || i == 11 || i == 11) {
                    server.runOnServer(s -> {
                        Village v = VillageData.get(s).get(1);
                        for (ResidentEntity e : s.overworld().getEntitiesOfClass(ResidentEntity.class, new AABB(v.center).inflate(90), ResidentEntity::colony)) {
                            if (e.colonyJob() == Job.MINER && e.getY() < house[1] - 1) {
                                // down in the mine: look along the tunnel
                                s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), e.getX() + 1.5, e.getY() + 1.2, e.getZ() + 1.5,
                                        java.util.Set.of(), 135, 15, false);
                                return;
                            }
                        }
                        s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), house[0] + 8, house[1] + 6, house[2] + 8,
                                java.util.Set.of(), 135, 30, false);
                    });
                    context.waitTicks(20);
                    context.takeScreenshot("mine_" + i);
                }
            }
        }
    }
}
