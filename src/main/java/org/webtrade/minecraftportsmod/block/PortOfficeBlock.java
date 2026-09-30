package org.webtrade.minecraftportsmod.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.webtrade.minecraftportsmod.chart.ChartService;
import org.webtrade.minecraftportsmod.port.Port;
import org.webtrade.minecraftportsmod.port.PortData;
import org.webtrade.minecraftportsmod.port.PortService;

/**
 * Harbour-master's desk. Placing it founds a port (named after the item if it was renamed in an anvil),
 * using it opens the nautical chart, breaking it dissolves the port.
 */
public class PortOfficeBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<PortOfficeBlock> CODEC = simpleCodec(PortOfficeBlock::new);
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 14.5, 16);

    public PortOfficeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /** Almost a full block: walkers must go around it, not try to pass through. */
    @Override
    protected boolean isPathfindable(BlockState state, net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel) {
            Component custom = stack.get(DataComponents.CUSTOM_NAME);
            PortService.createPort(serverLevel, pos, placer instanceof Player p ? p : null,
                    custom == null ? null : custom.getString());
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            Port port = PortData.get(serverLevel.getServer()).portAt(level.dimension(), pos);
            if (port == null) {
                // block placed before the mod tracked it (e.g. /setblock): found the port now
                port = PortService.createPort(serverLevel, pos, player, null);
            }
            ChartService.openOffice(serverPlayer, port);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        if (!level.getBlockState(pos).is(this)) {
            PortService.removePort(level, pos);
        }
    }
}
