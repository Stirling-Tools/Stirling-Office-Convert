package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;

final class TextBody {

    static final int MAX_DEPTH = 48;

    private static final String TINY = "<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/>"
            + "<w:rPr><w:sz w:val=\"2\"/></w:rPr>";

    record ListPos(WordLists.Chain chain, int level, boolean label, Element style) {}

    private static final class Block {
        final String ppr;
        String content;
        final boolean paragraph;
        String sectPr;

        Block(String ppr, String content, boolean paragraph) {
            this.ppr = ppr;
            this.content = content;
            this.paragraph = paragraph;
        }
    }

    private static final class Section {
        String master;
        Element columns;
        boolean continuous;
        String pageStart;
        int blocks;
    }

    final OdtWriter w;

    final Part part;

    final Styles.Scope scope;

    private final boolean top;

    private final int depth;

    private final List<Block> blocks = new ArrayList<>();

    private final List<String> anchors = new ArrayList<>();

    private boolean pageBreak;

    private Element sectionColumns;

    private Props paraBase;

    private Props textBase;

    private Section section;

    private final List<Section> sections = new ArrayList<>();

    TextBody(OdtWriter w, Part part, Styles.Scope scope, boolean top, int depth) {
        this.w = w;
        this.part = part;
        this.scope = scope;
        this.top = top;
        this.depth = depth;
        if (top) {
            section = new Section();
            Element first = w.styles.firstMaster();
            section.master = first == null ? null : Dom.attr(first, Ns.STYLE, "name");
            section.columns = w.pages.columns(section.master);
            w.currentMaster = section.master;
        }
    }

    void shapeText(Props paragraph, Props text) {
        paraBase = paragraph;
        textBase = text;
    }

    private Props paragraphProps(String name, String kind) {
        Props base = kind.equals("text-properties") ? textBase : paraBase;
        if (base == null) {
            return w.styles.props("paragraph", name, scope, kind, true);
        }
        Props p = new Props(base);
        p.merge(w.styles.props("paragraph", name, scope, kind, false));
        return p;
    }

    TextBody nested(Part p, Styles.Scope s) {
        return new TextBody(w, p, s, false, depth + 1);
    }

    String master() {
        return section == null ? w.currentMaster : section.master;
    }

    boolean tooDeep() {
        return depth > MAX_DEPTH;
    }

    Props span(Props run, String styleName) {
        if (styleName == null || styleName.isEmpty()) {
            return run;
        }
        Props p = new Props(run);
        p.merge(w.styles.props("text", styleName, scope, "text-properties", false));
        return p;
    }

    void blocks(Element parent, ListPos list) throws IOException {
        if (parent == null || tooDeep()) {
            return;
        }
        for (Element k : Dom.kids(parent)) {
            block(k, list);
        }
    }

    private void block(Element k, ListPos list) throws IOException {
        String ns = k.getNamespaceURI();
        String local = Dom.local(k);
        if (Ns.TABLE.equals(ns) && local.equals("table")) {
            table(k);
            return;
        }
        if (Ns.DRAW.equals(ns)) {
            String d = w.drawings.anchored(k, this);
            if (d != null) {
                anchors.add(d);
            }
            return;
        }
        if (!Ns.TEXT.equals(ns)) {
            if (Ns.LOEXT.equals(ns) && local.equals("content-control")) {
                blocks(k, list);
            }
            return;
        }
        switch (local) {
            case "p" -> paragraph(k, list, false);
            case "h" -> paragraph(k, list, true);
            case "list" -> list(k, list);
            case "numbered-paragraph" -> numbered(k);
            case "section" -> section(k);
            case "table-of-content", "illustration-index", "table-index", "object-index", "user-index",
                    "alphabetical-index", "bibliography" -> blocks(Dom.kid(k, Ns.TEXT, "index-body"), list);
            case "index-title", "index-body" -> blocks(k, list);
            default -> {
            }
        }
    }

    private void numbered(Element k) throws IOException {
        int level = Math.max(0, Math.min(8, Dom.integer(k, Ns.TEXT, "level", 1) - 1));
        String styleName = Dom.attr(k, Ns.TEXT, "style-name");
        Element style = w.styles.listStyle(styleName, scope);
        WordLists.Chain chain = null;
        String id = Dom.attr(k, Ns.TEXT, "list-id");
        if (style != null && !w.lists.full()) {
            chain = id == null ? null : w.listIds.get(id);
            if (chain == null) {
                chain = w.lists.chain(style, scope);
                if (id != null) {
                    w.listIds.put(id, chain);
                }
            }
        }
        for (Element p : Dom.kids(k)) {
            if (Dom.is(p, Ns.TEXT, "p") || Dom.is(p, Ns.TEXT, "h")) {
                paragraph(p, chain == null ? null : new ListPos(chain, level, true, style), false);
            }
        }
    }

    private void list(Element l, ListPos parent) throws IOException {
        if (tooDeep()) {
            return;
        }
        int level = parent == null ? 0 : Math.min(8, parent.level() + 1);
        WordLists.Chain chain;
        Element style;
        if (parent == null || parent.chain() == null) {
            String styleName = Dom.attr(l, Ns.TEXT, "style-name");
            if (styleName == null) {
                styleName = firstParagraphList(l);
            }
            style = w.styles.listStyle(styleName, scope);
            chain = style == null || w.lists.full() ? null : chain(l, styleName, style);
            if (parent != null && chain == null) {
                level = 0;
            }
        } else {
            chain = parent.chain();
            style = parent.style();
        }
        for (Element item : Dom.kids(l)) {
            boolean header = Dom.is(item, Ns.TEXT, "list-header");
            if (!header && !Dom.is(item, Ns.TEXT, "list-item")) {
                continue;
            }
            String start = Dom.attr(item, Ns.TEXT, "start-value");
            if (start != null && chain != null && !header) {
                w.lists.restart(chain, level, Dom.integer(item, Ns.TEXT, "start-value", 1));
            }
            boolean labelled = !header;
            for (Element g : Dom.kids(item)) {
                if (Dom.is(g, Ns.TEXT, "list")) {
                    list(g, new ListPos(chain, level, false, style));
                    labelled = false;
                } else if (Dom.is(g, Ns.TEXT, "p") || Dom.is(g, Ns.TEXT, "h")) {
                    paragraph(g, new ListPos(chain, level, labelled, style), Dom.is(g, Ns.TEXT, "h"));
                    labelled = false;
                } else {
                    block(g, new ListPos(chain, level, false, style));
                }
            }
        }
    }

    private String firstParagraphList(Element l) {
        for (Element item : Dom.kids(l)) {
            for (Element g : Dom.kids(item)) {
                if (Dom.is(g, Ns.TEXT, "p") || Dom.is(g, Ns.TEXT, "h")) {
                    return w.styles.inherited("paragraph", Dom.attr(g, Ns.TEXT, "style-name"), scope, Ns.STYLE,
                            "list-style-name");
                }
            }
        }
        return null;
    }

    private WordLists.Chain chain(Element l, String styleName, Element style) {
        WordLists.Chain chain = null;
        String cont = Dom.attr(l, Ns.TEXT, "continue-list");
        if (cont != null) {
            chain = w.listIds.get(cont);
        }
        if (chain == null && "true".equals(Dom.attr(l, Ns.TEXT, "continue-numbering"))) {
            chain = w.lastChain.get(styleName);
        }
        if (chain == null || chain.style != style) {
            chain = w.lists.chain(style, scope);
        }
        String id = Dom.attr(l, Ns.XML, "id");
        if (id != null) {
            w.listIds.put(id, chain);
        }
        w.lastChain.put(styleName, chain);
        return chain;
    }

    private void section(Element s) throws IOException {
        if ("none".equals(Dom.attr(s, Ns.TEXT, "display"))) {
            return;
        }
        if (!top) {
            blocks(s, null);
            return;
        }
        Props sp = w.styles.props("section", Dom.attr(s, Ns.TEXT, "style-name"), scope, "section-properties", false);
        Element cols = sp.kid("columns");
        Element outer = sectionColumns;
        if (cols == null) {
            cols = outer;
        }
        sectionColumns = cols;
        if (WordPages.columnCount(cols) != WordPages.columnCount(section.columns)) {
            newSection(section.master, cols, true, null);
        }
        blocks(s, null);
        sectionColumns = outer;
        Element after = columnsFor(section.master);
        if (WordPages.columnCount(after) != WordPages.columnCount(section.columns)) {
            newSection(section.master, after, true, null);
        }
    }

    private Element columnsFor(String master) {
        return sectionColumns != null ? sectionColumns : w.pages.columns(master);
    }

    private void newSection(String master, Element columns, boolean continuous, String pageStart) {
        if (section.blocks == 0) {
            section.master = master;
            w.currentMaster = master;
            section.columns = columns;
            section.continuous = section.continuous && continuous;
            if (pageStart != null) {
                section.pageStart = pageStart;
            }
            return;
        }
        Block last = blocks.get(blocks.size() - 1);
        if (!last.paragraph || last.sectPr != null) {
            last = new Block(TINY, "", true);
            blocks.add(last);
        }
        last.sectPr = w.pages.sectPr(section.master, section.columns, section.continuous, section.pageStart);
        sections.add(section);
        Section next = new Section();
        next.master = master;
        next.columns = columns;
        next.continuous = continuous;
        next.pageStart = pageStart;
        section = next;
        w.currentMaster = master;
    }

    private void masterBreak(String family, String styleName, Props pp) {
        if (!top) {
            return;
        }
        String mp = w.styles.inherited(family, styleName, scope, Ns.STYLE, "master-page-name");
        if (mp == null || mp.isEmpty() || w.styles.master(mp) == null) {
            return;
        }
        String number = pp == null ? null : pp.get("style:page-number");
        if (number != null && (number.equals("auto") || number.isBlank())) {
            number = null;
        }
        if (section.blocks == 0 && sections.isEmpty() && blocks.isEmpty()) {
            section.master = mp;
            section.columns = w.pages.columns(mp);
            w.currentMaster = mp;
            if (number != null) {
                section.pageStart = number;
            }
            return;
        }
        newSection(mp, columnsFor(mp), false, number);
        pageBreak = false;
    }

    private void table(Element t) throws IOException {
        if (tooDeep()) {
            return;
        }
        String name = Dom.attr(t, Ns.TABLE, "style-name");
        Props tp = w.styles.props("table", name, scope, "table-properties", false);
        masterBreak("table", name, tp);
        boolean breakBefore = pageBreak || "page".equals(tp.get("fo:break-before")) && !blocks.isEmpty();
        pageBreak = false;
        if (breakBefore || !anchors.isEmpty()) {
            StringBuilder c = new StringBuilder();
            for (String a : anchors) {
                c.append(a);
            }
            anchors.clear();
            add(new Block((breakBefore ? "<w:pageBreakBefore/>" : "")
                    + "<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/>"
                    + "<w:rPr><w:sz w:val=\"2\"/></w:rPr>", c.toString(), true));
        }
        String xml = w.tables.table(t, this);
        if (xml != null) {
            add(new Block(null, xml, false));
        }
        if ("page".equals(tp.get("fo:break-after"))) {
            pageBreak = true;
        }
    }

    private void add(Block b) {
        blocks.add(b);
        if (section != null) {
            section.blocks++;
        }
    }

    void paragraph(Element p, ListPos list, boolean heading) throws IOException {
        String name = Dom.attr(p, Ns.TEXT, "style-name");
        Props pp = paragraphProps(name, "paragraph-properties");
        Props tp = paragraphProps(name, "text-properties");
        masterBreak("paragraph", name, pp);
        StringBuilder ppr = new StringBuilder();
        String common = commonStyle(name);
        if (common != null) {
            ppr.append("<w:pStyle w:val=\"").append(w.styleId(common)).append("\"/>");
        }
        ppr.append(WordPara.keeps(pp));
        String before = pp.get("fo:break-before");
        if (pageBreak || "page".equals(before) && (!blocks.isEmpty() || !top)) {
            ppr.append("<w:pageBreakBefore/>");
        }
        pageBreak = false;
        WordLists.Chain chain = list == null ? null : list.chain();
        int level = list == null ? 0 : list.level();
        if (chain == null && heading && list == null
                && !"".equals(w.styles.inherited("paragraph", name, scope, Ns.STYLE, "list-style-name"))) {
            int outline = Dom.integer(p, Ns.TEXT, "outline-level", 0);
            if (outline >= 1 && w.outlineNumbered(outline - 1)) {
                chain = w.outlineChain();
                level = Math.min(8, outline - 1);
                list = new ListPos(chain, level, true, w.styles.outlineStyle());
            }
        }
        if (chain != null && list.label()) {
            if ("true".equals(Dom.attr(p, Ns.TEXT, "restart-numbering"))) {
                w.lists.restart(chain, level, Dom.integer(p, Ns.TEXT, "start-value",
                        WordLists.start(list.style(), level)));
            }
            ppr.append("<w:numPr><w:ilvl w:val=\"").append(level).append("\"/><w:numId w:val=\"").append(chain.numId)
                    .append("\"/></w:numPr>");
        }
        ppr.append(WordPara.borders(pp)).append(WordPara.shading(pp));
        double origin = 0;
        if (w.tabsRelative) {
            origin = pp.pt("fo:margin-left", 0);
            if (list != null && chain != null && !ownIndent(name)) {
                double li = WordLists.textIndent(list.style(), level);
                origin = Double.isNaN(li) ? origin : li;
            }
        }
        ppr.append(WordPara.tabs(pp, origin));
        if (w.autoHyphenation && "false".equals(tp.get("fo:hyphenate"))) {
            ppr.append("<w:suppressAutoHyphens/>");
        }
        ppr.append(WordPara.spacing(pp));
        if (list == null || chain == null) {
            ppr.append(WordPara.indent(pp));
        } else if (ownIndent(name)) {
            ppr.append(WordPara.indent(pp));
        } else if (!list.label()) {
            double li = WordLists.textIndent(list.style(), level);
            if (!Double.isNaN(li)) {
                ppr.append("<w:ind w:left=\"").append(Length.twips(li)).append("\" w:firstLine=\"0\"/>");
            }
        }
        if ("true".equals(pp.get("style:contextual-spacing"))) {
            ppr.append("<w:contextualSpacing/>");
        }
        if (WordPara.rtl(pp)) {
            ppr.append("<w:bidi/>");
        }
        String jc = WordPara.jc(pp);
        if (jc != null) {
            ppr.append("<w:jc w:val=\"").append(jc).append("\"/>");
        }
        Props mark = span(tp, Dom.attr(p, Ns.LOEXT, "marker-style-name"));
        String markRpr = WordRun.rPr(mark, w.styles);
        if (!markRpr.isEmpty()) {
            ppr.append("<w:rPr>").append(markRpr).append("</w:rPr>");
        }
        boolean hoisted = hoistFloatingTables(p, ppr);
        TextRuns runs = new TextRuns(this);
        if ("column".equals(before)) {
            Block last = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
            if (last != null && last.paragraph && last.sectPr == null && last.content.isEmpty()) {
                last.content = "<w:r><w:br w:type=\"column\"/></w:r>";
            } else {
                runs.prefix.append("<w:r><w:br w:type=\"column\"/></w:r>");
            }
        }
        for (String a : anchors) {
            runs.prefix.append(a);
        }
        anchors.clear();
        runs.children(p, tp);
        String after = pp.get("fo:break-after");
        String content = runs.xml();
        if ("column".equals(after)) {
            content += "<w:r><w:br w:type=\"column\"/></w:r>";
        } else if ("page".equals(after)) {
            pageBreak = true;
        }
        if (hoisted && content.isEmpty() && chain == null && !pageBreak && pp.pt("fo:line-height", 1) == 0) {
            return;
        }
        add(new Block(ppr.toString(), content, true));
    }

    private boolean hoistFloatingTables(Element p, StringBuilder ppr) throws IOException {
        List<String> tables = w.floating.hoist(p, this);
        if (tables.isEmpty()) {
            return false;
        }
        String pageBreakBefore = "<w:pageBreakBefore/>";
        int at = ppr.indexOf(pageBreakBefore);
        if (at >= 0) {
            ppr.delete(at, at + pageBreakBefore.length());
            add(new Block(pageBreakBefore + TINY, "", true));
        }
        for (String t : tables) {
            add(new Block(null, t, false));
        }
        return true;
    }

    private boolean ownIndent(String styleName) {
        if (styleName == null || w.styles.isCommon("paragraph", styleName)) {
            return false;
        }
        Element s = w.styles.style("paragraph", styleName, scope);
        Element pp = Dom.kid(s, Ns.STYLE, "paragraph-properties");
        return pp != null && (pp.hasAttributeNS(Ns.FO, "margin-left") || pp.hasAttributeNS(Ns.FO, "text-indent"));
    }

    private String commonStyle(String name) {
        if (name == null) {
            return null;
        }
        if (w.styles.isCommon("paragraph", name)) {
            return name;
        }
        Element s = w.styles.style("paragraph", name, scope);
        String parent = Dom.attr(s, Ns.STYLE, "parent-style-name");
        return parent != null && w.styles.isCommon("paragraph", parent) ? parent : null;
    }

    void finish() {
        if (!anchors.isEmpty()) {
            StringBuilder c = new StringBuilder();
            for (String a : anchors) {
                c.append(a);
            }
            anchors.clear();
            add(new Block("", c.toString(), true));
        }
    }

    String finalSectPr() {
        return w.pages.sectPr(section.master, section.columns, section.continuous, section.pageStart);
    }

    boolean isEmpty() {
        return blocks.isEmpty();
    }

    boolean endsWithTable() {
        return !blocks.isEmpty() && !blocks.get(blocks.size() - 1).paragraph;
    }

    String xml() {
        StringBuilder b = new StringBuilder();
        for (Block k : blocks) {
            if (!k.paragraph) {
                b.append(k.content);
                continue;
            }
            b.append("<w:p>");
            if (!k.ppr.isEmpty() || k.sectPr != null) {
                b.append("<w:pPr>").append(k.ppr);
                if (k.sectPr != null) {
                    b.append(k.sectPr);
                }
                b.append("</w:pPr>");
            }
            b.append(k.content).append("</w:p>");
        }
        return b.toString();
    }

    String cellXml() {
        finish();
        if (blocks.isEmpty() || endsWithTable()) {
            blocks.add(new Block("", "", true));
        }
        return xml();
    }
}
