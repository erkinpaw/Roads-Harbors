package org.webtrade.minecraftportsmod.client.chart;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/** A small wood-framed dialog to type a new name; returns to the chart afterwards. */
public class RenameScreen extends UiScreen {

    private final Screen parent;
    private final String initial;
    private final Consumer<String> onDone;
    private EditBox box;

    public RenameScreen(Screen parent, Component title, String initial, Consumer<String> onDone) {
        super(title);
        this.parent = parent;
        this.initial = initial;
        this.onDone = onDone;
    }

    @Override
    protected void layout() {
        int w = 200, x = (width - w) / 2, y = height / 2 - 20;
        box = new EditBox(font, x, y, w, 20, title);
        box.setMaxLength(32);
        box.setValue(initial);
        addRenderableWidget(box);
        setInitialFocus(box);
        addRenderableWidget(UiButton.make(CommonComponents.GUI_DONE, b -> done()).bounds(x, y + 26, w / 2 - 2, 20).build());
        addRenderableWidget(UiButton.make(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(x + w / 2 + 2, y + 26, w / 2 - 2, 20).build());
    }

    private void done() {
        String v = box.getValue().strip();
        if (!v.isEmpty() && !v.equals(initial)) onDone.accept(v);
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            done();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int w = 220, h = 84, x = (width - w) / 2, y = height / 2 - 44;
        Ui.frame(g, x, y, x + w, y + h);
        g.centeredText(font, title, width / 2, y + 9, ChartStyle.TEXT_LIGHT);
        widgets(g, mouseX, mouseY, partialTick);
    }
}
