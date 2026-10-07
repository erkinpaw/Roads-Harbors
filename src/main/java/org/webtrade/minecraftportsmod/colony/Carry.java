package org.webtrade.minecraftportsmod.colony;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Goods carried to a neighbour on a village's donkey: what the village has to spare and the neighbour is short of,
 * out of its store onto the donkey when the task is taken. The player rides it there (it comes to no harm, its
 * packs stay shut); ridden into the neighbour's land, the goods go to its store, it pays the village for them, and
 * the player is rewarded.
 */
public final class Carry {

    private Carry() {
    }

    /** The tag of a village's pack donkey. */
    public static final String TAG = "minecraftportsmod_cargo";

    /** The server (for the day's ends, which run out tasks without it at hand). */
    static MinecraftServer server;

    /** Most units a donkey carries. */
    static final int LOAD = 128;

    /** A task to carry goods to a neighbour the trails lead to (one such task out at a time), or null. */
    static Quests.Quest offer(VillageData data, Village v, Dweller d) {
        for (Quests.Quest q : v.tasks.quests) if (q.kind == Quests.Kind.CARRY) return null;
        Quests.Quest best = null;
        double most = 0;
        for (Village o : data.all()) {
            if (o == v || Trails.route(data, v.id, o.id) == null) continue;
            for (Res r : Caravans.GOODS) {
                if (r.toolLevel() > 0) continue;
                int n = Math.min(LOAD, Math.min(Caravans.spare(v, r), Caravans.short_(o, r)));
                if (n < 8) continue;
                double worth = n * Trade.base(r);
                if (worth <= most) continue;
                most = worth;
                best = new Quests.Quest(0, d.id, Quests.Kind.CARRY, r.id(), n);
                best.to = o.id;
            }
        }
        return best;
    }

    /** The task taken: the goods off the store, the donkey by the player, saddled, its packs on. */
    static boolean load(ServerPlayer p, Village v, Quests.Quest q) {
        Res r = q.res();
        if (r == null || v.stock(r) < q.count || !(p.level() instanceof ServerLevel level)) return false;
        Donkey donkey = net.minecraft.world.entity.EntityTypes.DONKEY.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (donkey == null) return false;
        v.add(r, -q.count);
        donkey.snapTo(p.getX() + 1.5, p.getY(), p.getZ() + 1.5, p.getYRot(), 0);
        donkey.setTamed(true);
        donkey.setOwner(p);
        donkey.setChest(true);
        donkey.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
        donkey.setInvulnerable(true);
        donkey.setPersistenceRequired();
        donkey.addTag(TAG);
        donkey.setCustomName(Component.literal(v.name));
        level.addFreshEntity(donkey);
        q.mount = donkey.getUUID();
        return true;
    }

    /** The task given up or run out: the goods back to the store (the donkey brought them home), the donkey gone. */
    static void unload(MinecraftServer srv, Village v, Quests.Quest q) {
        Res r = q.res();
        if (r != null && !q.complete()) v.add(r, q.count);
        Entity e = donkey(srv, q);
        if (e != null) {
            e.ejectPassengers();
            e.discard();
        }
    }

    static Entity donkey(MinecraftServer srv, Quests.Quest q) {
        if (q.mount == null) return null;
        for (ServerLevel level : srv.getAllLevels()) {
            Entity e = level.getEntity(q.mount);
            if (e != null) return e;
        }
        return null;
    }

    /** Every second: a player riding a village's donkey into the land of the village the goods go to delivers them. */
    static void tick(MinecraftServer srv) {
        server = srv;
        if (srv.getTickCount() % 20 != 0) return;
        VillageData data = VillageData.get(srv);
        for (Village v : data.all()) {
            for (Quests.Quest q : new java.util.ArrayList<>(v.tasks.quests)) {
                if (q.kind != Quests.Kind.CARRY || !q.taken() || q.mount == null) continue;
                Entity e = donkey(srv, q);
                if (e == null) continue;
                Village to = data.get(q.to);
                if (to == null) continue;
                ServerPlayer p = srv.getPlayerList().getPlayer(q.taker);
                if (p == null || e.getControllingPassenger() != p) continue;
                if (!Territory.contains(to, e.getBlockX(), e.getBlockZ())) continue;
                deliver(p, data, v, to, q, e);
            }
        }
    }

    /** The goods reach the neighbour: into its store, paid for to the village they came from; the task done. */
    static void deliver(ServerPlayer p, VillageData data, Village from, Village to, Quests.Quest q, Entity donkey) {
        Res r = q.res();
        int n = Math.min(q.count, to.free(r));
        to.add(r, n);
        // (what found no room in its store is given to the village as it is: it is theirs now)
        if (n < q.count) to.add(r, q.count - n);
        // (the neighbour pays for them out of its purse, at their worth; what it cannot pay is a gift)
        int pay = Math.min(to.emeralds, (int) Math.round(q.count * Trade.base(r)));
        to.emeralds -= pay;
        from.emeralds += pay;
        q.done = q.count;
        from.log(data.day, Component.translatable("minecraftportsmod.vlog.carried", p.getName(), VillageText.amounts(java.util.Map.of(r, q.count)), to.name)
                .withStyle(ChatFormatting.DARK_GREEN));
        to.log(data.day, Component.translatable("minecraftportsmod.vlog.carried_in", p.getName(), VillageText.amounts(java.util.Map.of(r, q.count)), from.name)
                .withStyle(ChatFormatting.DARK_GREEN));
        donkey.ejectPassengers();
        donkey.discard();
        q.mount = null;
        Quests.finish(p, data, from, q);
        p.sendSystemMessage(Component.translatable("minecraftportsmod.quest.carried", to.name).withStyle(ChatFormatting.GOLD), true);
        p.level().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.0F);
        data.changed();
    }
}
