package ai.xelta.craft.client.renderer;

import ai.xelta.craft.item.PhotoSlideItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

/**
 * PhotoSlideRenderer
 *
 * Renders the actual AI-generated image onto a Photo Slide item
 * when displayed in an item frame or held in the player's hand.
 *
 * Lifecycle:
 *   1. Read imageUrl from item NBT
 *   2. Ask TextureCache for the ResourceLocation
 *      - If not loaded yet  → kick off async download, render placeholder
 *      - If loading failed  → render error icon
 *      - If ready           → render full AI image quad
 *   3. Draw a flat textured quad on the item's face
 *
 * This class is registered as a special item renderer via
 * IClientItemExtensions or called directly from the model renderer.
 */
public class PhotoSlideRenderer {

    private static PhotoSlideRenderer INSTANCE;
    public static PhotoSlideRenderer getInstance() {
        if (INSTANCE == null) INSTANCE = new PhotoSlideRenderer();
        return INSTANCE;
    }

    private final TextureCache textureCache = TextureCache.getInstance();

    // =========================================================
    // Main Render Entry Point
    // =========================================================

    /**
     * Called each frame for every visible Photo Slide item.
     * Must be extremely fast — avoid any blocking I/O here.
     */
    public void render(ItemStack stack, ItemDisplayContext displayContext,
                       PoseStack poseStack, MultiBufferSource bufferSource,
                       int packedLight, int packedOverlay) {

        String imageUrl = PhotoSlideItem.getImageUrl(stack);

        if (imageUrl.isBlank()) {
            // No image generated yet — render base item model (handled by vanilla)
            return;
        }

        // Kick off texture load (no-op if already loading or cached)
        ResourceLocation tex = textureCache.get(imageUrl);

        if (tex == null) {
            // Not yet in cache — trigger download, show placeholder this frame
            textureCache.load(imageUrl, loc -> { /* next frame will pick it up */ });
            tex = TextureCache.PLACEHOLDER_TEXTURE;
        }

        // Render the textured quad onto the item face
        renderImageQuad(poseStack, bufferSource, tex, packedLight);
    }

    // =========================================================
    // Quad Rendering
    // =========================================================

    /**
     * Draws a flat textured quad in the XY plane (item face).
     *
     * Coordinate space: item model space, centred at 0.5, 0.5, 0.5
     * The quad covers the front face of a 1×1×0.0625 slab shape.
     */
    private void renderImageQuad(PoseStack poseStack, MultiBufferSource bufferSource,
                                  ResourceLocation texture, int packedLight) {
        poseStack.pushPose();

        // Translate to item face position
        poseStack.translate(0.5f, 0.5f, 0.505f);
        poseStack.scale(1.0f, 1.0f, 1.0f);

        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutout(texture));

        float minU = 0.0f, maxU = 1.0f;
        float minV = 0.0f, maxV = 1.0f;

        // Corner order: BL, BR, TR, TL  (counter-clockwise when facing +Z)
        float x0 = -0.5f, x1 = 0.5f;
        float y0 = -0.5f, y1 = 0.5f;
        float z  =  0.0f;

        int r = 255, g = 255, b = 255, a = 255;

        consumer.addVertex(matrix, x0, y0, z).setColor(r, g, b, a)
            .setUv(minU, maxV).setOverlay(packedOverlay(packedLight))
            .setLight(packedLight).setNormal(0, 0, 1);

        consumer.addVertex(matrix, x1, y0, z).setColor(r, g, b, a)
            .setUv(maxU, maxV).setOverlay(packedOverlay(packedLight))
            .setLight(packedLight).setNormal(0, 0, 1);

        consumer.addVertex(matrix, x1, y1, z).setColor(r, g, b, a)
            .setUv(maxU, minV).setOverlay(packedOverlay(packedLight))
            .setLight(packedLight).setNormal(0, 0, 1);

        consumer.addVertex(matrix, x0, y1, z).setColor(r, g, b, a)
            .setUv(minU, minV).setOverlay(packedOverlay(packedLight))
            .setLight(packedLight).setNormal(0, 0, 1);

        poseStack.popPose();
    }

    /** Extracts packed overlay value, defaulting to no overlay. */
    private int packedOverlay(int packedLight) {
        return OverlayTexture.NO_OVERLAY;
    }
}
