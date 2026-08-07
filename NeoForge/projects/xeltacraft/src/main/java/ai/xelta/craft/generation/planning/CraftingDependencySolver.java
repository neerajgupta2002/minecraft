package ai.xelta.craft.generation.planning;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursively resolves a set of target items into the raw materials required to obtain them,
 * expanding craftable/smeltable items into their ingredients one recipe level at a time until
 * everything bottoms out at a raw material (no known recipe) or the player's inventory.
 *
 * The algorithm itself is generic over the item-key type {@code T} so it has no dependency on
 * Minecraft's registries and can be unit tested directly. {@link RecipeKnowledgeBase} is the
 * production adapter that builds {@code Map<Item, ...>} indices from the real
 * {@link net.minecraft.world.item.crafting.RecipeManager}.
 */
public class CraftingDependencySolver {

    private static final int MAX_DEPTH = 12;

    public record IngredientOption<T>(List<T> acceptableItems) {}

    public record CraftingRecipeInfo<T>(T result, int resultCount, List<IngredientOption<T>> ingredientSlots) {}

    public record SmeltingRecipeInfo<T>(T result, int resultCount, List<T> acceptableInputs, int cookingTimeTicks) {}

    public record CraftingStep<T>(T result, int craftsPerformed, int itemsProduced) {}

    public record ResourcePlan<T>(
            Map<T, Integer> missingRawMaterials,
            List<CraftingStep<T>> craftingSteps,
            Map<T, Integer> smeltingNeeded
    ) {
        public boolean isSufficient() {
            return missingRawMaterials.isEmpty();
        }
    }

    /** Production entry point: builds recipe indices from a real level, then resolves. */
    public static ResourcePlan<Item> resolve(Level level, Map<Item, Integer> targets, Map<Item, Integer> inventory) {
        return resolve(
                RecipeKnowledgeBase.indexCraftingRecipes(level),
                RecipeKnowledgeBase.indexSmeltingRecipes(level),
                targets, inventory);
    }

    /**
     * Core algorithm, independent of Minecraft's registries — takes pre-built recipe indices
     * so it can be unit tested without a running server.
     */
    public static <T> ResourcePlan<T> resolve(
            Map<T, CraftingRecipeInfo<T>> craftingIndex,
            Map<T, SmeltingRecipeInfo<T>> smeltingIndex,
            Map<T, Integer> targets,
            Map<T, Integer> inventory
    ) {
        Map<T, Integer> missing = new HashMap<>();
        List<CraftingStep<T>> steps = new ArrayList<>();
        Map<T, Integer> smelting = new HashMap<>();

        for (Map.Entry<T, Integer> target : targets.entrySet()) {
            solve(target.getKey(), target.getValue(), inventory, craftingIndex, smeltingIndex,
                    missing, steps, smelting, new HashSet<>(), 0);
        }

        return new ResourcePlan<>(missing, steps, smelting);
    }

    private static <T> void solve(
            T item, int needed,
            Map<T, Integer> inventory,
            Map<T, CraftingRecipeInfo<T>> craftingIndex,
            Map<T, SmeltingRecipeInfo<T>> smeltingIndex,
            Map<T, Integer> missing,
            List<CraftingStep<T>> steps,
            Map<T, Integer> smelting,
            Set<T> visiting,
            int depth
    ) {
        if (needed <= 0) return;

        int have = inventory.getOrDefault(item, 0);
        int useFromInventory = Math.min(have, needed);
        if (useFromInventory > 0) {
            inventory.put(item, have - useFromInventory);
        }
        int stillNeeded = needed - useFromInventory;
        if (stillNeeded <= 0) return;

        // Cycle/depth guard — treat as raw material rather than recursing forever.
        if (depth >= MAX_DEPTH || visiting.contains(item)) {
            missing.merge(item, stillNeeded, Integer::sum);
            return;
        }

        CraftingRecipeInfo<T> craftingRecipe = craftingIndex.get(item);
        if (craftingRecipe != null) {
            visiting.add(item);
            int craftsNeeded = ceilDiv(stillNeeded, craftingRecipe.resultCount());
            for (IngredientOption<T> slot : craftingRecipe.ingredientSlots()) {
                T chosen = pickBestOption(slot.acceptableItems(), inventory);
                solve(chosen, craftsNeeded, inventory, craftingIndex, smeltingIndex, missing, steps, smelting, visiting, depth + 1);
            }
            visiting.remove(item);
            int totalProduced = craftsNeeded * craftingRecipe.resultCount();
            steps.add(new CraftingStep<>(item, craftsNeeded, totalProduced));
            creditSurplus(item, totalProduced, stillNeeded, inventory);
            return;
        }

        SmeltingRecipeInfo<T> smeltingRecipe = smeltingIndex.get(item);
        if (smeltingRecipe != null) {
            visiting.add(item);
            int smeltsNeeded = ceilDiv(stillNeeded, smeltingRecipe.resultCount());
            T chosenInput = pickBestOption(smeltingRecipe.acceptableInputs(), inventory);
            solve(chosenInput, smeltsNeeded, inventory, craftingIndex, smeltingIndex, missing, steps, smelting, visiting, depth + 1);
            visiting.remove(item);
            smelting.merge(item, smeltsNeeded, Integer::sum);
            creditSurplus(item, smeltsNeeded * smeltingRecipe.resultCount(), stillNeeded, inventory);
            return;
        }

        // No known recipe — this is a raw material that must be obtained (mined/gathered) directly.
        missing.merge(item, stillNeeded, Integer::sum);
    }

    /** Prefer an option the player already has some of, otherwise fall back to the first acceptable item. */
    private static <T> T pickBestOption(List<T> options, Map<T, Integer> inventory) {
        for (T option : options) {
            if (inventory.getOrDefault(option, 0) > 0) return option;
        }
        return options.get(0);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /**
     * Crafting/smelting happens in whole batches, so a batch can produce more than what was
     * strictly needed (e.g. 1 log -> 4 planks when only 1 plank was required). Credit the
     * leftover back to the virtual inventory so sibling branches in the same plan can reuse it
     * instead of re-deriving it from raw materials.
     */
    private static <T> void creditSurplus(T item, int produced, int neededHere, Map<T, Integer> inventory) {
        int surplus = produced - neededHere;
        if (surplus > 0) {
            inventory.merge(item, surplus, Integer::sum);
        }
    }
}
