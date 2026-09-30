package org.webtrade.minecraftportsmod.fleet;

import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.webtrade.minecraftportsmod.vessel.VesselEntity;

/**
 * A vessel's cargo hold as a {@link Container}, reading and writing the fleet record directly, so whatever is
 * loaded travels with the vessel even while it sails "virtually".
 */
public final class HoldContainer implements Container {

    private final VesselRecord record;

    public HoldContainer(VesselRecord record) {
        this.record = record;
    }

    @Override
    public int getContainerSize() {
        return record.cargo().size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : record.cargo()) if (!s.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return record.cargo().get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack s = ContainerHelper.removeItem(record.cargo(), slot, amount);
        if (!s.isEmpty()) setChanged();
        return s;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(record.cargo(), slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        record.cargo().set(slot, stack);
        stack.limitSize(getMaxStackSize(stack));
        setChanged();
    }

    @Override
    public void setChanged() {
        FleetManager.changed();
    }

    /** Usable while standing next to the vessel's body or sitting in it. */
    @Override
    public boolean stillValid(Player player) {
        VesselEntity body = FleetManager.live(record.id());
        if (body == null || !record.isOwnedBy(player.getUUID())) return false;
        return player.getVehicle() == body || player.distanceToSqr(body) <= 8 * 8;
    }

    @Override
    public void clearContent() {
        record.cargo().replaceAll(s -> ItemStack.EMPTY);
        setChanged();
    }
}
