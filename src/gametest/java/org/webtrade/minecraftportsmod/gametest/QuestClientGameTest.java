package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.client.chart.QuestScreen;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Dweller;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.colony.Quests;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Trade;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

/**
 * The villagers' tasks: a village left to itself for some days has tasks out (marks over heads); then one of each
 * kind is done by the player: wood brought to the store, bone meal that makes the farmers faster, what a building
 * site lacks, a hunt round the village, a letter to a second village nobody there had heard of (and its answer
 * back: the two villages know each other). Each pays, counts towards the achievements, and survives saving.
 */
public class QuestClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[quests] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static Dweller of(Village v, Job job) {
        for (Dweller d : v.dwellers()) if (d.job() == job && !d.elder()) return d;
        return null;
    }

    private static void act(MinecraftServer s, int village, int kind, int a) {
        ColonyService.handleAction(player(s), new ColonyPayloads.VillageAction(village, kind, a, 0));
    }

    private static boolean has(MinecraftServer s, String id) {
        var h = s.getAdvancements().get(Minecraftportsmod.id("roads/" + id));
        return h != null && player(s).getAdvancements().getOrStartProgress(h).isDone();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            context.getInput().resizeWindow(1920, 1080);
            context.waitTicks(5);
            server.runCommand("gamemode creative @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 6000");
            // a second village first, far off, then the first one where the player stays
            server.runCommand("fill 370 -61 -50 430 -62 -26 water");
            server.runCommand("tp @a 400 -60 -10 0 0");
            context.waitTicks(40);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(10);
            server.runCommand("fill -30 -61 -50 30 -62 -26 water");
            server.runCommand("tp @a 0 -60 -10 0 0");
            context.waitTicks(40);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(20);
            for (String c : new String[]{"grow 2 woodcutter", "grow 2 farmer", "grow 2 miner", "grow 2 smith", "grow 2 gatherer",
                    "build 2 field 1", "build 2 hut 1", "build 2 hut 1"}) {
                server.runCommand("village " + c);
            }
            server.runCommand("village give 2 food 200");
            // a few days of its own: tasks come out of the village's life
            for (int day = 0; day < 4; day++) {
                server.runCommand("village day");
                context.waitTicks(10);
            }
            int[] ids = new int[2];
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                ids[0] = 2;
                ids[1] = 1;
                Village v = data.get(2);
                log("village {} '{}' people {}, tasks out {} (room {})", v.id, v.name, v.population(), v.tasks().quests().size(), v.dwellers().size());
                for (Quests.Quest q : v.tasks().quests()) log("  {} by #{}: {} x{} reward {}", q.kind, q.giver, q.what, q.count, q.reward());
                if (v.tasks().quests().isEmpty()) throw new AssertionError("no tasks after four days");
                if (data.get(1).knownIds().contains(2)) throw new AssertionError("the villages should not know each other yet");
                player(s).teleportTo(s.overworld(), v.center.getX() + 4.5, v.center.getY() + 1, v.center.getZ() + 6.5, java.util.Set.of(), 180, 10, false);
            });
            context.waitTicks(40);
            context.takeScreenshot("quest_a_marks");

            // 1. wood for the store
            int[] q1 = {-1};
            int[] woodBefore = {0};
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                Village v = data.get(2);
                Dweller wc = of(v, Job.WOODCUTTER);
                Quests.Quest q = Quests.ask(data, v, wc.id, Quests.Kind.BRING);
                q1[0] = q.id;
                log("asked: {} {} x{} by {}, reward {}", q.kind, q.what, q.count, wc.name, q.reward());
                act(s, 2, ColonyPayloads.VillageAction.QUEST, wc.id);
            });
            context.waitForScreen(QuestScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("quest_b_offer");
            server.runOnServer(s -> act(s, 2, ColonyPayloads.VillageAction.QUEST_TAKE, q1[0]));
            context.waitTicks(10);
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(2);
                var p = player(s);
                p.getInventory().clearContent();
                Quests.Quest q = find(v, q1[0]);
                Res r = Res.byId(q.what);
                p.getInventory().add(Trade.piece(v, Trade.WARES.get(r.ordinal())).copyWithCount(Math.min(64, q.count)));
                woodBefore[0] = v.stock(r);
                act(s, 2, ColonyPayloads.VillageAction.QUEST, q.giver);
            });
            context.waitTicks(10);
            context.takeScreenshot("quest_c_taken");
            server.runOnServer(s -> act(s, 2, ColonyPayloads.VillageAction.QUEST_HAND, q1[0]));
            context.waitTicks(10);
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(2);
                int em = Trade.emeralds(player(s));
                log("bring: task left {}, emeralds {}, store {} -> {}, thanks {}", find(v, q1[0]) != null, em, woodBefore[0],
                        v.stock(Res.WOOD) + v.stock(Res.FOOD) + v.stock(Res.STONE), v.tasks().thanks(player(s).getUUID()));
                if (find(v, q1[0]) != null) throw new AssertionError("the bring task should be done");
                if (!has(s, "quest")) throw new AssertionError("no achievement for the first task");
            });

            // 2. bone meal for the fields: the farmers work faster
            int[] q2 = {-1};
            double[] before = {0};
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                Village v = data.get(2);
                Dweller f = of(v, Job.FARMER);
                Quests.Quest q = Quests.ask(data, v, f.id, Quests.Kind.ITEM);
                q2[0] = q.id;
                before[0] = org.webtrade.minecraftportsmod.colony.VillageLife.production(v, Res.FOOD);
                act(s, 2, ColonyPayloads.VillageAction.QUEST_TAKE, q.id);
                player(s).getInventory().add(new ItemStack(Quests.item(q.what), q.count));
                log("farmer asks for {} x{}", q.what, q.count);
                act(s, 2, ColonyPayloads.VillageAction.QUEST_HAND, q.id);
                log("bone meal: done {}, food a day {} -> {}", find(v, q.id) == null, before[0],
                        org.webtrade.minecraftportsmod.colony.VillageLife.production(v, Res.FOOD));
                if (find(v, q.id) != null) throw new AssertionError("the bone meal task should be done");
                if (org.webtrade.minecraftportsmod.colony.VillageLife.production(v, Res.FOOD) <= before[0])
                    throw new AssertionError("the farmers should work faster");
            });

            // 3. a building site's materials
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                Village v = data.get(2);
                Quests.Quest q = Quests.ask(data, v, v.elder().id, Quests.Kind.SITE);
                if (q == null) {
                    log("site: no site waiting for materials (projects {})", v.projects().size());
                    return;
                }
                var b = v.building(q.building());
                Res r = Res.byId(q.what);
                int missing = b.missing(r);
                act(s, 2, ColonyPayloads.VillageAction.QUEST_TAKE, q.id);
                ItemStack piece = Trade.piece(v, Trade.WARES.get(r.ordinal()));
                for (int left = q.count; left > 0; left -= 64) player(s).getInventory().add(piece.copyWithCount(Math.min(64, left)));
                act(s, 2, ColonyPayloads.VillageAction.QUEST_HAND, q.id);
                log("site: {} {} missing {} -> {}, task done {}", b.type.id(), r.id(), missing, b.missing(r), find(v, q.id) == null);
                if (b.missing(r) >= missing) throw new AssertionError("the site should have got its materials");
            });

            // 4. a hunt
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                Village v = data.get(2);
                Dweller m = of(v, Job.MINER);
                Quests.Quest q = Quests.ask(data, v, m.id, Quests.Kind.HUNT);
                act(s, 2, ColonyPayloads.VillageAction.QUEST_TAKE, q.id);
                act(s, 2, ColonyPayloads.VillageAction.QUEST_HAND, q.id);
                if (find(v, q.id) == null) throw new AssertionError("a hunt must not be done before the monsters are");
                for (int k = 0; k < q.count; k++) Quests.killed(player(s), v.center.offset(10, 0, 10));
                act(s, 2, ColonyPayloads.VillageAction.QUEST_HAND, q.id);
                log("hunt of {}: done {}", q.count, find(v, q.id) == null);
                if (find(v, q.id) != null || !has(s, "hunt")) throw new AssertionError("the hunt should be done");
            });

            // 5. a letter to the other village
            int[] q5 = {-1};
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                Village v = data.get(2);
                Quests.Quest q = Quests.ask(data, v, v.elder().id, Quests.Kind.LETTER);
                if (q == null) throw new AssertionError("no letter: the other village should be unknown");
                q5[0] = q.id;
                act(s, 2, ColonyPayloads.VillageAction.QUEST_TAKE, q.id);
                act(s, 2, ColonyPayloads.VillageAction.QUEST, v.elder().id);
            });
            context.waitTicks(10);
            context.takeScreenshot("quest_d_letter");
            context.setScreen(() -> null);
            server.runOnServer(s -> {
                Village o = VillageData.get(s).get(1);
                player(s).teleportTo(s.overworld(), o.center.getX() + 0.5, o.center.getY() + 1, o.center.getZ() + 0.5, java.util.Set.of(), 0, 0, false);
            });
            context.waitTicks(60);
            server.runOnServer(s -> {
                VillageData data = VillageData.get(s);
                ResidentEntity body = null;
                for (var e : s.overworld().getEntitiesOfClass(ResidentEntity.class, player(s).getBoundingBox().inflate(80), ResidentEntity::colony)) {
                    if (e.colonyVillage() == 1) body = e;
                }
                if (body == null) throw new AssertionError("nobody of the other village about");
                ColonyService.openDweller(player(s), body);
                boolean known = data.get(1).knownIds().contains(2) && data.get(2).knownIds().contains(1);
                log("letter delivered: villages know each other {}", known);
                if (!known || !has(s, "letter")) throw new AssertionError("the letter should have made the villages known to each other");
                Village v = data.get(2);
                player(s).teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 1, v.center.getZ() + 0.5, java.util.Set.of(), 0, 0, false);
            });
            context.waitTicks(40);
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(2);
                act(s, 2, ColonyPayloads.VillageAction.QUEST_HAND, q5[0]);
                log("answer brought back: task done {}, emeralds {}, thanks {}", find(v, q5[0]) == null, Trade.emeralds(player(s)),
                        v.tasks().thanks(player(s).getUUID()));
                if (find(v, q5[0]) != null) throw new AssertionError("the letter task should be done once the answer is back");
                // saved and read back
                var tag = VillageData.CODEC.encodeStart(NbtOps.INSTANCE, VillageData.get(s)).getOrThrow();
                VillageData back = VillageData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
                int thanks = back.get(2).tasks().thanks(player(s).getUUID());
                log("saved and read back: thanks {}, tasks {}", thanks, back.get(2).tasks().quests().size());
                if (thanks != v.tasks().thanks(player(s).getUUID())) throw new AssertionError("thanks lost in saving");
            });
            // the days go on: new tasks come, old offers run out
            for (int day = 0; day < 5; day++) {
                server.runCommand("village day");
                context.waitTicks(5);
            }
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(2);
                log("after 5 more days: tasks out {}", v.tasks().quests().size());
                for (Quests.Quest q : v.tasks().quests()) log("  {} by #{}: {} x{} reward {} until {}", q.kind, q.giver, q.what, q.count, q.reward(), q.until());
            });
            context.waitTicks(20);
            context.takeScreenshot("quest_e_later");
        }
    }

    private static Quests.Quest find(Village v, int id) {
        for (Quests.Quest q : v.tasks().quests()) if (q.id == id) return q;
        return null;
    }
}
