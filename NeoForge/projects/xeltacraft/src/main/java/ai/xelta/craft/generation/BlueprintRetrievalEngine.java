package ai.xelta.craft.generation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

public class BlueprintRetrievalEngine {

    // Maps a prefab_id to a pre-defined array of relative block positions and generic roles
    private static final Map<String, PrefabTemplate> PREFABS = new HashMap<>();

    static {
        // Example: A basic watchtower roof (pyramid shape)
        PrefabTemplate watchtowerRoof = new PrefabTemplate();
        // Base layer (3x3)
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                watchtowerRoof.addBlock(x, 0, z, "roof_primary");
            }
        }
        // Top tip
        watchtowerRoof.addBlock(0, 1, 0, "roof_trim");
        PREFABS.put("watchtower_roof", watchtowerRoof);

        // Example: Gothic Arch (simple 3-block wide arch)
        PrefabTemplate gothicArch = new PrefabTemplate();
        gothicArch.addBlock(-1, 0, 0, "pillar_block");
        gothicArch.addBlock(1, 0, 0, "pillar_block");
        gothicArch.addBlock(-1, 1, 0, "pillar_block");
        gothicArch.addBlock(1, 1, 0, "pillar_block");
        gothicArch.addBlock(-1, 2, 0, "pillar_block");
        gothicArch.addBlock(1, 2, 0, "pillar_block");
        gothicArch.addBlock(-1, 3, 0, "roof_trim"); // Corbel
        gothicArch.addBlock(1, 3, 0, "roof_trim");  // Corbel
        gothicArch.addBlock(0, 4, 0, "wall_primary"); // Keystone
        PREFABS.put("gothic_arch", gothicArch);
    }

    public static boolean hasPrefab(String prefabId) {
        return prefabId != null && PREFABS.containsKey(prefabId.toLowerCase());
    }

    /**
     * Injects the requested prefab into the provided JSON array at the given offset.
     * The blocks use the provided style to resolve specific BlockStates (e.g., 'medieval' -> stone bricks).
     */
    public static void injectPrefab(String prefabId, BlockPos offset, String style, JsonArray targetArray) {
        PrefabTemplate template = PREFABS.get(prefabId.toLowerCase());
        if (template == null) return;

        for (PrefabBlock pBlock : template.blocks) {
            JsonObject entry = new JsonObject();
            entry.addProperty("x", pBlock.x + offset.getX());
            entry.addProperty("y", pBlock.y + offset.getY());
            entry.addProperty("z", pBlock.z + offset.getZ());
            
            // Resolve the generic role (e.g. "roof_primary") into an actual block based on style
            String actualBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(MaterialPlanner.getBlockForRole(pBlock.role, style).getBlock())
                    .toString();
                    
            entry.addProperty("block", actualBlock);
            targetArray.add(entry);
        }
    }

    private static class PrefabTemplate {
        final java.util.List<PrefabBlock> blocks = new java.util.ArrayList<>();
        void addBlock(int x, int y, int z, String role) {
            blocks.add(new PrefabBlock(x, y, z, role));
        }
    }

    private record PrefabBlock(int x, int y, int z, String role) {}
}
