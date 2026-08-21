package ai.xelta.craft.schematic;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The isolated drop folder for schematic files (e.g. {@code .litematic}) — same convention as
 * {@link ai.xelta.craft.sdk.AssetCacheManager}'s cache folder: a fixed subdirectory under the
 * game directory ({@code run/} in a dev environment, the actual game/server folder otherwise),
 * created on first use.
 */
public final class SchematicPaths {

    private static final String FOLDER_NAME = "xeltacraft_schematics";

    private SchematicPaths() {}

    public static Path getSchematicsFolder() {
        Path folder = FMLPaths.GAMEDIR.get().resolve(FOLDER_NAME);
        try {
            Files.createDirectories(folder);
        } catch (IOException ignored) {
            // If this fails, the subsequent file-read attempt will surface a clear error anyway.
        }
        return folder;
    }

    /**
     * Resolves a bare filename against the schematics folder, rejecting anything that would
     * escape it (e.g. {@code ../../whatever}) — commands only ever hand the player a filename
     * picked from {@link #listSchematicFiles()}, not an arbitrary path.
     */
    public static Path resolve(String filename) {
        Path folder = getSchematicsFolder();
        Path resolved = folder.resolve(filename).normalize();
        if (!resolved.startsWith(folder)) {
            throw new IllegalArgumentException("Invalid filename: " + filename);
        }
        return resolved;
    }

    public static List<String> listSchematicFiles() {
        Path folder = getSchematicsFolder();
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".litematic"))
                .map(p -> p.getFileName().toString())
                .sorted(Comparator.naturalOrder())
                .forEach(names::add);
        } catch (IOException ignored) {
            // Empty/unreadable folder — return what we have (nothing) rather than fail the suggestion.
        }
        return names;
    }
}
