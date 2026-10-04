package org.webtrade.minecraftportsmod.combat;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * One of a warship's crew, on her deck: the captain at the wheel, gunners at the guns of a side, marines at the rail
 * with their muskets, hands about the deck. Where a sailor is, is kept in the ship's own frame (across her and along
 * her): the ship carries her crew with her as she sails and turns; a sailor runs about her deck to where his work is.
 * The crew is the ship's (she keeps their number and brings them back aboard): their bodies are not saved.
 */
public class SailorEntity extends PathfinderMob {

    public enum Role {
        CAPTAIN, GUNNER, MARINE, HAND;

        public static Role of(int i) {
            return values()[Math.floorMod(i, values().length)];
        }
    }

    private static final EntityDataAccessor<Integer> DATA_SKIN = SynchedEntityData.defineId(SailorEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ROLE = SynchedEntityData.defineId(SailorEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_PIRATE = SynchedEntityData.defineId(SailorEntity.class, EntityDataSerializers.BOOLEAN);

    /** The ship (entity id), and the sailor's place on her: across (to starboard) and along (to the bow), blocks. */
    int ship = -1;
    double side, along;
    /** Where on her he is running to. */
    double toSide, toAlong;
    /** Facing outboard to this side at his post (-1 port, 1 starboard, 0: as he runs). */
    int faces;
    /** A gunner's side and gun. */
    int gunSide, gun;
    /** Ticks until his musket is loaded again; ticks until he next picks somewhere to go. */
    int musket, wander;
    /** How far he ran this tick (for his legs). */
    private float ran;

    public SailorEntity(EntityType<? extends SailorEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        noPhysics = true;
        setNoGravity(true);
        // (drawn where the server has him: on a moving deck a sailor drawn ticks behind would trail his ship)
        if (getInterpolation() != null) getInterpolation().setInterpolationLength(3);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return createMobAttributes().add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.3);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN, 0);
        builder.define(DATA_ROLE, Role.HAND.ordinal());
        builder.define(DATA_PIRATE, false);
    }

    public int skin() {
        return entityData.get(DATA_SKIN);
    }

    public Role role() {
        return Role.of(entityData.get(DATA_ROLE));
    }

    public boolean pirate() {
        return entityData.get(DATA_PIRATE);
    }

    /** Sets who he is, aboard which ship. */
    void setup(WarshipEntity w, Role role, int skin, double side, double along) {
        ship = w.getId();
        entityData.set(DATA_ROLE, role.ordinal());
        entityData.set(DATA_SKIN, skin);
        entityData.set(DATA_PIRATE, w.isPirate());
        this.side = toSide = side;
        this.along = toAlong = along;
        dress(role, w.isPirate());
        switch (role) {
            case CAPTAIN -> {
                setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                setCustomName(Component.translatable(w.isPirate() ? "entity.minecraftportsmod.sailor.pirate_captain" : "entity.minecraftportsmod.sailor.captain"));
                setCustomNameVisible(true);
            }
            case MARINE -> setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(org.webtrade.minecraftportsmod.registry.ModContent.MUSKET_ITEM));
            case GUNNER -> setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SHOVEL));
            default -> {
            }
        }
        place(w);
    }

    /** A piece of dyed leather to wear. */
    private static ItemStack worn(net.minecraft.world.item.Item item, int rgb) {
        ItemStack s = new ItemStack(item);
        s.set(net.minecraft.core.component.DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(rgb));
        return s;
    }

    /**
     * What he wears: a navy captain a dark blue coat and hat, a pirate captain a red coat and a black hat; the navy's
     * marines red coats; pirates a kerchief on their heads (red or black), the rest of a navy's crew their own clothes.
     */
    private void dress(Role role, boolean pirate) {
        switch (role) {
            case CAPTAIN -> {
                setItemSlot(EquipmentSlot.HEAD, worn(Items.LEATHER_HELMET, pirate ? 0x161616 : 0x1C2A4E));
                setItemSlot(EquipmentSlot.CHEST, worn(Items.LEATHER_CHESTPLATE, pirate ? 0x8C1C1C : 0x1C2A4E));
                setItemSlot(EquipmentSlot.LEGS, worn(Items.LEATHER_LEGGINGS, pirate ? 0x3A2A1A : 0xE8E2D0));
            }
            case MARINE -> {
                if (!pirate) setItemSlot(EquipmentSlot.CHEST, worn(Items.LEATHER_CHESTPLATE, 0xB02424));
                setItemSlot(EquipmentSlot.HEAD, worn(Items.LEATHER_HELMET, pirate ? 0x8C1C1C : 0x161616));
            }
            default -> {
                if (pirate) setItemSlot(EquipmentSlot.HEAD, worn(Items.LEATHER_HELMET, random.nextBoolean() ? 0x9C1E1E : 0x1A1A1A));
            }
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS}) setDropChance(slot, 0);
    }

    public WarshipEntity ship() {
        return level().getEntity(ship) instanceof WarshipEntity w ? w : null;
    }

    /** Where he stands on her now, at the height of her deck there. */
    void place(WarshipEntity w) {
        Vec3 p = w.at(side, along, w.deckAt(along));
        setPos(p.x, p.y, p.z);
    }

    // ------------------------------------------------------------------ his day aboard

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level)) return;
        WarshipEntity w = ship();
        if (w == null || w.isRemoved()) {
            discard();
            return;
        }
        if (w.sinking() > 0) {
            // overboard: he swims for it (and is gone)
            if (w.sinking() == 3) setDeltaMovement(w.starboard().scale(random.nextBoolean() ? 0.4 : -0.4).add(0, 0.4, 0));
            if (w.sinking() > 60) discard();
            noPhysics = false;
            setNoGravity(false);
            return;
        }
        // to his place, running
        double ds = toSide - side, da = toAlong - along, d = Math.sqrt(ds * ds + da * da);
        double step = Math.min(d, 0.16);
        ran = (float) step;
        if (d > 1e-3) {
            side += ds / d * step;
            along += da / d * step;
        }
        place(w);
        // which way he looks: where he runs, or out over the side at his post
        float shipYaw = w.getYRot();
        float yaw;
        if (d > 0.05) yaw = shipYaw + (float) Math.toDegrees(Math.atan2(-ds, da));
        else if (faces != 0) yaw = shipYaw + (faces > 0 ? -90 : 90);
        else if (role() == Role.CAPTAIN) yaw = shipYaw;
        else yaw = getYRot();
        setYRot(yaw);
        setYHeadRot(yaw);
        setYBodyRot(yaw);
        if (musket > 0) musket--;
        w.crewTick(level, this);
    }

    @Override
    public void calculateEntityAnimation(boolean flying) {
        // his legs go by how he runs on her deck, not by the ship carrying him
        walkAnimation.update(Math.min(ran * 6F, 1F), 0.4F, 1F);
    }

    /** Runs to a place on her. */
    void runTo(double side, double along, int faces) {
        toSide = side;
        toAlong = along;
        this.faces = faces;
    }

    boolean there() {
        return Math.abs(toSide - side) < 0.1 && Math.abs(toAlong - along) < 0.1;
    }

    /** A gunner at his gun while it is being loaded: ramming the ball home. */
    void work() {
        if (tickCount % 12 == 0) swing(InteractionHand.MAIN_HAND, true);
    }

    /** A marine fires his musket at someone. */
    void shoot(ServerLevel level, LivingEntity target) {
        if (musket > 0) return;
        musket = 50 + random.nextInt(30);
        Vec3 from = getEyePosition();
        Vec3 aim = target.getEyePosition().subtract(0, 0.4, 0).subtract(from);
        MusketBallEntity.fire(level, this, from, aim, 0.06);
        lookAt(target, 360, 360);
        swing(InteractionHand.MAIN_HAND, true);
    }

    // ------------------------------------------------------------------ hurt, killed

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        // (his own ship's guns and his shipmates don't hurt him)
        if (source.getEntity() instanceof SailorEntity s && s.ship == ship) return false;
        return super.hurtServer(level, source, amount);
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (level() instanceof ServerLevel && ship() != null) ship().crewLost(role());
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) {
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void dropEquipment(ServerLevel level) {
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
    }

    /** Nobody leads or rides him. */
    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    protected net.minecraft.world.InteractionResult mobInteract(Player player, InteractionHand hand) {
        return net.minecraft.world.InteractionResult.PASS;
    }

    static float clampYaw(float y) {
        return Mth.wrapDegrees(y);
    }
}
