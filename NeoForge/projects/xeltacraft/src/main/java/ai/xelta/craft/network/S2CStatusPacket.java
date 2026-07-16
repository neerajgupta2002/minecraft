package ai.xelta.craft.network;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.blockentity.XeltaTerminalBlockEntity.GenerationStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.function.Consumer;

/**
 * S2CStatusPacket — Server → Client
 *
 * Broadcast when the Terminal's generation status changes.
 *
 * The actual client-side screen update is handled via a static consumer
 * {@link #clientHandler} which is set by {@code ClientEventHandler} in the
 * client sourceset. This avoids a compile-time dependency from main → client.
 */
public record S2CStatusPacket(
        BlockPos pos,
        GenerationStatus status,
        String message,
        int creditBalance
) implements CustomPacketPayload {

    public static final Type<S2CStatusPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(XeltaCraftMod.MOD_ID, "status"));

    public static final StreamCodec<FriendlyByteBuf, S2CStatusPacket> CODEC =
        StreamCodec.of(
            (buf, pkt) -> {
                buf.writeBlockPos(pkt.pos());
                buf.writeEnum(pkt.status());
                buf.writeUtf(pkt.message(), 256);
                buf.writeInt(pkt.creditBalance());
            },
            buf -> new S2CStatusPacket(
                buf.readBlockPos(),
                buf.readEnum(GenerationStatus.class),
                buf.readUtf(256),
                buf.readInt()
            )
        );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // =========================================================
    // Client handler — set by ClientEventHandler (client sourceset)
    // Avoids compile-time dependency from main → client classes.
    // =========================================================

    /** Set by ClientEventHandler on the client side. No-op on server. */
    public static Consumer<S2CStatusPacket> clientHandler = pkt -> {};

    public static void handle(S2CStatusPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> clientHandler.accept(pkt));
    }
}
