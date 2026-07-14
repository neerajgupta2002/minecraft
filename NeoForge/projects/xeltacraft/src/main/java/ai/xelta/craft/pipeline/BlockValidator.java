package ai.xelta.craft.pipeline;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.CompletableFuture;

public class BlockValidator {
    public CompletableFuture<CompoundTag> validate(CompoundTag blueprintNbt) {
        return CompletableFuture.supplyAsync(() -> {
            ListTag blocks = blueprintNbt.getList("Blocks", Tag.TAG_COMPOUND);
            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag blockTag = blocks.getCompound(i);
                String state = blockTag.getString("state");
                ResourceLocation res = ResourceLocation.tryParse(state);
                if (res == null || !BuiltInRegistries.BLOCK.containsKey(res)) {
                    // Fallback to stone if invalid
                    blockTag.putString("state", "minecraft:stone");
                }
            }
            return blueprintNbt;
        });
    }
}
