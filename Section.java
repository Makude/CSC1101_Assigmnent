
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A single storage section in the warehouse (e.g. electronics).
 * Thread safety:
 * One lock per section.
 * Pickers wait on notEmpty when the section is empty.
 */
public class Section {
	private final String name;
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition notEmpty = lock.newCondition();

	private final Map<BoxType, List<Box>> boxes = new HashMap<>();
	private int boxCount = 0;

	// Used for prioritised stocking
	private int waitingPickers = 0;

	public Section(String name) {
		this.name = name;
		for (BoxType t : BoxType.getAllTypes()) {
			boxes.put(t, new ArrayList<>());
		}
	}

	public String getName() {
		return name;
	}

	public ReentrantLock lock() {
		return lock;
	}

	public Condition notEmpty() {
		return notEmpty;
	}

	public int getBoxCount() {
		return boxCount;
	}

	public int getWaitingPickers() {
		return waitingPickers;
	}

	public void addInitialBox(Box box) {
		boxes.get(box.getType()).add(box);
		boxCount++;
	}

	public int stockOne(BoxType type) {
		boxes.get(type).add(new Box(type));
		boxCount++;
		notEmpty.signalAll();
		return 1;
	}

	// Remove one box from any type in this section. Returns null if empty
	public Box takeOneAnyType() {
		if (boxCount <= 0) return null;
		for (BoxType t : BoxType.getAllTypes()) {
			List<Box> list = boxes.get(t);
			if (!list.isEmpty()) {
				boxCount--;
				return list.remove(0);
			}
		}
		return null;
	}

	public void incWaitingPicker() {
		waitingPickers++;
	}

	public void decWaitingPicker() {
		waitingPickers--;
	}
}
