package ai.xelta.craft.event;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.init.ModBlockEntities;
import ai.xelta.craft.render.MediaCanvasRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = XeltaCraftMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.MEDIA_CANVAS.get(), MediaCanvasRenderer::new);
    }
}
