package org.webtrade.minecraftportsmod.client.chart;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** A button of the mod's screens: a wooden plank with a brass edge (or a tab), its label in light letters. */
public class UiButton extends Button.Plain {

    public enum Style {PLANK, TAB, TAB_OPEN}

    private Style style = Style.PLANK;

    protected UiButton(int x, int y, int w, int h, Component message, OnPress onPress) {
        super(x, y, w, h, message, onPress, DEFAULT_NARRATION);
    }

    public UiButton style(Style s) {
        this.style = s;
        return this;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        boolean hover = active && isHoveredOrFocused();
        Identifier sprite = switch (style) {
            case PLANK -> !active ? Ui.BUTTON_OFF : hover ? Ui.BUTTON_HOVER : Ui.BUTTON;
            case TAB -> hover ? Ui.TAB_HOVER : Ui.TAB;
            case TAB_OPEN -> Ui.TAB_OPEN;
        };
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, getX(), getY(), getWidth(), getHeight());
        Font font = Minecraft.getInstance().font;
        boolean open = style == Style.TAB_OPEN;
        int color = open ? ChartStyle.TEXT : !active && style == Style.PLANK ? 0xFFB0A590 : hover ? 0xFFFFF4D6 : ChartStyle.TEXT_LIGHT;
        String text = Ui.fit(font, getMessage().getString(), getWidth() - 6);
        int x = getX() + (getWidth() - font.width(text)) / 2, y = getY() + (getHeight() - 8) / 2 + (style == Style.PLANK ? 0 : 1);
        g.text(font, text, x, y, color, !open);
    }

    public static Maker make(Component message, OnPress onPress) {
        return new Maker(message, onPress);
    }

    /** As vanilla's builder: bounds, a tooltip, a style. */
    public static final class Maker {
        private final Component message;
        private final OnPress onPress;
        private int x, y, w = 150, h = 20;
        private Tooltip tooltip;
        private Style style = Style.PLANK;

        private Maker(Component message, OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Maker bounds(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            return this;
        }

        public Maker pos(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Maker size(int w, int h) {
            this.w = w;
            this.h = h;
            return this;
        }

        public Maker width(int w) {
            this.w = w;
            return this;
        }

        public Maker tooltip(Tooltip t) {
            this.tooltip = t;
            return this;
        }

        public Maker style(Style s) {
            this.style = s;
            return this;
        }

        public UiButton build() {
            UiButton b = new UiButton(x, y, w, h, message, onPress);
            b.style = style;
            if (tooltip != null) b.setTooltip(tooltip);
            return b;
        }
    }
}
