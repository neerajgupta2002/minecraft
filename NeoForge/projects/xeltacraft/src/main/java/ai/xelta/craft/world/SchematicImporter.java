package ai.xelta.craft.world;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.init.ModItems;
import ai.xelta.craft.item.SchematicItem;
import ai.xelta.craft.schematic.LitematicaSchematicReader;
import ai.xelta.craft.schematic.SchematicPaths;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Reads a {@code .litematic} file from the {@link SchematicPaths} drop folder and hands the
 * player a {@link SchematicItem} loaded with its blocks — nothing is placed in the world until
 * they right-click it somewhere. Parsing (NBT decode + palette resolution) happens off the
 * server thread; giving the item happens back on it.
 */
public final class SchematicImporter {

    private SchematicImporter() {}

    public static void importFromFile(String filename, ServerLevel serverLevel, ServerPlayer player) {
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

                if (!result.tileEntities().isEmpty()) {
                    player.sendSystemMessage(Component.literal(
                        "§7" + result.tileEntities().size() + " container(s)/tile entities will be restored "
                            + "with their contents (chest items, sign text, ...)."));
                }
                if (result.skippedEntities() > 0) {
                    player.sendSystemMessage(Component.literal(
                        "§7Note: " + result.skippedEntities() + " entitie(s) (mobs, item frames, ...) will be skipped."));
                }

                ItemStack stack = SchematicItem.create(result.name(), result.placements(), result.tileEntities(), ModItems.SCHEMATIC.get());
                if (!SchematicItem.hasSchematic(stack)) {
                    player.sendSystemMessage(Component.literal(
                        "§c\"" + result.name() + "\" (" + result.placements().size() + " blocks) is too large "
                            + "to carry as a schematic item right now — try a smaller structure."));
                    return;
                }

                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
                player.sendSystemMessage(Component.literal(
                    "§aSchematic ready: \"" + result.name() + "\" (" + result.placements().size()
                        + " blocks) — right-click a block to build it there."));
            }));
    }
}
