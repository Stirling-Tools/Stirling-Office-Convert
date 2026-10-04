package stirling.software.officeconvert.topdf.io;

import java.util.Objects;

public record Relationship(String id, String type, String target, boolean external, String part) {

    public Relationship {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        if (external && part != null) {
            throw new IllegalArgumentException("An external relationship has no part");
        }
    }

    public String typeName() {
        return typeName(type);
    }

    public boolean is(String typeOrName) {
        return typeOrName.indexOf('/') >= 0 ? type.equals(typeOrName) : typeName().equals(typeOrName);
    }

    static String typeName(String type) {
        return type.substring(type.lastIndexOf('/') + 1);
    }
}
