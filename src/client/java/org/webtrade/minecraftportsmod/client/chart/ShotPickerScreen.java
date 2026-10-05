package org.webtrade.minecraftportsmod.client.chart;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The player's own screenshots (the game's screenshots folder, the newest first), to put one on a mark of the
 * world map: a click sends it and goes back to the map.
 */
public class ShotPickerScreen extends UiScreen {

    private static final int COLS = 4, MAX = 40;

    private final Screen back;
    private final int mark;
    private final List<Path> files = new ArrayList<>();
    private final List<Identifier> thumbs = new ArrayList<>();
    private final List<int[]> sizes = new ArrayList<>();
    private int loaded;
    private int scroll;
    private int fx0, fy0, fx1, fy1, cw, ch;

    public ShotPickerScreen(Screen back, int mark) {
        super(Component.translatable("minecraftportsmod.worldmap.pick"));
        this.back = back;
        this.mark = mark;
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified()))
                    .limit(MAX).forEach(files::add);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void layout() {
        int m = width < 360 ? 4 : 10;
        fx0 = m;
        fy0 = m;
        fx1 = width - m;
        fy1 = height - m;
        cw = (fx1 - fx0 - 12 - (COLS - 1) * 6) / COLS;
        ch = cw * 9 / 16;
        addRenderableWidget(UiButton.make(Component.translatable("gui.back"), b -> onClose()).bounds(fx1 - 76, fy0 + 3, 70, 16).build());
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(back);
    }

    @Override
    public void removed() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (Identifier id : thumbs) if (id != null) tm.release(id);
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // a few small pictures a frame
        for (int n = 0; n < 2 && loaded < files.size(); n++, loaded++) thumb(files.get(loaded));
        Ui.frame(g, fx0, fy0, fx1, fy1);
        g.text(font, title, fx0 + 12, fy0 + 8, ChartStyle.TEXT_LIGHT, false);
        int y0 = fy0 + 24;
        g.fill(fx0 + 4, y0 - 2, fx1 - 4, fy1 - 4, ChartStyle.PARCHMENT_DARK);
        g.enableScissor(fx0 + 4, y0 - 2, fx1 - 4, fy1 - 4);
        for (int i = 0; i < files.size(); i++) {
            int x = fx0 + 6 + (i % COLS) * (cw + 6), y = y0 + (i / COLS) * (ch + 6) - scroll;
            if (y > fy1 || y + ch < y0) continue;
            boolean hot = mouseX >= x && mouseX < x + cw && mouseY >= y && mouseY < y + ch;
            g.fill(x - 1, y - 1, x + cw + 1, y + ch + 1, hot ? ChartStyle.BRASS : ChartStyle.INK_SOFT);
            Identifier t = i < thumbs.size() ? thumbs.get(i) : null;
            if (t != null) {
                int[] sz = sizes.get(i);
                g.blit(RenderPipelines.GUI_TEXTURED, t, x, y, 0, 0, cw, ch, sz[0], sz[1], sz[0], sz[1]);
            } else {
                g.fill(x, y, x + cw, y + ch, ChartStyle.PARCHMENT_SHADE);
            }
        }
        g.disableScissor();
        widgets(g, mouseX, mouseY, partialTick);
    }

    private void thumb(Path file) {
        try (InputStream in = Files.newInputStream(file); NativeImage full = NativeImage.read(in)) {
            int w = 256, h = Math.max(1, Math.round(full.getHeight() * (w / (float) full.getWidth())));
            NativeImage small = new NativeImage(w, h, false);
            full.resizeSubRectTo(0, 0, full.getWidth(), full.getHeight(), small);
            Identifier id = Minecraftportsmod.id("pick/" + thumbs.size());
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, small));
            thumbs.add(id);
            sizes.add(new int[]{w, h});
        } catch (Exception e) {
            thumbs.add(null);
            sizes.add(new int[]{1, 1});
        }
    }

    @Override
    protected boolean uiClick(MouseButtonEvent event, boolean doubleClick) {
        if (super.uiClick(event, doubleClick)) return true;
        int y0 = fy0 + 24;
        for (int i = 0; i < files.size(); i++) {
            int x = fx0 + 6 + (i % COLS) * (cw + 6), y = y0 + (i / COLS) * (ch + 6) - scroll;
            if (event.x() >= x && event.x() < x + cw && event.y() >= y && event.y() < y + ch && event.y() >= y0) {
                try (InputStream in = Files.newInputStream(files.get(i))) {
                    Shots.send(NativeImage.read(in), mark);
                } catch (Exception ignored) {
                }
                onClose();
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean uiScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        int rows = (files.size() + COLS - 1) / COLS;
        int max = Math.max(0, rows * (ch + 6) - (fy1 - fy0 - 30));
        scroll = Math.max(0, Math.min(max, scroll - (int) (scrollY * 30)));
        return true;
    }
}
