package org.webtrade.minecraftportsmod.client.chart;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.webtrade.minecraftportsmod.Experimental;

/** The mod's experimental features, turned on and off (from the title screen). */
public class ExperimentsScreen extends Screen {

    private final Screen parent;

    public ExperimentsScreen(Screen parent) {
        super(Component.translatable("minecraftportsmod.exp.title"));
        this.parent = parent;
    }

    private static Component duels() {
        return Component.translatable("minecraftportsmod.exp.duels",
                Component.translatable(Experimental.duels() ? "minecraftportsmod.exp.on" : "minecraftportsmod.exp.off"));
    }

    @Override
    protected void init() {
        int x = width / 2 - 100, y = height / 2 - 20;
        addRenderableWidget(Button.builder(duels(), b -> {
            Experimental.setDuels(!Experimental.duels());
            b.setMessage(duels());
        }).bounds(x, y, 200, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x, y + 30, 200, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Ui.centered(g, font, title, width / 2, height / 2 - 50, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
