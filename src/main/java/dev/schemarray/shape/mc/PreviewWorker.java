package dev.schemarray.shape.mc;

import dev.schemarray.shape.GeneratorSettings;
import dev.schemarray.shape.ShapeGenerator;
import dev.schemarray.shape.VoxelResult;
import net.minecraft.client.Minecraft;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Generates off the render thread: request() waits 250 ms for more changes, cancels a job that is still running,
 * runs ShapeGenerator on one background thread and hands the result to onResult on the render thread. Every request
 * gets a generation number; a job is cancelled, and its result dropped, as soon as a newer generation exists.
 */
public final class PreviewWorker {
    private PreviewWorker() {}

    private static final long DEBOUNCE_MS = 250;

    private static final ScheduledThreadPoolExecutor EXECUTOR = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "SchematicArray shape preview");
        t.setDaemon(true);
        return t;
    });

    static {
        EXECUTOR.setRemoveOnCancelPolicy(true);
    }

    private static final AtomicLong REQUESTED = new AtomicLong();   // newest generation asked for
    private static final AtomicLong FINISHED = new AtomicLong();    // newest generation delivered, failed or cancelled
    private static ScheduledFuture<?> waiting;

    /** Schedules a (re)generation; only the newest request's result is delivered. */
    public static synchronized void request(GeneratorSettings settings, Consumer<VoxelResult> onResult) {
        long gen = REQUESTED.incrementAndGet();
        if (waiting != null) waiting.cancel(false);
        waiting = EXECUTOR.schedule(() -> run(gen, settings, onResult), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private static void run(long gen, GeneratorSettings settings, Consumer<VoxelResult> onResult) {
        if (REQUESTED.get() != gen) return;
        VoxelResult result = null;
        try {
            result = ShapeGenerator.generate(settings, () -> REQUESTED.get() != gen);
        } catch (Throwable t) {
            System.out.println("[SchematicArray] shape generation failed: " + t);
        }
        if (result == null || REQUESTED.get() != gen) {
            if (REQUESTED.get() == gen) FINISHED.accumulateAndGet(gen, Math::max);   // failed: nothing more is coming
            return;
        }
        VoxelResult done = result;
        Minecraft.getInstance().execute(() -> {
            FINISHED.accumulateAndGet(gen, Math::max);
            if (REQUESTED.get() == gen) onResult.accept(done);
        });
    }

    /** Whether a job is waiting or running (the screen shows "Generating..."). */
    public static boolean busy() {
        return FINISHED.get() < REQUESTED.get();
    }

    /** Drops pending and running jobs (screen closed). */
    public static synchronized void cancel() {
        long gen = REQUESTED.incrementAndGet();
        FINISHED.accumulateAndGet(gen, Math::max);
        if (waiting != null) waiting.cancel(false);
        waiting = null;
    }
}
