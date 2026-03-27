import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The staging area where deliveries arrive.
 *
 * Basic rules:
 * 
 * Unlimited size.
 * Only stockers take from it.
 * Only one stocker can take at a time.
 * Once taken, boxes can't be returned to staging.
 */
public class StagingArea {
    private final List<Box> boxes = new ArrayList<>();

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    // Only one stocker may take at a time.
    private final Semaphore takeSemaphore = new Semaphore(1, true);

    public void addDelivery(Map<BoxType, Integer> delivered) {
        lock.lock();
        try {
            for (Map.Entry<BoxType, Integer> e : delivered.entrySet()) {
                for (int i = 0; i < e.getValue(); i++) {
                    boxes.add(new Box(e.getKey()));
                }
            }
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public int getBoxCount() {
        lock.lock();
        try {
            return boxes.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Take up to boxCount boxes from staging.
     * Returns a map of loaded counts by type (always includes all types).
     *
     * Waiting is tick based (not wall clock). We wait in small chunks and recheck the tick.
     */
    public Map<BoxType, Integer> takeBoxes(int boxCount, long waitTicks, Ticks timer) throws InterruptedException {
        if (boxCount <= 0 || boxCount > 10) {
            throw new IllegalArgumentException("boxCount must be 1-10");
        }

        if (timer == null) {
            throw new IllegalArgumentException("timer must not be null");
        }

        Map<BoxType, Integer> loaded = new HashMap<>();
        for (BoxType t : BoxType.getAllTypes()) {
            loaded.put(t, 0);
        }

        takeSemaphore.acquire();
        try {
            lock.lock();
            try {
                long startTick = timer.getCurrentTick();
                while (boxes.isEmpty()) {
                    long now = timer.getCurrentTick();
                    if (now - startTick >= waitTicks) {
                        return loaded;
                    }

                    // Wait up to 1 tick for a delivery; ensured tick consistent
                    notEmpty.await(1, TimeUnit.MILLISECONDS);
                }

                int toTake = Math.min(boxCount, boxes.size());
                for (int i = 0; i < toTake; i++) {
                    Box b = boxes.remove(0);
                    loaded.put(b.getType(), loaded.get(b.getType()) + 1);
                }
                return loaded;
            } finally {
                lock.unlock();
            }
        } finally {
            takeSemaphore.release();
        }
    }
}
