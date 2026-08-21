package ai.xelta.craft.world;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.schematic.LitematicaSchematicReader.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Places a parsed schematic's blocks a few hundred at a time across server ticks, mirroring
 * {@link BuildStateManager}'s pacing so a large import doesn't stall the server in one tick.
 * Unlike {@link BuildStateManager} (which drives the AI-generation feature: string block IDs,
 * survival-mode inventory consumption, crafting fallback), this places the schematic's real
 * {@code BlockState}s verbatim and doesn't touch the player's inventory — an imported build is
 * placed as-is, not remixed from what the player is carrying.
 */
@EventBusSubscriber(modid = XeltaCraftMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class SchematicBuildStateManager {

    private static final int BLOCKS_PER_TICK = 200;

    private static class ImportJob {
        final ServerLevel level;
        final BlockPos origin;
        final List<Placement> placements;
        final ServerPlayer player;
        final String schematicName;
        int currentIndex = 0;

        ImportJob(ServerLevel level, BlockPos origin, List<Placement> placements, ServerPlayer player, String schematicName) {
            this.level = level;
            this.origin = origin;
            this.placements = placements;
            this.player = player;
            this.schematicName = schematicName;
        }
    }

    private static final Queue<ImportJob> activeJobs = new LinkedList<>();

    public static void queueImport(ServerLevel level, BlockPos origin, List<Placement> placements,
                                    ServerPlayer player, String schematicName) {
        activeJobs.add(new ImportJob(level, origin, placements, player, schematicName));
        if (player != null) {
            int estimatedSeconds = Math.max(1, placements.size() / (BLOCKS_PER_TICK * 20));
            player.sendSystemMessage(Component.literal(
                "§bImporting \"" + schematicName + "\" — " + placements.size()
                    + " blocks, ~" + estimatedSeconds + "s."));
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (activeJobs.isEmpty()) return;

        ImportJob job = activeJobs.peek();
        int placedThisTick = 0;

        while (placedThisTick < BLOCKS_PER_TICK && job.currentIndex < job.placements.size()) {
            Placement placement = job.placements.get(job.currentIndex);
            job.currentIndex++;
            placedThisTick++;

            BlockPos targetPos = job.origin.offset(placement.pos());
            if (targetPos.getY() < job.level.getMinBuildHeight() || targetPos.getY() >= job.level.getMaxBuildHeight()) {
                continue;
            }

            job.level.setBlockAndUpdate(targetPos, placement.state());
        }

        if (job.currentIndex >= job.placements.size()) {
            XeltaCraftMod.LOGGER.info("[SchematicImport] Finished \"{}\" — {} blocks placed at {}",
                job.schematicName, job.placements.size(), job.origin.toShortString());
            if (job.player != null) {
                job.player.sendSystemMessage(Component.literal(
                    "§aImport complete: \"" + job.schematicName + "\" (" + job.placements.size() + " blocks)."));
            }
            activeJobs.poll();
        }
    }
}
