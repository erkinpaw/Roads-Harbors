package org.webtrade.minecraftportsmod.vessel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * A trip along a fixed list of waypoints, ending at a berth (or at a port's anchorage if all berths were taken).
 * <p>
 * Progress is kept as distance travelled along the path, which works both when the vessel physically sails
 * (loaded chunks) and when it travels "virtually" while nobody is around to see it.
 */
public final class Voyage {

    private static final Codec<int[]> INT_ARRAY = Codec.INT_STREAM.xmap(IntStream::toArray, Arrays::stream);

    public static final Codec<Voyage> CODEC = RecordCodecBuilder.create(i -> i.group(
            INT_ARRAY.fieldOf("path").forGetter(v -> v.waypoints),
            Codec.INT.fieldOf("to").forGetter(v -> v.destPortId),
            Codec.INT.optionalFieldOf("dock", -1).forGetter(v -> v.destDockId),
            Codec.DOUBLE.optionalFieldOf("progress", 0.0).forGetter(v -> v.progress),
            Codec.BOOL.optionalFieldOf("rough", false).forGetter(v -> v.rough)
    ).apply(i, (path, to, dock, progress, rough) -> {
        Voyage v = new Voyage(path, to, dock, rough);
        v.setProgress(progress);
        return v;
    }));

    private final int[] waypoints;
    private final int destPortId;
    private final int destDockId;
    /** cumulative[i] = distance from the start to waypoint i (block centres). */
    private final double[] cumulative;
    private double progress;
    /** Next waypoint to steer to. */
    private int index;
    /**
     * Follows a rough lane from the world plan (waters nobody has sailed yet): travelled only virtually, the
     * vessel never takes shape in the world on the way — the lane may cut corners over land.
     */
    private final boolean rough;

    public Voyage(int[] waypoints, int destPortId, int destDockId) {
        this(waypoints, destPortId, destDockId, false);
    }

    public Voyage(int[] waypoints, int destPortId, int destDockId, boolean rough) {
        this.rough = rough;
        this.waypoints = waypoints;
        this.destPortId = destPortId;
        this.destDockId = destDockId;
        int n = waypoints.length / 2;
        this.cumulative = new double[Math.max(1, n)];
        for (int i = 1; i < n; i++) {
            cumulative[i] = cumulative[i - 1] + Math.hypot(waypoints[i * 2] - waypoints[(i - 1) * 2],
                    waypoints[i * 2 + 1] - waypoints[(i - 1) * 2 + 1]);
        }
        this.index = n > 1 ? 1 : 0;
    }

    public int count() {
        return waypoints.length / 2;
    }

    public double totalLength() {
        return cumulative[cumulative.length - 1];
    }

    public double progress() {
        return progress;
    }

    public double remaining() {
        return Math.max(0, totalLength() - progress);
    }

    /** Sets progress and moves the steering index to the matching leg. */
    public void setProgress(double p) {
        progress = Math.max(0, Math.min(totalLength(), p));
        int i = 1;
        while (i < count() - 1 && cumulative[i] <= progress) i++;
        index = Math.min(i, Math.max(0, count() - 1));
    }

    public boolean finished() {
        return progress >= totalLength() - 1e-3 || count() < 2;
    }

    // ------------------------------------------------------------------ physical steering (entity)

    public int index() {
        return index;
    }

    public boolean isLast() {
        return index >= count() - 1;
    }

    public void advance() {
        if (index < count() - 1) index++;
        else progress = totalLength();
    }

    public double targetX() {
        return waypoints[index * 2] + 0.5;
    }

    public double targetZ() {
        return waypoints[index * 2 + 1] + 0.5;
    }

    /** Updates progress from where the hull physically is while steering towards {@link #index()}. */
    public void trackPhysical(double x, double z) {
        double toTarget = Math.hypot(targetX() - x, targetZ() - z);
        progress = Math.max(progress, Math.min(totalLength(), cumulative[index] - toTarget));
    }

    /** Heading change (degrees, 0..180) at the current target, or 0 at the last point. */
    public double turnAngleAtTarget() {
        if (index <= 0 || index >= count() - 1) return 0;
        double ax = waypoints[index * 2] - waypoints[(index - 1) * 2];
        double az = waypoints[index * 2 + 1] - waypoints[(index - 1) * 2 + 1];
        double bx = waypoints[(index + 1) * 2] - waypoints[index * 2];
        double bz = waypoints[(index + 1) * 2 + 1] - waypoints[index * 2 + 1];
        double la = Math.sqrt(ax * ax + az * az), lb = Math.sqrt(bx * bx + bz * bz);
        if (la == 0 || lb == 0) return 0;
        double cos = (ax * bx + az * bz) / (la * lb);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    // ------------------------------------------------------------------ virtual travel

    /** Position and heading at the current progress: {x, z, yawDegrees}. */
    public double[] pose() {
        int n = count();
        if (n == 0) return new double[]{0, 0, 0};
        if (n == 1) return new double[]{waypoints[0] + 0.5, waypoints[1] + 0.5, 0};
        int i = 1;
        while (i < n - 1 && cumulative[i] < progress) i++;
        double segLen = cumulative[i] - cumulative[i - 1];
        double t = segLen <= 0 ? 1 : Math.max(0, Math.min(1, (progress - cumulative[i - 1]) / segLen));
        double ax = waypoints[(i - 1) * 2] + 0.5, az = waypoints[(i - 1) * 2 + 1] + 0.5;
        double bx = waypoints[i * 2] + 0.5, bz = waypoints[i * 2 + 1] + 0.5;
        double yaw = Math.toDegrees(Math.atan2(-(bx - ax), bz - az));
        return new double[]{ax + (bx - ax) * t, az + (bz - az) * t, yaw};
    }

    public boolean rough() {
        return rough;
    }

    public int[] waypoints() {
        return waypoints;
    }

    public int destPortId() {
        return destPortId;
    }

    public int destDockId() {
        return destDockId;
    }
}
