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
 * PhotoSlideItem — stores an Xelta AI-generated image URL via DataComponents.
 *
 * MC 1.21+ uses the Data Component system instead of raw NBT tags.
 * All image metadata is stored under {@code DataComponents.CUSTOM_DATA}.
 */
public class PhotoSlideItem extends Item {

    private static final String TAG_IMAGE_URL  = "xelta_image_url";
    private static final String TAG_PROMPT     = "xelta_prompt";
    private static final String TAG_GENERATED  = "xelta_generated";
    private static final String TAG_LEGACY_URL = "ImageUrl"; // migration

    public PhotoSlideItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    // =========================================================
    // Static Accessors — DataComponents API (MC 1.21+)
    // =========================================================

    public static void setImageUrl(ItemStack stack, String url) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> {
            CompoundTag tag = existing.copyTag();
            tag.putString(TAG_IMAGE_URL, url);
            tag.putLong(TAG_GENERATED, System.currentTimeMillis() / 1000L);
            return CustomData.of(tag);
        });
    }

    public static String getImageUrl(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        CompoundTag tag = data.copyTag();
        if (tag.contains(TAG_IMAGE_URL)) return tag.getString(TAG_IMAGE_URL);
        if (tag.contains(TAG_LEGACY_URL)) return tag.getString(TAG_LEGACY_URL);
        return "";
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

    public static boolean hasImage(ItemStack stack) {
        return !getImageUrl(stack).isBlank();
    }

    // =========================================================
    // Tooltip
    // =========================================================
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        String url    = getImageUrl(stack);
        String prompt = getPrompt(stack);
        if (!prompt.isBlank()) {
            String display = prompt.length() > 40 ? prompt.substring(0, 37) + "..." : prompt;
            tooltip.add(Component.literal("§7Prompt: §f" + display));
        }
        tooltip.add(url.isBlank()
            ? Component.literal("§cNo image — use Xelta Terminal to generate")
            : Component.literal("§aImage ready — place in item frame to display"));
    }

    @Override
    public Component getName(ItemStack stack) {
        return hasImage(stack)
            ? Component.translatable("item.xeltacraft.photo_slide.filled")
            : Component.translatable("item.xeltacraft.photo_slide");
    }
}
