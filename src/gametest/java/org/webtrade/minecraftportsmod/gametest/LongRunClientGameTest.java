package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.Dweller;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;

/** Forty village days in a row on flat land: does the village keep growing, fed and busy? */
public class LongRunClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            var server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 7000");
            server.runCommand("tp @a 0 -60 0 0 0");
            context.waitTicks(20);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(20 * 5);
            server.runCommand("tp @a 0 -40 30 180 40");
            for (int day = 1; day <= 60; day++) {
                server.runCommand("village day");
                context.waitTicks(20 * 3);
                final int d = day;
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(1);
                    StringBuilder jobs = new StringBuilder();
                    for (Job j : Job.values()) jobs.append(j.id()).append('=').append(v.workers(j)).append(' ');

                    int children = 0;
                    for (Dweller w : v.dwellers()) if (w.job() == null) children++;
                    StringBuilder sites = new StringBuilder();
                    for (Building b : v.projects()) sites.append(b.type.id()).append(':').append(b.state().id()).append(' ');
                    StringBuilder built = new StringBuilder();
                    for (Building b : v.buildings()) {
                        if (b.state() == Building.State.BUILT) built.append(b.type.id()).append(b.level()).append(b.goal() > 0 ? "^" : "").append(' ');
                    }
                    StringBuilder open = new StringBuilder();
                    for (var t : org.webtrade.minecraftportsmod.colony.BuildingType.values()) if (t.isNode() && !t.free && v.unlocked(t)) open.append(t.id()).append(' ');
                    built.append("| open: ").append(open).append("| focus ").append(v.focus());
                    Minecraftportsmod.LOGGER.info("[long] day {} {} people={} (children {}) beds={} houses={} mood={} food={} wood={} stone={} iron={} planks={} sticks={} | {}| {}| built: {}",
                            d, v.level(), v.population(), children, v.beds(), v.houses(), v.mood(), v.stock(Res.FOOD), v.stock(Res.WOOD),
                            v.stock(Res.STONE), v.stock(Res.IRON), v.stock(Res.PLANKS), v.stock(Res.STICKS), jobs, sites, built);
                });
            }
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                for (var l : v.log()) Minecraftportsmod.LOGGER.info("[long] log {}: {}", l.day(), l.text().getString());
            });
            context.takeScreenshot("long_run");
        }
    }
}
