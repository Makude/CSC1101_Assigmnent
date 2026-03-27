import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Shared warehouse state (sections, staging, trolleys) + the locking around it.
 *
 * Concurrency summary:
 * - each section has its own lock/condition for pickers vs stockers
 * - a semaphore limits how many trolleys can be in use
 */
public class Warehouse {
    private final Configuration config;
    private final Logger logger;

    // Timing
    private final Ticks timer;

    // Staging area (deliveries arrive here)
    private final StagingArea stagingArea;

    // Sections
    private final Map<String, Section> sections;
    private final String[] sectionNames;

    // Trolley management
    private final Semaphore trolleyPool;
    private final AtomicInteger activeTrolleyCount;
    private final Map<Integer, Trolley> trolleyRegistry;
    private final AtomicInteger trolleyIdCounter;

    // Statistics
    private final AtomicInteger totalBoxesDelivered;
    private final AtomicInteger totalBoxesPicked;
    private final AtomicInteger totalBoxesStocked;

    /** Pick a section to stock next (prefer space + pickers waiting). */
    public String chooseSectionToStock(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String name : candidates) {
            Section s = sections.get(name);
            if (s == null) continue;
            int capacity = config.getSectionCapacity();
            int empty = (capacity == Integer.MAX_VALUE) ? 0 : Math.max(0, capacity - s.getBoxCount());
            int score = empty + 5 * s.getWaitingPickers();
            if (score > bestScore) {
                bestScore = score;
                best = name;
            }
        }
        return best;
    }

    public Warehouse(Configuration config, Logger logger, Ticks timer) {
        this.config = config;
        this.logger = logger;
        this.timer = timer;

        // Initialize staging area
        this.stagingArea = new StagingArea();

        // Initialize sections
        int numSections = config.getNumSections();
        this.sectionNames = generateSectionNames(numSections);
        this.sections = new HashMap<>();
        for (String sectionName : sectionNames) {
            sections.put(sectionName, new Section(sectionName));
        }

        // Initialize section with starting boxes
        initializeStartingBoxes();

        // Initialize trolley pool
        int effectiveNumTrolleys = config.getEffectiveNumTrolleys();
        this.trolleyPool = new Semaphore(effectiveNumTrolleys, true);
        this.activeTrolleyCount = new AtomicInteger(0);
        this.trolleyRegistry = new ConcurrentHashMap<>();
        this.trolleyIdCounter = new AtomicInteger(0);

        // Initialize statistics
        this.totalBoxesDelivered = new AtomicInteger(0);
        this.totalBoxesPicked = new AtomicInteger(0);
        this.totalBoxesStocked = new AtomicInteger(0);
    }

    /** Make section names (first few match the assignment’s categories). */
    private String[] generateSectionNames(int count) {
        String[] predefined = {"electronics", "books", "medicines", "clothes", "tools"};
        String[] names = new String[count];
        for (int i = 0; i < count && i < predefined.length; i++) {
            names[i] = predefined[i];
        }
        for (int i = predefined.length; i < count; i++) {
            names[i] = "section_" + (i + 1);
        }
        return names;
    }

    /**
     * Seed each section with starting stock (defaults to 5 of that section’s type).
     */
    private void initializeStartingBoxes() {
        int totalBoxesPerSection = config.getInitialBoxesPerSection();

        for (String sectionName : sectionNames) {
            Section section = sections.get(sectionName);
            if (section == null) continue;

            BoxType type = BoxType.fromLabel(sectionName);
            // For non-standard section names, fall back to a stable type.
            if (type == null) {
                type = BoxType.getAllTypes()[0];
            }

            for (int i = 0; i < totalBoxesPerSection; i++) {
                section.addInitialBox(new Box(type));
            }
        }
    }

    /** Add a delivery into the staging area. */
    public void addDelivery(Map<BoxType, Integer> deliveredBoxes) {
        stagingArea.addDelivery(deliveredBoxes);
        for (int c : deliveredBoxes.values()) {
            totalBoxesDelivered.addAndGet(c);
        }
    }

    /**
     * Acquire a trolley from the pool.
     * Blocks until a trolley is available.
     * 
     * @return Trolley ID
     * @throws InterruptedException if thread is interrupted while waiting
     */
    public int acquireTrolley(String threadId) throws InterruptedException {
        long waitStartTick = timer.getCurrentTick();
        trolleyPool.acquire(); // Block until available

        long waitEndTick = timer.getCurrentTick();
        long waitedTicks = waitEndTick - waitStartTick;

        // Create or reuse trolley
        int trolleyId = trolleyIdCounter.getAndIncrement();
        Trolley trolley = new Trolley(trolleyId);
        trolleyRegistry.put(trolleyId, trolley);
        activeTrolleyCount.incrementAndGet();

        logger.log(timer.getCurrentTick(), threadId, "acquire_trolley",
                "trolley_id=" + trolleyId,
                "waited_ticks=" + waitedTicks);

        return trolleyId;
    }

    /**
     * Release a trolley back to the pool.
     * Trolley must be empty (no remaining boxes).
     * 
     * @param trolleyId Trolley ID to release
     * @param threadId Thread identifier for logging
     */
    public void releaseTrolley(int trolleyId, String threadId) {
        Trolley trolley = trolleyRegistry.get(trolleyId);
        if (trolley == null) {
            throw new IllegalStateException("Unknown trolley ID: " + trolleyId);
        }

        if (trolley.totalLoad > 0) {
            throw new IllegalStateException("Cannot release trolley " + trolleyId +
                    " with remaining load: " + trolley.totalLoad);
        }

        logger.log(timer.getCurrentTick(), threadId, "release_trolley",
                "trolley_id=" + trolleyId,
                "remaining_load=0");

        trolleyRegistry.remove(trolleyId);
        activeTrolleyCount.decrementAndGet();
        trolleyPool.release();
    }

    /**
     * Load boxes from staging area onto a trolley.
     * Takes 1 tick regardless of number of boxes.
     * Will wait up to timeout for boxes to appear if staging area empty.
     * 
     * @param trolleyId Trolley to load onto
     * @param boxCount Number of boxes to load (up to 10)
     * @param threadId Thread identifier for logging
     * @return Map of BoxType -> count actually loaded (may be empty if timeout)
     */
    public Map<BoxType, Integer> loadFromStaging(int trolleyId, int boxCount, String threadId)
            throws InterruptedException {
        if (boxCount <= 0 || boxCount > 10) {
            throw new IllegalArgumentException("Box count must be 1-10, got " + boxCount);
        }

        Trolley trolley = trolleyRegistry.get(trolleyId);
        if (trolley == null) {
            throw new IllegalStateException("Unknown trolley ID: " + trolleyId);
        }

        // Load up to boxCount boxes, respecting trolley capacity
        int availableCapacity = 10 - trolley.totalLoad;
        int request = Math.min(boxCount, availableCapacity);

    // Loading always costs 1 tick (even if we end up taking 0 boxes).
        timer.sleepTicks(1);

        Map<BoxType, Integer> loaded = stagingArea.takeBoxes(request, 1000, timer);
        for (BoxType type : BoxType.getAllTypes()) {
            int c = loaded.get(type);
            if (c > 0) {
                trolley.load.put(type, trolley.load.get(type) + c);
                trolley.totalLoad += c;
            }
        }

        // Log the load event only when boxes are actually taken.
        if (loaded.values().stream().mapToInt(Integer::intValue).sum() > 0) {
            List<String> fields = new ArrayList<>();
            for (BoxType type : BoxType.getAllTypes()) {
                fields.add(type.getLabel() + "=" + loaded.get(type));
            }
            fields.add("total_load=" + trolley.totalLoad);

            logger.log(timer.getCurrentTick(), threadId, "stocker_load", fields.toArray(new String[0]));
        }

        return loaded;
    }

    /**
     * Move from one location to another with a load.
     * Cost: 10 ticks base + 1 tick per box on trolley.
     * 
     * @param from Starting location ("staging" or section name)
     * @param to Destination location ("staging" or section name)
     * @param trolleyId Trolley being moved
     * @param threadId Thread identifier for logging
     */
    public void moveWithLoad(String from, String to, int trolleyId, String threadId) {
        Trolley trolley = trolleyRegistry.get(trolleyId);
        if (trolley == null) {
            throw new IllegalStateException("Unknown trolley ID: " + trolleyId);
        }

        // Calculate movement time: 10 base + 1 per box
        int movementTicks = 10 + trolley.totalLoad;

        // Log movement
        logger.log(timer.getCurrentTick(), threadId, "move",
                "from=" + from,
                "to=" + to,
                "load=" + trolley.totalLoad,
                "trolley_id=" + trolleyId);

        // Sleep for movement duration
        try {
            timer.sleepTicks(movementTicks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stock boxes from trolley into a section.
     * Cost: 1 tick per box stocked.
     * 
     * If section reaches capacity mid-stocking, stocker stops and lock is released.
     * 
     * @param sectionName Section to stock into
     * @param trolleyId Trolley holding boxes
     * @param boxTypeToStock BoxType to stock (all of this type on trolley)
     * @param threadId Thread identifier for logging
     * @return Number of boxes actually stocked (may be < requested if section full)
     */
    public int stockSection(String sectionName, int trolleyId, BoxType boxTypeToStock, String threadId)
            throws InterruptedException {
        Section section = sections.get(sectionName);
        if (section == null) {
            throw new IllegalArgumentException("Unknown section: " + sectionName);
        }

        // Enforce the spec's "respective sections" model: a box type may only be stocked
        // into the section whose name matches the box type label.
        if (!sectionName.equals(boxTypeToStock.getLabel())) {
            logger.log(timer.getCurrentTick(), threadId, "stock_rejected",
                    "section=" + sectionName,
                    "box_type=" + boxTypeToStock.getLabel(),
                    "reason=wrong_section");
            return 0;
        }

        Trolley trolley = trolleyRegistry.get(trolleyId);
        if (trolley == null) {
            throw new IllegalStateException("Unknown trolley ID: " + trolleyId);
        }

        int boxesToStock = trolley.load.get(boxTypeToStock);
        if (boxesToStock <= 0) {
            return 0; // Nothing to stock
        }

    section.lock().lock();
        try {
            // Log stock begin
            logger.log(timer.getCurrentTick(), threadId, "stock_begin",
                    "section=" + sectionName,
                    "amount=" + boxesToStock,
                    "trolley_id=" + trolleyId);

            int sectionCapacity = config.getSectionCapacity();
            int stocked = 0;

            // Stock boxes one at a time, checking capacity
            for (int i = 0; i < boxesToStock; i++) {
                if (section.getBoxCount() >= sectionCapacity) {
                    // Section is full, stop stocking
                    break;
                }

                // Stock one box
                section.stockOne(boxTypeToStock);
                trolley.totalLoad--;
                trolley.load.put(boxTypeToStock, trolley.load.get(boxTypeToStock) - 1);
                stocked++;
                totalBoxesStocked.incrementAndGet();

                // Tick cost: 1 per box
                timer.sleepTicks(1);

                // Signal already handled inside section.stockOne(...)
            }

            // Log stock end
            logger.log(timer.getCurrentTick(), threadId, "stock_end",
                    "section=" + sectionName,
                    "stocked=" + stocked,
                    "remaining_load=" + trolley.totalLoad,
                    "trolley_id=" + trolleyId);

            return stocked;
        } finally {
            section.lock().unlock();
        }
    }

    /**
     * Pick a box from a section.
     * Cost: 1 tick.
     * 
     * @param sectionName Section to pick from
     * @param pickId Unique pick attempt ID
     * @param trolleyId Trolley to receive the box
     * @param threadId Thread identifier for logging
     * @return true if box was successfully picked, false if section empty (after timeout)
     */
    public boolean pickFromSection(String sectionName, long pickId, int trolleyId, String threadId)
            throws InterruptedException {
        Section section = sections.get(sectionName);
        if (section == null) {
            throw new IllegalArgumentException("Unknown section: " + sectionName);
        }

        // waited_ticks should include *all* waiting relevant to the pick attempt,
        // including contention on the section lock (e.g. while a stocker is stocking).
        long waitStartTick = timer.getCurrentTick();
        section.lock().lock();
        try {
            // Log pick start (must be before waiting)
            logger.log(timer.getCurrentTick(), threadId, "pick_start",
                    "pick_id=" + pickId,
                    "section=" + sectionName,
                    "trolley_id=" + trolleyId);

            long waitedTicks = Math.max(0, timer.getCurrentTick() - waitStartTick);

            // Wait until section has boxes
            while (section.getBoxCount() == 0) {
                long before = timer.getCurrentTick();
                section.incWaitingPicker();
                section.notEmpty().await();
                section.decWaitingPicker();
                long after = timer.getCurrentTick();
                long delta = after - before;
                if (delta > 0) waitedTicks += delta;
            }

            // Pick any available box
            Box box = section.takeOneAnyType();

            if (box == null) {
                throw new RuntimeException("Section has boxes but couldn't find any?");
            }
            totalBoxesPicked.incrementAndGet();

            // Tick cost: 1 per pick
            timer.sleepTicks(1);

            // Log pick done
            logger.log(timer.getCurrentTick(), threadId, "pick_done",
                    "pick_id=" + pickId,
                    "section=" + sectionName,
                    "waited_ticks=" + waitedTicks,
                    "trolley_id=" + trolleyId);

            return true;
        } finally {
            section.lock().unlock();
        }
    }

    /**
     * Get total boxes currently on a trolley.
     */
    public int getTrolleyLoad(int trolleyId) {
        Trolley trolley = trolleyRegistry.get(trolleyId);
        return trolley != null ? trolley.totalLoad : -1;
    }

    /**
     * Get box counts by type on a trolley.
     */
    public Map<BoxType, Integer> getTrolleyBoxes(int trolleyId) {
        Trolley trolley = trolleyRegistry.get(trolleyId);
        if (trolley == null) {
            return new HashMap<>();
        }
        return new HashMap<>(trolley.load);
    }

    /**
     * Get all section names.
     */
    public String[] getSectionNames() {
        return Arrays.copyOf(sectionNames, sectionNames.length);
    }

    /**
     * Get box count in a section (for testing/statistics).
     */
    public int getSectionBoxCount(String sectionName) {
        Section section = sections.get(sectionName);
        if (section == null) return -1;
        return section.getBoxCount();
    }

    /**
     * Get staging area box count (for testing/statistics).
     */
    public int getStagingAreaBoxCount() {
        return stagingArea.getBoxCount();
    }

    /**
     * Get total system box count (should be constant).
     */
    public int getTotalSystemBoxCount() {
        int total = getStagingAreaBoxCount();
        for (String sectionName : sectionNames) {
            total += getSectionBoxCount(sectionName);
        }
        return total;
    }

    // Statistics getters
    public int getTotalBoxesDelivered() {
        return totalBoxesDelivered.get();
    }

    public int getTotalBoxesPicked() {
        return totalBoxesPicked.get();
    }

    public int getTotalBoxesStocked() {
        return totalBoxesStocked.get();
    }
}
