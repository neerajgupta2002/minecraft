package ai.xelta.craft.client;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.client.screen.XeltaTerminalScreen;
import ai.xelta.craft.init.ModMenuTypes;
import ai.xelta.craft.network.S2CStatusPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * ClientEventHandler — registers all client-only event listeners.
 *
 * Also wires the S2CStatusPacket handler to XeltaTerminalScreen,
 * which cannot be done in the main sourceset (client class isolation).
 */
@EventBusSubscriber(modid = XeltaCraftMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientEventHandler {

    /**
     * Wire the packet consumer — set before any packets can arrive.
     * Called during client setup (before the player loads a world).
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Wire S2CStatusPacket handler → XeltaTerminalScreen
        S2CStatusPacket.clientHandler = pkt -> {
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof XeltaTerminalScreen screen
                    && screen.getBlockPos().equals(pkt.pos())) {
                screen.onStatusUpdate(pkt.status(), pkt.message(), pkt.creditBalance());
            }
        };
        XeltaCraftMod.LOGGER.debug("S2CStatusPacket client handler registered.");
    }

    /**
     * Register the GUI screen for the Xelta Terminal menu type.
     * Without this, right-clicking the block shows a blank/missing screen.
     */
    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.XELTA_TERMINAL.get(), XeltaTerminalScreen::new);
        XeltaCraftMod.LOGGER.debug("XeltaTerminalScreen registered.");
    }
}
