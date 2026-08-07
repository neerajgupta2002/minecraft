package ai.xelta.craft.network;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.blockentity.XeltaTerminalBlockEntity;
import ai.xelta.craft.blockentity.XeltaTerminalBlockEntity.GenerationMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * C2SGeneratePacket — Client → Server
 *
 * Sent when the player clicks the "Generate" button in XeltaTerminalScreen.
 * Carries the mode, desired size string, and an optional reference image, sent as raw
 * bytes read on the client — a local filesystem path is meaningless once it leaves the
 * client's machine (a dedicated/remote server can't read it), and a separate upload to
 * Xelta's API isn't needed either: the generate/Gemini calls already accept the image
 * embedded directly in the same request as the prompt. Capped well under any realistic
 * packet-size concern; large images should be downscaled/compressed before picking them.
 */
public record C2SGeneratePacket(BlockPos pos, String prompt, UUID requestId, int mode,
                                String size, byte[] referenceImageBytes, String model)
        implements CustomPacketPayload {

    /** 5 MiB — generous for a compressed reference photo, small enough to be a safe single packet. */
    public static final int MAX_REFERENCE_IMAGE_BYTES = 5 * 1024 * 1024;

    public static final Type<C2SGeneratePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(XeltaCraftMod.MOD_ID, "generate"));

    public static final StreamCodec<FriendlyByteBuf, C2SGeneratePacket> CODEC =
        StreamCodec.of(
            (buf, pkt) -> {
                buf.writeBlockPos(pkt.pos());
                buf.writeUtf(pkt.prompt(), 512);
                buf.writeUUID(pkt.requestId());
                buf.writeVarInt(pkt.mode());
                buf.writeUtf(pkt.size(), 64);   // increased: accommodates "1024x1024,5x5" format
                buf.writeByteArray(pkt.referenceImageBytes() != null ? pkt.referenceImageBytes() : new byte[0]);
                buf.writeUtf(pkt.model(), 128);
            },
            buf -> new C2SGeneratePacket(
                buf.readBlockPos(),
                buf.readUtf(512),
                buf.readUUID(),
                buf.readVarInt(),
                buf.readUtf(64),   // increased: accommodates "1024x1024,5x5" format
                buf.readByteArray(MAX_REFERENCE_IMAGE_BYTES),
                buf.readUtf(128)
            )
        );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // =========================================================
    // Server-side handler
    // =========================================================

    public static void handle(C2SGeneratePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var player = ctx.player();
            if (!(player.level() instanceof ServerLevel serverLevel)) return;

            // Validate player is close enough to the block (anti-cheat)
            if (player.distanceToSqr(pkt.pos().getX(), pkt.pos().getY(), pkt.pos().getZ()) > 64) {
                XeltaCraftMod.LOGGER.warn("Player {} tried to generate from too far away (pos={})",
                    player.getName().getString(), pkt.pos());
                return;
            }

            // Validate prompt not empty
            if (pkt.prompt().isBlank()) return;

            // Clamp mode to valid range
            int modeIndex = Math.max(0, Math.min(pkt.mode(), GenerationMode.values().length - 1));
            GenerationMode mode = GenerationMode.values()[modeIndex];

            BlockEntity be = serverLevel.getBlockEntity(pkt.pos());
            if (be instanceof XeltaTerminalBlockEntity terminal) {
                terminal.handleGenerateRequest(
                    (net.minecraft.server.level.ServerPlayer) player,
                    pkt.prompt().trim(),
                    pkt.requestId(),
                    mode,
                    pkt.size(),
                    pkt.referenceImageBytes(),
                    pkt.model()
                );
            }
        });
    }
}
