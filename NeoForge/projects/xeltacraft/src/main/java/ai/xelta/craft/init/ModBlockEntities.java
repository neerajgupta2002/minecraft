package ai.xelta.craft.init;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.blockentity.XeltaTerminalBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, XeltaCraftMod.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<XeltaTerminalBlockEntity>> XELTA_TERMINAL =
            BLOCK_ENTITY_TYPES.register("xelta_terminal",
                    () -> BlockEntityType.Builder
                            .of(XeltaTerminalBlockEntity::new, ModBlocks.XELTA_TERMINAL.get())
                            .build(null)
            );
}
