package ai.xelta.craft.generation;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;

public class ArchitecturalGrammarEngine {
    
    public record PlacedTemplate(AssetLibrary.AssetTemplate template, BlockPos origin) {}

    public static List<PlacedTemplate> applyGrammar(SemanticModel.SemanticObject semanticObj, BlockPos origin, int baseScale) {
        List<PlacedTemplate> placedTemplates = new ArrayList<>();
        String type = semanticObj.type().toLowerCase();

        // Types with a hand-authored prefab in BlueprintRetrievalEngine (gothic_arch,
        // watchtower_roof) are placed separately by VoxelEngine's prefab-injection pass — falling
        // through to `default: generic_cube` below as well used to stamp a placeholder cube
        // overlapping the real prefab at a different offset. Contributing nothing here instead
        // of duplicating/fighting with that pass.
        if (ai.xelta.craft.generation.BlueprintRetrievalEngine.hasPrefab(type)) {
            return placedTemplates;
        }

        int scale = (int) (baseScale * semanticObj.scale());
        if (scale < 3) scale = 3;

        // Position offsets
        int offsetX = 0;
        int offsetZ = 0;
        if (semanticObj.position() != null) {
            String pos = semanticObj.position().toLowerCase();
            if (pos.contains("left")) offsetX = -scale;
            if (pos.contains("right")) offsetX = scale;
            if (pos.contains("front") || pos.contains("forward")) offsetZ = -scale;
            if (pos.contains("back") || pos.contains("behind")) offsetZ = scale;
        }
        
        BlockPos objOrigin = origin.offset(offsetX, 0, offsetZ);

        switch (type) {
            case "castle":
            case "keep":
                // Castle Grammar: Base walls + 4 towers at corners.
                // Corner offsets now scale with `scale` — previously hardcoded to a fixed
                // ±5, so "tiny castle" and "massive castle" produced an identical footprint.
                int keepSpan = scale;
                placedTemplates.add(new PlacedTemplate(AssetLibrary.getTemplate("wall_segment"), objOrigin));
                placedTemplates.add(new PlacedTemplate(AssetLibrary.getTemplate("tower_base"), objOrigin.offset(-keepSpan, 0, -keepSpan)));
                placedTemplates.add(new PlacedTemplate(AssetLibrary.getTemplate("tower_base"), objOrigin.offset(keepSpan, 0, -keepSpan)));
                placedTemplates.add(new PlacedTemplate(AssetLibrary.getTemplate("tower_base"), objOrigin.offset(-keepSpan, 0, keepSpan)));
                placedTemplates.add(new PlacedTemplate(AssetLibrary.getTemplate("tower_base"), objOrigin.offset(keepSpan, 0, keepSpan)));
                break;
            case "house":
            case "building":
            case "cottage":
                // House Grammar: Walls + pitched roof.
                // Wall gap and roof centering now scale with `scale` — previously a fixed
                // 3-block gap regardless of requested size.
                int wallGap = Math.max(3, scale);
                placedTemplates.add(new PlacedTemplate(AssetLibrary.getTemplate("wall_segment"), objOrigin));
                placedTemplates.add(new PlacedTemplate(AssetLibrary.getTemplate("wall_segment"), objOrigin.offset(wallGap, 0, 0)));
                placedTemplates.add(new PlacedTemplate(AssetLibrary.getTemplate("roof_segment"), objOrigin.offset(wallGap / 2, 3, 0)));
                break;
            case "tower":
            case "watchtower":
            case "spire":
                // Tower Grammar: tower_base segments stacked (each is 5 blocks tall).
                // Segment count now scales with `scale` — previously always exactly 3
                // segments regardless of requested size, so towers couldn't be made taller.
                int segments = Math.max(2, scale / 3);
                for (int i = 0; i < segments; i++) {
                    placedTemplates.add(new PlacedTemplate(AssetLibrary.getTemplate("tower_base"), objOrigin.offset(0, i * 5, 0)));
                }
                break;
            case "wall":
            case "fence":
            case "barrier":
                placedTemplates.add(new PlacedTemplate(AssetLibrary.getTemplate("wall_segment"), objOrigin));
                break;
            case "roof":
                placedTemplates.add(new PlacedTemplate(AssetLibrary.getTemplate("roof_segment"), objOrigin));
                break;
            case "chair":
            case "seat":
            case "stool":
                placedTemplates.add(new PlacedTemplate(AssetLibrary.getTemplate("chair_base"), objOrigin));
                break;
            case "porch":
            case "veranda":
            case "patio":
                placedTemplates.add(new PlacedTemplate(AssetLibrary.getTemplate("porch"), objOrigin));
                break;
            case "chimney":
            case "smokestack":
                placedTemplates.add(new PlacedTemplate(AssetLibrary.getTemplate("chimney"), objOrigin));
                break;
            default:
                // Fallback to a small generic cube
                placedTemplates.add(new PlacedTemplate(AssetLibrary.getTemplate("generic_cube"), objOrigin));
                break;
        }

        return placedTemplates;
    }
}
