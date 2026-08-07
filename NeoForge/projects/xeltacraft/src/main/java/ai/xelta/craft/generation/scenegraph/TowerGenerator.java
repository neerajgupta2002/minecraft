package ai.xelta.craft.generation.scenegraph;

import ai.xelta.craft.generation.MaterialPlanner;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Resolves {@link TowerLayout}'s pure placements into real {@link BlockState}s, mirroring {@link HouseGenerator}. */
public class TowerGenerator {

    public static JsonArray generate(SceneGraph.Tower tower, String style, int maxFootprint) {
        JsonArray blueprint = new JsonArray();
        for (TowerLayout.Placement placement : TowerLayout.compute(tower, maxFootprint).values()) {
            appendBlock(blueprint, placement.x(), placement.y(), placement.z(),
                MaterialPlanner.getBlockForRole(placement.role(), style));
        }
        return blueprint;
    }

    static void appendBlock(JsonArray blueprint, int x, int y, int z, BlockState state) {
        JsonObject blockEntry = new JsonObject();
        blockEntry.addProperty("x", x);
        blockEntry.addProperty("y", y);
        blockEntry.addProperty("z", z);
        blockEntry.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        blueprint.add(blockEntry);
    }
}
