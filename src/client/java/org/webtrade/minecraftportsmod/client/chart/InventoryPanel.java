package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.colony.Quests;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * Beside the player's inventory (E): the purse, the tasks taken with how far along they are, the plot, the trade
 * hired at. Asked of the server when the inventory opens and every two seconds while it is open.
 */
public final class InventoryPanel {

    private InventoryPanel() {
    }

    private static ColonyPayloads.PanelView view;
    private static int ticks;
    static final int W = 220;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(ColonyPayloads.PanelView.TYPE, (payload, ctx) -> view = payload);
        ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
            if (!(screen instanceof InventoryScreen)) return;
            ask();
            ScreenEvents.afterExtract(screen).register((s, g, mx, my, pt) -> draw(s, g, mx, my));
            ScreenEvents.afterTick(screen).register(s -> {
                if (++ticks % 40 == 0) ask();
            });
        });
    }

    private static void ask() {
        if (Minecraft.getInstance().getConnection() != null) ClientPlayNetworking.send(new ColonyPayloads.RequestPanel());
    }

    /** Where each task is in the panel as last drawn: {x0, y0, x1, y1} by task. */
    private static final java.util.List<int[]> ROWS = new java.util.ArrayList<>();

    private static void draw(Screen screen, GuiGraphicsExtractor g, int mx, int my) {
        if (view == null) return;
        var font = Minecraft.getInstance().font;
        // left of the inventory's window (176 wide, in the middle)
        // (as wide as there is room for, down to a narrow one; with the recipe book open beside the inventory, right of it)
        var player = Minecraft.getInstance().player;
        boolean book = player != null && screen.width >= 379
                && player.getRecipeBook().isOpen(net.minecraft.world.inventory.RecipeBookType.CRAFTING);
        int y0 = (screen.height - 166) / 2, x0, x1, W;
        if (book) {
            x0 = (screen.width - 176) / 2 + 77 + 176 + 4;
            W = Math.min(InventoryPanel.W, screen.width - 2 - x0);
            x1 = x0 + W;
        } else {
            x1 = (screen.width - 176) / 2 - 4;
            W = Math.min(InventoryPanel.W, x1 - 2);
            x0 = x1 - W;
        }
        if (W < 80) return;
        // (the text wraps rather than being cut: the panel as tall as it comes to, kept on the screen)
        int h = body(null, font, x0, x1, 0);
        y0 = Math.max(2, Math.min(y0, screen.height - 2 - h));
        panel(g, x0, y0, x1, y0 + h);
        ROWS.clear();
        body(g, font, x0, x1, y0);
        // the task under the mouse: all of it
        for (int i = 0; i < ROWS.size(); i++) {
            int[] r = ROWS.get(i);
            if (mx < r[0] || mx >= r[2] || my < r[1] || my >= r[3]) continue;
            // (drawn now, on top: the frame's own tips are drawn already by the time the panel is)
            g.nextStratum();
            g.tooltip(font, tip(font, view.quests().get(i)).stream()
                            .map(net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent::create).toList(), mx, my,
                    net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner.INSTANCE, null);
        }
    }

    /** A task in full: what is asked, how far along, what is carried, the reward, the days left, whose and where. */
    private static java.util.List<net.minecraft.util.FormattedCharSequence> tip(net.minecraft.client.gui.Font font, ColonyPayloads.PanelQuest q) {
        java.util.List<net.minecraft.util.FormattedCharSequence> out = new java.util.ArrayList<>();
        out.addAll(font.split(q.full().copy().withStyle(net.minecraft.ChatFormatting.WHITE), 220));
        java.util.List<Component> more = new java.util.ArrayList<>();
        Quests.Kind kind = Quests.Kind.values()[Math.min(q.kind(), Quests.Kind.values().length - 1)];
        switch (kind) {
            case HUNT -> more.add(Component.translatable("minecraftportsmod.panel.tip.killed", q.done(), q.need()));
            case LETTER -> {
                if (!q.to().isEmpty()) more.add(Component.translatable("minecraftportsmod.panel.tip.letter", q.to()));
            }
            default -> {
                more.add(Component.translatable("minecraftportsmod.panel.tip.done", q.done(), q.need()));
                more.add(Component.translatable("minecraftportsmod.panel.tip.carried", q.carried()));
            }
        }
        more.add(Component.translatable("minecraftportsmod.panel.tip.reward", q.reward()).withStyle(net.minecraft.ChatFormatting.GREEN));
        more.add(Component.translatable("minecraftportsmod.panel.tip.days", q.days())
                .withStyle(q.days() <= 1 ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.GRAY));
        Component who = q.trade().getString().isEmpty() ? Component.literal(q.giver())
                : Component.literal(q.giver() + ", ").append(q.trade());
        more.add(who.copy().append(" · " + q.village()).withStyle(net.minecraft.ChatFormatting.GRAY));
        for (Component c : more) out.addAll(font.split(c, 220));
        return out;
    }

    /** The panel's insides from {@code y0}; drawn unless {@code g} is null. Returns how tall it all is. */
    private static int body(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, int x0, int x1, int y0) {
        // the purse
        if (g != null) {
            slot(g, x0 + 8, y0 + 9, new ItemStack(Items.EMERALD));
            g.text(font, org.webtrade.minecraftportsmod.colony.Trade.money(view.purse()), x0 + 34, y0 + 14, INK, false);
        }
        int y = y0 + 34;
        int rows = Math.min(Quests.MAX_TAKEN, view.quests().size());
        if (rows > 0) {
            if (g != null) g.fill(x0 + 8, y, x1 - 8, y + 1, 0x30000000);
            y += 6;
            for (int i = 0; i < rows; i++) {
                ColonyPayloads.PanelQuest q = view.quests().get(i);
                int top = y, tx = x0 + 32, rx = x1 - 8;
                if (g != null) ROWS.add(new int[]{x0 + 4, top, x1 - 4, 0});
                if (g != null) slot(g, x0 + 8, y + 2, q.icon());
                // what is wanted, and how many of it there are already
                String n = q.have() + "/" + q.need();
                if (g != null) g.text(font, n, rx - font.width(n), y + 1, q.have() >= q.need() ? DONE : INK, false);
                y = lines(g, font, q.what(), tx, y + 1, rx - tx - font.width(n) - 6, INK);
                // whom it is for, and where
                y = lines(g, font, Component.translatable("minecraftportsmod.panel.to", q.giver(), q.village()), tx, y + 1, rx - tx, MUTED);
                // how far along, and the days left
                String d = Component.translatable("minecraftportsmod.panel.days", q.days()).getString();
                int by = Math.max(y + 1, top + 21);
                if (g != null) {
                    g.text(font, d, rx - font.width(d), by - 1, q.days() <= 1 ? LATE : MUTED, false);
                    int bx = rx - font.width(d) - 5;
                    g.fill(tx, by, bx, by + 6, 0xFF373737);
                    g.fill(tx + 1, by + 1, bx - 1, by + 5, 0xFF6B6B6B);
                    int w = (bx - tx - 2) * Math.min(q.have(), q.need()) / Math.max(1, q.need());
                    if (w > 0) g.fill(tx + 1, by + 1, tx + 1 + w, by + 5, BAR);
                }
                y = by + 10;
                if (g != null) ROWS.getLast()[3] = y;
            }
        }
        boolean plot = !view.plot().getString().isEmpty(), hired = !view.hired().getString().isEmpty();
        if (plot || hired) {
            if (g != null) g.fill(x0 + 8, y, x1 - 8, y + 1, 0x30000000);
            y += 6;
        }
        if (plot) {
            if (g != null) slot(g, x0 + 8, y, new ItemStack(org.webtrade.minecraftportsmod.registry.ModContent.PLOT_MARKER_ITEM));
            y = Math.max(y + 20, lines(g, font, view.plot(), x0 + 32, y + 5, x1 - 8 - x0 - 32, INK) + 4);
        }
        if (hired) {
            if (g != null) slot(g, x0 + 8, y, new ItemStack(Items.IRON_AXE));
            y = Math.max(y + 20, lines(g, font, view.hired(), x0 + 32, y + 5, x1 - 8 - x0 - 32, INK) + 4);
        }
        return Math.max(40, y - y0 + 4);
    }

    /** Text wrapped to {@code w} (drawn unless {@code g} is null); returns the y under it. */
    private static int lines(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, Component text, int x, int y, int w, int color) {
        for (var l : font.split(text, Math.max(20, w))) {
            if (g != null) g.text(font, l, x, y, color, false);
            y += 10;
        }
        return y;
    }

    private static final int INK = 0xFF2A2A2A, MUTED = 0xFF505050, DONE = 0xFF1E6B26, LATE = 0xFF9A1E1E, BAR = 0xFF2F9A3A;

    /** A panel like the inventory's own: light grey, a white edge top-left, a dark one bottom-right, a black rim. */
    private static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        g.fill(x0 + 1, y0, x1 - 1, y1, 0xFF000000);
        g.fill(x0, y0 + 1, x1, y1 - 1, 0xFF000000);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0xFFC6C6C6);
        g.fill(x0 + 1, y0 + 1, x1 - 2, y0 + 3, 0xFFFFFFFF);
        g.fill(x0 + 1, y0 + 1, x0 + 3, y1 - 2, 0xFFFFFFFF);
        g.fill(x0 + 3, y1 - 3, x1 - 1, y1 - 1, 0xFF555555);
        g.fill(x1 - 3, y0 + 3, x1 - 1, y1 - 1, 0xFF555555);
    }

    /** A slot like the inventory's (18 across), with a thing in it. */
    private static void slot(GuiGraphicsExtractor g, int x, int y, ItemStack stack) {
        g.fill(x, y, x + 18, y + 18, 0xFF373737);
        g.fill(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
        if (!stack.isEmpty()) g.item(stack, x + 1, y + 1);
    }

    /** For tests: where the tasks are in the panel as last drawn ({x0, y0, x1, y1}, in the screen's scaled units). */
    public static java.util.List<int[]> rows() {
        return java.util.List.copyOf(ROWS);
    }

    /** For tests: what the panel shows now. */
    public static ColonyPayloads.PanelView view() {
        return view;
    }

    static Component none() {
        return Component.empty();
    }
}
