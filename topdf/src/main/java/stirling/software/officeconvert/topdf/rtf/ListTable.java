package stirling.software.officeconvert.topdf.rtf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ListTable {

    static final int MAX = 4096;

    static final int MAX_LEVELS = 9;

    static final class Level {
        int format;
        int justify;
        int start = 1;
        int follow;
        boolean legal;
        boolean noRestart;
        final List<Integer> text = new ArrayList<>();
        final CharProps chp = new CharProps();
        final ParaProps pap = new ParaProps();
    }

    static final class ListDef {
        int id;
        boolean hybrid;
        final List<Level> levels = new ArrayList<>();
    }

    static final class Override {
        int listId;
        int ls;
        int lfoCount;
        final Map<Integer, Integer> starts = new LinkedHashMap<>();
    }

    final Map<Integer, ListDef> lists = new LinkedHashMap<>();

    final Map<Integer, Override> overrides = new LinkedHashMap<>();

    void add(ListDef d) {
        if (lists.size() < MAX) {
            lists.putIfAbsent(d.id, d);
        }
    }

    void add(Override o) {
        if (overrides.size() < MAX && o.ls > 0) {
            overrides.putIfAbsent(o.ls, o);
        }
    }

    boolean numbered(int ls) {
        Override o = overrides.get(ls);
        return o != null && lists.containsKey(o.listId);
    }
}
