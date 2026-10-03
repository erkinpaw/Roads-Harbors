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
 * A person's task: what is asked (an item, how many, how many brought so far and how many the player has on them),
 * what it pays, the days left; take it, hand in, give it up.
 */
public class QuestScreen extends UiScreen {

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

    private void send(int kind) {
        ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), kind, view.quest(), 0));
    }

    @Override
    protected void layout() {
        int w = Math.min(width - 24, 380), h = Math.min(height - 24, 128);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        int by = y1 - 26, bw = (w - 26) / 3;
        if (view.quest() < 0) {
            addRenderableWidget(UiButton.make(Component.translatable("gui.done"), b -> onClose()).bounds(x1 - 8 - bw, by, bw, 18).build());
            return;
        }
        Quests.Kind kind = Quests.Kind.values()[view.kind()];
        switch (view.state()) {
            case 0 -> {
                addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.quest.take"),
                        b -> send(ColonyPayloads.VillageAction.QUEST_TAKE)).bounds(x1 - 8 - bw, by, bw, 18).build());
            }
            case 1 -> {
                addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.quest.drop"),
                        b -> send(ColonyPayloads.VillageAction.QUEST_DROP)).bounds(x0 + 8, by, bw, 18).build());
                addRenderableWidget(UiButton.make(Component.translatable(
                                kind == Quests.Kind.HUNT || kind == Quests.Kind.LETTER ? "minecraftportsmod.quest.report" : "minecraftportsmod.quest.hand"),
                        b -> send(ColonyPayloads.VillageAction.QUEST_HAND)).bounds(x1 - 8 - bw, by, bw, 18).build());

            }
            default -> {
            }
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
        g.text(font, Component.literal((view.elder() ? "★ " : "") + view.name()), x0 + 10, y0 + 7, view.elder() ? ChartStyle.BRASS : ChartStyle.TEXT_LIGHT, true);
        String count = view.taken() + "/" + Quests.MAX_TAKEN;
        g.text(font, count, x1 - 10 - font.width(count), y0 + 7, ChartStyle.TEXT_LIGHT, true);
        int px0 = x0 + 8, py0 = y0 + 20, px1 = x1 - 8, py1 = y1 - 32;
        Ui.blit(g, Ui.PARCHMENT, px0 - 1, py0 - 1, px1 + 1, py1 + 1);
        if (view.quest() < 0) {
            g.item(new ItemStack(job == null ? Items.POPPY : job.tool()), px0 + 6, py0 + 6);
            g.text(font, "—", px0 + 28, py0 + 10, ChartStyle.TEXT_MUTED, false);
            widgets(g, mouseX, mouseY, partialTick);
            return;
        }
        Quests.Kind kind = Quests.Kind.values()[view.kind()];
        int x = px0 + 8, y = py0 + 8;
        Ui.slot(g, x, y, 28, view.icon());
        int tx = x + 36, tw = px1 - tx - 8;
        y += Ui.wrap(g, font, view.what(), tx, y + 2, tw, ChartStyle.TEXT) + 6;
        y = Math.max(y, py0 + 44);
        // progress: brought so far (or monsters killed) out of what is asked
        float frac = view.count() == 0 ? 0 : Math.min(1F, view.done() / (float) view.count());
        Ui.bar(g, x, y, px1 - 8, y + 10, frac, ChartStyle.GOOD);
        String prog = view.done() + " / " + view.count();
        g.text(font, prog, (x + px1 - 8 - font.width(prog)) / 2, y + 1, ChartStyle.TEXT, false);
        y += 16;
        if (kind != Quests.Kind.HUNT && kind != Quests.Kind.LETTER && view.state() == 1) {
            g.item(new ItemStack(Items.BUNDLE), x, y);
            g.text(font, String.valueOf(view.carried()), x + 20, y + 5, view.carried() > 0 ? ChartStyle.GOOD : ChartStyle.TEXT_MUTED, false);
        }
        if (kind == Quests.Kind.LETTER && !view.to().isEmpty()) {
            g.item(new ItemStack(Items.FILLED_MAP), x, y);
            g.text(font, view.to(), x + 20, y + 5, ChartStyle.TEXT, false);
        }
        // the reward, and the days left
        int rx = px1 - 8;
        String days = String.valueOf(view.left());
        rx -= font.width(days);
        g.text(font, days, rx, y + 5, view.left() <= 1 ? ChartStyle.BAD : ChartStyle.TEXT, false);
        rx -= 20;
        g.item(new ItemStack(Items.CLOCK), rx, y);
        String reward = "×" + view.reward();
        rx -= 14 + font.width(reward);
        g.text(font, reward, rx, y + 5, ChartStyle.TEXT, false);
        rx -= 18;
        g.item(new ItemStack(Items.EMERALD), rx, y);
        if (view.state() == 2) g.text(font, "✔", px0 + 8, py1 - 12, ChartStyle.TEXT_MUTED, false);
        widgets(g, mouseX, mouseY, partialTick);
    }

    /** Back to the world (a task screen opened from a person: back to nothing, they are right there). */
    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(null);
    }
}
