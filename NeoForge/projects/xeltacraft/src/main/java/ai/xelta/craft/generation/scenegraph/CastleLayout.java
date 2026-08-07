package ai.xelta.craft.generation.scenegraph;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure geometry core for a real castle — a curtain wall perimeter with one gate, and a real
 * cylindrical {@link TowerLayout} tower at each of the 4 corners. Replaces the old fixed
 * AssetLibrary "wall_segment" + 4 "tower_base" combination, whose corner offsets used to be
 * hardcoded to a fixed ±5 regardless of requested size (fixed in the P0 pass) and whose towers
 * were a crude blocky 12-block faux-ring, not a real cylinder.
 */
public class CastleLayout {

    public static final int MIN_SIZE = 7;
    public static final int MIN_WALL_HEIGHT = 2;
    public static final int MAX_WALL_HEIGHT = 8;

    public record Placement(int x, int y, int z, String role) {}

    public static Map<Long, Placement> compute(SceneGraph.Castle castle, int maxFootprint) {
        int width = clamp(castle.width(), MIN_SIZE, Math.max(MIN_SIZE, maxFootprint));
        int depth = clamp(castle.depth(), MIN_SIZE, Math.max(MIN_SIZE, maxFootprint));
        int wallHeight = clamp(castle.wallHeight(), MIN_WALL_HEIGHT, MAX_WALL_HEIGHT);
        int towerRadius = clamp(castle.towerRadius(), TowerLayout.MIN_RADIUS, TowerLayout.MAX_RADIUS);
        int towerHeight = clamp(castle.towerHeight(), TowerLayout.MIN_HEIGHT, TowerLayout.MAX_HEIGHT);
        SceneGraph.Direction gateWall = castle.gateWall() != null ? castle.gateWall() : SceneGraph.Direction.SOUTH;

        Map<Long, Placement> placements = new LinkedHashMap<>();
        placeCurtainWall(placements, width, depth, wallHeight, towerRadius, gateWall, castle.gateOffset());
        placeCornerTowers(placements, width, depth, towerRadius, towerHeight, maxFootprint);
        return placements;
    }

    // =========================================================
    // Curtain wall — runs BETWEEN corner towers (not through them), one gate opening
    // =========================================================
    private static void placeCurtainWall(Map<Long, Placement> placements, int width, int depth, int wallHeight,
                                          int towerRadius, SceneGraph.Direction gateWall, int gateOffset) {
        for (SceneGraph.Direction facing : SceneGraph.Direction.values()) {
            boolean horizontal = facing == SceneGraph.Direction.NORTH || facing == SceneGraph.Direction.SOUTH;
            int runLength = horizontal ? width : depth;
            int start = towerRadius;
            int end = runLength - 1 - towerRadius; // inclusive
            if (start > end) continue; // corner towers touch/overlap on this side — no straight run to build

            boolean isGateWall = facing == gateWall;
            int gateP = clamp(start + gateOffset, start, Math.max(start, end - 1)); // 2-wide gate, kept in-bounds

            for (int p = start; p <= end; p++) {
                int x, z;
                switch (facing) {
                    case NORTH -> { x = p; z = 0; }
                    case SOUTH -> { x = p; z = depth - 1; }
                    case WEST  -> { x = 0; z = p; }
                    default    -> { x = width - 1; z = p; } // EAST
                }

                for (int y = 0; y < wallHeight; y++) {
                    boolean isGate = isGateWall && (p == gateP || p == gateP + 1) && y <= 2;
                    if (isGate) continue; // 2 wide, 3 tall gate opening — reaches the ground

                    put(placements, x, y, z, "wall_primary");
                }
            }
        }
    }

    // =========================================================
    // Corner towers — real cylindrical geometry, reused from TowerLayout rather than duplicated
    // =========================================================
    private static void placeCornerTowers(Map<Long, Placement> placements, int width, int depth,
                                           int towerRadius, int towerHeight, int maxFootprint) {
        SceneGraph.Tower tower = new SceneGraph.Tower(towerRadius, towerHeight, true, Math.max(1, towerRadius));
        int[][] corners = {{0, 0}, {width - 1, 0}, {0, depth - 1}, {width - 1, depth - 1}};

        for (int[] corner : corners) {
            for (Map.Entry<Long, TowerLayout.Placement> entry : TowerLayout.compute(tower, maxFootprint, corner[0], corner[1]).entrySet()) {
                TowerLayout.Placement tp = entry.getValue();
                placements.put(entry.getKey(), new Placement(tp.x(), tp.y(), tp.z(), tp.role()));
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
