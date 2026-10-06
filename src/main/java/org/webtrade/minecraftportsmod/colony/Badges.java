package org.webtrade.minecraftportsmod.colony;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

    /** The players who were last sent some badges. */
    private static final java.util.Set<java.util.UUID> SHOWN = new java.util.HashSet<>();

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
            // the player's own ships near: their hold, and whether they lie at a village loading (its stall trades with them)
            ServerLevel level = (ServerLevel) p.level();
            for (var ship : level.getEntitiesOfClass(org.webtrade.minecraftportsmod.combat.WarshipEntity.class, p.getBoundingBox().inflate(NEAR),
                    w -> p.getUUID().equals(w.owner()) && w.sinking() == 0)) {
                out.add(shipBadge(level, data, p, ship));
            }
            // (nothing near, as a second ago: nothing sent)
            if (out.isEmpty() && !SHOWN.remove(p.getUUID())) continue;
            if (!out.isEmpty()) SHOWN.add(p.getUUID());
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
        return new ColonyPayloads.Badge(top[0] + 0.5F, top[1] + 1.2F, top[2] + 0.5F, new int[]{top[3], top[4], top[5], top[6], top[7], top[8]}, icon,
                progress, doing, title);
    }

    /**
     * A player's ship's badge, over her masts: a barrel in a ring that fills with her hold, how full it is, and where
     * she is loading (at a village whose stall trades with her hold). Her box: her hull and rigging.
     */
    static ColonyPayloads.Badge shipBadge(ServerLevel level, VillageData data, ServerPlayer p, org.webtrade.minecraftportsmod.combat.WarshipEntity ship) {
        var hold = ship.hold();
        int used = 0;
        for (int i = 0; i < hold.getContainerSize(); i++) if (!hold.getItem(i).isEmpty()) used++;
        Component doing = Component.empty();
        for (Village v : data.all()) {
            if (ship.blockPosition().distSqr(v.center) > 200 * 200) continue;
            if (Harbour.playerShip(level, v, p.getUUID()) == ship) {
                doing = Component.translatable("minecraftportsmod.ship.loading", v.name);
                break;
            }
        }
        int x = ship.getBlockX(), y = ship.getBlockY(), z = ship.getBlockZ();
        return new ColonyPayloads.Badge(x + 0.5F, y + 16F, z + 0.5F, new int[]{x - 9, y - 1, z - 9, x + 10, y + 16, z + 10}, new ItemStack(Items.BARREL),
                used / (float) hold.getContainerSize(), doing, Component.translatable("minecraftportsmod.ship.hold_badge", used, hold.getContainerSize()));
    }

    /** The middle of a building's top, and its box (worked out once for a blueprint). */
    private static int[] top(Building b, Blueprint bp) {
        if (b.badgeAt != null && b.badgeOf == bp) return b.badgeAt;
        if (bp.pieces.isEmpty()) return null;
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE;
        for (Blueprint.Piece pc : bp.pieces) {
            BlockPos p = pc.pos();
            x0 = Math.min(x0, p.getX());
            x1 = Math.max(x1, p.getX());
            z0 = Math.min(z0, p.getZ());
            z1 = Math.max(z1, p.getZ());
            y1 = Math.max(y1, p.getY());
            y0 = Math.min(y0, p.getY());
        }
        // (and the building's box, for the badge to show while it is looked at)
        b.badgeAt = new int[]{(x0 + x1) / 2, y1 + 1, (z0 + z1) / 2, x0, y0, z0, x1 + 1, y1 + 1, z1 + 1};
        b.badgeOf = bp;
        return b.badgeAt;
    }
}
