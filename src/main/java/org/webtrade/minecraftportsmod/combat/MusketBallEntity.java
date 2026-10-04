package org.webtrade.minecraftportsmod.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A musket ball: fast and nearly straight. It hurts whoever it strikes (not the one who fired it, nor his shipmates),
 * chips at a hull (no harm to her), stops in the land, falls into the sea.
 */
public class MusketBallEntity extends Entity {

    static final double SPEED = 3.5, GRAVITY = 0.012, DRAG = 0.995;
    static final float DAMAGE = 7;

    /** Balls fired, and the hits they made, since the game started (tests). */
    public static int fired, struck;

    /** Who fired it (an entity id), and his ship (his shipmates are spared). */
    int shooter = -1, ship = -1;

    public MusketBallEntity(EntityType<?> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    /** Fires a ball from a point along a line, a little off it ({@code spread}): the flash, the smoke, the crack. */
    public static void fire(ServerLevel level, LivingEntity by, Vec3 from, Vec3 aim, double spread) {
        MusketBallEntity b = new MusketBallEntity(org.webtrade.minecraftportsmod.registry.ModContent.MUSKET_BALL, level);
        Vec3 dir = aim.normalize().add((level.getRandom().nextDouble() - 0.5) * spread, (level.getRandom().nextDouble() - 0.5) * spread,
                (level.getRandom().nextDouble() - 0.5) * spread).normalize();
        b.setPos(from.add(dir.scale(0.6)));
        b.setDeltaMovement(dir.scale(SPEED));
        b.shooter = by.getId();
        b.ship = by instanceof SailorEntity s ? s.ship : by.getVehicle() != null ? by.getVehicle().getId() : -1;
        level.addFreshEntity(b);
        fired++;
        Vec3 m = from.add(dir.scale(0.9));
        level.sendParticles(ParticleTypes.SMOKE, m.x, m.y, m.z, 8, 0.1, 0.1, 0.1, 0.02);
        level.sendParticles(ParticleTypes.POOF, m.x, m.y, m.z, 2, 0.05, 0.05, 0.05, 0.01);
        level.sendParticles(ParticleTypes.SMALL_FLAME, m.x, m.y, m.z, 2, 0.02, 0.02, 0.02, 0.01);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.NEUTRAL, 3F, 0.6F + level.getRandom().nextFloat() * 0.2F);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.CROSSBOW_SHOOT, SoundSource.NEUTRAL, 2F, 0.5F);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public void tick() {
        super.tick();
        Vec3 from = position(), v = getDeltaMovement(), to = from.add(v);
        if (level() instanceof ServerLevel level) {
            if (tickCount > 60 || hit(level, from, to)) {
                discard();
                return;
            }
        } else if (tickCount > 1) {
            level().addParticle(ParticleTypes.SMOKE, from.x, from.y, from.z, 0, 0, 0);
        }
        setPos(to);
        setDeltaMovement(v.x * DRAG, (v.y - GRAVITY) * DRAG, v.z * DRAG);
    }

    private boolean hit(ServerLevel level, Vec3 from, Vec3 to) {
        double len = to.subtract(from).length();
        int steps = Math.max(1, (int) Math.ceil(len / 0.3));
        for (int i = 1; i <= steps; i++) {
            Vec3 p = from.lerp(to, i / (double) steps);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(p, p).inflate(0.35))) {
                if (e.getId() == shooter || e instanceof SailorEntity s && s.ship == ship && ship >= 0) continue;
                if (e.getVehicle() != null && e.getVehicle().getId() == ship) continue;
                if (!e.getBoundingBox().inflate(0.1).contains(p)) continue;
                Entity by = level.getEntity(shooter);
                DamageSource src = level.damageSources().thrown(this, by);
                e.hurtServer(level, src, DAMAGE);
                struck++;
                level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, p.x, p.y, p.z, 3, 0.1, 0.1, 0.1, 0.1);
                return true;
            }
            for (WarshipEntity s : level.getEntitiesOfClass(WarshipEntity.class, new AABB(p, p).inflate(12))) {
                if (s.getId() == ship || !s.hits(p)) continue;
                // (into her side: a splinter, nothing more)
                level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK,
                        net.minecraft.world.level.block.Blocks.DARK_OAK_PLANKS.defaultBlockState()), p.x, p.y, p.z, 4, 0.1, 0.1, 0.1, 0.1);
                return true;
            }
            BlockPos b = BlockPos.containing(p);
            BlockState st = level.getBlockState(b);
            if (!st.getFluidState().isEmpty()) {
                level.sendParticles(ParticleTypes.SPLASH, p.x, b.getY() + 1, p.z, 6, 0.1, 0.05, 0.1, 0.1);
                return true;
            }
            if (!st.getCollisionShape(level, b).isEmpty()) {
                level.sendParticles(ParticleTypes.POOF, p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.01);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
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
