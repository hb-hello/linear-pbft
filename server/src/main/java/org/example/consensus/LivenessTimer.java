package org.example.consensus;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.config.Config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class LivenessTimer {
    private static final Logger logger = LogManager.getLogger(LivenessTimer.class);

    private long timeoutMillis;
    private final Runnable timeoutCallback;
    private long startTime;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "liveness-timer");
                t.setDaemon(false);
                return t;
            });

    private volatile boolean running = false;
    private volatile boolean complete = false;
    private volatile ScheduledFuture<?> currentTask = null;
    // generation counter ensures we can ignore stale scheduled tasks that were not cancelled
    private final AtomicLong generation = new AtomicLong(0L);

    public LivenessTimer(long timeoutMillis, Runnable timeoutCallback) {
        this.timeoutMillis = timeoutMillis;
        this.timeoutCallback = timeoutCallback;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isComplete() {
        return complete;
    }

    public synchronized void startIfNotRunning() {
        if (running) {
            logger.debug("Timer is already running, skipping start");
            return;
        }

        start();
    }

    public synchronized void startIfNotRunning(long withTimeoutMillis) {
        if (running) {
            logger.debug("Timer is already running, skipping start");
            return;
        }

        start(withTimeoutMillis);
    }

    private void scheduleTask(long delayMillis) {
        // Cancel any existing scheduled task to avoid overlap
        if (currentTask != null && !currentTask.isDone()) {
            boolean cancelled = currentTask.cancel(false);
            logger.debug("Cancelling previous liveness task before scheduling new one: {}", cancelled);
            currentTask = null;
        }

        startTime = System.currentTimeMillis();
        running = true;

        // bump generation to invalidate any older scheduled runnables
        final long gen = generation.incrementAndGet();

        currentTask = scheduler.schedule(() -> {
            try {
                // If generation changed since scheduling, this is a stale task; ignore it.
                if (gen != generation.get()) {
                    logger.debug("Ignoring stale liveness task (gen {} != current {})", gen, generation.get());
                    return;
                }
                logger.warn("Liveness timer expired after {} ms", delayMillis);
                complete = true;
                running = false;
                timeoutCallback.run();
            } catch (Exception e) {
                logger.error("Error executing timeout callback", e);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized void start() {
        complete = false; // reset complete so the timer can be reused
        logger.info("Starting liveness timer with timeout of {} ms", timeoutMillis);
        scheduleTask(timeoutMillis);
    }

    public synchronized void start(long withTimeoutMillis) {
        this.timeoutMillis = withTimeoutMillis;
        start();
    }

    public synchronized void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        logger.info("Liveness timer timeout set to {} ms", timeoutMillis);
    }

    public synchronized void addToTimeoutMillis(long additionalMillis) {
        if (!running) {
            this.timeoutMillis += additionalMillis;
            logger.info("Liveness timer timeout increased by {} ms to {} ms (for next run)", additionalMillis, timeoutMillis);
            return;
        }
    }

    public synchronized long getTimeoutMillis() {
        return timeoutMillis;
    }

    public synchronized long getRemainingTimeMillis() {
        if (!running) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - startTime;
        long remaining = timeoutMillis - elapsed;
        return Math.max(remaining, 0);
    }

    public synchronized void stop() {
        if (!running) {
            logger.debug("Timer is not running, nothing to stop");
            return;
        }

        logger.info("Stopping liveness timer");
        logger.info("Remaining time was {} ms", getRemainingTimeMillis());
        running = false;

        // Cancel the current task instead of shutting down the entire scheduler
        if (currentTask != null && !currentTask.isDone()) {
            boolean cancelled = currentTask.cancel(false);
            logger.debug("Cancelled current task: {}", cancelled);
            currentTask = null;
        }
        // bump generation so any task that still runs is ignored
        generation.incrementAndGet();
    }

    public synchronized void restart() {
        logger.info("Restarting liveness timer");
        stop();
        complete = false; // Reset complete flag to allow restart
        start();
    }

    public void reset() {
        stop();
        complete = false;
        generation.set(0L);
        timeoutMillis = Config.getServerTimeoutMillis();
    }

    public void shutdown() {
        timeoutMillis = Config.getServerTimeoutMillis();
        generation.set(0L);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
