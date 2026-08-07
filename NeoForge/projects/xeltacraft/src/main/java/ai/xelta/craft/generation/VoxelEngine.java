package ai.xelta.craft.generation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class VoxelEngine {

    public static JsonArray generateBlueprint(SemanticModel.Scene scene, int blockSize) {
        JsonArray blueprint = new JsonArray();
        if (scene == null || scene.objects() == null) return blueprint;

        // Generate procedural geometry
        Map<BlockPos, MultiPassGenerator.VoxelPlacement> worldGrid = MultiPassGenerator.generate(scene, blockSize);

        // Inject Macro Blueprints (Prefabs)
        for (SemanticModel.SemanticObject obj : scene.objects()) {
            String possiblePrefabId = obj.type();
            if ("prefab".equalsIgnoreCase(possiblePrefabId)) {
                possiblePrefabId = obj.material();
            }
            
            if (BlueprintRetrievalEngine.hasPrefab(possiblePrefabId)) {
                BlockPos offset = BlockPos.ZERO;
                if (obj.position() != null) {
                    offset = switch (obj.position().toLowerCase()) {
                        case "north" -> new BlockPos(0, 0, -blockSize);
                        case "south" -> new BlockPos(0, 0, blockSize);
                        case "east" -> new BlockPos(blockSize, 0, 0);
                        case "west" -> new BlockPos(-blockSize, 0, 0);
                        default -> BlockPos.ZERO;
                    };
                }
                BlueprintRetrievalEngine.injectPrefab(possiblePrefabId, offset, scene.style(), blueprint);
            }
        }

        for (MultiPassGenerator.VoxelPlacement placement : worldGrid.values()) {
            JsonObject blockEntry = new JsonObject();
            blockEntry.addProperty("x", placement.pos.getX());
            blockEntry.addProperty("y", placement.pos.getY());
            blockEntry.addProperty("z", placement.pos.getZ());
            
            String blockId = BuiltInRegistries.BLOCK.getKey(placement.state.getBlock()).toString();
            blockEntry.addProperty("block", blockId);
            
            blueprint.add(blockEntry);
        }

        return blueprint;
    }
}
