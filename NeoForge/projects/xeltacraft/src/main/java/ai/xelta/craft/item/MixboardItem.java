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
 * MixboardItem — holds a Xelta Mix style-transfer board.
 *
 * Generated via:  POST https://mxboard.stg.xelta.ai/api/mixboards/
 * Progress via:   GET  https://mxboard.stg.xelta.ai/api/mixboards/{board_id}/progress
 * Result via:     GET  https://mxboard.stg.xelta.ai/api/mixboards/{board_id}
 * Refine via:     POST https://mxboard.stg.xelta.ai/api/mixboards/{board_id}/refine/{position}
 *
 * Stores:
 *   - board_id:    The Xelta Mix board ID (for refining individual images)
 *   - command:     The original style prompt
 *   - image_urls:  Up to 8 CDN URLs for generated style variations
 *   - num_images:  How many images were generated
 *   - active_idx:  Which image is currently "selected" for viewing
 */
public class MixboardItem extends Item {

    private static final String TAG_BOARD_ID    = "xelta_board_id";
    private static final String TAG_COMMAND     = "xelta_command";
    private static final String TAG_IMAGE_URLS  = "xelta_image_urls";
    private static final String TAG_NUM_IMAGES  = "xelta_num_images";
    private static final String TAG_ACTIVE_IDX  = "xelta_active_idx";
    private static final String TAG_GENERATED   = "xelta_generated";

    public MixboardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    // =========================================================
    // Static Accessors — DataComponents API (MC 1.21+)
    // =========================================================

    public static void setBoardId(ItemStack stack, String boardId) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> {
            CompoundTag tag = existing.copyTag();
            tag.putString(TAG_BOARD_ID, boardId);
            tag.putLong(TAG_GENERATED, System.currentTimeMillis() / 1000L);
            return CustomData.of(tag);
        });
    }

    public static String getBoardId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString(TAG_BOARD_ID);
    }

    public static void setCommand(ItemStack stack, String command) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> {
            CompoundTag tag = existing.copyTag();
            tag.putString(TAG_COMMAND, command);
            return CustomData.of(tag);
        });
    }

    public static String getCommand(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString(TAG_COMMAND);
    }

    public static void setImageUrls(ItemStack stack, List<String> urls) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> {
            CompoundTag tag = existing.copyTag();
            ListTag list = new ListTag();
            for (String url : urls) {
                list.add(StringTag.valueOf(url));
            }
            tag.put(TAG_IMAGE_URLS, list);
            tag.putInt(TAG_NUM_IMAGES, urls.size());
            return CustomData.of(tag);
        });
    }

    public static List<String> getImageUrls(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return List.of();
        CompoundTag tag = data.copyTag();
        if (!tag.contains(TAG_IMAGE_URLS)) return List.of();
        ListTag list = tag.getList(TAG_IMAGE_URLS, Tag.TAG_STRING);
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            urls.add(list.getString(i));
        }
        return urls;
    }

    public static int getImageCount(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        CompoundTag tag = data.copyTag();
        return tag.contains(TAG_NUM_IMAGES) ? tag.getInt(TAG_NUM_IMAGES) : 0;
    }

    public static void setActiveIndex(ItemStack stack, int idx) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> {
            CompoundTag tag = existing.copyTag();
            tag.putInt(TAG_ACTIVE_IDX, idx);
            return CustomData.of(tag);
        });
    }

    public static int getActiveIndex(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        return data.copyTag().getInt(TAG_ACTIVE_IDX);
    }

    /** Returns the currently selected image URL (for item frame rendering). */
    public static String getActiveImageUrl(ItemStack stack) {
        List<String> urls = getImageUrls(stack);
        int idx = getActiveIndex(stack);
        if (urls.isEmpty() || idx >= urls.size()) return "";
        return urls.get(idx);
    }

    public static boolean hasImages(ItemStack stack) {
        return getImageCount(stack) > 0;
    }

    // =========================================================
    // Tooltip
    // =========================================================

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);

        String boardId  = getBoardId(stack);
        String command  = getCommand(stack);
        int    numImgs  = getImageCount(stack);
        int    active   = getActiveIndex(stack);

        if (!command.isBlank()) {
            tooltip.add(Component.literal("§7Style: §f" +
                (command.length() > 40 ? command.substring(0, 40) + "..." : command)));
        }
        if (numImgs > 0) {
            tooltip.add(Component.literal("§a✅ " + numImgs + " variations  §7(viewing #" + (active + 1) + ")"));
            tooltip.add(Component.literal("§7Sneak+scroll to cycle variations"));
        } else if (!boardId.isBlank()) {
            tooltip.add(Component.literal("§e⏳ Board generating..."));
        } else {
            tooltip.add(Component.literal("§8[ Blank — generate via Xelta Terminal ]"));
        }
    }
}
