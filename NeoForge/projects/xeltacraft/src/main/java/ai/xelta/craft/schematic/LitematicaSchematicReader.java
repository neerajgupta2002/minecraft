package ai.xelta.craft.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a Litematica {@code .litematic} schematic file (gzipped NBT) into a flat list of
 * block placements. Litematica's on-disk format: a "Regions" compound of named regions, each
 * with a block-state palette (the same Name/Properties shape as vanilla structure NBT) and a
 * packed {@code long[]} of per-block palette indices (see {@link LitematicaBitArray}).
 *
 * Tile entity data (chest inventories, sign text, ...) is restored — each region's
 * "TileEntities" list stores its own x/y/z/data verbatim (same shape a real {@link
 * net.minecraft.world.level.block.entity.BlockEntity} saves), which are kept and normalized the
 * same way block positions are, then reapplied by the caller once the corresponding block is
 * placed. Entities (mobs, item frames, ...) are still not restored — callers get a count of how
 * many were skipped so that limitation stays visible rather than silent.
 */
public final class LitematicaSchematicReader {

    public record Placement(BlockPos pos, BlockState state) {}

    public record TileEntityData(BlockPos pos, CompoundTag data) {}

    public record Result(
        String name,
        int totalBlocks,
        List<TileEntityData> tileEntities,
        int skippedEntities,
        List<Placement> placements) {}

    private LitematicaSchematicReader() {}

    public static Result read(Path file) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());

        CompoundTag metadata = root.getCompound("Metadata");
        String name = metadata.contains("Name") ? metadata.getString("Name") : file.getFileName().toString();

        CompoundTag regions = root.getCompound("Regions");
        int skippedEntities = 0;

        // First pass: collect every region's blocks (and tile entities) in schematic-local
        // space, tracking the overall minimum corner. The second pass shifts everything so the
        // structure's own minimum corner sits at local (0,0,0) — the caller then offsets that
        // onto wherever the player wants it built.
        List<Placement> raw = new ArrayList<>();
        List<TileEntityData> rawTileEntities = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;

        for (String regionName : regions.getAllKeys()) {
            Tag regionTag = regions.get(regionName);
            if (!(regionTag instanceof CompoundTag region)) continue;

            CompoundTag posTag = region.getCompound("Position");
            CompoundTag sizeTag = region.getCompound("Size");
            int posX = posTag.getInt("x"), posY = posTag.getInt("y"), posZ = posTag.getInt("z");
            int sizeX = sizeTag.getInt("x"), sizeY = sizeTag.getInt("y"), sizeZ = sizeTag.getInt("z");

            int absSizeX = Math.abs(sizeX), absSizeY = Math.abs(sizeY), absSizeZ = Math.abs(sizeZ);
            // A negative size means the region extends in the negative direction from Position —
            // normalize to the region's actual minimum corner so local indices always count up.
            int originX = sizeX >= 0 ? posX : posX + sizeX + 1;
            int originY = sizeY >= 0 ? posY : posY + sizeY + 1;
            int originZ = sizeZ >= 0 ? posZ : posZ + sizeZ + 1;

            List<BlockState> palette = readPalette(region);
            if (palette.isEmpty()) continue;

            long[] packed = region.getLongArray("BlockStates");
            int bitsPerEntry = LitematicaBitArray.bitsFor(palette.size());
            LitematicaBitArray bitArray = new LitematicaBitArray(bitsPerEntry, packed);

            for (int y = 0; y < absSizeY; y++) {
                for (int z = 0; z < absSizeZ; z++) {
                    for (int x = 0; x < absSizeX; x++) {
                        int index = (y * absSizeZ + z) * absSizeX + x;
                        int paletteIndex = bitArray.get(index);
                        if (paletteIndex < 0 || paletteIndex >= palette.size()) continue;
                        BlockState state = palette.get(paletteIndex);
                        if (state.isAir()) continue;

                        int wx = originX + x, wy = originY + y, wz = originZ + z;
                        raw.add(new Placement(new BlockPos(wx, wy, wz), state));
                        if (wx < minX) minX = wx;
                        if (wy < minY) minY = wy;
                        if (wz < minZ) minZ = wz;
                    }
                }
            }

            if (region.contains("TileEntities", 9)) {
                ListTag tileEntitiesTag = region.getList("TileEntities", 10);
                for (int i = 0; i < tileEntitiesTag.size(); i++) {
                    CompoundTag te = tileEntitiesTag.getCompound(i).copy();
                    if (!te.contains("x") || !te.contains("y") || !te.contains("z")) continue;
                    rawTileEntities.add(new TileEntityData(
                        new BlockPos(te.getInt("x"), te.getInt("y"), te.getInt("z")), te));
                }
            }
            if (region.contains("Entities", 9)) {
                skippedEntities += region.getList("Entities", 10).size();
            }
        }

        if (raw.isEmpty()) {
            return new Result(name, 0, List.of(), skippedEntities, List.of());
        }

        List<Placement> placements = new ArrayList<>(raw.size());
        for (Placement p : raw) {
            placements.add(new Placement(
                new BlockPos(p.pos().getX() - minX, p.pos().getY() - minY, p.pos().getZ() - minZ),
                p.state()));
        }

        List<TileEntityData> tileEntities = new ArrayList<>(rawTileEntities.size());
        for (TileEntityData te : rawTileEntities) {
            tileEntities.add(new TileEntityData(
                new BlockPos(te.pos().getX() - minX, te.pos().getY() - minY, te.pos().getZ() - minZ),
                te.data()));
        }

        return new Result(name, placements.size(), tileEntities, skippedEntities, placements);
    }

    private static List<BlockState> readPalette(CompoundTag region) {
        List<BlockState> palette = new ArrayList<>();
        if (!region.contains("BlockStatePalette", 9)) return palette;
        ListTag list = region.getList("BlockStatePalette", 10);
        var blockLookup = BuiltInRegistries.BLOCK.asLookup();
        for (int i = 0; i < list.size(); i++) {
            palette.add(NbtUtils.readBlockState(blockLookup, list.getCompound(i)));
        }
        return palette;
    }
}
