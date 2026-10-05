package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * A player's empty plot, as its stone shows it: the houses the village will build on it, what each takes and what
 * it costs; one chosen, the village builds it and it is the player's.
 */
public class PlotScreen extends UiScreen {

    private static final int ROW = 34;

    private final ColonyPayloads.PlotView view;
    private int x0, y0, x1, y1;

    public PlotScreen(ColonyPayloads.PlotView view) {
        super(Component.translatable("minecraftportsmod.plot.screen", view.villageName()));
        this.view = view;
    }

    @Override
    protected void layout() {
        int w = Math.min(width - 24, 380), h = Math.min(height - 24, 36 + view.houses().size() * ROW + 10);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        int y = y0 + 30;
        for (ColonyPayloads.PlotHouse house : view.houses()) {
            // (only what can be paid for has a button)
            if (house.price() <= view.purse()) {
                addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.plot.build"), b -> {
                    onClose();
                    ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), ColonyPayloads.VillageAction.PLOT_HOUSE, house.kind(), 0));
                }).bounds(x1 - 78, y + 8, 66, 16).build());
            }
            y += ROW;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.frame(g, x0, y0, x1, y1);
        g.text(font, title, x0 + 10, y0 + 7, ChartStyle.TEXT_LIGHT, true);
        String purse = String.valueOf(view.purse());
        g.item(new ItemStack(Items.EMERALD), x1 - 26, y0 + 3);
        g.text(font, purse, x1 - 30 - font.width(purse), y0 + 7, ChartStyle.TEXT_LIGHT, true);
        Ui.blit(g, Ui.PARCHMENT, x0 + 7, y0 + 23, x1 - 7, y1 - 7);
        int y = y0 + 30;
        for (ColonyPayloads.PlotHouse house : view.houses()) {
            BuildingType t = BuildingType.values()[house.kind()];
            Ui.slot(g, x0 + 12, y + 3, 24, new ItemStack(t.icon));
            g.text(font, t.displayName(), x0 + 42, y + 4, ChartStyle.INK, false);
            g.text(font, Component.translatable("minecraftportsmod.plot.beds", house.beds()), x0 + 42 + font.width(t.displayName()) + 8, y + 4,
                    ChartStyle.TEXT_MUTED, false);
            // what it takes
            int x = x0 + 42;
            for (Res r : Res.values()) {
                int n = house.cost()[r.ordinal()];
                if (n <= 0) continue;
                if (x > x1 - 150) break;
                g.item(new ItemStack(r.icon), x, y + 14);
                g.text(font, String.valueOf(n), x + 17, y + 19, ChartStyle.TEXT, false);
                x += 22 + font.width(String.valueOf(n));
            }
            // its price
            String price = String.valueOf(house.price());
            int px = x1 - 86 - font.width(price);
            g.item(new ItemStack(Items.EMERALD), px - 17, y + 8);
            g.text(font, price, px, y + 12, house.price() <= view.purse() ? ChartStyle.INK : ChartStyle.BAD, false);
            g.fill(x0 + 12, y + ROW - 2, x1 - 12, y + ROW - 1, 0x30000000);
            y += ROW;
        }
        widgets(g, mouseX, mouseY, partialTick);
    }
}
