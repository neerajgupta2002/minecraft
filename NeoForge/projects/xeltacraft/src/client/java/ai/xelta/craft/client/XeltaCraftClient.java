package ai.xelta.craft.client;

import ai.xelta.craft.client.screen.XeltaTerminalScreen;
import ai.xelta.craft.init.ModMenuTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * XeltaCraftClient — Client-only initialization.
 *
 * Registers the custom GUI screen for the Xelta Terminal
 * using the NeoForge 1.21 RegisterMenuScreensEvent API.
 */
@EventBusSubscriber(modid = "xeltacraft", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class XeltaCraftClient {

    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.XELTA_TERMINAL.get(), XeltaTerminalScreen::new);
    }
}
