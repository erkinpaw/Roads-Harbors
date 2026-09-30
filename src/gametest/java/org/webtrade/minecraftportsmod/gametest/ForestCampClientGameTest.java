package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

/**
 * A camp in the middle of a wood: a clearing is cut for it, the woodcutter takes the trees crowding the tents first,
 * and paths are trodden between the buildings as the village grows.
 */
public class ForestCampClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[forest] " + fmt, args);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            var server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 2000");
            // a wood all around
            java.util.Random rnd = new java.util.Random(7);
            for (int x = -36; x <= 36; x += 6) {
                for (int z = -36; z <= 36; z += 6) {
                    int tx = x + rnd.nextInt(3) - 1, tz = z + rnd.nextInt(3) - 1;
                    server.runCommand("place feature minecraft:" + (rnd.nextBoolean() ? "oak" : "birch") + " " + tx + " -60 " + tz);
                }
            }
            server.runCommand("tp @a 0 -60 12 180 0");
            context.waitTicks(40);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(40);
            int[] c = new int[3];
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                c[0] = v.center.getX();
                c[1] = v.center.getY();
                c[2] = v.center.getZ();
                int near = 0;
                for (int x = -14; x <= 14; x++) {
                    for (int z = -14; z <= 14; z++) {
                        for (int y = 0; y < 8; y++) {
                            if (s.overworld().getBlockState(v.center.offset(x, y, z)).is(BlockTags.LOGS)) near++;
                        }
                    }
                }
                log("camp at {}, wood in store {}, logs left within 14: {}", v.center.toShortString(), v.stock(org.webtrade.minecraftportsmod.colony.Res.WOOD), near);
                for (Building b : v.buildings()) log("  {} at {}", b.type.id(), b.origin.toShortString());
            });
            look(server, c, 0, 40, 18);
            context.waitTicks(60);
            context.takeScreenshot("forest_00_camp");

            // the woodcutter's first trees: how far from the buildings?
            for (int i = 0; i < 9; i++) {
                context.waitTicks(20 * 10);
                server.runOnServer(s -> {
                    for (ResidentEntity e : s.overworld().getEntitiesOfClass(ResidentEntity.class,
                            new net.minecraft.world.phys.AABB(new BlockPos(c[0], c[1], c[2])).inflate(60), ResidentEntity::colony)) {
                        if (e.colonyJob() == org.webtrade.minecraftportsmod.colony.Job.WOODCUTTER) {
                            log("woodcutter {} at {} ({} from the middle)", e.activity().getString(), e.blockPosition().toShortString(),
                                    (int) Math.sqrt(e.blockPosition().distSqr(new BlockPos(c[0], c[1], c[2]))));
                        }
                    }
                });
            }
            look(server, c, 0, 40, 18);
            context.waitTicks(20);
            context.takeScreenshot("forest_01_after_work");

            // a few days on: houses and the paths between them
            for (int i = 0; i < 6; i++) {
                server.runCommand("village day 2");
                context.waitTicks(20 * 8);
            }
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                int paths = 0;
                for (int x = -40; x <= 40; x++) {
                    for (int z = -40; z <= 40; z++) {
                        for (int y = -2; y <= 3; y++) {
                            if (s.overworld().getBlockState(v.center.offset(x, y - 1, z)).is(net.minecraft.world.level.block.Blocks.DIRT_PATH)) paths++;
                        }
                    }
                }
                log("after days: level {} people {} buildings {} path blocks {}", v.level(), v.population(), v.buildings().size(), paths);
            });
            look(server, c, 0, 45, 20);
            context.waitTicks(60);
            context.takeScreenshot("forest_02_village");
            look(server, c, 12, 10, 12);
            context.waitTicks(30);
            context.takeScreenshot("forest_03_ground");
        }
    }

    private static void look(net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext server, int[] c, double ox, double oy, double oz) {
        server.runOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            double x = c[0] + 0.5 + ox, y = c[1] + oy, z = c[2] + 0.5 + oz;
            double dx = c[0] + 0.5 - x, dy = c[1] - y, dz = c[2] + 0.5 - z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            player.teleportTo(s.overworld(), x, y, z, java.util.Set.of(), yaw, pitch, false);
        });
    }
}
