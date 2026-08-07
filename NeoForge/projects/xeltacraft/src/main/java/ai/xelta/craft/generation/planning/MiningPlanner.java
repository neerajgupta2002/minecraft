package ai.xelta.craft.generation.planning;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Advisory-only: turns a set of missing raw materials into a "go get this" recommendation
 * (which block(s) to break to obtain each one). Does not move or act in the world itself.
 */
public class MiningPlanner {

    public record MiningRecommendation(Item material, int amountNeeded, List<Block> sourceBlocks, String note) {}

    private static final Map<Item, List<Block>> ORE_SOURCES = Map.ofEntries(
            Map.entry(Items.RAW_IRON, List.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE)),
            Map.entry(Items.RAW_GOLD, List.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE)),
            Map.entry(Items.RAW_COPPER, List.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE)),
            Map.entry(Items.COAL, List.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE)),
            Map.entry(Items.DIAMOND, List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE)),
            Map.entry(Items.EMERALD, List.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE)),
            Map.entry(Items.REDSTONE, List.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE)),
            Map.entry(Items.LAPIS_LAZULI, List.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE)),
            Map.entry(Items.QUARTZ, List.of(Blocks.NETHER_QUARTZ_ORE)),
            Map.entry(Items.COBBLESTONE, List.of(Blocks.STONE, Blocks.COBBLESTONE)),
            Map.entry(Items.COBBLED_DEEPSLATE, List.of(Blocks.DEEPSLATE)),
            Map.entry(Items.OAK_LOG, List.of(Blocks.OAK_LOG)),
            Map.entry(Items.SPRUCE_LOG, List.of(Blocks.SPRUCE_LOG)),
            Map.entry(Items.BIRCH_LOG, List.of(Blocks.BIRCH_LOG)),
            Map.entry(Items.SAND, List.of(Blocks.SAND)),
            Map.entry(Items.GRAVEL, List.of(Blocks.GRAVEL)),
            Map.entry(Items.CLAY_BALL, List.of(Blocks.CLAY))
    );

    public static List<MiningRecommendation> plan(Map<Item, Integer> missingRawMaterials) {
        List<MiningRecommendation> recommendations = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : missingRawMaterials.entrySet()) {
            Item item = entry.getKey();
            int amount = entry.getValue();
            List<Block> sources = ORE_SOURCES.get(item);
            String note = sources != null
                    ? "Mine " + describeBlocks(sources)
                    : "No known ore/block source — obtain " + BuiltInRegistries.ITEM.getKey(item) + " directly.";
            recommendations.add(new MiningRecommendation(item, amount, sources != null ? sources : List.of(), note));
        }
        return recommendations;
    }

    private static String describeBlocks(List<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) sb.append(" or ");
            sb.append(BuiltInRegistries.BLOCK.getKey(blocks.get(i)).getPath());
        }
        return sb.toString();
    }
}
