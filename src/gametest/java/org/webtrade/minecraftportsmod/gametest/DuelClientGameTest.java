package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.Experimental;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.client.chart.PersonScreen;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Dweller;
import org.webtrade.minecraftportsmod.colony.Duels;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

/**
 * (Experimental) a duel with a villager: the experiment turned on, the person's window has a duel; the player is in
 * the arena with iron armour and an iron sword against the person's likeness; beaten, the player is back where they
 * stood with all they had (no death); the fighter down, the same.
 */
public class DuelClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[duel] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean was = Experimental.duels();
        Experimental.setDuels(true);
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            context.getInput().resizeWindow(1920, 1080);
            server.runCommand("gamemode survival @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("time set 6000");
            server.runCommand("tp @a 0 -60 0 0 0");
            context.waitTicks(40);
            server.runCommand("execute as @a at @s run village camp");
            server.runCommand("village grow 1 miner");
            context.waitTicks(60);

            // 1. the person's window: a duel to call out to
            int[] who = {-1};
            double[] home = new double[3];
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                var p = player(s);
                p.getInventory().add(new ItemStack(Items.DIAMOND, 7));
                p.getInventory().add(new ItemStack(Items.BREAD, 3));
                home[0] = p.getX();
                home[1] = p.getY();
                home[2] = p.getZ();
                for (var e : s.overworld().getEntitiesOfClass(ResidentEntity.class, p.getBoundingBox().inflate(60), ResidentEntity::colony)) {
                    Dweller d = v.dweller(e.colonyDweller());
                    if (d != null && !d.elder() && who[0] < 0) {
                        who[0] = d.id;
                        ColonyService.openDweller(p, e);
                    }
                }
                log("the person called out: #{}", who[0]);
            });
            context.waitForScreen(PersonScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("duel_a_person");
            context.setScreen(() -> null);

            // 2. off to the arena
            server.runOnServer(s -> ColonyService.handleAction(player(s), new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.DUEL, who[0], 0)));
            context.waitTicks(60);
            sp.getConnection().waitForChunksRender();
            context.takeScreenshot("duel_b_arena");
            server.runOnServer(s -> {
                var p = player(s);
                ResidentEntity f = Duels.fighter(p);
                log("in the arena: dimension {}, at {}, armour {} {}, sword {}, diamonds {}; fighter {} health {}", p.level().dimension().identifier(),
                        p.blockPosition().toShortString(), p.getItemBySlot(EquipmentSlot.CHEST).getItem(), p.getItemBySlot(EquipmentSlot.HEAD).getItem(),
                        p.getMainHandItem().getItem(), p.getInventory().countItem(Items.DIAMOND), f == null ? "none" : f.getName().getString(),
                        f == null ? 0 : f.getHealth());
                if (p.level().dimension() != Duels.ARENA || f == null) throw new AssertionError("the player should be in the arena against a fighter");
                if (!p.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE) || !p.getMainHandItem().is(Items.IRON_SWORD))
                    throw new AssertionError("the player should have iron armour and an iron sword");
            });
            // (the world saved mid-fight: what the player had kept with it)
            server.runCommand("save-all");
            // 3. the player stands still: the fighter beats them, and they are back home with all they had
            float[] lowest = {20};
            for (int k = 0; k < 40; k++) {
                server.runOnServer(s -> lowest[0] = Math.min(lowest[0], player(s).getHealth()));
                context.waitTicks(10);
                if (k == 12) context.takeScreenshot("duel_c_fight");
                boolean[] over = {false};
                server.runOnServer(s -> over[0] = !Duels.inDuel(player(s)));
                if (over[0]) break;
            }
            server.runOnServer(s -> {
                var p = player(s);
                log("lost: in a duel {}, dimension {}, at {} {} {}, diamonds {}, bread {}, chest armour {}, lowest health {}",
                        Duels.inDuel(p), p.level().dimension().identifier(), (int) p.getX(), (int) p.getY(), (int) p.getZ(),
                        p.getInventory().countItem(Items.DIAMOND), p.getInventory().countItem(Items.BREAD), p.getItemBySlot(EquipmentSlot.CHEST).getItem(),
                        lowest[0]);
                if (Duels.inDuel(p)) throw new AssertionError("the fighter should have beaten a player standing still");
                if (p.level() != s.overworld() || p.getInventory().countItem(Items.DIAMOND) != 7 || p.getInventory().countItem(Items.BREAD) != 3
                        || !p.getItemBySlot(EquipmentSlot.CHEST).isEmpty() || Math.abs(p.getX() - home[0]) > 1)
                    throw new AssertionError("the player should be back where they stood with all they had");
            });
            context.waitTicks(20);
            context.takeScreenshot("duel_d_back");

            // 4. again, and the fighter down: won, back home
            server.runOnServer(s -> ColonyService.handleAction(player(s), new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.DUEL, who[0], 0)));
            context.waitTicks(40);
            server.runOnServer(s -> {
                var p = player(s);
                Village v = VillageData.get(s).get(1);
                log("second call: can {}, in a duel {}, dimension {}", Duels.can(p, v.dweller(who[0]), VillageData.get(s).day()), Duels.inDuel(p),
                        p.level().dimension().identifier());
                ResidentEntity f = Duels.fighter(player(s));
                if (f == null) throw new AssertionError("no second fight");
                f.hurtServer((net.minecraft.server.level.ServerLevel) f.level(), f.damageSources().playerAttack(player(s)), 1000);
            });
            context.waitTicks(20);
            server.runOnServer(s -> {
                var p = player(s);
                log("won: in a duel {}, dimension {}, diamonds {}", Duels.inDuel(p), p.level().dimension().identifier(), p.getInventory().countItem(Items.DIAMOND));
                if (Duels.inDuel(p) || p.level() != s.overworld() || p.getInventory().countItem(Items.DIAMOND) != 7)
                    throw new AssertionError("the fighter down: the player back with all they had");
            });
            context.takeScreenshot("duel_e_won");
        } finally {
            Experimental.setDuels(was);
        }
    }
}
