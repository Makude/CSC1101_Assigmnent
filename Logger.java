import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe event logger for warehouse simulation events.
 * All events are formatted as single-line, space-separated key=value pairs.
 * 
 * Thread-safety: Uses intrinsic lock on this object for synchronized output.
 * This prevents interleaving of log lines from multiple threads.
 */
public class Logger {
    private final PrintStream out;
    private final List<String> eventBuffer;
    private final boolean bufferEvents;

    public Logger(PrintStream out, boolean bufferEvents) {
        this.out = out;
        this.bufferEvents = bufferEvents;
        this.eventBuffer = bufferEvents ? new ArrayList<>() : null;
    }

    /**
     * Log an event with specified fields.
     * Fields are provided as pairs: field1, value1, field2, value2, ...
     * 
     * @param tick Current simulation tick
     * @param threadId Thread identifier (e.g., "S1", "P3", "DEL")
     * @param eventType Type of event (e.g., "acquire_trolley")
     * @param fields Key-value pairs (alternating strings)
     */
    public synchronized void log(long tick, String threadId, String eventType, String... fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("tick=").append(tick)
          .append(" tid=").append(threadId)
          .append(" event=").append(eventType);

        // Add all fields as key=value pairs
        for (String field : fields) {
            sb.append(" ").append(field);
        }

        String logLine = sb.toString();

        if (bufferEvents) {
            eventBuffer.add(logLine);
        } else {
            out.println(logLine);
            out.flush();
        }
    }

    /**
     * Flush all buffered events to output stream.
     */
    public synchronized void flush() {
        if (bufferEvents && eventBuffer != null) {
            for (String line : eventBuffer) {
                out.println(line);
            }
            out.flush();
            eventBuffer.clear();
        }
    }

    /**
     * Get all logged events (for testing/analysis).
     */
    public synchronized List<String> getEvents() {
        return new ArrayList<>(bufferEvents ? eventBuffer : new ArrayList<>());
    }
}
