package ai.xelta.craft.block;

import ai.xelta.craft.blockentity.MediaCanvasBlockEntity;
import ai.xelta.craft.item.FilmRollItem;
import ai.xelta.craft.item.PhotoSlideItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class MediaCanvasBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public MediaCanvasBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(MediaCanvasBlock::new);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MediaCanvasBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MediaCanvasBlockEntity canvas) {
                if (stack.getItem() instanceof PhotoSlideItem) {
                    String url = PhotoSlideItem.getImageUrl(stack);
                    if (url != null && !url.isBlank()) {
                        canvas.setMediaUrl(url);
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal("Loaded image into canvas!"), true);
                        return ItemInteractionResult.SUCCESS;
                    }
                } else if (stack.getItem() instanceof FilmRollItem) {
                    String url = FilmRollItem.getVideoUrl(stack);
                    if (url != null && !url.isBlank()) {
                        canvas.setMediaUrl(url);
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal("Loaded video into canvas!"), true);
                        return ItemInteractionResult.SUCCESS;
                    }
                }
            }
        }
        return ItemInteractionResult.SUCCESS;
    }
}
