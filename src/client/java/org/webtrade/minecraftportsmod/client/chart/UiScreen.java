package org.webtrade.minecraftportsmod.client.chart;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A screen of the mod drawn at its own scale ({@link UiConfig}): laid out and drawn in its own, finer pixels
 * ({@link #width} and {@link #height} are in them, and so are the mouse's coordinates handed on), the whole of it
 * scaled down to the game's GUI. Subclasses lay out in {@link #layout()}, draw in {@link #draw} (calling
 * {@link #widgets} for the buttons) and take the mouse through the {@code ui*} methods.
 */
public abstract class UiScreen extends Screen {

    /** Own pixels per GUI pixel. */
    protected float k = 1;

    protected UiScreen(Component title) {
        super(title);
    }

    @Override
    protected final void init() {
        var win = minecraft.getWindow();
        int s = UiConfig.effective(win);
        k = win.getGuiScale() / (float) s;
        width = Math.round(win.getGuiScaledWidth() * k);
        height = Math.round(win.getGuiScaledHeight() * k);
        layout();
    }

    /** Lays the screen out (widgets and all) in its own pixels. */
    protected abstract void layout();

    /** Two small buttons, smaller and larger, ending at {@code right}. */
    protected void scaleButtons(int right, int y) {
        int now = UiConfig.effective(minecraft.getWindow());
        UiButton less = addRenderableWidget(UiButton.make(Component.literal("-"), b -> rescale(now - 1)).bounds(right - 30, y, 14, 14).build());
        UiButton more = addRenderableWidget(UiButton.make(Component.literal("+"), b -> rescale(now + 1)).bounds(right - 14, y, 14, 14).build());
        less.active = now > 1;
        more.active = now < UiConfig.maxScale(minecraft.getWindow());
        less.setTooltip(Tooltip.create(Component.translatable("minecraftportsmod.ui.finer", now)));
        more.setTooltip(Tooltip.create(Component.translatable("minecraftportsmod.ui.coarser", now)));
    }

    private void rescale(int s) {
        UiConfig.setScale(Math.max(1, s));
        rebuildWidgets();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xB0100A05);
    }

    @Override
    public final void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.pose().pushMatrix();
        g.pose().scale(1f / k, 1f / k);
        int mx = Math.round(mouseX * k), my = Math.round(mouseY * k);
        draw(g, mx, my, partialTick);
        // (the tooltips too, while the scale holds)
        g.extractDeferredElements(mx, my, partialTick);
        g.pose().popMatrix();
    }

    /** Draws the screen in its own pixels; the mouse is in them too. */
    protected abstract void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick);

    /** The buttons and other widgets. */
    protected final void widgets(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    // ------------------------------------------------------------------ the mouse, in own pixels

    private MouseButtonEvent own(MouseButtonEvent e) {
        return new MouseButtonEvent(e.x() * k, e.y() * k, e.buttonInfo());
    }

    @Override
    public final boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return uiClick(own(event), doubleClick);
    }

    @Override
    public final boolean mouseReleased(MouseButtonEvent event) {
        return uiRelease(own(event));
    }

    @Override
    public final boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return uiDrag(own(event), dx * k, dy * k);
    }

    @Override
    public final boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return uiScroll(mouseX * k, mouseY * k, scrollX, scrollY);
    }

    @Override
    public final void mouseMoved(double mouseX, double mouseY) {
        uiMove(mouseX * k, mouseY * k);
    }

    protected boolean uiClick(MouseButtonEvent event, boolean doubleClick) {
        return super.mouseClicked(event, doubleClick);
    }

    protected boolean uiRelease(MouseButtonEvent event) {
        return super.mouseReleased(event);
    }

    protected boolean uiDrag(MouseButtonEvent event, double dx, double dy) {
        return super.mouseDragged(event, dx, dy);
    }

    protected boolean uiScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    protected void uiMove(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
