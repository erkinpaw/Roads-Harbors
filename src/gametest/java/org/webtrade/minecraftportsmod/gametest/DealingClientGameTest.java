package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Dealing;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Trade;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.colony.VillageLife;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A trader at the villages' own prices, in three test villages ("/village sandbox"): one has a heap of iron (it asks
 * little for it), one has none (it pays well); a trader of the third going round the two buys the iron cheap at the
 * first and sells it dear at the second, and comes home with more emeralds than he set out with. A village with
 * plenty of a thing asks less for it than one with just over what it keeps.
 */
public class DealingClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[dealing] " + fmt, args);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("4242")).create()) {
            var server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            int[][] at = {{300, -300}, {300, -520}, {520, -300}};
            for (int[] p : at) {
                // (the chunks' rendering not waited for: the world is busy with the village just set up)
                server.runCommand("tp @a " + p[0] + " 100 " + p[1]);
                context.waitTicks(100);
                server.runCommand("spreadplayers " + p[0] + " " + p[1] + " 0 40 false @a");
                context.waitTicks(100);
                server.runCommand("execute as @p at @p run village sandbox");
                context.waitTicks(20);
            }
            String[] failed = {null};
            server.runOnServer(s -> {
                List<Village> vs = new ArrayList<>();
                for (Village x : VillageData.get(s).all()) if (x.name.contains("Тест") || x.name.contains("Test")) vs.add(x);
                log("test villages: {}", vs.size());
                if (vs.size() < 3) {
                    failed[0] = "only " + vs.size() + " test villages";
                    return;
                }
                Village home = vs.get(0), cheap = vs.get(1), dear = vs.get(2);
                // (room in the stores for a heap: its other heaps down to a third of what it keeps)
                for (Village x : List.of(cheap, dear)) {
                    for (Res r : Res.values()) if (r != Res.IRON) Dealing.stock(x, r, Math.min(x.stock(r), VillageLife.target(x, r) / 3));
                }
                int keep = VillageLife.target(cheap, Res.IRON);
                log("room for iron at the heap: {}", cheap.capacity(Res.IRON));
                Dealing.stock(cheap, Res.IRON, Math.min(keep * 3, keep + cheap.capacity(Res.IRON) / 2));
                Dealing.stock(dear, Res.IRON, 0);
                Dealing.stock(home, Res.IRON, VillageLife.target(home, Res.IRON));
                Village a = cheap;
                log("iron: keeps {} / {} / {}; has {} / {} / {}", VillageLife.target(home, Res.IRON), keep, VillageLife.target(dear, Res.IRON),
                        home.stock(Res.IRON), cheap.stock(Res.IRON), dear.stock(Res.IRON));
                Trade.Ware iron = Trade.WARES.get(Res.IRON.ordinal());
                int askCheap = Trade.sellCents(cheap, iron), bidDear = Trade.buyCents(dear, iron);
                log("the heap asks {} an ingot, the empty one pays {}", askCheap, bidDear);
                // just over what it keeps: asks more than the heap
                Dealing.stock(home, Res.IRON, VillageLife.target(home, Res.IRON) + 5);
                int askNear = Trade.sellCents(home, iron);
                Dealing.stock(home, Res.IRON, VillageLife.target(home, Res.IRON));
                log("just over what it keeps asks {}", askNear);
                if (askCheap < 0 || askNear <= askCheap) {
                    failed[0] = "a heap of iron is not cheaper (" + askCheap + " against " + askNear + ")";
                    return;
                }
                int purse = 60;
                int[] back = Dealing.round(home, List.of(a, dear), Map.of(), purse);
                log("home again with {} emeralds (set out with {}), {} carried; bought to sell on {}, made {} hundredths", back[0], purse, back[1],
                        Dealing.carriedOn, Dealing.madeOn);
                if (Dealing.carriedOn <= 0) failed[0] = "nothing bought where cheap to sell on";
                else if (back[0] <= purse) failed[0] = "the round made nothing: " + back[0] + " from " + purse;
            });
            if (failed[0] != null) throw new AssertionError(failed[0]);
        }
    }
}
