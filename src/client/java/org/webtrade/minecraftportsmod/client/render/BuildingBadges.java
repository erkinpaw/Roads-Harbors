package org.webtrade.minecraftportsmod.client.render;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;

import net.minecraft.world.item.ItemDisplayContext;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

import java.util.ArrayList;
import java.util.List;

/** The badges over the village buildings near the player (as the server sends them every second). */
public final class BuildingBadges {

    private BuildingBadges() {
    }

    /** A badge and its icon made ready to draw. */
    private record Shown(ColonyPayloads.Badge badge, ItemStackRenderState icon) {
    }

    private static List<Shown> shown = List.of();

    /** A resident's badge, as made ready while the frame's entities were looked at. */
    private record Person(double x, double y, double z, net.minecraft.network.chat.Component title, ItemStackRenderState jobIcon,
                          net.minecraft.network.chat.Component doing, ItemStackRenderState icon, float progress, int light) {
    }

    private static final List<Person> people = new ArrayList<>();

    /** A resident's badge to draw this frame (where it hangs, in the world). */
    static void resident(double x, double y, double z, net.minecraft.network.chat.Component title, ItemStackRenderState jobIcon,
                         net.minecraft.network.chat.Component doing, ItemStackRenderState icon, float progress, int light) {
        people.add(new Person(x, y, z, title, jobIcon, doing, icon, progress, light));
    }
    /** Badges are drawn this near the camera. */
    private static final double NEAR = 40, IDLE = 20;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(ColonyPayloads.BadgeView.TYPE, (payload, ctx) -> {
            var mc = Minecraft.getInstance();
            List<Shown> out = new ArrayList<>();
            for (ColonyPayloads.Badge b : payload.badges()) {
                ItemStackRenderState icon = new ItemStackRenderState();
                if (mc.level != null) mc.getItemModelResolver().updateForTopItem(icon, b.icon(), ItemDisplayContext.GUI, mc.level, null, 0);
                out.add(new Shown(b, icon));
            }
            shown = out;
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
            var camera = ctx.levelState().cameraRenderState;
            if (camera == null || camera.pos == null) {
                people.clear();
                return;
            }
            var pose = ctx.poseStack();
            for (Person p : people) {
                pose.pushPose();
                pose.translate(p.x() - camera.pos.x, p.y() - camera.pos.y, p.z() - camera.pos.z);
                Badge.submit(pose, ctx.submitNodeCollector(), camera.orientation, p.title(), p.jobIcon(), p.doing(), p.icon(), p.progress(), p.light(), 1);
                pose.popPose();
            }
            people.clear();
            for (Shown s : shown) {
                ColonyPayloads.Badge b = s.badge();
                double dx = b.x() - camera.pos.x, dy = b.y() - camera.pos.y, dz = b.z() - camera.pos.z;
                // (an idle building's badge only close by; one at work from farther off)
                double near = b.progress() >= 0 ? NEAR : IDLE;
                if (dx * dx + dy * dy + dz * dz > near * near) continue;
                pose.pushPose();
                pose.translate(dx, dy, dz);
                Badge.submit(pose, ctx.submitNodeCollector(), camera.orientation, b.title(), null, b.doing(), s.icon(), b.progress(),
                        0xF000F0, 1.8F);
                pose.popPose();
            }
        });
    }

    /** For tests: the badges shown now. */
    public static List<ColonyPayloads.Badge> badges() {
        List<ColonyPayloads.Badge> out = new ArrayList<>();
        for (Shown s : shown) out.add(s.badge());
        return out;
    }
}
