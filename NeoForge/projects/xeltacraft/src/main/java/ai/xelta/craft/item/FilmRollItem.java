package ai.xelta.craft.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/**
 * FilmRollItem — stores an Xelta AI-generated video URL via DataComponents.
 * MC 1.21+ uses the Data Component system instead of raw NBT tags.
 */
public class FilmRollItem extends Item {

    private static final String TAG_VIDEO_URL  = "xelta_video_url";
    private static final String TAG_PROMPT     = "xelta_prompt";
    private static final String TAG_DURATION   = "xelta_duration_sec";
    private static final String TAG_GENERATED  = "xelta_generated";

    public FilmRollItem(Properties properties) {
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
        CompoundTag tag = data.copyTag();
        return tag.contains(TAG_VIDEO_URL) ? tag.getString(TAG_VIDEO_URL) : "";
    }

    public static void setPrompt(ItemStack stack, String prompt) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> {
            CompoundTag tag = existing.copyTag();
            tag.putString(TAG_PROMPT, prompt);
            return CustomData.of(tag);
        });
    }

    public static String getPrompt(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString(TAG_PROMPT);
    }

    public static void setDurationSeconds(ItemStack stack, int seconds) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> {
            CompoundTag tag = existing.copyTag();
            tag.putInt(TAG_DURATION, seconds);
            return CustomData.of(tag);
        });
    }

    public static int getDurationSeconds(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        CompoundTag tag = data.copyTag();
        return tag.contains(TAG_DURATION) ? tag.getInt(TAG_DURATION) : 0;
    }

    public static boolean hasVideo(ItemStack stack) {
        return !getVideoUrl(stack).isBlank();
    }

    // =========================================================
    // Tooltip
    // =========================================================
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        String prompt   = getPrompt(stack);
        int    duration = getDurationSeconds(stack);
        if (!prompt.isBlank()) {
            String display = prompt.length() > 40 ? prompt.substring(0, 37) + "..." : prompt;
            tooltip.add(Component.literal("§7Prompt: §f" + display));
        }
        if (duration > 0) {
            tooltip.add(Component.literal("§7Duration: §f" + duration + "s"));
        }
        tooltip.add(hasVideo(stack)
            ? Component.literal("§aVideo ready — place in Video Screen to play")
            : Component.literal("§cNo video — use Xelta Terminal to generate"));
    }

    @Override
    public Component getName(ItemStack stack) {
        return hasVideo(stack)
            ? Component.translatable("item.xeltacraft.film_roll.filled")
            : Component.translatable("item.xeltacraft.film_roll");
    }
}
