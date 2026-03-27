import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Starts the simulation and wires together the threads + shared objects.
 */
public class WarehouseSimulation {
    private final Configuration config;
    private final Logger logger;
    private final Ticks timer;
    private final Warehouse warehouse;

    private final List<Thread> threads;
    private final AtomicLong pickIdCounter;

    public WarehouseSimulation(Configuration config) {
        this.config = config;
        this.logger = new Logger(System.out, false);
        this.timer = new Ticks(config.getTickDurationMs());
        this.warehouse = new Warehouse(config, logger, timer);
        this.threads = new ArrayList<>();
        this.pickIdCounter = new AtomicLong(0);

        logConfiguration();
    }

    /** Prints config to stderr so it doesn’t mix with the event log on stdout. */
    private void logConfiguration() {
        System.err.println("=== Warehouse Simulation Configuration ===");
        System.err.println(config.toString());
        System.err.println("Effective trolleys: " + config.getEffectiveNumTrolleys());
        System.err.println("=========================================");
        System.err.println();
    }

    /** Run until the tick clock stops, then shut everything down. */
    public void run() {
        System.err.println("Starting simulation...");

        // Start the central clock (ticks advance from a single source)
        timer.start(config.getSimulationDurationTicks());

        // Create and start delivery thread
        DeliveryThread deliveryThread = new DeliveryThread(
                warehouse, config, timer, logger, System.nanoTime());
        threads.add(deliveryThread);
        deliveryThread.start();

        // Create and start stocker threads
        for (int i = 1; i <= config.getNumStockers(); i++) {
            StockerThread stocker = new StockerThread(
                    i, warehouse, config, timer, logger, System.nanoTime() + i);
            threads.add(stocker);
            stocker.start();
        }

        // Create and start picker threads
        for (int i = 1; i <= config.getNumPickers(); i++) {
            PickerThread picker = new PickerThread(
                    i, warehouse, config, timer, logger, pickIdCounter, System.nanoTime() + i);
            threads.add(picker);
            picker.start();
        }

        // Wait until the clock stops (reaches simulation_duration_ticks)
        try {
            while (timer.isRunning()) {
                Thread.sleep(5);
            }
        } catch (InterruptedException e) {
            System.err.println("Simulation interrupted while waiting for clock.");
            Thread.currentThread().interrupt();
        } finally {
            // Ensure clock is stopped
            timer.stop();
        }

        // Ask workers to stop and wait for them to exit.
        for (Thread t : threads) {
            t.interrupt();
        }
        for (Thread t : threads) {
            try {
                t.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        logger.flush();
        printStatistics();
    }

    /** Final stats go to stderr (again: keep stdout clean for the validator). */
    private void printStatistics() {
        System.err.println();
        System.err.println("=== Simulation Complete ===");
        System.err.println("Final tick: " + timer.getCurrentTick());
    System.err.println("Configured simulation_duration_ticks: " + config.getSimulationDurationTicks());
        System.err.println("Elapsed real time: " + timer.getElapsedRealTimeMs() + " ms");
        System.err.println();
        System.err.println("=== Statistics ===");
        System.err.println("Total boxes delivered: " + warehouse.getTotalBoxesDelivered());
        System.err.println("Total boxes picked: " + warehouse.getTotalBoxesPicked());
        System.err.println("Total boxes stocked: " + warehouse.getTotalBoxesStocked());
        System.err.println("Staging area remaining: " + warehouse.getStagingAreaBoxCount());
        System.err.println("Total system boxes: " + warehouse.getTotalSystemBoxCount());
        System.err.println();

        // Calculate rates
        long finalTick = timer.getCurrentTick();
        double deliveryRate = (double) warehouse.getTotalBoxesDelivered() * 1000.0 / finalTick;
        double pickRate = (double) warehouse.getTotalBoxesPicked() * 1000.0 / finalTick;
        System.err.println("Delivery rate: " + String.format("%.2f", deliveryRate) + " boxes/1000 ticks");
        System.err.println("Pick rate: " + String.format("%.2f", pickRate) + " boxes/1000 ticks");
        System.err.println();
    }

    public static void main(String[] args) {
        // Load configuration
        Configuration config = new Configuration();

        // Run simulation
        WarehouseSimulation simulation = new WarehouseSimulation(config);
        simulation.run();
    }
}
