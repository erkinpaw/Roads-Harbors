package org.webtrade.minecraftportsmod.colony;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

import java.util.ArrayList;
import java.util.List;

/**
 * The badges over a village's buildings, for the players near: the building's icon in a ring that fills as its work
 * goes on (going up, being raised, the making in hand at a workshop), what it is doing, its name and level. Sent every
 * second.
 */
public final class Badges {

    private Badges() {
    }

    /** Buildings this near a player have a badge. */
    static final int NEAR = 48;

    static void tick(MinecraftServer srv) {
        if (srv.getTickCount() % 20 != 7) return;
        VillageData data = VillageData.get(srv);
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            if (p.level().dimension() != Level.OVERWORLD) continue;
            List<ColonyPayloads.Badge> out = new ArrayList<>();
            BlockPos at = p.blockPosition();
            for (Village v : data.all()) {
                if (at.distSqr(v.center) > (200 + NEAR) * (200 + NEAR)) continue;
                for (Building b : v.buildings) {
                    if (b.type == BuildingType.SHIP || b.state == Building.State.DEMOLISHING && b.finished) continue;
                    if (Math.abs(b.origin.getX() - at.getX()) > NEAR || Math.abs(b.origin.getZ() - at.getZ()) > NEAR) continue;
                    ColonyPayloads.Badge badge = badge(v, b);
                    if (badge != null) out.add(badge);
                }
            }
            ServerPlayNetworking.send(p, new ColonyPayloads.BadgeView(out));
        }
    }

    /** A building's badge: over its middle, a little above its top. */
    public static ColonyPayloads.Badge badge(Village v, Building b) {
        Blueprint bp = b.blueprint(v.wood);
        int[] top = top(b, bp);
        if (top == null) return null;
        Component title = Component.translatable("minecraftportsmod.badge.title", b.type.displayName(), b.level);
        Component doing = Component.empty();
        float progress = -1;
        ItemStack icon = new ItemStack(b.type.icon);
        switch (b.state) {
            case PLANNED -> {
                // the materials being brought
                int need = 0, got = 0;
                for (Res r : Res.values()) {
                    need += b.cost(r);
                    got += Math.min(b.cost(r), b.delivered(r));
                }
                progress = need == 0 ? 0 : got / (float) need;
                doing = Component.translatable("minecraftportsmod.badge.gathering");
            }
            case BUILDING -> {
                progress = Math.min(1F, b.work / (float) Math.max(1, bp.pieces.size()));
                doing = Component.translatable("minecraftportsmod.badge.building");
            }
            case DEMOLISHING -> {
                int total = Math.max(1, bp.pieces.size());
                progress = Math.min(1F, (total - b.work) / (float) total);
                doing = Component.translatable("minecraftportsmod.badge.demolishing");
            }
            case BUILT -> {
                if (b.upgrading()) {
                    progress = ColonyService.raiseProgress(v, b);
                    doing = Component.translatable(b.supplied() ? "minecraftportsmod.badge.raising" : "minecraftportsmod.badge.gathering");
                } else if (!Workshops.recipes(b.type).isEmpty()) {
                    Orders.Order o = Workshops.current(v, b);
                    Workshops.Recipe r = o == null ? null : Workshops.recipe(b.type, o.recipe);
                    if (r != null) {
                        icon = Orders.piece(v, r.out());
                        if (b.taken && o.id == b.making) {
                            progress = (float) Workshops.progress(v, b)[1];
                            doing = Component.translatable("minecraftportsmod.badge.making", r.out().displayName());
                        } else {
                            progress = 0;
                            doing = Component.translatable(Orders.supplied(v, r) ? "minecraftportsmod.badge.queued" : "minecraftportsmod.badge.short",
                                    r.out().displayName());
                        }
                    }
                }
            }
        }
        return new ColonyPayloads.Badge(top[0] + 0.5F, top[1] + 1.2F, top[2] + 0.5F, icon, progress, doing, title);
    }

    /** The middle of a building's top (worked out once for a blueprint). */
    private static int[] top(Building b, Blueprint bp) {
        if (b.badgeAt != null && b.badgeOf == bp) return b.badgeAt;
        if (bp.pieces.isEmpty()) return null;
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (Blueprint.Piece pc : bp.pieces) {
            BlockPos p = pc.pos();
            x0 = Math.min(x0, p.getX());
            x1 = Math.max(x1, p.getX());
            z0 = Math.min(z0, p.getZ());
            z1 = Math.max(z1, p.getZ());
            y1 = Math.max(y1, p.getY());
        }
        b.badgeAt = new int[]{(x0 + x1) / 2, y1 + 1, (z0 + z1) / 2};
        b.badgeOf = bp;
        return b.badgeAt;
    }
}
