package ai.xelta.craft.world;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.schematic.LitematicaSchematicReader;
import ai.xelta.craft.schematic.SchematicPaths;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Reads a {@code .litematic} file from the {@link SchematicPaths} drop folder and queues its
 * blocks for placement via {@link SchematicBuildStateManager}. Parsing (NBT decode + palette
 * resolution) happens off the server thread; queueing happens back on it, same split as
 * {@link GeminiWorldBuilder#buildFromBlueprint}.
 */
public final class SchematicImporter {

    private SchematicImporter() {}

    public static void importFromFile(String filename, ServerLevel serverLevel, BlockPos origin, ServerPlayer player) {
        Path file;
        try {
            file = SchematicPaths.resolve(filename);
        } catch (IllegalArgumentException e) {
            player.sendSystemMessage(Component.literal("§c" + e.getMessage()));
            return;
        }

        if (!Files.isRegularFile(file)) {
            player.sendSystemMessage(Component.literal(
                "§cNot found: " + filename + " — drop .litematic files into "
                    + SchematicPaths.getSchematicsFolder() + " and use /xeltacraft schematics to list them."));
            return;
        }

        player.sendSystemMessage(Component.literal("§7Reading " + filename + "..."));

        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return LitematicaSchematicReader.read(file);
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, Executors.newVirtualThreadPerTaskExecutor())
            .whenComplete((result, error) -> serverLevel.getServer().execute(() -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    XeltaCraftMod.LOGGER.warn("[SchematicImport] Failed to read {}: {}", filename, cause.getMessage());
                    player.sendSystemMessage(Component.literal("§cCould not read " + filename + ": " + cause.getMessage()));
                    return;
                }

                if (result.placements().isEmpty()) {
                    player.sendSystemMessage(Component.literal("§cNo blocks found in " + filename + " (empty or unreadable regions)."));
                    return;
                }

                if (result.skippedTileEntities() > 0 || result.skippedEntities() > 0) {
                    player.sendSystemMessage(Component.literal(
                        "§7Note: " + result.skippedTileEntities() + " container(s)/tile entities and "
                            + result.skippedEntities() + " entitie(s) will be placed as empty blocks/skipped — "
                            + "contents and mobs aren't imported yet."));
                }

                SchematicBuildStateManager.queueImport(serverLevel, origin, result.placements(), player, result.name());
            }));
    }
}
