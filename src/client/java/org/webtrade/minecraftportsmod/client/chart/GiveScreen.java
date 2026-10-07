package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

import java.util.ArrayList;
import java.util.List;

/**
 * Giving to a village what one carries, by choice: on the left what the player has of what the village keeps, on
 * the right where it may go (the store, or a building site that still lacks something, with what it lacks). A click
 * on a thing gives one of it where the right side points (Ctrl five, Shift a stack).
 */
public class GiveScreen extends UiScreen {

    private static final int SLOT = 22, COLS = 6;

    private ColonyPayloads.GiveView view;
    /** Where things go: -1 the store, else a building site's id. */
    private int to = -1;
    private int x0, y0, x1, y1, mid;

    public GiveScreen(ColonyPayloads.GiveView view) {
        super(Component.translatable("minecraftportsmod.give.title", view.villageName()));
        this.view = view;
        // (a site waiting for something the player has: that first; at the storehouse, the store)
        for (ColonyPayloads.GiveSite s : view.person() == ColonyPayloads.GiveView.AT_STORE ? java.util.List.<ColonyPayloads.GiveSite>of() : view.sites()) {
            if (useful(s)) {
                to = s.id();
                break;
            }
        }
    }

    public boolean shows(ColonyPayloads.GiveView v) {
        return v.village() == view.village();
    }

    public void update(ColonyPayloads.GiveView v) {
        view = v;
        if (to >= 0 && site(to) == null) to = -1;
    }

    private boolean useful(ColonyPayloads.GiveSite s) {
        for (Res r : Res.values()) if (s.missing()[r.ordinal()] > 0 && view.carried()[r.ordinal()] > 0) return true;
        return false;
    }

    private ColonyPayloads.GiveSite site(int id) {
        for (ColonyPayloads.GiveSite s : view.sites()) if (s.id() == id) return s;
        return null;
    }

    /** What the place chosen takes of a resource now (the store's room, a site's want). */
    private int takes(Res r) {
        ColonyPayloads.GiveSite s = site(to);
        return s == null ? view.room()[r.ordinal()] : s.missing()[r.ordinal()];
    }

    private List<Res> carried() {
        List<Res> out = new ArrayList<>();
        for (Res r : Res.values()) if (view.carried()[r.ordinal()] > 0) out.add(r);
        return out;
    }

    @Override
    protected void layout() {
        int w = Math.min(width - 24, 460), h = Math.min(height - 24, 250);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        mid = x0 + 8 + COLS * SLOT + 18;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        places.clear();
        Ui.frame(g, x0, y0, x1, y1);
        g.text(font, title, x0 + 10, y0 + 7, ChartStyle.TEXT_LIGHT, false);
        int px0 = x0 + 8, py0 = y0 + 20, px1 = x1 - 8, py1 = y1 - 8;
        Ui.blit(g, Ui.PARCHMENT, px0 - 1, py0 - 1, px1 + 1, py1 + 1);
        List<Component> tip = null;

        // the left: what the player has (a green ring round what the place chosen takes)
        g.text(font, Component.translatable("minecraftportsmod.give.carried"), px0 + 6, py0 + 5, ChartStyle.TEXT, false);
        List<Res> mine = carried();
        for (int i = 0; i < mine.size(); i++) {
            Res r = mine.get(i);
            int x = px0 + 6 + (i % COLS) * SLOT, y = py0 + 17 + (i / COLS) * SLOT;
            Ui.slot(g, x, y, SLOT - 2, new ItemStack(r.icon));
            String n = String.valueOf(view.carried()[r.ordinal()]);
            g.text(font, n, x + SLOT - 3 - font.width(n), y + SLOT - 11, ChartStyle.TEXT_LIGHT, false);
            int k = takes(r);
            if (k > 0) g.outline(x - 1, y - 1, SLOT, SLOT, ChartStyle.GOOD);
            else g.fill(x, y, x + SLOT - 2, y + SLOT - 2, 0x60F3EAD5);
            if (mouseX >= x && mouseX < x + SLOT - 2 && mouseY >= y && mouseY < y + SLOT - 2) {
                tip = new ArrayList<>();
                tip.add(r.displayName());
                tip.add(Component.literal(view.carried()[r.ordinal()] + " → " + k).withStyle(k > 0 ? net.minecraft.ChatFormatting.GREEN
                        : net.minecraft.ChatFormatting.GRAY));
            }
        }

        // the right: where it goes
        g.fill(mid - 9, py0 + 4, mid - 8, py1 - 4, ChartStyle.PARCHMENT_SHADE);
        int y = py0 + 5;
        y = place(g, -1, new ItemStack(Items.BARREL), Component.translatable("minecraftportsmod.give.store"), y, px1, mouseX, mouseY);
        for (ColonyPayloads.GiveSite s : view.sites()) {
            if (y > py1 - 40) break;
            BuildingType t = BuildingType.values()[s.type()];
            Component name = s.level() > 1 ? Component.empty().append(t.displayName()).append(" · " + s.level()) : t.displayName();
            y = place(g, s.id(), new ItemStack(t.icon), name, y, px1, mouseX, mouseY);
        }

        // what the place chosen still lacks (a site's), under the list
        ColonyPayloads.GiveSite chosen = site(to);
        if (chosen != null) {
            int lx = mid, ly = py1 - 30;
            g.fill(mid, ly - 4, px1 - 4, ly - 3, ChartStyle.PARCHMENT_SHADE);
            for (Res r : Res.values()) {
                int k = chosen.missing()[r.ordinal()];
                if (k <= 0) continue;
                if (lx + 40 > px1) break;
                Ui.slot(g, lx, ly, 18, new ItemStack(r.icon));
                String n = String.valueOf(k);
                g.text(font, n, lx + 20, ly + 5, view.carried()[r.ordinal()] > 0 ? ChartStyle.GOOD : ChartStyle.INK_SOFT, false);
                if (mouseX >= lx && mouseX < lx + 18 && mouseY >= ly && mouseY < ly + 18) tip = List.of(r.displayName());
                lx += 24 + font.width(n);
            }
        }
        widgets(g, mouseX, mouseY, partialTick);
        if (tip != null) g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
    }

    /** A place in the right's list: chosen, or to be clicked. */
    private int place(GuiGraphicsExtractor g, int id, ItemStack icon, Component name, int y, int right, int mouseX, int mouseY) {
        boolean on = id == to;
        boolean hot = mouseX >= mid && mouseX < right - 4 && mouseY >= y && mouseY < y + 18;
        if (on) g.fill(mid, y, right - 4, y + 18, 0x507FC04A);
        else if (hot) g.fill(mid, y, right - 4, y + 18, 0x30FFFFFF);
        g.item(icon, mid + 2, y + 1);
        g.text(font, Ui.fit(font, name.getString(), right - mid - 30), mid + 22, y + 5, on ? ChartStyle.INK : ChartStyle.TEXT, false);
        places.add(new int[]{id, y});
        return y + 20;
    }

    private final List<int[]> places = new ArrayList<>();

    // ------------------------------------------------------------------ input

    @Override
    protected boolean uiClick(MouseButtonEvent event, boolean doubleClick) {
        if (super.uiClick(event, doubleClick)) return true;
        double mx = event.x(), my = event.y();
        // a place on the right: chosen
        if (mx >= mid && mx < x1 - 12) {
            for (int[] p : places) {
                if (my >= p[1] && my < p[1] + 18) {
                    to = p[0];
                    return true;
                }
            }
        }
        // a thing on the left: given there
        int n = event.hasShiftDown() ? 64 : event.hasControlDown() ? 5 : 1;
        List<Res> mine = carried();
        int px0 = x0 + 8, py0 = y0 + 20;
        for (int i = 0; i < mine.size(); i++) {
            int x = px0 + 6 + (i % COLS) * SLOT, y = py0 + 17 + (i / COLS) * SLOT;
            if (mx >= x && mx < x + SLOT - 2 && my >= y && my < y + SLOT - 2) {
                Res r = mine.get(i);
                ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), ColonyPayloads.VillageAction.GIVE, to,
                        r.ordinal() * 100000 + Math.min(99999, n)));
                return true;
            }
        }
        return false;
    }

    /** Tests: give {@code n} of a resource to the place {@code id} (-1 the store). */
    public void give(int id, Res r, int n) {
        to = id;
        ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), ColonyPayloads.VillageAction.GIVE, to, r.ordinal() * 100000 + n));
    }

    public ColonyPayloads.GiveView view() {
        return view;
    }
}
