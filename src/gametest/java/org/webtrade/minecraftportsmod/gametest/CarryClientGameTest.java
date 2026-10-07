package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.client.chart.QuestScreen;
import org.webtrade.minecraftportsmod.colony.Carry;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Quests;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Trails;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * Goods carried to a neighbour on the village's donkey: offered once a trail joins the two; taken, the goods leave
 * the store and a saddled donkey with packs stands by the player (it takes no harm, its packs do not open); ridden
 * into the neighbour's land, the goods go to its store, it pays for them, the task is done and the donkey gone.
 */
public class CarryClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[carry] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            context.getInput().resizeWindow(1920, 1080);
            server.runCommand("gamemode creative @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("time set 6000");
            server.runCommand("tp @a 0 -60 0 0 0");
            context.waitTicks(40);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(20);
            server.runCommand("tp @a 0 -60 260 0 0");
            context.waitTicks(60);
            sp.getConnection().waitForChunksRender();
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(20);
            int[] ids = {-1, -1};
            server.runOnServer(s -> {
                for (Village v : VillageData.get(s).all()) {
                    if (ids[0] < 0) ids[0] = v.id;
                    else ids[1] = v.id;
                }
                log("villages #{} and #{}", ids[0], ids[1]);
            });
            if (ids[1] < 0) throw new AssertionError("two villages wanted");
            server.runCommand("village trail " + ids[0] + " " + ids[1]);
            server.waitFor(s -> {
                Trails.Trail t = Trails.find(VillageData.get(s), ids[0], ids[1]);
                return t != null && (t.ready() || t.none());
            }, 20 * 300);
            server.runCommand("village roads finish");
            // the first village with wood to spare, the second short of it
            server.runCommand("village give " + ids[0] + " wood 400");

            server.runCommand("tp @a 4 -60 8 0 0");
            context.waitTicks(40);
            // 1. the task: offered by the head, taken
            int[] quest = {-1};
            int[] before = {0, 0, 0, 0};
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                Village a = data.get(ids[0]), b = data.get(ids[1]);
                log("trail: {}, route {}", Trails.find(data, a.id, b.id) == null ? "none" : "yes", Trails.route(data, a.id, b.id) == null ? "none" : "yes");
                Quests.Quest q = Quests.ask(data, a, a.elder().id, Quests.Kind.CARRY);
                if (q == null) throw new AssertionError("no goods to carry offered");
                quest[0] = q.id;
                log("offered: {} {} to #{}, reward {}", q.count, q.what, q.to(), q.reward());
                before[0] = a.stock(Res.WOOD);
                before[1] = b.stock(Res.WOOD);
                before[2] = a.emeralds();
                before[3] = b.emeralds();
                ColonyService.sendQuest(player(s), a, a.elder());
            });
            context.waitForScreen(QuestScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("carry_a_offer");
            server.runOnServer(s -> ColonyService.handleAction(player(s), new ColonyPayloads.VillageAction(ids[0], ColonyPayloads.VillageAction.QUEST_TAKE, quest[0], 0)));
            context.waitTicks(10);
            context.setScreen(() -> null);
            Entity[] donkey = {null};
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                Village a = data.get(ids[0]);
                var p = player(s);
                for (Entity e : s.overworld().getEntitiesOfClass(net.minecraft.world.entity.animal.equine.Donkey.class, p.getBoundingBox().inflate(16), x -> x.entityTags().contains(Carry.TAG))) donkey[0] = e;
                log("taken: store wood {} -> {}, donkey {}", before[0], a.stock(Res.WOOD), donkey[0]);
                if (donkey[0] == null) throw new AssertionError("no donkey");
                if (a.stock(Res.WOOD) >= before[0]) throw new AssertionError("the goods should leave the store");
                // it takes no harm, its packs stay shut
                boolean hurt = donkey[0].hurtServer(s.overworld(), s.overworld().damageSources().playerAttack(p), 100);
                var menu = p.containerMenu;
                ((net.minecraft.world.entity.animal.equine.AbstractHorse) donkey[0]).openCustomInventoryScreen(p);
                log("hurt {}, alive {}, packs opened {}", hurt, donkey[0].isAlive(), p.containerMenu != menu);
                if (!donkey[0].isAlive() || p.containerMenu != menu) throw new AssertionError("the donkey should be unharmed and its packs shut");
                p.startRiding(donkey[0], true, true);
            });
            context.waitTicks(20);
            context.takeScreenshot("carry_b_riding");

            // 2. ridden into the neighbour's land: delivered
            server.runOnServer(s -> {
                Village b = VillageData.get(s).get(ids[1]);
                donkey[0].teleportTo(b.center.getX() + 6.5, b.center.getY() + 1, b.center.getZ() + 6.5);
            });
            context.waitTicks(60);
            context.takeScreenshot("carry_c_delivered");
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                Village a = data.get(ids[0]), b = data.get(ids[1]);
                boolean open = false;
                for (Quests.Quest q : a.tasks().quests()) open |= q.id == quest[0];
                log("delivered: neighbour wood {} -> {}, purses: village {} -> {}, neighbour {} -> {}; task still out {}, donkey alive {}",
                        before[1], b.stock(Res.WOOD), before[2], a.emeralds(), before[3], b.emeralds(), open, donkey[0].isAlive());
                if (open || b.stock(Res.WOOD) <= before[1] || donkey[0].isAlive()) throw new AssertionError("the goods should be delivered");
            });
        }
    }
}
