import java.util.*;

/**
 * Stocker worker.
 * Moves boxes from staging into sections, using a trolley.
 *
 * Rough loop: get a trolley -> load up to 10 boxes -> stock until empty -> return trolley.
 */
public class StockerThread extends Thread {
    private final Warehouse warehouse;
    private final Configuration config;
    private final Ticks timer;
    private final Logger logger;
    private final int stockerId;
    private final Random random;
    private volatile boolean running;
    private long nextBreakAtTick;

    public StockerThread(int stockerId, Warehouse warehouse, Configuration config,
        Ticks timer, Logger logger, long randomSeed) {
        super("StockerThread-" + stockerId);
        this.stockerId = stockerId;
        this.warehouse = warehouse;
        this.config = config;
        this.timer = timer;
        this.logger = logger;
        this.random = new Random(randomSeed + stockerId);
        this.running = true;
    // Next break is anytime 200–300 ticks from now (including the first one).
        scheduleNextBreak();
    }

    @Override
    public void run() {
        String threadId = "S" + stockerId;

        while (running && timer.getCurrentTick() < config.getSimulationDurationTicks()) {
            try {
                // Check if it's time for a break
                if (shouldTakeBreak()) {
                    takeBreak(threadId);
                    scheduleNextBreak();
                }

                // Acquire trolley
                int trolleyId = warehouse.acquireTrolley(threadId);

                try {
                    // Load boxes from staging area (with timeout)
                    Map<BoxType, Integer> loaded = warehouse.loadFromStaging(trolleyId, 10, threadId);
                    int totalLoaded = loaded.values().stream().mapToInt(Integer::intValue).sum();

                    if (totalLoaded == 0) {
                        // No boxes in staging, release trolley and retry after a delay
                        warehouse.releaseTrolley(trolleyId, threadId);
                        timer.sleepTicks(5); // Wait a bit before retrying
                        continue;
                    }

                    // Stock boxes into sections
                    stockBoxes(trolleyId, threadId);

                    // Only release when it's empty. If sections are full, back off and try again.
                    while (warehouse.getTrolleyLoad(trolleyId) > 0
                            && running
                            && timer.getCurrentTick() < config.getSimulationDurationTicks()) {
                        // Back off a bit to allow pickers to free space in sections.
                        timer.sleepTicks(5);
                        stockBoxes(trolleyId, threadId);
                    }

                    // Release trolley (Warehouse.releaseTrolley enforces empty).
                    // If time runs out while still carrying load, just stop trying.
                    if (warehouse.getTrolleyLoad(trolleyId) == 0
                            && timer.getCurrentTick() < config.getSimulationDurationTicks()) {
                        warehouse.releaseTrolley(trolleyId, threadId);
                    }


                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        running = false;
    }

    /** Stock what’s currently on the trolley until it’s empty (or the run ends). */
    private void stockBoxes(int trolleyId, String threadId) throws InterruptedException {
        String currentLocation = "staging";

    while (warehouse.getTrolleyLoad(trolleyId) > 0
        && running
        && timer.getCurrentTick() < config.getSimulationDurationTicks()) {
            // Get what's on the trolley
            Map<BoxType, Integer> trolleyBoxes = warehouse.getTrolleyBoxes(trolleyId);

            // Build candidate sections where we have at least one box to stock
            List<String> candidates = new ArrayList<>();
            for (BoxType type : BoxType.getAllTypes()) {
                Integer c = trolleyBoxes.get(type);
                if (c != null && c > 0) {
                    candidates.add(type.getLabel());
                }
            }
            String targetSection = warehouse.chooseSectionToStock(candidates);
            if (targetSection == null) break;
            BoxType nextBoxType = BoxType.fromLabel(targetSection);
            if (nextBoxType == null) break;

            // Move to section if not already there
            if (!currentLocation.equals(targetSection)) {
                warehouse.moveWithLoad(currentLocation, targetSection, trolleyId, threadId);
                currentLocation = targetSection;
            }

            // Try to stock this box type
            try {
                int stocked = warehouse.stockSection(targetSection, trolleyId, nextBoxType, threadId);
                // If nothing was stocked (section full), move to another section or staging
                if (stocked == 0) {
                    // Section is full. Spec allows releasing the lock and then waiting/choosing another action.
                    // Policy here: back off briefly and try again (either this or another section).
                    timer.sleepTicks(5);
                }
            } catch (Exception e) {
                // Error stocking, skip to next section
                continue;
            }
        }

        // Return to staging when done or full
        if (!currentLocation.equals("staging")) {
            warehouse.moveWithLoad(currentLocation, "staging", trolleyId, threadId);
        }
    }

    /**
     * Determine if stocker should take a break.
     */
    private boolean shouldTakeBreak() {
        return timer.getCurrentTick() >= nextBreakAtTick;
    }

    private void scheduleNextBreak() {
        // Spec: every 200–300 ticks take a break.
        int min = 200;
        int max = 300;
        int interval = min + random.nextInt(max - min + 1);
        nextBreakAtTick = timer.getCurrentTick() + interval;
    }

    /**
     * Take a break for configured duration.
     */
    private void takeBreak(String threadId) {
        int breakDuration = config.getStockerBreakDurationTicks();
    logger.log(timer.getCurrentTick(), threadId, "start_break",
        "duration=" + breakDuration);

        try {
            timer.sleepTicks(breakDuration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        logger.log(timer.getCurrentTick(), threadId, "end_break");
    }

    /**
     * Stop the stocker thread gracefully.
     */
    public void stopStocker() {
        running = false;
    }
}
