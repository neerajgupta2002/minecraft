package ai.xelta.craft.world;

import ai.xelta.craft.XeltaCraftMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Map;
import java.util.HashMap;

@EventBusSubscriber(modid = XeltaCraftMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class BuildStateManager {

    private static final int BLOCKS_PER_TICK = 100;

    private static class BuildJob {
        final ServerLevel level;
        final BlockPos origin;
        final JsonArray blueprint;
        final ServerPlayer player;
        final String jobId;
        int currentIndex = 0;

        BuildJob(ServerLevel level, BlockPos origin, JsonArray blueprint, ServerPlayer player) {
            this.level = level;
            this.origin = origin;
            this.blueprint = blueprint;
            this.player = player;
            this.jobId = java.util.UUID.randomUUID().toString();
        }
    }

    private static final Queue<BuildJob> activeJobs = new LinkedList<>();
    private static final Map<ServerPlayer, BuildJob> pendingJobs = new HashMap<>();

    public static boolean confirmPendingJob(ServerPlayer player) {
        BuildJob job = pendingJobs.remove(player);
        if (job != null) {
            activeJobs.add(job);
            return true;
        }
        return false;
    }

    public static void queueBuild(ServerLevel level, BlockPos origin, JsonArray blueprint, ServerPlayer player) {
        BuildJob job = new BuildJob(level, origin, blueprint, player);
        if (blueprint.size() > 1000 && player != null) {
            pendingJobs.put(player, job);
            int estimatedSeconds = blueprint.size() / (BLOCKS_PER_TICK * 20); // 20 ticks per second
            if (estimatedSeconds == 0) estimatedSeconds = 1;
            player.sendSystemMessage(Component.literal("§e⚠ This massive build requires " + blueprint.size() + " blocks and will take ~" + estimatedSeconds + " seconds. Type §b/xeltacraft confirm§e to proceed."));
        } else {
            activeJobs.add(job);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (activeJobs.isEmpty()) return;

        BuildJob job = activeJobs.peek();
        int blocksPlacedThisTick = 0;

        while (blocksPlacedThisTick < BLOCKS_PER_TICK && job.currentIndex < job.blueprint.size()) {
            JsonElement el = job.blueprint.get(job.currentIndex);
            job.currentIndex++;
            blocksPlacedThisTick++;

            if (el.isJsonObject()) {
                JsonObject blockEntry = el.getAsJsonObject();
                int x = blockEntry.get("x").getAsInt();
                int y = blockEntry.get("y").getAsInt();
                int z = blockEntry.get("z").getAsInt();
                String blockId = blockEntry.get("block").getAsString();

                BlockPos targetPos = job.origin.offset(x, y, z);

                // Validation: Prevent building below bedrock or above height limit
                if (targetPos.getY() < job.level.getMinBuildHeight() || targetPos.getY() >= job.level.getMaxBuildHeight()) {
                    continue;
                }

                Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
                if (block == Blocks.AIR && !blockId.equals("minecraft:air")) {
                    // Fallback for hallucinated or missing blocks
                    block = Blocks.STONE_BRICKS; 
                }

                // Inventory Consumption for Survival Mode
                if (job.player != null && !job.player.isCreative() && block != Blocks.AIR) {
                    boolean found = false;
                    for (int i = 0; i < job.player.getInventory().getContainerSize(); i++) {
                        ItemStack stack = job.player.getInventory().getItem(i);
                        if (!stack.isEmpty() && stack.getItem() == block.asItem()) {
                            stack.shrink(1);
                            found = true;
                            ai.xelta.craft.XeltaCraftMod.LOGGER.info("[BuildStateManager] Consumed 1x {} from inventory of {}", stack.getItem(), job.player.getName().getString());
                            break;
                        }
                    }

                    if (!found) {
                        found = ai.xelta.craft.generation.VirtualCrafter.tryCraft(job.player, block);
                    }

                    if (!found) {
                        job.player.sendSystemMessage(Component.literal("§cBuild halted: Out of " + BuiltInRegistries.BLOCK.getKey(block).getPath() + "."));
                        activeJobs.poll(); // Abort job
                        return; // Exit tick early
                    }
                }

                job.level.setBlockAndUpdate(targetPos, block.defaultBlockState());
                ai.xelta.craft.XeltaCraftMod.LOGGER.info("🧱 [BuildStateManager] Placed {} at [X:{}, Y:{}, Z:{}] 📍", BuiltInRegistries.BLOCK.getKey(block), targetPos.getX(), targetPos.getY(), targetPos.getZ());
            }
        }

        if (job.currentIndex >= job.blueprint.size()) {
            if (job.player != null) {
                ai.xelta.craft.generation.FeedbackEngine.promptForRating(job.player, job.jobId, job.blueprint);
            }
            activeJobs.poll(); // Job finished
        }
    }
}
