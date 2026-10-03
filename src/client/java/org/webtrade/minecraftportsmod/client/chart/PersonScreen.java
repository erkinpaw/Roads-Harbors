package org.webtrade.minecraftportsmod.client.chart;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.colony.Job;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * Talking to a village resident: who they are, their trade, what they are doing, where they live. The head of the
 * village can tell about the village; one who works in a building takes orders for what it makes (Trade).
 */
public class PersonScreen extends UiScreen {

    private final ColonyPayloads.PersonView view;
    private int x0, y0, x1, y1;

    public PersonScreen(ColonyPayloads.PersonView view) {
        super(Component.literal(view.name()));
        this.view = view;
    }

    @Override
    protected void layout() {
        int w = Math.min(width - 24, 420), h = Math.min(height - 24, 180);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        // only what can be done with this person: no greyed-out buttons
        java.util.List<Component> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        if (view.elder()) {
            labels.add(Component.translatable("minecraftportsmod.person.about"));
            actions.add(() -> Minecraft.getInstance().gui.setScreen(new VillageScreen(this, view.village())));
        }
        if (view.quest() != 0) {
            Component label = Component.translatable("minecraftportsmod.person.tasks");
            if (view.quest() == 1) label = Component.literal("! ").withStyle(net.minecraft.ChatFormatting.YELLOW).append(label);
            labels.add(label);
            actions.add(() -> send(ColonyPayloads.VillageAction.QUEST, view.person()));
        }
        if (view.orders() >= 0) {
            labels.add(Component.translatable("minecraftportsmod.person.trade"));
            actions.add(() -> {
                Minecraft.getInstance().gui.setScreen(null);
                send(ColonyPayloads.VillageAction.ORDERS, view.orders());
            });
        }
        if (view.plotPrice() >= 0) {
            labels.add(Component.translatable("minecraftportsmod.person.plot", view.plotPrice()));
            actions.add(() -> {
                Minecraft.getInstance().gui.setScreen(null);
                send(ColonyPayloads.VillageAction.PLOT_BUY, view.person());
            });
        }
        int n = Math.max(1, labels.size());
        int bw = Math.min(160, (w - 16 - 5 * (n - 1)) / n), by = y1 - 26;
        int bx = x1 - 8 - n * bw - 5 * (n - 1);
        for (int i = 0; i < labels.size(); i++) {
            Runnable act = actions.get(i);
            addRenderableWidget(UiButton.make(labels.get(i), b -> act.run()).bounds(bx + i * (bw + 5), by, bw, 18).build());
        }
    }

    private void send(int kind, int a) {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), kind, a, 0));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.frame(g, x0, y0, x1, y1);
        Job job = view.job() < 0 ? null : Job.values()[view.job()];
        Component name = Component.literal((view.elder() ? "★ " : "") + view.name());
        g.text(font, name, x0 + 10, y0 + 7, view.elder() ? ChartStyle.BRASS : ChartStyle.TEXT_LIGHT, true);
        int px0 = x0 + 8, py0 = y0 + 20, px1 = x1 - 8, py1 = y1 - 32;
        Ui.blit(g, Ui.PARCHMENT, px0 - 1, py0 - 1, px1 + 1, py1 + 1);
        int x = px0 + 6, y = py0 + 6;
        g.item(new ItemStack(job == null ? Items.POPPY : job.tool()), x, y);
        Component role = job == null ? Component.translatable("minecraftportsmod.job.child") : job.displayName();
        if (view.elder()) role = Component.translatable("minecraftportsmod.person.elder_of", role);
        g.text(font, role, x + 22, y, ChartStyle.TEXT, false);
        Village.Level lvl = Village.Level.values()[view.level()];
        g.text(font, Component.translatable("minecraftportsmod.person.of", view.villageName(), lvl.displayName()), x + 22, y + 10,
                ChartStyle.TEXT_MUTED, false);
        y += 28;
        g.text(font, Component.translatable("minecraftportsmod.person.doing").append(view.activity()), x, y, ChartStyle.INK_SOFT, false);
        y += 12;
        g.text(font, Component.translatable("minecraftportsmod.person.home").append(view.home()), x, y, ChartStyle.TEXT, false);
        y += 12;
        g.text(font, Component.translatable("minecraftportsmod.person.days", view.days()), x, y, ChartStyle.TEXT_MUTED, false);
        widgets(g, mouseX, mouseY, partialTick);
    }
}
