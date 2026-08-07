package ai.xelta.craft.render;

import ai.xelta.craft.block.MediaCanvasBlock;
import ai.xelta.craft.blockentity.MediaCanvasBlockEntity;
import ai.xelta.craft.sdk.AssetCacheManager;
import ai.xelta.craft.sdk.AssetDownloader;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.io.File;
import java.io.FileInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MediaCanvasRenderer implements BlockEntityRenderer<MediaCanvasBlockEntity> {

    // Cache of texture IDs so we don't recreate the texture every frame
    private static final Map<String, ResourceLocation> TEXTURE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> DOWNLOADING = new ConcurrentHashMap<>();

    public MediaCanvasRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MediaCanvasBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        String url = blockEntity.getMediaUrl();
        if (url == null || url.isEmpty()) return;

        // Skip rendering videos on standard Canvas for now (requires video decoder)
        if (url.contains(".mp4")) return;

        ResourceLocation textureId = TEXTURE_CACHE.get(url);

        if (textureId == null) {
            // Initiate async download and texture loading if not already started
            if (!DOWNLOADING.containsKey(url)) {
                DOWNLOADING.put(url, true);
                AssetDownloader.downloadAsset(url, AssetCacheManager.AssetType.IMAGE).thenAccept(file -> {
                    // Texture registration must happen on the main client thread
                    Minecraft.getInstance().execute(() -> {
                        try {
                            NativeImage image = NativeImage.read(new FileInputStream(file));
                            DynamicTexture dynamicTexture = new DynamicTexture(image);
                            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("xeltacraft", "canvas_" + file.getName().toLowerCase().replace(".png", ""));
                            Minecraft.getInstance().getTextureManager().register(id, dynamicTexture);
                            TEXTURE_CACHE.put(url, id);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                });
            }
            return; // Don't render until loaded
        }

        // Texture is loaded, draw a 1x1 quad on the face of the block
        Direction facing = blockEntity.getBlockState().getValue(MediaCanvasBlock.FACING);
        
        poseStack.pushPose();
        
        // Center on the block
        poseStack.translate(0.5D, 0.5D, 0.5D);
        
        // Rotate to match the block's facing direction
        float rotation = -facing.toYRot();
        poseStack.mulPose(Axis.YP.rotationDegrees(rotation));
        
        // Move slightly outward to prevent Z-fighting with the block surface
        poseStack.translate(0.0D, 0.0D, 0.501D);

        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutout(textureId));

        // Draw quad from (-0.5, -0.5) to (0.5, 0.5)
        consumer.addVertex(matrix, -0.5f, -0.5f, 0.0f).setColor(255, 255, 255, 255).setUv(1.0f, 1.0f).setOverlay(packedOverlay).setLight(packedLight).setNormal(poseStack.last(), 0.0f, 0.0f, 1.0f);
        consumer.addVertex(matrix, 0.5f, -0.5f, 0.0f).setColor(255, 255, 255, 255).setUv(0.0f, 1.0f).setOverlay(packedOverlay).setLight(packedLight).setNormal(poseStack.last(), 0.0f, 0.0f, 1.0f);
        consumer.addVertex(matrix, 0.5f, 0.5f, 0.0f).setColor(255, 255, 255, 255).setUv(0.0f, 0.0f).setOverlay(packedOverlay).setLight(packedLight).setNormal(poseStack.last(), 0.0f, 0.0f, 1.0f);
        consumer.addVertex(matrix, -0.5f, 0.5f, 0.0f).setColor(255, 255, 255, 255).setUv(1.0f, 0.0f).setOverlay(packedOverlay).setLight(packedLight).setNormal(poseStack.last(), 0.0f, 0.0f, 1.0f);

        poseStack.popPose();
    }
}
