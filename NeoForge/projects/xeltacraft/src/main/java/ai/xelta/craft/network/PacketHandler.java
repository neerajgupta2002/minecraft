package ai.xelta.craft.network;

import ai.xelta.craft.XeltaCraftMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * PacketHandler
 *
 * Registers all custom network packets with NeoForge's payload system.
 *
 * Packets:
 *   C2SGeneratePacket  — Client → Server: start generation
 *   S2CStatusPacket    — Server → Client: status update
 */
@EventBusSubscriber(modid = XeltaCraftMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class PacketHandler {

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(XeltaCraftMod.MOD_ID)
            .versioned("1.0.0");

        // Client → Server
        registrar.playToServer(
            C2SGeneratePacket.TYPE,
            C2SGeneratePacket.CODEC,
            C2SGeneratePacket::handle
        );

        // Server → Client
        registrar.playToClient(
            S2CStatusPacket.TYPE,
            S2CStatusPacket.CODEC,
            S2CStatusPacket::handle
        );

        XeltaCraftMod.LOGGER.debug("XeltaCraft packets registered.");
    }
}
