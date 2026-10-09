package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Territory;
import org.webtrade.minecraftportsmod.colony.Tidy;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;

import java.util.ArrayList;
import java.util.List;

/**
 * The village's land kept: on several seeds, a village grown for some days, then its land looked over: ponds and
 * puddles left (that it should have filled), pits, a bridge's deck beside another at another height, trees cut off
 * (a trunk with no crown, a crown with no trunk). Logged, with shots from above; it fails if water is left, or more than a few pits.
 */
public class HolesClientGameTest implements FabricClientGameTest {

    /** The seeds (more with -Dmpm.holesSeeds=a,b,c). */
    private static final String[] SEEDS = System.getProperty("mpm.holesSeeds", "1732842924495830164,4242,777,12345,2026,31337").split(",");
    private static final int DAYS = Integer.getInteger("mpm.holesDays", 40);

    /** The first pits found at the end, for a shot of each. */
    private static final List<BlockPos> PITS = new ArrayList<>();

    private static void log(String tag, String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[holes " + tag + "] " + fmt, args);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        List<String> bad = new ArrayList<>();
        for (String seed : SEEDS) run(context, seed.trim(), bad);
        if (!bad.isEmpty()) throw new AssertionError("the land is not kept: " + String.join("; ", bad));
    }

    private void run(ClientGameTestContext context, String seed, List<String> bad) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed(seed)).create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            context.getInput().resizeWindow(1920, 1080);
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("time set 3000");
            server.waitFor(s -> VillageData.get(s).all().size() >= 1, 20 * 600);
            int[] id = {-1};
            server.runOnServer(s -> {
                BlockPos spawn = s.getPlayerList().getPlayers().getFirst().blockPosition();
                double best = Double.MAX_VALUE;
                for (Village v : VillageData.get(s).all()) {
                    if (v.center.distSqr(spawn) < best) {
                        best = v.center.distSqr(spawn);
                        id[0] = v.id;
                    }
                }
                Village v = VillageData.get(s).get(id[0]);
                log(seed, "village #{} {} at {}", v.id, v.name, v.center.toShortString());
                s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), v.center.getX() + 0.5, v.center.getY() + 30,
                        v.center.getZ() + 30.5, java.util.Set.of(), 180, 45, false);
            });
            final int vid = id[0];
            context.waitTicks(60);
            sp.getConnection().waitForChunksRender();
            for (int day = 1; day <= DAYS; day++) {
                server.runCommand("village day");
                context.waitTicks(50);
                if (day % 10 == 0) {
                    final int d = day;
                    server.runOnServer(s -> {
                        Village v = VillageData.get(s).get(vid);
                        log(seed, "day {}: people {} buildings {} land {} m2; {}", d, v.population(), v.buildings().size(), Territory.area(v),
                                look(s, v, false));
                    });
                }
            }
            // a working day as it goes, the people at the upkeep with their own hands
            context.waitTicks(20 * 120);
            int[] left = new int[1];
            PITS.clear();
            server.runOnServer(s -> {
                Village v = VillageData.get(s).get(vid);
                String what = look(s, v, true);
                log(seed, "at the end: {}", what);
                // (no water left lying; a few pits at most: those the people are at, the land as it goes on changing)
                left[0] = what.contains("ponds 0 ") && pitsOf(what) <= 10 ? 0 : 1;
                if (left[0] != 0) bad.add(seed + ": " + what);
            });
            for (String[] view : new String[][]{{"top", "0", "60", "0", "90"}, {"south", "0", "40", "50", "40"}}) {
                server.runOnServer(s -> {
                    Village v = VillageData.get(s).get(vid);
                    s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), v.center.getX() + 0.5 + Integer.parseInt(view[1]),
                            v.center.getY() + Integer.parseInt(view[2]), v.center.getZ() + 0.5 + Integer.parseInt(view[3]), java.util.Set.of(), 180,
                            Integer.parseInt(view[4]), false);
                });
                context.waitTicks(80);
                sp.getConnection().waitForChunksRender();
                context.takeScreenshot("holes_" + seed + "_" + view[0]);
            }
            for (int i = 0; i < PITS.size(); i++) {
                BlockPos p = PITS.get(i);
                server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), p.getX() + 6.5, p.getY() + 7,
                        p.getZ() + 6.5, java.util.Set.of(), 135, 40, false));
                context.waitTicks(60);
                sp.getConnection().waitForChunksRender();
                context.takeScreenshot("holes_" + seed + "_pit" + i);
            }
        }
    }

    private static int pitsOf(String what) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("pits ([0-9]+)").matcher(what);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /** The land looked over: what is left on it that should not be (and, {@code where}: where each pond and pit is). */
    private static String look(MinecraftServer s, Village v, boolean where) {
        ServerLevel level = s.overworld();
        int ponds = 0, pondCols = 0, pits = 0, decks = 0, bare = 0, cols = 0;
        java.util.Set<BlockPos> seenPond = new java.util.HashSet<>();
        StringBuilder at = new StringBuilder();
        for (long k : Territory.cells(v)) {
            int cx = (int) (k >> 32), cz = (int) k;
            for (int dx = 0; dx < Territory.CELL; dx++) {
                for (int dz = 0; dz < Territory.CELL; dz++) {
                    int x = cx * Territory.CELL + dx, z = cz * Territory.CELL + dz;
                    if (!Tidy.kept(v, x, z)) continue;
                    cols++;
                    int g = Tidy.ground(level, x, z);
                    BlockPos top = new BlockPos(x, g, z);
                    BlockState st = level.getBlockState(top), up = level.getBlockState(top.above());
                    // water lying on the land: a pond the village fills
                    BlockPos w = !up.getFluidState().isEmpty() ? top.above() : !st.getFluidState().isEmpty() ? top : null;
                    if (w != null) {
                        if (Tidy.pond(level, w)) {
                            pondCols++;
                            if (seenPond.add(w)) {
                                boolean fresh = true;
                                for (BlockPos o : seenPond) if (o != w && Math.abs(o.getX() - x) + Math.abs(o.getZ() - z) <= 2) fresh = false;
                                if (fresh) {
                                    ponds++;
                                    if (where) at.append("pond ").append(w.toShortString()).append("; ");
                                }
                            }
                        }
                        continue;
                    }
                    int sunk = Tidy.sunk(level, v, x, z);
                    if (sunk >= 2) {
                        pits++;
                        if (where && pits <= 12) {
                            at.append("pit ").append(x).append(',').append(g).append(',').append(z).append(" by ").append(sunk).append(' ')
                                    .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath()).append("; ");
                            if (PITS.size() < 3) PITS.add(top);
                        }
                    }
                    // a deck beside another at another height
                    if (st.is(BlockTags.PLANKS)) {
                        for (int[] d : new int[][]{{1, 0}, {0, 1}}) {
                            int ng = Tidy.ground(level, x + d[0], z + d[1]);
                            if (level.getBlockState(new BlockPos(x + d[0], ng, z + d[1])).is(BlockTags.PLANKS) && Math.abs(ng - g) >= 1) decks++;
                        }
                    }
                    // a trunk with no crown
                    if (up.is(BlockTags.LOGS)) {
                        boolean crown = false;
                        for (int k2 = 2; k2 <= 14 && !crown; k2++) crown = level.getBlockState(top.above(k2)).is(BlockTags.LEAVES);
                        if (!crown) bare++;
                    }
                }
            }
        }
        return "ponds " + ponds + " (" + pondCols + " columns of water), pits " + pits + " , decks at two heights " + decks + ", bare trunks " + bare
                + ", on " + cols + " columns" + (at.length() > 0 ? " | " + at : "");
    }
}
