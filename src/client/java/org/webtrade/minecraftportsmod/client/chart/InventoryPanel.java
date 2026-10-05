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
    static final int W = 150;

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
        // (as wide as there is room for, down to a narrow one)
        int x1 = (screen.width - 176) / 2 - 4, W = Math.min(InventoryPanel.W, x1 - 2), x0 = x1 - W, y0 = (screen.height - 166) / 2;
        if (W < 80) return;
        int rows = Math.min(6, view.quests().size());
        int y1 = y0 + 34 + (rows > 0 ? 6 + rows * 24 : 0) + (view.plot().getString().isEmpty() ? 0 : 14) + (view.hired().getString().isEmpty() ? 0 : 14);
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
                slot(g, x0 + 8, y + 1, q.icon());
                g.text(font, Ui.fit(font, q.who().getString(), W - 44), x0 + 32, y + 1, INK, false);
                int bx0 = x0 + 32, bx1 = x1 - 30, by = y + 12;
                g.fill(bx0, by, bx1, by + 4, 0x30000000);
                g.fill(bx0, by, bx0 + (bx1 - bx0) * Math.min(q.have(), q.need()) / Math.max(1, q.need()), by + 4, ChartStyle.GOOD);
                String n = q.have() + "/" + q.need();
                g.text(font, n, x1 - 8 - font.width(n), y + 9, MUTED, false);
                y += 24;
            }
        }
        if (!view.plot().getString().isEmpty()) {
            g.item(new ItemStack(org.webtrade.minecraftportsmod.registry.ModContent.PLOT_MARKER_ITEM), x0 + 8, y - 4);
            g.text(font, Ui.fit(font, view.plot().getString(), W - 36), x0 + 28, y, INK, false);
            y += 14;
        }
        if (!view.hired().getString().isEmpty()) {
            g.item(new ItemStack(Items.IRON_AXE), x0 + 8, y - 4);
            g.text(font, Ui.fit(font, view.hired().getString(), W - 36), x0 + 28, y, INK, false);
        }
    }

    private static final int INK = 0xFF404040, MUTED = 0xFF707070;

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
