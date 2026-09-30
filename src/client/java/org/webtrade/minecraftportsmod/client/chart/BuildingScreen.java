package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Crop;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

import java.util.Locale;

/**
 * A building's own menu: what it is and how far it has grown; who lives or works here and what it gives (tools,
 * room in the store...); what it makes and uses up in a day; its next level, what that takes (and how much of it
 * the village has), with a button to have it raised first; keeping it from ever being pulled down, or pulling it
 * down; and for a field, what to sow.
 */
public class BuildingScreen extends UiScreen {

    private final Screen parent;
    private ColonyPayloads.BuildingView view;
    private int x0, y0, x1, y1;
    /** The page's columns: left (the building), right (the next level); the band of flows under them. */
    private int px0, py0, px1, py1, mid, flowsY;
    /** The demolish button asks once more before it does anything. */
    private boolean confirmDemolish;

    public BuildingScreen(Screen parent, ColonyPayloads.BuildingView view) {
        super(BuildingType.values()[view.kind()].displayName());
        this.parent = parent;
        this.view = view;
    }

    public boolean shows(ColonyPayloads.BuildingView v) {
        return v.village() == view.village() && v.building() == view.building();
    }

    public void update(ColonyPayloads.BuildingView v) {
        if (!shows(v)) return;
        this.view = v;
        rebuildWidgets();
    }

    private void act(int kind) {
        ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), kind, view.building(), 0));
    }

    private BuildingType type() {
        return BuildingType.values()[view.kind()];
    }

    @Override
    protected void layout() {
        int w = Math.min(width - 24, 600), h = Math.min(height - 24, 420);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        px0 = x0 + Ui.BORDER + 8;
        py0 = y0 + Ui.TITLE + 8;
        px1 = x1 - Ui.BORDER - 8;
        py1 = y1 - Ui.BORDER - 34;
        mid = px0 + (px1 - px0) * 55 / 100;
        flowsY = py1 - 62;
        BuildingType t = type();
        scaleButtons(x1 - 8, y0 + 6);

        // the next level: raise it first
        if (view.raiseCost().length > 0) {
            Component label = Component.translatable(view.raiseFirst() ? "minecraftportsmod.bmenu.raise_cancel" : "minecraftportsmod.bmenu.raise_first");
            addRenderableWidget(UiButton.make(label, b -> act(ColonyPayloads.VillageAction.RAISE))
                    .bounds(mid + 10, flowsY - 30, px1 - mid - 16, 20).build());
        }
        // a field: what to sow
        if (view.crop() >= 0) {
            int bx = mid + 10, by = flowsY - 30 - (view.raiseCost().length > 0 ? 50 : 26);
            int bw = Math.max(50, (px1 - mid - 16 - 4 * (view.crops().length - 1)) / Math.max(1, view.crops().length));
            for (int c : view.crops()) {
                Crop crop = Crop.values()[c];
                UiButton b = addRenderableWidget(UiButton.make(crop.displayName(),
                        btn -> ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), ColonyPayloads.VillageAction.CROP,
                                view.building(), c))).bounds(bx, by, bw, 18)
                        .style(c == view.crop() ? UiButton.Style.TAB_OPEN : UiButton.Style.PLANK).build());
                b.active = c != view.crop();
                bx += bw + 4;
            }
        }
        // the foot of the window: back, keep, pull down
        int by = y1 - Ui.BORDER - 26;
        addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.bmenu.back"), b -> onClose())
                .bounds(px0, by, 90, 20).build());
        if (!t.isCenter() && t != BuildingType.TENT) {
            addRenderableWidget(UiButton.make(Component.translatable(view.keep() ? "minecraftportsmod.bmenu.keep_on" : "minecraftportsmod.bmenu.keep_off"),
                            b -> act(ColonyPayloads.VillageAction.KEEP)).bounds(px0 + 96, by, 140, 20).build());
            UiButton down = addRenderableWidget(UiButton.make(Component.translatable(confirmDemolish ? "minecraftportsmod.bmenu.demolish_sure"
                    : "minecraftportsmod.bmenu.demolish"), b -> {
                if (!confirmDemolish) {
                    confirmDemolish = true;
                    rebuildWidgets();
                } else {
                    act(ColonyPayloads.VillageAction.DEMOLISH);
                    onClose();
                }
            }).bounds(px1 - 170, by, 170, 20).build());
            down.active = !view.keep();
            if (view.keep()) down.setTooltip(Tooltip.create(Component.translatable("minecraftportsmod.bmenu.demolish_kept")));
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    /** Pips: how far a building has grown (gold: built, green: being raised to, dim: still to come). */
    static void pips(GuiGraphicsExtractor g, int x, int y, int level, int max, int goal) {
        pips(g, x, y, level, max, goal, 5);
    }

    static void pips(GuiGraphicsExtractor g, int x, int y, int level, int max, int goal, int size) {
        for (int i = 0; i < max; i++) {
            int c = i < level ? 0xFFE6B43A : i < goal ? 0xFF7FC04A : 0xFF6D6558;
            int px = x + i * (size + 2);
            g.fill(px, y, px + size, y + size, 0xFF2B2118);
            g.fill(px + 1, y + 1, px + size - 1, y + size - 1, c);
            if (i < level) g.fill(px + 1, y + 1, px + size - 1, y + 2, 0x80FFFFFF);
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        BuildingType t = type();
        Ui.window(g, font, x0, y0, x1, y1, Component.translatable("minecraftportsmod.bmenu.title", t.displayName(), view.villageName()));

        // ---- the building
        int x = px0, y = py0;
        Ui.slot(g, x, y, 36, new ItemStack(t.icon));
        g.text(font, t.displayName(), x + 44, y + 2, ChartStyle.INK, false);
        g.text(font, Component.translatable("minecraftportsmod.bmenu.branch_only", t.branch.displayName()), x + 44, y + 13, ChartStyle.TEXT_MUTED, false);
        if (t.maxLevel > 1) {
            Component lv = Component.translatable("minecraftportsmod.bmenu.level", view.level(), t.maxLevel);
            g.text(font, lv, x + 44, y + 24, ChartStyle.TEXT, false);
            pips(g, x + 50 + font.width(lv), y + 24, view.level(), t.maxLevel, view.goal(), 7);
        }
        y += 46;
        int colW = mid - px0 - 12;
        Ui.heading(g, font, Component.translatable("minecraftportsmod.bmenu.about"), x, y, colW);
        y += 16;
        for (Component n : view.notes()) y += Ui.wrap(g, font, n, x + 2, y, colW - 4, ChartStyle.INK_SOFT) + 2;
        if (!view.people().isEmpty()) {
            y += 4;
            Ui.heading(g, font, Component.translatable("minecraftportsmod.bmenu.people", view.people().size()), x, y, colW);
            y += 16;
            int cx = x;
            for (String p : view.people()) {
                int w = font.width(p) + 22;
                if (cx + w > x + colW) {
                    cx = x;
                    y += 18;
                }
                if (y > flowsY - 20) break;
                g.item(new ItemStack(Items.PLAYER_HEAD), cx, y - 4);
                g.text(font, p, cx + 18, y, ChartStyle.TEXT, false);
                cx += w + 6;
            }
            y += 18;
        }
        if (view.keep()) g.text(font, Component.translatable("minecraftportsmod.bmenu.kept"), x, flowsY - 14, ChartStyle.GOOD, false);

        // ---- the next level
        g.fill(mid, py0, mid + 1, flowsY - 6, ChartStyle.PARCHMENT_SHADE);
        int nx = mid + 10, nw = px1 - nx;
        int ny = py0;
        if (t.maxLevel <= 1) {
            Ui.heading(g, font, Component.translatable("minecraftportsmod.bmenu.levels"), nx, ny, nw);
            Ui.wrap(g, font, Component.translatable("minecraftportsmod.bmenu.no_levels"), nx, ny + 18, nw, ChartStyle.TEXT_MUTED);
        } else if (view.goal() > view.level()) {
            Ui.heading(g, font, Component.translatable("minecraftportsmod.bmenu.growing_to", view.goal()), nx, ny, nw);
            ny += 20;
            Ui.bar(g, nx, ny, px1, ny + 10, view.progress(), ChartStyle.GOOD);
            g.text(font, Math.round(view.progress() * 100) + "%", nx, ny + 14, ChartStyle.TEXT, false);
        } else if (view.raiseCost().length == 0) {
            Ui.heading(g, font, Component.translatable("minecraftportsmod.bmenu.levels"), nx, ny, nw);
            Ui.wrap(g, font, Component.translatable("minecraftportsmod.bmenu.top_level"), nx, ny + 18, nw, ChartStyle.TEXT_MUTED);
        } else {
            Ui.heading(g, font, Component.translatable("minecraftportsmod.bmenu.next_level", view.level() + 1), nx, ny, nw);
            ny += 18;
            g.text(font, Component.translatable("minecraftportsmod.bmenu.it_takes"), nx, ny, ChartStyle.TEXT_MUTED, false);
            ny += 12;
            // what it takes: a slot each, what the village has against what is needed
            int cx = nx;
            for (Res r : Res.values()) {
                int c = view.raiseCost()[r.ordinal()];
                if (c <= 0) continue;
                int have = r.ordinal() < view.stock().length ? view.stock()[r.ordinal()] : 0;
                if (cx + 60 > px1) {
                    cx = nx;
                    ny += 26;
                }
                Ui.slot(g, cx, ny, 22, new ItemStack(r.icon));
                g.text(font, String.valueOf(c), cx + 25, ny + 3, ChartStyle.TEXT, false);
                g.text(font, have + "", cx + 25, ny + 13, have >= c ? ChartStyle.GOOD : ChartStyle.BAD, false);
                if (mouseX >= cx && mouseX < cx + 22 && mouseY >= ny && mouseY < ny + 22) {
                    g.setTooltipForNextFrame(font, Component.translatable("minecraftportsmod.bmenu.cost_tip", r.displayName(), c, have), mouseX, mouseY);
                }
                cx += 60;
            }
            ny += 30;
            if (view.raiseFirst()) g.text(font, Component.translatable("minecraftportsmod.bmenu.raise_pinned"), nx, ny, ChartStyle.GOOD, false);
            else Ui.wrap(g, font, Component.translatable("minecraftportsmod.bmenu.raise_self"), nx, ny, nw, ChartStyle.TEXT_MUTED);
        }
        if (view.crop() >= 0) {
            int by = flowsY - 30 - (view.raiseCost().length > 0 ? 50 : 26);
            g.text(font, Component.translatable("minecraftportsmod.bmenu.sow"), mid + 10, by - 12, ChartStyle.TEXT_MUTED, false);
        }

        // ---- a day's making and using
        Ui.inset(g, px0 - 2, flowsY - 4, px1 + 2, py1 + 2);
        flowRow(g, Component.translatable("minecraftportsmod.bmenu.makes"), view.makes(), px0 + 6, flowsY + 4, px1 - 4, mouseX, mouseY);
        flowRow(g, Component.translatable("minecraftportsmod.bmenu.uses"), view.uses(), px0 + 6, flowsY + 32, px1 - 4, mouseX, mouseY);

        widgets(g, mouseX, mouseY, partialTick);
    }

    /** A label, then a slot and an amount a day for every resource with any. */
    private void flowRow(GuiGraphicsExtractor g, Component label, int[] tenths, int x, int y, int right, int mouseX, int mouseY) {
        g.text(font, label, x, y + 7, ChartStyle.TEXT_MUTED, false);
        int cx = x + Math.max(font.width(Component.translatable("minecraftportsmod.bmenu.makes")),
                font.width(Component.translatable("minecraftportsmod.bmenu.uses"))) + 10;
        boolean any = false;
        for (Res r : Res.values()) {
            int t = r.ordinal() < tenths.length ? tenths[r.ordinal()] : 0;
            if (t <= 0) continue;
            String amount = t >= 100 || t % 10 == 0 ? String.valueOf(Math.round(t / 10f)) : String.format(Locale.ROOT, "%.1f", t / 10f);
            int w = 24 + font.width(amount) + 10;
            if (cx + w > right) {
                g.text(font, "…", cx, y + 7, ChartStyle.TEXT, false);
                break;
            }
            Ui.slot(g, cx, y, 22, new ItemStack(r.icon));
            g.text(font, amount, cx + 25, y + 7, ChartStyle.TEXT, false);
            if (mouseX >= cx && mouseX < cx + 22 && mouseY >= y && mouseY < y + 22) {
                g.setTooltipForNextFrame(font, Component.translatable("minecraftportsmod.bmenu.flow_tip", r.displayName(), amount), mouseX, mouseY);
            }
            cx += w;
            any = true;
        }
        if (!any) g.text(font, Component.translatable("minecraftportsmod.bmenu.nothing"), cx, y + 7, ChartStyle.TEXT_MUTED, false);
    }
}
