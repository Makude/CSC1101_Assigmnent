import java.util.*;

/**
 * Enumeration of box categories in the warehouse.
 * Each delivery contains boxes distributed across these types.
 */
public enum BoxType {
    ELECTRONICS("electronics"),
    BOOKS("books"),
    MEDICINES("medicines"),
    CLOTHES("clothes"),
    TOOLS("tools");

    private final String label;

    BoxType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Get all box types as a sorted array for consistent iteration.
     */
    public static BoxType[] getAllTypes() {
        return values();
    }

    /**
     * Convert a section/label string (e.g. "electronics") to a BoxType.
     */
    public static BoxType fromLabel(String label) {
        if (label == null) return null;
        for (BoxType t : values()) {
            if (t.label.equals(label)) return t;
        }
        return null;
    }
}
