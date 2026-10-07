package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * The trades a player can take up in a village, the most wanted first: how badly it wants each (a bar), a day's
 * work at it; the one they are at, and how much of today's work is brought. A click on a trade takes it up.
 */
public class JobScreen extends UiScreen {

    private static final int ROW = 22;

    private ColonyPayloads.JobView view;
    private int x0, y0, x1, y1;

    public JobScreen(ColonyPayloads.JobView view) {
        super(Component.translatable("minecraftportsmod.jobs.title", view.villageName()));
        this.view = view;
    }

    public boolean shows(ColonyPayloads.JobView v) {
        return v.village() == view.village();
    }

    public void update(ColonyPayloads.JobView v) {
        view = v;
        rebuildWidgets();
    }

    public ColonyPayloads.JobView view() {
        return view;
    }

    @Override
    protected void layout() {
        int w = Math.min(width - 24, 340), h = Math.min(height - 24, 30 + view.rows().size() * ROW + (view.mine() >= 0 ? 30 : 8));
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        if (view.mine() >= 0) {
            addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.person.quit"), b -> hire(-1))
                    .bounds(x1 - 8 - 120, y1 - 26, 120, 18).build());
        }
    }

    /** Takes up a trade (-1: leaves the one one is at). */
    public void hire(int job) {
        ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), ColonyPayloads.VillageAction.HIRE, view.person(), job));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.frame(g, x0, y0, x1, y1);
        g.text(font, title, x0 + 10, y0 + 7, ChartStyle.TEXT_LIGHT, false);
        int px0 = x0 + 8, py0 = y0 + 20, px1 = x1 - 8, py1 = y1 - (view.mine() >= 0 ? 32 : 6);
        Ui.blit(g, Ui.PARCHMENT, px0 - 1, py0 - 1, px1 + 1, py1 + 1);
        int y = py0 + 3;
        for (ColonyPayloads.JobRow r : view.rows()) {
            if (y + ROW > py1 + 2) break;
            Job j = Job.values()[r.job()];
            boolean mine = r.job() == view.mine();
            boolean hot = mouseX >= px0 && mouseX < px1 && mouseY >= y && mouseY < y + ROW - 2;
            if (mine) g.fill(px0 + 2, y, px1 - 2, y + ROW - 2, 0x507FC04A);
            else if (hot) g.fill(px0 + 2, y, px1 - 2, y + ROW - 2, 0x30FFFFFF);
            g.item(new ItemStack(j.tool() == Items.AIR ? Items.WHEAT_SEEDS : j.tool()), px0 + 4, y + 2);
            g.text(font, j.displayName(), px0 + 24, y + 6, ChartStyle.TEXT, false);
            // how badly it is wanted
            int bx = px0 + 120, bw = px1 - bx - 70;
            float frac = Math.min(1F, r.demand() / 100F);
            Ui.bar(g, bx, y + 5, bx + bw, y + 13, frac, frac >= 0.5F ? ChartStyle.BAD : frac >= 0.2F ? 0xFFD9A030 : ChartStyle.GOOD);
            // a day's work (brought so far, at one's own)
            String q = mine ? Math.min(view.brought(), r.quota()) + "/" + r.quota() : String.valueOf(r.quota());
            g.item(new ItemStack(r.crafter() ? Items.CRAFTING_TABLE : Items.BUNDLE), px1 - 62, y + 2);
            g.text(font, q, px1 - 44, y + 6, mine && view.brought() >= r.quota() ? ChartStyle.GOOD : ChartStyle.TEXT, false);
            y += ROW;
        }
        widgets(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected boolean uiClick(MouseButtonEvent event, boolean doubleClick) {
        if (super.uiClick(event, doubleClick)) return true;
        int px0 = x0 + 8, px1 = x1 - 8, y = y0 + 23;
        for (ColonyPayloads.JobRow r : view.rows()) {
            if (event.x() >= px0 && event.x() < px1 && event.y() >= y && event.y() < y + ROW - 2) {
                if (r.job() != view.mine()) hire(r.job());
                return true;
            }
            y += ROW;
        }
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(null);
    }
}
