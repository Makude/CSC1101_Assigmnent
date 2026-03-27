import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Picker worker.
 * Tries to pick one box at a time from a random section, at a roughly fixed overall rate.
 */
public class PickerThread extends Thread {
    private final Warehouse warehouse;
    private final Configuration config;
    private final Ticks timer;
    private final Logger logger;
    private final int pickerId;
    private final Random random;
    private final AtomicLong pickIdCounter;
    private volatile boolean running;

    public PickerThread(int pickerId, Warehouse warehouse, Configuration config,
        Ticks timer, Logger logger, AtomicLong pickIdCounter, long randomSeed) {
        super("PickerThread-" + pickerId);
        this.pickerId = pickerId;
        this.warehouse = warehouse;
        this.config = config;
        this.timer = timer;
        this.logger = logger;
        this.random = new Random(randomSeed + pickerId);
        this.pickIdCounter = pickIdCounter;
        this.running = true;
    }

    @Override
    public void run() {
        String threadId = "P" + pickerId;

        try {
            while (running && timer.getCurrentTick() < config.getSimulationDurationTicks()) {
                try {
                    // Wait before next pick attempt
                    int waitBeforePickTicks = getRandomPickIntervalTicks();
                    timer.sleepTicks(waitBeforePickTicks);

                    if (!running || timer.getCurrentTick() >= config.getSimulationDurationTicks()) {
                        break;
                    }

                    // Attempt to pick a box
                    attemptPick(threadId);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            running = false;
        }
    }

    /**
     * Attempt to pick a box from a randomly selected section.
     */
    private void attemptPick(String threadId) throws InterruptedException {
        // Acquire trolley
        int trolleyId = warehouse.acquireTrolley(threadId);

        try {
            // Generate unique pick ID
            long pickId = pickIdCounter.getAndIncrement();

            // Randomly select a section (fixed for this pick attempt)
            String[] sections = warehouse.getSectionNames();
            String chosenSection = sections[random.nextInt(sections.length)];

            // Pick the box
            boolean success = warehouse.pickFromSection(chosenSection, pickId, trolleyId, threadId);

            if (!success) {
                // If this ever happens, log it and move on.
                logger.log(timer.getCurrentTick(), threadId, "pick_failed", "pick_id=" + pickId, "section=" + chosenSection);
            }

        } finally {
            warehouse.releaseTrolley(trolleyId, threadId); // Release trolley
        }
    }

    /**
     * Random wait (in ticks) between pick attempts.
     * We aim for approx 100 total attempts per 1000 ticks across all pickers, so each picker
     * waits on average about (10 * numPickers) ticks between attempts.
     */
    private int getRandomPickIntervalTicks() {
        int numPickers = config.getNumPickers();
        
    // Mean gap per picker so that total rate across all pickers stays ~constant.
        double meanInterval = 10.0 * numPickers;

        // Use exponential distribution
        double wait = -meanInterval * Math.log(random.nextDouble());
        return Math.max(1, (int) Math.round(wait));
    }
}
