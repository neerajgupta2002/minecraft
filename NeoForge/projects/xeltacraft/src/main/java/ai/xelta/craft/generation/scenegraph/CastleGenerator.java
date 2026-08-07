package ai.xelta.craft.generation.scenegraph;

import ai.xelta.craft.generation.MaterialPlanner;
import com.google.gson.JsonArray;

/** Resolves {@link CastleLayout}'s pure placements into real BlockStates, mirroring {@link HouseGenerator}. */
public class CastleGenerator {

    public static JsonArray generate(SceneGraph.Castle castle, String style, int maxFootprint) {
        JsonArray blueprint = new JsonArray();
        for (CastleLayout.Placement placement : CastleLayout.compute(castle, maxFootprint).values()) {
            TowerGenerator.appendBlock(blueprint, placement.x(), placement.y(), placement.z(),
                MaterialPlanner.getBlockForRole(placement.role(), style));
        }
        return blueprint;
    }
}
