package org.webtrade.minecraftportsmod.vessel;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.webtrade.minecraftportsmod.registry.ModContent;

/**
 * The cargo hold screen: the hold's slots in rows of nine (a short last row is centred), the player's inventory below.
 */
public class HoldMenu extends AbstractContainerMenu {

    public static final int SLOT = 18;
    private final Container hold;
    private final int holdSize;

    /** Client side: an empty stand-in container of the right size. */
    public HoldMenu(int id, Inventory playerInventory, int size) {
        this(id, playerInventory, new SimpleContainer(size));
    }

    public HoldMenu(int id, Inventory playerInventory, Container hold) {
        super(ModContent.HOLD_MENU, id);
        this.hold = hold;
        this.holdSize = hold.getContainerSize();
        hold.startOpen(playerInventory.player);
        int rows = rows(holdSize);
        for (int i = 0; i < holdSize; i++) {
            int row = i / 9, col = i % 9;
            int inRow = Math.min(9, holdSize - row * 9);
            int x = 8 + (9 - inRow) * SLOT / 2 + col * SLOT;
            addSlot(new Slot(hold, i, x, 18 + row * SLOT));
        }
        addStandardInventorySlots(playerInventory, 8, inventoryTop(rows));
    }

    public static int rows(int size) {
        return Math.max(1, (size + 8) / 9);
    }

    public static int inventoryTop(int rows) {
        return 18 + rows * SLOT + 14;
    }

    public int holdSize() {
        return holdSize;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < holdSize) {
                if (!moveItemStackTo(stack, holdSize, slots.size(), true)) return ItemStack.EMPTY;
            } else if (!moveItemStackTo(stack, 0, holdSize, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
            else slot.setChanged();
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return hold.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        hold.stopOpen(player);
    }
}
