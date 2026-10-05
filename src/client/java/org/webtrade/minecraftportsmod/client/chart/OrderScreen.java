package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.colony.Orders;
import org.webtrade.minecraftportsmod.colony.Trade;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A workshop's orders, laid out like crafting: on the left its recipes, each what a making takes (the store's stock of
 * it on the mouse; red-ringed what the store is short of) -> what it gives; a click puts a piece of it in the cart
 * (Ctrl five, Shift sixty-four; the right button takes back), and the cart paid goes to the end of the workshop's
 * queue, on the right.
 */
public class OrderScreen extends UiScreen {

    private static final int ROW = 26, SLOT = 20, INS = 3;

    private ColonyPayloads.OrderView view;
    private int x0, y0, x1, y1;
    private int lx0, lx1, rx0, rx1, ly0, ly1, dy0;

    /** The cart: recipe -> pieces, in the order put in. */
    private final LinkedHashMap<Integer, Integer> cart = new LinkedHashMap<>();
    private int scrollLeft, scrollRight;

    private UiButton pay, clear, collect, tools;
    private int ticks;

    public OrderScreen(ColonyPayloads.OrderView view) {
        super(Component.translatable("minecraftportsmod.order.title"));
        this.view = view;
    }

    public boolean shows(ColonyPayloads.OrderView v) {
        return v.village() == view.village() && v.building() == view.building();
    }

    public void update(ColonyPayloads.OrderView v) {
        view = v;
        if (collect == null) {
            rebuildWidgets();
            return;
        }
        refresh();
    }

    // ------------------------------------------------------------------ what is shown

    /** The recipes: the open ones first, then the ones a higher level opens. */
    private List<ColonyPayloads.OrderRow> rows() {
        List<ColonyPayloads.OrderRow> out = new ArrayList<>();
        for (ColonyPayloads.OrderRow r : view.rows()) if (r.open()) out.add(r);
        for (ColonyPayloads.OrderRow r : view.rows()) if (!r.open()) out.add(r);
        return out;
    }

    private ColonyPayloads.OrderRow row(int recipe) {
        for (ColonyPayloads.OrderRow r : view.rows()) if (r.recipe() == recipe) return r;
        return null;
    }

    /** What the cart costs, in hundredths. */
    private long total() {
        long t = 0;
        for (var e : cart.entrySet()) {
            ColonyPayloads.OrderRow r = row(e.getKey());
            if (r != null) t += (long) r.cents() * e.getValue();
        }
        return t;
    }

    private boolean canPay() {
        return !cart.isEmpty() && total() <= view.playerEmeralds();
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
        int px1 = x1 - Ui.BORDER - 8;
        ly0 = y0 + Ui.TITLE + 40;
        dy0 = y1 - Ui.BORDER - 46;
        ly1 = dy0 - 8;
        int mid = (x0 + x1) / 2;
        lx0 = x0 + Ui.BORDER + 8;
        lx1 = mid + 40;
        rx0 = mid + 52;
        rx1 = px1;
        collect = addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.order.collect"),
                b -> ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), ColonyPayloads.VillageAction.COLLECT, view.building(), 0)))
                .bounds(rx1 - 130, ly0 - 24, 130, 20).build());
        tools = addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.order.put_tools"),
                b -> ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), ColonyPayloads.VillageAction.TOOLS, view.building(), 0)))
                .bounds(rx1 - 150, ly1 - 22, 146, 18).build());
        int by = dy0 + 12;
        pay = addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.order.pay"), b -> pay()).bounds(px1 - 120, by, 120, 20).build());
        clear = addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.order.clear"), b -> {
            cart.clear();
            refresh();
        }).bounds(px1 - 210, by, 84, 20).build());
        refresh();
    }

    /** Only what can be done shows: no greyed-out buttons. */
    private void refresh() {
        if (collect == null) return;
        collect.visible = anyReady();
        tools.visible = hasTools();
        pay.visible = canPay();
        clear.visible = !cart.isEmpty();
    }

    /** The cart paid: each line an order at the end of the queue. */
    private void pay() {
        if (!canPay()) return;
        for (var e : cart.entrySet()) {
            ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), ColonyPayloads.VillageAction.ORDER, view.building(),
                    e.getKey() * 1000 + e.getValue()));
        }
        cart.clear();
        refresh();
    }

    private void put(int recipe, int n) {
        int now = cart.getOrDefault(recipe, 0) + n;
        if (now <= 0) cart.remove(recipe);
        else cart.put(recipe, Math.min(now, Orders.MAX_PIECES));
        refresh();
    }

    /** For tests: {@code n} pieces of a recipe in the cart. */
    public void pick(int recipe, int n) {
        put(recipe, n);
    }

    /** For tests: the cart paid. */
    public void press() {
        pay();
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
        g.text(font, Trade.money(view.playerEmeralds()), yx, py0 + 12, ChartStyle.INK, false);
        if (!view.note().getString().isEmpty()) g.text(font, view.note(), px0, py0 + 8, ChartStyle.TEXT, false);

        List<Component> tip = recipes(g, mouseX, mouseY);
        queue(g);
        List<Component> cartTip = cart(g, mouseX, mouseY);
        widgets(g, mouseX, mouseY, partialTick);
        if (tip == null) tip = cartTip;
        if (tip != null) g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
    }

    /** Where a row's parts are: the inputs, the arrow, the output. */
    private int insX(int k) {
        return lx0 + 6 + k * (SLOT + 2);
    }

    private int arrowX() {
        return insX(INS) + 2;
    }

    private int outX() {
        return arrowX() + 26;
    }

    /**
     * The recipes, each like a crafting: what a making takes -> what it gives; the price of a piece; how many in the
     * cart. Returns the tip for what is under the mouse, if anything.
     */
    private List<Component> recipes(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<ColonyPayloads.OrderRow> rows = rows();
        Ui.inset(g, lx0, ly0, lx1, ly1);
        int y = ly0 + 3;
        List<Component> tip = null;
        for (int i = scrollLeft; i < rows.size() && y + ROW <= ly1 - 2; i++, y += ROW) {
            ColonyPayloads.OrderRow r = rows.get(i);
            boolean hover = mouseX >= lx0 && mouseX < lx1 && mouseY >= y && mouseY < y + ROW;
            if (hover && r.open()) g.fill(lx0 + 2, y, lx1 - 2, y + ROW, 0x18000000);
            if (i > scrollLeft) g.fill(lx0 + 6, y, lx1 - 6, y + 1, 0x20000000);
            int sy = y + 3;
            // what a making takes: red-ringed what the store has too little of
            for (int k = 0; k < Math.min(INS, r.ins().size()); k++) {
                ItemStack in = r.ins().get(k);
                int sx = insX(k);
                boolean short_ = r.inStock()[k] < in.getCount();
                if (short_ && r.open()) g.fill(sx - 1, sy - 1, sx + SLOT + 1, sy + SLOT + 1, 0xFFC0302A);
                Ui.slot(g, sx, sy, SLOT, in.copyWithCount(1));
                count(g, in.getCount(), sx, sy);
                if (mouseX >= sx && mouseX < sx + SLOT && mouseY >= sy && mouseY < sy + SLOT) {
                    tip = List.of(in.getHoverName(),
                            Component.translatable("minecraftportsmod.order.tip.in_store", r.inStock()[k])
                                    .withStyle(short_ ? ChatFormatting.RED : ChatFormatting.GRAY),
                            Component.translatable("minecraftportsmod.order.tip.per_making", in.getCount()).withStyle(ChatFormatting.GRAY));
                }
            }
            // the arrow, the seconds a making takes under it
            arrow(g, arrowX(), sy + 3, r.open() ? ChartStyle.INK_SOFT : 0x60000000);
            String sec = r.seconds() + "s";
            g.text(font, sec, arrowX() + 9 - font.width(sec) / 2, sy + 13, ChartStyle.TEXT_MUTED, false);
            // what it gives
            int ox = outX();
            Ui.slot(g, ox, sy, SLOT, r.icon().copyWithCount(1));
            count(g, r.icon().getCount(), ox, sy);
            if (!r.open()) {
                g.fill(insX(0) - 1, sy - 1, ox + SLOT + 1, sy + SLOT + 1, 0x70D8CBB0);
                lock(g, ox + SLOT - 6, sy + SLOT - 8);
            }
            int nx = ox + SLOT + 8;
            String price = TradeScreen.price(r.cents());
            int pw = font.width(price) + 12;
            Integer inCart = cart.get(r.recipe());
            String c = inCart == null ? "" : "+" + inCart;
            int cw = c.isEmpty() ? 0 : font.width(c) + 8;
            g.text(font, Ui.fit(font, r.name().getString(), lx1 - nx - pw - cw - 10), nx, y + 9, r.open() ? ChartStyle.INK : ChartStyle.TEXT_MUTED, false);
            if (!c.isEmpty()) {
                int cx = lx1 - 8 - pw - cw;
                g.fill(cx, y + 6, cx + cw - 3, y + 19, 0xFFE6B43A);
                g.text(font, c, cx + 3, y + 9, 0xFF3A2610, false);
            }
            g.text(font, price, lx1 - 8 - pw, y + 9, r.open() ? ChartStyle.INK : ChartStyle.TEXT_MUTED, false);
            g.item(new ItemStack(Items.EMERALD), lx1 - 8 - 10, y + 5);
            if (mouseX >= ox && mouseX < ox + SLOT && mouseY >= sy && mouseY < sy + SLOT) {
                List<Component> t = new ArrayList<>();
                t.add(r.name());
                if (r.open()) {
                    t.add(Component.translatable("minecraftportsmod.order.tip.makes", r.perMaking(), r.seconds()).withStyle(ChatFormatting.GRAY));
                    t.add(Component.translatable("minecraftportsmod.order.tip.price", TradeScreen.price(r.cents())).withStyle(ChatFormatting.GREEN));
                    t.add(Component.translatable("minecraftportsmod.order.tip.ready", r.stock()).withStyle(ChatFormatting.GRAY));
                } else {
                    t.add(Component.translatable("minecraftportsmod.order.tip.level", r.lvl()).withStyle(ChatFormatting.RED));
                }
                tip = t;
            }
        }
        return tip;
    }

    /** A count in a slot's corner, as on a stack (none for one). */
    private void count(GuiGraphicsExtractor g, int n, int x, int y) {
        if (n <= 1) return;
        String s = String.valueOf(n);
        g.nextStratum();
        g.text(font, s, x + SLOT - 1 - font.width(s), y + SLOT - 8, 0xFF1C1408, false);
    }

    /** An arrow to the right, as in a crafting table. */
    private static void arrow(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x, y + 3, x + 12, y + 6, color);
        for (int k = 0; k < 5; k++) g.fill(x + 12 + k, y + k, x + 13 + k, y + 9 - k, color);
    }

    /** A small padlock: a recipe a higher level opens. */
    private static void lock(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x + 1, y, x + 6, y + 1, 0xFF4A4038);
        g.fill(x + 1, y, x + 2, y + 4, 0xFF4A4038);
        g.fill(x + 5, y, x + 6, y + 4, 0xFF4A4038);
        g.fill(x, y + 3, x + 7, y + 9, 0xFF4A4038);
        g.fill(x + 1, y + 4, x + 6, y + 8, 0xFFC9922A);
        g.fill(x + 3, y + 5, x + 4, y + 7, 0xFF4A4038);
    }

    private int cartX(int k) {
        return x0 + Ui.BORDER + 12 + k * (SLOT + 4);
    }

    /** The cart: what is in it (a click takes back), what it all costs. Returns the tip for what is under the mouse. */
    private List<Component> cart(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int px0 = x0 + Ui.BORDER + 8, px1 = x1 - Ui.BORDER - 8;
        Ui.inset(g, px0 - 2, dy0, px1 + 2, y1 - Ui.BORDER - 6);
        int y = dy0 + 11, k = 0;
        List<Component> tip = null;
        for (var e : cart.entrySet()) {
            ColonyPayloads.OrderRow r = row(e.getKey());
            int x = cartX(k++);
            if (r == null || x + SLOT > px1 - 320) continue;
            Ui.slot(g, x, y, SLOT, r.icon().copyWithCount(1));
            count(g, e.getValue(), x, y);
            if (mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT) {
                tip = List.of(Component.literal(e.getValue() + " × ").append(r.name()),
                        Component.literal(Trade.money((long) r.cents() * e.getValue())).withStyle(ChatFormatting.GREEN));
            }
        }
        if (cart.isEmpty()) return tip;
        long t = total();
        Component sum = Component.translatable("minecraftportsmod.order.cart_total", Trade.money(t));
        int sx = px1 - 230 - font.width(sum) - 16;
        g.text(font, sum, sx, y + 6, t <= view.playerEmeralds() ? ChartStyle.INK : ChartStyle.BAD, false);
        g.item(new ItemStack(Items.EMERALD), sx + font.width(sum) + 2, y + 2);
        return tip;
    }

    /**
     * The workshop's queue, whoever's, the oldest first: the one being made with its bar (how far along the making in
     * hand is) and the seconds left; the rest, made of ordered and whose. Under it, the tools in the slot.
     */
    private void queue(GuiGraphicsExtractor g) {
        g.text(font, Component.translatable("minecraftportsmod.order.queue"), rx0 + 2, ly0 - 12, ChartStyle.INK, false);
        int qy1 = ly1 - 28, qrow = 28;
        Ui.inset(g, rx0, ly0, rx1, qy1);
        int y = ly0 + 3;
        for (int i = scrollRight; i < view.queue().size() && y + qrow <= qy1 - 2; i++, y += qrow) {
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
        int n = event.hasShiftDown() ? 64 : event.hasControlDown() ? 5 : 1;
        boolean back = event.button() == 1;
        // a recipe: into the cart (the right button: out of it)
        if (my >= ly0 && my < ly1 && mx >= lx0 && mx < lx1) {
            List<ColonyPayloads.OrderRow> rows = rows();
            int i = scrollLeft + (int) ((my - ly0 - 3) / ROW);
            if (i < 0 || i >= rows.size() || !rows.get(i).open()) return false;
            put(rows.get(i).recipe(), back ? -n : n);
            return true;
        }
        // the cart: a click takes back
        int y = dy0 + 11, k = 0;
        for (var e : new ArrayList<>(cart.entrySet())) {
            int x = cartX(k++);
            if (mx >= x && mx < x + SLOT && my >= y && my < y + SLOT) {
                put(e.getKey(), -n);
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean uiScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = (int) -Math.signum(scrollY);
        int visible = Math.max(1, (ly1 - ly0 - 4) / ROW);
        if (mouseX < lx1) scrollLeft = Math.max(0, Math.min(Math.max(0, rows().size() - visible), scrollLeft + step));
        else scrollRight = Math.max(0, Math.min(Math.max(0, view.queue().size() - 1), scrollRight + step));
        return true;
    }
}
