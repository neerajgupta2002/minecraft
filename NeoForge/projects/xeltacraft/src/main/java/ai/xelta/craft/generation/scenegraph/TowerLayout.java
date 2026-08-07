package ai.xelta.craft.generation.scenegraph;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure geometry core for a real cylindrical tower — replaces the old fixed 12-block faux-ring
 * template (AssetLibrary's "tower_base", stacked a fixed number of times regardless of requested
 * size). Radius, height, an optional crenellated parapet, and a conical roof are all genuinely
 * parametric here. No Minecraft registry dependency, same reasoning as {@link HouseLayout}.
 */
public class TowerLayout {

    public static final int MIN_RADIUS = 2;
    public static final int MAX_RADIUS = 8;
    public static final int MIN_HEIGHT = 3;
    public static final int MAX_HEIGHT = 30;
    public static final int MAX_ROOF_HEIGHT = 10;

    /** Number of merlon/gap arc-pairs around the ring — angle-based, so it scales with any radius. */
    private static final int CRENEL_SEGMENTS = 8;

    public record Placement(int x, int y, int z, String role) {}

    public static Map<Long, Placement> compute(SceneGraph.Tower tower, int maxFootprint) {
        return compute(tower, maxFootprint, 0, 0);
    }

    /** centerX/centerZ let CastleLayout reuse this for corner towers without a separate translate pass. */
    public static Map<Long, Placement> compute(SceneGraph.Tower tower, int maxFootprint, int centerX, int centerZ) {
        int radius = clamp(tower.radius(), MIN_RADIUS, Math.max(MIN_RADIUS, maxFootprint / 2));
        int height = clamp(tower.height(), MIN_HEIGHT, MAX_HEIGHT);
        int roofHeight = clamp(tower.roofHeight(), 1, MAX_ROOF_HEIGHT);

        Map<Long, Placement> placements = new LinkedHashMap<>();
        placeCylinderWalls(placements, radius, height, centerX, centerZ);
        if (tower.crenellated()) {
            placeCrenellations(placements, radius, height, centerX, centerZ);
        }
        placeFloor(placements, radius, centerX, centerZ);
        placeConeRoof(placements, radius, height, roofHeight, tower.crenellated(), centerX, centerZ);
        return placements;
    }

    // =========================================================
    // Hollow cylindrical wall
    // =========================================================
    private static void placeCylinderWalls(Map<Long, Placement> placements, int radius, int height, int cx, int cz) {
        int outerSq = radius * radius;
        int innerSq = Math.max(0, (radius - 1) * (radius - 1));

        for (int y = 0; y < height; y++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int distSq = dx * dx + dz * dz;
                    if (distSq > outerSq || distSq < innerSq) continue; // ring only — hollow interior
                    put(placements, cx + dx, y, cz + dz, "wall_primary");
                }
            }
        }
    }

    // =========================================================
    // Crenellated parapet — one row above the wall top, alternating merlon/gap by angle
    // =========================================================
    private static void placeCrenellations(Map<Long, Placement> placements, int radius, int height, int cx, int cz) {
        int outerSq = radius * radius;
        int innerSq = Math.max(0, (radius - 1) * (radius - 1));
        int y = height;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int distSq = dx * dx + dz * dz;
                if (distSq > outerSq || distSq < innerSq) continue;
                if ((dx == 0) && (dz == 0)) continue;

                double angle = Math.atan2(dz, dx) + Math.PI; // 0..2*PI
                double normalized = angle / (2 * Math.PI);   // 0..1
                int arc = (int) Math.floor(normalized * CRENEL_SEGMENTS * 2);
                if (arc % 2 != 0) continue; // odd arcs are the gaps — merlons only on even arcs

                put(placements, cx + dx, y, cz + dz, "pillar_block");
            }
        }
    }

    // =========================================================
    // Floor — solid disk filling the tower's hollow interior
    // =========================================================
    private static void placeFloor(Map<Long, Placement> placements, int radius, int cx, int cz) {
        int innerSq = Math.max(0, (radius - 1) * (radius - 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz < innerSq) {
                    put(placements, cx + dx, 0, cz + dz, "wall_primary");
                }
            }
        }
    }

    // =========================================================
    // Cone roof — solid disk per layer, radius shrinking linearly to a peak
    // =========================================================
    private static void placeConeRoof(Map<Long, Placement> placements, int radius, int height,
                                       int roofHeight, boolean crenellated, int cx, int cz) {
        int baseY = crenellated ? height + 1 : height; // sit above the parapet if there is one

        for (int i = 0; i < roofHeight; i++) {
            // progress must reach exactly 1.0 on the LAST layer (i = roofHeight-1) so the roof
            // actually narrows to a true single-block peak instead of stopping at radius 1.
            float progress = i / (float) Math.max(1, roofHeight - 1);
            int layerRadius = Math.round(radius * (1 - progress));
            int y = baseY + i;
            String role = (i == 0) ? "roof_trim" : "roof_primary";

            if (layerRadius <= 0) {
                put(placements, cx, y, cz, role);
                continue;
            }
            int layerSq = layerRadius * layerRadius;
            for (int dx = -layerRadius; dx <= layerRadius; dx++) {
                for (int dz = -layerRadius; dz <= layerRadius; dz++) {
                    if (dx * dx + dz * dz <= layerSq) {
                        put(placements, cx + dx, y, cz + dz, role);
                    }
                }
            }
        }
    }

    // =========================================================
    // Helpers
    // =========================================================
    private static void put(Map<Long, Placement> placements, int x, int y, int z, String role) {
        placements.put(packPos(x, y, z), new Placement(x, y, z, role));
    }

    static long packPos(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0xFFF) << 30) | (z & 0x3FFFFFFFL);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
