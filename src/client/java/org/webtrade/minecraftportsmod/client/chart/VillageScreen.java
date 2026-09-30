package org.webtrade.minecraftportsmod.client.chart;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Tree;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;
import org.webtrade.minecraftportsmod.network.ColonyPayloads.BuildingRow;
import org.webtrade.minecraftportsmod.network.ColonyPayloads.PersonRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The village board: what the village is and has, who lives there and what they are doing, what stands and what is
 * being built, a map of the place, the village's tasks for players (to come) and its log. Refreshes while open.
 */
public class VillageScreen extends UiScreen {

    private enum Tab {OVERVIEW, PEOPLE, STORE, TRADE, TREE, MAP, TASKS, LOG}

    private static final int ROW = 24;
    private static final int TAB_HEIGHT = 18;
    private static final int REFRESH_TICKS = 40;
    private static final Identifier MAP_TEXTURE = Minecraftportsmod.id("village_map");

    private final Screen parent;
    private final int villageId;
    private ColonyPayloads.VillageView view;
    private Tab tab = Tab.OVERVIEW;
    private int scroll, ticks;
    private int x0, y0, x1, y1, cx0, cy0, cx1, cy1;
    // the map: its texture, zoom and where it is looked at (blocks from the village middle)
    private DynamicTexture mapTexture;
    private int mapSize;
    private double zoom = 2, panX, panZ;
    private boolean dragging;
    // the development tree
    private TreeView treeView;

    public VillageScreen(Screen parent, int villageId) {
        super(Component.translatable("minecraftportsmod.vboard.title"));
        this.parent = parent;
        this.villageId = villageId;
    }

    public int villageId() {
        return villageId;
    }

    public void update(ColonyPayloads.VillageView v) {
        if (v.id() != villageId) return;
        if (v.map().length > 0 && v.mapSize() > 0) uploadMap(v.mapSize(), v.map());
        this.view = v;
    }

    /** Picks a node of the development tree (automated tests). */
    public void select(int type) {
        tree().select(type);
    }

    /** Where a node of the tree is on the screen (automated tests). */
    public double[] nodeOnScreen(BuildingType t) {
        return tree().onScreen(t);
    }

    /** Where the player moved a node to, or null (automated tests). */
    public static double[] nodeMoved(BuildingType t) {
        return TreeView.moved(t);
    }

    private TreeView tree() {
        if (treeView == null) treeView = new TreeView(Minecraft.getInstance().font, villageId);
        return treeView;
    }

    /** Switches tabs (automated tests). */
    public void showTab(String name) {
        for (Tab t : Tab.values()) if (t.name().equalsIgnoreCase(name)) tab = t;
        scroll = 0;
    }

    private void uploadMap(int size, int[] argb) {
        if (mapTexture == null || mapSize != size) {
            if (mapTexture != null) mapTexture.close();
            mapTexture = new DynamicTexture(MAP_TEXTURE::toString, size, size, false);
            Minecraft.getInstance().getTextureManager().register(MAP_TEXTURE, mapTexture);
            mapSize = size;
        }
        NativeImage img = mapTexture.getPixels();
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int c = argb[z * size + x];
                img.setPixel(x, z, c == 0 ? 0xFFE4D2A6 : c);
            }
        }
        mapTexture.upload();
    }

    @Override
    protected void layout() {
        int w = Math.min(width - 24, 720), h = Math.min(height - 24, 465);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        cx0 = x0 + 8;
        cx1 = x1 - 8;
        cy0 = y0 + 24 + 34 + TAB_HEIGHT + 2;
        cy1 = y1 - 10;
        cx0 = x0 + 10;
        cx1 = x1 - 10;
        scaleButtons(x1 - 8, y0 + 5);
        if (view == null) ClientPlayNetworking.send(new ColonyPayloads.RequestVillage(villageId, true));
        tree();
    }

    @Override
    public void tick() {
        if (++ticks % REFRESH_TICKS == 0) ClientPlayNetworking.send(new ColonyPayloads.RequestVillage(villageId, tab == Tab.MAP && ticks % 200 == 0));
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
    public void removed() {
        if (mapTexture != null) {
            Minecraft.getInstance().getTextureManager().release(MAP_TEXTURE);
            mapTexture = null;
        }
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
            case OVERVIEW -> drawOverview(g, mouseX, mouseY);
            case STORE -> drawStore(g, mouseX, mouseY);
            case TRADE -> drawTrade(g, mouseX, mouseY);
            case PEOPLE -> drawPeople(g, mouseX, mouseY);
            case TREE -> drawTree(g, mouseX, mouseY);
            case MAP -> drawMap(g, mouseX, mouseY);
            case TASKS -> drawTasks(g);
            case LOG -> drawLog(g);
        }
        g.disableScissor();
        widgets(g, mouseX, mouseY, partialTick);
    }

    private Village.Level level() {
        return Village.Level.values()[view.level()];
    }

    private void drawHeader(GuiGraphicsExtractor g) {
        Component name = Component.literal(view.name());
        int tw = font.width(name) + 32, cx = (x0 + x1) / 2;
        Ui.blit(g, Ui.PLATE, cx - tw / 2, y0 + 4, cx + tw / 2, y0 + 22);
        g.text(font, name, cx - font.width(name) / 2, y0 + 9, 0xFF3A2610, false);
        Component day = Component.translatable("minecraftportsmod.hall.day", view.day());
        int bw = 70, bx = x1 - 50 - bw, by = y0 + 9;
        Ui.bar(g, bx, by - 1, bx + bw, by + 7, view.progress(), ChartStyle.BRASS);
        g.text(font, day, bx - 6 - font.width(day), y0 + 9, ChartStyle.TEXT_LIGHT, true);
    }

    private void drawSummary(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int sy0 = y0 + 26, sy1 = sy0 + 30;
        Ui.inset(g, x0 + 10, sy0 - 1, x1 - 10, sy1 + 1);
        int x = x0 + 16;
        Component lvl = Component.translatable("minecraftportsmod.vboard.level", level().number(), level().displayName());
        g.text(font, lvl, x, sy0 + 4, ChartStyle.TEXT, false);
        Component people = Component.translatable("minecraftportsmod.vboard.people", view.people().size(), view.beds());
        g.text(font, people, x, sy0 + 16, ChartStyle.TEXT_MUTED, false);
        // mood
        int mx = x + Math.max(font.width(lvl), font.width(people)) + 18, mw = 80;
        g.text(font, Component.translatable("minecraftportsmod.hall.mood"), mx, sy0 + 4, ChartStyle.TEXT, false);
        bar(g, mx, sy0 + 16, mw, 6, view.mood() / 100F);
        // the stores, at the right
        int rx = x1 - 14, limit = mx + mw + 12;
        for (int i = Res.values().length - 1; i >= 0; i--) {
            Res r = Res.values()[i];
            String n = String.valueOf(view.stock()[i]);
            // (a narrow window: the ones that don't fit are on the overview)
            if (rx - font.width(n) - 18 < limit) break;
            rx -= font.width(n);
            g.text(font, n, rx, sy0 + 10, ChartStyle.TEXT, false);
            rx -= 18;
            g.item(new ItemStack(r.icon), rx, sy0 + 6);
            if (mouseX >= rx && mouseX < rx + 18 + font.width(n) && mouseY >= sy0 && mouseY < sy1) {
                g.setComponentTooltipForNextFrame(font, stockTip(r), mouseX, mouseY);
            }
            rx -= 10;
        }
    }

    private List<Component> stockTip(Res r) {
        List<Component> lines = new ArrayList<>();
        lines.add(r.displayName());
        lines.add(Component.translatable("minecraftportsmod.vboard.in_store", view.stock()[r.ordinal()]).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("minecraftportsmod.vboard.made", view.made()[r.ordinal()]).withStyle(ChatFormatting.DARK_GREEN));
        if (r == Res.FOOD) lines.add(Component.translatable("minecraftportsmod.vboard.eaten", view.foodNeed()).withStyle(ChatFormatting.RED));
        return lines;
    }

    private static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float v) {
        v = Math.max(0, Math.min(1, v));
        g.fill(x, y, x + w, y + h, ChartStyle.WOOD_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, ChartStyle.PARCHMENT_SHADE);
        int color = v >= 0.6F ? ChartStyle.GOOD : v >= 0.35F ? 0xFFC9A038 : ChartStyle.BAD;
        g.fill(x + 1, y + 1, x + 1 + Math.round((w - 2) * v), y + h - 1, color);
    }

    private void drawTabs(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = cx0;
        int y = cy0 - TAB_HEIGHT - 1;
        for (Tab t : Tab.values()) {
            Component label = tabLabel(t);
            int w = font.width(label) + 22;
            boolean active = t == tab;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + TAB_HEIGHT;
            Ui.blit(g, active ? Ui.TAB_OPEN : hover ? Ui.TAB_HOVER : Ui.TAB, x, y, x + w, y + TAB_HEIGHT + (active ? 1 : 0));
            g.text(font, label, x + 7, y + 6, active ? ChartStyle.TEXT : ChartStyle.PARCHMENT_SHADE, !active);
            x += w + 2;
        }
    }

    private Component tabLabel(Tab t) {
        return Component.translatable("minecraftportsmod.vboard.tab." + t.name().toLowerCase(Locale.ROOT));
    }

    private Tab tabAt(double mx, double my) {
        int x = cx0;
        int y = cy0 - TAB_HEIGHT - 1;
        if (my < y || my >= y + TAB_HEIGHT) return null;
        for (Tab t : Tab.values()) {
            int w = font.width(tabLabel(t)) + 22;
            if (mx >= x && mx < x + w) return t;
            x += w + 2;
        }
        return null;
    }

    // --- overview: the way to the next level, and the building sites

    private void drawOverview(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int mid = (cx0 + cx1) / 2;
        int x = cx0 + 6, y = cy0 + 6;
        Village.Level next = level().next();
        if (next == null) {
            g.text(font, Component.translatable("minecraftportsmod.vboard.top_level"), x, y, ChartStyle.TEXT, false);
            y += 6;
        } else {
            g.text(font, Component.translatable("minecraftportsmod.vboard.next_level", next.number(), next.displayName()), x, y, ChartStyle.ROUTE, false);
            y += 13;
            for (ColonyPayloads.Req r : view.reqs()) {
                boolean ok = r.have() >= r.need();
                Component line = Component.literal(ok ? "✔ " : "✘ ").append(r.label()).append(": " + r.have() + " / " + r.need());
                g.text(font, line, x + 2, y, ok ? ChartStyle.GOOD : ChartStyle.BAD, false);
                y += 11;
            }
        }
        y += 8;
        int colW = mid - x - 12;
        // the queue: as many places as the village takes on at once; what is in each
        y = drawSlots(g, x, y, colW, mouseX, mouseY) + 10;
        // how near the next child (the chance tonight: what was built up, and what tonight adds)
        int labelW = font.width(Component.translatable("minecraftportsmod.vboard.growth"));
        int bx0 = x + labelW + 8, bx1 = x + colW;
        boolean bed = view.beds() > view.people().size();
        float tonight = Math.min(1F, view.ready() + (bed ? view.gain() : 0));
        g.text(font, Component.translatable("minecraftportsmod.vboard.growth"), x, y + 2, ChartStyle.INK, false);
        Ui.bar(g, bx0, y, bx1, y + 12, Math.min(1F, tonight / 0.5F), 0xFF6FA8DC);
        Component growth = bed ? Component.translatable("minecraftportsmod.vboard.growth_val", Math.round(tonight * 100), Math.round(view.gain() * 100))
                : Component.translatable("minecraftportsmod.vboard.growth_wait");
        g.text(font, growth, (bx0 + bx1 - font.width(growth)) / 2, y + 2, ChartStyle.TEXT_LIGHT, true);
        y += 16;

        // building sites, on the right
        g.fill(mid, cy0 + 4, mid + 1, cy1 - 4, ChartStyle.PARCHMENT_SHADE);
        int rx = mid + 8, ry = cy0 + 6;
        Ui.heading(g, font, Component.translatable("minecraftportsmod.vboard.sites"), rx, ry, cx1 - rx - 8);
        ry += 18;
        boolean any = false;
        for (BuildingRow b : view.buildings()) {
            if (b.state() == Building.State.BUILT.ordinal() && b.progress() >= 1) continue;
            any = true;
            BuildingType t = BuildingType.values()[b.type()];
            Ui.slot(g, rx, ry, 24, new ItemStack(t.icon));
            g.text(font, t.displayName(), rx + 30, ry, ChartStyle.INK, false);
            g.text(font, font.plainSubstrByWidth(stateText(b).getString(), cx1 - rx - 36), rx + 30, ry + 10, stateColor(b), false);
            int bw = cx1 - rx - 12;
            boolean waiting = b.state() == Building.State.PLANNED.ordinal() || b.state() == Building.State.BUILT.ordinal() && b.progress() < 0.5F;
            if (!waiting) Ui.bar(g, rx + 30, ry + 20, rx + bw, ry + 28, b.progress(), ChartStyle.GOOD);
            else g.text(font, missingText(b), rx + 30, ry + 20, ChartStyle.TEXT_MUTED, false);
            if (mouseX >= rx && mouseX < cx1 && mouseY >= ry && mouseY < ry + 28) {
                g.setComponentTooltipForNextFrame(font, buildingTip(b), mouseX, mouseY);
            }
            ry += 32;
        }
        if (!any) g.textWithWordWrap(font, Component.translatable("minecraftportsmod.vboard.no_sites"), rx, ry, cx1 - rx - 6, ChartStyle.TEXT_MUTED, false);
    }

    /** The queue's places, in a row: what is in each (its icon; the level it is raised to), the free ones empty. */
    private int drawSlots(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
        Component title = Component.translatable("minecraftportsmod.vboard.slots");
        g.text(font, title, x, y + 6, ChartStyle.INK, false);
        List<ColonyPayloads.QueueRow> steps = new ArrayList<>();
        for (ColonyPayloads.QueueRow q : view.queue()) if (q.kind() != 4) steps.add(q);
        int sx = x + font.width(title) + 8, size = 20;
        for (int i = 0; i < view.slots(); i++) {
            int cx = sx + i * (size + 3);
            if (cx + size > x + w) break;
            ColonyPayloads.QueueRow q = i < steps.size() ? steps.get(i) : null;
            g.fill(cx, y, cx + size, y + size, q == null ? ChartStyle.PARCHMENT_SHADE : 0xFFF3E6C4);
            g.outline(cx, y, size, size, q == null ? 0x60000000 : ChartStyle.INK_SOFT);
            if (q == null) continue;
            BuildingType t = BuildingType.values()[q.type()];
            g.item(new ItemStack(t.icon), cx + 2, cy(y));
            if (q.kind() == 2) g.text(font, String.valueOf(q.level()), cx + size - 6, y + size - 8, ChartStyle.INK, true);
            if (q.kind() == 0) g.text(font, "?", cx + size - 6, y + size - 8, 0xFFB07A10, true);
            // how far it has come, under it
            g.fill(cx + 1, y + size - 2, cx + 1 + Math.round((size - 2) * Math.max(0, Math.min(1, q.progress()))), y + size - 1, ChartStyle.GOOD);
            if (mouseX >= cx && mouseX < cx + size && mouseY >= y && mouseY < y + size) {
                Component name = switch (q.kind()) {
                    case 0 -> Component.translatable("minecraftportsmod.queue.research", t.displayName());
                    case 2 -> Component.translatable("minecraftportsmod.queue.raise", t.displayName(), q.level());
                    case 3 -> Component.translatable("minecraftportsmod.queue.demolish", t.displayName());
                    default -> t.displayName();
                };
                g.setComponentTooltipForNextFrame(font, List.of(name, q.status()), mouseX, mouseY);
            }
        }
        return y + size;
    }

    private static int cy(int y) {
        return y + 2;
    }

    // --- the stores: one room for everything; each thing: how much there is, made and spent the last day

    private void drawStore(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = cx0 + 8, w = cx1 - cx0 - 16, y = cy0 + 8;
        float fill = view.room() <= 0 ? 0 : view.stored() / (float) view.room();
        Component title = Component.translatable("minecraftportsmod.vboard.store");
        g.text(font, title, x, y + 2, ChartStyle.INK, false);
        int bx0 = x + font.width(title) + 10;
        Ui.bar(g, bx0, y, x + w, y + 12, fill, fill >= 1 ? ChartStyle.BAD : fill >= 0.85F ? 0xFFC9A038 : ChartStyle.GOOD);
        Component st = Component.translatable("minecraftportsmod.vboard.store_val", view.stored(), view.room());
        g.text(font, st, (bx0 + x + w - font.width(st)) / 2, y + 2, ChartStyle.TEXT_LIGHT, true);
        y += 22;
        // the columns: the thing, in store, made, spent, the day's balance
        int cName = x + 24, cHave = x + w * 45 / 100, cGot = x + w * 60 / 100, cSpent = x + w * 75 / 100, cNet = x + w * 90 / 100;
        String[] heads = {"minecraftportsmod.vboard.col_have", "minecraftportsmod.vboard.col_got", "minecraftportsmod.vboard.col_spent", "minecraftportsmod.vboard.col_net"};
        int[] cols = {cHave, cGot, cSpent, cNet};
        for (int k = 0; k < heads.length; k++) {
            Component h = Component.translatable(heads[k]);
            g.text(font, h, cols[k] - font.width(h), y, ChartStyle.TEXT_MUTED, false);
        }
        y += 12;
        g.fill(x, y, x + w, y + 1, ChartStyle.PARCHMENT_SHADE);
        y += 4;
        int rowH = Math.max(16, Math.min(20, (cy1 - y - 4) / Res.values().length));
        int row = 0;
        for (Res r : Res.values()) {
            int i = r.ordinal();
            if (row++ % 2 == 1) g.fill(x, y - 1, x + w, y + rowH - 1, 0x18000000);
            g.item(new ItemStack(r.icon), x + 2, y + (rowH - 16) / 2);
            int ty = y + (rowH - 8) / 2;
            g.text(font, r.displayName(), cName, ty, ChartStyle.INK, false);
            String have = String.valueOf(view.stock()[i]);
            g.text(font, have, cHave - font.width(have), ty, ChartStyle.INK, false);
            int got = view.got()[i], spent = view.spent()[i], net = got - spent;
            String gs = got > 0 ? "+" + got : "0", ss = spent > 0 ? "−" + spent : "0";
            g.text(font, gs, cGot - font.width(gs), ty, got > 0 ? ChartStyle.GOOD : ChartStyle.TEXT_MUTED, false);
            g.text(font, ss, cSpent - font.width(ss), ty, spent > 0 ? ChartStyle.BAD : ChartStyle.TEXT_MUTED, false);
            String ns = net == 0 ? "0" : (net > 0 ? "+" : "−") + Math.abs(net);
            g.text(font, ns, cNet - font.width(ns), ty, net > 0 ? ChartStyle.GOOD : net < 0 ? ChartStyle.BAD : ChartStyle.TEXT_MUTED, false);
            y += rowH;
        }
    }

    // --- trade: where the merchant is; what the village is short of and has to spare; its deals

    private void drawTrade(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = cx0 + 8, w = cx1 - cx0 - 16, y = cy0 + 8;
        y += Ui.wrap(g, font, view.merchant(), x, y, w, ChartStyle.INK) + 8;
        int half = (w - 12) / 2;
        y = Math.max(y, wantList(g, x, y, half, Component.translatable("minecraftportsmod.trade.short"), view.shortOf(), ChartStyle.BAD, mouseX, mouseY));
        int y2 = wantList(g, x + half + 12, cy0 + 8 + font.wordWrapHeight(view.merchant(), w) + 8, half,
                Component.translatable("minecraftportsmod.trade.spare"), view.spare(), ChartStyle.GOOD, mouseX, mouseY);
        y = Math.max(y, y2) + 8;
        Ui.heading(g, font, Component.translatable("minecraftportsmod.trade.deals"), x, y, w);
        y += 16;
        if (view.deals().isEmpty()) {
            g.text(font, "—", x, y, ChartStyle.TEXT_MUTED, false);
            return;
        }
        for (ColonyPayloads.LogRow l : view.deals()) {
            if (y > cy1 - 12) break;
            Component day = Component.translatable("minecraftportsmod.hall.day", l.day());
            g.text(font, day, x, y, ChartStyle.TEXT_MUTED, false);
            int dw = 52;
            y += Ui.wrap(g, font, l.text(), x + dw, y, w - dw, ChartStyle.INK) + 3;
        }
    }

    /** A list of goods and amounts under a heading, two to a row; returns where it ends. */
    private int wantList(GuiGraphicsExtractor g, int x, int y, int w, Component title, int[] amounts, int color, int mouseX, int mouseY) {
        Ui.heading(g, font, title, x, y, w);
        y += 16;
        int col = w / 2, i = 0;
        for (Res r : Res.values()) {
            int n = amounts[r.ordinal()];
            if (n <= 0) continue;
            int cx = x + (i % 2) * col, cy = y + (i / 2) * 18;
            g.item(new ItemStack(r.icon), cx, cy);
            g.text(font, String.valueOf(n), cx + 20, cy + 5, color, false);
            if (mouseX >= cx && mouseX < cx + col && mouseY >= cy && mouseY < cy + 18) {
                g.setComponentTooltipForNextFrame(font, List.of(r.displayName()), mouseX, mouseY);
            }
            i++;
        }
        if (i == 0) {
            g.text(font, "—", x, y + 4, ChartStyle.TEXT_MUTED, false);
            i = 1;
        }
        return y + ((i + 1) / 2) * 18;
    }

    private Component stateText(BuildingRow b) {
        Building.State s = Building.State.values()[b.state()];
        Component eta = b.eta() < 0 ? Component.translatable("minecraftportsmod.vboard.eta_never")
                : b.eta() <= 1 ? Component.translatable("minecraftportsmod.vboard.eta_soon")
                : Component.translatable("minecraftportsmod.vboard.eta_days", b.eta());
        return switch (s) {
            case PLANNED -> Component.translatable("minecraftportsmod.bstate.planned").append(" · ").append(eta);
            case BUILDING -> Component.translatable("minecraftportsmod.bstate.building_pct", Math.round(b.progress() * 100)).append(" · ").append(eta);
            case BUILT -> b.progress() < 1 ? Component.translatable("minecraftportsmod.bstate.raising", Math.round(b.progress() * 100))
                    : Component.translatable("minecraftportsmod.bstate.built");
            case DEMOLISHING -> Component.translatable("minecraftportsmod.bstate.demolishing");
        };
    }

    private static int stateColor(BuildingRow b) {
        return switch (Building.State.values()[b.state()]) {
            case PLANNED -> 0xFFB07A10;
            case BUILDING -> ChartStyle.GOOD;
            case BUILT -> ChartStyle.TEXT_MUTED;
            case DEMOLISHING -> ChartStyle.BAD;
        };
    }

    private Component missingText(BuildingRow b) {
        var out = Component.translatable("minecraftportsmod.vboard.needs");
        boolean any = false;
        for (Res r : Res.values()) {
            int m = b.missing()[r.ordinal()];
            if (m <= 0) continue;
            out.append(" ").append(r.displayName()).append(" " + m);
            any = true;
        }
        return any ? out : Component.translatable("minecraftportsmod.vboard.all_there");
    }

    private List<Component> buildingTip(BuildingRow b) {
        BuildingType t = BuildingType.values()[b.type()];
        List<Component> lines = new ArrayList<>();
        lines.add(t.displayName().copy().withStyle(ChatFormatting.GOLD));
        lines.add(stateText(b).copy().withStyle(ChatFormatting.GRAY));
        if (b.beds() > 0) {
            lines.add(Component.translatable("minecraftportsmod.vboard.lives", b.people().size(), b.beds()).withStyle(ChatFormatting.GRAY));
            for (String p : b.people()) lines.add(Component.literal("  • " + p).withStyle(ChatFormatting.WHITE));
            if (b.people().size() < b.beds() && b.state() == Building.State.BUILT.ordinal()) {
                lines.add(Component.translatable("minecraftportsmod.vboard.free_beds", b.beds() - b.people().size()).withStyle(ChatFormatting.GREEN));
            }
        }
        if (b.state() == Building.State.PLANNED.ordinal()) lines.add(missingText(b).copy().withStyle(ChatFormatting.YELLOW));
        return lines;
    }

    // --- people

    private void drawPeople(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<PersonRow> rows = view.people();
        int visible = Math.max(1, (cy1 - cy0 - 14) / ROW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        int W = cx1 - cx0, homeX = cx0 + W * 30 / 100, daysX = cx0 + W * 52 / 100, doingX = cx0 + W * 60 / 100;
        g.text(font, Component.translatable("minecraftportsmod.vboard.col.name"), cx0 + 32, cy0 + 4, ChartStyle.TEXT_MUTED, false);
        g.text(font, Component.translatable("minecraftportsmod.vboard.col.home"), homeX, cy0 + 4, ChartStyle.TEXT_MUTED, false);
        g.text(font, Component.translatable("minecraftportsmod.vboard.col.days"), daysX, cy0 + 4, ChartStyle.TEXT_MUTED, false);
        g.text(font, Component.translatable("minecraftportsmod.vboard.col.doing"), doingX, cy0 + 4, ChartStyle.TEXT_MUTED, false);
        Ui.rule(g, cx0 + 4, cx1 - 4, cy0 + 15);
        int y = cy0 + 18;
        for (int i = scroll; i < rows.size() && y + ROW <= cy1; i++, y += ROW) {
            PersonRow p = rows.get(i);
            if (mouseX >= cx0 && mouseX < cx1 && mouseY >= y && mouseY < y + ROW) g.fill(cx0 + 2, y, cx1 - 2, y + ROW, 0x20000000);
            else if ((i & 1) == 1) g.fill(cx0 + 2, y, cx1 - 2, y + ROW, 0x0C000000);
            Job job = p.job() < 0 ? null : Job.values()[p.job()];
            Ui.slot(g, cx0 + 4, y + 1, 22, new ItemStack(job == null ? Items.POPPY : job.tool()));
            Component name = Component.literal((p.elder() ? "★ " : "") + p.name());
            g.text(font, name, cx0 + 32, y + 3, p.elder() ? 0xFF9A6A10 : ChartStyle.INK, false);
            Component role = job == null ? Component.translatable("minecraftportsmod.job.child") : job.displayName();
            g.text(font, role, cx0 + 32, y + 13, ChartStyle.TEXT_MUTED, false);
            g.text(font, Ui.fit(font, homeText(p).getString(), daysX - homeX - 8), homeX, y + 8, ChartStyle.TEXT, false);
            g.text(font, String.valueOf(Math.max(0, view.day() - p.joined())), daysX, y + 8, ChartStyle.TEXT_MUTED, false);
            g.text(font, Ui.fit(font, p.activity().getString(), cx1 - doingX - 8), doingX, y + 8, ChartStyle.INK_SOFT, false);
        }
    }

    private Component homeText(PersonRow p) {
        for (BuildingRow b : view.buildings()) {
            if (b.id() == p.home()) return BuildingType.values()[b.type()].displayName();
        }
        return Component.translatable("minecraftportsmod.colony.homeless");
    }

    // --- the development tree: a circle round the village's fire (see TreeView)

    private void drawTree(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (view.tree().isEmpty()) return;
        treeView.draw(g, view, cx0, cy0, cx1, cy1, mouseX, mouseY, ticks);
    }

    // --- the map

    private void drawMap(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int w = cx1 - cx0, h = cy1 - cy0;
        int mx = (cx0 + cx1) / 2, my = (cy0 + cy1) / 2;
        if (mapTexture == null || mapSize == 0) {
            g.centeredText(font, Component.translatable("minecraftportsmod.hall.loading"), mx, my, ChartStyle.TEXT_MUTED);
            return;
        }
        int r = mapSize / 2;
        // block (dx, dz) from the middle → screen
        double s = zoom;
        int left = (int) Math.round(mx - (r + panX) * s), top = (int) Math.round(my - (r + panZ) * s);
        int size = (int) Math.round(mapSize * s);
        g.blit(RenderPipelines.GUI_TEXTURED, MAP_TEXTURE, left, top, 0, 0, size, size, mapSize, mapSize, mapSize, mapSize);
        BuildingRow hovered = null;
        for (BuildingRow b : view.buildings()) {
            BuildingType t = BuildingType.values()[b.type()];
            int bx0 = (int) Math.round(mx + (b.dx() - t.half - panX) * s), bz0 = (int) Math.round(my + (b.dz() - t.half - panZ) * s);
            int bx1 = (int) Math.round(mx + (b.dx() + t.half + 1 - panX) * s), bz1 = (int) Math.round(my + (b.dz() + t.half + 1 - panZ) * s);
            boolean built = b.state() == Building.State.BUILT.ordinal();
            int frame = built ? 0xFF3B2A1C : stateColor(b);
            g.fill(bx0, bz0, bx1, bz1, (built ? 0xA0000000 : 0x50000000) | t.color);
            g.outline(bx0, bz0, bx1 - bx0, bz1 - bz0, frame);
            if (!built && (ticks / 10) % 2 == 0) g.outline(bx0 - 1, bz0 - 1, bx1 - bx0 + 2, bz1 - bz0 + 2, frame);
            int iconSize = 16;
            if (bx1 - bx0 >= 14) {
                g.item(new ItemStack(t.icon), (bx0 + bx1) / 2 - iconSize / 2, (bz0 + bz1) / 2 - iconSize / 2);
            }
            if (mouseX >= bx0 && mouseX < bx1 && mouseY >= bz0 && mouseY < bz1) hovered = b;
        }
        // the board and the viewer
        int bdx = (int) Math.round(mx + (view.boardDx() + 0.5 - panX) * s), bdz = (int) Math.round(my + (view.boardDz() + 0.5 - panZ) * s);
        g.fill(bdx - 2, bdz - 2, bdx + 3, bdz + 3, 0xFFFFE08A);
        g.outline(bdx - 3, bdz - 3, 7, 7, ChartStyle.INK);
        int px = (int) Math.round(mx + (view.viewerDx() + 0.5 - panX) * s), pz = (int) Math.round(my + (view.viewerDz() + 0.5 - panZ) * s);
        g.fill(px - 2, pz - 2, px + 3, pz + 3, 0xFFFFFFFF);
        g.outline(px - 3, pz - 3, 7, 7, 0xFFA8322A);
        if (hovered != null) g.setComponentTooltipForNextFrame(font, buildingTip(hovered), mouseX, mouseY);
    }

    // --- tasks (to come) and the log

    /** The buttons of the queue's rows as drawn last: x, y, action, building id. */
    private final List<int[]> queueButtons = new ArrayList<>();
    private static final int QROW = 30;

    /** The village's queue: what it is saving for and building, in order; the players move a step up, down or out. */
    private void drawTasks(GuiGraphicsExtractor g) {
        queueButtons.clear();
        int x = cx0 + 6, w = cx1 - cx0 - 12, y = cy0 + 4;
        if (view.queue().isEmpty()) {
            g.text(font, Component.translatable("minecraftportsmod.queue.empty"), x + 4, y + 2, ChartStyle.TEXT_MUTED, false);
            return;
        }
        int n = 0, skip = scroll;
        for (ColonyPayloads.QueueRow q : view.queue()) {
            n++;
            if (skip-- > 0) continue;
            if (y + QROW > cy1) break;
            g.fill(x, y, x + w, y + QROW - 3, (n % 2 == 0) ? 0x20000000 : 0x10000000);
            Component name = switch (q.kind()) {
                case 0 -> Component.translatable("minecraftportsmod.queue.research", BuildingType.values()[q.type()].displayName());
                case 2 -> Component.translatable("minecraftportsmod.queue.raise", BuildingType.values()[q.type()].displayName(), q.level());
                case 3 -> Component.translatable("minecraftportsmod.queue.demolish", BuildingType.values()[q.type()].displayName());
                case 4 -> Component.translatable("minecraftportsmod.queue.road");
                default -> BuildingType.values()[q.type()].displayName();
            };
            g.text(font, Component.literal(n + ". ").append(name), x + 4, y + 3, ChartStyle.TEXT, false);
            // how far it has come
            int bx = x + 4, bw = Math.min(160, w / 3), by = y + 15;
            g.fill(bx, by, bx + bw, by + 5, 0x40000000);
            g.fill(bx, by, bx + Math.round(bw * Math.max(0, Math.min(1, q.progress()))), by + 5, 0xFF5BA05A);
            // what it waits for, and what is missing
            List<Component> missing = new ArrayList<>();
            for (Res r : Res.values()) if (q.missing()[r.ordinal()] > 0) missing.add(Component.literal(q.missing()[r.ordinal()] + " ").append(r.displayName()));
            Component status = q.status();
            if (!missing.isEmpty()) {
                net.minecraft.network.chat.MutableComponent m = Component.translatable("minecraftportsmod.queue.missing");
                for (int i = 0; i < missing.size(); i++) m.append(i == 0 ? " " : ", ").append(missing.get(i));
                status = Component.empty().append(status).append(" · ").append(m);
            }
            if (q.eta() > 0) status = Component.empty().append(status).append(" · ").append(Component.translatable("minecraftportsmod.queue.eta", q.eta()));
            g.text(font, status, bx + bw + 8, y + 13, ChartStyle.TEXT_MUTED, false);
            // up, down, out (not for a trail)
            if (q.kind() != 4) {
                int ax = x + w - 3 * 16;
                String[] marks = {"▲", "▼", "✕"};
                int[] acts = {ColonyPayloads.VillageAction.QUEUE_UP, ColonyPayloads.VillageAction.QUEUE_DOWN, ColonyPayloads.VillageAction.QUEUE_CANCEL};
                for (int k = 0; k < 3; k++) {
                    if (q.kind() == 0 && k < 2) continue;
                    int bxk = ax + k * 16, byk = y + 2;
                    g.fill(bxk, byk, bxk + 14, byk + 12, 0x40000000);
                    g.centeredText(font, Component.literal(marks[k]), bxk + 7, byk + 2, k == 2 ? 0xFFB0403A : ChartStyle.TEXT);
                    queueButtons.add(new int[]{bxk, byk, acts[k], q.id()});
                }
            }
            y += QROW;
        }
    }

    private void drawLog(GuiGraphicsExtractor g) {
        int y = cy0 + 5;
        int skip = scroll;
        long lastDay = Long.MIN_VALUE;
        for (ColonyPayloads.LogRow l : view.log()) {
            if (skip-- > 0) continue;
            if (l.day() != lastDay) {
                lastDay = l.day();
                g.text(font, Component.translatable("minecraftportsmod.hall.day", l.day()), cx0 + 6, y, ChartStyle.ROUTE, false);
                y += 11;
            }
            for (FormattedCharSequence line : font.split(l.text(), cx1 - cx0 - 24)) {
                g.text(font, line, cx0 + 14, y, ChartStyle.TEXT, false);
                y += 10;
            }
            y += 2;
            if (y > cy1) break;
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    protected boolean uiClick(MouseButtonEvent event, boolean doubleClick) {
        if (super.uiClick(event, doubleClick)) return true;
        Tab t = tabAt(event.x(), event.y());
        if (t != null) {
            tab = t;
            scroll = 0;
            if (t == Tab.MAP) ClientPlayNetworking.send(new ColonyPayloads.RequestVillage(villageId, true));
            return true;
        }
        if (tab == Tab.MAP && event.x() >= cx0 && event.x() < cx1 && event.y() >= cy0 && event.y() < cy1) {
            dragging = true;
            return true;
        }
        if (tab == Tab.TREE && view != null) return tree().click(event.x(), event.y());
        if (tab == Tab.TASKS) {
            for (int[] b : queueButtons) {
                if (event.x() >= b[0] && event.x() < b[0] + 14 && event.y() >= b[1] && event.y() < b[1] + 12) {
                    ClientPlayNetworking.send(new ColonyPayloads.VillageAction(villageId, b[2], b[3], 0));
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected boolean uiRelease(MouseButtonEvent event) {
        dragging = false;
        tree().release();
        return super.uiRelease(event);
    }

    @Override
    protected boolean uiDrag(MouseButtonEvent event, double dx, double dy) {
        if (tab == Tab.TREE && tree().drag(event.x(), event.y(), dx, dy)) return true;
        if (dragging && tab == Tab.MAP) {
            panX -= dx / zoom;
            panZ -= dy / zoom;
            int r = mapSize / 2;
            panX = Math.max(-r, Math.min(r, panX));
            panZ = Math.max(-r, Math.min(r, panZ));
            return true;
        }
        return super.uiDrag(event, dx, dy);
    }

    @Override
    protected boolean uiScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == Tab.MAP) {
            zoom = Math.max(1, Math.min(6, zoom * (scrollY > 0 ? 1.25 : 0.8)));
            return true;
        }
        if (tab == Tab.TREE && tree().scroll(mouseX, mouseY, scrollY)) return true;
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
        if (view != null && tab == Tab.LOG) scroll = Math.min(scroll, Math.max(0, view.log().size() - 1));
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
