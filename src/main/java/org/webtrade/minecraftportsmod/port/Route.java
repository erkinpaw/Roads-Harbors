package org.webtrade.minecraftportsmod.port;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * A sea route between two ports (unordered pair). Waypoints are stored from the port with the
 * lower id to the port with the higher id; {@link #waypointsFrom(int)} returns them in sailing order.
 */
public final class Route {

    public enum Status implements StringRepresentable {
        /** Being calculated for the first time. */
        PENDING("pending"),
        /** Usable. */
        OK("ok"),
        /** Was fine, but the water changed and the stored path hits land now. Recalculating. */
        BLOCKED("blocked"),
        /** No water connection through explored chunks (yet). Retried when new water is found. */
        NO_PATH("no_path");

        public static final Codec<Status> CODEC = StringRepresentable.fromEnum(Status::values);
        private final String name;

        Status(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    private static final Codec<int[]> INT_ARRAY = Codec.INT_STREAM.xmap(IntStream::toArray, Arrays::stream);

    public static final Codec<Route> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("a").forGetter(r -> r.portA),
            Codec.INT.fieldOf("b").forGetter(r -> r.portB),
            Status.CODEC.fieldOf("status").forGetter(r -> r.status),
            INT_ARRAY.optionalFieldOf("path", new int[0]).forGetter(r -> r.waypoints),
            Codec.DOUBLE.optionalFieldOf("length", 0.0).forGetter(r -> r.length),
            Codec.LONG.optionalFieldOf("computed", 0L).forGetter(r -> r.computedAt)
    ).apply(i, (a, b, status, path, length, computed) -> {
        Route r = new Route(a, b);
        r.status = status == Status.PENDING ? Status.NO_PATH : status; // pending jobs don't survive restarts
        r.waypoints = path;
        r.length = length;
        r.computedAt = computed;
        return r;
    }));

    private final int portA;
    private final int portB;
    private Status status = Status.PENDING;
    private int[] waypoints = new int[0];
    private double length;
    private long computedAt;

    /** Transient: server tick of the last calculation attempt, used for throttling. */
    private long lastAttemptTick = Long.MIN_VALUE / 2;

    public Route(int portX, int portY) {
        this.portA = Math.min(portX, portY);
        this.portB = Math.max(portX, portY);
    }

    public static long key(int portX, int portY) {
        int a = Math.min(portX, portY);
        int b = Math.max(portX, portY);
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    public long key() {
        return key(portA, portB);
    }

    public int portA() {
        return portA;
    }

    public int portB() {
        return portB;
    }

    public boolean connects(int portId) {
        return portA == portId || portB == portId;
    }

    public int other(int portId) {
        return portId == portA ? portB : portA;
    }

    public Status status() {
        return status;
    }

    void setStatus(Status status) {
        this.status = status;
    }

    public boolean isUsable() {
        return status == Status.OK && waypoints.length >= 4;
    }

    /** Interleaved x,z block coordinates from {@link #portA()} to {@link #portB()}. Do not modify. */
    public int[] waypoints() {
        return waypoints;
    }

    public int[] waypointsFrom(int fromPortId) {
        if (fromPortId == portA) return waypoints.clone();
        int n = waypoints.length / 2;
        int[] reversed = new int[waypoints.length];
        for (int i = 0; i < n; i++) {
            reversed[i * 2] = waypoints[(n - 1 - i) * 2];
            reversed[i * 2 + 1] = waypoints[(n - 1 - i) * 2 + 1];
        }
        return reversed;
    }

    void setPath(int[] waypoints, double length, long computedAt) {
        this.waypoints = waypoints;
        this.length = length;
        this.computedAt = computedAt;
    }

    public double length() {
        return length;
    }

    public long computedAt() {
        return computedAt;
    }

    public long lastAttemptTick() {
        return lastAttemptTick;
    }

    public void markAttempt(long tick) {
        this.lastAttemptTick = tick;
    }

    /** Axis-aligned bounds of the stored path: {minX, minZ, maxX, maxZ}, or null if empty. */
    public int[] bounds() {
        if (waypoints.length < 2) return null;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < waypoints.length; i += 2) {
            minX = Math.min(minX, waypoints[i]);
            maxX = Math.max(maxX, waypoints[i]);
            minZ = Math.min(minZ, waypoints[i + 1]);
            maxZ = Math.max(maxZ, waypoints[i + 1]);
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }
}
