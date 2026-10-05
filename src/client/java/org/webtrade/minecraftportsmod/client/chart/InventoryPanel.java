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
        int x1 = (screen.width - 176) / 2 - 4, x0 = x1 - W, y0 = (screen.height - 166) / 2;
        if (x0 < 2) return;
        int rows = Math.min(6, view.quests().size());
        int y1 = y0 + 34 + (rows > 0 ? 6 + rows * 24 : 0) + (view.plot().getString().isEmpty() ? 0 : 14) + (view.hired().getString().isEmpty() ? 0 : 14);
        Ui.frame(g, x0, y0, x1, Math.max(y1, y0 + 40));
        // the purse
        Ui.slot(g, x0 + 8, y0 + 8, 20, new ItemStack(Items.EMERALD));
        g.text(font, String.valueOf(view.purse()), x0 + 34, y0 + 14, ChartStyle.INK, false);
        int y = y0 + 34;
        if (rows > 0) {
            g.fill(x0 + 8, y, x1 - 8, y + 1, 0x30000000);
            y += 6;
            for (int i = 0; i < rows; i++) {
                ColonyPayloads.PanelQuest q = view.quests().get(i);
                Ui.slot(g, x0 + 8, y, 20, q.icon());
                g.text(font, Ui.fit(font, q.who().getString(), W - 44), x0 + 32, y + 1, ChartStyle.INK, false);
                int bx0 = x0 + 32, bx1 = x1 - 30, by = y + 12;
                g.fill(bx0, by, bx1, by + 4, 0x30000000);
                g.fill(bx0, by, bx0 + (bx1 - bx0) * Math.min(q.have(), q.need()) / Math.max(1, q.need()), by + 4, ChartStyle.GOOD);
                String n = q.have() + "/" + q.need();
                g.text(font, n, x1 - 8 - font.width(n), y + 9, ChartStyle.TEXT_MUTED, false);
                y += 24;
            }
        }
        if (!view.plot().getString().isEmpty()) {
            g.item(new ItemStack(org.webtrade.minecraftportsmod.registry.ModContent.PLOT_MARKER_ITEM), x0 + 8, y - 4);
            g.text(font, Ui.fit(font, view.plot().getString(), W - 36), x0 + 28, y, ChartStyle.TEXT, false);
            y += 14;
        }
        if (!view.hired().getString().isEmpty()) {
            g.item(new ItemStack(Items.IRON_AXE), x0 + 8, y - 4);
            g.text(font, Ui.fit(font, view.hired().getString(), W - 36), x0 + 28, y, ChartStyle.TEXT, false);
        }
    }

    /** For tests: what the panel shows now. */
    public static ColonyPayloads.PanelView view() {
        return view;
    }

    static Component none() {
        return Component.empty();
    }
}
