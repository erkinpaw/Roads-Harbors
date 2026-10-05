package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Trade;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The merchant's stall, as a trader's in Mount &amp; Blade: the wares sorted by kind in tabs; on the left what the
 * village has for sale, on the right what the player carries that the village would buy; a ware picked on either
 * side, then how many (a slider from the least to the most there can be dealt, or typed) and the deal.
 */
public class TradeScreen extends UiScreen {

    private static final int ROW = 26;
    /** Tabs: all, then one per kind of goods. */
    private static final int TABS = Res.Kind.values().length + 1;

    private ColonyPayloads.TradeView view;
    private int x0, y0, x1, y1;
    /** The two lists' boxes, and the deal's band. */
    private int lx0, lx1, rx0, rx1, ly0, ly1, dy0;

    // kept across updates from the server
    private int tab;
    private int selected = -1;
    private boolean selling;
    private int qty;
    private int scrollLeft, scrollRight;

    private QtySlider slider;
    private EditBox qtyBox;
    private UiButton confirm;
    private boolean syncing;

    public TradeScreen(ColonyPayloads.TradeView view) {
        super(Component.translatable("minecraftportsmod.trade.title"));
        this.view = view;
    }

    public boolean shows(ColonyPayloads.TradeView v) {
        return v.village() == view.village();
    }

    public void update(ColonyPayloads.TradeView v) {
        view = v;
        rebuildWidgets();
    }

    // ------------------------------------------------------------------ what is shown

    private boolean inTab(ColonyPayloads.TradeRow r) {
        return tab == 0 || r.kind() == tab - 1;
    }

    /** The village's side: what it has for sale. */
    private List<ColonyPayloads.TradeRow> villageRows() {
        List<ColonyPayloads.TradeRow> out = new ArrayList<>();
        for (ColonyPayloads.TradeRow r : view.rows()) if (inTab(r) && r.available() > 0 && r.sellCents() >= 0) out.add(r);
        return out;
    }

    /** The player's side: what they carry of the wares. */
    private List<ColonyPayloads.TradeRow> playerRows() {
        List<ColonyPayloads.TradeRow> out = new ArrayList<>();
        for (ColonyPayloads.TradeRow r : view.rows()) if (inTab(r) && r.carried() > 0) out.add(r);
        return out;
    }

    private ColonyPayloads.TradeRow selectedRow() {
        for (ColonyPayloads.TradeRow r : view.rows()) if (r.ware() == selected) return r;
        return null;
    }

    private int maxQty() {
        ColonyPayloads.TradeRow r = selectedRow();
        if (r == null) return 0;
        return selling ? r.maxSell() : r.maxBuy();
    }

    private int cents() {
        ColonyPayloads.TradeRow r = selectedRow();
        return r == null ? -1 : selling ? r.buyCents() : r.sellCents();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void layout() {
        int w = Math.min(width - 24, 760), h = Math.min(height - 24, 470);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        scaleButtons(x1 - 8, y0 + 6);
        int px0 = x0 + Ui.BORDER + 8, px1 = x1 - Ui.BORDER - 8;
        // the tabs, along the top of the page
        int tabY = y0 + Ui.TITLE + 30;
        int tw = Math.min(130, (px1 - px0 - (TABS - 1) * 3) / TABS);
        for (int i = 0; i < TABS; i++) {
            final int t = i;
            Component label = i == 0 ? Component.translatable("minecraftportsmod.goods.all") : Res.Kind.values()[i - 1].displayName();
            addRenderableWidget(UiButton.make(label, btn -> {
                tab = t;
                scrollLeft = 0;
                scrollRight = 0;
                rebuildWidgets();
            }).bounds(px0 + i * (tw + 3), tabY, tw, 20).style(i == tab ? UiButton.Style.TAB_OPEN : UiButton.Style.TAB).build());
        }
        ly0 = tabY + 20 + 18;
        dy0 = y1 - Ui.BORDER - 58;
        ly1 = dy0 - 10;
        int mid = (x0 + x1) / 2;
        lx0 = px0;
        lx1 = mid - 6;
        rx0 = mid + 6;
        rx1 = px1;
        // the deal: slider, the number typed, the button
        int dy = dy0 + 10;
        int sx = px0 + 190;
        slider = addRenderableWidget(new QtySlider(sx, dy, Math.max(80, px1 - sx - 190), 20));
        qtyBox = addRenderableWidget(new EditBox(font, px1 - 182, dy + 2, 48, 16, Component.translatable("minecraftportsmod.market.qty")));
        qtyBox.setMaxLength(5);
        qtyBox.setResponder(this::typed);
        confirm = addRenderableWidget(UiButton.make(Component.empty(), b -> deal()).bounds(px1 - 126, dy, 126, 20).build());
        setQty(qty);
        refresh();
    }

    private void deal() {
        if (qty <= 0 || selected < 0) return;
        int kind = selling ? ColonyPayloads.VillageAction.SELL : ColonyPayloads.VillageAction.BUY;
        ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), kind, selected, qty));
    }

    private void refresh() {
        ColonyPayloads.TradeRow r = selectedRow();
        boolean on = r != null && maxQty() > 0;
        for (AbstractWidget w : new AbstractWidget[]{slider, qtyBox, confirm}) w.visible = r != null;
        if (r == null) return;
        slider.active = maxQty() > 1;
        qtyBox.active = on;
        confirm.active = on && qty > 0;
        confirm.setMessage(Component.translatable(selling ? "minecraftportsmod.trade.sell_n" : "minecraftportsmod.trade.buy_n",
                Trade.money((long) Math.max(0, cents()) * qty)));
    }

    /** Sets the quantity (clamped to 1..max, or 0 if nothing can be dealt) and moves slider and field with it. */
    private void setQty(int n) {
        int max = maxQty();
        qty = max <= 0 ? 0 : Math.max(1, Math.min(n, max));
        syncing = true;
        if (slider != null) slider.show(qty, max);
        if (qtyBox != null && !qtyBox.getValue().equals(String.valueOf(qty))) qtyBox.setValue(String.valueOf(qty));
        syncing = false;
        if (confirm != null) refresh();
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
        int max = maxQty();
        qty = max <= 0 ? 0 : Math.max(1, Math.min(n, max));
        syncing = true;
        slider.show(qty, max);
        syncing = false;
        refresh();
    }

    /** Slides from the least (left) to the most that can be dealt (right). */
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
            refresh();
        }
    }

    /** For tests: a tab (0: all, then Res.Kind + 1). */
    public void showTab(int t) {
        tab = t;
        rebuildWidgets();
    }

    /** For tests: a ware picked on one side, and how many. */
    public void pick(int ware, boolean sell, int n) {
        selected = ware;
        selling = sell;
        setQty(n);
    }

    /** For tests: the deal button pressed. */
    public void press() {
        if (confirm.active) deal();
    }

    // ------------------------------------------------------------------ drawing

    /** Hundredths of an emerald, as a price: "0.24", "3", "1.5". */
    static String price(int cents) {
        if (cents % 100 == 0) return String.valueOf(cents / 100);
        return cents >= 1000 ? String.valueOf(Math.round(cents / 100f)) : String.format(Locale.ROOT, "%.2f", cents / 100f).replaceAll("0$", "");
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.window(g, font, x0, y0, x1, y1, Component.translatable("minecraftportsmod.trade.header", view.merchant(), view.villageName()));
        int px0 = x0 + Ui.BORDER + 8, py0 = y0 + Ui.TITLE + 6, px1 = x1 - Ui.BORDER - 8;
        // the purses: the village's and the player's
        Ui.slot(g, px0, py0, 22, new ItemStack(Items.EMERALD));
        g.text(font, Component.translatable("minecraftportsmod.trade.purse_village"), px0 + 28, py0 + 2, ChartStyle.TEXT_MUTED, false);
        g.text(font, Trade.money(view.purse()), px0 + 28, py0 + 12, ChartStyle.INK, false);
        Component yours = Component.translatable("minecraftportsmod.trade.purse_yours");
        int yx = px1 - 28 - Math.max(font.width(yours), 30);
        Ui.slot(g, px1 - 22, py0, 22, new ItemStack(Items.EMERALD));
        g.text(font, yours, yx, py0 + 2, ChartStyle.TEXT_MUTED, false);
        g.text(font, Trade.money(view.playerEmeralds()), yx, py0 + 12, ChartStyle.INK, false);
        // under the tabs, the page they open
        Ui.rule(g, px0, px1, ly0 - 18);

        list(g, Component.translatable("minecraftportsmod.trade.theirs"), villageRows(), false, lx0, lx1, scrollLeft, mouseX, mouseY);
        list(g, Component.translatable("minecraftportsmod.trade.yours"), playerRows(), true, rx0, rx1, scrollRight, mouseX, mouseY);

        // the deal
        Ui.inset(g, px0 - 2, dy0, px1 + 2, y1 - Ui.BORDER - 6);
        int dy = dy0 + 10;
        ColonyPayloads.TradeRow r = selectedRow();
        if (r != null) {
            Ui.slot(g, px0 + 4, dy - 2, 26, r.icon());
            Component what = Component.translatable(selling ? "minecraftportsmod.trade.selling" : "minecraftportsmod.trade.buying");
            g.text(font, what, px0 + 36, dy, ChartStyle.TEXT_MUTED, false);
            g.text(font, Ui.fit(font, r.name().getString(), 140), px0 + 36, dy + 11, ChartStyle.INK, false);
            String total = Trade.money((long) Math.max(0, cents()) * qty);
            Component sum = maxQty() <= 0
                    ? Component.translatable(selling ? "minecraftportsmod.trade.cant_sell" : "minecraftportsmod.trade.cant_buy")
                    : Component.translatable("minecraftportsmod.trade.total", qty, price(Math.max(0, cents())), total);
            g.text(font, sum, px0 + 8, dy + 28, maxQty() <= 0 ? ChartStyle.BAD : ChartStyle.TEXT, false);
        }
        if (!view.note().getString().isEmpty()) g.text(font, view.note(), px1 - 6 - font.width(view.note()), dy + 28, ChartStyle.TEXT, false);
        widgets(g, mouseX, mouseY, partialTick);
    }

    /** One side's list: a heading, then a row per ware (a slot, the name, what the village thinks of it, how many, the price). */
    private void list(GuiGraphicsExtractor g, Component title, List<ColonyPayloads.TradeRow> rows, boolean player, int lx0, int lx1,
                      int scroll, int mouseX, int mouseY) {
        Component head = Component.translatable(player ? "minecraftportsmod.trade.col.they_pay" : "minecraftportsmod.trade.col.price");
        g.text(font, title, lx0 + 2, ly0 - 12, ChartStyle.INK, false);
        g.text(font, head, lx1 - 4 - font.width(head), ly0 - 12, ChartStyle.TEXT_MUTED, false);
        Ui.inset(g, lx0, ly0, lx1, ly1);
        if (rows.isEmpty()) {
            g.text(font, Component.translatable(player ? "minecraftportsmod.trade.none_yours" : "minecraftportsmod.trade.none_theirs"),
                    lx0 + 8, ly0 + 10, ChartStyle.TEXT_MUTED, false);
            return;
        }
        int y = ly0 + 3;
        for (int i = scroll; i < rows.size() && y + ROW <= ly1 - 2; i++, y += ROW) {
            ColonyPayloads.TradeRow r = rows.get(i);
            boolean sel = r.ware() == selected && selling == player;
            boolean hover = mouseX >= lx0 && mouseX < lx1 && mouseY >= y && mouseY < y + ROW;
            if (sel) g.fill(lx0 + 2, y, lx1 - 2, y + ROW, 0x50E6B43A);
            else if (hover) g.fill(lx0 + 2, y, lx1 - 2, y + ROW, 0x18000000);
            if (i > scroll) g.fill(lx0 + 6, y, lx1 - 6, y + 1, 0x20000000);
            Ui.slot(g, lx0 + 5, y + 2, 22, r.icon());
            int count = player ? r.carried() : r.available();
            String n = "×" + count;
            int cents = player ? r.buyCents() : r.sellCents();
            String p = cents < 0 ? "—" : price(cents);
            int pw = font.width(p) + 12, nw = font.width(n);
            int nameW = lx1 - lx0 - 34 - pw - nw - 20;
            g.text(font, Ui.fit(font, r.name().getString(), nameW), lx0 + 32, y + 5, ChartStyle.INK, false);
            // what the village thinks of it: short of it, or has plenty
            if (r.target() > 0) {
                boolean shortOf = r.stock() < r.target();
                Component state = Component.translatable(shortOf ? "minecraftportsmod.trade.state_short" : "minecraftportsmod.trade.state_plenty");
                g.text(font, state, lx0 + 32, y + 15, shortOf ? ChartStyle.BAD : ChartStyle.TEXT_MUTED, false);
            }
            g.text(font, n, lx1 - 10 - pw - nw - 8, y + 9, ChartStyle.TEXT_MUTED, false);
            int color = cents < 0 ? ChartStyle.TEXT_MUTED : player && r.target() > 0 && r.stock() < r.target() ? ChartStyle.GOOD : ChartStyle.INK;
            g.text(font, p, lx1 - 8 - pw, y + 9, color, false);
            if (cents >= 0) g.item(new ItemStack(Items.EMERALD), lx1 - 8 - 10, y + 5);
            if (hover && mouseX >= lx0 + 5 && mouseX < lx0 + 27) g.setTooltipForNextFrame(font, r.icon(), mouseX, mouseY);
        }
        if (rows.size() * ROW > ly1 - ly0 - 4) {
            // a scroll bar
            int visible = (ly1 - ly0 - 4) / ROW;
            int bh = Math.max(10, (ly1 - ly0) * visible / rows.size());
            int by = ly0 + 2 + (ly1 - ly0 - 4 - bh) * scroll / Math.max(1, rows.size() - visible);
            g.fill(lx1 - 5, by, lx1 - 2, by + bh, ChartStyle.BRASS_DARK);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    protected boolean uiClick(MouseButtonEvent event, boolean doubleClick) {
        if (super.uiClick(event, doubleClick)) return true;
        double mx = event.x(), my = event.y();
        if (my < ly0 || my >= ly1) return false;
        boolean left = mx >= lx0 && mx < lx1, right = mx >= rx0 && mx < rx1;
        if (!left && !right) return false;
        List<ColonyPayloads.TradeRow> rows = left ? villageRows() : playerRows();
        int i = (left ? scrollLeft : scrollRight) + (int) ((my - ly0 - 3) / ROW);
        if (i < 0 || i >= rows.size()) return false;
        selected = rows.get(i).ware();
        selling = right;
        setQty(1);
        return true;
    }

    @Override
    protected boolean uiScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = (int) -Math.signum(scrollY);
        int visible = Math.max(1, (ly1 - ly0 - 4) / ROW);
        if (mouseX < (x0 + x1) / 2.0) scrollLeft = Math.max(0, Math.min(Math.max(0, villageRows().size() - visible), scrollLeft + step));
        else scrollRight = Math.max(0, Math.min(Math.max(0, playerRows().size() - visible), scrollRight + step));
        return true;
    }
}
