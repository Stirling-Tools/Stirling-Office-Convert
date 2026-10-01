package stirling.software.officeconvert.topdf.docx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Styles {

    private record Def(String id, String type, String basedOn, String link, XEl el) {}

    final RunProps defaultRun = new RunProps();

    final ParaProps defaultPara = new ParaProps();

    private final Map<String, Def> defs = new HashMap<>();

    private final Theme theme;

    private String defaultParagraph;

    private String defaultCharacter;

    private String defaultTable;

    private final Map<String, ParaProps> paraCache = new HashMap<>();

    private final Map<String, RunProps> paraRunCache = new HashMap<>();

    private final Map<String, RunProps> charCache = new HashMap<>();

    private final Map<String, TableStyle> tableCache = new HashMap<>();

    Styles(XEl styles, Theme theme) {
        this.theme = theme;
        defaultRun.size = 10f;
        if (styles == null) {
            return;
        }
        XEl dd = styles.child("w:docDefaults");
        if (dd != null) {
            defaultRun.apply(dd.path("w:rPrDefault", "w:rPr"), theme);
            defaultPara.apply(dd.path("w:pPrDefault", "w:pPr"), theme);
        }
        for (XEl s : styles.children("w:style")) {
            String id = s.attr("styleId");
            if (id == null) {
                continue;
            }
            String type = s.attr("type", "paragraph");
            XEl based = s.child("w:basedOn");
            XEl link = s.child("w:link");
            Def d = new Def(id, type, based == null ? null : based.val(), link == null ? null : link.val(), s);
            defs.putIfAbsent(id, d);
            if (Ooxml.flag(s.attr("default"), false)) {
                switch (type) {
                    case "paragraph" -> defaultParagraph = defaultParagraph == null ? id : defaultParagraph;
                    case "character" -> defaultCharacter = defaultCharacter == null ? id : defaultCharacter;
                    case "table" -> defaultTable = defaultTable == null ? id : defaultTable;
                    default -> {
                    }
                }
            }
        }
    }

    String defaultParagraphStyle() {
        return defaultParagraph;
    }

    boolean exists(String id) {
        return id != null && defs.containsKey(id);
    }

    String type(String id) {
        Def d = id == null ? null : defs.get(id);
        return d == null ? null : d.type;
    }

    private List<Def> chain(String id, String type) {
        List<Def> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cur = id;
        while (cur != null && seen.add(cur) && out.size() < 64) {
            Def d = defs.get(cur);
            if (d == null || (type != null && !type.equals(d.type))) {
                break;
            }
            out.add(0, d);
            cur = d.basedOn;
        }
        return out;
    }

    Integer listNum(String id) {
        if (id == null || !defs.containsKey(id)) {
            return null;
        }
        String type = defs.get(id).type;
        ParaProps p = new ParaProps();
        for (Def d : chain(id, type)) {
            p.mergeFrom(ParaProps.parse(d.el.child("w:pPr"), theme));
        }
        return p.numId;
    }

    ParaProps paragraph(String id) {
        String key = id == null || !defs.containsKey(id) ? defaultParagraph : id;
        if (key == null) {
            return new ParaProps();
        }
        return paraCache.computeIfAbsent(key, k -> {
            ParaProps p = new ParaProps();
            for (Def d : chain(k, "paragraph")) {
                p.mergeFrom(ParaProps.parse(d.el.child("w:pPr"), theme));
            }
            return p;
        });
    }

    RunProps paragraphRun(String id) {
        String key = id == null || !defs.containsKey(id) ? defaultParagraph : id;
        if (key == null) {
            return new RunProps();
        }
        return paraRunCache.computeIfAbsent(key, k -> {
            RunProps r = new RunProps();
            for (Def d : chain(k, "paragraph")) {
                r.mergeFrom(RunProps.parse(d.el.child("w:rPr"), theme));
            }
            return r;
        });
    }

    RunProps character(String id) {
        if (id == null) {
            return null;
        }
        Def d = defs.get(id);
        if (d == null) {
            return null;
        }
        if (d.type.equals("paragraph")) {
            if (d.link != null && defs.containsKey(d.link) && "character".equals(defs.get(d.link).type)) {
                return character(d.link);
            }
            return paragraphRun(id);
        }
        return charCache.computeIfAbsent(id, k -> {
            RunProps r = new RunProps();
            for (Def c : chain(k, "character")) {
                r.mergeFrom(RunProps.parse(c.el.child("w:rPr"), theme));
            }
            return r;
        });
    }

    TableStyle table(String id) {
        String key = id == null || !defs.containsKey(id) ? defaultTable : id;
        if (key == null) {
            return TableStyle.EMPTY;
        }
        return tableCache.computeIfAbsent(key, k -> {
            TableStyle t = new TableStyle();
            for (Def d : chain(k, "table")) {
                TableStyle own = new TableStyle();
                own.base.mergeFrom(TableStyle.parsePart(d.el, theme));
                for (XEl c : d.el.children("w:tblStylePr")) {
                    String type = c.attr("type");
                    if (type != null) {
                        own.conditional.computeIfAbsent(type, x -> new TableStyle.Part())
                                .mergeFrom(TableStyle.parsePart(c, theme));
                    }
                }
                t.mergeFrom(own);
            }
            return t;
        });
    }
}
