package dev.vitruvius.editor.cam;

/**
 * How far the spectator player may move towards the camera in one tick (pure). A vanilla server refuses a move of more
 * than 10 blocks per movement packet ("moved too quickly") and sends the player back, so a camera that flies fast or
 * jumps to an object would be refused every tick. The player walks there in steps of at most {@link #MAX_STEP} instead.
 */
public final class FollowStep {
    private FollowStep() {}

    /** Blocks per tick: below the server's limit of 10 (100 squared), with room for rounding and its own velocity term. */
    public static final double MAX_STEP = 8.0;

    /**
     * The next position on the way from {@code from} to {@code to}: {@code to} itself when it is at most
     * {@code maxStep} away, otherwise the point {@code maxStep} along the straight line.
     */
    public static double[] next(double fx, double fy, double fz, double tx, double ty, double tz, double maxStep) {
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d <= maxStep || d == 0) return new double[] {tx, ty, tz};
        double k = maxStep / d;
        return new double[] {fx + dx * k, fy + dy * k, fz + dz * k};
    }
}
