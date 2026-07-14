package ai.xelta.craft.menu;

import ai.xelta.craft.init.ModMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * XeltaTerminalMenu — server-side container logic.
 *
 * Slot layout:
 *   Slot 0 (SLOT_INPUT)  — Player places a blank Photo Slide here before generating
 *   Slot 1 (SLOT_OUTPUT) — Generated Photo Slide appears here after generation
 *   Slots 2–28           — Player inventory (3 rows)
 *   Slots 29–38          — Player hotbar
 */
public class XeltaTerminalMenu extends AbstractContainerMenu {

    public static final int SLOT_INPUT  = 0;
    public static final int SLOT_OUTPUT = 1;

    private final IItemHandler terminalInventory;
    private final BlockPos blockPos;

    // Called from server (via BlockEntity.createMenu)
    public XeltaTerminalMenu(int containerId, Inventory playerInventory,
                              ItemStackHandler terminalHandler, BlockPos blockPos) {
        super(ModMenuTypes.XELTA_TERMINAL.get(), containerId);
        this.terminalInventory = terminalHandler;
        this.blockPos          = blockPos;

        // ---- Terminal slots ----
        addSlot(new SlotItemHandler(terminalHandler, SLOT_INPUT, 44, 35));   // Input slot
        addSlot(new OutputSlot(terminalHandler, SLOT_OUTPUT, 116, 35));      // Output slot (non-insertable)

        // ---- Player inventory (3 rows) ----
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9,
                    8 + col * 18, 84 + row * 18));
            }
        }

        // ---- Player hotbar ----
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
    }

    // Called from client (network deserialisation)
    public XeltaTerminalMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        this(containerId, playerInventory,
            new ItemStackHandler(2),       // Dummy handler on client side
            extraData.readBlockPos());
    }

    public BlockPos getBlockPos() { return blockPos; }

    // Standard shift-click logic
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);

        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();

            if (index < 2) {
                // Move from terminal slots → player inventory
                if (!moveItemStackTo(stack, 2, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // Move from player → terminal input slot
                if (!moveItemStackTo(stack, SLOT_INPUT, SLOT_INPUT + 1, false)) {
                    return ItemStack.EMPTY;
                }
            }

            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
        }

        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(
            blockPos.getX() + 0.5,
            blockPos.getY() + 0.5,
            blockPos.getZ() + 0.5) < 64.0;
    }

    // Output slot — player can take from but cannot insert into
    private static class OutputSlot extends SlotItemHandler {
        public OutputSlot(IItemHandler handler, int index, int x, int y) {
            super(handler, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;   // Can't put items into output slot manually
        }
    }
}
