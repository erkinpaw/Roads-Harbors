package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.colony.Orders;
import org.webtrade.minecraftportsmod.colony.Trade;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

import java.util.ArrayList;
import java.util.List;

/**
 * A building's people taking orders (the sawyer at the sawmill): on the left what they make, each by its recipe (a
 * piece's price, how many a day); on the right the player's orders with them, how far along, and the ones ready to
 * take. A thing picked on the left, how many (slider or typed), and the order: paid now, made over the next days.
 */
public class OrderScreen extends UiScreen {

    private static final int ROW = 28;

    private ColonyPayloads.OrderView view;
    private int x0, y0, x1, y1;
    private int lx0, lx1, rx0, rx1, ly0, ly1, dy0;

    private int selected = -1;
    private int qty = 1;
    private int scrollLeft, scrollRight;

    private QtySlider slider;
    private EditBox qtyBox;
    private UiButton confirm, buyNow, collect, tools;
    private int ticks;
    private boolean syncing;

    public OrderScreen(ColonyPayloads.OrderView view) {
        super(Component.translatable("minecraftportsmod.order.title"));
        this.view = view;
    }

    public boolean shows(ColonyPayloads.OrderView v) {
        return v.village() == view.village() && v.building() == view.building();
    }

    public void update(ColonyPayloads.OrderView v) {
        boolean same = v.rows().size() == view.rows().size() && v.note().getString().equals(view.note().getString()) && collect != null;
        view = v;
        // (the second-by-second refresh: the bars move, the typing is not lost)
        if (!same) {
            rebuildWidgets();
            return;
        }
        collect.visible = anyReady();
        tools.visible = hasTools();
        refresh();
    }

    // ------------------------------------------------------------------ what is shown

    /** What can be ordered: the open recipes first, then the ones a higher level opens. */
    private List<ColonyPayloads.OrderRow> rows() {
        List<ColonyPayloads.OrderRow> out = new ArrayList<>();
        for (ColonyPayloads.OrderRow r : view.rows()) if (r.open()) out.add(r);
        for (ColonyPayloads.OrderRow r : view.rows()) if (!r.open()) out.add(r);
        return out;
    }

    private ColonyPayloads.OrderRow selectedRow() {
        for (ColonyPayloads.OrderRow r : view.rows()) if (r.recipe() == selected) return r;
        return null;
    }

    /** The most pieces the player can pay for as an order (and no more than an order takes). */
    private int maxOrder() {
        ColonyPayloads.OrderRow r = selectedRow();
        if (r == null || !r.open()) return 0;
        int n = Orders.MAX_PIECES;
        while (n > 0 && Trade.total(r.cents(), n) > view.playerEmeralds()) n--;
        return n;
    }

    /** The most pieces the player can buy now: what the village has to spare, and the emeralds. */
    private int maxNow() {
        ColonyPayloads.OrderRow r = selectedRow();
        if (r == null || r.nowCents() < 0) return 0;
        int n = Math.min(r.stock(), Orders.MAX_PIECES);
        while (n > 0 && Trade.total(r.nowCents(), n) > view.playerEmeralds()) n--;
        return n;
    }

    private int maxQty() {
        return Math.max(maxOrder(), maxNow());
    }

    /** Has the player any tools to put in the slot (wooden, stone, iron)? */
    private boolean hasTools() {
        var p = net.minecraft.client.Minecraft.getInstance().player;
        if (p == null) return false;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            for (int t = 1; t <= 3; t++) if (org.webtrade.minecraftportsmod.colony.Res.tools(t).unitsOf(s) > 0) return true;
        }
        return false;
    }

    /** When a window of orders was last closed, and whose: a refresh on its way then is not to open it again. */
    private static long closedAt;
    private static int closedVillage = -1, closedBuilding = -1;

    @Override
    public void removed() {
        super.removed();
        closedAt = System.currentTimeMillis();
        closedVillage = view.village();
        closedBuilding = view.building();
    }

    /** Is this the answer to a refresh of a window just closed? */
    public static boolean justClosed(ColonyPayloads.OrderView v) {
        return v.village() == closedVillage && v.building() == closedBuilding && System.currentTimeMillis() - closedAt < 2500;
    }

    /** Every second, the queue and how far along it is, as it is now. */
    @Override
    public void tick() {
        super.tick();
        if (++ticks % 20 == 0) {
            ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), ColonyPayloads.VillageAction.ORDERS, view.building(), 0));
        }
    }

    private boolean anyReady() {
        for (ColonyPayloads.MineRow m : view.mine()) if (m.eta() == 0) return true;
        return false;
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
        ly0 = y0 + Ui.TITLE + 52;
        dy0 = y1 - Ui.BORDER - 58;
        ly1 = dy0 - 10;
        int mid = (x0 + x1) / 2;
        lx0 = px0;
        lx1 = mid + 40;
        rx0 = mid + 52;
        rx1 = px1;
        collect = addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.order.collect"),
                b -> ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), ColonyPayloads.VillageAction.COLLECT, view.building(), 0)))
                .bounds(rx1 - 130, ly0 - 24, 130, 20).build());
        // (only what can be done: no greyed-out buttons)
        collect.visible = anyReady();
        tools = addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.order.put_tools"),
                b -> ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), ColonyPayloads.VillageAction.TOOLS, view.building(), 0)))
                .bounds(rx1 - 150, ly1 - 22, 146, 18).build());
        tools.visible = hasTools();
        int dy = dy0 + 10;
        int sx = px0 + 190;
        slider = addRenderableWidget(new QtySlider(sx, dy, Math.max(80, px1 - sx - 442), 20));
        qtyBox = addRenderableWidget(new EditBox(font, px1 - 434, dy + 2, 48, 16, Component.translatable("minecraftportsmod.market.qty")));
        qtyBox.setMaxLength(4);
        qtyBox.setResponder(this::typed);
        confirm = addRenderableWidget(UiButton.make(Component.empty(), b -> order()).bounds(px1 - 186, dy, 186, 20).build());
        buyNow = addRenderableWidget(UiButton.make(Component.empty(), b -> buy()).bounds(px1 - 378, dy, 186, 20).build());
        setQty(qty);
        refresh();
    }

    private void order() {
        if (qty <= 0 || selected < 0) return;
        ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), ColonyPayloads.VillageAction.ORDER, view.building(),
                selected * 1000 + qty));
    }

    private void buy() {
        if (qty <= 0 || selected < 0) return;
        ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), ColonyPayloads.VillageAction.BUY_NOW, view.building(),
                selected * 1000 + qty));
    }

    /** Days the order takes at the people's pace (the orders before it come first). */
    private int days() {
        ColonyPayloads.OrderRow r = selectedRow();
        return r == null || r.perDay() <= 0 ? 0 : Math.max(1, (qty + r.perDay() - 1) / r.perDay());
    }

    private void refresh() {
        ColonyPayloads.OrderRow r = selectedRow();
        boolean on = r != null && maxQty() > 0;
        for (AbstractWidget w : new AbstractWidget[]{slider, qtyBox, confirm}) w.visible = r != null && (r.open() || r.stock() > 0);
        buyNow.visible = r != null && r.nowCents() >= 0 && r.stock() > 0;
        if (r == null) return;
        slider.active = maxQty() > 1;
        qtyBox.active = on;
        confirm.visible = r.open();
        confirm.active = on && qty > 0 && qty <= maxOrder();
        confirm.setMessage(Component.translatable("minecraftportsmod.order.order_n", Trade.total(r.cents(), qty)));
        buyNow.active = qty > 0 && qty <= maxNow();
        buyNow.setMessage(Component.translatable("minecraftportsmod.order.buy_now_n", r.nowCents() < 0 ? 0 : Trade.total(r.nowCents(), qty)));
    }

    private void setQty(int n) {
        int max = maxQty();
        qty = max <= 0 ? 0 : Math.max(1, Math.min(n, max));
        syncing = true;
        if (slider != null) slider.show(qty, max);
        if (qtyBox != null && !qtyBox.getValue().equals(String.valueOf(qty))) qtyBox.setValue(String.valueOf(qty));
        syncing = false;
        if (confirm != null) refresh();
    }

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

    /** For tests: a recipe picked, and how many. */
    public void pick(int recipe, int n) {
        selected = recipe;
        setQty(n);
    }

    /** For tests: the order button pressed. */
    public void press() {
        if (confirm.active) order();
    }

    /** For tests: the buy-now button pressed. */
    public void pressBuy() {
        if (buyNow.active) buy();
    }

    /** For tests: the recipe (index) of the first open row that makes an item whose id contains {@code part}. */
    public int find(String part) {
        for (ColonyPayloads.OrderRow r : rows()) {
            if (r.open() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(r.icon().getItem()).getPath().contains(part)) return r.recipe();
        }
        return -1;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.window(g, font, x0, y0, x1, y1, Component.translatable("minecraftportsmod.order.header", view.worker(), view.buildingName(),
                view.villageName()));
        int px0 = x0 + Ui.BORDER + 8, py0 = y0 + Ui.TITLE + 6, px1 = x1 - Ui.BORDER - 8;
        Component yours = Component.translatable("minecraftportsmod.trade.purse_yours");
        int yx = px1 - 28 - Math.max(font.width(yours), 30);
        Ui.slot(g, px1 - 22, py0, 22, new ItemStack(Items.EMERALD));
        g.text(font, yours, yx, py0 + 2, ChartStyle.TEXT_MUTED, false);
        g.text(font, String.valueOf(view.playerEmeralds()), yx, py0 + 12, ChartStyle.INK, false);
        Ui.rule(g, px0, px1, ly0 - 30);

        recipes(g, mouseX, mouseY);
        mine(g);

        // the order
        Ui.inset(g, px0 - 2, dy0, px1 + 2, y1 - Ui.BORDER - 6);
        int dy = dy0 + 10;
        ColonyPayloads.OrderRow r = selectedRow();
        if (r == null) {
            g.text(font, Component.translatable("minecraftportsmod.order.pick"), px0 + 8, dy + 6, ChartStyle.TEXT_MUTED, false);
        } else {
            Ui.slot(g, px0 + 4, dy - 2, 26, r.icon());
            g.text(font, Component.translatable("minecraftportsmod.order.ordering"), px0 + 36, dy, ChartStyle.TEXT_MUTED, false);
            g.text(font, Ui.fit(font, r.name().getString(), 140), px0 + 36, dy + 11, ChartStyle.INK, false);
            Component sum;
            int color = ChartStyle.TEXT;
            if (!r.open() && r.stock() <= 0) {
                sum = Component.translatable("minecraftportsmod.order.closed", r.lvl());
                color = ChartStyle.BAD;
            } else if (!r.open()) {
                sum = Component.translatable("minecraftportsmod.order.now_only", r.stock(), TradeScreen.price(r.nowCents()));
            } else if (maxQty() <= 0 && !r.open()) {
                sum = Component.translatable("minecraftportsmod.order.cant_pay");
                color = ChartStyle.BAD;
            } else {
                sum = r.stock() > 0 && r.nowCents() >= 0
                        ? Component.translatable("minecraftportsmod.order.total_both", qty, Trade.total(r.cents(), qty), days(), r.stock(),
                        TradeScreen.price(r.nowCents()))
                        : Component.translatable("minecraftportsmod.order.total", qty, TradeScreen.price(r.cents()), Trade.total(r.cents(), qty), days());
            }
            g.text(font, sum, px0 + 8, dy + 28, color, false);
        }
        if (!view.note().getString().isEmpty()) g.text(font, view.note(), px1 - 6 - font.width(view.note()), dy + 28, ChartStyle.TEXT, false);
        widgets(g, mouseX, mouseY, partialTick);
    }

    /** What can be ordered: a slot, the name, what a making takes, pieces a day, the price of a piece. */
    private void recipes(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<ColonyPayloads.OrderRow> rows = rows();
        Component head = Component.translatable("minecraftportsmod.order.col.price");
        g.text(font, Component.translatable("minecraftportsmod.order.makes"), lx0 + 2, ly0 - 12, ChartStyle.INK, false);
        g.text(font, head, lx1 - 4 - font.width(head), ly0 - 12, ChartStyle.TEXT_MUTED, false);
        Ui.inset(g, lx0, ly0, lx1, ly1);
        if (rows.isEmpty()) {
            g.text(font, Component.translatable("minecraftportsmod.order.none"), lx0 + 8, ly0 + 10, ChartStyle.TEXT_MUTED, false);
            return;
        }
        int y = ly0 + 3;
        for (int i = scrollLeft; i < rows.size() && y + ROW <= ly1 - 2; i++, y += ROW) {
            ColonyPayloads.OrderRow r = rows.get(i);
            boolean sel = r.recipe() == selected;
            boolean hover = mouseX >= lx0 && mouseX < lx1 && mouseY >= y && mouseY < y + ROW;
            if (sel) g.fill(lx0 + 2, y, lx1 - 2, y + ROW, 0x50E6B43A);
            else if (hover) g.fill(lx0 + 2, y, lx1 - 2, y + ROW, 0x18000000);
            if (i > scrollLeft) g.fill(lx0 + 6, y, lx1 - 6, y + 1, 0x20000000);
            Ui.slot(g, lx0 + 5, y + 3, 22, r.icon());
            String p = TradeScreen.price(r.cents());
            int pw = font.width(p) + 12;
            int nameW = lx1 - lx0 - 44 - pw;
            int ink = r.open() ? ChartStyle.INK : ChartStyle.TEXT_MUTED;
            g.text(font, Ui.fit(font, r.name().getString(), nameW), lx0 + 32, y + 4, ink, false);
            Component line = r.open() && r.gathered()
                    ? Component.translatable("minecraftportsmod.order.line_gathered", r.perDay())
                    : r.open()
                    ? Component.translatable("minecraftportsmod.order.line", r.takes(), r.perMaking(), r.perDay())
                    : Component.translatable("minecraftportsmod.order.from_level", r.lvl());
            g.text(font, Ui.fit(font, line.getString(), nameW), lx0 + 32, y + 15, r.open() && !r.supplied() ? ChartStyle.BAD : ChartStyle.TEXT_MUTED, false);
            g.text(font, p, lx1 - 8 - pw, y + 9, ink, false);
            if (r.stock() > 0) {
                String st = "×" + r.stock();
                g.text(font, st, lx1 - 8 - pw - 10 - font.width(st), y + 9, ChartStyle.GOOD, false);
            }
            g.item(new ItemStack(Items.EMERALD), lx1 - 8 - 10, y + 5);
            if (hover && mouseX >= lx0 + 5 && mouseX < lx0 + 27) g.setTooltipForNextFrame(font, r.icon(), mouseX, mouseY);
        }
    }

    /**
     * The workshop's queue, whoever's, the oldest first: the one being made with its bar (how far along the making in
     * hand is) and the seconds left; the rest, made of ordered and whose. Under it, the tools in the slot.
     */
    private void mine(GuiGraphicsExtractor g) {
        g.text(font, Component.translatable("minecraftportsmod.order.queue"), rx0 + 2, ly0 - 12, ChartStyle.INK, false);
        int qy1 = ly1 - 28;
        Ui.inset(g, rx0, ly0, rx1, qy1);
        int y = ly0 + 3;
        for (int i = scrollRight; i < view.queue().size() && y + ROW <= qy1 - 2; i++, y += ROW) {
            ColonyPayloads.WorkRow q = view.queue().get(i);
            if (i > scrollRight) g.fill(rx0 + 6, y, rx1 - 6, y + 1, 0x20000000);
            Ui.slot(g, rx0 + 5, y + 3, 22, q.icon());
            g.text(font, Ui.fit(font, q.made() + "/" + q.count() + " × " + q.icon().getHoverName().getString(), rx1 - rx0 - 120), rx0 + 32, y + 4,
                    ChartStyle.INK, false);
            g.text(font, Ui.fit(font, q.who().getString(), 80), rx1 - 6 - Math.min(80, font.width(q.who())), y + 4, ChartStyle.TEXT_MUTED, false);
            int bx0 = rx0 + 32, bx1 = rx1 - 50, by = y + 16;
            g.fill(bx0, by, bx1, by + 5, 0x30000000);
            if (i == 0) {
                // the making in hand
                g.fill(bx0, by, bx0 + (int) ((bx1 - bx0) * view.progress()), by + 5, view.workers() > 0 ? ChartStyle.GOOD : ChartStyle.TEXT_MUTED);
                if (view.secondsLeft() >= 0) {
                    String s = view.secondsLeft() + "s";
                    g.text(font, s, rx1 - 6 - font.width(s), y + 14, view.workers() > 0 ? ChartStyle.TEXT : ChartStyle.TEXT_MUTED, false);
                }
            } else {
                g.fill(bx0, by, bx0 + (bx1 - bx0) * Math.min(q.made(), q.count()) / Math.max(1, q.count()), by + 5, 0x6040A040);
            }
        }
        // the tools in the slot: uses left of wooden, stone, iron ones
        int ty = qy1 + 6;
        g.text(font, Component.translatable("minecraftportsmod.order.tools"), rx0 + 2, ty + 5, ChartStyle.TEXT_MUTED, false);
        net.minecraft.world.item.Item[] icons = {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE};
        int tx = rx0 + 60;
        for (int k = 0; k < 3; k++) {
            Ui.slot(g, tx, ty, 18, new ItemStack(icons[k]));
            g.text(font, String.valueOf(view.tools()[k]), tx + 21, ty + 5, view.tools()[k] > 0 ? ChartStyle.INK : ChartStyle.TEXT_MUTED, false);
            tx += 50;
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    protected boolean uiClick(MouseButtonEvent event, boolean doubleClick) {
        if (super.uiClick(event, doubleClick)) return true;
        double mx = event.x(), my = event.y();
        if (my < ly0 || my >= ly1 || mx < lx0 || mx >= lx1) return false;
        List<ColonyPayloads.OrderRow> rows = rows();
        int i = scrollLeft + (int) ((my - ly0 - 3) / ROW);
        if (i < 0 || i >= rows.size()) return false;
        selected = rows.get(i).recipe();
        setQty(Math.max(1, qty));
        return true;
    }

    @Override
    protected boolean uiScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = (int) -Math.signum(scrollY);
        int visible = Math.max(1, (ly1 - ly0 - 4) / ROW);
        if (mouseX < lx1) scrollLeft = Math.max(0, Math.min(Math.max(0, rows().size() - visible), scrollLeft + step));
        else scrollRight = Math.max(0, Math.min(Math.max(0, view.queue().size() - visible), scrollRight + step));
        return true;
    }
}
