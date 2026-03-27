import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/** Loads config from `warehouse.properties`, with a few `-D...` overrides. */
public class Configuration {
    // Timing
    private long tickDurationMs;

    // Warehouse structure
    private int numStockers;
    private int numPickers;
    private int numSections;
    private int numTrolleys;
    private int sectionCapacity;

    // Delivery parameters
    private int deliveryMeanIntervalTicks;

    // Simulation parameters
    private long simulationDurationTicks;
    private int initialBoxesPerSection;

    // Stocker parameters
    private int stockerBreakDurationTicks;

    public Configuration() {
        setDefaults();
        loadFromFile();
        loadFromSystemProperties();
    }

    /** Default settings (used if the properties file is missing). */
    private void setDefaults() {
        // Timing
        tickDurationMs = 100;

        // Warehouse structure
        numStockers = 1;
        numPickers = 2;
        numSections = 5;
        numTrolleys = -1; // Will compute as (numStockers + numPickers) / 2
        // Good-level default: sections have limited capacity (spec example uses 10).
        // You can set this to Integer.MAX_VALUE in warehouse.properties to emulate minimal.
        sectionCapacity = 10;

        // Delivery parameters
        deliveryMeanIntervalTicks = 100;

        // Simulation parameters
        simulationDurationTicks = 10000;
    
        initialBoxesPerSection = 5;

        // Stocker parameters
        stockerBreakDurationTicks = 150;
    }

    /**
     * Load configuration from warehouse.properties file if it exists.
     */
    private void loadFromFile() {
        String configPath = "warehouse.properties";
        try (FileInputStream fis = new FileInputStream(configPath)) {
            Properties props = new Properties();
            props.load(fis);

            if (props.containsKey("tick_duration_ms"))
                tickDurationMs = Long.parseLong(props.getProperty("tick_duration_ms"));
            if (props.containsKey("num_stockers"))
                numStockers = Integer.parseInt(props.getProperty("num_stockers"));
            if (props.containsKey("num_pickers"))
                numPickers = Integer.parseInt(props.getProperty("num_pickers"));
            if (props.containsKey("num_sections"))
                numSections = Integer.parseInt(props.getProperty("num_sections"));
            if (props.containsKey("num_trolleys"))
                numTrolleys = Integer.parseInt(props.getProperty("num_trolleys"));
            if (props.containsKey("section_capacity"))
                sectionCapacity = Integer.parseInt(props.getProperty("section_capacity"));
            if (props.containsKey("delivery_mean_interval_ticks"))
                deliveryMeanIntervalTicks = Integer.parseInt(props.getProperty("delivery_mean_interval_ticks"));
            if (props.containsKey("simulation_duration_ticks"))
                simulationDurationTicks = Long.parseLong(props.getProperty("simulation_duration_ticks"));
            if (props.containsKey("initial_boxes_per_section"))
                initialBoxesPerSection = Integer.parseInt(props.getProperty("initial_boxes_per_section"));
            if (props.containsKey("stocker_break_duration_ticks"))
                stockerBreakDurationTicks = Integer.parseInt(props.getProperty("stocker_break_duration_ticks"));
        } catch (IOException e) {
            // Configuration file not found; use defaults
            System.err.println("Configuration file not found; using defaults.");
        }
    }

    /** Optional `-D...` overrides (take priority over the file). */
    private void loadFromSystemProperties() {
        if (System.getProperty("tick_duration_ms") != null)
            tickDurationMs = Long.parseLong(System.getProperty("tick_duration_ms"));
        if (System.getProperty("num_stockers") != null)
            numStockers = Integer.parseInt(System.getProperty("num_stockers"));
        if (System.getProperty("num_pickers") != null)
            numPickers = Integer.parseInt(System.getProperty("num_pickers"));
        if (System.getProperty("num_sections") != null)
            numSections = Integer.parseInt(System.getProperty("num_sections"));
        if (System.getProperty("num_trolleys") != null)
            numTrolleys = Integer.parseInt(System.getProperty("num_trolleys"));
        if (System.getProperty("section_capacity") != null)
            sectionCapacity = Integer.parseInt(System.getProperty("section_capacity"));
        if (System.getProperty("simulation_duration_ticks") != null)
            simulationDurationTicks = Long.parseLong(System.getProperty("simulation_duration_ticks"));
    }

    /**
     * Compute effective number of trolleys if not explicitly set.
     * Default: floor((numStockers + numPickers) / 2)
     */
    public int getEffectiveNumTrolleys() {
        if (numTrolleys > 0) {
            return numTrolleys;
        }
        return (numStockers + numPickers) / 2;
    }

    // Getters
    public long getTickDurationMs() {
        return tickDurationMs;
    }

    public int getNumStockers() {
        return numStockers;
    }

    public int getNumPickers() {
        return numPickers;
    }

    public int getNumSections() {
        return numSections;
    }

    public int getNumTrolleys() {
        return numTrolleys;
    }

    public int getSectionCapacity() {
        return sectionCapacity;
    }

    public int getDeliveryMeanIntervalTicks() {
        return deliveryMeanIntervalTicks;
    }

    public long getSimulationDurationTicks() {
        return simulationDurationTicks;
    }

    public int getInitialBoxesPerSection() {
        return initialBoxesPerSection;
    }

    public int getStockerBreakDurationTicks() {
        return stockerBreakDurationTicks;
    }

    @Override
    public String toString() {
        return "Configuration{\n" +
                "  tickDurationMs=" + tickDurationMs + "\n" +
                "  numStockers=" + numStockers + "\n" +
                "  numPickers=" + numPickers + "\n" +
                "  numSections=" + numSections + "\n" +
                "  numTrolleys=" + numTrolleys + " (effective: " + getEffectiveNumTrolleys() + ")\n" +
                "  sectionCapacity=" + sectionCapacity + "\n" +
                "  simulationDurationTicks=" + simulationDurationTicks + "\n" +
                '}';
    }
}
