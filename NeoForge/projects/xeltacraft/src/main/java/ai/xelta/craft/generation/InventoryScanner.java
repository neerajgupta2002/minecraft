package ai.xelta.craft.generation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

public class InventoryScanner {

    /**
     * Scans every item stack in the player's inventory (not just placeable blocks), for use by
     * the resource-planning pipeline which needs raw materials like ingots and logs too.
     */
    public static Map<Item, Integer> scanAllItems(ServerPlayer player) {
        Map<Item, Integer> counts = new HashMap<>();
        if (player.isCreative()) return counts; // treated as infinite by the caller

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Scans the player's inventory and returns a natural language summary of available blocks.
     * If the player is in Creative mode, returns a string indicating infinite resources.
     */
    public static String scanInventory(ServerPlayer player) {
        if (player.isCreative()) {
            return "Inventory: Creative Mode (Infinite resources).";
        }

        Map<String, Integer> blockCounts = new HashMap<>();
        
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
                String blockName = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
                blockCounts.put(blockName, blockCounts.getOrDefault(blockName, 0) + stack.getCount());
            }
        }
        
        ai.xelta.craft.XeltaCraftMod.LOGGER.info("[InventoryScanner] Scanned inventory for player {}: {} unique block types found.", player.getName().getString(), blockCounts.size());

        if (blockCounts.isEmpty()) {
            return "Inventory: Empty (No building blocks available).";
        }

        StringBuilder sb = new StringBuilder("Inventory Blocks: ");
        int count = 0;
        for (Map.Entry<String, Integer> entry : blockCounts.entrySet()) {
            sb.append(entry.getValue()).append("x ").append(entry.getKey());
            count++;
            if (count < blockCounts.size()) {
                sb.append(", ");
            }
        }
        sb.append(".");
        return sb.toString();
    }
}
