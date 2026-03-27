import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Central-clock tick source.
 *
 * Tick model:
 * A single clock thread increments the tick by +1 every tickDurationMs.
 * Worker threads never increment time; they wait for ticks using sleepTicks/awaitTick.
 */
public class Ticks {
    private final long tickDurationMs;
    private final long startTimeMs;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition tickAdvanced = lock.newCondition();

    private long currentTick = 0;

    private volatile boolean running = false;
    private Thread clockThread;
    private volatile long maxTick = Long.MAX_VALUE;

    public Ticks(long tickDurationMs) {
        this.tickDurationMs = tickDurationMs;
        this.startTimeMs = System.currentTimeMillis();
    }

    /**
     * Start the central clock. Safe to call once.
     * @param maxTickInclusive stop automatically when tick reaches this value
     */
    public void start(long maxTickInclusive) {
        if (running) return;
        this.maxTick = (maxTickInclusive <= 0) ? Long.MAX_VALUE : maxTickInclusive;
        running = true;
        clockThread = new Thread(this::clockLoop, "TicksClock");
        clockThread.setDaemon(true);
        clockThread.start();
    }

    // Stop the clock and wake any sleepers
    public void stop() {
        running = false;
        lock.lock();
        try {
            tickAdvanced.signalAll();
        } finally {
            lock.unlock();
        }
        if (clockThread != null) {
            clockThread.interrupt();
        }
    }

    private void clockLoop() {
        while (running) {
            if (tickDurationMs > 0) {
                try {
                    Thread.sleep(tickDurationMs);
                } catch (InterruptedException e) {
                    // allow exit
                }
            }

            lock.lock();
            try {
                if (!running) break;
                if (currentTick >= maxTick) {
                    running = false;
                    tickAdvanced.signalAll();
                    break;
                }
                currentTick++;
                tickAdvanced.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    // Current simulation tick (central clock)
    public long getCurrentTick() {
        lock.lock();
        try {
            return currentTick;
        } finally {
            lock.unlock();
        }
    }

    // Wait until the tick reaches at least targetTick
    public void awaitTick(long targetTick) throws InterruptedException {
        lock.lock();
        try {
            while (currentTick < targetTick && running) {
                tickAdvanced.await();
            }
        } finally {
            lock.unlock();
        }
    }

    // Sleep N ticks according to the central clock
    public void sleepTicks(long ticks) throws InterruptedException {
        if (ticks <= 0) return;
        long start = getCurrentTick();
        awaitTick(start + ticks);
    }

    // Elapsed real time in milliseconds
    public long getElapsedRealTimeMs() {
        return System.currentTimeMillis() - startTimeMs;
    }

    // Whether the clock is running
    public boolean isRunning() {
        return running;
    }
}
