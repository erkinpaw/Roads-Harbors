package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.webtrade.minecraftportsmod.network.SettlementPayloads;

import java.util.List;

/** The world's news: famines, fairs, fires, villages rising, neighbours helping each other. Newest first. */
public class NewsScreen extends UiScreen {

    private static final int REFRESH_TICKS = 60;

    private final Screen parent;
    private List<SettlementPayloads.NewsLine> lines;
    private int scroll, ticks;
    private int x0, y0, x1, y1;

    public NewsScreen(Screen parent) {
        super(Component.translatable("minecraftportsmod.news.title"));
        this.parent = parent;
    }

    public void update(SettlementPayloads.News news) {
        lines = news.lines();
    }

    @Override
    protected void layout() {
        int w = Math.min(width - 24, 660), h = Math.min(height - 24, 435);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        if (lines == null) ClientPlayNetworking.send(new SettlementPayloads.RequestNews());
    }

    @Override
    public void tick() {
        if (++ticks % REFRESH_TICKS == 0) ClientPlayNetworking.send(new SettlementPayloads.RequestNews());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.frame(g, x0, y0, x1, y1);
        g.text(font, title, x0 + 10, y0 + 7, ChartStyle.TEXT_LIGHT, true);
        int cx0 = x0 + 8, cy0 = y0 + 22, cx1 = x1 - 8, cy1 = y1 - 8;
        Ui.blit(g, Ui.PARCHMENT, cx0 - 1, cy0 - 1, cx1 + 1, cy1 + 1);
        g.enableScissor(cx0, cy0, cx1, cy1);
        int y = cy0 + 5;
        if (lines == null || lines.isEmpty()) {
            g.text(font, Component.translatable(lines == null ? "minecraftportsmod.hall.loading" : "minecraftportsmod.news.none"),
                    cx0 + 6, y, ChartStyle.TEXT_MUTED, false);
        } else {
            int skip = scroll;
            long lastDay = Long.MIN_VALUE;
            for (SettlementPayloads.NewsLine l : lines) {
                if (skip-- > 0) continue;
                if (l.day() != lastDay) {
                    // a heading for each day
                    lastDay = l.day();
                    g.text(font, Component.translatable("minecraftportsmod.hall.day", l.day()), cx0 + 6, y, ChartStyle.ROUTE, false);
                    y += 11;
                }
                // the text already names the village
                boolean first = true;
                for (FormattedCharSequence line : font.split(l.text(), cx1 - cx0 - 34)) {
                    if (first) g.text(font, "⚓", cx0 + 10, y, ChartStyle.TEXT, false);
                    g.text(font, line, cx0 + 22, y, ChartStyle.TEXT, false);
                    first = false;
                    y += 10;
                }
                y += 3;
                if (y > cy1) break;
            }
        }
        g.disableScissor();
        widgets(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected boolean uiScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), lines == null ? 0 : Math.max(0, lines.size() - 1)));
        return true;
    }
}
