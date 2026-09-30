package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import org.webtrade.minecraftportsmod.economy.Good;
import org.webtrade.minecraftportsmod.economy.Profession;
import org.webtrade.minecraftportsmod.economy.Settlement;
import org.webtrade.minecraftportsmod.economy.Specialization;
import org.webtrade.minecraftportsmod.network.SettlementPayloads;
import org.webtrade.minecraftportsmod.network.SettlementPayloads.GoodRow;
import org.webtrade.minecraftportsmod.network.SettlementPayloads.TradeLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The town hall of a settlement: its stores and prices, its people and their needs, its ships and markets, and the
 * chronicle of what happened. Refreshes itself while open, so you can watch the settlement live.
 */
public class TownHallScreen extends UiScreen {

    private enum Tab {GOODS, PEOPLE, TRADE, CHRONICLE}

    private static final int ROW = 18;
    private static final int TAB_HEIGHT = 14;
    private static final int REFRESH_TICKS = 40;

    private final Screen parent;
    private final int portId;
    private SettlementPayloads.View view;
    private Tab tab = Tab.GOODS;
    private int scroll;
    private int selectedGood = -1;
    private int ticks;

    private int x0, y0, x1, y1;       // frame
    private int cx0, cy0, cx1, cy1;   // content area (below the summary and tabs)

    public TownHallScreen(Screen parent, int portId) {
        super(Component.translatable("minecraftportsmod.hall.title"));
        this.parent = parent;
        this.portId = portId;
    }

    public int portId() {
        return portId;
    }

    public void update(SettlementPayloads.View view) {
        if (view.portId() != portId) return;
        boolean first = this.view == null;
        this.view = view;
        if (first && !view.goods().isEmpty()) selectedGood = mostInteresting(view);
    }

    /** Switches tabs programmatically (automated tests). */
    public void showTab(String name) {
        for (Tab t : Tab.values()) if (t.name().equalsIgnoreCase(name)) tab = t;
        scroll = 0;
    }

    private static int mostInteresting(SettlementPayloads.View v) {
        GoodRow best = null;
        for (GoodRow r : v.goods()) if (best == null || r.stock() * Good.values()[r.good()].basePrice > best.stock() * Good.values()[best.good()].basePrice) best = r;
        return best == null ? -1 : best.good();
    }

    @Override
    protected void layout() {
        int w = Math.min(width - 24, 705), h = Math.min(height - 24, 450);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        cx0 = x0 + 8;
        cx1 = x1 - 8;
        cy0 = y0 + 20 + 30 + TAB_HEIGHT + 2;
        cy1 = y1 - 8;
        if (view == null) ClientPlayNetworking.send(new SettlementPayloads.Request(portId));
    }

    @Override
    public void tick() {
        if (++ticks % REFRESH_TICKS == 0) ClientPlayNetworking.send(new SettlementPayloads.Request(portId));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.frame(g, x0, y0, x1, y1);
        if (view == null) {
            g.centeredText(font, Component.translatable("minecraftportsmod.hall.loading"), (x0 + x1) / 2, (y0 + y1) / 2, ChartStyle.TEXT_LIGHT);
            widgets(g, mouseX, mouseY, partialTick);
            return;
        }
        drawHeader(g);
        drawSummary(g, mouseX, mouseY);
        drawTabs(g, mouseX, mouseY);
        Ui.blit(g, Ui.PARCHMENT, cx0 - 1, cy0 - 1, cx1 + 1, cy1 + 1);
        g.enableScissor(cx0, cy0, cx1, cy1);
        switch (tab) {
            case GOODS -> drawGoods(g, mouseX, mouseY);
            case PEOPLE -> drawPeople(g, mouseX, mouseY);
            case TRADE -> drawTrade(g);
            case CHRONICLE -> drawChronicle(g);
        }
        g.disableScissor();
        widgets(g, mouseX, mouseY, partialTick);
    }

    private void drawHeader(GuiGraphicsExtractor g) {
        Component title = Component.translatable("minecraftportsmod.hall.title_of", view.name());
        g.text(font, title, x0 + 10, y0 + 7, ChartStyle.TEXT_LIGHT, true);
        // the economy clock: day number and how far into it we are
        Component day = Component.translatable("minecraftportsmod.hall.day", view.day());
        int bw = 50, bx = x1 - 12 - bw, by = y0 + 9;
        g.fill(bx, by, bx + bw, by + 4, ChartStyle.WOOD_DARK);
        g.fill(bx, by, bx + Math.round(bw * view.progress()), by + 4, ChartStyle.BRASS);
        g.text(font, day, bx - 6 - font.width(day), y0 + 7, ChartStyle.PARCHMENT_SHADE, false);
    }

    private void drawSummary(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int sy0 = y0 + 20, sy1 = sy0 + 28;
        g.fill(x0 + 7, sy0 - 1, x1 - 7, sy1 + 1, ChartStyle.PARCHMENT_SHADE);
        g.fill(x0 + 8, sy0, x1 - 8, sy1, ChartStyle.PARCHMENT_DARK);
        Specialization spec = Specialization.values()[view.spec()];
        Settlement.Level level = Settlement.Level.values()[view.level()];
        int x = x0 + 12;
        g.fill(x, sy0 + 4, x + 4, sy0 + 12, 0xFF000000 | SettlementStyle.specColor(spec));
        Component kind = Component.empty().append(level.displayName()).append(" · ").append(spec.displayName());
        g.text(font, kind, x + 7, sy0 + 4, ChartStyle.TEXT, false);
        Component people = Component.translatable("minecraftportsmod.hall.population", view.population(),
                view.houses() * Settlement.RESIDENTS_PER_HOUSE, view.houses());
        // what is going on: events first, else the way to the next level
        Component status = null;
        if (view.effects().length >= 2) {
            var ev = org.webtrade.minecraftportsmod.economy.EventType.values()[view.effects()[0]];
            status = Component.translatable("minecraftportsmod.hall.event", ev.displayName(), view.effects()[1]).withColor(0xA33A2A);
        } else if (view.levelDays() > 0) {
            status = Component.translatable("minecraftportsmod.hall.rising", Settlement.Level.values()[Math.min(view.level() + 1, 3)].displayName(),
                    view.levelDays(), 3).withColor(0x3F7A3A);
        } else if (view.levelDays() < 0) {
            status = Component.translatable("minecraftportsmod.hall.falling", -view.levelDays(), 6).withColor(0xA33A2A);
        }
        if (status != null && mouseX >= x && mouseX < x + 200 && mouseY >= sy0 + 14 && mouseY < sy1) people = status;
        g.text(font, status != null && (ticks / 60) % 2 == 1 ? status : people, x, sy0 + 16, ChartStyle.TEXT_MUTED, false);

        String money = String.format(Locale.ROOT, "%.1f", view.treasury());
        Component treasury = Component.translatable("minecraftportsmod.hall.treasury");
        int tx = x1 - 14 - font.width(money);
        int treasuryLeft = tx - 22 - font.width(treasury);

        // mood, between the two
        int mx = x + Math.max(font.width(kind) + 7, font.width(people)) + 16;
        int mw = Math.min(90, treasuryLeft - 14 - mx);
        Component mood = Component.translatable("minecraftportsmod.hall.mood");
        if (mw >= 30) {
            g.text(font, mood, mx, sy0 + 4, ChartStyle.TEXT, false);
            bar(g, mx, sy0 + 16, mw, 6, view.happiness());
        }

        // treasury
        g.item(new ItemStack(Items.EMERALD), tx - 18, sy0 + 6);
        g.text(font, money, tx, sy0 + 10, ChartStyle.TEXT, false);
        g.text(font, treasury, treasuryLeft, sy0 + 10, ChartStyle.TEXT_MUTED, false);
    }

    private void drawTabs(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = cx0;
        int y = cy0 - TAB_HEIGHT - 1;
        for (Tab t : Tab.values()) {
            Component label = tabLabel(t);
            int w = font.width(label) + 14;
            boolean active = t == tab;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + TAB_HEIGHT;
            Ui.blit(g, active ? Ui.TAB_OPEN : hover ? Ui.TAB_HOVER : Ui.TAB, x, y, x + w, y + TAB_HEIGHT + (active ? 1 : 0));
            g.text(font, label, x + 7, y + 3, active ? ChartStyle.TEXT : ChartStyle.PARCHMENT_SHADE, false);
            x += w + 2;
        }
    }

    private Component tabLabel(Tab t) {
        return Component.translatable("minecraftportsmod.hall.tab." + t.name().toLowerCase(Locale.ROOT));
    }

    private Tab tabAt(double mx, double my) {
        int x = cx0;
        int y = cy0 - TAB_HEIGHT - 1;
        if (my < y || my >= y + TAB_HEIGHT) return null;
        for (Tab t : Tab.values()) {
            int w = font.width(tabLabel(t)) + 14;
            if (mx >= x && mx < x + w) return t;
            x += w + 2;
        }
        return null;
    }

    // --- goods: the market board

    private int goodsListRight() {
        return cx1 - Math.min(170, (cx1 - cx0) * 2 / 5);
    }

    private int visibleRows() {
        return Math.max(1, (cy1 - cy0 - 14) / ROW);
    }

    private void drawGoods(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int lx1 = goodsListRight();
        List<GoodRow> rows = view.goods();
        int colStock = lx1 - 128, colDelta = lx1 - 82, colPrice = lx1 - 40;
        g.text(font, Component.translatable("minecraftportsmod.hall.col.good"), cx0 + 24, cy0 + 3, ChartStyle.TEXT_MUTED, false);
        rightText(g, Component.translatable("minecraftportsmod.hall.col.stock"), colStock + 30, cy0 + 3, ChartStyle.TEXT_MUTED);
        rightText(g, Component.translatable("minecraftportsmod.hall.col.day"), colDelta + 34, cy0 + 3, ChartStyle.TEXT_MUTED);
        rightText(g, Component.translatable("minecraftportsmod.hall.col.price_stack"), lx1 - 2, cy0 + 3, ChartStyle.TEXT_MUTED);
        clampScroll(rows.size());
        int y = cy0 + 13;
        for (int i = scroll; i < rows.size() && y + ROW <= cy1; i++, y += ROW) {
            GoodRow r = rows.get(i);
            Good good = Good.values()[r.good()];
            boolean sel = r.good() == selectedGood;
            boolean hover = mouseX >= cx0 && mouseX < lx1 && mouseY >= y && mouseY < y + ROW;
            if (sel) g.fill(cx0, y, lx1, y + ROW, 0x40A8322A);
            else if (hover) g.fill(cx0, y, lx1, y + ROW, 0x20000000);
            g.item(new ItemStack(good.item), cx0 + 3, y + 1);
            g.text(font, font.plainSubstrByWidth(good.displayName().getString(), colStock - cx0 - 30), cx0 + 24, y + 5, ChartStyle.TEXT, false);
            rightText(g, Component.literal(amount(r.stock())), colStock + 30, y + 5, ChartStyle.TEXT);
            double delta = r.produced() - r.consumed();
            String d = Math.abs(delta) < 0.05 ? "·" : (delta > 0 ? "+" : "−") + amount(Math.abs(delta));
            rightText(g, Component.literal(d), colDelta + 34, y + 5, delta > 0.05 ? ChartStyle.GOOD : delta < -0.05 ? ChartStyle.BAD : ChartStyle.TEXT_MUTED);
            int trend = trend(r);
            String arrow = trend > 0 ? "▲" : trend < 0 ? "▼" : "";
            rightText(g, Component.literal(price(r.price() * 64)), colPrice + 28, y + 5, priceColor(r, good));
            g.text(font, arrow, colPrice + 30, y + 5, trend > 0 ? ChartStyle.BAD : ChartStyle.GOOD, false);
        }
        drawScrollbar(g, lx1 - 3, cy0 + 13, cy1, rows.size(), visibleRows());
        g.fill(lx1, cy0, lx1 + 1, cy1, ChartStyle.PARCHMENT_SHADE);
        drawGoodDetails(g, lx1 + 1, mouseX, mouseY);
    }

    /** +1 when the price rose over the last days, -1 when it fell. */
    private static int trend(GoodRow r) {
        float[] h = r.history();
        if (h.length < 2) return 0;
        float then = h[Math.max(0, h.length - 4)];
        float now = r.price();
        if (then <= 0) return 0;
        double ch = (now - then) / then;
        return ch > 0.08 ? 1 : ch < -0.08 ? -1 : 0;
    }

    private static int priceColor(GoodRow r, Good good) {
        double rel = r.price() / good.basePrice;
        return rel > 1.6 ? ChartStyle.BAD : rel < 0.6 ? ChartStyle.GOOD : ChartStyle.TEXT;
    }

    private void drawGoodDetails(GuiGraphicsExtractor g, int dx0, int mouseX, int mouseY) {
        GoodRow r = null;
        for (GoodRow row : view.goods()) if (row.good() == selectedGood) r = row;
        int x = dx0 + 6, w = cx1 - x - 6;
        if (r == null) {
            return;
        }
        Good good = Good.values()[r.good()];
        g.item(new ItemStack(good.item), x, cy0 + 4);
        g.text(font, font.plainSubstrByWidth(good.displayName().getString(), w - 20), x + 20, cy0 + 8, ChartStyle.TEXT, false);

        // price chart, as tall as the space under it allows (six detail lines below)
        int details = r.demand() > 0.05 ? 6 : 5;
        int gx0 = x, gy0 = cy0 + 24, gx1 = x + w;
        int gy1 = gy0 + Math.max(20, Math.min(60, cy1 - gy0 - 8 - details * 11));
        g.fill(gx0, gy0, gx1, gy1, ChartStyle.PARCHMENT_DARK);
        g.outline(gx0, gy0, gx1 - gx0, gy1 - gy0, ChartStyle.PARCHMENT_SHADE);
        float[] h = r.history();
        float max = (float) good.basePrice * 1.2F, min = (float) good.basePrice * 0.8F;
        for (float f : h) {
            max = Math.max(max, f);
            min = Math.min(min, f);
        }
        float span = Math.max(1e-6F, max - min);
        // base price guide
        int by = gy1 - 2 - Math.round((float) ((good.basePrice - min) / span * (gy1 - gy0 - 4)));
        for (int i = gx0 + 1; i < gx1 - 1; i += 4) g.fill(i, by, i + 2, by + 1, ChartStyle.TEXT_MUTED);
        if (h.length >= 2) {
            float step = (float) (gx1 - gx0 - 4) / (h.length - 1);
            for (int i = 1; i < h.length; i++) {
                int ax = gx0 + 2 + Math.round((i - 1) * step), bx = gx0 + 2 + Math.round(i * step);
                int ay = gy1 - 2 - Math.round((h[i - 1] - min) / span * (gy1 - gy0 - 4));
                int ay2 = gy1 - 2 - Math.round((h[i] - min) / span * (gy1 - gy0 - 4));
                line(g, ax, ay, bx, ay2, ChartStyle.ROUTE);
            }
        }
        if (mouseX >= gx0 && mouseX < gx1 && mouseY >= gy0 && mouseY < gy1) {
            g.setComponentTooltipForNextFrame(font, List.of(Component.translatable("minecraftportsmod.hall.chart_tip", h.length,
                    price(min), price(max), price(good.basePrice))), mouseX, mouseY);
        }

        int y = gy1 + 6;
        y = detail(g, x, y, w, "minecraftportsmod.hall.d.price", price(r.price()) + " (" + Math.round(r.price() / good.basePrice * 100) + "%)");
        y = detail(g, x, y, w, "minecraftportsmod.hall.d.stock", amount(r.stock()));
        y = detail(g, x, y, w, "minecraftportsmod.hall.d.produced", amount(r.produced()));
        y = detail(g, x, y, w, "minecraftportsmod.hall.d.consumed", amount(r.consumed()));
        y = detail(g, x, y, w, "minecraftportsmod.hall.d.demand", amount(r.demand()));
        if (r.demand() > 0.05) {
            detail(g, x, y, w, "minecraftportsmod.hall.d.days", amount(r.stock() / r.demand()));
        }
    }

    private int detail(GuiGraphicsExtractor g, int x, int y, int w, String key, String value) {
        g.text(font, Component.translatable(key), x, y, ChartStyle.TEXT_MUTED, false);
        g.text(font, value, x + w - font.width(value), y, ChartStyle.TEXT, false);
        return y + 11;
    }

    // --- people

    private void drawPeople(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int mid = (cx0 + cx1) / 2;
        int[] w = view.workers();
        int max = 1;
        for (int i = 1; i < w.length; i += 2) max = Math.max(max, w[i]);
        g.text(font, Component.translatable("minecraftportsmod.hall.workers"), cx0 + 6, cy0 + 5, ChartStyle.TEXT_MUTED, false);
        int y = cy0 + 17;
        for (int i = 0; i + 1 < w.length && y + ROW <= cy1; i += 2, y += ROW) {
            Profession p = Profession.values()[w[i]];
            g.item(SettlementStyle.professionIcon(p), cx0 + 4, y);
            g.text(font, p.displayName(), cx0 + 24, y + 4, ChartStyle.TEXT, false);
            int bx = cx0 + 96, bw = mid - bx - 30;
            g.fill(bx, y + 4, bx + bw, y + 11, ChartStyle.PARCHMENT_DARK);
            g.fill(bx, y + 4, bx + Math.max(2, bw * w[i + 1] / max), y + 11, 0xFF000000 | SettlementStyle.specColor(Specialization.values()[view.spec()]));
            g.text(font, String.valueOf(w[i + 1]), bx + bw + 4, y + 4, ChartStyle.TEXT, false);
        }

        // what the next level asks for: under the workers
        int lx = cx0 + 6, ly = y + 6;
        if (view.level() < Settlement.Level.CITY.ordinal()) {
            Settlement.Level next = Settlement.Level.values()[view.level() + 1];
            g.text(font, Component.translatable("minecraftportsmod.hall.next_level", next.displayName()), lx, ly, ChartStyle.ROUTE, false);
            ly += 11;
            ly = requirement(g, lx, ly, Component.translatable("minecraftportsmod.hall.req.people", next.population), view.population() >= next.population);
            int si = java.util.Arrays.asList(SettlementPayloads.NEEDS).indexOf("survival");
            int ei = java.util.Arrays.asList(SettlementPayloads.NEEDS).indexOf("everyday");
            int pi = java.util.Arrays.asList(SettlementPayloads.NEEDS).indexOf("prosperity");
            ly = requirement(g, lx, ly, Component.translatable("minecraftportsmod.hall.req.survival", 90), view.needs()[si] >= 0.9);
            if (next.ordinal() >= Settlement.Level.TOWN.ordinal()) {
                ly = requirement(g, lx, ly, Component.translatable("minecraftportsmod.hall.req.everyday", next == Settlement.Level.CITY ? 80 : 75),
                        view.needs()[ei] >= (next == Settlement.Level.CITY ? 0.8 : 0.75));
            }
            if (next == Settlement.Level.CITY) {
                ly = requirement(g, lx, ly, Component.translatable("minecraftportsmod.hall.req.prosperity", 60), view.needs()[pi] >= 0.6);
            }
        }

        g.fill(mid, cy0 + 4, mid + 1, cy1 - 4, ChartStyle.PARCHMENT_SHADE);
        int x = mid + 8, bw = cx1 - x - 44;
        g.text(font, Component.translatable("minecraftportsmod.hall.needs"), x, cy0 + 5, ChartStyle.TEXT_MUTED, false);
        y = cy0 + 18;
        boolean aspiring = view.level() >= Settlement.Level.VILLAGE.ordinal();
        String[] shown = {"survival", "everyday", "prosperity", "housing", "productivity"};
        for (String need : shown) {
            int i = java.util.Arrays.asList(SettlementPayloads.NEEDS).indexOf(need);
            if (i < 0 || i >= view.needs().length) continue;
            boolean locked = need.equals("prosperity") && !aspiring;
            g.text(font, Component.translatable("minecraftportsmod.hall.need." + need), x, y, locked ? ChartStyle.TEXT_MUTED : ChartStyle.TEXT, false);
            if (locked) {
                g.text(font, Component.translatable("minecraftportsmod.hall.locked"), x, y + 9, ChartStyle.TEXT_MUTED, false);
            } else {
                bar(g, x, y + 10, bw, 5, view.needs()[i]);
                g.text(font, Math.round(view.needs()[i] * 100) + "%", x + bw + 6, y + 8, ChartStyle.TEXT_MUTED, false);
            }
            y += 19;
        }
    }

    private int requirement(GuiGraphicsExtractor g, int x, int y, Component text, boolean ok) {
        if (y + 9 > cy1) return y;
        g.text(font, Component.literal(ok ? "✔ " : "✘ ").append(text), x + 4, y, ok ? ChartStyle.GOOD : ChartStyle.BAD, false);
        return y + 10;
    }

    // --- trade

    private void drawTrade(GuiGraphicsExtractor g) {
        int x = cx0 + 6, w = cx1 - cx0 - 12;
        int y = cy0 + 5 - scroll * ROW;
        g.text(font, Component.translatable("minecraftportsmod.hall.ships"), x, y, ChartStyle.TEXT_MUTED, false);
        y += 13;
        boolean any = false;
        for (TradeLine t : view.trade()) {
            if (t.visitor()) continue;
            any = true;
            y = tradeLine(g, t, x, y, w);
        }
        if (!any) {
            g.text(font, Component.translatable("minecraftportsmod.hall.no_ships"), x + 4, y, ChartStyle.TEXT_MUTED, false);
            y += 12;
        }
        y += 6;
        g.text(font, Component.translatable("minecraftportsmod.hall.visitors"), x, y, ChartStyle.TEXT_MUTED, false);
        y += 13;
        any = false;
        for (TradeLine t : view.trade()) {
            if (!t.visitor()) continue;
            any = true;
            y = tradeLine(g, t, x, y, w);
        }
        if (!any) {
            g.text(font, Component.translatable("minecraftportsmod.hall.no_visitors"), x + 4, y, ChartStyle.TEXT_MUTED, false);
            y += 12;
        }
        y += 6;
        g.text(font, Component.translatable("minecraftportsmod.hall.markets"), x, y, ChartStyle.TEXT_MUTED, false);
        y += 13;
        if (view.markets().isEmpty()) {
            g.text(font, Component.translatable("minecraftportsmod.hall.no_markets"), x + 4, y, ChartStyle.TEXT_MUTED, false);
        }
        for (SettlementPayloads.Market m : view.markets()) {
            Component age = m.daysAgo() == 0 ? Component.translatable("minecraftportsmod.hall.news_today")
                    : Component.translatable("minecraftportsmod.hall.news_days", m.daysAgo());
            String mark = m.relation() >= 30 ? "★ " : "⚓ ";
            g.text(font, mark + m.name(), x + 4, y, m.reachable() ? ChartStyle.TEXT : ChartStyle.TEXT_MUTED, false);
            if (m.relation() >= 1) {
                Component rel = Component.translatable(m.relation() >= 30 ? "minecraftportsmod.hall.partner" : "minecraftportsmod.hall.goodwill",
                        Math.round(m.relation()));
                g.text(font, rel, x + 14 + font.width(mark + m.name()), y, m.relation() >= 30 ? ChartStyle.GOOD : ChartStyle.TEXT_MUTED, false);
            }
            g.text(font, age, x + w - font.width(age), y, ChartStyle.TEXT_MUTED, false);
            y += 12;
        }
    }

    private int tradeLine(GuiGraphicsExtractor g, TradeLine t, int x, int y, int w) {
        Component status = switch (t.phase()) {
            case 0 -> Component.translatable("minecraftportsmod.hall.phase.loading", t.partner());
            case 1 -> Component.translatable(t.visitor() ? "minecraftportsmod.hall.phase.coming" : "minecraftportsmod.hall.phase.outbound", t.partner());
            case 2 -> Component.translatable(t.visitor() ? "minecraftportsmod.hall.phase.here" : "minecraftportsmod.hall.phase.trading", t.partner());
            case 3 -> Component.translatable("minecraftportsmod.hall.phase.return", t.partner());
            default -> Component.translatable("minecraftportsmod.hall.phase.idle");
        };
        g.text(font, Component.literal("⛵ " + t.vessel()).withStyle(ChatFormatting.BOLD), x + 4, y, ChartStyle.TEXT, false);
        g.text(font, status, x + w - font.width(status), y, ChartStyle.TEXT_MUTED, false);
        y += 11;
        if (!t.cargo().getString().isEmpty()) {
            for (FormattedCharSequence line : font.split(Component.translatable("minecraftportsmod.hall.cargo", t.cargo()), w - 16)) {
                g.text(font, line, x + 14, y, ChartStyle.INK_SOFT, false);
                y += 10;
            }
        }
        return y + 3;
    }

    // --- chronicle

    private void drawChronicle(GuiGraphicsExtractor g) {
        int x = cx0 + 6, w = cx1 - cx0 - 12;
        int y = cy0 + 5;
        int skip = scroll;
        for (SettlementPayloads.LogLine l : view.log()) {
            if (skip-- > 0) continue;
            String day = Component.translatable("minecraftportsmod.hall.day_short", l.day()).getString();
            int dw = Math.max(34, font.width(day) + 6);
            g.text(font, day, x, y, ChartStyle.TEXT_MUTED, false);
            for (FormattedCharSequence line : font.split(l.text(), w - dw)) {
                g.text(font, line, x + dw, y, ChartStyle.TEXT, false);
                y += 10;
            }
            y += 3;
            if (y > cy1) break;
        }
        if (view.log().isEmpty()) g.text(font, Component.translatable("minecraftportsmod.hall.no_events"), x, y, ChartStyle.TEXT_MUTED, false);
    }

    // ------------------------------------------------------------------ helpers

    private void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float value) {
        float v = Math.max(0, Math.min(1, value));
        int color = v >= 0.8F ? ChartStyle.GOOD : v >= 0.5F ? 0xFFB8892A : ChartStyle.BAD;
        g.fill(x, y, x + w, y + h, ChartStyle.PARCHMENT_SHADE);
        g.fill(x, y, x + Math.round(w * v), y + h, color);
        g.outline(x - 1, y - 1, w + 2, h + 2, ChartStyle.INK_SOFT);
    }

    private void rightText(GuiGraphicsExtractor g, Component text, int right, int y, int color) {
        g.text(font, text, right - font.width(text), y, color, false);
    }

    private static void line(GuiGraphicsExtractor g, int ax, int ay, int bx, int by, int color) {
        int steps = Math.max(Math.abs(bx - ax), Math.abs(by - ay));
        for (int i = 0; i <= steps; i++) {
            int x = ax + (steps == 0 ? 0 : (bx - ax) * i / steps);
            int y = ay + (steps == 0 ? 0 : (by - ay) * i / steps);
            g.fill(x, y, x + 1, y + 2, color);
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor g, int x, int top, int bottom, int total, int visible) {
        if (total <= visible) return;
        int h = bottom - top;
        int th = Math.max(8, h * visible / total);
        int ty = top + (h - th) * scroll / Math.max(1, total - visible);
        g.fill(x, top, x + 2, bottom, ChartStyle.PARCHMENT_SHADE);
        g.fill(x, ty, x + 2, ty + th, ChartStyle.INK_SOFT);
    }

    private void clampScroll(int total) {
        scroll = Math.max(0, Math.min(scroll, total - visibleRows()));
    }

    static String amount(double v) {
        if (v >= 1000) return String.format(Locale.ROOT, "%.1fk", v / 1000);
        if (v >= 100) return String.valueOf(Math.round(v));
        if (v >= 10) return String.format(Locale.ROOT, "%.1f", v);
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String price(double v) {
        if (v >= 10) return String.format(Locale.ROOT, "%.1f", v);
        if (v >= 1) return String.format(Locale.ROOT, "%.2f", v);
        return String.format(Locale.ROOT, "%.3f", v);
    }

    // ------------------------------------------------------------------ input

    @Override
    protected boolean uiClick(MouseButtonEvent event, boolean doubleClick) {
        if (super.uiClick(event, doubleClick)) return true;
        double mx = event.x(), my = event.y();
        Tab t = tabAt(mx, my);
        if (t != null) {
            tab = t;
            scroll = 0;
            return true;
        }
        if (view != null && tab == Tab.GOODS && mx >= cx0 && mx < goodsListRight() && my >= cy0 + 13 && my < cy1) {
            int i = scroll + (int) ((my - cy0 - 13) / ROW);
            if (i >= 0 && i < view.goods().size()) selectedGood = view.goods().get(i).good();
            return true;
        }
        return false;
    }

    @Override
    protected boolean uiScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
        if (view != null && tab == Tab.GOODS) clampScroll(view.goods().size());
        if (view != null && tab == Tab.CHRONICLE) scroll = Math.min(scroll, Math.max(0, view.log().size() - 1));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_TAB) {
            tab = Tab.values()[(tab.ordinal() + 1) % Tab.values().length];
            scroll = 0;
            return true;
        }
        return super.keyPressed(event);
    }
}
