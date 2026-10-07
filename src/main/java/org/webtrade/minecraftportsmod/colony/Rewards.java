package org.webtrade.minecraftportsmod.colony;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

import java.util.List;

/**
 * A task's reward goes to the player's home in the village: into a chest of the house on their plot (the chest is
 * marked out for them a while). No home there, or no room in its chests: into what they carry.
 */
public final class Rewards {

    private Rewards() {
    }

    /** How long the chest a reward went into stays marked out (ticks). */
    static final int MARK_TICKS = 20 * 60;

    static void send(ServerPlayer p, Village v, List<ItemStack> items) {
        if (items.isEmpty()) return;
        BlockPos chest = null;
        Container box = null;
        Plots.Plot plot = Plots.of(v, p.getUUID());
        if (plot != null && plot.home() && p.level() instanceof ServerLevel level) {
            BlockPos m = plot.marker();
            // the chests of the house on the plot, the nearest its middle first
            double best = Double.MAX_VALUE;
            for (BlockPos q : BlockPos.betweenClosed(m.offset(-Plots.HALF, -3, -Plots.HALF), m.offset(Plots.HALF, 8, Plots.HALF))) {
                if (!level.isLoaded(q)) continue;
                BlockEntity be = level.getBlockEntity(q);
                if (!(be instanceof ChestBlockEntity c)) continue;
                double d = q.distSqr(m);
                if (d < best && room(c, items)) {
                    best = d;
                    chest = q.immutable();
                    box = c;
                }
            }
        }
        boolean home = false;
        for (ItemStack s : items) {
            ItemStack left = box == null ? s : put(box, s.copy());
            if (left.getCount() < s.getCount()) home = true;
            if (!left.isEmpty()) Quests.give(p, left);
        }
        if (box != null) box.setChanged();
        if (home) {
            ServerPlayNetworking.send(p, new ColonyPayloads.RewardChest(chest.asLong(), MARK_TICKS));
            p.sendSystemMessage(Component.translatable("minecraftportsmod.quest.reward_home").withStyle(ChatFormatting.GOLD), true);
        }
    }

    /** Is there room in a chest for any of the things (an empty slot, or a stack of one of them not full)? */
    private static boolean room(Container c, List<ItemStack> items) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack in = c.getItem(i);
            if (in.isEmpty()) return true;
            for (ItemStack s : items) if (ItemStack.isSameItemSameComponents(in, s) && in.getCount() < in.getMaxStackSize()) return true;
        }
        return false;
    }

    /** Puts a stack into a container (onto its like first, then into empty slots); returns what did not go in. */
    private static ItemStack put(Container c, ItemStack s) {
        for (int i = 0; i < c.getContainerSize() && !s.isEmpty(); i++) {
            ItemStack in = c.getItem(i);
            if (in.isEmpty() || !ItemStack.isSameItemSameComponents(in, s)) continue;
            int k = Math.min(s.getCount(), in.getMaxStackSize() - in.getCount());
            if (k <= 0) continue;
            in.grow(k);
            s.shrink(k);
        }
        for (int i = 0; i < c.getContainerSize() && !s.isEmpty(); i++) {
            if (!c.getItem(i).isEmpty()) continue;
            c.setItem(i, s.copy());
            s.setCount(0);
        }
        return s;
    }
}
