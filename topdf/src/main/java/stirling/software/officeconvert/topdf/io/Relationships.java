package stirling.software.officeconvert.topdf.io;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Relationships {

    static final Relationships NONE = new Relationships(List.of());

    private final List<Relationship> all;

    private final Map<String, Relationship> byId;

    Relationships(List<Relationship> all) {
        this.all = List.copyOf(all);
        Map<String, Relationship> ids = new LinkedHashMap<>();
        for (Relationship r : this.all) {
            ids.putIfAbsent(r.id(), r);
        }
        this.byId = Collections.unmodifiableMap(ids);
    }

    public List<Relationship> all() {
        return all;
    }

    public Relationship get(String id) {
        return id == null ? null : byId.get(id);
    }

    public List<Relationship> ofType(String typeOrName) {
        List<Relationship> out = new ArrayList<>();
        for (Relationship r : all) {
            if (r.is(typeOrName)) {
                out.add(r);
            }
        }
        return out;
    }

    public Relationship first(String typeOrName) {
        for (Relationship r : all) {
            if (r.is(typeOrName)) {
                return r;
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return all.isEmpty();
    }

    public int size() {
        return all.size();
    }
}
