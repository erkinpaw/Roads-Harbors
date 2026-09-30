package org.webtrade.minecraftportsmod.client.chart;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.util.List;

/**
 * The look of the mod's screens, carved wood and brass round parchment: the sprites (nine-slice, in the GUI
 * atlas) and the pieces every screen is made of — the window, its page, inset panels, item slots, headings, bars.
 */
public final class Ui {

    static final Identifier FRAME = sprite("frame"), PARCHMENT = sprite("parchment"), INSET = sprite("inset"), SLOT = sprite("slot"),
            PLATE = sprite("plate"), BUTTON = sprite("button"), BUTTON_HOVER = sprite("button_hover"), BUTTON_OFF = sprite("button_off"),
            TAB = sprite("tab"), TAB_HOVER = sprite("tab_hover"), TAB_OPEN = sprite("tab_open"), BAR = sprite("bar"),
            GROOVE = sprite("groove"), KNOB = sprite("knob");

    /** The frame's border: the page starts this far in. */
    public static final int BORDER = 10;
    /** The title plate's height; the page starts below it. */
    public static final int TITLE = 26;

    private Ui() {
    }

    private static Identifier sprite(String name) {
        return Minecraftportsmod.id("ui/" + name);
    }

    static void blit(GuiGraphicsExtractor g, Identifier s, int x0, int y0, int x1, int y1) {
        if (x1 > x0 && y1 > y0) g.blitSprite(RenderPipelines.GUI_TEXTURED, s, x0, y0, x1 - x0, y1 - y0);
    }

    /**
     * A window: the carved frame, a brass plate with the title at the top, the parchment page. The page is
     * {@code (x0 + BORDER, y0 + TITLE, x1 - BORDER, y1 - BORDER)}.
     */
    static void window(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, Component title) {
        // a shadow under it
        g.fill(x0 + 4, y0 + 4, x1 + 4, y1 + 4, 0x60000000);
        blit(g, FRAME, x0, y0, x1, y1);
        blit(g, PARCHMENT, x0 + BORDER, y0 + TITLE, x1 - BORDER, y1 - BORDER);
        if (title != null) {
            int tw = Math.min(font.width(title) + 28, x1 - x0 - 60);
            int cx = (x0 + x1) / 2;
            blit(g, PLATE, cx - tw / 2, y0 + 4, cx + tw / 2, y0 + 22);
            String t = fit(font, title.getString(), tw - 20);
            g.text(font, t, cx - font.width(t) / 2, y0 + 9, 0xFF3A2610, false);
        }
    }

    /** The carved frame alone (with its shadow): a screen of its own design draws its page and title in it. */
    static void frame(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        g.fill(x0 + 4, y0 + 4, x1 + 4, y1 + 4, 0x60000000);
        blit(g, FRAME, x0, y0, x1, y1);
    }

    /** A panel set into the page (a darker leaf with a shadow along its top). */
    static void inset(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        blit(g, INSET, x0, y0, x1, y1);
    }

    /** A heading on the page: the words, a rule under them. */
    static void heading(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int w) {
        g.text(font, text, x, y, ChartStyle.INK, false);
        g.fill(x, y + 10, x + w, y + 11, ChartStyle.PARCHMENT_SHADE);
        g.fill(x, y + 11, x + Math.min(w, font.width(text) + 6), y + 12, ChartStyle.BRASS_DARK);
    }

    /** A thin rule across the page. */
    static void rule(GuiGraphicsExtractor g, int x0, int x1, int y) {
        g.fill(x0, y, x1, y + 1, ChartStyle.PARCHMENT_SHADE);
        g.fill(x0, y + 1, x1, y + 2, 0x40FFFFFF);
    }

    /** An item in a slot (a slot of {@code size}, the item in its middle). */
    static void slot(GuiGraphicsExtractor g, int x, int y, int size, ItemStack stack) {
        blit(g, SLOT, x, y, x + size, y + size);
        if (!stack.isEmpty()) g.item(stack, x + (size - 16) / 2, y + (size - 16) / 2);
    }

    /** A bar: its groove, and the part filled ({@code frac} of it) in a colour. */
    static void bar(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float frac, int color) {
        blit(g, BAR, x0, y0, x1, y1);
        int w = Math.round((x1 - x0 - 4) * Math.max(0, Math.min(1, frac)));
        if (w > 0) {
            g.fill(x0 + 2, y0 + 2, x0 + 2 + w, y1 - 2, color);
            g.fill(x0 + 2, y0 + 2, x0 + 2 + w, y0 + 3, 0x50FFFFFF);
        }
    }

    /** Text cut to a width, with "…" if it was cut. */
    static String fit(Font font, String s, int w) {
        if (font.width(s) <= w) return s;
        return font.plainSubstrByWidth(s, Math.max(0, w - font.width("…"))) + "…";
    }

    /** Wrapped text: the lines drawn, the height taken returned. */
    static int wrap(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int w, int color) {
        List<FormattedCharSequence> lines = font.split(text, w);
        for (FormattedCharSequence l : lines) {
            g.text(font, l, x, y, color, false);
            y += 10;
        }
        return lines.size() * 10;
    }

    /** A slider in the mod's look: a groove, a brass knob, the label over it. */
    abstract static class Slider extends AbstractSliderButton {
        Slider(int x, int y, int w, int h, Component message, double value) {
            super(x, y, w, h, message, value);
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            blit(g, GROOVE, getX(), getY() + 2, getX() + getWidth(), getY() + getHeight() - 2);
            int kx = getX() + (int) Math.round(value * (getWidth() - 8));
            if (active) blit(g, KNOB, kx, getY(), kx + 8, getY() + getHeight());
            Font font = net.minecraft.client.Minecraft.getInstance().font;
            String t = fit(font, getMessage().getString(), getWidth() - 6);
            g.text(font, t, getX() + (getWidth() - font.width(t)) / 2, getY() + (getHeight() - 8) / 2, active ? ChartStyle.TEXT_LIGHT : 0xFFB0A590, true);
        }
    }
}
