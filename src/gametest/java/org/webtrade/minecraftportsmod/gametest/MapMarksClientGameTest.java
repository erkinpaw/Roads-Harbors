package org.webtrade.minecraftportsmod.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.chart.MapMarks;
import org.webtrade.minecraftportsmod.chart.WorldMapService;
import org.webtrade.minecraftportsmod.client.chart.ShotPickerScreen;
import org.webtrade.minecraftportsmod.client.chart.Shots;
import org.webtrade.minecraftportsmod.client.chart.WorldMapScreen;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.network.WorldMapPayloads;

import java.nio.file.Files;

/**
 * The world map's marks: two put down (a castle with a name, a tower), a screenshot taken for the first (sent to the
 * server, kept there as a file, fetched back), the first opened at the right with its screenshot, the screenshot
 * shown large, the villages hidden, the picker of the player's own screenshots, a mark taken away with its
 * screenshot. On a 1920×1080 window, with screenshots of each step.
 */
public class MapMarksClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Minecraftportsmod.LOGGER.info("[marks] " + fmt, args);
    }

    private static WorldMapScreen map(ClientGameTestContext context) {
        WorldMapScreen[] m = {null};
        context.runOnClient(mc -> m[0] = mc.gui.screen() instanceof WorldMapScreen w ? w : null);
        return m[0];
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("4242")).create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            context.getInput().resizeWindow(1920, 1080);
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 6000");
            server.waitFor(s -> VillageData.get(s).all().size() >= 1, 20 * 600);
            // near the first village, up in the air: something to see in the screenshot
            server.runOnServer(s -> {
                var v = VillageData.get(s).all().iterator().next();
                var p = s.getPlayerList().getPlayers().getFirst();
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "gamemode spectator @a");
                p.teleportTo(s.overworld(), v.center.getX() + 30.5, v.center.getY() + 25, v.center.getZ() + 30.5, java.util.Set.of(), 135, 30, false);
            });
            context.waitTicks(80);
            sp.getConnection().waitForChunksRender();
            int[] at = {0, 0};
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                at[0] = p.getBlockX();
                at[1] = p.getBlockZ();
                WorldMapService.handle(p, new WorldMapPayloads.MarkAction(WorldMapPayloads.MarkAction.CREATE, 0, at[0], at[1], "castle", "Моя база"));
                WorldMapService.handle(p, new WorldMapPayloads.MarkAction(WorldMapPayloads.MarkAction.CREATE, 0, at[0] + 90, at[1] - 60, "tower", "Смотровая"));
                log("marks {}", MapMarks.get(s).marks().size());
            });
            int[] castle = {-1};
            server.runOnServer(s -> {
                for (MapMarks.Mark m : MapMarks.get(s).marks()) if (m.icon.equals("castle")) castle[0] = m.id;
            });
            if (castle[0] < 0) throw new AssertionError("the mark was not put down");
            // a screenshot for it
            context.runOnClient(mc -> net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(),
                    img -> mc.execute(() -> Shots.send(img, castle[0]))));
            context.waitTicks(40);
            int[] shot = {-1};
            server.runOnServer(s -> {
                MapMarks marks = MapMarks.get(s);
                MapMarks.Mark m = marks.mark(castle[0]);
                if (m != null && !m.shots.isEmpty()) shot[0] = m.shots.getFirst();
                long size = -1;
                try {
                    if (shot[0] >= 0) size = Files.size(MapMarks.shotFile(s, shot[0]));
                } catch (Exception ignored) {
                }
                log("shot {} on the mark, file {} bytes", shot[0], size);
            });
            if (shot[0] < 0) throw new AssertionError("the screenshot did not reach the mark");

            // the map, the mark open at the right with its screenshot
            server.runOnServer(s -> WorldMapService.send(s.getPlayerList().getPlayers().getFirst(), false));
            context.waitTicks(30);
            if (map(context) == null) throw new AssertionError("the world map did not open");
            context.runOnClient(mc -> map(context).open(castle[0]));
            context.waitTicks(60);
            context.takeScreenshot("marks_a_panel");
            context.runOnClient(mc -> map(context).view(shot[0]));
            context.waitTicks(20);
            context.takeScreenshot("marks_b_view");
            context.runOnClient(mc -> map(context).view(-1));
            // the villages hidden (for a video)
            context.runOnClient(mc -> {
                map(context).toggle(0);
                map(context).zoomOut(3);
            });
            context.waitTicks(40);
            context.takeScreenshot("marks_c_no_villages");
            context.runOnClient(mc -> map(context).toggle(0));
            // the picker of the player's own screenshots
            context.runOnClient(mc -> mc.gui.setScreen(new ShotPickerScreen(mc.gui.screen(), castle[0])));
            context.waitTicks(40);
            context.takeScreenshot("marks_d_picker");
            context.setScreen(() -> null);
            // the mark taken away: its screenshot and file with it
            server.runOnServer(s -> WorldMapService.handle(s.getPlayerList().getPlayers().getFirst(),
                    new WorldMapPayloads.MarkAction(WorldMapPayloads.MarkAction.DELETE, castle[0], 0, 0, "", "")));
            context.waitTicks(10);
            server.runOnServer(s -> {
                MapMarks marks = MapMarks.get(s);
                boolean fileGone = !Files.exists(MapMarks.shotFile(s, shot[0]));
                log("after delete: marks {}, shots {}, file gone {}", marks.marks().size(), marks.shots().size(), fileGone);
                if (marks.mark(castle[0]) != null || marks.shot(shot[0]) != null || !fileGone) {
                    throw new AssertionError("the mark or its screenshot stayed");
                }
            });
        }
    }
}
