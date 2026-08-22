package ai.xelta.craft.schematic;

/**
 * Packs/unpacks a block-state index array — a {@code long[]} where each entry occupies
 * {@code bitsPerEntry} bits, back-to-back with no per-long padding (an entry may straddle two
 * longs). This is the same packing Litematica itself uses (its own {@code LitematicaBitArray}),
 * which differs from vanilla Minecraft's structure-NBT packing. Used both to read a
 * {@code .litematic} file's own packed arrays ({@link LitematicaSchematicReader}) and to give
 * {@link ai.xelta.craft.item.SchematicItem} the same compact, palette-indexed storage instead of
 * one verbose NBT compound per block — the original per-block encoding could exceed the item
 * stack's network size limit on anything but a small structure.
 */
public final class LitematicaBitArray {
    private final long[] data;
    private final int bitsPerEntry;
    private final long mask;

    public LitematicaBitArray(int bitsPerEntry, long[] data) {
        this.bitsPerEntry = bitsPerEntry;
        this.data = data;
        this.mask = (1L << bitsPerEntry) - 1L;
    }

    public int get(int index) {
        long startOffset = (long) index * bitsPerEntry;
        int startArrIndex = (int) (startOffset >> 6);
        int endArrIndex = (int) (((index + 1L) * bitsPerEntry - 1L) >> 6);
        int startBitOffset = (int) (startOffset & 0x3FL);

        if (startArrIndex == endArrIndex) {
            return (int) (data[startArrIndex] >>> startBitOffset & mask);
        } else {
            int endOffset = 64 - startBitOffset;
            return (int) ((data[startArrIndex] >>> startBitOffset | data[endArrIndex] << endOffset) & mask);
        }
    }

    /** Bits needed per palette index for a palette of the given size (Litematica's own formula). */
    public static int bitsFor(int paletteSize) {
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
    }

    /** Packs {@code indices} (each < 2^bitsPerEntry) into a {@code long[]} using the same scheme {@link #get} reads. */
    public static long[] pack(int bitsPerEntry, int[] indices) {
        long totalBits = (long) indices.length * bitsPerEntry;
        long[] data = new long[(int) ((totalBits + 63L) / 64L)];
        long mask = (1L << bitsPerEntry) - 1L;

        for (int i = 0; i < indices.length; i++) {
            long value = indices[i] & mask;
            long startOffset = (long) i * bitsPerEntry;
            int startArrIndex = (int) (startOffset >> 6);
            int endArrIndex = (int) (((i + 1L) * bitsPerEntry - 1L) >> 6);
            int startBitOffset = (int) (startOffset & 0x3FL);

            data[startArrIndex] |= value << startBitOffset;
            if (startArrIndex != endArrIndex) {
                data[endArrIndex] |= value >>> (64 - startBitOffset);
            }
        }
        return data;
    }
}
