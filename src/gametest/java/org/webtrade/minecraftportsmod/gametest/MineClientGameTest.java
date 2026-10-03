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

/**
 * The miners' house on flat grass, no rock anywhere about: the miners dig their pit behind it all the same (a layer
 * at a time, its sides going down in steps), carry the stone in and find iron; dug out, they work its sides.
 */
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
            // flat grass: earth, then stone under it, deep enough for the pit
            context.getInput().resizeWindow(1920, 1080);
            for (int x = -55; x < 55; x += 55) {
                for (int z = -55; z < 55; z += 55) {
                    server.runCommand("fill " + x + " -63 " + z + " " + (x + 54) + " -57 " + (z + 54) + " stone");
                    server.runCommand("fill " + x + " -56 " + z + " " + (x + 54) + " -55 " + (z + 54) + " dirt");
                    server.runCommand("fill " + x + " -54 " + z + " " + (x + 54) + " -54 " + (z + 54) + " grass_block");
                }
            }
            server.runCommand("tp @a 0 -53 0 180 0");
            context.waitTicks(20);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(40);
            // (the raised ground ends 55 out: the mine house goes in its middle, a hollow would draw it off it)
            server.runCommand("village build 1 mine_house");
            server.runCommand("village grow 1 miner");
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
            for (int i = 0; i < 18; i++) {
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
                if (i == 5 || i == 11 || i == 17) {
                    server.runOnServer(s -> {
                        Village v = VillageData.get(s).get(1);
                        for (Building b : v.buildings()) {
                            if (b.type != BuildingType.MINE_HOUSE) continue;
                            var c = b.origin.relative(b.front.getOpposite(), BuildingType.MINE_HOUSE.half + 1 + 5);
                            var side = b.front.getClockWise();
                            var eye = c.relative(side, 12).relative(b.front, 6).above(10);
                            double dx = c.getX() - eye.getX(), dz = c.getZ() - eye.getZ();
                            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                            s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), eye.getX() + 0.5, eye.getY(), eye.getZ() + 0.5,
                                    java.util.Set.of(), yaw, 35, false);
                        }
                    });
                    context.waitTicks(20);
                    context.takeScreenshot("mine_" + i);
                }
            }
            // the pit: so many blocks of it open
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                Building h = null;
                for (Building b : v.buildings()) if (b.type == BuildingType.MINE_HOUSE) h = b;
                int open = 0, all = 0;
                var level = s.overworld();
                net.minecraft.core.BlockPos c = h.origin.relative(h.front.getOpposite(), BuildingType.MINE_HOUSE.half + 1 + 5);
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        all++;
                        if (level.getBlockState(new net.minecraft.core.BlockPos(c.getX() + dx, house[1] - 1, c.getZ() + dz)).isAir()) open++;
                    }
                }
                log("pit top layer: {} of {} open; step {}; stone {} iron {}", open, all, VillageManager.mineStepOf(v), v.stock(Res.STONE), v.stock(Res.IRON));
                if (open < all / 2) throw new AssertionError("the pit was hardly dug: " + open + " of " + all);
            });
        }
    }
}
