package ai.xelta.craft.generation.scenegraph;

import java.util.List;

/**
 * A real hierarchical building representation — Building -> Room -> Wall -> Opening,
 * plus a Roof — as opposed to {@link ai.xelta.craft.generation.SemanticModel.SemanticObject},
 * which is a flat {type, position, scale, material} record with nowhere to put architectural
 * detail like doors, windows, or room dimensions.
 *
 * This is additive, not a replacement: {@code SemanticModel} still drives every structure type
 * except "house", "tower", and "castle", which route through this richer graph
 * (see {@link HouseGenerator}, {@link TowerGenerator}, {@link CastleGenerator}).
 */
public class SceneGraph {

    public enum Direction { NORTH, SOUTH, EAST, WEST }
    public enum OpeningType { DOOR, WINDOW }
    /** CONE is reserved for {@link Tower}, which has its own dedicated roof algorithm and doesn't use this enum. */
    public enum RoofType { GABLE, HIP, FLAT, CONE }

    /** A cut into a wall. Door: 1 wide x 2 tall, reaches the floor. Window: 1 wide x 1 tall, at mid-height. */
    public record Opening(OpeningType type, int offset) {}

    /** One side of a room. Length/height are derived from the owning Room, not restated here. */
    public record Wall(Direction facing, List<Opening> openings) {}

    /**
     * {@code offsetX}/{@code offsetZ} place this room's own (0,0) corner relative to the
     * building's origin — a second room offset flush against the first produces an L/T-shaped
     * footprint instead of every house being a single rectangle. Zero for a standalone room.
     */
    public record Room(String name, int width, int depth, int height, List<Wall> walls, int offsetX, int offsetZ) {}

    /** {@code pitchHeight} is the roof's rise above the wall top for GABLE/HIP; parapet height for FLAT. */
    public record Roof(RoofType type, int pitchHeight) {}

    /** {@code rooms} may hold more than one entry — a primary room plus an optional attached annex. */
    public record Building(int width, int depth, List<Room> rooms, Roof roof) {}

    /** A real cylindrical tower — replaces the old fixed 12-block faux-ring template. */
    public record Tower(int radius, int height, boolean crenellated, int roofHeight) {}

    /** A curtain wall perimeter with one gate, and a real {@link Tower} at each of its 4 corners. */
    public record Castle(int width, int depth, int wallHeight, int towerRadius, int towerHeight,
                          Direction gateWall, int gateOffset) {}
}
