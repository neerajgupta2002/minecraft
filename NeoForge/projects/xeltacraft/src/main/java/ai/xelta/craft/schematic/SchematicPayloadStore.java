package ai.xelta.craft.schematic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, disk-backed home for a {@link ai.xelta.craft.item.SchematicItem}'s block payload
 * (palette + packed indices + tile entities) once it's too big to sync inline on the item stack
 * itself. The stack only ever carries a small UUID reference to a payload stored here — this is
 * what lets a schematic's size stop being bounded by the ~2 MiB network limit on synced item
 * components. Payloads are written under a fixed folder in the game directory and cached in
 * memory once loaded, so a repeat lookup (e.g. building the same held item twice) doesn't re-hit
 * disk.
 */
public final class SchematicPayloadStore {

    private static final String FOLDER_NAME = "xeltacraft_schematics/.payloads";
    private static final Map<UUID, CompoundTag> CACHE = new ConcurrentHashMap<>();

    private SchematicPayloadStore() {}

    private static Path folder() {
        Path folder = FMLPaths.GAMEDIR.get().resolve(FOLDER_NAME);
        try {
            Files.createDirectories(folder);
        } catch (IOException ignored) {
            // Surfaced by the subsequent read/write attempt instead.
        }
        return folder;
    }

    public static void save(UUID id, CompoundTag payload) throws IOException {
        save(id, payload, folder());
    }

    /** @return the payload, or {@code null} if it isn't on disk (never saved here, or wrong machine — e.g. a dedicated-server-only payload looked up from the client). */
    public static CompoundTag load(UUID id) {
        return load(id, folder());
    }

    /**
     * Folder-explicit variants of {@link #save} / {@link #load}, used directly by tests so they
     * don't depend on {@link FMLPaths#GAMEDIR}, which is only ever set inside a running FML/
     * NeoForge launch (client, server, or game-test) — not in a plain JUnit run.
     */
    static void save(UUID id, CompoundTag payload, Path folder) throws IOException {
        Files.createDirectories(folder);
        NbtIo.writeCompressed(payload, folder.resolve(id + ".nbt"));
        CACHE.put(id, payload);
    }

    static CompoundTag load(UUID id, Path folder) {
        CompoundTag cached = CACHE.get(id);
        if (cached != null) return cached;

        Path file = folder.resolve(id + ".nbt");
        if (!Files.isRegularFile(file)) return null;
        try {
            CompoundTag loaded = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            CACHE.put(id, loaded);
            return loaded;
        } catch (IOException e) {
            return null;
        }
    }
}
