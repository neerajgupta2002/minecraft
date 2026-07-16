package ai.xelta.craft.world;

import ai.xelta.craft.XeltaCraftMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * XeltaWorldBuilder — Converts an AI-generated top-down image into placed Minecraft blocks.
 *
 * Flow:
 *   1. Download the image URL returned by Xelta Photo Lab.
 *   2. Scale it to a configurable grid (default 32×32).
 *   3. Map each pixel to the nearest Minecraft block via an RGB palette.
 *   4. Place each block in the world starting at the player's current position.
 *
 * Limits:
 *   - Max 32×32 = 1024 blocks per build.
 *   - Transparent / near-black pixels are treated as air and skipped.
 *   - Very dark pixels (brightness < 15) are skipped to avoid filling with black wool.
 */
public class XeltaWorldBuilder {

    // Maximum grid size on each axis (total max = GRID × GRID blocks)
    public static final int GRID = 32;

    // =========================================================
    // Color → Block palette
    //   Each entry: { R, G, B, Block }
    //   Black (near-zero brightness) = null → skip (air)
    // =========================================================
    private static final Object[][] PALETTE = {
        // Stone / Masonry
        { 127, 127, 127, Blocks.STONE           },
        { 160, 160, 160, Blocks.COBBLESTONE     },
        { 190, 190, 190, Blocks.STONE_BRICKS    },
        { 200, 200, 200, Blocks.WHITE_CONCRETE  },
        { 240, 240, 240, Blocks.QUARTZ_BLOCK    },
        { 100, 100, 120, Blocks.DEEPSLATE       },

        // Wood
        {  96,  60,  30, Blocks.OAK_PLANKS      },
        { 100,  65,  25, Blocks.SPRUCE_PLANKS   },
        { 140,  80,  40, Blocks.JUNGLE_PLANKS   },
        {  95,  95,  75, Blocks.BIRCH_PLANKS    },
        {  60,  40,  20, Blocks.OAK_LOG         },

        // Dirt / Earth
        { 139,  90,  43, Blocks.DIRT            },
        { 120,  72,  36, Blocks.COARSE_DIRT     },
        { 100,  60,  30, Blocks.ROOTED_DIRT     },

        // Grass / Foliage
        {  58,  95,  58, Blocks.GRASS_BLOCK     },
        {  30, 120,  30, Blocks.GREEN_WOOL       },
        {  30,  80,  30, Blocks.OAK_LEAVES      },
        {  25,  70,  25, Blocks.DARK_OAK_LEAVES },

        // Sand / Sandstone
        { 220, 200, 140, Blocks.SAND            },
        { 210, 190, 120, Blocks.SANDSTONE       },

        // Water / Ice
        {  64, 143, 212, Blocks.WATER            },  // uses fluid state; placed as blue glass instead
        { 100, 160, 220, Blocks.BLUE_STAINED_GLASS },
        { 180, 220, 240, Blocks.ICE             },

        // Wool / Concrete colours
        { 255,  85,  85, Blocks.RED_WOOL        },
        { 255, 170,   0, Blocks.ORANGE_WOOL     },
        { 255, 255,   0, Blocks.YELLOW_WOOL     },
        { 100, 200, 100, Blocks.LIME_WOOL       },
        {  85, 170, 255, Blocks.LIGHT_BLUE_WOOL },
        { 120,  60, 180, Blocks.PURPLE_WOOL     },
        { 220,  50, 220, Blocks.MAGENTA_WOOL    },
        { 255, 200, 200, Blocks.PINK_WOOL       },
        { 100, 100, 180, Blocks.BLUE_WOOL       },
        {  40,  40,  40, Blocks.GRAY_WOOL       },
        { 160, 160, 160, Blocks.LIGHT_GRAY_WOOL },
        {  50,  50,  50, Blocks.BLACK_CONCRETE  },

        // Metals / Special
        { 220, 180,  50, Blocks.GOLD_BLOCK      },
        { 100, 200, 200, Blocks.DIAMOND_BLOCK   },
        { 150, 150, 150, Blocks.IRON_BLOCK      },
        { 100, 150,  80, Blocks.EMERALD_BLOCK   },

        // Glass
        { 190, 240, 240, Blocks.GLASS           },

        // Nether / Dark
        {  80,  30,  30, Blocks.NETHER_BRICKS   },
        {  60,  20,  20, Blocks.CRIMSON_PLANKS  },
        {  30,  60,  60, Blocks.WARPED_PLANKS   },
    };

    private static final int MIN_BRIGHTNESS = 15; // pixels darker than this are skipped (treated as air)

    private final HttpClient httpClient;

    public XeltaWorldBuilder() {
        this.httpClient = HttpClient.newBuilder()
            .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    // =========================================================
    // Public API
    // =========================================================

    /**
     * Downloads the image at {@code imageUrl}, converts it to a block grid, and
     * places blocks in the world starting at {@code origin} (the player's feet).
     *
     * @return CompletableFuture resolving to the number of blocks placed.
     */
    public CompletableFuture<Integer> downloadAndBuild(
            String imageUrl, ServerLevel level, BlockPos origin, ServerPlayer player) {

        XeltaCraftMod.LOGGER.info("[WorldBuilder] Downloading image: {}", imageUrl);

        return downloadImageBytes(imageUrl)
            .thenCompose(bytes -> {
                // Parse + place must happen on the server tick thread
                CompletableFuture<Integer> result = new CompletableFuture<>();
                level.getServer().execute(() -> {
                    try {
                        int placed = parseAndPlace(bytes, level, origin);
                        result.complete(placed);
                    } catch (Exception e) {
                        result.completeExceptionally(e);
                    }
                });
                return result;
            });
    }

    // =========================================================
    // Image download
    // =========================================================

    private CompletableFuture<byte[]> downloadImageBytes(String imageUrl) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(imageUrl))
            .header("User-Agent", "XeltaCraft-Mod/1.0.0")
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            .thenApply(resp -> {
                if (resp.statusCode() != 200) {
                    throw new RuntimeException("Failed to download image: HTTP " + resp.statusCode());
                }
                XeltaCraftMod.LOGGER.info("[WorldBuilder] Downloaded {} bytes", resp.body().length);
                return resp.body();
            });
    }

    // =========================================================
    // Pixel → Block placement
    // =========================================================

    private int parseAndPlace(byte[] imageBytes, ServerLevel level, BlockPos origin) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (img == null) throw new Exception("ImageIO could not decode the image");

        // Sample at most GRID × GRID cells from the image
        int gridW = Math.min(img.getWidth(),  GRID);
        int gridH = Math.min(img.getHeight(), GRID);
        float scaleX = (float) img.getWidth()  / gridW;
        float scaleY = (float) img.getHeight() / gridH;

        int baseX = origin.getX();
        int baseY = origin.getY();
        int baseZ = origin.getZ();

        int placed = 0;

        for (int gz = 0; gz < gridH; gz++) {
            for (int gx = 0; gx < gridW; gx++) {
                int px = Math.min((int)(gx * scaleX), img.getWidth()  - 1);
                int pz = Math.min((int)(gz * scaleY), img.getHeight() - 1);
                int argb = img.getRGB(px, pz);

                // Skip transparent pixels
                int alpha = (argb >> 24) & 0xFF;
                if (alpha < 64) continue;

                int r = (argb >> 16) & 0xFF;
                int g = (argb >>  8) & 0xFF;
                int b =  argb        & 0xFF;

                // Skip near-black pixels (background / sky / air)
                if (r < MIN_BRIGHTNESS && g < MIN_BRIGHTNESS && b < MIN_BRIGHTNESS) continue;

                Block block = findNearestBlock(r, g, b);
                if (block == null) continue;

                BlockPos pos = new BlockPos(baseX + gx, baseY, baseZ + gz);
                level.setBlockAndUpdate(pos, block.defaultBlockState());
                placed++;
            }
        }

        XeltaCraftMod.LOGGER.info("[WorldBuilder] Placed {} blocks in {}×{} grid at {}",
            placed, gridW, gridH, origin);
        return placed;
    }

    // =========================================================
    // Color matching
    // =========================================================

    /** Returns the Minecraft block whose palette RGB is closest to (r, g, b). */
    private static Block findNearestBlock(int r, int g, int b) {
        Block best = Blocks.STONE;
        int bestDist = Integer.MAX_VALUE;

        for (Object[] entry : PALETTE) {
            int pr = (int) entry[0];
            int pg = (int) entry[1];
            int pb = (int) entry[2];
            Block block = (Block) entry[3];

            int dr = r - pr;
            int dg = g - pg;
            int db = b - pb;
            int dist = dr * dr + dg * dg + db * db; // squared Euclidean in RGB space

            if (dist < bestDist) {
                bestDist = dist;
                best = block;
            }
        }

        return best;
    }
}
