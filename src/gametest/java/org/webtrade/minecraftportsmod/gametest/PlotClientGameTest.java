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
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.ColonyService;
import org.webtrade.minecraftportsmod.colony.Plots;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;
import org.webtrade.minecraftportsmod.registry.ModContent;

/**
 * A player's plot: a boundary stone bought from the head of the village is set down too near the square (refused,
 * back in the hand), then in a good place: the plot is marked (corner lanterns), the village grows for some days
 * and builds nothing on it, a path is laid to its front; the stone used shows the houses the village would build; a
 * hut paid for goes up as the village's site and, built, is handed over: the player's home (achievement); the stone
 * taken up gives the plot back.
 */
public class PlotClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[plot] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static boolean has(MinecraftServer s, String id) {
        var h = s.getAdvancements().get(Minecraftportsmod.id("roads/" + id));
        return h != null && player(s).getAdvancements().getOrStartProgress(h).isDone();
    }

    private static int stones(ServerPlayer p) {
        return p.getInventory().countItem(ModContent.PLOT_MARKER_ITEM);
    }

    private static ItemStack stoneIn(ServerPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(ModContent.PLOT_MARKER_ITEM)) return inv.getItem(i);
        return ItemStack.EMPTY;
    }

    /** Sets the stone down at a place, as the player's hand would. True if it stayed (a plot was claimed). */
    private static boolean place(ServerLevel level, ServerPlayer p, BlockPos pos) {
        ItemStack s = stoneIn(p);
        if (s.isEmpty()) throw new AssertionError("no stone in hand");
        ItemStack one = s.copyWithCount(1);
        s.shrink(1);
        BlockState st = ModContent.PLOT_MARKER.defaultBlockState();
        level.setBlockAndUpdate(pos, st);
        st.getBlock().setPlacedBy(level, pos, st, p, one);
        return level.getBlockState(pos).is(ModContent.PLOT_MARKER);
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
            for (String c : new String[]{"grow 1 woodcutter", "grow 1 woodcutter", "grow 1 miner", "grow 1 farmer", "grow 1 gatherer", "build 1 hut 1",
                    "build 1 hut 1", "build 1 storehouse 1"}) {
                server.runCommand("village " + c);
            }
            for (String r : new String[]{"wood 400", "stone 200", "food 300", "wheat 60", "planks 100"}) server.runCommand("village give 1 " + r);
            server.runCommand("village day");
            context.waitTicks(20);

            // the stone, bought from the head of the village
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                var p = player(s);
                p.getInventory().clearContent();
                p.getInventory().add(new ItemStack(Items.EMERALD, 40));
                int price = Plots.price(v, p.getUUID());
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.PLOT_BUY, v.elder().id, 0));
                log("bought a stone for {}: stones {}, emeralds left {}", price, stones(p), p.getInventory().countItem(Items.EMERALD));
                if (stones(p) != 1) throw new AssertionError("no stone bought");
            });

            // too near the square: refused, back in the hand
            BlockPos[] at = {null};
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                ServerLevel level = s.overworld();
                var p = player(s);
                BlockPos near = v.center.offset(3, 0, 3);
                near = new BlockPos(near.getX(), Plots.ground(level, near.getX(), near.getZ()).getY(), near.getZ());
                boolean stayed = place(level, p, near);
                log("by the square: stayed {}, stones {}", stayed, stones(p));
                if (stayed || stones(p) != 1) throw new AssertionError("a plot by the square should be refused");
                // a good place: the first ring spot that takes it
                for (int r = 16; r <= 60 && at[0] == null; r += 4) {
                    for (int a = 0; a < 360 && at[0] == null; a += 30) {
                        int x = v.center.getX() + (int) Math.round(Math.cos(Math.toRadians(a)) * r);
                        int z = v.center.getZ() + (int) Math.round(Math.sin(Math.toRadians(a)) * r);
                        BlockPos g = Plots.ground(level, x, z);
                        if (place(level, p, g)) at[0] = g;
                    }
                }
                if (at[0] == null) throw new AssertionError("no place took the stone");
                log("plot at {} ({} from the middle); plots {}", at[0].toShortString(),
                        (int) Math.hypot(at[0].getX() - v.center.getX(), at[0].getZ() - v.center.getZ()), v.tasks().plots().size());
                int lanterns = 0;
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sz = -1; sz <= 1; sz += 2) {
                        for (int dy = -3; dy <= 3; dy++) {
                            if (level.getBlockState(at[0].offset(sx * Plots.HALF, dy, sz * Plots.HALF)).is(Blocks.LANTERN)) lanterns++;
                        }
                    }
                }
                log("corner lanterns {}, achievement plot {}", lanterns, has(s, "plot"));
                if (v.tasks().plots().size() != 1 || lanterns < 3 || !has(s, "plot")) throw new AssertionError("the plot was not marked");
                p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                p.teleportTo(level, at[0].getX() + 0.5, at[0].getY() + 24, at[0].getZ() + 0.5, java.util.Set.of(), 0, 90, false);
            });
            context.waitTicks(30);
            context.takeScreenshot("plot_a_marked");

            // the village grows: nothing of it on the plot; a path comes to the plot's front
            for (int day = 0; day < 8; day++) {
                server.runCommand("village day");
                context.waitTicks(60);
            }
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                ServerLevel level = s.overworld();
                for (Building b : v.buildings()) {
                    if (b.overlaps(at[0], Plots.HALF, 0)) throw new AssertionError(b.type.id() + " built on the plot");
                }
                int path = 0;
                for (int x = -Plots.HALF - 3; x <= Plots.HALF + 3; x++) {
                    for (int z = -Plots.HALF - 3; z <= Plots.HALF + 3; z++) {
                        boolean edge = Math.abs(x) > Plots.HALF || Math.abs(z) > Plots.HALF;
                        int cx = at[0].getX() + x, cz = at[0].getZ() + z;
                        BlockState top = level.getBlockState(new BlockPos(cx, Plots.ground(level, cx, cz).getY() - 1, cz));
                        if (top.is(Blocks.DIRT_PATH)) {
                            if (!edge) throw new AssertionError("a path through the plot at " + cx + "," + cz);
                            path++;
                        }
                    }
                }
                log("after 8 days: buildings {}, path blocks round the plot {}", v.buildings().size(), path);
                if (path == 0) throw new AssertionError("no path to the plot");
            });
            server.runOnServer(s -> {
                var p = player(s);
                p.teleportTo(s.overworld(), at[0].getX() + 0.5, at[0].getY() + 34, at[0].getZ() + 0.5, java.util.Set.of(), 0, 90, false);
                Plots.use(p, s.overworld(), at[0]);
            });
            context.waitTicks(30);
            context.takeScreenshot("plot_b_village_round_it");

            // the stone used: the houses to choose from
            context.waitForScreen(org.webtrade.minecraftportsmod.client.chart.PlotScreen.class);
            context.takeScreenshot("plot_c_houses");
            context.setScreen(() -> null);
            // a hut chosen and paid for: the village builds it, first in its queue; the stone moves to the front edge
            BlockPos[] stone = {null};
            int[] house = {-1};
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(1);
                var p = player(s);
                p.getInventory().add(new ItemStack(Items.EMERALD, 64));
                ColonyService.handleAction(p, new ColonyPayloads.VillageAction(1, ColonyPayloads.VillageAction.PLOT_HOUSE,
                        org.webtrade.minecraftportsmod.colony.BuildingType.HUT.ordinal(), 0));
                Plots.Plot plot = Plots.of(v, p.getUUID());
                stone[0] = plot.stone();
                house[0] = plot.house();
                Building b = v.building(plot.house());
                log("hut ordered: house #{}, stone moved to {} ({}), purse {}", plot.house(), plot.stone().toShortString(),
                        s.overworld().getBlockState(plot.stone()).is(ModContent.PLOT_MARKER), org.webtrade.minecraftportsmod.colony.Wallet.balance(p));
                if (b == null || b.owner() == null || stone[0].equals(at[0]) || !s.overworld().getBlockState(stone[0]).is(ModContent.PLOT_MARKER)
                        || s.overworld().getBlockState(at[0]).is(ModContent.PLOT_MARKER)) {
                    throw new AssertionError("the house was not begun");
                }
                // (not the stone to take up while it goes up)
                if (Plots.mayBreak(p, s.overworld(), stone[0])) throw new AssertionError("the stone should stay while the house goes up");
            });
            context.waitTicks(10);
            context.setScreen(() -> null);
            // the days go by: the hut goes up and is handed over
            boolean[] done = {false};
            for (int day = 0; day < 12 && !done[0]; day++) {
                server.runCommand("village day");
                context.waitTicks(60);
                final int d = day;
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(1);
                    Plots.Plot plot = Plots.of(v, player(s).getUUID());
                    Building b = v.building(house[0]);
                    log("day {}: house {} {}", d + 1, b == null ? "gone from the village" : b.state().name(), b == null ? "" : b.work() + " work");
                    done[0] = plot.home() && plot.house() < 0 && b == null;
                });
            }
            server.runOnServer(s -> {
                ServerLevel level = s.overworld();
                Village v = VillageData.get(s).get(1);
                int beds = 0, doors = 0;
                for (int x = -Plots.HALF; x <= Plots.HALF; x++) {
                    for (int z = -Plots.HALF; z <= Plots.HALF; z++) {
                        for (int y = -3; y <= 8; y++) {
                            BlockState st = level.getBlockState(at[0].offset(x, y, z));
                            if (st.is(net.minecraft.tags.BlockTags.BEDS)) beds++;
                            if (st.is(net.minecraft.tags.BlockTags.DOORS)) doors++;
                        }
                    }
                }
                int homes = 0;
                for (Building b : v.buildings()) if (b.type == org.webtrade.minecraftportsmod.colony.BuildingType.HUT && b.overlaps(at[0], 1, 0)) homes++;
                log("handed over {}: bed blocks {}, door blocks {}, the village's buildings on the plot {}, achievement home {}", done[0], beds, doors,
                        homes, has(s, "home"));
                if (!done[0] || beds == 0 || doors == 0 || homes > 0 || !has(s, "home")) throw new AssertionError("the hut was not built and handed over");
                var p = player(s);
                p.teleportTo(level, at[0].getX() + 9.5, at[0].getY() + 6, at[0].getZ() + 9.5, java.util.Set.of(), 135, 25, false);
            });
            context.waitTicks(40);
            context.takeScreenshot("plot_d_house");
            // taken up: the plot is the village's again (the house stays), the stone back in the hand
            server.runOnServer(s -> {
                ServerLevel level = s.overworld();
                var p = player(s);
                BlockState st = level.getBlockState(stone[0]);
                st.getBlock().playerWillDestroy(level, stone[0], st, p);
                level.setBlockAndUpdate(stone[0], Blocks.AIR.defaultBlockState());
                Village v = VillageData.get(s).get(1);
                log("taken up: plots {}, stones {}", v.tasks().plots().size(), stones(p));
                if (!v.tasks().plots().isEmpty() || stones(p) != 1) throw new AssertionError("the plot should be given back");
            });
        }
    }
}
