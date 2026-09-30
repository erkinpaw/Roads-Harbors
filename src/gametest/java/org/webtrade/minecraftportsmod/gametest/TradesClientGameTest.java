package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.AABB;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Dweller;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

/**
 * The trades up close, on flat land made ready for them: a wood behind the camp, a pond before it, a rocky ledge
 * and a ripe field. Each trade is watched and photographed; the log counts trees felled, saplings planted, stone
 * broken and what reached the store.
 */
public class TradesClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[trades] " + fmt, args);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule random_tick_speed 0");
            server.runCommand("time set 1500");
            // the camp faces north (-z): the pond in front, the wood behind, the ledge and the field to the sides
            server.runCommand("fill -12 -61 -30 12 -62 -16 water");
            server.runCommand("fill -12 -61 -15 12 -61 -15 sand");
            for (int[] t : new int[][]{{-8, 22}, {-3, 25}, {3, 23}, {8, 26}, {0, 30}, {-6, 31}, {6, 32}}) {
                server.runCommand("place feature minecraft:" + (t[0] % 2 == 0 ? "oak" : "birch") + " " + t[0] + " -60 " + t[1]);
            }
            server.runCommand("fill 22 -60 -4 26 -58 4 stone");
            server.runCommand("fill 23 -57 -3 25 -57 3 stone");
            server.runCommand("tp @a 0 -60 0 180 0");
            context.waitTicks(20);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(40);
            int[] c = new int[4];
            server.runOnServer(s -> {
                Village v = VillageData.get(s).all().iterator().next();
                c[0] = v.center.getX();
                c[1] = v.center.getY();
                c[2] = v.center.getZ();
                c[3] = v.id;
                log("camp #{} at {}", v.id, v.center.toShortString());
            });
            final int id = c[3];
            // one more miner, and a field with a farmer (the wheat ripens as he tends it)
            server.runCommand("village grow " + id + " miner");
            server.runCommand("village build " + id + " field");
            server.runCommand("village grow " + id + " farmer");
            int[] counts0 = counts(server);
            look(server, 0, -45, 30, 0, -60, 0);
            context.waitTicks(40);
            context.takeScreenshot("trades_00_setup");

            for (int i = 0; i < 30; i++) {
                context.waitTicks(20 * 4);
                final int secs = (i + 1) * 4;
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(id);
                    StringBuilder b = new StringBuilder();
                    for (ResidentEntity e : residents(s, c)) {
                        b.append(e.getCustomName() == null ? "?" : e.getCustomName().getString()).append('[')
                                .append(e.colonyJob() == null ? "child" : e.colonyJob().id()).append("] ").append(e.activity().getString())
                                .append(" @").append(e.blockPosition().toShortString()).append(" | ");
                    }
                    log("t={}s stock food={} wood={} stone={} :: {}", secs, v.stock(Res.FOOD), v.stock(Res.WOOD), v.stock(Res.STONE), b);
                });
                if (i % 3 == 0) {
                    Job job = new Job[]{Job.WOODCUTTER, Job.GATHERER, Job.MINER, Job.WOODCUTTER, Job.FARMER}[(i / 3) % 5];
                    follow(context, server, c, job, "trades_" + String.format("%02d", i + 1) + "_" + job.id());
                }
            }
            int[] counts1 = counts(server);
            log("RESULT logs {} -> {}, leaves {} -> {}, saplings {} -> {}, stone {} -> {}", counts0[0], counts1[0], counts0[1], counts1[1],
                    counts0[2], counts1[2], counts0[3], counts1[3]);
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(id);
                for (Dweller d : v.dwellers()) log("earned {} {} = {}", d.name, d.job(), d.earned());
            });
            look(server, 0, -45, 30, 0, -60, 0);
            context.waitTicks(20);
            context.takeScreenshot("trades_99_after");
        }
    }

    /** Logs, leaves, saplings and stone in the prepared ground. */
    private static int[] counts(TestServerContext server) {
        int[] n = new int[4];
        server.runOnServer(s -> {
            var level = s.overworld();
            for (int x = -40; x <= 40; x++) {
                for (int z = -40; z <= 40; z++) {
                    for (int y = -61; y <= -45; y++) {
                        var st = level.getBlockState(new BlockPos(x, y, z));
                        if (st.is(BlockTags.LOGS)) n[0]++;
                        else if (st.is(BlockTags.LEAVES)) n[1]++;
                        else if (st.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock) n[2]++;
                        else if (st.is(net.minecraft.world.level.block.Blocks.STONE)) n[3]++;
                    }
                }
            }
        });
        return n;
    }

    private static java.util.List<ResidentEntity> residents(MinecraftServer s, int[] c) {
        return s.overworld().getEntitiesOfClass(ResidentEntity.class, new AABB(new BlockPos(c[0], c[1], c[2])).inflate(80), ResidentEntity::colony);
    }

    private static void follow(ClientGameTestContext context, TestServerContext server, int[] c, Job job, String shot) {
        boolean[] found = new boolean[1];
        server.runOnServer(s -> {
            for (ResidentEntity e : residents(s, c)) {
                if (e.colonyJob() == job) {
                    lookAt(s, e.getX() + 4, e.getY() + 3, e.getZ() + 4, e.getX(), e.getY() + 1, e.getZ());
                    found[0] = true;
                    break;
                }
            }
        });
        if (!found[0]) return;
        context.waitTicks(10);
        context.takeScreenshot(shot);
    }

    private static void look(TestServerContext server, double x, double y, double z, double tx, double ty, double tz) {
        server.runOnServer(s -> lookAt(s, x, y, z, tx, ty, tz));
    }

    private static void lookAt(MinecraftServer s, double x, double y, double z, double tx, double ty, double tz) {
        var player = s.getPlayerList().getPlayers().getFirst();
        double dx = tx - x, dy = ty - y, dz = tz - z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        player.teleportTo(s.overworld(), x, y, z, java.util.Set.of(), yaw, pitch, false);
    }
}
