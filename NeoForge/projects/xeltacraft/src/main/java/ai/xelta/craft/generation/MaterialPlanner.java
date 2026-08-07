package ai.xelta.craft.generation;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class MaterialPlanner {
    private static final Map<String, BlockState[]> STYLE_PALETTES = new HashMap<>();
    private static final Random RANDOM = new Random();

    static {
        STYLE_PALETTES.put("medieval", new BlockState[]{
                Blocks.STONE_BRICKS.defaultBlockState(),
                Blocks.COBBLESTONE.defaultBlockState(),
                Blocks.SPRUCE_PLANKS.defaultBlockState(),
                Blocks.OAK_LOG.defaultBlockState()
        });
        STYLE_PALETTES.put("steampunk", new BlockState[]{
                Blocks.COPPER_BLOCK.defaultBlockState(),
                Blocks.DEEPSLATE_BRICKS.defaultBlockState(),
                Blocks.DARK_OAK_PLANKS.defaultBlockState(),
                Blocks.IRON_BARS.defaultBlockState()
        });
        STYLE_PALETTES.put("modern", new BlockState[]{
                Blocks.QUARTZ_BLOCK.defaultBlockState(),
                Blocks.WHITE_CONCRETE.defaultBlockState(),
                Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(),
                Blocks.SMOOTH_STONE.defaultBlockState()
        });
        STYLE_PALETTES.put("nature", new BlockState[]{
                Blocks.OAK_LEAVES.defaultBlockState(),
                Blocks.OAK_LOG.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(),
                Blocks.GRASS_BLOCK.defaultBlockState()
        });
    }

    public static BlockState[] getPalette(String style) {
        if (style == null) return STYLE_PALETTES.get("medieval");
        
        String key = style.toLowerCase();
        for (String k : STYLE_PALETTES.keySet()) {
            if (key.contains(k)) {
                return STYLE_PALETTES.get(k);
            }
        }

        // Dynamic resolution for exact inventory blocks
        try {
            net.minecraft.resources.ResourceLocation loc = net.minecraft.resources.ResourceLocation.tryParse(key);
            if (loc != null) {
                net.minecraft.world.level.block.Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(loc);
                if (block != Blocks.AIR && !key.equals("minecraft:air")) {
                    return new BlockState[]{
                            block.defaultBlockState(),
                            block.defaultBlockState(),
                            block.defaultBlockState(),
                            block.defaultBlockState()
                    };
                }
            }
        } catch (Exception ignored) {}

        return STYLE_PALETTES.get("medieval");
    }

    public static BlockState getBlockForRole(String role, String style) {
        BlockState[] palette = getPalette(style);
        
        return switch (role.toLowerCase()) {
            case "wall_primary" -> palette[0];
            case "pillar_block" -> palette[3];
            case "roof_primary" -> palette[2];
            case "roof_trim" -> palette[1];
            case "stair_block" -> Blocks.OAK_STAIRS.defaultBlockState(); // A default chair block
            default -> palette[0];
        };
    }

    public static BlockState getRandomBlock(BlockState[] palette) {
        return palette[RANDOM.nextInt(palette.length)];
    }
}
