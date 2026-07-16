package ai.xelta.craft.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;

/**
 * AvatarScrollItem — holds a Xelta GenAvatar talking-avatar video URL.
 *
 * Generated via:  POST https://stgavatar.xelta.ai/api/v1/avatar/talking-avatar-upload
 * Polled via:     GET  https://stgavatar.xelta.ai/api/v1/job/{job_id}
 *
 * Stores:
 *   - video_url:  CDN URL of the generated talking avatar video
 *   - message:    The text used to drive the avatar's speech
 *   - voice_name: The TTS voice name used
 *   - job_id:     Reference to the GenAvatar job (for re-download)
 */
public class AvatarScrollItem extends Item {

    private static final String TAG_VIDEO_URL  = "xelta_video_url";
    private static final String TAG_MESSAGE    = "xelta_message";
    private static final String TAG_VOICE_NAME = "xelta_voice_name";
    private static final String TAG_JOB_ID     = "xelta_job_id";
    private static final String TAG_GENERATED  = "xelta_generated";

    public AvatarScrollItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    // =========================================================
    // Static Accessors — DataComponents API (MC 1.21+)
    // =========================================================

    public static void setVideoUrl(ItemStack stack, String url) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> {
            CompoundTag tag = existing.copyTag();
            tag.putString(TAG_VIDEO_URL, url);
            tag.putLong(TAG_GENERATED, System.currentTimeMillis() / 1000L);
            return CustomData.of(tag);
        });
    }

    public static String getVideoUrl(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString(TAG_VIDEO_URL);
    }

    public static void setMessage(ItemStack stack, String message) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> {
            CompoundTag tag = existing.copyTag();
            tag.putString(TAG_MESSAGE, message);
            return CustomData.of(tag);
        });
    }

    public static String getMessage(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString(TAG_MESSAGE);
    }

    public static void setVoiceName(ItemStack stack, String voiceName) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> {
            CompoundTag tag = existing.copyTag();
            tag.putString(TAG_VOICE_NAME, voiceName);
            return CustomData.of(tag);
        });
    }

    public static String getVoiceName(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString(TAG_VOICE_NAME);
    }

    public static void setJobId(ItemStack stack, String jobId) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> {
            CompoundTag tag = existing.copyTag();
            tag.putString(TAG_JOB_ID, jobId);
            return CustomData.of(tag);
        });
    }

    public static String getJobId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString(TAG_JOB_ID);
    }

    public static boolean hasVideo(ItemStack stack) {
        return !getVideoUrl(stack).isBlank();
    }

    // =========================================================
    // Tooltip
    // =========================================================

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);

        String url  = getVideoUrl(stack);
        String msg  = getMessage(stack);
        String voice = getVoiceName(stack);

        if (!msg.isBlank()) {
            tooltip.add(Component.literal("§7Message: §f" +
                (msg.length() > 40 ? msg.substring(0, 40) + "..." : msg)));
        }
        if (!voice.isBlank()) {
            tooltip.add(Component.literal("§7Voice: §b" + voice));
        }
        if (!url.isBlank()) {
            tooltip.add(Component.literal("§a✅ Avatar video ready"));
        } else {
            tooltip.add(Component.literal("§8[ Blank — generate via Xelta Terminal ]"));
        }
    }
}
