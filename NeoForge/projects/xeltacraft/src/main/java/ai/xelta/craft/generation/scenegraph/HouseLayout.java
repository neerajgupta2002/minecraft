package ai.xelta.craft.generation.scenegraph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure geometry core for {@link HouseGenerator} — computes WHERE each block goes and WHAT
 * ROLE it has (e.g. "wall_primary", "pillar_block"), with no dependency on Minecraft's block
 * registry. Kept separate specifically so this math (wall construction, door/window cutouts,
 * roof shape) can be unit tested directly — referencing real {@code Blocks.*} constants
 * requires a full FML bootstrap unavailable in a plain JUnit JVM (the same constraint that
 * shaped {@code CraftingDependencySolver}'s design).
 *
 * A {@link SceneGraph.Building} may carry more than one {@link SceneGraph.Room} — each room is
 * built independently at its own offset and merged into the shared placement map, so a building
 * with a primary room plus an offset annex produces a real L/T-shaped footprint instead of every
 * house being the same single rectangle. Each room gets its own roof, in the building's shared
 * {@link SceneGraph.RoofType}, sized to that room's own footprint.
 */
public class HouseLayout {

    public static final int MIN_SIZE = 3;
    public static final int MIN_HEIGHT = 2;
    public static final int MAX_HEIGHT = 6;
    public static final int MAX_PITCH = 6;
    /** Keeps an annex from being offset so far away it reads as a disconnected floating box. */
    public static final int MAX_OFFSET_MAGNITUDE = 40;

    public record Placement(int x, int y, int z, String role) {}

    /** Deterministic order (LinkedHashMap, keyed by packed position) so later writes at the same position win predictably. */
    public static Map<Long, Placement> compute(SceneGraph.Building buildingIn, int maxFootprint) {
        Map<Long, Placement> placements = new LinkedHashMap<>();

        SceneGraph.Roof roof = buildingIn.roof() != null ? buildingIn.roof() : new SceneGraph.Roof(SceneGraph.RoofType.GABLE, 3);
        int pitchHeight = clamp(roof.pitchHeight(), 1, MAX_PITCH);

        List<SceneGraph.Room> rooms = buildingIn.rooms();
        if (rooms == null || rooms.isEmpty()) return placements;

        // Later rooms in the list are processed after (and so win over) earlier ones at any
        // shared position — an annex flush against the main room cleanly cuts into its wall.
        for (SceneGraph.Room room : rooms) {
            int width  = clamp(room.width(),  MIN_SIZE, Math.max(MIN_SIZE, maxFootprint));
            int depth  = clamp(room.depth(),  MIN_SIZE, Math.max(MIN_SIZE, maxFootprint));
            int height = clamp(room.height(), MIN_HEIGHT, MAX_HEIGHT);
            int offsetX = clamp(room.offsetX(), -MAX_OFFSET_MAGNITUDE, MAX_OFFSET_MAGNITUDE);
            int offsetZ = clamp(room.offsetZ(), -MAX_OFFSET_MAGNITUDE, MAX_OFFSET_MAGNITUDE);

            placeWalls(placements, room, width, depth, height, offsetX, offsetZ);
            placeFloor(placements, width, depth, offsetX, offsetZ);
            placeRoof(placements, roof.type(), width, depth, height, pitchHeight, offsetX, offsetZ);
        }

        return placements;
    }

    // =========================================================
    // Walls — real per-wall length/height with door/window cutouts
    // =========================================================
    private static void placeWalls(Map<Long, Placement> placements, SceneGraph.Room room,
                                    int width, int depth, int height, int offsetX, int offsetZ) {
        // All 4 walls are always physically built regardless of which ones Gemini bothered to
        // list — the prompt tells it to omit sides with no door/window, so `room.walls()` is only
        // a source of openings, never the set of walls that physically exist. Building only the
        // listed walls would leave a house missing entire sides whenever a side has no opening.
        Map<SceneGraph.Direction, SceneGraph.Wall> byDirection = new java.util.EnumMap<>(SceneGraph.Direction.class);
        if (room != null && room.walls() != null) {
            for (SceneGraph.Wall w : room.walls()) byDirection.put(w.facing(), w);
        }

        for (SceneGraph.Direction facing : SceneGraph.Direction.values()) {
            SceneGraph.Wall wall = byDirection.getOrDefault(facing, new SceneGraph.Wall(facing, List.of()));
            int length = (facing == SceneGraph.Direction.NORTH || facing == SceneGraph.Direction.SOUTH)
                ? width : depth;

            for (int p = 0; p < length; p++) {
                int x, z;
                switch (facing) {
                    case NORTH -> { x = p; z = 0; }
                    case SOUTH -> { x = p; z = depth - 1; }
                    case WEST  -> { x = 0; z = p; }
                    default    -> { x = width - 1; z = p; } // EAST
                }

                for (int y = 0; y < height; y++) {
                    if (isOpening(wall, p, y, height)) continue; // leave as air — a real door/window cutout

                    boolean corner = (x == 0 || x == width - 1) && (z == 0 || z == depth - 1);
                    put(placements, offsetX + x, y, offsetZ + z, corner ? "pillar_block" : "wall_primary");
                }
            }
        }
    }

    /** Door: 1 wide, 2 tall, reaches the floor. Window: 1 wide, 1 tall, at roughly eye height. */
    static boolean isOpening(SceneGraph.Wall wall, int p, int y, int wallHeight) {
        if (wall.openings() == null) return false;
        for (SceneGraph.Opening opening : wall.openings()) {
            if (opening.offset() != p) continue;
            if (opening.type() == SceneGraph.OpeningType.DOOR && y <= 1) return true;
            if (opening.type() == SceneGraph.OpeningType.WINDOW && y == wallHeight / 2) return true;
        }
        return false;
    }

    // =========================================================
    // Floor — the interior only; the perimeter is already covered by wall base blocks
    // =========================================================
    private static void placeFloor(Map<Long, Placement> placements, int width, int depth, int offsetX, int offsetZ) {
        for (int x = 1; x <= width - 2; x++) {
            for (int z = 1; z <= depth - 2; z++) {
                put(placements, offsetX + x, 0, offsetZ + z, "wall_primary");
            }
        }
    }

    // =========================================================
    // Roof dispatch — GABLE/HIP/FLAT each get their own silhouette instead of every house
    // getting the same triangular cross-section regardless of what Gemini asked for.
    // =========================================================
    private static void placeRoof(Map<Long, Placement> placements, SceneGraph.RoofType type,
                                   int width, int depth, int wallHeight, int pitchHeight,
                                   int offsetX, int offsetZ) {
        switch (type) {
            case HIP  -> placeHipRoof(placements, width, depth, wallHeight, pitchHeight, offsetX, offsetZ);
            case FLAT -> placeFlatRoof(placements, width, depth, wallHeight, pitchHeight, offsetX, offsetZ);
            // CONE belongs to Tower, not a house — treat it (and any future unhandled value) as GABLE.
            default   -> placeGableRoof(placements, width, depth, wallHeight, pitchHeight, offsetX, offsetZ);
        }
    }

    /** Ridge along X at mid-depth, linear slope to each eave — the classic two-sided pitched roof. */
    private static void placeGableRoof(Map<Long, Placement> placements, int width, int depth,
                                        int wallHeight, int pitchHeight, int offsetX, int offsetZ) {
        int ridgeZ = depth / 2;
        // Each slope is normalized against ITS OWN distance to its eave, not a distance shared
        // between both sides — for an even `depth`, the ridge sits closer to one eave than the
        // other, and a single shared maxDist left the nearer eave never actually reaching
        // wallHeight (a floating roof edge with a gap beneath it).
        int frontMax = Math.max(1, ridgeZ);
        int backMax = Math.max(1, depth - 1 - ridgeZ);

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int distFromRidge = Math.abs(z - ridgeZ);
                int sideMax = (z <= ridgeZ) ? frontMax : backMax;
                int roofY = wallHeight + pitchHeight - Math.round(pitchHeight * (distFromRidge / (float) sideMax));
                boolean eave = (z == 0 || z == depth - 1 || x == 0 || x == width - 1);
                put(placements, offsetX + x, roofY, offsetZ + z, eave ? "roof_trim" : "roof_primary");
            }
        }
    }

    /**
     * Slopes down on all four sides to the eaves (no vertical gable ends). For a rectangular
     * footprint this naturally leaves a ridge LINE along the longer axis, not just a single
     * apex — {@code slopeUnits} caps out at {@code rise} before the two opposing slopes meet.
     */
    private static void placeHipRoof(Map<Long, Placement> placements, int width, int depth,
                                      int wallHeight, int pitchHeight, int offsetX, int offsetZ) {
        int maxRise = Math.max(1, Math.min(width, depth) / 2);
        int rise = Math.min(pitchHeight, maxRise);

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int distX = Math.min(x, width - 1 - x);
                int distZ = Math.min(z, depth - 1 - z);
                int slopeUnits = Math.min(Math.min(distX, distZ), rise);
                int roofY = wallHeight + slopeUnits;
                boolean eave = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
                put(placements, offsetX + x, roofY, offsetZ + z, eave ? "roof_trim" : "roof_primary");
            }
        }
    }

    /** A flat deck at wall-top height, with an optional low parapet ledge around the edge. */
    private static void placeFlatRoof(Map<Long, Placement> placements, int width, int depth,
                                       int wallHeight, int pitchHeight, int offsetX, int offsetZ) {
        // A flat roof has no pitch — pitchHeight is reused here as a small parapet height instead.
        int parapetHeight = Math.min(pitchHeight, 2);

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                boolean edge = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
                put(placements, offsetX + x, wallHeight, offsetZ + z, edge ? "roof_trim" : "roof_primary");
                if (edge) {
                    for (int py = 1; py <= parapetHeight; py++) {
                        put(placements, offsetX + x, wallHeight + py, offsetZ + z, "roof_trim");
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

    /** Packs local (x,y,z) into a long key — avoids constructing a BlockPos per lookup. */
    static long packPos(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0xFFF) << 30) | (z & 0x3FFFFFFFL);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
