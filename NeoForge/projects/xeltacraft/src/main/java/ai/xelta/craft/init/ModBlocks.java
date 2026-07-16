package ai.xelta.craft.init;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.block.XeltaTerminalBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(XeltaCraftMod.MOD_ID);

    // The main in-world Xelta Terminal block — right-click opens AI generation GUI
    public static final DeferredBlock<XeltaTerminalBlock> XELTA_TERMINAL =
            BLOCKS.registerBlock("xelta_terminal",
                    XeltaTerminalBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_CYAN)
                            .requiresCorrectToolForDrops()
                            .strength(3.5f, 6.0f)   // Hard like a computer, but breakable
                            .sound(SoundType.METAL)
                            .lightLevel(state -> 4)  // Subtle glow — it's running AI!
                            .noOcclusion()
            );
}
