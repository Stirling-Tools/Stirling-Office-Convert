package stirling.software.officeconvert.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Numbering {

    public record Level(
            String format, String text, RunStyle markerStyle, float indentLeft, float hanging) {}

    public static final class AbstractList {
        public final int id;
        public final Level[] levels = new Level[9];

        AbstractList(int id) {
            this.id = id;
        }
    }

    public static final class Instance {
        public final int numId;
        public final AbstractList definition;
        public final int[] starts = new int[9];

        Instance(int numId, AbstractList definition) {
            this.numId = numId;
            this.definition = definition;
            Arrays.fill(starts, 1);
        }
    }

    public final List<AbstractList> definitions = new ArrayList<>();
    public final List<Instance> instances = new ArrayList<>();

    public Instance create() {
        AbstractList def = new AbstractList(definitions.size());
        definitions.add(def);
        Instance inst = new Instance(instances.size() + 1, def);
        instances.add(inst);
        return inst;
    }

    public boolean isEmpty() {
        return instances.isEmpty();
    }
}
