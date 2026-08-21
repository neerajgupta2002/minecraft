package ai.xelta.craft.schematic;

/**
 * Unpacks Litematica's block-state index array — a {@code long[]} where each entry occupies
 * {@code bitsPerEntry} bits, packed back-to-back with no per-long padding (an entry may straddle
 * two longs). This is the same packing Litematica itself uses (its own {@code LitematicaBitArray}),
 * which differs from vanilla Minecraft's structure-NBT packing.
 */
final class LitematicaBitArray {
    private final long[] data;
    private final int bitsPerEntry;
    private final long mask;

    LitematicaBitArray(int bitsPerEntry, long[] data) {
        this.bitsPerEntry = bitsPerEntry;
        this.data = data;
        this.mask = (1L << bitsPerEntry) - 1L;
    }

    int get(int index) {
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
    static int bitsFor(int paletteSize) {
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
    }
}
