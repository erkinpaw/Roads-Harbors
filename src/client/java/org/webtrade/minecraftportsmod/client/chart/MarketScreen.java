package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.economy.Good;
import org.webtrade.minecraftportsmod.economy.Profession;
import org.webtrade.minecraftportsmod.network.MarketPayloads;
import org.webtrade.minecraftportsmod.network.MarketPayloads.OrderRow;
import org.webtrade.minecraftportsmod.network.MarketPayloads.Row;

import java.util.Locale;

/**
 * Talking to a resident: the settlement's market (buy from its stores, sell to it — at its prices, which every
 * deal moves) and its orders (bring what it lacks, for a reward).
 */
public class MarketScreen extends UiScreen {

    private enum Tab {MARKET, ORDERS}

    private static final int ROW = 20;
    private static final int TAB_HEIGHT = 14;
    private static final int ORDER_ROW = 46;

    private MarketPayloads.View view;
    private Tab tab = Tab.MARKET;
    private int selected = -1;       // Good ordinal
    private int scroll;

    private int x0, y0, x1, y1, cx0, cy0, cx1, cy1, split;
    private final Button[] deliver = new Button[3];

    // the deal panel: buy or sell, how many (slider or typed), what it costs
    private boolean selling;
    private int qty = 1;
    private Button buyMode, sellMode, confirm;
    private QtySlider slider;
    private net.minecraft.client.gui.components.EditBox qtyBox;
    private boolean syncing;

    public MarketScreen(MarketPayloads.View view) {
        super(Component.literal(view.name()));
        this.view = view;
        if (!view.rows().isEmpty()) selected = view.rows().getFirst().good();
    }

    public void update(MarketPayloads.View view) {
        this.view = view;
        setQty(qty);
    }

    /** Switches tabs programmatically (automated tests). */
    public void showTab(String name) {
        tab = "orders".equalsIgnoreCase(name) ? Tab.ORDERS : Tab.MARKET;
        scroll = 0;
    }

    @Override
    protected void layout() {
        int w = Math.min(width - 24, 690), h = Math.min(height - 24, 435);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        cx0 = x0 + 8;
        cx1 = x1 - 8;
        cy0 = y0 + 34 + TAB_HEIGHT;
        cy1 = y1 - 18;
        split = cx1 - 150;
        int px = split + 6, pw = 138;
        buyMode = addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.market.mode.buy"), b -> setMode(false))
                .bounds(px, cy0 + 24, pw / 2 - 1, 16).build());
        sellMode = addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.market.mode.sell"), b -> setMode(true))
                .bounds(px + pw / 2 + 1, cy0 + 24, pw / 2 - 1, 16).build());
        slider = addRenderableWidget(new QtySlider(px, cy0 + 46, pw, 18));
        qtyBox = addRenderableWidget(new net.minecraft.client.gui.components.EditBox(font, px, cy0 + 70, 56, 16,
                Component.translatable("minecraftportsmod.market.qty")));
        qtyBox.setTextShadow(false);
        qtyBox.setMaxLength(5);
        qtyBox.setResponder(this::typed);
        confirm = addRenderableWidget(UiButton.make(Component.empty(), b -> {
            if (qty > 0) act(selling ? MarketPayloads.Kind.SELL : MarketPayloads.Kind.BUY, selected, qty);
        }).bounds(px, cy1 - 24, pw, 20).build());
        setQty(qty);
        for (int i = 0; i < deliver.length; i++) {
            final int k = i;
            deliver[i] = addRenderableWidget(UiButton.make(Component.empty(), b -> {
                if (k < view.orders().size()) act(MarketPayloads.Kind.DELIVER, view.orders().get(k).id(), 0);
            }).bounds(cx1 - 128, cy0 + 8 + i * ORDER_ROW + 12, 120, 18).build());
        }
    }

    private void act(MarketPayloads.Kind kind, int what, int qty) {
        if (what < 0) return;
        ClientPlayNetworking.send(new MarketPayloads.Action(kind, view.portId(), what, qty));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Row selectedRow() {
        for (Row r : view.rows()) if (r.good() == selected) return r;
        return null;
    }

    // ------------------------------------------------------------------ the deal

    /** Largest quantity that can be dealt now: what they have (or you have) and what the money covers. */
    private int maxQty(Row r) {
        if (r == null) return 0;
        Good g = Good.values()[r.good()];
        if (!selling) {
            int limit = Math.min(r.available(), 64 * 36);
            // the most the player's emeralds can pay for (costs only grow with the quantity)
            int lo = 0, hi = limit;
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (org.webtrade.minecraftportsmod.economy.Market.buyTotal(g, r.target(), r.stock(), mid) <= view.emeralds()) lo = mid;
                else hi = mid - 1;
            }
            return lo;
        }
        int lo = 0, hi = r.have();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (org.webtrade.minecraftportsmod.economy.Market.sellTotal(g, r.target(), r.stock(), mid) <= view.treasury()) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private int total(Row r, int n) {
        Good g = Good.values()[r.good()];
        return selling ? org.webtrade.minecraftportsmod.economy.Market.sellTotal(g, r.target(), r.stock(), n)
                : org.webtrade.minecraftportsmod.economy.Market.buyTotal(g, r.target(), r.stock(), n);
    }

    private void setMode(boolean sell) {
        selling = sell;
        setQty(qty);
    }

    /** Sets the quantity (clamped to 1..max, or 0 if nothing can be dealt) and moves slider and field with it. */
    private void setQty(int n) {
        int max = maxQty(selectedRow());
        qty = max <= 0 ? 0 : Math.max(1, Math.min(n, max));
        syncing = true;
        if (slider != null) slider.show(qty, max);
        if (qtyBox != null && !qtyBox.getValue().equals(String.valueOf(qty))) qtyBox.setValue(String.valueOf(qty));
        syncing = false;
    }

    /** Typed into the quantity field: digits only. */
    private void typed(String text) {
        if (syncing) return;
        String digits = text.replaceAll("[^0-9]", "");
        if (!digits.equals(text)) {
            qtyBox.setValue(digits);
            return;
        }
        if (digits.isEmpty()) return;
        int n;
        try {
            n = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            n = Integer.MAX_VALUE;
        }
        int max = maxQty(selectedRow());
        qty = max <= 0 ? 0 : Math.max(1, Math.min(n, max));
        syncing = true;
        slider.show(qty, max);
        syncing = false;
    }

    /** Slides from the least (left) to the most you can deal (right). */
    private final class QtySlider extends Ui.Slider {
        private int max;

        QtySlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), 0);
        }

        void show(int n, int max) {
            this.max = max;
            setValue(max <= 1 ? 1 : (n - 1) / (double) (max - 1));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("minecraftportsmod.market.qty_slider", qty, max));
        }

        @Override
        protected void applyValue() {
            if (syncing) return;
            qty = max <= 0 ? 0 : 1 + (int) Math.round(value * (max - 1));
            syncing = true;
            if (qtyBox != null) qtyBox.setValue(String.valueOf(qty));
            syncing = false;
        }
    }

    private void updateButtons() {
        Row r = selectedRow();
        boolean market = tab == Tab.MARKET && r != null;
        for (var w : new net.minecraft.client.gui.components.AbstractWidget[]{buyMode, sellMode, slider, qtyBox, confirm}) w.visible = market;
        if (market) {
            buyMode.active = selling;
            sellMode.active = !selling;
            int max = maxQty(r);
            if (qty > max || qty == 0 && max > 0) setQty(qty);
            slider.active = max > 1;
            int cost = qty > 0 ? total(r, qty) : 0;
            confirm.active = qty > 0 && (selling ? cost >= 1 : cost <= view.emeralds());
            confirm.setMessage(Component.translatable(selling ? "minecraftportsmod.market.sell_btn" : "minecraftportsmod.market.buy_btn",
                    qty, cost));
        }
        for (int i = 0; i < deliver.length; i++) {
            boolean show = tab == Tab.ORDERS && i < view.orders().size();
            deliver[i].visible = show;
            if (!show) continue;
            OrderRow o = view.orders().get(i);
            deliver[i].setMessage(Component.translatable("minecraftportsmod.market.deliver", o.have()));
            deliver[i].active = o.have() > 0;
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        updateButtons();
        Ui.frame(g, x0, y0, x1, y1);

        Profession prof = Profession.values()[Math.min(view.profession(), Profession.values().length - 1)];
        g.text(font, Component.literal(view.name()).withStyle(ChatFormatting.BOLD), x0 + 10, y0 + 7, ChartStyle.TEXT_LIGHT, false);
        g.text(font, Component.translatable("minecraftportsmod.market.greeting", view.resident(), prof.displayName()),
                x0 + 10, y0 + 19, ChartStyle.PARCHMENT_SHADE, false);
        String em = String.valueOf(view.emeralds());
        g.item(new ItemStack(Items.EMERALD), x1 - 30 - font.width(em), y0 + 6);
        g.text(font, em, x1 - 12 - font.width(em), y0 + 11, ChartStyle.TEXT_LIGHT, false);

        drawTabs(g, mouseX, mouseY);
        Ui.blit(g, Ui.PARCHMENT, cx0 - 1, cy0 - 1, cx1 + 1, cy1 + 1);
        if (tab == Tab.MARKET) drawMarket(g, mouseX, mouseY);
        else drawOrders(g);
        Component foot = tab == Tab.MARKET ? Component.empty()
                : Component.translatable("minecraftportsmod.market.treasury", String.format(Locale.ROOT, "%.0f", view.treasury()));
        g.text(font, foot, cx0 + 2, cy1 + 5, ChartStyle.PARCHMENT_SHADE, false);
        widgets(g, mouseX, mouseY, partialTick);
    }

    private Component tabLabel(Tab t) {
        return t == Tab.MARKET ? Component.translatable("minecraftportsmod.market.tab.market")
                : Component.translatable("minecraftportsmod.market.tab.orders", view.orders().size());
    }

    private void drawTabs(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = cx0, y = cy0 - TAB_HEIGHT - 1;
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

    private Tab tabAt(double mx, double my) {
        int x = cx0, y = cy0 - TAB_HEIGHT - 1;
        if (my < y || my >= y + TAB_HEIGHT) return null;
        for (Tab t : Tab.values()) {
            int w = font.width(tabLabel(t)) + 14;
            if (mx >= x && mx < x + w) return t;
            x += w + 2;
        }
        return null;
    }

    private int visibleRows() {
        return Math.max(1, (cy1 - cy0 - 14) / ROW);
    }

    private void drawMarket(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int colSell = split - 4, colBuy = colSell - 40, colStock = colBuy - 42, colHave = colStock - 36;
        g.text(font, Component.translatable("minecraftportsmod.market.col.good"), cx0 + 24, cy0 + 3, ChartStyle.TEXT_MUTED, false);
        right(g, Component.translatable("minecraftportsmod.market.col.have"), colHave, cy0 + 3, ChartStyle.TEXT_MUTED);
        right(g, Component.translatable("minecraftportsmod.market.col.stock"), colStock, cy0 + 3, ChartStyle.TEXT_MUTED);
        right(g, Component.translatable("minecraftportsmod.market.col.buy_stack"), colBuy, cy0 + 3, ChartStyle.TEXT_MUTED);
        right(g, Component.translatable("minecraftportsmod.market.col.sell_stack"), colSell, cy0 + 3, ChartStyle.TEXT_MUTED);
        scroll = Math.max(0, Math.min(scroll, view.rows().size() - visibleRows()));
        g.enableScissor(cx0, cy0 + 13, split, cy1);
        int y = cy0 + 13;
        for (int i = scroll; i < view.rows().size() && y + ROW <= cy1; i++, y += ROW) {
            Row r = view.rows().get(i);
            Good good = Good.values()[r.good()];
            boolean sel = r.good() == selected;
            boolean hover = mouseX >= cx0 && mouseX < split && mouseY >= y && mouseY < y + ROW;
            if (sel) g.fill(cx0, y, split, y + ROW, 0x40A8322A);
            else if (hover) g.fill(cx0, y, split, y + ROW, 0x20000000);
            g.item(new ItemStack(good.item), cx0 + 3, y + 2);
            g.text(font, font.plainSubstrByWidth(good.displayName().getString(), colHave - 26 - (cx0 + 24)), cx0 + 24, y + 6, ChartStyle.TEXT, false);
            right(g, Component.literal(r.have() > 0 ? String.valueOf(r.have()) : "·"), colHave, y + 6, r.have() > 0 ? ChartStyle.TEXT : ChartStyle.TEXT_MUTED);
            right(g, Component.literal(r.available() > 0 ? String.valueOf(r.available()) : "·"), colStock, y + 6,
                    r.available() > 0 ? ChartStyle.TEXT : ChartStyle.TEXT_MUTED);
            right(g, Component.literal(stack(r.unitBuy())), colBuy, y + 6, ChartStyle.BAD);
            right(g, Component.literal(stack(r.unitSell())), colSell, y + 6, ChartStyle.GOOD);
        }
        g.disableScissor();
        g.fill(split, cy0, split + 1, cy1, ChartStyle.PARCHMENT_SHADE);

        Row r = selectedRow();
        if (r == null) {
            g.textWithWordWrap(font, Component.translatable("minecraftportsmod.market.pick"), split + 8, cy0 + 8, 136, ChartStyle.TEXT_MUTED, false);
            return;
        }
        Good good = Good.values()[r.good()];
        g.item(new ItemStack(good.item), split + 6, cy0 + 4);
        g.text(font, font.plainSubstrByWidth(good.displayName().getString(), 118), split + 26, cy0 + 9, ChartStyle.TEXT, false);
        int max = maxQty(r);
        g.text(font, Component.translatable("minecraftportsmod.market.of_max", max), split + 66, cy0 + 74, ChartStyle.TEXT_MUTED, false);
        y = cy0 + 92;
        if (qty > 0) {
            int cost = total(r, qty);
            Good gd = Good.values()[r.good()];
            double first = org.webtrade.minecraftportsmod.economy.Market.unitPrice(gd, r.target(), selling ? r.stock() + 1 : r.stock() - 1)
                    * (selling ? org.webtrade.minecraftportsmod.economy.Market.MARKDOWN : org.webtrade.minecraftportsmod.economy.Market.MARKUP);
            double last = org.webtrade.minecraftportsmod.economy.Market.unitPrice(gd, r.target(), selling ? r.stock() + qty : r.stock() - qty)
                    * (selling ? org.webtrade.minecraftportsmod.economy.Market.MARKDOWN : org.webtrade.minecraftportsmod.economy.Market.MARKUP);
            g.text(font, Component.translatable("minecraftportsmod.market.unit_range", price((float) first), price((float) last)),
                    split + 8, y, ChartStyle.TEXT_MUTED, false);
            g.text(font, Component.translatable(selling ? "minecraftportsmod.market.you_get" : "minecraftportsmod.market.you_pay", cost),
                    split + 8, y + 12, selling ? ChartStyle.GOOD : ChartStyle.BAD, false);
        } else {
            g.textWithWordWrap(font, Component.translatable(selling ? "minecraftportsmod.market.cant_sell" : "minecraftportsmod.market.cant_buy"),
                    split + 8, y, 136, ChartStyle.TEXT_MUTED, false);
        }
    }

    private void drawOrders(GuiGraphicsExtractor g) {
        if (view.orders().isEmpty()) {
            g.textWithWordWrap(font, Component.translatable("minecraftportsmod.market.no_orders"), cx0 + 8, cy0 + 10, cx1 - cx0 - 16,
                    ChartStyle.TEXT_MUTED, false);
            return;
        }
        int y = cy0 + 8;
        for (OrderRow o : view.orders()) {
            Good good = Good.values()[o.good()];
            g.item(new ItemStack(good.item), cx0 + 6, y + 4);
            g.text(font, Component.translatable("minecraftportsmod.market.order", o.amount(), good.displayName()).withStyle(ChatFormatting.BOLD),
                    cx0 + 28, y + 2, ChartStyle.TEXT, false);
            g.text(font, Component.translatable("minecraftportsmod.market.order_progress", o.delivered(), o.amount(), o.daysLeft()),
                    cx0 + 28, y + 14, ChartStyle.TEXT_MUTED, false);
            g.text(font, Component.translatable("minecraftportsmod.market.order_reward", o.reward()), cx0 + 28, y + 26, ChartStyle.GOOD, false);
            int bw = 90, bx = cx1 - 140;
            g.fill(bx - 100, y + 30, bx - 100 + bw, y + 34, ChartStyle.PARCHMENT_SHADE);
            g.fill(bx - 100, y + 30, bx - 100 + bw * o.delivered() / Math.max(1, o.amount()), y + 34, ChartStyle.GOOD);
            y += ORDER_ROW;
            g.fill(cx0 + 4, y - 4, cx1 - 4, y - 3, ChartStyle.PARCHMENT_SHADE);
        }
    }

    private void right(GuiGraphicsExtractor g, Component text, int right, int y, int color) {
        g.text(font, text, right - font.width(text), y, color, false);
    }

    /** A stack's price: cheap goods read better by the 64 than by the piece. */
    private static String stack(float unit) {
        float v = unit * 64;
        return v >= 100 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String price(float v) {
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
        if (tab == Tab.MARKET && mx >= cx0 && mx < split && my >= cy0 + 13 && my < cy1) {
            int i = scroll + (int) ((my - cy0 - 13) / ROW);
            if (i >= 0 && i < view.rows().size()) {
                selected = view.rows().get(i).good();
                // a good the settlement doesn't sell but the player has: offer to sell it
                Row row = view.rows().get(i);
                if (row.available() < 1 && row.have() > 0) selling = true;
                else if (row.have() == 0) selling = false;
                setQty(1);
            }
            return true;
        }
        return false;
    }

    @Override
    protected boolean uiScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
        return true;
    }
}
