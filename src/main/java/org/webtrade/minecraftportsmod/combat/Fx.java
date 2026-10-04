package org.webtrade.minecraftportsmod.combat;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The sights and sounds of a sea fight. The sounds are loud (heard far off: a captain's camera stands well out behind
 * his ship, and the game hears from the camera), the smoke and the fire and the splinters big.
 */
public final class Fx {

    private Fx() {
    }

    /** How loud a fight's sounds are: a sound is heard out to sixteen blocks for each unit of it. */
    static final float GUN = 6F, HIT = 5F, SPLASH = 4F, CREAK = 4F;

    static void sound(ServerLevel level, Vec3 p, SoundEvent s, float volume, float pitch) {
        level.playSound(null, p.x, p.y, p.z, s, SoundSource.NEUTRAL, volume, pitch);
    }

    static void sound(ServerLevel level, Vec3 p, Holder<SoundEvent> s, float volume, float pitch) {
        level.playSound(null, p.x, p.y, p.z, s, SoundSource.NEUTRAL, volume, pitch);
    }

    /** A gun goes off: the flash and the smoke out of its muzzle, the boom. */
    static void gun(ServerLevel level, Vec3 muzzle, Vec3 out, RandomSource rnd) {
        Vec3 f = muzzle.add(out.scale(0.8));
        level.sendParticles(net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFD27F), f.x, f.y, f.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.FLAME, f.x, f.y, f.z, 10, 0.15, 0.15, 0.15, 0.06);
        level.sendParticles(ParticleTypes.LAVA, f.x, f.y, f.z, 2, 0.1, 0.1, 0.1, 0);
        // the cloud rolls out of the muzzle and hangs over the water
        for (int i = 1; i <= 4; i++) {
            Vec3 c = muzzle.add(out.scale(0.6 + i * 0.7));
            level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 4, 0.3 + i * 0.1, 0.25, 0.3 + i * 0.1, 0.02);
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, c.x, c.y, c.z, 1, 0.3, 0.1, 0.3, 0.01);
        }
        level.sendParticles(ParticleTypes.CLOUD, f.x, f.y, f.z, 8, 0.6, 0.3, 0.6, 0.03);
        sound(level, muzzle, SoundEvents.GENERIC_EXPLODE, GUN, 0.6F + rnd.nextFloat() * 0.2F);
        sound(level, muzzle, SoundEvents.FIREWORK_ROCKET_BLAST_FAR, GUN, 0.5F);
    }

    /** A ball holes a ship: the burst, fire, splinters of her planks, the crash. */
    static void hit(ServerLevel level, Vec3 p, RandomSource rnd) {
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 18, 0.5, 0.4, 0.5, 0.08);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y + 0.5, p.z, 16, 0.6, 0.6, 0.6, 0.05);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DARK_OAK_PLANKS.defaultBlockState()), p.x, p.y, p.z, 40, 0.6, 0.6, 0.6, 0.35);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SPRUCE_PLANKS.defaultBlockState()), p.x, p.y, p.z, 20, 0.4, 0.4, 0.4, 0.5);
        sound(level, p, SoundEvents.GENERIC_EXPLODE, HIT, 0.9F + rnd.nextFloat() * 0.2F);
        sound(level, p, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, HIT, 0.6F + rnd.nextFloat() * 0.2F);
    }

    /** A ball falls into the sea: a tall fountain, spray. */
    static void splash(ServerLevel level, Vec3 p, RandomSource rnd) {
        level.sendParticles(ParticleTypes.SPLASH, p.x, p.y + 1, p.z, 60, 0.5, 0.2, 0.5, 0.4);
        level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, p.x, p.y + 0.5, p.z, 20, 0.3, 0.6, 0.3, 0.2);
        level.sendParticles(ParticleTypes.CLOUD, p.x, p.y + 1.5, p.z, 10, 0.3, 1.2, 0.3, 0.03);
        level.sendParticles(ParticleTypes.FALLING_WATER, p.x, p.y + 2.5, p.z, 30, 0.4, 1.0, 0.4, 0);
        sound(level, p, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SPLASH, 0.6F + rnd.nextFloat() * 0.3F);
    }

    /** Two balls meet in the air: they burst. */
    static void clash(ServerLevel level, Vec3 p, RandomSource rnd) {
        level.sendParticles(ParticleTypes.EXPLOSION, p.x, p.y, p.z, 2, 0.3, 0.3, 0.3, 0);
        level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 20, 0.3, 0.3, 0.3, 0.12);
        level.sendParticles(ParticleTypes.LAVA, p.x, p.y, p.z, 6, 0.2, 0.2, 0.2, 0);
        sound(level, p, SoundEvents.GENERIC_EXPLODE, HIT, 1.3F);
        sound(level, p, SoundEvents.ANVIL_LAND, HIT, 0.6F);
    }

    /** A shot-up ship smokes from her deck, and burns when she is near her end. */
    static void damage(ServerLevel level, WarshipEntity ship, RandomSource rnd) {
        float left = ship.hull() / ship.maxHull();
        if (left > 0.6F) return;
        ShipClass c = ship.cls();
        Vec3 p = ship.at((rnd.nextDouble() - 0.5) * c.halfBeam * 1.6, c.middle + (rnd.nextDouble() - 0.5) * c.halfLength * 1.6, c.deck() + 0.3);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, p.x, p.y, p.z, 1, 0.2, 0.1, 0.2, 0.01);
        if (left < 0.3F) {
            level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 4, 0.3, 0.2, 0.3, 0.01);
            if (rnd.nextInt(30) == 0) sound(level, p, SoundEvents.FIRE_AMBIENT, CREAK, 0.8F);
        }
    }

    /** A sinking ship groans and cracks. */
    static void sinking(ServerLevel level, Vec3 p, RandomSource rnd) {
        if (rnd.nextInt(12) == 0) sound(level, p, SoundEvents.WOOD_BREAK, CREAK, 0.4F + rnd.nextFloat() * 0.3F);
        if (rnd.nextInt(20) == 0) sound(level, p, SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, CREAK, 0.6F);
    }
}
