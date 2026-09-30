package org.webtrade.minecraftportsmod.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * One person of a village: the record the village keeps. The entity walking about is only the body; it is made
 * anew from this record whenever it is missing.
 */
public final class Dweller {

    /** A child grows up after this many days. */
    public static final int GROW_DAYS = 2;

    static final Codec<Dweller> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(d -> d.id),
            UUIDUtil.CODEC.optionalFieldOf("body").forGetter(d -> Optional.ofNullable(d.body)),
            Codec.STRING.fieldOf("name").forGetter(d -> d.name),
            Codec.INT.fieldOf("skin").forGetter(d -> d.skin),
            Codec.STRING.optionalFieldOf("job", "").forGetter(d -> d.job == null ? "" : d.job.id()),
            Codec.LONG.optionalFieldOf("born", -1L).forGetter(d -> d.born),
            Codec.INT.optionalFieldOf("home", -1).forGetter(d -> d.home),
            Codec.BOOL.optionalFieldOf("elder", false).forGetter(d -> d.elder),
            Codec.LONG.optionalFieldOf("joined", 0L).forGetter(d -> d.joined),
            Codec.BOOL.optionalFieldOf("arriving", false).forGetter(d -> d.arriving),
            Codec.INT.optionalFieldOf("earned", 0).forGetter(d -> d.earned),
            Codec.INT.optionalFieldOf("found", 0).forGetter(d -> d.found),
            Codec.BOOL.optionalFieldOf("away", false).forGetter(d -> d.away),
            Codec.LONG.optionalFieldOf("back", -1L).forGetter(d -> d.back),
            Codec.INT.optionalFieldOf("heading", 0).forGetter(d -> d.heading),
            Codec.INT.optionalFieldOf("range", 0).forGetter(d -> d.range)
    ).apply(i, (id, body, name, skin, job, born, home, elder, joined, arriving, earned, found, away, back, heading, range) -> {
        Dweller d = new Dweller(id, name, skin);
        d.body = body.orElse(null);
        d.job = Job.byId(job);
        d.born = born;
        d.home = home;
        d.elder = elder;
        d.joined = joined;
        d.arriving = arriving;
        d.earned = earned;
        d.found = found;
        d.away = away;
        d.back = back;
        d.heading = heading;
        d.range = range;
        return d;
    }));

    public final int id;
    public final String name;
    public final int skin;
    /** The entity of this person, if one was made. */
    UUID body;
    /** Null for a child. */
    Job job;
    /** The day of birth for those born in the village (-1: came from elsewhere, grown up). */
    long born = -1;
    /** The building this person sleeps in, or -1. */
    int home = -1;
    /** The head of the village. */
    boolean elder;
    /** The day this person joined the village. */
    long joined;
    /** Out of the village (a scout on an expedition), until day {@code back}; home again: the day of return. */
    boolean away;
    long back = -1;
    /** The expedition: its heading (degrees, 0 east, 90 south) and how far out (blocks). */
    int heading, range;

    public boolean away() {
        return away;
    }

    public long back() {
        return back;
    }

    public int heading() {
        return heading;
    }
    /** A newcomer still walking in from the edge of the village. */
    boolean arriving;
    /** What this person has brought to the store with their own hands today (units of their job's resource). */
    int earned;
    /** Iron ore this person found today while digging. */
    int found;

    Dweller(int id, String name, int skin) {
        this.id = id;
        this.name = name;
        this.skin = skin;
    }

    public boolean child(long today) {
        return born >= 0 && today - born < GROW_DAYS;
    }

    /** Has done a day's work already. */
    public boolean doneForToday() {
        return job != null && earned >= job.perDay;
    }

    public int earned() {
        return earned;
    }

    public Job job() {
        return job;
    }

    public int home() {
        return home;
    }

    public boolean elder() {
        return elder;
    }

    public UUID body() {
        return body;
    }
}
