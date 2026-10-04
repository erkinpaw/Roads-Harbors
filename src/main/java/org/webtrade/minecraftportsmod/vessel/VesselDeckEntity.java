package org.webtrade.minecraftportsmod.vessel;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * An invisible solid slab at deck height, so players and animals can walk on a moored ship instead of falling
 * through its model (the ship's own hitbox is only boat-sized). A few of these are laid along the hull by the
 * {@link VesselEntity} while it is not under way; they are never saved and vanish with their ship.
 */
public class VesselDeckEntity extends Entity {

    public static final float HEIGHT = 0.6F;
    private static final EntityDataAccessor<Float> DATA_SIZE =
            SynchedEntityData.defineId(VesselDeckEntity.class, EntityDataSerializers.FLOAT);

    /** The ship it belongs to (a fleet's vessel, a warship). */
    private Entity ship;

    public VesselDeckEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        setNoGravity(true);
    }

    public void attach(Entity ship, float size) {
        this.ship = ship;
        entityData.set(DATA_SIZE, size);
        refreshDimensions();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SIZE, 2.0F);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (DATA_SIZE.equals(accessor)) refreshDimensions();
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.fixed(entityData.get(DATA_SIZE), HEIGHT);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && (ship == null || ship.isRemoved())) discard();
    }

    /** Solid for everyone except ships and other deck slabs. */
    @Override
    public boolean canBeCollidedWith(Entity other) {
        return !(other instanceof VesselEntity) && !(other instanceof VesselDeckEntity)
                && !(other instanceof org.webtrade.minecraftportsmod.combat.WarshipEntity) && !(other instanceof org.webtrade.minecraftportsmod.combat.CannonballEntity);
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(Entity entity) {
    }

    @Override
    public void push(double x, double y, double z) {
    }

    @Override
    public void push(Vec3 impulse) {
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
    }
}
