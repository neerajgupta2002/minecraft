package ai.xelta.craft.client;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.client.screen.XeltaTerminalScreen;
import ai.xelta.craft.infra.cache.XeltaFileStorage;
import ai.xelta.craft.init.ModMenuTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * XeltaCraftClient — Client-only initialization.
 *
 * Registers the custom GUI screen for the Xelta Terminal
 * and initializes client-side infrastructure (storage, cache)
 * after Minecraft fully loads.
 */
@EventBusSubscriber(modid = "xeltacraft", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class XeltaCraftClient {

    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.XELTA_TERMINAL.get(), XeltaTerminalScreen::new);
    }
}

/**
 * Separate class to catch game-event bus events (not MOD bus) on the client side.
 */
@EventBusSubscriber(modid = "xeltacraft", bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
class XeltaCraftClientEvents {

    private static boolean storageInitialized = false;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!storageInitialized) {
            storageInitialized = true;
            try {
                XeltaFileStorage.getInstance().init();
                XeltaCraftMod.LOGGER.info("[XeltaCraftClient] Infrastructure initialized.");
            } catch (Exception e) {
                XeltaCraftMod.LOGGER.error("[XeltaCraftClient] Storage init failed: {}", e.getMessage(), e);
            }
        }
    }
}
