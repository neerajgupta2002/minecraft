package ai.xelta.craft.generation;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;

public class PrimitiveLibrary {

    public interface Primitive {
        List<BlockPos> generate(BlockPos origin);
    }

    public static class Cube implements Primitive {
        private final int width, height, depth;

        public Cube(int width, int height, int depth) {
            this.width = width;
            this.height = height;
            this.depth = depth;
        }

        @Override
        public List<BlockPos> generate(BlockPos origin) {
            List<BlockPos> blocks = new ArrayList<>();
            for (int x = -width / 2; x <= width / 2; x++) {
                for (int y = 0; y < height; y++) {
                    for (int z = -depth / 2; z <= depth / 2; z++) {
                        // Hollow check (optional, but good for buildings)
                        if (x == -width / 2 || x == width / 2 || z == -depth / 2 || z == depth / 2 || y == 0 || y == height - 1) {
                            blocks.add(origin.offset(x, y, z));
                        }
                    }
                }
            }
            return blocks;
        }
    }

    public static class Sphere implements Primitive {
        private final int radius;

        public Sphere(int radius) {
            this.radius = radius;
        }

        @Override
        public List<BlockPos> generate(BlockPos origin) {
            List<BlockPos> blocks = new ArrayList<>();
            int rSq = radius * radius;
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (x * x + y * y + z * z <= rSq) {
                            blocks.add(origin.offset(x, y + radius, z));
                        }
                    }
                }
            }
            return blocks;
        }
    }

    public static class Cylinder implements Primitive {
        private final int radius, height;

        public Cylinder(int radius, int height) {
            this.radius = radius;
            this.height = height;
        }

        @Override
        public List<BlockPos> generate(BlockPos origin) {
            List<BlockPos> blocks = new ArrayList<>();
            int rSq = radius * radius;
            for (int x = -radius; x <= radius; x++) {
                for (int y = 0; y < height; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (x * x + z * z <= rSq) {
                            if (x * x + z * z >= (radius - 1) * (radius - 1) || y == 0 || y == height - 1) {
                                blocks.add(origin.offset(x, y, z));
                            }
                        }
                    }
                }
            }
            return blocks;
        }
    }
    
    public static class Cone implements Primitive {
        private final int radius, height;

        public Cone(int radius, int height) {
            this.radius = radius;
            this.height = height;
        }

        @Override
        public List<BlockPos> generate(BlockPos origin) {
            List<BlockPos> blocks = new ArrayList<>();
            for (int y = 0; y < height; y++) {
                float progress = (float) y / height;
                int currentRadius = Math.round(radius * (1 - progress));
                int rSq = currentRadius * currentRadius;
                if (currentRadius == 0) {
                    blocks.add(origin.offset(0, y, 0));
                    continue;
                }
                for (int x = -currentRadius; x <= currentRadius; x++) {
                    for (int z = -currentRadius; z <= currentRadius; z++) {
                        if (x * x + z * z <= rSq) {
                            if (x * x + z * z >= (currentRadius - 1) * (currentRadius - 1) || y == 0) {
                                blocks.add(origin.offset(x, y, z));
                            }
                        }
                    }
                }
            }
            return blocks;
        }
    }
}
