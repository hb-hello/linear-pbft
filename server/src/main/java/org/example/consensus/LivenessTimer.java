package org.example.consensus;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class LivenessTimer {
    private static final Logger logger = LogManager.getLogger(LivenessTimer.class);

    private final long timeoutMillis;
    private final Runnable timeoutCallback;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "liveness-timer");
                t.setDaemon(false);
                return t;
            });

    private volatile boolean running = false;
    private volatile boolean complete = false;
    private volatile ScheduledFuture<?> currentTask = null;

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

    public synchronized void start() {
        if (complete) {
            logger.warn("Timer is already complete, cannot restart");
            return;
        }

        logger.info("Starting liveness timer with timeout of {} ms", timeoutMillis);
        running = true;

        currentTask = scheduler.schedule(() -> {
            try {
                logger.warn("Liveness timer expired after {} ms", timeoutMillis);
                complete = true;
                running = false;
                timeoutCallback.run();
            } catch (Exception e) {
                logger.error("Error executing timeout callback", e);
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (!running) {
            logger.debug("Timer is not running, nothing to stop");
            return;
        }

        logger.info("Stopping liveness timer");
        running = false;

        // Cancel the current task instead of shutting down the entire scheduler
        if (currentTask != null && !currentTask.isDone()) {
            boolean cancelled = currentTask.cancel(false);
            logger.debug("Cancelled current task: {}", cancelled);
            currentTask = null;
        }
    }

    public synchronized void restart() {
        logger.info("Restarting liveness timer");
        stop();
        complete = false; // Reset complete flag to allow restart
        start();
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}