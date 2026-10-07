package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.colony.Quests;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * A person's tasks to choose from (all of what the village needs now), and those taken: for each, what is asked and
 * how many, how far along, what the player carries of it, the reward and the days left; take it, hand in, give it up.
 */
public class QuestScreen extends UiScreen {

    private static final int ROW = 40;

    private ColonyPayloads.QuestView view;
    private int x0, y0, x1, y1;

    public QuestScreen(ColonyPayloads.QuestView view) {
        super(Component.literal(view.name()));
        this.view = view;
    }

    public boolean shows(ColonyPayloads.QuestView v) {
        return v.village() == view.village() && v.person() == view.person();
    }

    public void update(ColonyPayloads.QuestView v) {
        view = v;
        rebuildWidgets();
    }

    public ColonyPayloads.QuestView view() {
        return view;
    }

    private void send(int kind, int quest) {
        ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), kind, quest, 0));
    }

    @Override
    protected void layout() {
        int rows = Math.max(1, view.rows().size());
        int w = Math.min(width - 24, 420), h = Math.min(height - 24, 36 + rows * ROW + 6);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        int bw = 76;
        int y = y0 + 24;
        for (ColonyPayloads.QuestRow r : view.rows()) {
            if (y + ROW > y1) break;
            Quests.Kind kind = Quests.Kind.values()[r.kind()];
            int by = y + ROW - 22, bx = x1 - 14 - bw;
            switch (r.state()) {
                case 0 -> {
                    UiButton b = addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.quest.take"),
                            btn -> send(ColonyPayloads.VillageAction.QUEST_TAKE, r.quest())).bounds(bx, by, bw, 16).build());
                    b.active = view.taken() < Quests.MAX_TAKEN;
                }
                case 1 -> {
                    if (kind != Quests.Kind.CARRY) addRenderableWidget(UiButton.make(Component.translatable(
                                    kind == Quests.Kind.HUNT || kind == Quests.Kind.LETTER ? "minecraftportsmod.quest.report" : "minecraftportsmod.quest.hand"),
                            btn -> send(ColonyPayloads.VillageAction.QUEST_HAND, r.quest())).bounds(bx, by, bw, 16).build());
                    addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.quest.drop"),
                            btn -> send(ColonyPayloads.VillageAction.QUEST_DROP, r.quest())).bounds(bx - bw - 4, by, bw, 16).build());
                }
                default -> {
                }
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
        Job job = view.job() < 0 ? null : Job.values()[view.job()];
        g.text(font, Component.literal((view.elder() ? "★ " : "") + view.name()), x0 + 10, y0 + 7, view.elder() ? ChartStyle.BRASS : ChartStyle.TEXT_LIGHT, false);
        String count = view.taken() + "/" + Quests.MAX_TAKEN;
        g.text(font, count, x1 - 10 - font.width(count), y0 + 7, view.taken() >= Quests.MAX_TAKEN ? ChartStyle.BAD : ChartStyle.TEXT_LIGHT, false);
        int px0 = x0 + 8, py0 = y0 + 20, px1 = x1 - 8, py1 = y1 - 6;
        Ui.blit(g, Ui.PARCHMENT, px0 - 1, py0 - 1, px1 + 1, py1 + 1);
        if (view.rows().isEmpty()) {
            g.item(new ItemStack(job == null ? Items.POPPY : job.tool()), px0 + 6, py0 + 6);
            g.text(font, "—", px0 + 28, py0 + 10, ChartStyle.TEXT_MUTED, false);
            widgets(g, mouseX, mouseY, partialTick);
            return;
        }
        int y = py0 + 4;
        for (ColonyPayloads.QuestRow r : view.rows()) {
            if (y + ROW > py1 + 4) break;
            row(g, r, px0 + 4, y, px1 - 4);
            y += ROW;
            if (y < py1) g.fill(px0 + 4, y - 2, px1 - 4, y - 1, ChartStyle.PARCHMENT_SHADE);
        }
        widgets(g, mouseX, mouseY, partialTick);
    }

    private void row(GuiGraphicsExtractor g, ColonyPayloads.QuestRow r, int x, int y, int right) {
        Quests.Kind kind = Quests.Kind.values()[r.kind()];
        Ui.slot(g, x, y + 2, 24, r.icon());
        // how many are asked for, on the slot as on a stack
        if (kind != Quests.Kind.LETTER) {
            String n = String.valueOf(r.count());
            g.text(font, n, x + 23 - font.width(n), y + 17, ChartStyle.TEXT_LIGHT, false);
        }
        int tx = x + 30;
        int color = r.state() == 2 ? ChartStyle.TEXT_MUTED : ChartStyle.TEXT;
        g.text(font, Ui.fit(font, r.what().getString(), right - tx - 120), tx, y + 2, color, false);
        // the reward, and the days left
        int rx = tx;
        g.item(new ItemStack(Items.EMERALD), rx, y + 12);
        String reward = "×" + r.reward();
        g.text(font, reward, rx + 17, y + 17, ChartStyle.TEXT, false);
        rx += 22 + font.width(reward) + 6;
        g.item(new ItemStack(Items.CLOCK), rx, y + 12);
        String days = String.valueOf(r.left());
        g.text(font, days, rx + 17, y + 17, r.left() <= 1 ? ChartStyle.BAD : ChartStyle.TEXT, false);
        rx += 22 + font.width(days) + 6;
        // taken: how far along, and what the player carries of it
        if (r.state() == 1) {
            float frac = r.count() == 0 ? 0 : Math.min(1F, r.done() / (float) r.count());
            Ui.bar(g, rx, y + 16, rx + 60, y + 24, frac, ChartStyle.GOOD);
            String prog = r.done() + "/" + r.count();
            g.text(font, prog, rx + 64, y + 17, ChartStyle.TEXT, false);
            rx += 68 + font.width(prog);
            if (kind != Quests.Kind.HUNT && kind != Quests.Kind.LETTER && kind != Quests.Kind.CARRY) {
                g.item(new ItemStack(Items.BUNDLE), rx, y + 12);
                g.text(font, String.valueOf(r.carried()), rx + 17, y + 17, r.carried() > 0 ? ChartStyle.GOOD : ChartStyle.TEXT_MUTED, false);
            }
        } else if (r.state() == 0 && kind != Quests.Kind.HUNT && kind != Quests.Kind.LETTER && kind != Quests.Kind.CARRY && r.carried() > 0) {
            g.item(new ItemStack(Items.BUNDLE), rx, y + 12);
            g.text(font, String.valueOf(r.carried()), rx + 17, y + 17, ChartStyle.GOOD, false);
        }
        if ((kind == Quests.Kind.LETTER || kind == Quests.Kind.CARRY) && !r.to().isEmpty()) {
            g.item(new ItemStack(Items.FILLED_MAP), rx, y + 12);
            g.text(font, r.to(), rx + 17, y + 17, ChartStyle.TEXT, false);
        }
        if (r.state() == 2) g.text(font, "✔", right - 12, y + 10, ChartStyle.TEXT_MUTED, false);
    }

    /** Back to the world (a task screen opened from a person: back to nothing, they are right there). */
    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(null);
    }
}
