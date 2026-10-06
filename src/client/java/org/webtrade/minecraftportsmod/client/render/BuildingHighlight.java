package org.webtrade.minecraftportsmod.client.render;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * The building looked at, marked out: its box drawn round it, or its ground outlined, or sparks rising along its
 * edge (the way it is marked: {@link #mode}).
 */
public final class BuildingHighlight {

    private BuildingHighlight() {
    }

    /** How the building looked at is marked: 0 not at all, 1 its box, 2 its ground outlined, 3 the outline and corner posts of light, 4 sparks along its edge, 5 its box lit. */
    public static int mode = 2;

    private static final int GOLD = 0xFFFFD45A, SOFT = 0xC0FFE08A;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(BuildingHighlight::tick);
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || mode == 0) return;
        ColonyPayloads.Badge b = BuildingBadges.looked();
        if (b == null) return;
        int[] k = b.box();
        double x0 = k[0], y0 = k[1], z0 = k[2], x1 = k[3], y1 = k[4], z1 = k[5];
        // (the ground: the box's floor, a little over it)
        double g = y0 + 1.05;
        if (mode == 4) {
            // sparks along the edge, rising
            for (int i = 0; i < 3; i++) {
                Vec3 p = edge(mc, x0, z0, x1, z1);
                mc.level.addParticle(ParticleTypes.END_ROD, p.x, g, p.z, 0, 0.03 + mc.level.getRandom().nextDouble() * 0.03, 0);
            }
            return;
        }
        try (var c = mc.collectPerTickGizmos()) {
            switch (mode) {
                case 1 -> Gizmos.cuboid(new AABB(x0, y0, z0, x1, y1, z1), GizmoStyle.stroke(SOFT, 2F));
                case 5 -> Gizmos.cuboid(new AABB(x0, y0, z0, x1, y1, z1), GizmoStyle.strokeAndFill(SOFT, 2F, 0x26FFE08A));
                default -> {
                    Vec3 a = new Vec3(x0, g, z0), bb = new Vec3(x1, g, z0), cc = new Vec3(x1, g, z1), d = new Vec3(x0, g, z1);
                    Gizmos.line(a, bb, GOLD, 3F);
                    Gizmos.line(bb, cc, GOLD, 3F);
                    Gizmos.line(cc, d, GOLD, 3F);
                    Gizmos.line(d, a, GOLD, 3F);
                    if (mode == 3) {
                        for (Vec3 p : new Vec3[]{a, bb, cc, d}) Gizmos.line(p, p.add(0, 3, 0), GOLD, 3F);
                    }
                }
            }
        }
    }

    /** A point somewhere on the box's edge, at random. */
    private static Vec3 edge(Minecraft mc, double x0, double z0, double x1, double z1) {
        double w = x1 - x0, d = z1 - z0, t = mc.level.getRandom().nextDouble() * 2 * (w + d);
        if (t < w) return new Vec3(x0 + t, 0, z0);
        t -= w;
        if (t < d) return new Vec3(x1, 0, z0 + t);
        t -= d;
        if (t < w) return new Vec3(x1 - t, 0, z1);
        return new Vec3(x0, 0, z1 - (t - w));
    }
}
