import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Log validator for the root (src/warehouse) implementation.
 *
 * Usage:
 *   java LogValidator <logfile>
 *
 * Validates required invariants from the specification:
 * - Every line must be space-separated key=value tokens and must include tick, tid, event.
 * - Each pick_id appears in exactly one pick_start and one pick_done.
 * - pick_done must include waited_ticks and it must be a non-negative integer.
 * - release_trolley must include remaining_load and it must be 0.
 */
public final class LogValidator {
    private static final class PickState {
        boolean sawStart;
        boolean sawDone;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java LogValidator <logfile>");
            System.exit(2);
        }

        String path = args[0];
        int errors = 0;
        int lines = 0;

        Map<String, PickState> picks = new HashMap<>();
        Set<String> requiredKeys = new HashSet<>();
        requiredKeys.add("tick");
        requiredKeys.add("tid");
        requiredKeys.add("event");

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines++;
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split(" +");
                Map<String, String> kv = new HashMap<>();
                boolean parseOk = true;
                for (String p : parts) {
                    int eq = p.indexOf('=');
                    if (eq <= 0 || eq == p.length() - 1) {
                        parseOk = false;
                        break;
                    }
                    String k = p.substring(0, eq);
                    String v = p.substring(eq + 1);
                    kv.put(k, v);
                }

                if (!parseOk) {
                    System.err.println("ERROR line " + lines + ": non key=value token: " + line);
                    errors++;
                    continue;
                }

                for (String rk : requiredKeys) {
                    if (!kv.containsKey(rk)) {
                        System.err.println("ERROR line " + lines + ": missing required key " + rk + ": " + line);
                        errors++;
                        parseOk = false;
                    }
                }
                if (!parseOk) continue;

                String event = kv.get("event");
                if ("pick_start".equals(event) || "pick_done".equals(event)) {
                    String pickId = kv.get("pick_id");
                    if (pickId == null) {
                        System.err.println("ERROR line " + lines + ": missing pick_id: " + line);
                        errors++;
                    } else {
                        PickState st = picks.computeIfAbsent(pickId, k -> new PickState());
                        if ("pick_start".equals(event)) {
                            if (st.sawStart) {
                                System.err.println("ERROR line " + lines + ": duplicate pick_start for pick_id=" + pickId);
                                errors++;
                            }
                            st.sawStart = true;
                        } else {
                            if (st.sawDone) {
                                System.err.println("ERROR line " + lines + ": duplicate pick_done for pick_id=" + pickId);
                                errors++;
                            }
                            st.sawDone = true;

                            String waited = kv.get("waited_ticks");
                            if (waited == null) {
                                System.err.println("ERROR line " + lines + ": pick_done missing waited_ticks for pick_id=" + pickId);
                                errors++;
                            } else {
                                try {
                                    long w = Long.parseLong(waited);
                                    if (w < 0) {
                                        System.err.println("ERROR line " + lines + ": waited_ticks negative for pick_id=" + pickId);
                                        errors++;
                                    }
                                } catch (NumberFormatException nfe) {
                                    System.err.println("ERROR line " + lines + ": waited_ticks not numeric for pick_id=" + pickId);
                                    errors++;
                                }
                            }
                        }
                    }
                }

                if ("release_trolley".equals(event)) {
                    String remaining = kv.get("remaining_load");
                    if (remaining == null) {
                        System.err.println("ERROR line " + lines + ": release_trolley missing remaining_load: " + line);
                        errors++;
                    } else {
                        try {
                            long r = Long.parseLong(remaining);
                            if (r != 0) {
                                System.err.println("ERROR line " + lines + ": released trolley with remaining_load=" + r);
                                errors++;
                            }
                        } catch (NumberFormatException nfe) {
                            System.err.println("ERROR line " + lines + ": remaining_load not numeric: " + line);
                            errors++;
                        }
                    }
                }
            }
        }

        for (Map.Entry<String, PickState> e : picks.entrySet()) {
            String pickId = e.getKey();
            PickState st = e.getValue();
            if (!st.sawStart || !st.sawDone) {
                System.err.println("ERROR: pick_id=" + pickId + " missing start/done (start=" + st.sawStart + ", done=" + st.sawDone + ")");
                errors++;
            }
        }

        if (errors == 0) {
            System.out.println("PASS: no validation errors. lines=" + lines + " picks_seen=" + picks.size());
            System.exit(0);
        } else {
            System.out.println("FAIL: validation errors=" + errors + " lines=" + lines + " picks_seen=" + picks.size());
            System.exit(1);
        }
    }
}
