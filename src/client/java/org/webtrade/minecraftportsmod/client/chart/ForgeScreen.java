package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

import java.util.Random;

/**
 * Forging at the smithy's anvil: a slider runs to and fro along a bar with a mark on it, the mark's colour the
 * button to strike with (orange the left, blue the right). A stroke with the right button while the slider is on
 * the mark is in time; the wrong button, off the mark, or none in two runs of the slider, a miss. Each stroke the
 * slider runs faster and the mark is narrower. The strokes in time make the tools.
 */
public class ForgeScreen extends UiScreen {

    static final int LEFT = 0xFFE08A2A, RIGHT = 0xFF3A7BD5, HIT = 0xFF5FB04A, MISS = 0xFFC0453A, PENDING = 0xFF8A7A66;
    private static final int BAR_W = 320, BAR_H = 18;

    private final ColonyPayloads.ForgeView view;
    private final float[] mark, span;
    private final boolean[] right;
    /** Each stroke's outcome: 0 to come, 1 in time, 2 a miss. */
    private final int[] outcome;
    private int stroke, hits;
    /** Where the slider is (0..1), its way (+1, -1), its turns this stroke; when it moved last. */
    private double at;
    private int dir = 1, turns;
    private long last = -1;
    /** A pause after a stroke (the flash of it), and the end shown before the window closes (nanos, 0: none). */
    private long pauseUntil, closeAt;
    private int x0, y0, x1, y1, bx, by;

    public ForgeScreen(ColonyPayloads.ForgeView view) {
        super(Component.empty().append(view.icon().getHoverName()));
        this.view = view;
        int n = view.strokes();
        Random rnd = new Random(view.seed());
        mark = new float[n];
        span = new float[n];
        right = new boolean[n];
        outcome = new int[n];
        for (int k = 0; k < n; k++) {
            span[k] = Math.max(0.07F, 0.22F - 0.02F * k);
            mark[k] = span[k] / 2 + 0.05F + rnd.nextFloat() * (0.9F - span[k]);
            right[k] = rnd.nextBoolean();
        }
    }

    /** How fast the slider runs at a stroke: bars a second. */
    private double speed(int k) {
        return 0.7 + 0.14 * k;
    }

    /** Tools these strokes in time give (as the smithy reckons it). */
    private int pieces() {
        int n = view.strokes();
        int p = hits == 0 ? 0 : hits == n ? 4 : Math.max(1, Math.round(3 * hits / (float) n));
        return Math.min(p, view.most());
    }

    @Override
    protected void layout() {
        int w = Math.min(width - 24, BAR_W + 60), h = 132;
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        bx = (width - BAR_W) / 2;
        by = y0 + 58;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void move() {
        long now = System.nanoTime();
        if (last < 0) last = now;
        double dt = (now - last) / 1e9;
        last = now;
        if (stroke >= view.strokes() || now < pauseUntil) return;
        at += dir * speed(stroke) * dt;
        if (at > 1) {
            at = 2 - at;
            dir = -1;
            turns++;
        } else if (at < 0) {
            at = -at;
            dir = 1;
            turns++;
        }
        // (two runs to and fro and no stroke: a miss)
        if (turns >= 4) strike(-1);
    }

    /** A stroke with a button (0 left, 1 right; -1 none in time). */
    private void strike(int button) {
        if (stroke >= view.strokes()) return;
        float m = mark[stroke], w = span[stroke];
        boolean on = Math.abs(at - m) <= w / 2;
        boolean ok = button >= 0 && on && (button == 1) == right[stroke];
        outcome[stroke] = ok ? 1 : 2;
        if (ok) hits++;
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(ok ? SoundEvents.ANVIL_USE : SoundEvents.ANVIL_LAND, 0.5F, ok ? 1.0F + 0.05F * stroke : 0.6F);
        }
        stroke++;
        turns = 0;
        pauseUntil = System.nanoTime() + 350_000_000L;
        if (stroke >= view.strokes()) finish();
    }

    private boolean sent;

    private void finish() {
        if (sent) return;
        sent = true;
        ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(), ColonyPayloads.VillageAction.FORGE, view.building(), hits));
        closeAt = System.nanoTime() + 1_400_000_000L;
    }

    @Override
    public void tick() {
        if (closeAt > 0 && System.nanoTime() > closeAt) Minecraft.getInstance().gui.setScreen(null);
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        move();
        Ui.frame(g, x0, y0, x1, y1);
        g.item(view.icon(), x0 + 10, y0 + 5);
        g.text(font, title, x0 + 30, y0 + 9, ChartStyle.TEXT_LIGHT, false);
        int px0 = x0 + 8, py0 = y0 + 24, px1 = x1 - 8, py1 = y1 - 8;
        Ui.blit(g, Ui.PARCHMENT, px0 - 1, py0 - 1, px1 + 1, py1 + 1);
        // the strokes: in time, missed, to come
        int n = view.strokes(), pw = 14, gap = 4, sx = (width - (n * pw + (n - 1) * gap)) / 2;
        for (int k = 0; k < n; k++) {
            int c = outcome[k] == 1 ? HIT : outcome[k] == 2 ? MISS : k == stroke ? 0xFFD9C27A : PENDING;
            g.fill(sx + k * (pw + gap), py0 + 8, sx + k * (pw + gap) + pw, py0 + 16, c);
            g.outline(sx + k * (pw + gap), py0 + 8, pw, 8, 0x80000000);
        }
        // the bar, its mark (the button's colour), the slider
        g.fill(bx - 2, by - 2, bx + BAR_W + 2, by + BAR_H + 2, 0xFF3B2A1C);
        g.fill(bx, by, bx + BAR_W, by + BAR_H, 0xFF6B5A48);
        if (stroke < n) {
            int c = right[stroke] ? RIGHT : LEFT;
            int mx0 = bx + Math.round((mark[stroke] - span[stroke] / 2) * BAR_W), mx1 = bx + Math.round((mark[stroke] + span[stroke] / 2) * BAR_W);
            g.fill(mx0, by, mx1, by + BAR_H, c);
            g.fill(mx0, by, mx1, by + 2, 0x60FFFFFF);
            mouse(g, (mx0 + mx1) / 2 - 10, by + BAR_H + 6, right[stroke], c);
            int sxp = bx + (int) Math.round(at * BAR_W);
            g.fill(sxp - 2, by - 5, sxp + 2, by + BAR_H + 5, 0xFFF3EAD5);
            g.outline(sxp - 2, by - 5, 4, BAR_H + 10, 0xFF1C1408);
        } else {
            // the end: the tools forged
            int k = pieces();
            int cx = width / 2;
            g.item(k > 0 ? view.icon() : new ItemStack(Items.IRON_NUGGET), cx - 24, by);
            g.text(font, "× " + k, cx - 4, by + 5, k > 0 ? ChartStyle.GOOD : ChartStyle.BAD, false);
        }
        widgets(g, mouseX, mouseY, partialTick);
    }

    /** A small mouse, the button to strike with lit in its colour. */
    private static void mouse(GuiGraphicsExtractor g, int x, int y, boolean right, int c) {
        g.fill(x, y, x + 20, y + 28, 0xFF1C1408);
        g.fill(x + 2, y + 2, x + 18, y + 26, 0xFFE8E0D0);
        g.fill(x + 9, y + 2, x + 11, y + 12, 0xFF1C1408);
        g.fill(x + 2, y + 12, x + 18, y + 14, 0xFF1C1408);
        if (right) g.fill(x + 11, y + 2, x + 18, y + 12, c);
        else g.fill(x + 2, y + 2, x + 9, y + 12, c);
    }

    @Override
    protected boolean uiClick(MouseButtonEvent event, boolean doubleClick) {
        if (stroke >= view.strokes() || System.nanoTime() < pauseUntil) return true;
        if (event.button() == 0 || event.button() == 1) strike(event.button());
        return true;
    }

    /** Left before the end: what was struck counts, the rest are misses. */
    @Override
    public void onClose() {
        if (!sent) finish();
        Minecraft.getInstance().gui.setScreen(null);
    }

    /** Tests: strike now with a button (0 left, 1 right), the slider put on the mark first if {@code onMark}. */
    public void testStrike(boolean onMark, boolean rightButton) {
        if (stroke >= view.strokes()) return;
        if (onMark) at = mark[stroke];
        pauseUntil = 0;
        strike(rightButton ? 1 : 0);
        pauseUntil = 0;
    }

    /** Tests: the button the stroke now wants (true: the right). */
    public boolean wantsRight() {
        return stroke < view.strokes() && right[stroke];
    }

    public int hits() {
        return hits;
    }
}
