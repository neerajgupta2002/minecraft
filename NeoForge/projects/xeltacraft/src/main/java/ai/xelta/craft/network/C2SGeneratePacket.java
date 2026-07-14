package ai.xelta.craft.network;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.blockentity.XeltaTerminalBlockEntity;
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
 * Carries the prompt text, target block position, and a unique request ID
 * (to prevent double-submission race conditions).
 */
public record C2SGeneratePacket(BlockPos pos, String prompt, UUID requestId)
        implements CustomPacketPayload {

    public static final Type<C2SGeneratePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(XeltaCraftMod.MOD_ID, "generate"));

    public static final StreamCodec<FriendlyByteBuf, C2SGeneratePacket> CODEC =
        StreamCodec.of(
            (buf, pkt) -> {
                buf.writeBlockPos(pkt.pos());
                buf.writeUtf(pkt.prompt(), 512);           // Max 512 chars
                buf.writeUUID(pkt.requestId());
            },
            buf -> new C2SGeneratePacket(
                buf.readBlockPos(),
                buf.readUtf(512),
                buf.readUUID()
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

            BlockEntity be = serverLevel.getBlockEntity(pkt.pos());
            if (be instanceof XeltaTerminalBlockEntity terminal) {
                terminal.handleGenerateRequest(
                    (net.minecraft.server.level.ServerPlayer) player,
                    pkt.prompt().trim(),
                    pkt.requestId()
                );
            }
        });
    }
}
