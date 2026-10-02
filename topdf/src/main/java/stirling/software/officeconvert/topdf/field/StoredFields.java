package stirling.software.officeconvert.topdf.field;

import java.util.Set;

public final class StoredFields {

    private static final Set<String> NAMES = Set.of("DATE", "TIME", "CREATEDATE", "SAVEDATE", "PRINTDATE", "AUTHOR",
            "TITLE", "SUBJECT", "KEYWORDS", "COMMENTS", "LASTSAVEDBY", "REVNUM", "TEMPLATE", "NUMWORDS", "NUMCHARS",
            "EDITTIME", "FILENAME", "DOCPROPERTY");

    private StoredFields() {}

    public static boolean stored(String kind) {
        return NAMES.contains(kind);
    }
}
