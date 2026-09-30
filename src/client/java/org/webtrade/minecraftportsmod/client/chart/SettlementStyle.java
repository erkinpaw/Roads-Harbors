package org.webtrade.minecraftportsmod.client.chart;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.economy.Profession;
import org.webtrade.minecraftportsmod.economy.Specialization;

/** Colours and icons of settlements on the chart and in the town hall. */
final class SettlementStyle {

    private SettlementStyle() {
    }

    /** RGB. */
    static int specColor(Specialization spec) {
        return switch (spec) {
            case LUMBER -> 0x3F7A3A;
            case MINING -> 0x4E5566;
            case POTTERY -> 0xB5552E;
            case FISHING -> 0x2F6FA0;
            case FARMING -> 0xC9A227;
            case QUARRY -> 0x8A8478;
        };
    }

    static ItemStack professionIcon(Profession p) {
        return new ItemStack(switch (p) {
            case WOODCUTTER -> Items.IRON_AXE;
            case SAWYER -> Items.OAK_PLANKS;
            case MINER -> Items.IRON_PICKAXE;
            case SMELTER -> Items.FURNACE;
            case SMITH -> Items.ANVIL;
            case POTTER -> Items.DECORATED_POT;
            case MASON -> Items.STONE_BRICKS;
            case FARMER -> Items.IRON_HOE;
            case BAKER -> Items.BREAD;
            case FISHER -> Items.FISHING_ROD;
            case SHEPHERD -> Items.SHEARS;
            case MERCHANT -> Items.EMERALD;
        });
    }
}
