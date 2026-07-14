package ai.xelta.craft.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

public class BlueprintItem extends Item {
    public BlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        CustomData customData = context.getItemInHand().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (customData.isEmpty()) return InteractionResult.PASS;

        CompoundTag tag = customData.copyTag();
        if (!tag.contains("Blocks")) return InteractionResult.PASS;

        BlockPos origin = context.getClickedPos().relative(context.getClickedFace());
        ListTag blocks = tag.getList("Blocks", Tag.TAG_COMPOUND);

        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag blockTag = blocks.getCompound(i);
            int dx = blockTag.getInt("x");
            int dy = blockTag.getInt("y");
            int dz = blockTag.getInt("z");
            String stateStr = blockTag.getString("state");

            ResourceLocation res = ResourceLocation.tryParse(stateStr);
            if (res != null) {
                var block = BuiltInRegistries.BLOCK.get(res);
                if (block != null) {
                    BlockPos targetPos = origin.offset(dx, dy, dz);
                    level.setBlock(targetPos, block.defaultBlockState(), 3);
                }
            }
        }

        context.getItemInHand().shrink(1);
        return InteractionResult.CONSUME;
    }
}
