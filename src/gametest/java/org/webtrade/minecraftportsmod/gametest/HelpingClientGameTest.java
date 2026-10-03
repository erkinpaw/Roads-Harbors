package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.client.chart.PersonScreen;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Dweller;
import org.webtrade.minecraftportsmod.colony.Helping;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.colony.Plots;
import org.webtrade.minecraftportsmod.colony.Quests;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;
import org.webtrade.minecraftportsmod.registry.ModContent;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

/**
 * A player working in a village as one of its people: a villager met asks for something at once; the player builds
 * a site by hand (crouching, using its blocks: the work goes on, a bar shows it); takes what they carry to the store
 * (their tools and food stay with them); hires on at a trade and brings a day's work (paid at the day's end); with a
 * home on their plot counts among the village's people.
 */
public class HelpingClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[helping] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static Village village(MinecraftServer s) {
        return VillageData.get(s).get(1);
    }

    private static void act(MinecraftServer s, int kind, int a) {
        ColonyService.handleAction(player(s), new ColonyPayloads.VillageAction(1, kind, a, 0));
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            context.getInput().resizeWindow(1920, 1080);
            context.waitTicks(5);
            server.runCommand("gamemode survival @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 6000");
            server.runCommand("fill -30 -61 -50 30 -62 -26 water");
            server.runCommand("tp @a 0 -60 -10 0 0");
            context.waitTicks(40);
            server.runCommand("execute as @a at @s run village camp");
            context.waitTicks(20);
            for (String c : new String[]{"grow 1 woodcutter", "grow 1 miner", "grow 1 farmer", "build 1 storehouse 1"}) server.runCommand("village " + c);
            server.runCommand("village give 1 food 300");
            server.runCommand("village day");
            context.waitTicks(120);

            // 1. met: a villager with nothing to ask thinks of something
            server.runOnServer(s -> {
                Village v = village(s);
                var p = player(s);
                ResidentEntity body = null;
                for (var e : s.overworld().getEntitiesOfClass(ResidentEntity.class, p.getBoundingBox().inflate(60), ResidentEntity::colony)) {
                    Dweller d = v.dweller(e.colonyDweller());
                    if (d != null && d.elder()) body = e;
                }
                if (body == null) throw new AssertionError("no head of the village about");
                v.tasks().quests().clear();
                ColonyService.openDweller(p, body);
                Quests.Quest q = Quests.of(v, body.colonyDweller());
                log("met the head: task {}", q == null ? "none" : q.kind + " " + q.what);
            });
            context.waitForScreen(PersonScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("helping_a_person");
            context.setScreen(() -> null);

            // 2. a building site, its materials there: built by hand
            int[] site = {-1};
            server.runOnServer(s -> {
                Building b = org.webtrade.minecraftportsmod.colony.VillageManager.siteNow(s.overworld(), village(s),
                        org.webtrade.minecraftportsmod.colony.BuildingType.HUT);
                if (b != null) site[0] = b.id;
                log("site: {} {}", b == null ? "none" : b.type.id(), b == null ? "" : b.state());
            });
            if (site[0] < 0) throw new AssertionError("no building site came up");
            int[] before = {0};
            server.runOnServer(s -> {
                Village v = village(s);
                Building b = v.building(site[0]);
                var p = player(s);
                p.teleportTo(s.overworld(), b.origin.getX() + 0.5, b.origin.getY() + 1, b.origin.getZ() + b.type.half + 3.5, java.util.Set.of(), 180, 30, false);
                before[0] = b.work();
            });
            context.waitTicks(20);
            for (int k = 0; k < 40; k++) {
                server.runOnServer(s -> {
                    Village v = village(s);
                    Building b = v.building(site[0]);
                    var p = player(s);
                    p.setShiftKeyDown(true);
                    if (b != null) Helping.build(p, s.overworld(), b.origin);
                });
                context.waitTicks(2);
            }
            context.takeScreenshot("helping_b_building_bar");
            server.runOnServer(s -> {
                Village v = village(s);
                Building b = v.building(site[0]);
                player(s).setShiftKeyDown(false);
                log("built by hand: work {} -> {} ({}), state {}", before[0], b.work(), b.type.id(), b.state());
                if (b.work() < before[0] + 40 && (b.state() != Building.State.BUILT || b.upgradeWork())) throw new AssertionError("the strokes did not build");
            });

            // 3. to the store: logs and stone go, the pick and the bread stay
            int[] stock = {0, 0};
            server.runOnServer(s -> {
                Village v = village(s);
                var p = player(s);
                p.getInventory().clearContent();
                p.getInventory().add(new ItemStack(Items.OAK_LOG, 64));
                p.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
                p.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
                p.getInventory().add(new ItemStack(Items.BREAD, 5));
                stock[0] = v.stock(Res.WOOD);
                stock[1] = v.stock(Res.STONE);
                act(s, ColonyPayloads.VillageAction.DEPOSIT, v.elder().id);
                int logs = p.getInventory().countItem(Items.OAK_LOG), pick = p.getInventory().countItem(Items.IRON_PICKAXE),
                        bread = p.getInventory().countItem(Items.BREAD);
                log("store: wood {} -> {}, stone {} -> {}; left with logs {}, pick {}, bread {}; thanks {}", stock[0], v.stock(Res.WOOD), stock[1],
                        v.stock(Res.STONE), logs, pick, bread, v.tasks().thanks(p.getUUID()));
                if (v.stock(Res.WOOD) <= stock[0] || pick != 1 || bread != 5) throw new AssertionError("the store took the wrong things");
            });

            // 4. hired on: a day's work brought and paid
            server.runOnServer(s -> {
                Village v = village(s);
                var p = player(s);
                act(s, ColonyPayloads.VillageAction.HIRE, v.elder().id);
                Job j = Helping.hired(v, p.getUUID());
                int need = Helping.quota(v, j);
                log("hired as {}, a day's work {} (a villager brings {})", j, need, org.webtrade.minecraftportsmod.colony.VillageLife.production(v, j.makes)
                        / Math.max(1, v.workers(j)));
                ItemStack goods = switch (j) {
                    case WOODCUTTER -> new ItemStack(Items.OAK_LOG, 64);
                    case MINER -> new ItemStack(Items.COBBLESTONE, 64);
                    default -> new ItemStack(Items.COOKED_COD, 64);
                };
                p.getInventory().add(goods);
                act(s, ColonyPayloads.VillageAction.DEPOSIT, v.elder().id);
                log("brought {} of {}", Helping.brought(v, p.getUUID()), need);
                if (Helping.brought(v, p.getUUID()) < need) throw new AssertionError("the day's work should be brought");
            });
            int[] em = {0};
            server.runOnServer(s -> em[0] = player(s).getInventory().countItem(Items.EMERALD));
            server.runCommand("village day");
            context.waitTicks(10);
            server.runOnServer(s -> {
                Village v = village(s);
                var p = player(s);
                int now = p.getInventory().countItem(Items.EMERALD);
                log("day over: emeralds {} -> {}, brought now {}, purse {}", em[0], now, Helping.brought(v, p.getUUID()), v.emeralds());
                if (Helping.brought(v, p.getUUID()) != 0) throw new AssertionError("the day's count should start again");
            });

            // 5. a home on a plot: the player counts among the village's people
            server.runOnServer(s -> {
                Village v = village(s);
                ServerLevel level = s.overworld();
                var p = player(s);
                p.getInventory().add(Plots.stone(v));
                BlockPos at = null;
                for (int r = 16; r <= 60 && at == null; r += 4) {
                    for (int a = 0; a < 360 && at == null; a += 30) {
                        int x = v.center.getX() + (int) Math.round(Math.cos(Math.toRadians(a)) * r);
                        int z = v.center.getZ() + (int) Math.round(Math.sin(Math.toRadians(a)) * r);
                        BlockPos g = Plots.ground(level, x, z);
                        ItemStack one = Plots.stone(v);
                        BlockState st = ModContent.PLOT_MARKER.defaultBlockState();
                        level.setBlockAndUpdate(g, st);
                        st.getBlock().setPlacedBy(level, g, st, p, one);
                        if (level.getBlockState(g).is(ModContent.PLOT_MARKER)) at = g;
                    }
                }
                if (at == null) throw new AssertionError("no plot");
                level.setBlockAndUpdate(at.offset(2, 0, 2), Blocks.BED.red().defaultBlockState());
                level.setBlockAndUpdate(at.offset(-2, 0, 2), Blocks.OAK_DOOR.defaultBlockState());
                int people = v.population();
                Plots.use(p, level, at);
                log("home on the plot: residents {}, people {} + players {}", Helping.residents(v), people, Helping.residents(v));
                if (Helping.residents(v) != 1) throw new AssertionError("the player should live in the village now");
            });
            // the person's window with the work shown
            server.runOnServer(s -> {
                Village v = village(s);
                var p = player(s);
                for (var e : s.overworld().getEntitiesOfClass(ResidentEntity.class, p.getBoundingBox().inflate(80), ResidentEntity::colony)) {
                    Dweller d = v.dweller(e.colonyDweller());
                    if (d != null && d.elder()) {
                        ColonyService.openDweller(p, e);
                        break;
                    }
                }
            });
            context.waitForScreen(PersonScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("helping_c_person_hired");
            context.setScreen(() -> null);
        }
    }
}
