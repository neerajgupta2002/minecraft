package ai.xelta.craft.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * XeltaCraftConfig — Full configuration for all 5 Xelta services.
 *
 * ─── Getting Your Auth Token ──────────────────────────────────────
 *  xelta.ai → log in → F12 → Application → Local Storage → "token"
 *  /xeltacraft settoken <token>
 * ─────────────────────────────────────────────────────────────────
 *
 * ─── Service URLs ─────────────────────────────────────────────────
 *  Photo Lab (image gen):   https://bgremover.xelta.ai
 *  Motion Control (video):  https://advavatar.stg.xelta.ai
 *  Credit Balance:          https://devpmt.xelta.ai/api
 *  GenAvatar (talking):     https://stgavatar.xelta.ai/api/v1
 *  Xelta Mix (styles):      https://mxboard.stg.xelta.ai
 * ─────────────────────────────────────────────────────────────────
 */
public class XeltaCraftConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    // ── Authentication ─────────────────────────────────────────────
    public static final ModConfigSpec.ConfigValue<String> API_KEY;

    // ── Gemini API (Architecture / World Build) ────────────────────
    public static final ModConfigSpec.ConfigValue<String> GEMINI_API_KEY;
    public static final ModConfigSpec.ConfigValue<String> GEMINI_MODEL;

    public static String resolveGeminiApiKey() {
        return GeminiApiKeyResolver.resolve(GEMINI_API_KEY.get());
    }

    // ── Phase 4 — Photo Lab (Image Generation) ─────────────────────
    public static final ModConfigSpec.ConfigValue<String> PHOTO_LAB_URL;

    // ── Phase 5 — Motion Control (Video Generation) ────────────────
    public static final ModConfigSpec.ConfigValue<String> MOTION_CONTROL_URL;
    public static final ModConfigSpec.ConfigValue<String> REFERENCE_VIDEO_URL;

    // ── Phase 3 — Credit Balance ───────────────────────────────────
    public static final ModConfigSpec.ConfigValue<String> CREDIT_API_URL;

    // ── Phase 7a — GenAvatar (Talking Avatar) ──────────────────────
    public static final ModConfigSpec.ConfigValue<String> AVATAR_API_URL;
    /** UUID of the TTS voice. Get from /xeltacraft listvoices */
    public static final ModConfigSpec.ConfigValue<String> AVATAR_VOICE_ID;

    // ── Phase 7b — Xelta Mix (Style Transfer / Mixboard) ──────────
    public static final ModConfigSpec.ConfigValue<String> XELTA_MIX_URL;
    /** Number of style variations to generate per Mixboard (max 8) */
    public static final ModConfigSpec.IntValue MIX_NUM_IMAGES;

    // ── Legacy / backward compat ────────────────────────────────────
    public static final ModConfigSpec.ConfigValue<String> API_BASE_URL;
    public static final ModConfigSpec.ConfigValue<String> IMAGE_MODEL_ID;
    public static final ModConfigSpec.ConfigValue<String> VIDEO_MODEL_ID;

    // ── Generation Parameters ───────────────────────────────────────
    public static final ModConfigSpec.ConfigValue<String> NEGATIVE_PROMPT;

    // ── Behaviour ──────────────────────────────────────────────────
    public static final ModConfigSpec.IntValue     TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue     MAX_RETRIES;
    public static final ModConfigSpec.BooleanValue SHOW_CREDIT_BALANCE;
    public static final ModConfigSpec.BooleanValue DEBUG_MODE;

    static {
        BUILDER.comment("XeltaCraft — AI-powered Minecraft Mod",
                        "Connects to Xelta AI services for image, video, and avatar generation.");

        BUILDER.push("auth");
        API_KEY = BUILDER
            .comment("Your Xelta JWT auth token.",
                     "Get it: xelta.ai → F12 → Application → Local Storage → 'token'",
                     "Set via: /xeltacraft settoken <token>  (KEEP THIS PRIVATE!)")
            .define("auth_token", "");
        BUILDER.pop();

        BUILDER.push("gemini");

        GEMINI_API_KEY = BUILDER
            .comment("Google Gemini API key for WORLD_BUILD architecture generation.",
                     "Get it: https://aistudio.google.com/app/apikey",
                     "Set via: /xeltacraft setgeminikey <key>")
            .define("gemini_api_key", "");

        GEMINI_MODEL = BUILDER
            .comment("Gemini model to use for architecture generation.",
                     "Options: gemini-2.5-flash, gemini-2.0-flash, gemini-1.5-flash, gemini-1.5-pro")
            .define("gemini_model", "gemini-2.5-flash");

        BUILDER.pop();

        BUILDER.push("service_urls");

        PHOTO_LAB_URL = BUILDER
            .comment("Phase 4 — Photo Lab: image generation + background AI",
                     "Production: https://bgremover.xelta.ai")
            .define("photo_lab_url", "https://bgremover.xelta.ai");

        MOTION_CONTROL_URL = BUILDER
            .comment("Phase 5 — Motion Control: AI video generation",
                     "Staging: https://advavatar.stg.xelta.ai")
            .define("motion_control_url", "https://advavatar.stg.xelta.ai");

        CREDIT_API_URL = BUILDER
            .comment("Phase 3 — Credit balance API",
                     "Full path: {credit_api_url}/users/credits")
            .define("credit_api_url", "https://devpmt.xelta.ai/api");

        AVATAR_API_URL = BUILDER
            .comment("Phase 7a — GenAvatar: talking avatar video generation",
                     "Staging: https://stgavatar.xelta.ai/api/v1")
            .define("avatar_api_url", "https://stgavatar.xelta.ai/api/v1");

        XELTA_MIX_URL = BUILDER
            .comment("Phase 7b — Xelta Mix: style transfer / mixboard generation",
                     "Staging: https://mxboard.stg.xelta.ai")
            .define("xelta_mix_url", "https://mxboard.stg.xelta.ai");

        BUILDER.pop();
        BUILDER.push("motion_control");

        REFERENCE_VIDEO_URL = BUILDER
            .comment("URL of the reference video for Motion Control generation.",
                     "The mod downloads this MP4 and uploads it with your character image.",
                     "Set via: /xeltacraft setrefvideo <url>")
            .define("reference_video_url", "");

        BUILDER.pop();
        BUILDER.push("gen_avatar");

        AVATAR_VOICE_ID = BUILDER
            .comment("UUID of the TTS voice for GenAvatar generation.",
                     "List available voices: /xeltacraft listvoices",
                     "Set a voice: /xeltacraft setvoice <voice-uuid>")
            .define("avatar_voice_id", "");

        BUILDER.pop();
        BUILDER.push("xelta_mix");

        MIX_NUM_IMAGES = BUILDER
            .comment("Number of style variations to generate per Mixboard session.",
                     "Range: 1-8. Default: 8 (one full board).")
            .defineInRange("mix_num_images", 8, 1, 8);

        BUILDER.pop();
        BUILDER.push("generation");

        NEGATIVE_PROMPT = BUILDER
            .comment("Default negative prompt for Photo Lab image generation.")
            .define("negative_prompt", "blurry, low quality, pixelated, distorted, ugly");

        BUILDER.pop();
        BUILDER.push("legacy");

        API_BASE_URL = BUILDER
            .comment("Legacy — no longer used.")
            .define("api_base_url", "https://api.xelta.ai");
        IMAGE_MODEL_ID = BUILDER
            .comment("Legacy — Photo Lab uses endpoint routing, not model IDs.")
            .define("image_model_id", "flux-schnell");
        VIDEO_MODEL_ID = BUILDER
            .comment("Legacy — Motion Control uses endpoint routing, not model IDs.")
            .define("video_model_id", "motion-control");

        BUILDER.pop();
        BUILDER.push("behaviour");

        TIMEOUT_SECONDS = BUILDER
            .comment("HTTP request timeout in seconds.")
            .defineInRange("timeout_seconds", 60, 5, 120);
        MAX_RETRIES = BUILDER
            .comment("Maximum automatic retries on rate limit errors.")
            .defineInRange("max_retries", 3, 0, 10);
        SHOW_CREDIT_BALANCE = BUILDER
            .comment("Show credit balance in the Terminal GUI.")
            .define("show_credit_balance", true);
        DEBUG_MODE = BUILDER
            .comment("Log raw API request/response bodies for debugging.")
            .define("debug_mode", false);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}
