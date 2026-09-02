package ai.xelta.craft.item;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.schematic.LitematicaBitArray;
import ai.xelta.craft.schematic.LitematicaSchematicReader.Placement;
import ai.xelta.craft.schematic.LitematicaSchematicReader.TileEntityData;
import ai.xelta.craft.schematic.SchematicPayloadStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SchematicItem — a build "loaded into a held item" instead of placed immediately, whether it
 * came from a {@code .litematic} import or an AI-generated blueprint. Right-click a block face
 * to build it there: the position you're aiming at (offset onto the clicked face, same as
 * placing a normal block) becomes the schematic's own minimum corner. Aim wherever you like
 * before confirming — that's the "adjust position after generation" step.
 *
 * Block data is stored the same way a {@code .litematic} file itself does — a deduplicated
 * block-state palette plus a bit-packed array of per-cell palette indices over the structure's
 * bounding box (see {@link LitematicaBitArray}) — instead of one verbose NBT compound per block.
 * The first version of this item stored one compound per block; on anything past a few thousand
 * blocks that pushed the stack's {@link DataComponents#CUSTOM_DATA} past the client/server
 * container-sync packet's size limit and disconnected the player with a decoder error the moment
 * the item entered their inventory. The palette+bitpack form is orders of magnitude smaller for
 * the same structure, since most builds reuse only a handful of distinct block states.
 */
public class SchematicItem extends Item {

    private static final String TAG_NAME = "xelta_schem_name";
    private static final String TAG_BLOCK_COUNT = "xelta_schem_block_count";
    private static final String TAG_PAYLOAD_ID = "xelta_schem_payload_id";
    private static final String TAG_MIN = "xelta_schem_min";
    private static final String TAG_SIZE = "xelta_schem_size";
    private static final String TAG_PALETTE = "xelta_schem_palette";
    private static final String TAG_INDICES = "xelta_schem_indices";
    private static final String TAG_TILE_ENTITIES = "xelta_schem_tile_entities";

    /**
     * Threshold, well under the 2 MiB ({@code 2097152} bytes) budget {@code ByteBufCodecs}' per-
     * component NBT decoder enforces on the network, below which the block payload (palette +
     * packed indices + tile entities) is kept inline on the item stack itself — same as before,
     * so ghost preview and tooltips keep working with zero extra I/O for ordinary-sized builds.
     * A real-world schematic that measured ~703,000 bytes via a raw {@code NbtIo.write} of this
     * component still crashed the same way on the network, so whatever the actual wire size is,
     * it's substantially larger than that raw measurement suggested (the exact overhead wasn't
     * pinned down) — this threshold carries a large safety margin below 703,000 for that reason,
     * not just below 2,097,152.
     * <p>
     * Above this size, the payload is written to disk via {@link SchematicPayloadStore} instead
     * and the stack only carries a small reference (a UUID) — there's no upper bound on schematic
     * size anymore, just a point past which the stack stops carrying the data itself. The one
     * trade-off: {@code SchematicGhostRenderer}'s client-side preview and any other purely
     * client-side reader can only resolve a disk-backed payload when the client and server share
     * a game directory (singleplayer) — on a real dedicated server it has nothing to read, same
     * as today's existing {@code MAX_GHOST_BLOCKS} cutoff already means for very large builds.
     */
    private static final int MAX_SAFE_NBT_BYTES = 250_000;

    public SchematicItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    // =========================================================
    // Static accessors — DataComponents API (MC 1.21+)
    // =========================================================

    public static ItemStack create(String name, List<Placement> placements, List<TileEntityData> tileEntities, Item item) {
        ItemStack stack = new ItemStack(item);
        setSchematic(stack, name, placements, tileEntities);
        return stack;
    }

    /**
     * @return {@code true} if the schematic was stored and referenced on the stack; {@code false}
     *         only if a too-large payload couldn't be written to disk (the stack is left with no
     *         schematic data in that case — check {@link #hasSchematic}). Unlike before, a large
     *         block count on its own is no longer a reason to refuse — see the class-level note
     *         on {@link #MAX_SAFE_NBT_BYTES}.
     */
    public static boolean setSchematic(ItemStack stack, String name, List<Placement> placements, List<TileEntityData> tileEntities) {
        CompoundTag root = new CompoundTag();
        root.putString(TAG_NAME, name);

        if (placements.isEmpty()) {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
            return true;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Placement p : placements) {
            minX = Math.min(minX, p.pos().getX()); maxX = Math.max(maxX, p.pos().getX());
            minY = Math.min(minY, p.pos().getY()); maxY = Math.max(maxY, p.pos().getY());
            minZ = Math.min(minZ, p.pos().getZ()); maxZ = Math.max(maxZ, p.pos().getZ());
        }
        int sizeX = maxX - minX + 1, sizeY = maxY - minY + 1, sizeZ = maxZ - minZ + 1;

        // Index 0 is always air — cells the placement list doesn't cover default to it.
        Map<BlockState, Integer> paletteIndex = new LinkedHashMap<>();
        List<BlockState> palette = new ArrayList<>();
        paletteIndex.put(Blocks.AIR.defaultBlockState(), 0);
        palette.add(Blocks.AIR.defaultBlockState());

        int[] indices = new int[sizeX * sizeY * sizeZ];
        for (Placement p : placements) {
            int idx = paletteIndex.computeIfAbsent(p.state(), st -> {
                palette.add(st);
                return palette.size() - 1;
            });
            int lx = p.pos().getX() - minX, ly = p.pos().getY() - minY, lz = p.pos().getZ() - minZ;
            indices[(ly * sizeZ + lz) * sizeX + lx] = idx;
        }

        int bits = LitematicaBitArray.bitsFor(palette.size());
        long[] packed = LitematicaBitArray.pack(bits, indices);

        CompoundTag payload = new CompoundTag();
        payload.putIntArray(TAG_MIN, new int[]{minX, minY, minZ});
        payload.putIntArray(TAG_SIZE, new int[]{sizeX, sizeY, sizeZ});
        ListTag paletteTag = new ListTag();
        for (BlockState state : palette) paletteTag.add(NbtUtils.writeBlockState(state));
        payload.put(TAG_PALETTE, paletteTag);
        payload.putLongArray(TAG_INDICES, packed);

        if (!tileEntities.isEmpty()) {
            ListTag tileEntitiesTag = new ListTag();
            for (TileEntityData te : tileEntities) {
                CompoundTag entry = new CompoundTag();
                entry.putInt("x", te.pos().getX());
                entry.putInt("y", te.pos().getY());
                entry.putInt("z", te.pos().getZ());
                entry.put("data", te.data());
                tileEntitiesTag.add(entry);
            }
            payload.put(TAG_TILE_ENTITIES, tileEntitiesTag);
        }

        int encodedBytes;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.write(payload, new DataOutputStream(bos));
            encodedBytes = bos.size();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        root.putInt(TAG_BLOCK_COUNT, placements.size());

        if (encodedBytes <= MAX_SAFE_NBT_BYTES) {
            root.merge(payload);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
            return true;
        }

        UUID payloadId = UUID.randomUUID();
        try {
            SchematicPayloadStore.save(payloadId, payload);
        } catch (IOException e) {
            XeltaCraftMod.LOGGER.warn(
                "[SchematicItem] Failed to write disk payload for '{}' ({} blocks, {} bytes encoded): {}",
                name, placements.size(), encodedBytes, e.getMessage());
            return false;
        }

        XeltaCraftMod.LOGGER.info(
            "[SchematicItem] '{}' ({} blocks, {} bytes encoded) stored on disk as {} — over the {}-byte inline threshold.",
            name, placements.size(), encodedBytes, payloadId, MAX_SAFE_NBT_BYTES);
        root.putUUID(TAG_PAYLOAD_ID, payloadId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        return true;
    }

    public static String getSchematicName(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString(TAG_NAME);
    }

    /**
     * Resolves the compound that actually holds {@link #TAG_MIN}/{@link #TAG_SIZE}/
     * {@link #TAG_PALETTE}/{@link #TAG_INDICES}/{@link #TAG_TILE_ENTITIES} — either {@code root}
     * itself (payload was small enough to store inline) or the disk-backed payload {@code root}
     * only references by {@link #TAG_PAYLOAD_ID}. Returns {@code null} if neither is present, or
     * if a disk-backed payload can't be resolved from here (e.g. looked up client-side against a
     * dedicated server, which has no access to the server's disk).
     */
    private static CompoundTag resolvePayload(CompoundTag root) {
        if (root.contains(TAG_INDICES, 12)) return root; // 12 = LongArrayTag; stored inline
        if (root.hasUUID(TAG_PAYLOAD_ID)) return SchematicPayloadStore.load(root.getUUID(TAG_PAYLOAD_ID));
        return null;
    }

    public static List<Placement> getPlacements(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return List.of();

        CompoundTag payload = resolvePayload(data.copyTag());
        if (payload == null) return List.of();

        int[] min = payload.getIntArray(TAG_MIN);
        int[] size = payload.getIntArray(TAG_SIZE);
        if (min.length != 3 || size.length != 3) return List.of();
        int minX = min[0], minY = min[1], minZ = min[2];
        int sizeX = size[0], sizeY = size[1], sizeZ = size[2];

        var blockLookup = BuiltInRegistries.BLOCK.asLookup();
        ListTag paletteTag = payload.getList(TAG_PALETTE, 10);
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(i)));
        }
        if (palette.isEmpty()) return List.of();

        long[] packed = payload.getLongArray(TAG_INDICES);
        int bits = LitematicaBitArray.bitsFor(palette.size());
        LitematicaBitArray bitArray = new LitematicaBitArray(bits, packed);

        List<Placement> placements = new ArrayList<>();
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    int idx = bitArray.get((y * sizeZ + z) * sizeX + x);
                    if (idx <= 0 || idx >= palette.size()) continue; // 0 = air
                    BlockState state = palette.get(idx);
                    if (state.isAir()) continue;
                    placements.add(new Placement(new BlockPos(x + minX, y + minY, z + minZ), state));
                }
            }
        }
        return placements;
    }

    public static List<TileEntityData> getTileEntities(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return List.of();

        CompoundTag payload = resolvePayload(data.copyTag());
        if (payload == null || !payload.contains(TAG_TILE_ENTITIES, 9)) return List.of();

        ListTag tileEntitiesTag = payload.getList(TAG_TILE_ENTITIES, 10);
        List<TileEntityData> result = new ArrayList<>(tileEntitiesTag.size());
        for (int i = 0; i < tileEntitiesTag.size(); i++) {
            CompoundTag entry = tileEntitiesTag.getCompound(i);
            BlockPos pos = new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            result.add(new TileEntityData(pos, entry.getCompound("data")));
        }
        return result;
    }

    public static boolean hasSchematic(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        return data.copyTag().getInt(TAG_BLOCK_COUNT) > 0;
    }

    // =========================================================
    // Build on right-click
    // =========================================================

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return InteractionResult.PASS;
        }

        ItemStack stack = context.getItemInHand();
        List<Placement> placements = getPlacements(stack);
        if (placements.isEmpty()) {
            player.sendSystemMessage(Component.literal("§cThis schematic item is empty."));
            return InteractionResult.FAIL;
        }

        BlockPos origin = context.getClickedPos().relative(context.getClickedFace());
        String name = getSchematicName(stack);
        if (name.isBlank()) name = "schematic";

        ai.xelta.craft.world.SchematicBuildStateManager.queueImport(
            (net.minecraft.server.level.ServerLevel) context.getLevel(), origin, placements,
            getTileEntities(stack), player, name);

        if (!player.isCreative()) {
            stack.shrink(1);
        }
        return InteractionResult.SUCCESS;
    }

    // =========================================================
    // Tooltip / display name
    // =========================================================

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                 List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        String name = getSchematicName(stack);
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        int count = data != null ? data.copyTag().getInt(TAG_BLOCK_COUNT) : 0;
        if (count > 0) {
            tooltip.add(Component.literal("§7\"" + name + "\" — " + count + " blocks"));
            tooltip.add(Component.literal("§aRight-click a block to build it there"));
        } else {
            tooltip.add(Component.literal("§cEmpty — use /xeltacraft importschematic or the Terminal"));
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        String name = getSchematicName(stack);
        return name.isBlank()
            ? Component.translatable("item.xeltacraft.schematic")
            : Component.literal("Schematic: " + name);
    }
}
