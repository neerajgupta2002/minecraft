package ai.xelta.craft.generation.planning;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Advisory-only: for items the dependency solver resolved via a furnace-smelting recipe,
 * reports how many smelts are needed and the estimated real-time cost. Does not operate
 * any furnace itself.
 */
public class SmeltingPlanner {

    public record SmeltingRecommendation(Item result, int amountNeeded, int estimatedSeconds) {}

    public static List<SmeltingRecommendation> plan(Level level, Map<Item, Integer> smeltingNeeded) {
        Map<Item, CraftingDependencySolver.SmeltingRecipeInfo<Item>> index = RecipeKnowledgeBase.indexSmeltingRecipes(level);
        List<SmeltingRecommendation> recommendations = new ArrayList<>();

        for (Map.Entry<Item, Integer> entry : smeltingNeeded.entrySet()) {
            Item item = entry.getKey();
            int smeltsNeeded = entry.getValue();
            CraftingDependencySolver.SmeltingRecipeInfo<Item> recipe = index.get(item);
            int cookingTimeTicks = recipe != null ? recipe.cookingTimeTicks() : 200; // vanilla default
            int estimatedSeconds = (int) Math.ceil(smeltsNeeded * cookingTimeTicks / 20.0);
            recommendations.add(new SmeltingRecommendation(item, smeltsNeeded, estimatedSeconds));
        }
        return recommendations;
    }

    public static String describe(SmeltingRecommendation rec) {
        return rec.amountNeeded() + "x " + BuiltInRegistries.ITEM.getKey(rec.result()).getPath()
                + " (~" + rec.estimatedSeconds() + "s in a furnace)";
    }
}
