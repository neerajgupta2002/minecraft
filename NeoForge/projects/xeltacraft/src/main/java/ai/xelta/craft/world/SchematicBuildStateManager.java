package ai.xelta.craft.world;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.schematic.LitematicaSchematicReader.Placement;
import ai.xelta.craft.schematic.LitematicaSchematicReader.TileEntityData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
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

    /** Small imports place this slowly (20/sec) — slow enough to actually watch it build, not just appear. */
    private static final int MIN_BLOCKS_PER_TICK = 1;

    /** Never place more than this many blocks in one tick, however big the job — keeps a single tick cheap. */
    private static final int MAX_BLOCKS_PER_TICK = 200;

    /**
     * Chunk size scales with job size so a large import still finishes in roughly this many
     * ticks (~2 minutes) instead of crawling at {@link #MIN_BLOCKS_PER_TICK} the whole way —
     * e.g. a 293,000-block import would otherwise take ~4 hours at 1 block/tick.
     */
    private static final int TARGET_TICKS = 20 * 120;

    private static class ImportJob {
        final ServerLevel level;
        final BlockPos origin;
        final List<Placement> placements;
        final List<TileEntityData> tileEntities;
        final ServerPlayer player;
        final String schematicName;
        final int blocksPerTick;
        int currentIndex = 0;

        ImportJob(ServerLevel level, BlockPos origin, List<Placement> placements,
                  List<TileEntityData> tileEntities, ServerPlayer player, String schematicName) {
            this.level = level;
            this.origin = origin;
            this.placements = placements;
            this.tileEntities = tileEntities;
            this.player = player;
            this.schematicName = schematicName;
            int scaled = (placements.size() + TARGET_TICKS - 1) / TARGET_TICKS;
            this.blocksPerTick = Math.max(MIN_BLOCKS_PER_TICK, Math.min(MAX_BLOCKS_PER_TICK, scaled));
        }
    }

    private static final Queue<ImportJob> activeJobs = new LinkedList<>();

    public static void queueImport(ServerLevel level, BlockPos origin, List<Placement> placements,
                                    List<TileEntityData> tileEntities, ServerPlayer player, String schematicName) {
        ImportJob job = new ImportJob(level, origin, placements, tileEntities, player, schematicName);
        activeJobs.add(job);
        if (player != null) {
            int estimatedSeconds = Math.max(1, placements.size() / (job.blocksPerTick * 20));
            player.sendSystemMessage(Component.literal(
                "§bImporting \"" + schematicName + "\" — " + placements.size()
                    + " blocks, ~" + estimatedSeconds + "s (" + job.blocksPerTick + "/tick)."));
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (activeJobs.isEmpty()) return;

        ImportJob job = activeJobs.peek();
        int placedThisTick = 0;

        while (placedThisTick < job.blocksPerTick && job.currentIndex < job.placements.size()) {
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
            int restoredTileEntities = applyTileEntities(job);
            XeltaCraftMod.LOGGER.info("[SchematicImport] Finished \"{}\" — {} blocks placed at {} ({} tile entities restored)",
                job.schematicName, job.placements.size(), job.origin.toShortString(), restoredTileEntities);
            if (job.player != null) {
                job.player.sendSystemMessage(Component.literal(
                    "§aImport complete: \"" + job.schematicName + "\" (" + job.placements.size() + " blocks)."));
            }
            activeJobs.poll();
        }
    }

    /**
     * Reapplies each tile entity's saved data (chest contents, sign text, ...) onto the block
     * entity the placement loop above already created — done once at the end, after every block
     * in the job is placed, so a container's own block is guaranteed to exist first.
     */
    private static int applyTileEntities(ImportJob job) {
        int restored = 0;
        for (TileEntityData te : job.tileEntities) {
            BlockPos targetPos = job.origin.offset(te.pos());
            BlockEntity blockEntity = job.level.getBlockEntity(targetPos);
            if (blockEntity == null) continue;

            blockEntity.loadWithComponents(te.data(), job.level.registryAccess());
            blockEntity.setChanged();
            restored++;
        }
        return restored;
    }
}
