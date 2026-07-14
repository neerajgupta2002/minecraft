package ai.xelta.craft.block;

import ai.xelta.craft.blockentity.XeltaTerminalBlockEntity;
import ai.xelta.craft.init.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * The Xelta Terminal block.
 *
 * The physical in-world machine players interact with.
 * Right-clicking opens the XeltaTerminalScreen GUI where players
 * type prompts and trigger AI generation via the Xelta API.
 *
 * Analogous to Higgsfield's "Supercomputer" block.
 */
public class XeltaTerminalBlock extends BaseEntityBlock {

    public XeltaTerminalBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    // Required by MC 1.21 BaseEntityBlock
    @Override
    public MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(XeltaTerminalBlock::new);
    }


    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new XeltaTerminalBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        // Server-side ticker: checks generation status and handles callbacks
        if (!level.isClientSide) {
            return createTickerHelper(type, ModBlockEntities.XELTA_TERMINAL.get(),
                    XeltaTerminalBlockEntity::serverTick);
        }
        return null;
    }

    // =========================================================
    // Player Interaction
    // =========================================================

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hitResult) {

        if (level.isClientSide) {
            return InteractionResult.SUCCESS; // Client: handled by screen opening
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof XeltaTerminalBlockEntity terminal) {
            if (player instanceof ServerPlayer serverPlayer) {
                // Open the Terminal GUI for this player
                serverPlayer.openMenu(terminal, pos);
            }
        }
        return InteractionResult.CONSUME;
    }

    // =========================================================
    // Rendering
    // =========================================================

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL; // Standard block model rendering
    }

    // =========================================================
    // Drop loot on break
    // =========================================================

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof XeltaTerminalBlockEntity terminal) {
                // Drop any items in the terminal's slots
                terminal.dropContents(level, pos);
            }
            super.onRemove(state, level, pos, newState, movedByPiston);
        }
    }
}
