package org.webtrade.minecraftportsmod.combat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** A musket: a shot where one looks, then a while to load it again. Good against a ship's crew when she comes close. */
public class MusketItem extends Item {

    /** Ticks to load it again. */
    public static final int RELOAD = 40;

    public MusketItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(stack)) return InteractionResult.FAIL;
        if (level instanceof ServerLevel sl) {
            MusketBallEntity.fire(sl, player, player.getEyePosition(), player.getLookAngle(), 0.015);
            player.getCooldowns().addCooldown(stack, RELOAD);
        }
        return InteractionResult.SUCCESS;
    }
}
