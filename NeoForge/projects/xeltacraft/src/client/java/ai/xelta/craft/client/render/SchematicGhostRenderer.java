package ai.xelta.craft.client.render;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.item.SchematicItem;
import ai.xelta.craft.schematic.LitematicaSchematicReader.Placement;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.List;

/**
 * Renders a translucent, see-through copy of a held {@link SchematicItem}'s blocks at wherever
 * the player is currently aiming — the visual half of "adjust position after generation": you
 * see the whole structure follow your crosshair before you commit to it with a right-click
 * (the actual placement logic lives in {@link SchematicItem#useOn}, entirely independent of
 * whether this preview renders correctly).
 *
 * Each block is force-rendered with {@link RenderType#translucent()} and a reduced global alpha
 * via {@link RenderSystem#setShaderColor}, rather than trying to tint each block's own model —
 * the same trick WorldEdit-style "hologram" previews use.
 */
@EventBusSubscriber(modid = XeltaCraftMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SchematicGhostRenderer {

    /** Above this many blocks, skip the per-block ghost — rebuilding it every frame gets expensive. */
    private static final int MAX_GHOST_BLOCKS = 20_000;

    private SchematicGhostRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        ItemStack stack = getHeldSchematic(player);
        if (stack == null) return;

        List<Placement> placements = SchematicItem.getPlacements(stack);
        if (placements.isEmpty() || placements.size() > MAX_GHOST_BLOCKS) return;

        if (!(mc.hitResult instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) return;
        BlockPos origin = blockHit.getBlockPos().relative(blockHit.getDirection());

        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = event.getCamera().getPosition();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        int fullbright = 0xF000F0;

        poseStack.pushPose();
        poseStack.translate(origin.getX() - camPos.x, origin.getY() - camPos.y, origin.getZ() - camPos.z);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 0.45f);

        for (Placement p : placements) {
            poseStack.pushPose();
            poseStack.translate(p.pos().getX(), p.pos().getY(), p.pos().getZ());
            blockRenderer.renderSingleBlock(p.state(), poseStack, bufferSource, fullbright,
                OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.translucent());
            poseStack.popPose();
        }

        bufferSource.endBatch(RenderType.translucent());
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        poseStack.popPose();
    }

    private static ItemStack getHeldSchematic(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof SchematicItem) return main;
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof SchematicItem) return off;
        return null;
    }
}
