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
    static final int W = 220, ROW = 34;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(ColonyPayloads.PanelView.TYPE, (payload, ctx) -> view = payload);
        ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
            if (!(screen instanceof InventoryScreen)) return;
            ask();
            ScreenEvents.afterExtract(screen).register((s, g, mx, my, pt) -> draw(s, g));
            ScreenEvents.afterTick(screen).register(s -> {
                if (++ticks % 40 == 0) ask();
            });
        });
    }

    private static void ask() {
        if (Minecraft.getInstance().getConnection() != null) ClientPlayNetworking.send(new ColonyPayloads.RequestPanel());
    }

    private static void draw(Screen screen, GuiGraphicsExtractor g) {
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
        int rows = Math.min(6, view.quests().size());
        int y1 = y0 + 34 + (rows > 0 ? 6 + rows * ROW : 0) + (view.plot().getString().isEmpty() && view.hired().getString().isEmpty() ? 0 : 6)
                + (view.plot().getString().isEmpty() ? 0 : 20) + (view.hired().getString().isEmpty() ? 0 : 20) + 4;
        panel(g, x0, y0, x1, Math.max(y1, y0 + 40));
        // the purse
        slot(g, x0 + 8, y0 + 9, new ItemStack(Items.EMERALD));
        g.text(font, String.valueOf(view.purse()), x0 + 34, y0 + 14, INK, false);
        int y = y0 + 34;
        if (rows > 0) {
            g.fill(x0 + 8, y, x1 - 8, y + 1, 0x30000000);
            y += 6;
            for (int i = 0; i < rows; i++) {
                ColonyPayloads.PanelQuest q = view.quests().get(i);
                slot(g, x0 + 8, y + 2, q.icon());
                int tx = x0 + 32, rx = x1 - 8;
                // what is wanted, and how many of it there are already
                String n = q.have() + "/" + q.need();
                g.text(font, n, rx - font.width(n), y + 1, q.have() >= q.need() ? DONE : INK, false);
                g.text(font, Ui.fit(font, q.what().getString(), rx - tx - font.width(n) - 6), tx, y + 1, INK, false);
                // whose it is, and where
                g.text(font, Ui.fit(font, q.giver() + " · " + q.village(), rx - tx), tx, y + 12, MUTED, false);
                // how far along, and the days left
                String d = Component.translatable("minecraftportsmod.panel.days", q.days()).getString();
                g.text(font, d, rx - font.width(d), y + 23, q.days() <= 1 ? LATE : MUTED, false);
                int by = y + 24, bx = rx - font.width(d) - 5;
                g.fill(tx, by, bx, by + 6, 0xFF373737);
                g.fill(tx + 1, by + 1, bx - 1, by + 5, 0xFF6B6B6B);
                int w = (bx - tx - 2) * Math.min(q.have(), q.need()) / Math.max(1, q.need());
                if (w > 0) g.fill(tx + 1, by + 1, tx + 1 + w, by + 5, BAR);
                y += ROW;
            }
        }
        if (!view.plot().getString().isEmpty() || !view.hired().getString().isEmpty()) {
            g.fill(x0 + 8, y, x1 - 8, y + 1, 0x30000000);
            y += 6;
        }
        if (!view.plot().getString().isEmpty()) {
            slot(g, x0 + 8, y, new ItemStack(org.webtrade.minecraftportsmod.registry.ModContent.PLOT_MARKER_ITEM));
            g.text(font, Ui.fit(font, view.plot().getString(), W - 44), x0 + 32, y + 5, INK, false);
            y += 20;
        }
        if (!view.hired().getString().isEmpty()) {
            slot(g, x0 + 8, y, new ItemStack(Items.IRON_AXE));
            g.text(font, Ui.fit(font, view.hired().getString(), W - 44), x0 + 32, y + 5, INK, false);
        }
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

    /** For tests: what the panel shows now. */
    public static ColonyPayloads.PanelView view() {
        return view;
    }

    static Component none() {
        return Component.empty();
    }
}
