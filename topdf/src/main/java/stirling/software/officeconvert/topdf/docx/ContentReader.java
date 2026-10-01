package stirling.software.officeconvert.topdf.docx;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.Relationship;

final class ContentReader {

    record Layer(ParaProps pPr, RunProps rPr, boolean styled) {}

    private static final class FieldState {
        final StringBuilder instr = new StringBuilder();
        boolean separated;
        String name = "";
        Inline.Link link;
        boolean computed;
        RunProps rp;
        final StringBuilder cached = new StringBuilder();
    }

    private final DocxPackage pkg;

    private final String part;

    private final Deque<FieldState> fields = new ArrayDeque<>();

    private final DrawingReader drawings;

    private int depth;

    private int autonum;

    private int displayMath = -1;

    static final int MAX_DEPTH = 40;

    ContentReader(DocxPackage pkg, String part) {
        this.pkg = pkg;
        this.part = part;
        this.drawings = new DrawingReader(pkg, part, this);
    }

    String part() {
        return part;
    }

    List<Block> blocks(XEl container, Layer layer) {
        List<Block> out = new ArrayList<>();
        if (container == null) {
            return out;
        }
        if (++depth > MAX_DEPTH) {
            depth--;
            if (!container.kids.isEmpty() && pkg != null) {
                pkg.leftOut("Content nested more than " + MAX_DEPTH + " levels deep was left out");
            }
            return out;
        }
        try {
            for (XEl k : container.kids) {
                block(k, out, layer);
            }
        } finally {
            depth--;
        }
        return joinMarks(out);
    }

    // A deleted or hidden paragraph mark runs the paragraph into the next one, as Word's final view shows it
    static List<Block> joinMarks(List<Block> blocks) {
        List<Block> out = new ArrayList<>(blocks.size());
        Para pending = null;
        for (Block b : blocks) {
            if (pending != null && b instanceof Para next) {
                List<Inline> items = new ArrayList<>(pending.items);
                items.addAll(next.items);
                Para lead = pending.empty() || ParaFlow.hidden(pending) ? next : pending;
                Para joined = new Para(lead.pp, next.mark, items, lead.styleId, lead.label, lead.labelProps,
                        lead.level);
                joined.section = next.section;
                joined.joinsNext = next.joinsNext;
                b = joined;
                pending = null;
            } else if (pending != null) {
                out.add(pending);
                pending = null;
            }
            if (b instanceof Para p && p.joinsNext && p.section == null) {
                pending = p;
            } else {
                out.add(b);
            }
        }
        if (pending != null) {
            out.add(pending);
        }
        return out;
    }

    void block(XEl e, List<Block> out, Layer layer) {
        switch (e.name) {
            case "w:p" -> out.add(paragraph(e, layer));
            case "w:tbl" -> {
                TableBlock t = table(e);
                if (t != null) {
                    out.add(t);
                }
            }
            case "w:sdt" -> {
                XEl content = e.child("w:sdtContent");
                if (content != null) {
                    for (XEl k : content.kids) {
                        block(k, out, layer);
                    }
                }
            }
            case "w:customXml", "w:ins", "w:moveTo", "w:smartTag" -> {
                for (XEl k : e.kids) {
                    block(k, out, layer);
                }
            }
            default -> {
            }
        }
    }

    Para paragraph(XEl p, Layer layer) {
        XEl pPr = p.child("w:pPr");
        ParaProps direct = ParaProps.parse(pPr, pkg.theme);
        Styles styles = pkg.styles;
        String styleId = direct.styleId != null && styles.exists(direct.styleId) ? direct.styleId
                : styles.defaultParagraphStyle();
        ParaProps styled = styles.paragraph(styleId);
        Integer numId = direct.numId != null ? direct.numId : styled.numId;
        Integer ilvl = direct.ilvl != null ? direct.ilvl : styled.ilvl;
        ParaProps pp = new ParaProps();
        pp.mergeFrom(styles.defaultPara);
        if (layer != null) {
            pp.mergeFrom(layer.pPr());
        }
        Numbering.Level level = null;
        if (numId != null && numId > 0) {
            level = pkg.numbering.level(numId, ilvl == null ? 0 : ilvl);
        }
        // Numbering set by the style ranks below the style's own indents; numbering set on the paragraph ranks above
        boolean styleNumbering = direct.numId == null && direct.ilvl == null;
        if (level != null && styleNumbering) {
            pp.mergeFrom(level.pPr);
        }
        pp.mergeFrom(styled);
        if (level != null && !styleNumbering) {
            pp.mergeFrom(level.pPr);
        }
        pp.mergeFrom(direct);
        pp.styleId = styleId;
        pp.numId = numId;
        pp.ilvl = ilvl;
        RunProps paraRun = baseRun(styleId, layer);
        RunProps mark = paraRun.copy();
        XEl markPr = pPr == null ? null : pPr.child("w:rPr");
        if (markPr != null) {
            RunProps markDirect = RunProps.parse(markPr, pkg.theme);
            if (markDirect.styleId != null) {
                mark.mergeFrom(styles.character(markDirect.styleId));
            }
            mark.mergeFrom(markDirect);
        }
        List<Inline> items = new ArrayList<>();
        int outerMath = displayMath;
        displayMath = MathReader.alone(p.kids) ? 0 : -1;
        try {
            inline(p.kids, items, paraRun, null);
        } finally {
            displayMath = outerMath;
        }
        boolean deleted = markPr != null && (markPr.child("w:del") != null || markPr.child("w:moveFrom") != null);
        String label = null;
        RunProps labelProps = null;
        if (level != null && !(deleted && items.stream().allMatch(i -> i instanceof Inline.Bookmark))) {
            String next = pkg.numbering.next(numId, ilvl == null ? 0 : ilvl);
            labelProps = mark.copy();
            labelProps.underline = null;
            labelProps.mergeFrom(level.rPr);
            if (next != null && !labelProps.hidden()) {
                label = next;
            }
        }
        Para para = new Para(pp, mark, items, styleId, label, labelProps, level);
        para.joinsNext = deleted || mark.hidden();
        if (pPr != null && pPr.child("w:sectPr") != null && !deleted) {
            para.section = SectionProps.parse(pPr.child("w:sectPr"));
        }
        return para;
    }

    private RunProps baseRun(String styleId, Layer layer) {
        RunProps rp = pkg.styles.defaultRun.copy();
        if (layer != null) {
            rp.mergeFrom(layer.rPr());
        }
        Float tableSize = rp.size;
        rp.mergeFrom(pkg.styles.paragraphRun(styleId));
        // Before Word 2013 a table style's font size wins over the Normal style's inside the table
        if (layer != null && layer.styled() && !pkg.settings.overrideTableStyleFontSize
                && pkg.settings.compatibilityMode < 15
                && Objects.equals(styleId, pkg.styles.defaultParagraphStyle())) {
            rp.size = tableSize;
        }
        rp.styleId = null;
        return rp;
    }

    private RunProps runProps(XEl rPr, RunProps paraRun) {
        if (rPr == null) {
            return paraRun;
        }
        RunProps direct = RunProps.parse(rPr, pkg.theme);
        RunProps rp = paraRun.copy();
        if (direct.styleId != null) {
            rp.mergeFrom(pkg.styles.character(direct.styleId));
        }
        rp.mergeFrom(direct);
        return rp;
    }

    void inline(List<XEl> kids, List<Inline> out, RunProps paraRun, Inline.Link link) {
        for (XEl k : kids) {
            switch (k.name) {
                case "w:r" -> run(k, out, paraRun, link);
                case "w:hyperlink" -> inline(k.kids, out, paraRun, hyperlink(k));
                case "w:fldSimple" -> simpleField(k, out, paraRun, link);
                case "w:ins", "w:moveTo", "w:customXml", "w:smartTag", "w:dir", "w:bdo" ->
                        inline(k.kids, out, paraRun, link);
                case "w:sdt" -> {
                    XEl content = k.child("w:sdtContent");
                    if (content != null) {
                        inline(content.kids, out, paraRun, link);
                    }
                }
                case "w:bookmarkStart" -> {
                    String name = k.attr("name");
                    if (name != null && !name.isEmpty()) {
                        out.add(new Inline.Bookmark(name));
                    }
                }
                case "m:oMathPara", "m:oMath" -> {
                    if (displayMath >= 0 && k.is("m:oMath")) {
                        if (MathReader.display(k, out, paraRun, link, drawings.fonts(), r -> runProps(r, paraRun),
                                displayMath == 0)) {
                            displayMath++;
                            continue;
                        }
                    }
                    if (!MathReader.read(k, out, paraRun, link, drawings.fonts(), r -> runProps(r, paraRun))) {
                        math(k, out, paraRun, link);
                    }
                }
                case "w:br", "w:cr", "w:tab" -> runChild(k, paraRun, out, link);
                default -> {
                }
            }
        }
    }

    // Equations are written in Word's linear form (x+1)/2, y^2, with scripts raised; nothing is evaluated
    private void math(XEl e, List<Inline> out, RunProps paraRun, Inline.Link link) {
        math(e, out, paraRun, null, link);
    }

    private void math(XEl e, List<Inline> out, RunProps paraRun, String script, Inline.Link link) {
        if (e == null) {
            return;
        }
        boolean para = e.is("m:oMathPara");
        boolean firstEquation = true;
        for (XEl k : e.kids) {
            if (para && k.is("m:oMath")) {
                if (!firstEquation) {
                    out.add(new Inline.Break("textWrapping", paraRun));
                }
                firstEquation = false;
            }
            mathNode(k, out, paraRun, script, link);
        }
    }

    private void mathNode(XEl k, List<Inline> out, RunProps paraRun, String script, Inline.Link link) {
        switch (k.name) {
            case "m:r" -> {
                RunProps rp = runProps(k.child("w:rPr"), paraRun);
                if (rp.ascii == null && rp.asciiTheme == null || script != null) {
                    rp = rp.copy();
                    if (rp.ascii == null && rp.asciiTheme == null) {
                        rp.ascii = "Cambria Math";
                        rp.hAnsi = "Cambria Math";
                        rp.asciiTheme = null;
                        rp.hAnsiTheme = null;
                    }
                    if (script != null) {
                        rp.vertAlign = script;
                    }
                }
                for (XEl t : k.children("m:t")) {
                    text(t.text(), rp, out, link);
                }
            }
            case "w:r" -> run(k, out, paraRun, link);
            case "m:f" -> {
                grouped(k.child("m:num"), out, paraRun, script, link);
                mathText("/", out, paraRun, script, link);
                grouped(k.child("m:den"), out, paraRun, script, link);
            }
            case "m:sSup", "m:sSub", "m:sSubSup", "m:limLow", "m:limUpp" -> {
                math(k.child("m:e"), out, paraRun, script, link);
                scripts(k, out, paraRun, link);
            }
            case "m:sPre" -> {
                scripts(k, out, paraRun, link);
                math(k.child("m:e"), out, paraRun, script, link);
            }
            case "m:rad" -> {
                XEl deg = k.child("m:deg");
                if (deg != null && !mathText(deg).isBlank() && !mathFlag(k.child("m:radPr"), "m:degHide")) {
                    math(deg, out, paraRun, "superscript", link);
                }
                mathText("\u221A", out, paraRun, script, link);
                grouped(k.child("m:e"), out, paraRun, script, link);
            }
            case "m:nary" -> {
                XEl pr = k.child("m:naryPr");
                String chr = mathAttr(pr, "m:chr", "\u222B");
                mathText(chr, out, paraRun, script, link);
                if (!mathFlag(pr, "m:subHide")) {
                    math(k.child("m:sub"), out, paraRun, "subscript", link);
                }
                if (!mathFlag(pr, "m:supHide")) {
                    math(k.child("m:sup"), out, paraRun, "superscript", link);
                }
                math(k.child("m:e"), out, paraRun, script, link);
            }
            case "m:d" -> {
                XEl pr = k.child("m:dPr");
                mathText(mathAttr(pr, "m:begChr", "("), out, paraRun, script, link);
                String sep = mathAttr(pr, "m:sepChr", "|");
                boolean first = true;
                for (XEl part : k.children("m:e")) {
                    if (!first) {
                        mathText(sep, out, paraRun, script, link);
                    }
                    first = false;
                    math(part, out, paraRun, script, link);
                }
                mathText(mathAttr(pr, "m:endChr", ")"), out, paraRun, script, link);
            }
            case "m:acc" -> {
                math(k.child("m:e"), out, paraRun, script, link);
                mathText(mathAttr(k.child("m:accPr"), "m:chr", "\u0302"), out, paraRun, script, link);
            }
            case "m:func" -> {
                math(k.child("m:fName"), out, paraRun, script, link);
                mathText(" ", out, paraRun, script, link);
                math(k.child("m:e"), out, paraRun, script, link);
            }
            case "m:m" -> {
                boolean firstRow = true;
                for (XEl row : k.children("m:mr")) {
                    if (!firstRow) {
                        mathText("; ", out, paraRun, script, link);
                    }
                    firstRow = false;
                    boolean firstCell = true;
                    for (XEl cell : row.children("m:e")) {
                        if (!firstCell) {
                            mathText("  ", out, paraRun, script, link);
                        }
                        firstCell = false;
                        math(cell, out, paraRun, script, link);
                    }
                }
            }
            case "m:eqArr" -> {
                boolean first = true;
                for (XEl row : k.children("m:e")) {
                    if (!first) {
                        out.add(new Inline.Break("textWrapping", paraRun));
                    }
                    first = false;
                    math(row, out, paraRun, script, link);
                }
            }
            default -> {
                if (!k.name.endsWith("Pr")) {
                    math(k, out, paraRun, script, link);
                }
            }
        }
    }

    private void scripts(XEl k, List<Inline> out, RunProps paraRun, Inline.Link link) {
        math(k.child("m:sub"), out, paraRun, "subscript", link);
        math(k.child("m:lim"), out, paraRun, k.is("m:limUpp") ? "superscript" : "subscript", link);
        math(k.child("m:sup"), out, paraRun, "superscript", link);
    }

    private void grouped(XEl e, List<Inline> out, RunProps paraRun, String script, Inline.Link link) {
        String text = mathText(e);
        boolean wrap = text.codePoints().count() > 1 && text.codePoints().anyMatch(c -> !Character.isLetterOrDigit(c)
                && c != '.' && c != ',');
        if (wrap) {
            mathText("(", out, paraRun, script, link);
        }
        math(e, out, paraRun, script, link);
        if (wrap) {
            mathText(")", out, paraRun, script, link);
        }
    }

    private void mathText(String text, List<Inline> out, RunProps paraRun, String script, Inline.Link link) {
        if (text.isEmpty()) {
            return;
        }
        RunProps rp = paraRun.copy();
        if (rp.ascii == null && rp.asciiTheme == null) {
            rp.ascii = "Cambria Math";
            rp.hAnsi = "Cambria Math";
            rp.hAnsiTheme = null;
        }
        rp.vertAlign = script;
        text(text, rp, out, link);
    }

    private static String mathText(XEl e) {
        if (e == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (XEl t : e.descendants("m:t")) {
            sb.append(t.text());
        }
        return sb.toString();
    }

    private static String mathAttr(XEl pr, String name, String fallback) {
        XEl c = pr == null ? null : pr.child(name);
        if (c == null) {
            return fallback;
        }
        String v = c.attr("m:val", c.attr("val"));
        return v == null ? fallback : v;
    }

    private static boolean mathFlag(XEl pr, String name) {
        XEl c = pr == null ? null : pr.child(name);
        return c != null && Ooxml.flag(c.attr("m:val", c.attr("val")), true);
    }

    private Inline.Link hyperlink(XEl k) {
        String anchor = k.attr("anchor");
        String id = k.attr("r:id");
        String url = null;
        if (id != null) {
            Relationship r = pkg.relationship(part, id);
            url = r == null ? null : ActiveContent.hyperlink(r);
        }
        if (url == null && anchor == null) {
            return null;
        }
        return new Inline.Link(url, anchor);
    }

    private void simpleField(XEl k, List<Inline> out, RunProps paraRun, Inline.Link link) {
        String instr = k.attr("instr", "");
        String name = ActiveContent.fieldName(instr);
        if (ActiveContent.computedField(instr)) {
            XEl firstRun = k.child("w:r");
            RunProps rp = runProps(firstRun == null ? null : firstRun.child("w:rPr"), paraRun);
            out.add(new Inline.Field(name, rp, collectText(k), link, fieldFormat(instr)));
            return;
        }
        Inline.Link inner = name.equals("HYPERLINK") ? fieldLink(instr) : null;
        inline(k.kids, out, paraRun, inner != null ? inner : link);
    }

    // A page field's format switch picks its number format; applying it is formatting, not execution
    static String fieldFormat(String instr) {
        List<String> tokens = tokens(instr);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (!tokens.get(i).equals("\\*")) {
                continue;
            }
            String f = switch (tokens.get(i + 1)) {
                case "ROMAN" -> "upperRoman";
                case "roman" -> "lowerRoman";
                case "ALPHABETIC" -> "upperLetter";
                case "alphabetic" -> "lowerLetter";
                case "Arabic" -> "decimal";
                case "ArabicDash" -> "numberInDash";
                case "Ordinal", "ordinal" -> "ordinal";
                case "CardText", "cardtext" -> "cardinalText";
                case "OrdText", "ordtext" -> "ordinalText";
                case "Hex", "hex" -> "hex";
                default -> null;
            };
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    private static String collectText(XEl e) {
        StringBuilder sb = new StringBuilder();
        for (XEl t : e.descendants("w:t")) {
            sb.append(t.text());
        }
        return sb.toString();
    }

    static Inline.Link fieldLink(String instr) {
        String rest = instr.strip();
        int sp = rest.indexOf(' ');
        rest = sp < 0 ? "" : rest.substring(sp + 1).strip();
        String url = null;
        String anchor = null;
        List<String> tokens = tokens(rest);
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equalsIgnoreCase("\\l") && i + 1 < tokens.size()) {
                anchor = tokens.get(++i);
            } else if (t.startsWith("\\")) {
                if (t.length() == 2 && "otm".indexOf(Character.toLowerCase(t.charAt(1))) >= 0 && i + 1 < tokens.size()) {
                    i++;
                }
            } else if (url == null) {
                url = t;
            }
        }
        String safe = url == null ? null : stirling.software.officeconvert.topdf.pdf.SafeLinks.safeUrl(url);
        if (safe == null && anchor == null) {
            return null;
        }
        return new Inline.Link(safe, anchor);
    }

    private static String autonumSeparator(String instr) {
        List<String> t = tokens(instr);
        for (int i = 1; i + 1 < t.size(); i++) {
            if (t.get(i).equalsIgnoreCase("\\s") && !t.get(i + 1).isEmpty()) {
                return t.get(i + 1).substring(0, 1);
            }
        }
        return ".";
    }

    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"') {
                int end = s.indexOf('"', i + 1);
                if (end < 0) {
                    end = s.length();
                }
                out.add(s.substring(i + 1, end));
                i = end + 1;
            } else {
                int end = i;
                while (end < s.length() && !Character.isWhitespace(s.charAt(end))) {
                    end++;
                }
                out.add(s.substring(i, end));
                i = end;
            }
        }
        return out;
    }

    private boolean inInstruction() {
        for (FieldState f : fields) {
            if (!f.separated) {
                return true;
            }
        }
        return false;
    }

    private Inline.Link fieldLinkInScope(Inline.Link link) {
        for (FieldState f : fields) {
            if (f.link != null) {
                return f.link;
            }
        }
        return link;
    }

    private void run(XEl r, List<Inline> out, RunProps paraRun, Inline.Link link) {
        RunProps rp = runProps(r.child("w:rPr"), paraRun);
        for (XEl k : r.kids) {
            switch (k.name) {
                case "w:fldChar" -> fieldChar(k, rp, out, link);
                case "w:instrText" -> {
                    if (!fields.isEmpty() && !fields.peek().separated) {
                        FieldState f = fields.peek();
                        if (f.instr.length() < 4096) {
                            f.instr.append(k.text());
                        }
                    }
                }
                default -> runChild(k, rp, out, link);
            }
        }
    }

    private void fieldChar(XEl k, RunProps rp, List<Inline> out, Inline.Link link) {
        String type = k.attr("fldCharType", "");
        switch (type) {
            case "begin" -> {
                if (fields.size() < 32) {
                    FieldState f = new FieldState();
                    f.rp = rp;
                    fields.push(f);
                }
            }
            case "separate" -> {
                if (!fields.isEmpty()) {
                    FieldState f = fields.peek();
                    separate(f);
                }
            }
            case "end" -> {
                if (!fields.isEmpty()) {
                    FieldState f = fields.pop();
                    boolean result = f.separated;
                    if (!f.separated) {
                        separate(f);
                    }
                    if (!result && "AUTONUM".equals(f.name) && !inInstruction() && computedParentAllowsOutput()) {
                        text(++autonum + autonumSeparator(f.instr.toString()), f.rp, out, fieldLinkInScope(link));
                    }
                    if (f.computed && !inInstruction() && computedParentAllowsOutput()) {
                        out.add(new Inline.Field(f.name, f.rp, f.cached.toString(), fieldLinkInScope(link),
                                fieldFormat(f.instr.toString())));
                    }
                }
            }
            default -> {
            }
        }
    }

    private boolean computedParentAllowsOutput() {
        for (FieldState f : fields) {
            if (f.computed) {
                return false;
            }
        }
        return true;
    }

    private void separate(FieldState f) {
        f.separated = true;
        String instr = f.instr.toString();
        f.name = ActiveContent.fieldName(instr);
        f.computed = ActiveContent.computedField(instr);
        if (f.name.equals("HYPERLINK")) {
            f.link = fieldLink(instr);
        }
    }

    private boolean suppressed(RunProps rp) {
        if (fields.isEmpty()) {
            return false;
        }
        if (inInstruction()) {
            return true;
        }
        for (FieldState f : fields) {
            if (f.computed) {
                return true;
            }
        }
        return false;
    }

    private void cache(String text, RunProps rp) {
        for (FieldState f : fields) {
            if (f.computed) {
                if (f.cached.length() == 0) {
                    f.rp = rp;
                }
                f.cached.append(text);
                return;
            }
        }
    }

    private void runChild(XEl k, RunProps rp, List<Inline> out, Inline.Link outerLink) {
        Inline.Link link = fieldLinkInScope(outerLink);
        if (suppressed(rp)) {
            if (k.is("w:t") && !inInstruction()) {
                cache(k.text(), rp);
            }
            return;
        }
        switch (k.name) {
            case "w:t" -> text(preserve(k), rp, out, link);
            case "w:tab" -> out.add(new Inline.Tab(rp, link));
            case "w:br" -> {
                String type = k.attr("type", "textWrapping");
                String clear = k.attr("clear");
                if (type.equals("textWrapping") && clear != null && !clear.equals("none")) {
                    type = "clear";
                }
                out.add(new Inline.Break(type, rp));
            }
            case "w:cr" -> out.add(new Inline.Break("textWrapping", rp));
            case "w:noBreakHyphen" -> text("\u2011", rp, out, link);
            case "w:softHyphen" -> text("\u00AD", rp, out, link);
            case "w:sym" -> symbol(k, rp, out, link);
            case "w:footnoteReference", "w:endnoteReference" -> {
                Integer id = Ooxml.integer(k.attr("id"));
                if (id != null) {
                    boolean custom = Ooxml.flag(k.attr("customMarkFollows"), false);
                    out.add(new Inline.NoteRef(k.is("w:endnoteReference"), id, rp, custom ? "" : null));
                }
            }
            case "w:footnoteRef", "w:endnoteRef" -> out.add(new Inline.NoteMark(rp));
            case "w:separator" -> out.add(new Inline.Break("separator", rp));
            case "w:continuationSeparator" -> out.add(new Inline.Break("continuationSeparator", rp));
            case "w:pgNum" -> out.add(new Inline.Field("PAGE", rp, "", link, null));
            case "w:drawing" -> {
                for (XEl d : k.kids) {
                    Drawing dr = drawings.drawingML(d);
                    if (dr != null && !dr.hidden) {
                        out.add(new Inline.Obj(dr, rp, link));
                    }
                }
            }
            case "w:pict", "w:object" -> {
                Drawing dr = drawings.vml(k);
                if (dr != null && !dr.hidden) {
                    out.add(new Inline.Obj(dr, rp, link));
                }
            }
            case "w:ptab" -> out.add(new Inline.PTab(k.attr("alignment", "left"), k.attr("relativeTo", "margin"),
                    TabStop.leader(k.attr("leader")), rp));
            case "w:ruby" -> {
                XEl base = k.child("w:rubyBase");
                if (base != null) {
                    inline(base.kids, out, rp, link);
                }
            }
            default -> {
            }
        }
    }

    private static String preserve(XEl t) {
        String s = t.text();
        if ("preserve".equals(t.attr("xml:space"))) {
            return s;
        }
        return s.strip();
    }

    private void text(String s, RunProps rp, List<Inline> out, Inline.Link link) {
        if (s.isEmpty()) {
            return;
        }
        String clean = clean(s);
        if (!clean.isEmpty()) {
            out.add(new Inline.Text(clean, rp, link));
        }
    }

    private static String clean(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char r = c;
            if (c == '\n' || c == '\r' || c == '\t') {
                r = ' ';
            } else if (c < 0x20 || (c >= 0xFFF0 && c <= 0xFFFF)) {
                r = 0;
            }
            if (r != c && sb == null) {
                sb = new StringBuilder(s.length());
                sb.append(s, 0, i);
            }
            if (sb != null && r != 0) {
                sb.append(r);
            }
        }
        return sb == null ? s : sb.toString();
    }

    private void symbol(XEl k, RunProps rp, List<Inline> out, Inline.Link link) {
        String font = k.attr("font");
        String ch = k.attr("char");
        if (ch == null) {
            return;
        }
        int code;
        try {
            code = Integer.parseInt(ch.trim(), 16);
        } catch (NumberFormatException e) {
            return;
        }
        if (code <= 0 || code > 0xFFFF) {
            return;
        }
        RunProps sym = rp.copy();
        if (font != null) {
            sym.ascii = font;
            sym.hAnsi = font;
            sym.eastAsia = font;
            sym.cs = font;
            sym.asciiTheme = null;
            sym.hAnsiTheme = null;
            sym.eastAsiaTheme = null;
            sym.csTheme = null;
        }
        out.add(new Inline.Text(String.valueOf((char) code), sym, link));
    }

    TableBlock table(XEl tbl) {
        TableProps direct = TableProps.parse(tbl.child("w:tblPr"), pkg.theme);
        TableStyle style = pkg.styles.table(direct.styleId);
        TableProps tp = new TableProps();
        tp.mergeFrom(style.base.tblPr);
        tp.mergeFrom(direct);
        tp.styleId = direct.styleId;
        List<Float> gridCols = new ArrayList<>();
        XEl grid = tbl.child("w:tblGrid");
        if (grid != null) {
            for (XEl g : grid.children("w:gridCol")) {
                gridCols.add(Math.max(0, Ooxml.twips(g.attr("w"), 0)));
            }
        }
        float[] gridArr = new float[gridCols.size()];
        for (int i = 0; i < gridArr.length; i++) {
            gridArr[i] = gridCols.get(i);
        }
        TableBlock t = new TableBlock(tp, gridArr);
        List<XEl> rows = new ArrayList<>();
        collectRows(tbl, rows);
        if (rows.isEmpty()) {
            return null;
        }
        int rowCount = rows.size();
        int rowBand = tp.rowBand == null || tp.rowBand < 1 ? 1 : tp.rowBand;
        int colBand = tp.colBand == null || tp.colBand < 1 ? 1 : tp.colBand;
        for (int ri = 0; ri < rowCount; ri++) {
            XEl tr = rows.get(ri);
            RowProps rp = new RowProps();
            rp.mergeFrom(style.base.trPr);
            rp.apply(tr.child("w:trPr"));
            TableBlock.Row row = new TableBlock.Row(rp);
            XEl ex = tr.child("w:tblPrEx");
            if (ex != null) {
                row.exceptions = TableProps.parse(ex, pkg.theme);
            }
            List<XEl> cells = new ArrayList<>();
            collectCells(tr, cells);
            int col = rp.gridBefore == null ? 0 : Math.max(0, rp.gridBefore);
            int cellCount = cells.size();
            for (int ci = 0; ci < cellCount; ci++) {
                XEl tc = cells.get(ci);
                CellProps directCell = CellProps.parse(tc.child("w:tcPr"), pkg.theme);
                int span = directCell.gridSpan == null ? 1 : directCell.gridSpan;
                List<String> conds = conditions(tp, ri, rowCount, col, span, gridArr.length, ci, cellCount,
                        rp, rowBand, colBand);
                TableStyle.Part merged = new TableStyle.Part();
                merged.mergeFrom(style.base);
                for (String c : TableStyle.ORDER) {
                    if (conds.contains(c)) {
                        TableStyle.Part part = style.conditional.get(c);
                        if (part != null) {
                            merged.mergeFrom(part);
                        }
                    }
                }
                CellProps cp = merged.tcPr.copy();
                cp.mergeFrom(directCell);
                cp.gridSpan = span;
                Layer layer = new Layer(merged.pPr, merged.rPr, direct.styleId != null);
                if ("continue".equals(directCell.hMerge) && !row.cells.isEmpty()) {
                    TableBlock.Cell previous = row.cells.get(row.cells.size() - 1);
                    previous.span += span;
                    previous.cp.gridSpan = previous.span;
                    if (directCell.right != null) {
                        previous.cp.right = directCell.right;
                    }
                    col += span;
                    continue;
                }
                List<Block> content = trailingMark(blocks(tc, layer));
                for (Block b : content) {
                    if (b instanceof Para para) {
                        para.items.removeIf(ContentReader::pageBreak);
                    }
                }
                TableBlock.Cell cell = new TableBlock.Cell(cp, content);
                cell.col = col;
                cell.span = span;
                row.cells.add(cell);
                col += span;
            }
            t.rows.add(row);
        }
        return t;
    }

    // Word ignores page and column breaks inside a table cell
    private static boolean pageBreak(Inline in) {
        return in instanceof Inline.Break b && ("page".equals(b.type()) || "column".equals(b.type()));
    }

    // Word gives no line to the empty paragraph that must follow a nested table at the end of a cell
    static List<Block> trailingMark(List<Block> content) {
        int n = content.size();
        if (n >= 2 && content.get(n - 1) instanceof Para p && p.empty() && p.section == null
                && content.get(n - 2) instanceof TableBlock) {
            return new ArrayList<>(content.subList(0, n - 1));
        }
        return content;
    }

    private static List<String> conditions(TableProps tp, int ri, int rows, int col, int span, int gridCols, int ci,
            int cells, RowProps rp, int rowBand, int colBand) {
        List<String> out = new ArrayList<>();
        boolean firstRow = Boolean.TRUE.equals(tp.firstRow) && ri == 0;
        boolean lastRow = Boolean.TRUE.equals(tp.lastRow) && ri == rows - 1;
        boolean firstCol = Boolean.TRUE.equals(tp.firstCol) && ci == 0;
        boolean lastCol = Boolean.TRUE.equals(tp.lastCol) && ci == cells - 1;
        out.add("wholeTable");
        if (!Boolean.TRUE.equals(tp.noVBand)) {
            int c = ci - (Boolean.TRUE.equals(tp.firstCol) ? 1 : 0);
            if (!firstCol && !lastCol && c >= 0) {
                out.add((c / colBand) % 2 == 0 ? "band1Vert" : "band2Vert");
            }
        }
        if (!Boolean.TRUE.equals(tp.noHBand)) {
            int r = ri - (Boolean.TRUE.equals(tp.firstRow) ? 1 : 0);
            if (!firstRow && !lastRow && r >= 0) {
                out.add((r / rowBand) % 2 == 0 ? "band1Horz" : "band2Horz");
            }
        }
        if (firstCol) {
            out.add("firstCol");
        }
        if (lastCol) {
            out.add("lastCol");
        }
        if (firstRow) {
            out.add("firstRow");
        }
        if (lastRow) {
            out.add("lastRow");
        }
        if (firstRow && firstCol) {
            out.add("nwCell");
        }
        if (firstRow && lastCol) {
            out.add("neCell");
        }
        if (lastRow && firstCol) {
            out.add("swCell");
        }
        if (lastRow && lastCol) {
            out.add("seCell");
        }
        return out;
    }

    private static void collectRows(XEl e, List<XEl> out) {
        for (XEl k : e.kids) {
            switch (k.name) {
                case "w:tr" -> {
                    XEl trPr = k.child("w:trPr");
                    if (trPr == null || trPr.child("w:del") == null && trPr.child("w:moveFrom") == null) {
                        out.add(k);
                    }
                }
                case "w:sdt" -> {
                    XEl c = k.child("w:sdtContent");
                    if (c != null) {
                        collectRows(c, out);
                    }
                }
                case "w:customXml", "w:ins", "w:moveTo" -> collectRows(k, out);
                default -> {
                }
            }
        }
    }

    private static void collectCells(XEl e, List<XEl> out) {
        for (XEl k : e.kids) {
            switch (k.name) {
                case "w:tc" -> {
                    XEl tcPr = k.child("w:tcPr");
                    if (tcPr == null || tcPr.child("w:cellDel") == null) {
                        out.add(k);
                    }
                }
                case "w:sdt" -> {
                    XEl c = k.child("w:sdtContent");
                    if (c != null) {
                        collectCells(c, out);
                    }
                }
                case "w:customXml", "w:ins", "w:moveTo" -> collectCells(k, out);
                default -> {
                }
            }
        }
    }
}
