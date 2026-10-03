package org.webtrade.minecraftportsmod.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.webtrade.minecraftportsmod.colony.Plots;

/**
 * A boundary stone: set down in a village, it marks the square round it as the player's own plot (see
 * {@link Plots}); used, it shows the plot's edge. Only its owner can take it up again (the plot goes back to the
 * village then).
 */
public class PlotMarkerBlock extends Block {

    public static final MapCodec<PlotMarkerBlock> CODEC = simpleCodec(PlotMarkerBlock::new);
    private static final VoxelShape SHAPE = Block.box(5, 0, 5, 11, 12, 11);

    public PlotMarkerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel sl) || !(placer instanceof ServerPlayer p)) return;
        var why = Plots.claim(p, sl, pos, stack);
        if (why == null) return;
        // not here: the stone comes back to the hand
        sl.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        ItemStack back = stack.copyWithCount(1);
        if (!p.getInventory().add(back)) p.drop(back, false);
        p.sendSystemMessage(why.copy().withStyle(ChatFormatting.RED), true);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) Plots.use(sp, sl, pos);
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel sl) {
            var v = Plots.villageAt(sl, pos);
            Plots.release(sl, pos);
            // the stone back to its owner, still the village's
            if (v != null && !player.isCreative()) {
                ItemStack s = Plots.stone(v);
                if (!player.getInventory().add(s)) player.drop(s, false);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
