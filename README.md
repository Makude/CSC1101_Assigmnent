# Warehouse Simulation - Concurrent System in Java

A multi-threaded warehouse simulation demonstrating concurrent programming concepts including mutual exclusion, condition variables, semaphores, and fairness mechanisms.

## Quick Start

### Compilation

```bash
javac ./*.java
```

### Running the Simulation

```bash
java WarehouseSimulation
```

### Configuring Parameters

Edit `warehouse.properties` to adjust simulation parameters:

```properties
tick_duration_ms=100            # Milliseconds per simulation tick (affects speed)
num_stockers=1                  # Number of stocker threads
num_pickers=2                   # Number of picker threads
num_sections=5                  # Number of warehouse sections
section_capacity=10             # Max boxes per section (set to 2147483647 for "unlimited")
delivery_probability=0.01       # Probability of a delivery arriving on each tick
delivery_mean_interval_ticks=100 # Average ticks between deliveries (used for reporting;
                                # delivery_probability is the active model)
simulation_duration_ticks=1000  # Total simulation length
initial_boxes_per_section=5     # Starting boxes per section (matches its section type)
stocker_break_interval_ticks=250 # Legacy/optional; breaks are scheduled 200–300 ticks in code
stocker_break_duration_ticks=150 # Break duration
```

Notes:

- The program reads `warehouse.properties` and then applies `-D...` system property overrides.
- Only a subset of keys are overrideable via `-D` currently: `tick_duration_ms`, `num_stockers`, `num_pickers`,
  `num_sections`, `num_trolleys`, `section_capacity`, `simulation_duration_ticks`.

## Project Structure

```
./
├── BoxType.java
├── Box.java
├── Configuration.java
├── Logger.java
├── Ticks.java
├── Warehouse.java
├── DeliveryThread.java
├── StockerThread.java
├── PickerThread.java
├── LogValidator.java
└── WarehouseSimulation.java
```

## Key Features

### Concurrency Mechanisms

- **ReentrantLock + Condition Variables**: Per-section access control enabling exclusive stocker access + shared picker reads
- **Semaphore**: Trolley pool management (bounded resource allocation)
- **AtomicInteger/AtomicLong**: Thread-safe counters for statistics and unique pick IDs
- **ConcurrentHashMap**: Thread-safe trolley registry

### Event Logging Format

All events logged as single-line, space-separated key=value pairs to stdout:

```
tick=120 tid=DEL event=delivery_arrived electronics=7 books=0 medicines=1 clothes=2 tools=0
tick=206 tid=S1 event=stocker_load electronics=4 books=3 medicines=0 clothes=3 tools=0 total_load=10
tick=226 tid=S1 event=move from=staging to=electronics load=10 trolley_id=0
tick=226 tid=S1 event=stock_begin section=electronics amount=4 trolley_id=0
tick=230 tid=S1 event=stock_end section=electronics stocked=3 remaining_load=7 trolley_id=0
tick=467 tid=P1 event=pick_start pick_id=0 section=electronics trolley_id=1
tick=468 tid=P1 event=pick_done pick_id=0 section=electronics waited_ticks=0 trolley_id=1
```

### System Invariants

- **Box Conservation**: Total boxes in system = initial + delivered - picked (remains constant)
- **No Starvation**: All threads make progress; delivery and pick rates converge over time
- **Trolley Safety**: Trolleys only released when empty (remaining_load = 0)
- **Exclusive Stocking**: Only one stocker per section at a time; multiple pickers allowed
- **Section Capacity**: Sections never exceed configured capacity

## Spec alignment notes (important)

- **Staging take cost:** taking from staging costs **1 tick per attempt**, whether boxes were available or not.
- **Picker `waited_ticks`:** `pick_done waited_ticks` reflects the total time the picker spent blocked during that pick attempt for the chosen section to have stock. This includes:
  - time waiting for the section to become available (empty wait)
  - time waiting for a stocker to finish stocking the section (section lock contention)
- **Staging wait units:** staging waits are expressed in **ticks** (not wall-clock milliseconds).
- **Stocker breaks:** each stocker takes breaks every **200–300 ticks** (including the first break), with a break duration of 150 ticks by default.

## Performance Notes

- With `tick_duration_ms=1` and `simulation_duration_ticks=1000`: ~1-2 seconds execution
- Delivery rate: ~50-100 boxes per 1000 ticks
- Pick rate: Target ~100 boxes per 1000 ticks (distributed across pickers)
- Single trolley with 1 stocker + 2 pickers causes significant contention (intended for minimal version)

## Saving logs to files (optional)

By default, `WarehouseSimulation` prints:

- **event logs** to **stdout**
- configuration + statistics to **stderr**

So running without redirection prints everything to your terminal:

```bash
java WarehouseSimulation
```

To generate a `run.log` file (events only):

```bash
java WarehouseSimulation > run.log
```

To also save stderr (config + stats) to a separate file:

```bash
java WarehouseSimulation > run.log 2> stats.err
```

`stats.err` is not generated by the program automatically—it’s simply the file you get when you redirect **stderr**.
It contains the configuration banner, progress messages, and the end-of-run statistics summary (all printed via `System.err`).

To override configuration via system properties and still log to files:

```bash
java -Dtick_duration_ms=100 -Dsimulation_duration_ticks=3500 WarehouseSimulation > run.log 2> stats.err
```

To validate a saved log:

```bash
java LogValidator run.log
```

## Minimal Project Features

✓ Delivery thread with Poisson arrivals  
✓ Single stocker thread  
✓ Multiple picker threads  
✓ Box staging area (unlimited capacity)  
✓ 5 warehouse sections  
✓ Event logging with pick_id and waited_ticks  
✓ Trolley acquisition/release  
✓ Configurable simulation parameters  
✓ Thread-safe synchronization  

## Statistics Output

Upon completion, the simulation prints:

```
=== Statistics ===
Total boxes delivered: 50
Total boxes picked: 8
Total boxes stocked: 40
Staging area remaining: 10
Total system boxes: 67
Delivery rate: 49.02 boxes/1000 ticks
Pick rate: 7.84 boxes/1000 ticks
```

## Testing

Quick test with fast ticks:

```bash
java WarehouseSimulation \
  -Dtick_duration_ms=1 \
  -Dsimulation_duration_ticks=500
```

## Notes

- All threads are daemon=false, ensuring clean shutdown
- Event logger flushes output at simulation end
- Configuration defaults to unlimited section capacity (suitable for minimal version)
- Picker inter-attempt intervals are exponentially distributed targeting ~100 total picks/day
- Delivery inter-arrival times are exponentially distributed with mean ~100 ticks