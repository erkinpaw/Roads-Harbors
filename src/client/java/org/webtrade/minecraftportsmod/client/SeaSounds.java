package org.webtrade.minecraftportsmod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.webtrade.minecraftportsmod.combat.WarshipEntity;

/**
 * The sea aboard a warship under way: the wind in her rigging (louder the faster she goes), the water rushing past
 * her sides and slapping her bow, her timbers creaking now and then. Heard only by those aboard.
 */
public final class SeaSounds {

    private SeaSounds() {
    }

    private static Loop wind, water;
    private static int next = 20;

    /** A sound that keeps on while one is aboard her, as loud as she is fast. */
    private static final class Loop extends AbstractTickableSoundInstance {
        private final float most, base;

        Loop(SoundEvent sound, float most, float base, float pitch) {
            super(sound, SoundSource.NEUTRAL, RandomSource.create());
            this.most = most;
            this.base = base;
            this.looping = true;
            this.delay = 0;
            this.volume = 0.01F;
            this.pitch = pitch;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public void tick() {
            Minecraft mc = Minecraft.getInstance();
            WarshipEntity w = ShipMinimap.aboard(mc);
            if (w == null || w.isRemoved()) {
                volume = Math.max(0, volume - 0.03F);
                if (volume <= 0) stop();
                return;
            }
            float speed = (float) Math.hypot(w.getX() - w.xo, w.getZ() - w.zo);
            float want = base + most * Mth.clamp(speed / 0.2F, 0, 1);
            volume += Mth.clamp(want - volume, -0.02F, 0.02F);
        }
    }

    static void tick(Minecraft mc) {
        WarshipEntity w = ShipMinimap.aboard(mc);
        if (w == null || mc.level == null) return;
        var sounds = mc.getSoundManager();
        if (wind == null || wind.isStopped() || !sounds.isActive(wind)) {
            wind = new Loop(SoundEvents.ELYTRA_FLYING, 0.28F, 0.04F, 0.6F);
            sounds.play(wind);
        }
        if (water == null || water.isStopped() || !sounds.isActive(water)) {
            water = new Loop(SoundEvents.WATER_AMBIENT, 0.6F, 0.25F, 0.8F);
            sounds.play(water);
        }
        // the bow slapping into the swell, the timbers working
        if (--next <= 0) {
            var rnd = mc.level.getRandom();
            float speed = (float) Math.hypot(w.getX() - w.xo, w.getZ() - w.zo);
            next = 30 + rnd.nextInt(50);
            var bow = w.at(0, w.cls().middle + w.cls().halfLength, 0.5);
            if (speed > 0.03F) mc.level.playLocalSound(bow.x, bow.y, bow.z, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL,
                    0.4F + speed * 2F, 0.6F + rnd.nextFloat() * 0.3F, false);
            if (rnd.nextInt(3) == 0) mc.level.playLocalSound(w.getX(), w.getY() + 1, w.getZ(), SoundEvents.WOOD_STEP, SoundSource.NEUTRAL,
                    0.5F, 0.4F + rnd.nextFloat() * 0.2F, false);
            if (rnd.nextInt(5) == 0) mc.level.playLocalSound(w.getX(), w.getY() + 1, w.getZ(), SoundEvents.LADDER_STEP, SoundSource.NEUTRAL,
                    0.4F, 0.35F, false);
        }
    }
}
