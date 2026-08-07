package ai.xelta.craft.generation.planning;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Minecraft's real {@link RecipeManager} so the build-planning pipeline knows
 * actual crafting/smelting recipes instead of a hardcoded substitution table.
 */
public class RecipeKnowledgeBase {

    /**
     * Indexes every loaded crafting recipe by its result item. Built fresh per call since
     * this is only invoked from pre-flight planning (not per-tick), and recipes can change
     * on datapack reload.
     */
    public static Map<Item, CraftingDependencySolver.CraftingRecipeInfo<Item>> indexCraftingRecipes(Level level) {
        Map<Item, CraftingDependencySolver.CraftingRecipeInfo<Item>> index = new HashMap<>();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe.getType() != RecipeType.CRAFTING || !(recipe instanceof CraftingRecipe)) continue;

            ItemStack resultStack = recipe.getResultItem(level.registryAccess());
            if (resultStack.isEmpty()) continue;
            Item result = resultStack.getItem();
            if (index.containsKey(result)) continue; // keep the first recipe found for a given result

            List<CraftingDependencySolver.IngredientOption<Item>> slots = new ArrayList<>();
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) continue;
                List<Item> options = new ArrayList<>();
                for (ItemStack stack : ingredient.getItems()) {
                    if (!stack.isEmpty()) options.add(stack.getItem());
                }
                if (!options.isEmpty()) slots.add(new CraftingDependencySolver.IngredientOption<>(options));
            }
            if (slots.isEmpty()) continue; // shapeless/special recipes with no resolvable ingredients

            index.put(result, new CraftingDependencySolver.CraftingRecipeInfo<>(result, resultStack.getCount(), slots));
        }
        return index;
    }

    /** Indexes every loaded furnace-smelting recipe by its result item. */
    public static Map<Item, CraftingDependencySolver.SmeltingRecipeInfo<Item>> indexSmeltingRecipes(Level level) {
        Map<Item, CraftingDependencySolver.SmeltingRecipeInfo<Item>> index = new HashMap<>();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe.getType() != RecipeType.SMELTING || !(recipe instanceof AbstractCookingRecipe cooking)) continue;

            ItemStack resultStack = recipe.getResultItem(level.registryAccess());
            if (resultStack.isEmpty()) continue;
            Item result = resultStack.getItem();
            if (index.containsKey(result)) continue;

            List<Item> inputs = new ArrayList<>();
            for (Ingredient ingredient : recipe.getIngredients()) {
                for (ItemStack stack : ingredient.getItems()) {
                    if (!stack.isEmpty()) inputs.add(stack.getItem());
                }
            }
            if (inputs.isEmpty()) continue;

            index.put(result, new CraftingDependencySolver.SmeltingRecipeInfo<>(
                    result, resultStack.getCount(), inputs, cooking.getCookingTime()));
        }
        return index;
    }
}
