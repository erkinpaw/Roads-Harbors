package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * A building site's post: what goes up here, what it still needs (and what the village has in store), when it
 * will stand. Players can hand over materials from their inventory.
 */
public class SiteScreen extends UiScreen {

    private static final int REFRESH_TICKS = 30;

    private ColonyPayloads.SiteView view;
    private int x0, y0, x1, y1, ticks;
    private Button give;

    public SiteScreen(ColonyPayloads.SiteView view) {
        super(Component.translatable("minecraftportsmod.site.title"));
        this.view = view;
    }

    public void update(ColonyPayloads.SiteView v) {
        if (v.village() == view.village() && v.building() == view.building()) view = v;
    }

    public boolean shows(ColonyPayloads.SiteView v) {
        return v.village() == view.village() && v.building() == view.building();
    }

    @Override
    protected void layout() {
        int w = Math.min(width - 24, 480), h = Math.min(height - 24, 260);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        give = addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.site.give"),
                b -> ClientPlayNetworking.send(new ColonyPayloads.SiteAction(view.village(), view.building(), true)))
                .bounds(x0 + 10, y1 - 28, w - 20, 18).build());
    }

    @Override
    public void tick() {
        if (++ticks % REFRESH_TICKS == 0) ClientPlayNetworking.send(new ColonyPayloads.SiteAction(view.village(), view.building(), false));
        give.active = canGive();
    }

    private boolean canGive() {
        if (view.state() != Building.State.PLANNED.ordinal()) return false;
        for (Res r : Res.values()) {
            int missing = view.cost()[r.ordinal()] - view.delivered()[r.ordinal()];
            if (missing > 0 && view.carried()[r.ordinal()] > 0) return true;
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.frame(g, x0, y0, x1, y1);
        BuildingType t = BuildingType.values()[view.kind()];
        g.text(font, Component.translatable("minecraftportsmod.site.header", t.displayName(), view.villageName()), x0 + 10, y0 + 7,
                ChartStyle.TEXT_LIGHT, false);
        int px0 = x0 + 8, py0 = y0 + 20, px1 = x1 - 8, py1 = y1 - 34;
        Ui.blit(g, Ui.PARCHMENT, px0 - 1, py0 - 1, px1 + 1, py1 + 1);
        int x = px0 + 6, y = py0 + 6;
        g.item(new ItemStack(t.icon), x, y);
        Building.State s = Building.State.values()[view.state()];
        Component state = switch (s) {
            case PLANNED -> Component.translatable("minecraftportsmod.site.planned");
            case BUILDING -> Component.translatable("minecraftportsmod.bstate.building_pct", Math.round(view.progress() * 100));
            case BUILT -> Component.translatable("minecraftportsmod.bstate.built");
            case DEMOLISHING -> Component.translatable("minecraftportsmod.bstate.demolishing");
        };
        g.text(font, state, x + 22, y, ChartStyle.TEXT, false);
        Component eta = view.eta() < 0 ? Component.translatable("minecraftportsmod.vboard.eta_never")
                : view.eta() <= 1 ? Component.translatable("minecraftportsmod.vboard.eta_soon")
                : Component.translatable("minecraftportsmod.vboard.eta_days", view.eta());
        g.text(font, Component.translatable("minecraftportsmod.site.eta").append(eta), x + 22, y + 10, ChartStyle.TEXT_MUTED, false);
        y += 26;
        if (s == Building.State.BUILDING) {
            bar(g, x, y, px1 - px0 - 12, 6, view.progress());
            y += 12;
        }
        g.text(font, Component.translatable("minecraftportsmod.site.materials"), x, y, ChartStyle.TEXT_MUTED, false);
        y += 12;
        for (Res r : Res.values()) {
            int cost = view.cost()[r.ordinal()];
            if (cost <= 0) continue;
            int got = Math.min(cost, view.delivered()[r.ordinal()]);
            g.item(new ItemStack(r.icon), x, y - 4);
            g.text(font, r.displayName(), x + 20, y, ChartStyle.TEXT, false);
            int bx = x + 80, bw = 70;
            bar(g, bx, y + 1, bw, 6, (float) got / cost);
            g.text(font, got + " / " + cost, bx + bw + 6, y, got >= cost ? ChartStyle.GOOD : ChartStyle.TEXT, false);
            Component extra = Component.translatable("minecraftportsmod.site.have", view.stock()[r.ordinal()], view.carried()[r.ordinal()]);
            g.text(font, extra, bx + bw + 50, y, ChartStyle.TEXT_MUTED, false);
            y += 18;
        }
        widgets(g, mouseX, mouseY, partialTick);
    }

    private static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float v) {
        v = Math.max(0, Math.min(1, v));
        g.fill(x, y, x + w, y + h, ChartStyle.WOOD_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, ChartStyle.PARCHMENT_SHADE);
        g.fill(x + 1, y + 1, x + 1 + Math.round((w - 2) * v), y + h - 1, v >= 1 ? ChartStyle.GOOD : 0xFFC9A038);
    }
}
