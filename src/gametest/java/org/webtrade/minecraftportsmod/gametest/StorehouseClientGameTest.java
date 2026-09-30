package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.colony.VillageLife;

/**
 * Storehouses follow what the village needs to keep, not what happens to fill them: the store is one room for
 * everything. A village of eight with a storehouse has its store filled up, day after day, with iron, coal, planks and
 * sticks (far more than it needs of any of them): it builds no storehouse for that (what it needs still comes in: the
 * heaps are moved out for it). Then it grows to a size whose needs (five days of food, the day's eating, what its
 * building sites are to have) no longer fit: then it does make room (a storehouse raised a level, or a new one).
 */
public class StorehouseClientGameTest implements FabricClientGameTest {

    private static final String SEED = "4242";
    private static final Res[] HEAPED = {Res.IRON, Res.COAL, Res.PLANKS, Res.STICKS};

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[stores] " + fmt, args);
    }

    /** Storehouses standing or being built, and the levels they are (or are being) raised to, all told. */
    private static int[] stores(Village v) {
        int n = 0, levels = 0;
        for (Building b : v.buildings()) {
            if (b.type.branch != BuildingType.Branch.STORE || b.state() == Building.State.DEMOLISHING) continue;
            n++;
            levels += Math.max(b.level(), b.goal());
        }
        return new int[]{n, levels};
    }

    private static String needs(Village v) {
        StringBuilder sb = new StringBuilder("store ").append(v.stored()).append('/').append(v.capacity()).append(", needs ")
                .append(VillageLife.storageNeed(v)).append(" | ");
        for (Res r : new Res[]{Res.FOOD, Res.WOOD, Res.STONE, Res.IRON, Res.COAL, Res.PLANKS, Res.STICKS}) {
            sb.append(r.id()).append(' ').append(v.stock(r)).append(" (needs ").append(VillageLife.storageNeed(v, r)).append(") ");
        }
        return sb.toString();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed(SEED)).create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("time set 6000");
            server.waitFor(s -> VillageData.get(s).all().size() >= 1, 20 * 600);
            int[] a = {-1};
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                Village best = null;
                for (Village v : VillageData.get(s).all()) {
                    if (best == null || v.center.distSqr(p.blockPosition()) < best.center.distSqr(p.blockPosition())) best = v;
                }
                a[0] = best.id;
                p.teleportTo(s.overworld(), best.center.getX() + 0.5, best.center.getY() + 25, best.center.getZ() + 0.5, java.util.Set.of(), 0, 60, false);
            });
            context.waitTicks(80);
            sp.getConnection().waitForChunksRender();
            server.runOnServer(s -> {
                var cmd = s.getCommands();
                var src = s.createCommandSourceStack();
                for (String c : new String[]{"build %d storehouse 1", "grow %d woodcutter", "grow %d woodcutter", "grow %d miner", "grow %d fisher", "grow %d farmer"}) {
                    cmd.performPrefixedCommand(src, "village " + String.format(c, a[0]));
                }
                Village v = VillageData.get(s).get(a[0]);
                log("village #{} {}: {} people; storehouses {} | {}", v.id, v.name, v.population(), stores(v)[0], needs(v));
            });

            // 1. twenty days with the store filled up with iron, coal, planks and sticks
            int[] start = {0};
            server.runOnServer(s -> start[0] = stores(VillageData.get(s).get(a[0]))[0]);
            StringBuilder bad = new StringBuilder();
            for (int day = 1; day <= 20; day++) {
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(a[0]);
                    int each = v.room() / HEAPED.length;
                    for (Res r : HEAPED) {
                        if (each > 0) s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "village give " + a[0] + " " + r.id() + " " + each);
                    }
                });
                server.runCommand("village day");
                context.waitTicks(20);
                final int d = day;
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(a[0]);
                    int[] now = stores(v);
                    boolean shortOf = VillageLife.storageShort(v);
                    if (d % 5 == 0 || now[0] > start[0]) {
                        log("day {}: {} people, storehouses {} (levels {}), short of room {} | {}", d, v.population(), now[0], now[1], shortOf, needs(v));
                    }
                    if (now[0] > start[0]) {
                        // (a new one: only if something the village needs to keep did not fit)
                        boolean needed = VillageLife.storageNeed(v) > v.capacity() * 85 / 100;
                        if (!needed) bad.append("day ").append(d).append(": a storehouse started with nothing needed short of room; ");
                        start[0] = now[0];
                    }
                });
            }
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(a[0]);
                for (var l : v.log()) if (l.text().getString().toLowerCase().contains("storehouse")) log("log day {}: {}", l.day(), l.text().getString());
            });
            if (bad.length() > 0) throw new AssertionError("storehouses built for heaps it has no need of: " + bad);

            // 2. the village grows to where its food no longer fits: now it makes room
            int[] before = {0, 0, 0};
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(a[0]);
                for (int k = 0; k < 20; k++) s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "village grow " + a[0] + " fisher");
                int[] st = stores(v);
                before[0] = st[0];
                before[1] = st[1];
                before[2] = v.capacity();
                log("grown: {} people; the village needs {} of room, the store holds {}; storehouses {} (levels {})", v.population(), VillageLife.storageNeed(v),
                        v.capacity(), st[0], st[1]);
            });
            boolean[] made = {false};
            for (int day = 1; day <= 10 && !made[0]; day++) {
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(a[0]);
                    int more = v.capacity(Res.FOOD) - v.stock(Res.FOOD);
                    if (more > 0) s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "village give " + a[0] + " food " + more);
                });
                server.runCommand("village day");
                context.waitTicks(20);
                final int d = day;
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(a[0]);
                    int[] st = stores(v);
                    log("day {} after growing: storehouses {} (levels {}), short of room {} | {}", d, st[0], st[1], VillageLife.storageShort(v), needs(v));
                    if (st[0] > before[0] || st[1] > before[1]) made[0] = true;
                });
            }
            if (!made[0]) throw new AssertionError("the village's food did not fit, and it made no room for it");
        }
    }
}
