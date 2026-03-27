
import java.util.HashMap;
import java.util.Map;

/**
 * Simple trolley data object.
 * Holds box counts by type and the total load.
 */
public class Trolley {
	public final int id;
	public final Map<BoxType, Integer> load = new HashMap<>();
	public int totalLoad = 0;

	public Trolley(int id) {
		this.id = id;
		for (BoxType t : BoxType.getAllTypes()) {
			load.put(t, 0);
		}
	}
}
