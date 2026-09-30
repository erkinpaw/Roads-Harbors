package org.webtrade.minecraftportsmod.client.chart;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.webtrade.minecraftportsmod.vessel.HoldMenu;

/** The cargo hold: drawn in the same wood-and-brass style as the port office, sized to the vessel's hold. */
public class HoldScreen extends AbstractContainerScreen<HoldMenu> {

    public HoldScreen(HoldMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, HoldMenu.inventoryTop(HoldMenu.rows(menu.holdSize())) + 83);
        this.inventoryLabelY = HoldMenu.inventoryTop(HoldMenu.rows(menu.holdSize())) - 11;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        int x0 = leftPos, y0 = topPos, x1 = leftPos + imageWidth, y1 = topPos + imageHeight;
        g.fill(x0, y0, x1, y1, ChartStyle.WOOD_DARK);
        g.fill(x0 + 2, y0 + 2, x1 - 2, y1 - 2, ChartStyle.WOOD);
        g.outline(x0 + 3, y0 + 3, imageWidth - 6, imageHeight - 6, ChartStyle.BRASS_DARK);
        // parchment behind the hold slots, a darker plank floor behind the player's inventory
        int holdBottom = HoldMenu.inventoryTop(HoldMenu.rows(menu.holdSize())) - 14;
        g.fill(x0 + 5, y0 + 14, x1 - 5, holdBottom + 2, ChartStyle.PARCHMENT_SHADE);
        for (Slot slot : menu.slots) {
            int sx = leftPos + slot.x - 1, sy = topPos + slot.y - 1;
            boolean holdSlot = !(slot.container instanceof Inventory);
            g.fill(sx, sy, sx + 18, sy + 18, holdSlot ? ChartStyle.INK_SOFT : 0xFF2A1D12);
            g.fill(sx + 1, sy + 1, sx + 17, sy + 17, holdSlot ? ChartStyle.PARCHMENT_DARK : 0xFF5A4030);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(font, title, titleLabelX, titleLabelY, ChartStyle.TEXT_LIGHT, false);
        g.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, ChartStyle.TEXT_LIGHT, false);
    }
}
