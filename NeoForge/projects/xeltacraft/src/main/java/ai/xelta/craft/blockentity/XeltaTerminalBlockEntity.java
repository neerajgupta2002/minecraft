package ai.xelta.craft.blockentity;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.api.XeltaApiClient;
import ai.xelta.craft.api.XeltaCreditClient;
import ai.xelta.craft.api.XeltaGenAvatarClient;
import ai.xelta.craft.api.XeltaMixClient;
import ai.xelta.craft.api.XeltaMotionClient;
import ai.xelta.craft.config.XeltaCraftConfig;
import ai.xelta.craft.init.ModBlockEntities;
import ai.xelta.craft.init.ModItems;
import ai.xelta.craft.item.AvatarScrollItem;
import ai.xelta.craft.item.FilmRollItem;
import ai.xelta.craft.item.MixboardItem;
import ai.xelta.craft.item.PhotoSlideItem;
import ai.xelta.craft.menu.XeltaTerminalMenu;
import ai.xelta.craft.network.S2CStatusPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * XeltaTerminalBlockEntity
 *
 * Server-side state machine for the Xelta Terminal block.
 *
 * States:
 *   IDLE       → Ready for new generation
 *   GENERATING → API call in-flight (blocks new requests)
 *   DONE       → Output item ready in output slot
 *   ERROR      → Generation failed (message stored for display)
 *
 * Threading model:
 *   - serverTick() runs on the server thread (safe to modify world state)
 *   - API calls run on a CompletableFuture thread pool (NOT server thread)
 *   - All world-state changes from callbacks are scheduled via level.getServer().execute()
 */
public class XeltaTerminalBlockEntity extends BlockEntity implements MenuProvider {

    // =========================================================
    // Constants
    // =========================================================
    public static final int SLOT_INPUT  = 0;   // Photo Slide / Film Roll goes in here
    public static final int SLOT_OUTPUT = 1;   // Generated result comes out here
    public static final int SLOT_COUNT  = 2;

    // =========================================================
    // State
    // =========================================================
    public enum GenerationStatus { IDLE, GENERATING, DONE, ERROR }

    /**
     * Generation mode — detected from what items are in the terminal slots.
     *
     * Detection rules:
     *   MIXBOARD item in output slot              → XELTA_MIX
     *   AVATAR_SCROLL item in output slot         → GEN_AVATAR
     *   FILM_ROLL in output + PHOTO_SLIDE input   → MOTION_CONTROL
     *   Everything else                           → PHOTO_LAB (default)
     *   Mode index 4 (selected in GUI "Build" tab) → WORLD_BUILD
     */
    public enum GenerationMode { PHOTO_LAB, MOTION_CONTROL, GEN_AVATAR, XELTA_MIX, WORLD_BUILD }

    private GenerationMode detectMode() {
        ItemStack input  = itemHandler.getStackInSlot(SLOT_INPUT);
        ItemStack output = itemHandler.getStackInSlot(SLOT_OUTPUT);
        if (!output.isEmpty() && output.getItem() instanceof MixboardItem)    return GenerationMode.XELTA_MIX;
        if (!output.isEmpty() && output.getItem() instanceof AvatarScrollItem) return GenerationMode.GEN_AVATAR;
        if (!output.isEmpty() && output.getItem() instanceof FilmRollItem
            && !input.isEmpty() && input.getItem() instanceof PhotoSlideItem)  return GenerationMode.MOTION_CONTROL;
        return GenerationMode.PHOTO_LAB;
    }

    /** Minecraft-style suffix automatically appended to every Photo Lab prompt. */
    private static final String MINECRAFT_STYLE_SUFFIX =
        ", minecraft style, pixel art, voxel block texture, 16x16, retro game asset, simple flat shading";

    /**
     * World-Build prompt suffix: forces the AI to generate a top-down blueprint
     * that can be interpreted pixel-by-pixel as Minecraft blocks.
     */
    private static final String WORLD_BUILD_SUFFIX =
        ", top-down flat floor plan, overhead pixel art view, simple bold flat block colors, "
        + "32x32 grid, no perspective, no shadows, solid color regions, minecraft block palette";
    private static final String DEFAULT_MIN_IMAGE_SIZE = "512x512";
    private static final String DEFAULT_SLIDE_SIZE = "3x3";

    private final ItemStackHandler itemHandler = new ItemStackHandler(SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private GenerationStatus status  = GenerationStatus.IDLE;
    private String statusMessage     = "";
    private String lastPrompt        = "";
    private String lastJobId         = "";   // For async video jobs
    private UUID   lastRequestId     = UUID.randomUUID();
    private int    creditBalance     = -1;   // -1 = unknown

    // =========================================================
    // Constructor
    // =========================================================
    public XeltaTerminalBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.XELTA_TERMINAL.get(), pos, blockState);
    }

    // =========================================================
    // Server Tick
    // =========================================================
    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  XeltaTerminalBlockEntity be) {
        try {
            be.tick(level);
        } catch (Exception e) {
            // CRITICAL: never let exceptions escape the ticker — Minecraft will crash-loop
            XeltaCraftMod.LOGGER.error("XeltaTerminal tick error at {}: {}", pos, e.getMessage(), e);
            be.setStatus(GenerationStatus.ERROR, "Internal error — check logs.");
        }
    }

    private void tick(Level level) {
        // Nothing to tick currently — async callbacks handle state changes
        // Future: could poll job status here for video generation
    }

    // =========================================================
    // Generation Entry Point (called from packet handler)
    // =========================================================

    /** Called by old packet handler (without explicit mode) — kept for compatibility. */
    public void handleGenerateRequest(ServerPlayer player, String prompt, UUID requestId) {
        handleGenerateRequest(player, prompt, requestId, detectMode(), "", "", "");
    }

    /** Legacy entry point — mode but no size, reference, or model. */
    public void handleGenerateRequest(ServerPlayer player, String prompt, UUID requestId, GenerationMode mode) {
        handleGenerateRequest(player, prompt, requestId, mode, "", "", "");
    }

    /** Primary entry point — mode, size, optional reference image, and AI model. */
    public void handleGenerateRequest(ServerPlayer player, String prompt, UUID requestId,
                                      GenerationMode mode, String size, String referenceImagePath,
                                      String model) {
        // Guard: reject duplicate or concurrent requests
        if (status == GenerationStatus.GENERATING) {
            player.sendSystemMessage(Component.literal("⚠ Already generating — please wait."));
            return;
        }
        if (requestId.equals(lastRequestId) && status != GenerationStatus.IDLE) {
            XeltaCraftMod.LOGGER.warn("Duplicate requestId from {} — ignored", player.getName());
            return;
        }

        String apiKey = XeltaCraftConfig.API_KEY.get();
        if (apiKey.isBlank()) {
            failBeforeGeneration(player, "No API token. Run: /xeltacraft settoken <your-token>");
            return;
        }

        String validationError = validateModeInputs(mode);
        if (validationError != null) {
            failBeforeGeneration(player, validationError);
            return;
        }

        // Auto-append Minecraft style suffix to Photo Lab prompts
        String finalPrompt = prompt;
        if (mode == GenerationMode.PHOTO_LAB && !prompt.contains("minecraft")) {
            finalPrompt = prompt + MINECRAFT_STYLE_SUFFIX;
        }

        // Try to read the local reference image (singleplayer only)
        byte[] refImageBytes = null;
        if (referenceImagePath != null && !referenceImagePath.isBlank()) {
            try {
                java.io.File imgFile = new java.io.File(referenceImagePath);
                if (imgFile.exists() && imgFile.isFile() && imgFile.length() < 20_971_520L) { // 20 MB cap
                    refImageBytes = java.nio.file.Files.readAllBytes(imgFile.toPath());
                    XeltaCraftMod.LOGGER.info("[Terminal] Loaded reference image: {} ({} bytes)",
                        imgFile.getName(), refImageBytes.length);
                } else {
                    XeltaCraftMod.LOGGER.warn("[Terminal] Reference image not found or too large: {}", referenceImagePath);
                }
            } catch (Exception e) {
                XeltaCraftMod.LOGGER.warn("[Terminal] Could not read reference image: {}", e.getMessage());
            }
        }

        SizeSelection sizeSelection = normalizeSizeSelection(size);

        this.lastPrompt    = finalPrompt;
        this.lastRequestId = requestId;
        this.lastJobId     = sizeSelection.combined(); // save normalized params for WORLD_BUILD Gemini
        setStatus(GenerationStatus.GENERATING, "Sending to Xelta AI...", player);

        // Capture weak reference — block may be destroyed before callback fires
        WeakReference<XeltaTerminalBlockEntity> selfRef = new WeakReference<>(this);
        Level capturedLevel = this.level;
        BlockPos capturedPos = this.worldPosition;

        XeltaCraftMod.LOGGER.info("Starting generation for player {} (mode={}, size='{}') prompt: {}",
            player.getName().getString(), mode, sizeSelection.combined(),
            finalPrompt.substring(0, Math.min(80, finalPrompt.length())));

        switch (mode) {
            case PHOTO_LAB      -> startPhotoLabGeneration(finalPrompt, apiKey, sizeSelection.imageSize(), model, refImageBytes,
                                       selfRef, capturedLevel, capturedPos, player);
            case MOTION_CONTROL -> startMotionControlGeneration(finalPrompt, apiKey, selfRef,
                                       capturedLevel, capturedPos, player);
            case GEN_AVATAR     -> startGenAvatarGeneration(finalPrompt, apiKey, selfRef,
                                       capturedLevel, capturedPos, player);
            case XELTA_MIX      -> startXeltaMixGeneration(finalPrompt, apiKey, selfRef,
                                       capturedLevel, capturedPos, player);
            case WORLD_BUILD    -> startWorldBuildGeneration(prompt, apiKey, refImageBytes,
                                       selfRef, capturedLevel, player);
        }
    }

    private static SizeSelection normalizeSizeSelection(String rawSize) {
        String imageSize = DEFAULT_MIN_IMAGE_SIZE;
        String slideSize = DEFAULT_SLIDE_SIZE;

        if (rawSize != null && !rawSize.isBlank()) {
            String[] parts = rawSize.split(",");
            if (parts.length >= 2) {
                imageSize = normalizeImageSize(parts[0]);
                slideSize = normalizeSlideSize(parts[1]);
            } else if (rawSize.contains("x")) {
                String value = rawSize.trim();
                if (value.matches("\\d+x\\d+")) {
                    String first = value.split("x")[0];
                    if (first.length() <= 2) {
                        slideSize = normalizeSlideSize(value);
                    } else {
                        imageSize = normalizeImageSize(value);
                    }
                }
            }
        }

        return new SizeSelection(imageSize, slideSize);
    }

    private static String normalizeImageSize(String value) {
        if (value == null || value.isBlank()) return DEFAULT_MIN_IMAGE_SIZE;
        String trimmed = value.trim();
        return trimmed.matches("\\d+x\\d+") ? trimmed : DEFAULT_MIN_IMAGE_SIZE;
    }

    private static String normalizeSlideSize(String value) {
        if (value == null || value.isBlank()) return DEFAULT_SLIDE_SIZE;
        String trimmed = value.trim();
        return switch (trimmed) {
            case "1x1", "3x3", "5x5", "7x7" -> trimmed;
            default -> DEFAULT_SLIDE_SIZE;
        };
    }

    private record SizeSelection(String imageSize, String slideSize) {
        String combined() {
            return imageSize + "," + slideSize;
        }
    }

    private String validateModeInputs(GenerationMode mode) {
        ItemStack input = itemHandler.getStackInSlot(SLOT_INPUT);

        if (mode == GenerationMode.MOTION_CONTROL) {
            if (input.isEmpty() || !(input.getItem() instanceof PhotoSlideItem)) {
                return "Video mode needs a generated Photo Slide in the Reference slot.";
            }
            if (PhotoSlideItem.getImageUrl(input).isBlank()) {
                return "Video mode needs a Photo Slide that already has an image URL.";
            }
            if (XeltaCraftConfig.REFERENCE_VIDEO_URL.get().isBlank()) {
                return "No reference video set. Run: /xeltacraft setrefvideo <url>";
            }
        }

        if (mode == GenerationMode.GEN_AVATAR
                && !input.isEmpty()
                && input.getItem() instanceof PhotoSlideItem
                && PhotoSlideItem.getImageUrl(input).isBlank()) {
            return "Avatar mode reference Photo Slide has no image URL.";
        }

        return null;
    }

    private void failBeforeGeneration(ServerPlayer player, String message) {
        setStatus(GenerationStatus.ERROR, message, player);
        player.sendSystemMessage(Component.literal("XeltaCraft: " + message));
        broadcastStatusToNearbyPlayers();
    }

    // =========================================================
    // Mode A — Photo Lab (Image Generation)
    // =========================================================
    private void startPhotoLabGeneration(
            String prompt, String apiKey, String size, String model, byte[] refImageBytes,
            WeakReference<XeltaTerminalBlockEntity> selfRef,
            Level capturedLevel, BlockPos capturedPos, ServerPlayer player) {

        // --- Async API call --- runs off the server thread ---
        XeltaApiClient.getInstance()
            .generateImage(prompt, apiKey, refImageBytes, size, model, progressMsg -> {
                // Progress callbacks come from a virtual thread — marshal to server thread
                capturedLevel.getServer().execute(() -> {
                    XeltaTerminalBlockEntity self2 = selfRef.get();
                    if (self2 != null && !self2.isRemoved()) {
                        self2.setStatus(GenerationStatus.GENERATING, progressMsg);
                        self2.broadcastStatusToNearbyPlayers();
                    }
                });
            })
            .thenAccept(imageUrl -> {
                capturedLevel.getServer().execute(() -> {
                    XeltaTerminalBlockEntity self = selfRef.get();
                    if (self == null || self.isRemoved()) return;
                    self.applySuccessResult(imageUrl, player);
                });
            })
            .exceptionally(ex -> {
                capturedLevel.getServer().execute(() -> {
                    XeltaTerminalBlockEntity self = selfRef.get();
                    if (self == null || self.isRemoved()) return;
                    self.applyErrorResult(ex.getCause(), player);
                });
                return null;
            });
    }

    // =========================================================
    // Mode B — Motion Control (Video from Photo Slide image)
    // Input:  Photo Slide (has image URL) → downloads image bytes
    // Output: Film Roll with video URL
    // =========================================================
    private void startMotionControlGeneration(
            String prompt, String apiKey,
            WeakReference<XeltaTerminalBlockEntity> selfRef,
            Level capturedLevel, BlockPos capturedPos, ServerPlayer player) {

        ItemStack inputSlot = itemHandler.getStackInSlot(SLOT_INPUT);
        String photoUrl = PhotoSlideItem.getImageUrl(inputSlot);
        String refVideoUrl = XeltaCraftConfig.REFERENCE_VIDEO_URL.get();

        // Download both files async, then submit Motion Control job
        XeltaMotionClient.getInstance().downloadUrl(photoUrl)
            .thenCombine(
                XeltaMotionClient.getInstance().downloadUrl(refVideoUrl),
                (charBytes, refBytes) -> new byte[][]{charBytes, refBytes}
            )
            .thenCompose(files -> {
                capturedLevel.getServer().execute(() -> {
                    XeltaTerminalBlockEntity self2 = selfRef.get();
                    if (self2 != null && !self2.isRemoved())
                        self2.setStatus(GenerationStatus.GENERATING, "Uploading to Motion Control...");
                });
                return XeltaMotionClient.getInstance().generateVideo(
                    files[0], files[1], apiKey,
                    msg -> capturedLevel.getServer().execute(() -> {
                        XeltaTerminalBlockEntity self2 = selfRef.get();
                        if (self2 != null && !self2.isRemoved()) {
                            self2.setStatus(GenerationStatus.GENERATING, msg);
                            self2.broadcastStatusToNearbyPlayers();
                        }
                    })
                );
            })
            .thenAccept(videoUrl -> capturedLevel.getServer().execute(() -> {
                XeltaTerminalBlockEntity self = selfRef.get();
                if (self == null || self.isRemoved()) return;
                // Store video URL in Film Roll in output slot
                ItemStack filmRoll = self.itemHandler.getStackInSlot(SLOT_OUTPUT);
                if (filmRoll.getItem() instanceof FilmRollItem) {
                    FilmRollItem.setVideoUrl(filmRoll, videoUrl);
                    FilmRollItem.setPrompt(filmRoll, self.lastPrompt);
                } else {
                    ItemStack newRoll = new ItemStack(ModItems.FILM_ROLL.get());
                    FilmRollItem.setVideoUrl(newRoll, videoUrl);
                    FilmRollItem.setPrompt(newRoll, self.lastPrompt);
                    self.itemHandler.setStackInSlot(SLOT_OUTPUT, newRoll);
                }
                self.setStatus(GenerationStatus.DONE, "✅ Video ready! Collect your Film Roll.");
                player.sendSystemMessage(Component.literal("✅ Motion Control video complete!"));
                self.setChanged();
                self.broadcastStatusToNearbyPlayers();
            }))
            .exceptionally(ex -> {
                capturedLevel.getServer().execute(() -> {
                    XeltaTerminalBlockEntity self = selfRef.get();
                    if (self == null || self.isRemoved()) return;
                    self.applyErrorResult(ex.getCause(), player);
                });
                return null;
            });
    }

    // =========================================================
    // Mode C — GenAvatar (Talking Avatar from player skin)
    // Input:  Optional Photo Slide (avatar image) or uses player skin
    // Output: Avatar Scroll with video URL
    // =========================================================
    private void startGenAvatarGeneration(
            String prompt, String apiKey,
            WeakReference<XeltaTerminalBlockEntity> selfRef,
            Level capturedLevel, BlockPos capturedPos, ServerPlayer player) {

        String playerName = player.getName().getString();
        ItemStack inputSlot = itemHandler.getStackInSlot(SLOT_INPUT);

        // Use Photo Slide image if available, otherwise download player skin
        java.util.concurrent.CompletableFuture<byte[]> imageFuture;
        if (!inputSlot.isEmpty() && inputSlot.getItem() instanceof PhotoSlideItem) {
            String photoUrl = PhotoSlideItem.getImageUrl(inputSlot);
            imageFuture = !photoUrl.isBlank()
                ? XeltaGenAvatarClient.getInstance().downloadUrl(photoUrl)
                : XeltaGenAvatarClient.getInstance().downloadPlayerSkin(playerName);
        } else {
            imageFuture = XeltaGenAvatarClient.getInstance().downloadPlayerSkin(playerName);
        }

        imageFuture
            .thenCompose(avatarBytes ->
                XeltaGenAvatarClient.getInstance().generateAvatar(
                    prompt, avatarBytes, apiKey,
                    msg -> capturedLevel.getServer().execute(() -> {
                        XeltaTerminalBlockEntity self2 = selfRef.get();
                        if (self2 != null && !self2.isRemoved()) {
                            self2.setStatus(GenerationStatus.GENERATING, msg);
                            self2.broadcastStatusToNearbyPlayers();
                        }
                    })
                )
            )
            .thenAccept(videoUrl -> capturedLevel.getServer().execute(() -> {
                XeltaTerminalBlockEntity self = selfRef.get();
                if (self == null || self.isRemoved()) return;
                // Fill Avatar Scroll in output slot
                ItemStack outSlot = self.itemHandler.getStackInSlot(SLOT_OUTPUT);
                ItemStack scroll = outSlot.getItem() instanceof ai.xelta.craft.item.AvatarScrollItem
                    ? outSlot : new ItemStack(ModItems.AVATAR_SCROLL.get());
                AvatarScrollItem.setVideoUrl(scroll, videoUrl);
                AvatarScrollItem.setMessage(scroll, self.lastPrompt);
                AvatarScrollItem.setVoiceName(scroll, XeltaCraftConfig.AVATAR_VOICE_ID.get());
                self.itemHandler.setStackInSlot(SLOT_OUTPUT, scroll);
                self.setStatus(GenerationStatus.DONE, "✅ Avatar ready! Collect your Avatar Scroll.");
                player.sendSystemMessage(Component.literal("✅ Talking avatar generated!"));
                self.setChanged();
                self.broadcastStatusToNearbyPlayers();
            }))
            .exceptionally(ex -> {
                capturedLevel.getServer().execute(() -> {
                    XeltaTerminalBlockEntity self = selfRef.get();
                    if (self == null || self.isRemoved()) return;
                    self.applyErrorResult(ex.getCause(), player);
                });
                return null;
            });
    }

    // =========================================================
    // Mode D — Xelta Mix (Style Transfer / Mixboard)
    // Input:  Optional Photo Slide (source image, informational)
    // Output: Mixboard item with up to 8 style variation URLs
    // =========================================================
    private void startXeltaMixGeneration(
            String prompt, String apiKey,
            WeakReference<XeltaTerminalBlockEntity> selfRef,
            Level capturedLevel, BlockPos capturedPos, ServerPlayer player) {

        int numImages = XeltaCraftConfig.MIX_NUM_IMAGES.get();

        XeltaMixClient.getInstance().generateMixboard(prompt, numImages, apiKey,
            msg -> capturedLevel.getServer().execute(() -> {
                XeltaTerminalBlockEntity self2 = selfRef.get();
                if (self2 != null && !self2.isRemoved()) {
                    self2.setStatus(GenerationStatus.GENERATING, msg);
                    self2.broadcastStatusToNearbyPlayers();
                }
            })
        )
        .thenAccept(imageUrls -> capturedLevel.getServer().execute(() -> {
            XeltaTerminalBlockEntity self = selfRef.get();
            if (self == null || self.isRemoved()) return;
            if (imageUrls.isEmpty()) {
                self.applyErrorResult(new Exception("Mixboard returned no images"), player);
                return;
            }
            // Fill Mixboard in output slot
            ItemStack outSlot = self.itemHandler.getStackInSlot(SLOT_OUTPUT);
            ItemStack board = outSlot.getItem() instanceof MixboardItem
                ? outSlot : new ItemStack(ModItems.MIXBOARD.get());
            MixboardItem.setCommand(board, self.lastPrompt);
            MixboardItem.setImageUrls(board, imageUrls);
            MixboardItem.setActiveIndex(board, 0);
            self.itemHandler.setStackInSlot(SLOT_OUTPUT, board);
            self.setStatus(GenerationStatus.DONE,
                "✅ " + imageUrls.size() + " style variations ready! Collect your Mixboard.");
            player.sendSystemMessage(Component.literal(
                "✅ Xelta Mix complete! " + imageUrls.size() + " variations generated."));
            self.setChanged();
            self.broadcastStatusToNearbyPlayers();
        }))
        .exceptionally(ex -> {
            capturedLevel.getServer().execute(() -> {
                XeltaTerminalBlockEntity self = selfRef.get();
                if (self == null || self.isRemoved()) return;
                self.applyErrorResult(ex.getCause(), player);
            });
            return null;
        });
    }

    // =========================================================
    // Mode E — World Build (Gemini → JSON blueprint → blocks in world)
    // =========================================================
    private void startWorldBuildGeneration(
            String prompt, String apiKey, byte[] refImageBytes,
            WeakReference<XeltaTerminalBlockEntity> selfRef,
            Level capturedLevel, ServerPlayer player) {

        // Parse normalized backend size field: "512x512,3x3".
        String sizeParam = lastJobId; // set just before this call in handleGenerateRequest

        int resolutionPx = 512;
        int blockSize    = 3;

        if (sizeParam != null && !sizeParam.isBlank()) {
            String[] parts = sizeParam.split(",");
            if (parts.length >= 1) {
                // Parse resolution from e.g. "512x512"
                try {
                    resolutionPx = Integer.parseInt(parts[0].trim().split("x")[0]);
                } catch (Exception ignored) {}
            }
            if (parts.length >= 2) {
                // Parse slide size from e.g. "5x5"
                blockSize = ai.xelta.craft.api.GeminiArchitectClient.parseSlideSizeToInt(parts[1].trim());
            }
        }

        int maxColors   = ai.xelta.craft.api.GeminiArchitectClient.resolutionToMaxColors(resolutionPx);
        BlockPos buildOrigin = player.blockPosition();

        int finalBlockSize = blockSize;
        int finalResolution = resolutionPx;

        XeltaCraftMod.LOGGER.info("[WorldBuild] Starting Gemini generation: footprint={}x{}, maxColors={}, prompt='{}'",
            blockSize, blockSize, maxColors, prompt.substring(0, Math.min(60, prompt.length())));

        ai.xelta.craft.api.GeminiArchitectClient.getInstance()
            .generateBlueprint(prompt, finalBlockSize, maxColors, refImageBytes, progressMsg -> {
                capturedLevel.getServer().execute(() -> {
                    XeltaTerminalBlockEntity self2 = selfRef.get();
                    if (self2 != null && !self2.isRemoved()) {
                        self2.setStatus(GenerationStatus.GENERATING, progressMsg);
                        self2.broadcastStatusToNearbyPlayers();
                    }
                });
            })
            .thenCompose(jsonBlueprint -> {
                // Marshal progress update to server thread before builder runs
                capturedLevel.getServer().execute(() -> {
                    XeltaTerminalBlockEntity self2 = selfRef.get();
                    if (self2 != null && !self2.isRemoved()) {
                        self2.setStatus(GenerationStatus.GENERATING, "Building " + finalBlockSize + "x" + finalBlockSize + " structure...");
                        self2.broadcastStatusToNearbyPlayers();
                    }
                });

                // GeminiWorldBuilder handles its own server-thread scheduling internally
                return new ai.xelta.craft.world.GeminiWorldBuilder()
                    .buildFromBlueprint(jsonBlueprint,
                        (net.minecraft.server.level.ServerLevel) capturedLevel,
                        buildOrigin, player);
            })
            .thenAccept(count -> {
                capturedLevel.getServer().execute(() -> {
                    XeltaTerminalBlockEntity self = selfRef.get();
                    if (self == null || self.isRemoved()) return;
                    String msg = count > 0
                        ? "\u2705 Built " + count + " blocks! " + finalBlockSize + "x" + finalBlockSize
                            + " at " + buildOrigin.toShortString()
                        : "\u26a0 Build done but 0 blocks placed. Try a more specific prompt.";
                    self.setStatus(GenerationStatus.DONE, msg);
                    player.sendSystemMessage(Component.literal(
                        "\u2705 Gemini built " + count + " blocks at " + buildOrigin.toShortString() + "!"));
                    self.setChanged();
                    self.broadcastStatusToNearbyPlayers();
                });
            })
            .exceptionally(ex -> {
                capturedLevel.getServer().execute(() -> {
                    XeltaTerminalBlockEntity self = selfRef.get();
                    if (self == null || self.isRemoved()) return;
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    String errMsg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                    XeltaCraftMod.LOGGER.warn("[WorldBuild] Gemini generation failed: {}", errMsg);
                    self.applyErrorResult(cause, player);
                });
                return null;
            });
    }

    // =========================================================
    // Result Handlers (run on server thread)

    // =========================================================
    private void applySuccessResult(String imageUrl, ServerPlayer player) {
        XeltaCraftMod.LOGGER.info("Generation complete! image_url={}", imageUrl);

        // Create a Photo Slide item carrying the image URL
        ItemStack photoSlide = new ItemStack(ModItems.PHOTO_SLIDE.get());
        PhotoSlideItem.setImageUrl(photoSlide, imageUrl);
        PhotoSlideItem.setPrompt(photoSlide, lastPrompt);

        // Place in output slot
        itemHandler.setStackInSlot(SLOT_OUTPUT, photoSlide);

        setStatus(GenerationStatus.DONE, "✅ Image ready! Collect your Photo Slide.");
        player.sendSystemMessage(Component.literal("✅ Generation complete! Collect your Photo Slide."));

        // Async refresh credit balance
        String token = ai.xelta.craft.config.XeltaCraftConfig.API_KEY.get();
        if (!token.isBlank()) {
            ai.xelta.craft.api.XeltaCreditClient.getInstance().fetchBalance(token)
                .thenAccept(bal -> {
                    if (bal >= 0) {
                        if (level != null && !level.isClientSide) {
                            level.getServer().execute(() -> {
                                creditBalance = bal;
                                setChanged();
                                broadcastStatusToNearbyPlayers();
                            });
                        }
                    }
                });
        }

        setChanged();
        broadcastStatusToNearbyPlayers();
    }

    private void applyErrorResult(Throwable cause, ServerPlayer player) {
        String userMsg = XeltaApiClient.toUserMessage(cause);
        XeltaCraftMod.LOGGER.warn("Generation error for player {}: {}", player.getName(), cause.getMessage());

        setStatus(GenerationStatus.ERROR, userMsg);
        player.sendSystemMessage(Component.literal("❌ " + userMsg));

        setChanged();
        broadcastStatusToNearbyPlayers();
    }

    // =========================================================
    // Status Management
    // =========================================================
    private void setStatus(GenerationStatus newStatus, String message) {
        this.status        = newStatus;
        this.statusMessage = message;
        setChanged();
    }

    /** Overload that also sends an action-bar notification to the triggering player. */
    private void setStatus(GenerationStatus newStatus, String message, ServerPlayer player) {
        setStatus(newStatus, message);
        if (player != null && !player.hasDisconnected()) {
            player.displayClientMessage(
                Component.literal("\u00a7b[Xelta AI] \u00a7f" + message), true);
        }
    }

    public void resetToIdle() {
        setStatus(GenerationStatus.IDLE, "");
    }

    // =========================================================
    // Network — send status update to all players with GUI open
    // =========================================================
    private void broadcastStatusToNearbyPlayers() {
        if (level == null || level.isClientSide) return;
        // Broadcast to players within 10 blocks
        level.players().stream()
            .filter(p -> p instanceof ServerPlayer)
            .filter(p -> p.distanceToSqr(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ()) < 100)
            .map(p -> (ServerPlayer) p)
            .forEach(sp -> PacketDistributor.sendToPlayer(sp,
                new S2CStatusPacket(worldPosition, status, statusMessage, creditBalance)));
    }

    // =========================================================
    // Drop contents when block is broken
    // =========================================================
    public void dropContents(Level level, BlockPos pos) {
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            ItemStack stack = itemHandler.getStackInSlot(i);
            if (!stack.isEmpty()) {
                net.minecraft.world.Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            }
        }
    }

    // =========================================================
    // MenuProvider — opens the GUI for this block
    // =========================================================
    @Override
    public Component getDisplayName() {
        return Component.translatable("block.xeltacraft.xelta_terminal");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new XeltaTerminalMenu(containerId, playerInventory, itemHandler, worldPosition);
    }

    // =========================================================
    // Getters (used by GUI and packets)
    // =========================================================
    public GenerationStatus getStatus()  { return status; }
    public String getStatusMessage()     { return statusMessage; }
    public String getLastPrompt()        { return lastPrompt; }
    public int getCreditBalance()        { return creditBalance; }
    public ItemStackHandler getItemHandler() { return itemHandler; }

    public void setCreditBalance(int balance) {
        this.creditBalance = balance;
        setChanged();
    }

    // =========================================================
    // NBT Serialization
    // =========================================================
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", itemHandler.serializeNBT(registries));
        tag.putString("Status", status.name());
        tag.putString("StatusMessage", statusMessage);
        tag.putString("LastPrompt", lastPrompt);
        tag.putString("LastJobId", lastJobId);
        tag.putInt("CreditBalance", creditBalance);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        try {
            if (tag.contains("Inventory")) {
                itemHandler.deserializeNBT(registries, tag.getCompound("Inventory"));
            }

            // Safe enum parsing with fallback
            String statusStr = tag.contains("Status") ? tag.getString("Status") : "IDLE";
            try {
                status = GenerationStatus.valueOf(statusStr);
                // Never persist GENERATING state across restarts — would be stuck forever
                if (status == GenerationStatus.GENERATING) {
                    status = GenerationStatus.ERROR;
                    statusMessage = "Generation interrupted by server restart.";
                }
            } catch (IllegalArgumentException e) {
                status = GenerationStatus.IDLE;
            }

            statusMessage  = tag.getString("StatusMessage");
            lastPrompt     = tag.getString("LastPrompt");
            lastJobId      = tag.getString("LastJobId");
            creditBalance  = tag.contains("CreditBalance") ? tag.getInt("CreditBalance") : -1;

        } catch (Exception e) {
            XeltaCraftMod.LOGGER.warn("Failed to load XeltaTerminal NBT at {} — resetting: {}",
                worldPosition, e.getMessage());
            status        = GenerationStatus.IDLE;
            statusMessage = "";
            lastPrompt    = "";
        }
    }
}
