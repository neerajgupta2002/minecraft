package ai.xelta.craft.generation.scenegraph;

import ai.xelta.craft.generation.MaterialPlanner;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Resolves {@link HouseLayout}'s pure (position, role) placements into real {@link BlockState}s
 * via {@link MaterialPlanner} and serializes them to the JSON blueprint format the rest of the
 * pipeline expects. The actual geometry — walls, door/window cutouts, floor, gable roof — lives
 * in {@link HouseLayout}, which has no Minecraft registry dependency and is unit tested directly.
 */
public class HouseGenerator {

    public static JsonArray generate(SceneGraph.Building building, String style, int maxFootprint) {
        JsonArray blueprint = new JsonArray();

        for (HouseLayout.Placement placement : HouseLayout.compute(building, maxFootprint).values()) {
            BlockState state = MaterialPlanner.getBlockForRole(placement.role(), style);

            JsonObject blockEntry = new JsonObject();
            blockEntry.addProperty("x", placement.x());
            blockEntry.addProperty("y", placement.y());
            blockEntry.addProperty("z", placement.z());
            blockEntry.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            blueprint.add(blockEntry);
        }

        return blueprint;
    }
}
