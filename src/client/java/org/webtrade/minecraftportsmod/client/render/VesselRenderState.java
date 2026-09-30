package org.webtrade.minecraftportsmod.client.render;

import net.minecraft.client.renderer.entity.state.BoatRenderState;

/** Everything needed to draw a vessel: the boat state plus hull class, wood and whether it is under way. */
public class VesselRenderState extends BoatRenderState {
    public int tier;
    public String hullId = "minecraft:oak_boat";
    public boolean sailing;
    /** Barrels, crates and sacks on deck (a settlement's vessel with goods aboard). */
    public final java.util.List<net.minecraft.client.renderer.item.ItemStackRenderState> cargo = new java.util.ArrayList<>();
}
