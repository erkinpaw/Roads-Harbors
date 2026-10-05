package org.webtrade.minecraftportsmod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import org.joml.Quaternionf;

/**
 * A badge hung over a resident or a building, facing the camera: a round icon with a ring round it that fills as the
 * work in hand goes on, below it what is being done, below that the title (with a small icon left of it).
 */
final class Badge {

    private Badge() {
    }

    /**
     * Draws a badge whose bottom line is at the pose's origin (already moved to where it hangs).
     *
     * @param progress how far the work is (0..1); below 0: no ring
     * @param titleIcon the small icon left of the title (may be empty)
     * @param size      how big (1: a name tag's letters)
     */
    static void submit(PoseStack pose, SubmitNodeCollector collector, Quaternionf facing, Component title, ItemStackRenderState titleIcon,
                       Component doing, ItemStackRenderState icon, float progress, int light, float size) {
        Font font = Minecraft.getInstance().font;
        pose.pushPose();
        pose.mulPose(facing);
        pose.scale(0.025F * size, -0.025F * size, 0.025F * size);
        var t = title.getVisualOrderText();
        int tw = font.width(t);
        boolean small = titleIcon != null && !titleIcon.isEmpty();
        float tx = -tw / 2F + (small ? 6 : 0);
        var d = doing.getString().isEmpty() ? null : doing.getVisualOrderText();
        float y = d == null ? 0 : -11;
        float cy = y - 15;
        // (like a name tag: faint through walls, plain where seen)
        for (boolean through : new boolean[]{true, false}) {
            Font.DisplayMode mode = through ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;
            int ink = through ? 0x40FFFFFF : 0xFFFFFFFF;
            // the title, what is being done
            collector.submitText(pose, tx, 0, t, false, mode, light, ink, through ? 0x40000000 : 0, 0);
            if (d != null) collector.submitText(pose, -font.width(d) / 2F, y, d, false, mode, light, ink, through ? 0x40000000 : 0, 0);
            // the round icon's patch, and the ring of the work round it
            int fade = through ? 3 : 1;
            collector.submitCustomGeometry(pose, through ? RenderTypes.textBackgroundSeeThrough() : RenderTypes.textBackground(), (pp, vc) -> {
                disc(pp, vc, 0, cy, 8.5F, alpha(0x90202020, fade), light);
                if (progress >= 0) {
                    ring(pp, vc, 0, cy, 9, 11, alpha(0x80000000, fade), light, 1);
                    ring(pp, vc, 0, cy, 9, 11, alpha(0xFF55C855, fade), light, Math.min(1, progress));
                }
            });
        }
        if (small) item(titleIcon, pose, collector, tx - 7, 4, 9, light);
        if (icon != null && !icon.isEmpty()) item(icon, pose, collector, 0, cy, 12, light);
        pose.popPose();
    }

    private static int alpha(int argb, int div) {
        return ((argb >>> 24) / div) << 24 | argb & 0xFFFFFF;
    }

    /** An item's icon flat on the badge, centred at (x, y), so many pixels across. */
    private static void item(ItemStackRenderState item, PoseStack pose, SubmitNodeCollector collector, float x, float y, float size, int light) {
        pose.pushPose();
        pose.translate(x, y, 0.1F);
        pose.scale(size, -size, 0.01F);
        item.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, 0);
        pose.popPose();
    }

    /** A round patch (a fan of triangles drawn as quads). */
    private static void disc(PoseStack.Pose pose, VertexConsumer vc, float cx, float cy, float r, int argb, int light) {
        int n = 24;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            float x0 = cx + (float) Math.sin(a0) * r, y0 = cy - (float) Math.cos(a0) * r;
            float x1 = cx + (float) Math.sin(a1) * r, y1 = cy - (float) Math.cos(a1) * r;
            quad(pose, vc, cx, cy, x0, y0, x1, y1, cx, cy, 0, argb, light);
        }
    }

    /** A ring from the top, clockwise, so far round ({@code part} 0..1). */
    private static void ring(PoseStack.Pose pose, VertexConsumer vc, float cx, float cy, float r0, float r1, int argb, int light, float part) {
        int n = 32;
        int to = (int) Math.ceil(n * part);
        for (int i = 0; i < to; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * Math.min(i + 1, n * part) / n;
            float s0 = (float) Math.sin(a0), c0 = (float) Math.cos(a0), s1 = (float) Math.sin(a1), c1 = (float) Math.cos(a1);
            quad(pose, vc, cx + s0 * r0, cy - c0 * r0, cx + s0 * r1, cy - c0 * r1, cx + s1 * r1, cy - c1 * r1, cx + s1 * r0, cy - c1 * r0, -0.02F,
                    argb, light);
        }
    }

    /** A quad seen from both sides. */
    private static void quad(PoseStack.Pose pose, VertexConsumer vc, float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy,
                             float z, int argb, int light) {
        vc.addVertex(pose, ax, ay, z).setColor(argb).setLight(light);
        vc.addVertex(pose, bx, by, z).setColor(argb).setLight(light);
        vc.addVertex(pose, cx, cy, z).setColor(argb).setLight(light);
        vc.addVertex(pose, dx, dy, z).setColor(argb).setLight(light);
        vc.addVertex(pose, dx, dy, z).setColor(argb).setLight(light);
        vc.addVertex(pose, cx, cy, z).setColor(argb).setLight(light);
        vc.addVertex(pose, bx, by, z).setColor(argb).setLight(light);
        vc.addVertex(pose, ax, ay, z).setColor(argb).setLight(light);
    }
}
