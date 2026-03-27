/**
 * Immutable representation of a box in the warehouse.
 * Each box has a type and is indistinguishable from other boxes of the same type.
 */
public class Box {
    private final BoxType type;

    public Box(BoxType type) {
        this.type = type;
    }

    public BoxType getType() {
        return type;
    }

    @Override
    public String toString() {
        return "Box(" + type.getLabel() + ")";
    }
}
