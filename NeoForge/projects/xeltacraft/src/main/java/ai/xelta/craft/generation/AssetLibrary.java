package ai.xelta.craft.generation;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AssetLibrary {
    public record TemplateBlock(BlockPos pos, String role) {}
    public record AssetTemplate(String name, List<TemplateBlock> blocks) {}

    private static final Map<String, AssetTemplate> templates = new HashMap<>();

    static {
        // Mock a few handcrafted blueprints (Asset Retrieval / RAG simulation)
        
        // 1. A basic medieval wall segment (3x3)
        List<TemplateBlock> wallSegment = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y < 3; y++) {
                wallSegment.add(new TemplateBlock(new BlockPos(x, y, 0), "wall_primary"));
            }
        }
        // Add a pillar on one side
        for (int y = 0; y < 4; y++) {
            wallSegment.add(new TemplateBlock(new BlockPos(-2, y, 0), "pillar_block"));
        }
        templates.put("wall_segment", new AssetTemplate("wall_segment", wallSegment));

        // 2. A simple pitched roof section (5x3)
        List<TemplateBlock> roofSegment = new ArrayList<>();
        for (int x = -2; x <= 2; x++) {
            roofSegment.add(new TemplateBlock(new BlockPos(x, 0, -1), "roof_trim"));
            roofSegment.add(new TemplateBlock(new BlockPos(x, 0, 1), "roof_trim"));
            roofSegment.add(new TemplateBlock(new BlockPos(x, 1, 0), "roof_primary"));
        }
        templates.put("roof_segment", new AssetTemplate("roof_segment", roofSegment));
        
        // 3. A tower base (circle-ish 5x5)
        List<TemplateBlock> towerBase = new ArrayList<>();
        for (int y = 0; y < 5; y++) {
            towerBase.add(new TemplateBlock(new BlockPos(-2, y, -1), "wall_primary"));
            towerBase.add(new TemplateBlock(new BlockPos(-2, y, 0), "wall_primary"));
            towerBase.add(new TemplateBlock(new BlockPos(-2, y, 1), "wall_primary"));
            
            towerBase.add(new TemplateBlock(new BlockPos(2, y, -1), "wall_primary"));
            towerBase.add(new TemplateBlock(new BlockPos(2, y, 0), "wall_primary"));
            towerBase.add(new TemplateBlock(new BlockPos(2, y, 1), "wall_primary"));
            
            towerBase.add(new TemplateBlock(new BlockPos(-1, y, -2), "wall_primary"));
            towerBase.add(new TemplateBlock(new BlockPos(0, y, -2), "wall_primary"));
            towerBase.add(new TemplateBlock(new BlockPos(1, y, -2), "wall_primary"));
            
            towerBase.add(new TemplateBlock(new BlockPos(-1, y, 2), "wall_primary"));
            towerBase.add(new TemplateBlock(new BlockPos(0, y, 2), "wall_primary"));
            towerBase.add(new TemplateBlock(new BlockPos(1, y, 2), "wall_primary"));
        }
        templates.put("tower_base", new AssetTemplate("tower_base", towerBase));
        // 4. A chair base (single block)
        List<TemplateBlock> chairBase = new ArrayList<>();
        chairBase.add(new TemplateBlock(new BlockPos(0, 0, 0), "stair_block"));
        templates.put("chair_base", new AssetTemplate("chair_base", chairBase));
        
        // 5. Generic cube fallback (2x2)
        List<TemplateBlock> genericCube = new ArrayList<>();
        for (int x = -1; x <= 0; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = -1; z <= 0; z++) {
                    genericCube.add(new TemplateBlock(new BlockPos(x, y, z), "wall_primary"));
                }
            }
        }
        templates.put("generic_cube", new AssetTemplate("generic_cube", genericCube));

        // 6. Porch — small covered deck with two posts and a flat roof (3x2 footprint)
        List<TemplateBlock> porch = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = 0; z <= 1; z++) {
                porch.add(new TemplateBlock(new BlockPos(x, 0, z), "wall_primary")); // deck
            }
        }
        for (int y = 1; y <= 2; y++) {
            porch.add(new TemplateBlock(new BlockPos(-1, y, 1), "pillar_block"));
            porch.add(new TemplateBlock(new BlockPos(1, y, 1), "pillar_block"));
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = 0; z <= 1; z++) {
                porch.add(new TemplateBlock(new BlockPos(x, 3, z), "roof_trim"));
            }
        }
        templates.put("porch", new AssetTemplate("porch", porch));

        // 7. Chimney — a simple vertical stack with a capstone
        List<TemplateBlock> chimney = new ArrayList<>();
        for (int y = 0; y <= 4; y++) {
            chimney.add(new TemplateBlock(new BlockPos(0, y, 0), "pillar_block"));
        }
        chimney.add(new TemplateBlock(new BlockPos(0, 5, 0), "roof_trim"));
        templates.put("chimney", new AssetTemplate("chimney", chimney));
    }

    public static AssetTemplate getTemplate(String name) {
        return templates.get(name);
    }
}
