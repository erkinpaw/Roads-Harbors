package org.webtrade.minecraftportsmod.client.render;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * The block of a building's trade (the smithy's anvil, the joiner's crafting table...), marked out while the building
 * is looked at: what to click for the building's menu (the way it is marked: {@link #mode}).
 */
public final class BuildingHighlight {

    private BuildingHighlight() {
    }

    /** How the block is marked: 0 not at all, 1 a gold outline, 2 a glow coming and going, 3 sparks rising over it, 4 green sparkles round it, 5 the outline and a spark now and then. */
    public static int mode = 1;

    private static final int GOLD = 0xFFFFD45A;
    private static int ticks;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(BuildingHighlight::tick);
    }

    private static void tick(Minecraft mc) {
        ticks++;
        if (mc.level == null || mode == 0) return;
        ColonyPayloads.Badge b = BuildingBadges.looked();
        if (b == null || b.key() == ColonyPayloads.Badge.NO_KEY) return;
        BlockPos k = BlockPos.of(b.key());
        AABB box = new AABB(k).inflate(0.03);
        var rnd = mc.level.getRandom();
        switch (mode) {
            case 3 -> {
                if (ticks % 2 == 0) mc.level.addParticle(ParticleTypes.END_ROD, k.getX() + 0.2 + rnd.nextDouble() * 0.6, k.getY() + 1.05,
                        k.getZ() + 0.2 + rnd.nextDouble() * 0.6, 0, 0.04, 0);
                return;
            }
            case 4 -> {
                if (ticks % 3 == 0) mc.level.addParticle(ParticleTypes.HAPPY_VILLAGER, k.getX() - 0.1 + rnd.nextDouble() * 1.2,
                        k.getY() + rnd.nextDouble() * 1.2, k.getZ() - 0.1 + rnd.nextDouble() * 1.2, 0, 0, 0);
                return;
            }
            default -> {
            }
        }
        try (var c = mc.collectPerTickGizmos()) {
            if (mode == 2) {
                // (the glow comes and goes, a second and a half round)
                double phase = (Math.sin(ticks * Math.PI * 2 / 30) + 1) / 2;
                int alpha = (int) (0x18 + phase * 0x50);
                Gizmos.cuboid(box, GizmoStyle.strokeAndFill(GOLD, 2F, (alpha << 24) | 0xFFE08A));
            } else {
                Gizmos.cuboid(box, GizmoStyle.stroke(GOLD, 3F));
            }
        }
        if (mode == 5 && ticks % 8 == 0) {
            mc.level.addParticle(ParticleTypes.END_ROD, k.getX() + 0.5, k.getY() + 1.1, k.getZ() + 0.5, 0, 0.03, 0);
        }
    }
}
