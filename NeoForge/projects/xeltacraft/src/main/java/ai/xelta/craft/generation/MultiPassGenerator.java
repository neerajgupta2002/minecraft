package ai.xelta.craft.generation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MultiPassGenerator {

    public static class VoxelPlacement {
        public final BlockPos pos;
        public final BlockState state;

        public VoxelPlacement(BlockPos pos, BlockState state) {
            this.pos = pos;
            this.state = state;
        }
    }

    public static Map<BlockPos, VoxelPlacement> generate(SemanticModel.Scene scene, int baseScale) {
        // We use a map to handle overlapping blocks (e.g. roof on top of wall)
        Map<BlockPos, VoxelPlacement> worldGrid = new HashMap<>();

        if (scene == null || scene.objects() == null) return worldGrid;

        BlockPos origin = BlockPos.ZERO;

        for (SemanticModel.SemanticObject obj : scene.objects()) {
            
            // Pass 1: Layout & Structure (Grammar Engine)
            List<ArchitecturalGrammarEngine.PlacedTemplate> layout = ArchitecturalGrammarEngine.applyGrammar(obj, origin, baseScale);

            // Pass 2: Materialization & Decoration
            String style = scene.style();
            if (obj.material() != null && !obj.material().isBlank()) {
                style = obj.material(); // allow object to override scene style
            }

            for (ArchitecturalGrammarEngine.PlacedTemplate pt : layout) {
                if (pt.template() == null) continue;
                
                for (AssetLibrary.TemplateBlock tBlock : pt.template().blocks()) {
                    BlockPos worldPos = pt.origin().offset(tBlock.pos());
                    
                    // Resolve material based on role and style
                    BlockState blockState = MaterialPlanner.getBlockForRole(tBlock.role(), style);
                    
                    worldGrid.put(worldPos, new VoxelPlacement(worldPos, blockState));
                }
            }

            // Pass 3: Validation (Mock step - e.g. remove blocks below y=0 if not digging)
            worldGrid.entrySet().removeIf(entry -> entry.getKey().getY() < 0);
        }

        return worldGrid;
    }
}
