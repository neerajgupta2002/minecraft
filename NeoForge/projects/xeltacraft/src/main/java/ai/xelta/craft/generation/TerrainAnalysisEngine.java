package ai.xelta.craft.generation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

public class TerrainAnalysisEngine {

    /**
     * Scans the terrain in a block size radius around the origin
     * and returns a natural language description for the AI.
     */
    public static String analyzeTerrain(Level level, BlockPos origin, int blockSize) {
        if (level == null) return "Terrain: Unknown";

        int minZ = origin.getZ() - blockSize;
        int maxZ = origin.getZ() + blockSize;
        int minX = origin.getX() - blockSize;
        int maxX = origin.getX() + blockSize;

        int highestY = Integer.MIN_VALUE;
        int lowestY = Integer.MAX_VALUE;
        long totalY = 0;
        int count = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                
                if (y > highestY) highestY = y;
                if (y < lowestY) lowestY = y;
                totalY += y;
                count++;
            }
        }

        if (count == 0) return "Terrain: Unknown";

        int avgY = (int) (totalY / count);
        int elevationDelta = highestY - lowestY;

        String description = "Terrain at origin (" + origin.getX() + ", " + origin.getZ() + "): ";
        
        if (elevationDelta <= 2) {
            description += "Flat plains (Avg Y=" + avgY + ").";
        } else if (elevationDelta <= 5) {
            description += "Uneven or rolling hills (Min Y=" + lowestY + ", Max Y=" + highestY + ").";
        } else {
            description += "Steep slope or mountainous (Min Y=" + lowestY + ", Max Y=" + highestY + "). Structural supports required.";
        }

        return description;
    }
}
