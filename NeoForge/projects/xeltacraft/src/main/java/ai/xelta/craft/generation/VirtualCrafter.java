package ai.xelta.craft.generation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Map;

public class VirtualCrafter {

    // Maps a desired architectural block to its base raw material
    private static final Map<Block, Block> RECIPE_MAP = new HashMap<>();

    static {
        // Oak Wood Family
        RECIPE_MAP.put(Blocks.OAK_PLANKS, Blocks.OAK_LOG);
        RECIPE_MAP.put(Blocks.OAK_STAIRS, Blocks.OAK_LOG);
        RECIPE_MAP.put(Blocks.OAK_SLAB, Blocks.OAK_LOG);
        RECIPE_MAP.put(Blocks.OAK_DOOR, Blocks.OAK_LOG);
        RECIPE_MAP.put(Blocks.OAK_TRAPDOOR, Blocks.OAK_LOG);
        RECIPE_MAP.put(Blocks.OAK_FENCE, Blocks.OAK_LOG);

        // Stone Family
        RECIPE_MAP.put(Blocks.STONE_BRICKS, Blocks.COBBLESTONE);
        RECIPE_MAP.put(Blocks.STONE_BRICK_STAIRS, Blocks.COBBLESTONE);
        RECIPE_MAP.put(Blocks.STONE_BRICK_SLAB, Blocks.COBBLESTONE);
        RECIPE_MAP.put(Blocks.STONE, Blocks.COBBLESTONE);
        RECIPE_MAP.put(Blocks.SMOOTH_STONE, Blocks.COBBLESTONE);
        
        // Deepslate Family
        RECIPE_MAP.put(Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE);
        RECIPE_MAP.put(Blocks.DEEPSLATE_BRICKS, Blocks.DEEPSLATE);
        RECIPE_MAP.put(Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE);
    }

    /**
     * Simulates crafting by checking if the player has the raw material for the desired block.
     * If they do, it shrinks the raw material stack by 1 and returns true.
     */
    public static boolean tryCraft(ServerPlayer player, Block desiredBlock) {
        Block rawMaterial = RECIPE_MAP.get(desiredBlock);
        if (rawMaterial == null) {
            return false; // No known recipe mapping
        }

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == rawMaterial.asItem()) {
                stack.shrink(1);
                ai.xelta.craft.XeltaCraftMod.LOGGER.info("[VirtualCrafter] Player {} successfully virtual-crafted {} using {}", player.getName().getString(), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(desiredBlock), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(rawMaterial));
                return true; // Successfully consumed the raw material to craft the desired block!
            }
        }
        return false;
    }
}
