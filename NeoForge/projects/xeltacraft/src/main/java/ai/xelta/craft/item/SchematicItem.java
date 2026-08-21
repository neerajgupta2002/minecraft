package ai.xelta.craft.item;

import ai.xelta.craft.schematic.LitematicaSchematicReader.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * SchematicItem — a build "loaded into a held item" instead of placed immediately, whether it
 * came from a {@code .litematic} import or an AI-generated blueprint. Right-click a block face
 * to build it there: the position you're aiming at (offset onto the clicked face, same as
 * placing a normal block) becomes the schematic's own minimum corner. Aim wherever you like
 * before confirming — that's the "adjust position after generation" step.
 *
 * Block data is stored as full {@link BlockState} NBT (via {@link NbtUtils#writeBlockState}),
 * same shape PhotoSlideItem/FilmRollItem already use for their own generated content
 * ({@link DataComponents#CUSTOM_DATA}), so it round-trips through inventories/servers normally
 * and syncs to the client for the ghost preview renderer.
 */
public class SchematicItem extends Item {

    private static final String TAG_NAME = "xelta_schem_name";
    private static final String TAG_BLOCKS = "xelta_schem_blocks";

    public SchematicItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    // =========================================================
    // Static accessors — DataComponents API (MC 1.21+)
    // =========================================================

    public static ItemStack create(String name, List<Placement> placements, Item item) {
        ItemStack stack = new ItemStack(item);
        setSchematic(stack, name, placements);
        return stack;
    }

    public static void setSchematic(ItemStack stack, String name, List<Placement> placements) {
        CompoundTag root = new CompoundTag();
        root.putString(TAG_NAME, name);

        ListTag blocks = new ListTag();
        for (Placement p : placements) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", p.pos().getX());
            entry.putInt("y", p.pos().getY());
            entry.putInt("z", p.pos().getZ());
            entry.put("state", NbtUtils.writeBlockState(p.state()));
            blocks.add(entry);
        }
        root.put(TAG_BLOCKS, blocks);

        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    public static String getSchematicName(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString(TAG_NAME);
    }

    public static List<Placement> getPlacements(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return List.of();

        CompoundTag root = data.copyTag();
        if (!root.contains(TAG_BLOCKS, 9)) return List.of();

        var blockLookup = BuiltInRegistries.BLOCK.asLookup();
        ListTag blocks = root.getList(TAG_BLOCKS, 10);
        List<Placement> placements = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag entry = blocks.getCompound(i);
            BlockPos pos = new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            BlockState state = NbtUtils.readBlockState(blockLookup, entry.getCompound("state"));
            placements.add(new Placement(pos, state));
        }
        return placements;
    }

    public static boolean hasSchematic(ItemStack stack) {
        return !getPlacements(stack).isEmpty();
    }

    // =========================================================
    // Build on right-click
    // =========================================================

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return InteractionResult.PASS;
        }

        ItemStack stack = context.getItemInHand();
        List<Placement> placements = getPlacements(stack);
        if (placements.isEmpty()) {
            player.sendSystemMessage(Component.literal("§cThis schematic item is empty."));
            return InteractionResult.FAIL;
        }

        BlockPos origin = context.getClickedPos().relative(context.getClickedFace());
        String name = getSchematicName(stack);
        if (name.isBlank()) name = "schematic";

        ai.xelta.craft.world.SchematicBuildStateManager.queueImport(
            (net.minecraft.server.level.ServerLevel) context.getLevel(), origin, placements, player, name);

        if (!player.isCreative()) {
            stack.shrink(1);
        }
        return InteractionResult.SUCCESS;
    }

    // =========================================================
    // Tooltip / display name
    // =========================================================

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                 List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        String name = getSchematicName(stack);
        int count = getPlacements(stack).size();
        if (count > 0) {
            tooltip.add(Component.literal("§7\"" + name + "\" — " + count + " blocks"));
            tooltip.add(Component.literal("§aRight-click a block to build it there"));
        } else {
            tooltip.add(Component.literal("§cEmpty — use /xeltacraft importschematic or the Terminal"));
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        String name = getSchematicName(stack);
        return name.isBlank()
            ? Component.translatable("item.xeltacraft.schematic")
            : Component.literal("Schematic: " + name);
    }
}
