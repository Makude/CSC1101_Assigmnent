import java.util.*;

/**
 * Delivery worker.
 * Adds deliveries into the staging area at random intervals.
 */
public class DeliveryThread extends Thread {
    private final Warehouse warehouse;
    private final Configuration config;
    private final Ticks timer;
    private final Logger logger;
    private final Random random;
    private volatile boolean running;

    public DeliveryThread(Warehouse warehouse, Configuration config, Ticks timer,
        Logger logger, long randomSeed) {
        super("DeliveryThread");
        this.warehouse = warehouse;
        this.config = config;
        this.timer = timer;
        this.logger = logger;
        this.random = new Random(randomSeed);
        this.running = true;
    }

    @Override
    public void run() {
        // Warm up: initial wait before first delivery
        int initialWaitTicks = getRandomInterArrivalTicks();
        try {
            timer.sleepTicks(initialWaitTicks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
            return;
        }

    while (running && timer.getCurrentTick() < config.getSimulationDurationTicks()) {
            // Wait until next delivery
            int interArrivalTicks = getRandomInterArrivalTicks();
            try {
                timer.sleepTicks(interArrivalTicks);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (!running || timer.getCurrentTick() >= config.getSimulationDurationTicks()) {
                break;
            }

            // Generate delivery: 10 boxes distributed across categories
            Map<BoxType, Integer> delivery = generateDelivery();

            // Add delivery to warehouse
            warehouse.addDelivery(delivery);

            // Log delivery event
            List<String> fields = new ArrayList<>();
            for (BoxType type : BoxType.getAllTypes()) {
                fields.add(type.getLabel() + "=" + delivery.get(type));
            }

            logger.log(timer.getCurrentTick(), "DEL", "delivery_arrived", fields.toArray(new String[0]));
        }

        running = false;
    }

    // Random ticks until the next delivery (exponential distribution).
    private int getRandomInterArrivalTicks() {
        double mean = config.getDeliveryMeanIntervalTicks();
        // Exponential distribution: -mean * ln(random)
        double wait = -mean * Math.log(random.nextDouble());
        return Math.max(1, (int) Math.round(wait));
    }

    /**
     * Generate a delivery of 10 boxes with random distribution across categories.
     * 
     * @return Map of BoxType -> count (total always 10)
     */
    private Map<BoxType, Integer> generateDelivery() {
        Map<BoxType, Integer> delivery = new HashMap<>();
        for (BoxType type : BoxType.getAllTypes()) {
            delivery.put(type, 0);
        }

        BoxType[] types = BoxType.getAllTypes();
        int remainingBoxes = 10;

        // Distribute boxes across types
        // Use random allocation: for each box, pick a random type
        for (int i = 0; i < 10; i++) {
            BoxType type = types[random.nextInt(types.length)];
            delivery.put(type, delivery.get(type) + 1);
        }

        return delivery;
    }

}
