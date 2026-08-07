package ai.xelta.craft.command;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.api.XeltaApiClient;
import ai.xelta.craft.api.auth.XeltaSessionManager;
import ai.xelta.craft.blockentity.XeltaTerminalBlockEntity;
import ai.xelta.craft.config.XeltaCraftConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /xeltacraft — in-game commands for XeltaCraft management.
 *
 *   /xeltacraft setkey <apikey>   — Store Xelta API key in config
 *   /xeltacraft balance           — Check current credit balance
 *   /xeltacraft status            — Show current generation status
 *   /xeltacraft reset             — Reset targeted Terminal to IDLE
 *   /xeltacraft help              — Show all commands
 */
public class XeltaCraftCommand {
    private static final Pattern JWT_PATTERN =
        Pattern.compile("(eyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("xeltacraft")
                .then(Commands.literal("settoken")
                    .then(Commands.argument("token", StringArgumentType.greedyString())
                        .executes(XeltaCraftCommand::setKey)))
                .then(Commands.literal("setkey")   // alias for settoken
                    .then(Commands.argument("apikey", StringArgumentType.greedyString())
                        .executes(XeltaCraftCommand::setKey)))
                .then(Commands.literal("loadtoken") // read token from a file (bypasses 256-char chat limit)
                    .then(Commands.argument("filepath", StringArgumentType.greedyString())
                        .executes(XeltaCraftCommand::loadTokenFromFile)))
                .then(Commands.literal("setgeminikey")
                    .then(Commands.argument("geminikey", StringArgumentType.greedyString())
                        .executes(XeltaCraftCommand::setGeminiKey)))
                .then(Commands.literal("setmodel")
                    .then(Commands.argument("modelid", StringArgumentType.greedyString())
                        .executes(XeltaCraftCommand::setModel)))
                .then(Commands.literal("setrefvideo")
                    .then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(XeltaCraftCommand::setRefVideo)))
                .then(Commands.literal("listvoices")
                    .executes(XeltaCraftCommand::listVoices))
                .then(Commands.literal("setvoice")
                    .then(Commands.argument("voiceid", StringArgumentType.greedyString())
                        .executes(XeltaCraftCommand::setVoice)))
                .then(Commands.literal("balance")
                    .executes(XeltaCraftCommand::checkBalance))
                .then(Commands.literal("status")
                    .executes(XeltaCraftCommand::checkStatus))
                .then(Commands.literal("reset")
                    .executes(XeltaCraftCommand::resetTerminal))
                .then(Commands.literal("confirm")
                    .executes(XeltaCraftCommand::confirmBuild))
                .then(Commands.literal("rate")
                    .then(Commands.argument("stars", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 5))
                        .executes(XeltaCraftCommand::rateBuild)))
                .then(Commands.literal("help")
                    .executes(XeltaCraftCommand::showHelp))
                .executes(XeltaCraftCommand::showHelp)
        );
    }

    // ─── /xeltacraft setkey <apikey> ────────────────────────────────────────

    private static int setKey(CommandContext<CommandSourceStack> ctx) {
        // Accept both "token" arg (from settoken) and "apikey" arg (from setkey alias)
        String key;
        try { key = StringArgumentType.getString(ctx, "token").trim(); }
        catch (Exception e) { key = StringArgumentType.getString(ctx, "apikey").trim(); }

        key = normalizeToken(key);

        if (key.length() < 8) {
            ctx.getSource().sendFailure(Component.literal(
                "❌ Token too short. Get your JWT token from xelta.ai → F12 → Local Storage → 'token'"));
            return 0;
        }
        if (key.length() > 2000) {
            ctx.getSource().sendFailure(Component.literal(
                "❌ Token too long. Paste only the token value, not the entire localStorage."));
            return 0;
        }

        TokenValidation validation = validateJwt(key);
        if (!validation.valid()) {
            ctx.getSource().sendFailure(Component.literal("❌ " + validation.message()));
            return 0;
        }

        XeltaCraftConfig.API_KEY.set(key);
        XeltaCraftConfig.SPEC.save();

        // Also inject into the live session manager so auth gate activates immediately
        XeltaSessionManager.getInstance().setManualToken(key);

        String masked = key.substring(0, Math.min(6, key.length())) + "••••••••" + key.substring(Math.max(0, key.length() - 4));
        ctx.getSource().sendSuccess(
            () -> Component.literal("✅ Auth token saved: ")
                .append(Component.literal(masked).withStyle(ChatFormatting.GREEN)),
            false
        );
        XeltaCraftMod.LOGGER.info("Auth token updated by {}", ctx.getSource().getTextName());
        return 1;
    }

    // ─── /xeltacraft loadtoken <filepath> ─────────────────────────────────────
    // Reads a JWT token from a plain-text file — bypasses Minecraft's 256-char
    // chat input limit. Usage:
    //   1. Save your token to a file, e.g.: C:\token.txt
    //   2. /xeltacraft loadtoken C:\token.txt

    private static int loadTokenFromFile(CommandContext<CommandSourceStack> ctx) {
        String filePath = StringArgumentType.getString(ctx, "filepath").trim();

        java.nio.file.Path path;
        try {
            path = java.nio.file.Path.of(filePath);
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("❌ Invalid file path: " + filePath));
            return 0;
        }

        if (!java.nio.file.Files.exists(path)) {
            ctx.getSource().sendFailure(Component.literal(
                "❌ File not found: " + filePath + 
                "  |  Tip: Save your token to a .txt file first."));
            return 0;
        }

        String key;
        try {
            key = java.nio.file.Files.readString(path).trim();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("❌ Could not read file: " + e.getMessage()));
            return 0;
        }

        key = normalizeToken(key);

        if (key.length() < 8) {
            ctx.getSource().sendFailure(Component.literal(
                "❌ Token too short — check the file contents."));
            return 0;
        }

        TokenValidation validation = validateJwt(key);
        if (!validation.valid()) {
            ctx.getSource().sendFailure(Component.literal("❌ " + validation.message()));
            return 0;
        }

        XeltaCraftConfig.API_KEY.set(key);
        XeltaCraftConfig.SPEC.save();
        XeltaSessionManager.getInstance().setManualToken(key);

        String masked = key.substring(0, Math.min(6, key.length()))
            + "••••••••" + key.substring(Math.max(0, key.length() - 4));
        ctx.getSource().sendSuccess(
            () -> Component.literal("✅ Token loaded from file: ")
                .append(Component.literal(masked).withStyle(ChatFormatting.GREEN)),
            false
        );
        XeltaCraftMod.LOGGER.info("Auth token loaded from file {} by {}", filePath, ctx.getSource().getTextName());
        return 1;
    }

    // ─── /xeltacraft setgeminikey <key> ────────────────────────────────────────

    private static int setGeminiKey(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "geminikey").trim();

        if (key.isBlank() || key.length() < 10) {
            ctx.getSource().sendFailure(Component.literal(
                "❌ Invalid Gemini API key. Get one at: https://aistudio.google.com/app/apikey"));
            return 0;
        }

        XeltaCraftConfig.GEMINI_API_KEY.set(key);
        XeltaCraftConfig.SPEC.save();

        String masked = key.substring(0, Math.min(6, key.length())) + "••••••••";
        ctx.getSource().sendSuccess(
            () -> Component.literal("✅ Gemini API key saved: ")
                .append(Component.literal(masked).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" (Build mode now uses Gemini architecture AI)").withStyle(ChatFormatting.GRAY)),
            false
        );
        XeltaCraftMod.LOGGER.info("Gemini API key updated by {}", ctx.getSource().getTextName());
        return 1;
    }

    // ─── /xeltacraft balance ────────────────────────────────────────────────

    private static int checkBalance(CommandContext<CommandSourceStack> ctx) {
        String apiKey = XeltaCraftConfig.API_KEY.get();

        if (apiKey.isBlank()) {
            ctx.getSource().sendFailure(Component.literal(
                "❌ No API key set. Run: /xeltacraft setkey <key>"));
            return 0;
        }

        ctx.getSource().sendSuccess(
            () -> Component.literal("⏳ Fetching balance from Xelta...").withStyle(ChatFormatting.YELLOW),
            false
        );

        ai.xelta.craft.api.XeltaCreditClient.getInstance().fetchBalanceResult(apiKey)
            .thenAccept(result -> {
                if (ctx.getSource().getServer() == null) return;
                ctx.getSource().getServer().execute(() -> {
                    if (!result.success()) {
                        ctx.getSource().sendFailure(Component.literal(
                            "❌ " + result.message()));
                    } else {
                        int balance = result.balance();
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("💳 Xelta balance: ")
                                .append(Component.literal(balance + " credits")
                                    .withStyle(balance < 50
                                        ? ChatFormatting.RED
                                        : ChatFormatting.GREEN)),
                            false
                        );
                        if (balance < 50) {
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("  ⚠ Low balance! ")
                                    .append(Component.literal("[Buy Credits →]")
                                        .withStyle(Style.EMPTY
                                            .withColor(ChatFormatting.AQUA)
                                            .withUnderlined(true)
                                            .withClickEvent(new ClickEvent(
                                                ClickEvent.Action.OPEN_URL,
                                                "https://xelta.ai/pricing")))),
                                false
                            );
                        }
                    }
                });
            });

        return 1;
    }

    private static String normalizeToken(String token) {
        String cleaned = token == null ? "" : token.trim();
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        if (cleaned.regionMatches(true, 0, "Bearer ", 0, 7)) {
            cleaned = cleaned.substring(7).trim();
        }
        Matcher matcher = JWT_PATTERN.matcher(cleaned);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return cleaned;
    }

    private static TokenValidation validateJwt(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return new TokenValidation(false,
                "That does not look like a JWT. Paste the raw xelta.ai token value, not masked text or a whole page.");
        }

        try {
            String payloadJson = new String(Base64.getUrlDecoder().decode(padBase64Url(parts[1])),
                StandardCharsets.UTF_8);
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();

            if (payload.has("exp")) {
                long expiresAt = payload.get("exp").getAsLong();
                long now = System.currentTimeMillis() / 1000L;
                if (expiresAt <= now) {
                    return new TokenValidation(false,
                        "This JWT is already expired. Log in to xelta.ai again and copy a fresh token.");
                }
            }

            return new TokenValidation(true, "");
        } catch (Exception e) {
            return new TokenValidation(false,
                "Could not decode the JWT payload. Paste the full token without spaces or line breaks.");
        }
    }

    private static String padBase64Url(String value) {
        int remainder = value.length() % 4;
        if (remainder == 0) return value;
        return value + "=".repeat(4 - remainder);
    }

    private record TokenValidation(boolean valid, String message) {}

    // ─── /xeltacraft setmodel <modelid> ─────────────────────────────────

    private static int setModel(CommandContext<CommandSourceStack> ctx) {
        String modelId = StringArgumentType.getString(ctx, "modelid").trim();
        if (modelId.isBlank()) {
            ctx.getSource().sendFailure(Component.literal("❌ Model ID cannot be blank."));
            return 0;
        }
        XeltaCraftConfig.IMAGE_MODEL_ID.set(modelId);
        XeltaCraftConfig.SPEC.save();
        ctx.getSource().sendSuccess(
            () -> Component.literal("✅ Image model set to: ")
                .append(Component.literal(modelId).withStyle(ChatFormatting.AQUA)),
            false
        );
        return 1;
    }

    // ─── /xeltacraft setrefvideo <url> ──────────────────────────────────────

    private static int setRefVideo(CommandContext<CommandSourceStack> ctx) {
        String url = StringArgumentType.getString(ctx, "url").trim();
        if (url.isBlank()) {
            ctx.getSource().sendFailure(Component.literal("❌ URL cannot be blank."));
            return 0;
        }
        XeltaCraftConfig.REFERENCE_VIDEO_URL.set(url);
        XeltaCraftConfig.SPEC.save();
        ctx.getSource().sendSuccess(
            () -> Component.literal("✅ Reference video set to: ")
                .append(Component.literal(url).withStyle(ChatFormatting.AQUA)),
            false
        );
        return 1;
    }

    // ─── /xeltacraft listvoices ─────────────────────────────────────────────

    private static int listVoices(CommandContext<CommandSourceStack> ctx) {
        String apiKey = XeltaCraftConfig.API_KEY.get();
        if (apiKey.isBlank()) {
            ctx.getSource().sendFailure(Component.literal(
                "❌ No API key set. Run: /xeltacraft setkey <key>"));
            return 0;
        }

        ctx.getSource().sendSuccess(
            () -> Component.literal("⏳ Fetching voices from GenAvatar...").withStyle(ChatFormatting.YELLOW),
            false
        );

        ai.xelta.craft.api.XeltaGenAvatarClient.getInstance().fetchVoices(apiKey, "en", null)
            .thenAccept(voices -> {
                if (ctx.getSource().getServer() == null) return;
                ctx.getSource().getServer().execute(() -> {
                    if (voices.isEmpty()) {
                        ctx.getSource().sendFailure(Component.literal(
                            "❌ Could not fetch voices. Check your API key or internet."));
                    } else {
                        ctx.getSource().sendSuccess(() -> Component.literal("§bAvailable English Voices:"), false);
                        for (ai.xelta.craft.api.XeltaGenAvatarClient.VoiceInfo v : voices) {
                            ctx.getSource().sendSuccess(() -> Component.literal(v.toString()), false);
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal("Set with: §e/xeltacraft setvoice <id>"), false);
                    }
                });
            });
        return 1;
    }

    // ─── /xeltacraft setvoice <voiceid> ─────────────────────────────────────

    private static int setVoice(CommandContext<CommandSourceStack> ctx) {
        String voiceId = StringArgumentType.getString(ctx, "voiceid").trim();
        if (voiceId.isBlank()) {
            ctx.getSource().sendFailure(Component.literal("❌ Voice ID cannot be blank."));
            return 0;
        }
        XeltaCraftConfig.AVATAR_VOICE_ID.set(voiceId);
        XeltaCraftConfig.SPEC.save();
        ctx.getSource().sendSuccess(
            () -> Component.literal("✅ Avatar voice set to: ")
                .append(Component.literal(voiceId).withStyle(ChatFormatting.AQUA)),
            false
        );
        return 1;
    }

    // ─── /xeltacraft status ─────────────────────────────────────────────────

    private static int checkStatus(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }

        // Find nearest Terminal block within 8 blocks
        BlockPos playerPos = player.blockPosition();
        var level = player.serverLevel();

        for (int r = 0; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        BlockPos check = playerPos.offset(dx, dy, dz);
                        BlockEntity be = level.getBlockEntity(check);
                        if (be instanceof XeltaTerminalBlockEntity terminal) {
                            String status = terminal.getStatus().name();
                            String msg    = terminal.getStatusMessage();
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("📡 Terminal at " + check + ": ")
                                    .append(Component.literal("[" + status + "] ").withStyle(
                                        switch (terminal.getStatus()) {
                                            case DONE       -> ChatFormatting.GREEN;
                                            case ERROR      -> ChatFormatting.RED;
                                            case GENERATING -> ChatFormatting.YELLOW;
                                            default         -> ChatFormatting.GRAY;
                                        }))
                                    .append(Component.literal(msg)),
                                false
                            );
                            return 1;
                        }
                    }
                }
            }
        }

        ctx.getSource().sendFailure(Component.literal(
            "No Xelta Terminal found within 8 blocks."));
        return 0;
    }

    // ─── /xeltacraft reset ──────────────────────────────────────────────────

    private static int resetTerminal(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }

        BlockPos playerPos = player.blockPosition();
        var level = player.serverLevel();

        for (int r = 0; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        BlockPos check = playerPos.offset(dx, dy, dz);
                        BlockEntity be = level.getBlockEntity(check);
                        if (be instanceof XeltaTerminalBlockEntity terminal) {
                            terminal.resetToIdle();
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("✅ Terminal at " + check + " reset to IDLE.")
                                    .withStyle(ChatFormatting.GREEN),
                                false
                            );
                            return 1;
                        }
                    }
                }
            }
        }

        ctx.getSource().sendFailure(Component.literal("No Terminal found within 8 blocks."));
        return 0;
    }

    // ─── /xeltacraft confirm ────────────────────────────────────────────────

    private static int confirmBuild(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }

        boolean confirmed = ai.xelta.craft.world.BuildStateManager.confirmPendingJob(player);
        if (confirmed) {
            ctx.getSource().sendSuccess(
                () -> Component.literal("✅ Massive build confirmed. Execution started.")
                    .withStyle(ChatFormatting.GREEN),
                false
            );
            return 1;
        } else {
            ctx.getSource().sendFailure(Component.literal("❌ You have no pending builds to confirm."));
            return 0;
        }
    }

    // ─── /xeltacraft rate <stars> ───────────────────────────────────────────

    private static int rateBuild(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }

        int stars = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "stars");
        ai.xelta.craft.generation.FeedbackEngine.saveRating(player, stars);
        return 1;
    }

    // ─── /xeltacraft help ───────────────────────────────────────────────────

    private static int showHelp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("""
            §b§l── XeltaCraft Commands ──§r
            §e/xeltacraft settoken <jwt>§r    — Set your Xelta auth token
            §e/xeltacraft balance§r           — Check credit balance
            §e/xeltacraft setrefvideo <url>§r — Set Motion Control video
            §e/xeltacraft listvoices§r        — List Avatar TTS voices
            §e/xeltacraft setvoice <id>§r     — Set Avatar TTS voice
            §e/xeltacraft status§r            — Show Terminal status nearby
            §e/xeltacraft reset§r             — Reset stuck Terminal to IDLE
            §e/xeltacraft help§r              — Show this message
            §7
            §7Get your token: §bxelta.ai §7→ F12 → App → Local Storage → 'token'"""),
            false
        );
        return 1;
    }
}
