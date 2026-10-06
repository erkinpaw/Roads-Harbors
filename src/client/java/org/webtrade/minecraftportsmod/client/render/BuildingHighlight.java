package org.webtrade.minecraftportsmod.client.render;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * The block of a building's trade (the smithy's anvil, the joiner's crafting table...), outlined in gold while the
 * building or the block is looked at: what to click for the building's menu.
 */
public final class BuildingHighlight {

    private BuildingHighlight() {
    }

    private static final int GOLD = 0xFFFFD45A;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(BuildingHighlight::tick);
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null) return;
        ColonyPayloads.Badge b = BuildingBadges.looked();
        if (b == null || b.key() == ColonyPayloads.Badge.NO_KEY) return;
        try (var c = mc.collectPerTickGizmos()) {
            Gizmos.cuboid(new AABB(BlockPos.of(b.key())).inflate(0.03), GizmoStyle.stroke(GOLD, 3F));
        }
    }
}
