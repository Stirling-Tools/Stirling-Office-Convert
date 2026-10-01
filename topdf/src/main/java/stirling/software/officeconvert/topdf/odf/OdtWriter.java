package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.w3c.dom.Element;

/** An OpenDocument text rewritten as the WordprocessingML package the DOCX renderer draws. */
final class OdtWriter {

    static final String NAMESPACES = "xmlns:w=\"" + Xml.W + "\" xmlns:r=\"" + Xml.R + "\" xmlns:wp=\"" + Xml.WP
            + "\" xmlns:a=\"" + Xml.A + "\" xmlns:pic=\"" + Xml.PIC + "\" xmlns:wps=\"" + Xml.WPS + "\"";

    final OdfDocument doc;

    final Styles styles;

    final PackageOut out;

    final Part main = new Part("word/document.xml");

    final WordLists lists;

    final WordNotes notes;

    final WordPages pages;

    final WordTables tables;

    final WordDrawings drawings;

    final boolean tabsRelative;

    final boolean autoHyphenation;

    final int compatibilityMode;

    final Map<String, WordLists.Chain> listIds = new HashMap<>();

    final Map<String, WordLists.Chain> lastChain = new HashMap<>();

    final Map<String, Integer> openBookmarks = new HashMap<>();

    private final Map<String, String> styleIds = new LinkedHashMap<>();

    int bookmarkId;

    String currentMaster;

    boolean externalSkipped;

    private WordLists.Chain outline;

    OdtWriter(OdfDocument doc, PackageOut out) {
        this.doc = doc;
        this.out = out;
        this.styles = new Styles(doc);
        this.lists = new WordLists(styles);
        this.notes = new WordNotes(this);
        this.pages = new WordPages(this);
        this.tables = new WordTables(this);
        this.drawings = new WordDrawings(this);
        this.tabsRelative = config("TabsRelativeToIndent", true);
        this.compatibilityMode = !config("JustifyLinesWithShrinking", false) && config("TabOverMargin", false) ? 14 : 15;
        Props dt = styles.props("paragraph", null, Styles.Scope.CONTENT, "text-properties", true);
        this.autoHyphenation = "true".equals(dt.get("fo:hyphenate"));
    }

    private boolean config(String name, boolean fallback) {
        Element settings = doc.settings();
        if (settings == null) {
            return fallback;
        }
        var items = settings.getElementsByTagNameNS(Ns.CONFIG, "config-item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if (name.equals(Dom.attr(item, Ns.CONFIG, "name"))) {
                String v = item.getTextContent().trim().toLowerCase(Locale.ROOT);
                return v.equals("true");
            }
        }
        return fallback;
    }

    List<String> write() throws IOException {
        Element body = Dom.kid(Dom.kid(doc.content(), Ns.OFFICE, "body"), Ns.OFFICE, "text");
        TextBody text = new TextBody(this, main, Styles.Scope.CONTENT, true, 0);
        if (body != null) {
            text.blocks(body, null);
        }
        text.finish();
        StringBuilder d = new StringBuilder(Xml.HEAD).append("<w:document ").append(NAMESPACES).append('>');
        String bg = pageBackground();
        if (bg != null) {
            d.append("<w:background w:color=\"").append(bg).append("\"/>");
        }
        d.append("<w:body>").append(text.xml()).append(text.finalSectPr()).append("</w:body></w:document>");
        out.xml(main.name, Xml.CT + "wordprocessingml.document.main+xml", d);
        out.xml("word/styles.xml", Xml.CT + "wordprocessingml.styles+xml", stylesXml());
        main.rels.add("styles", "styles.xml");
        out.xml("word/settings.xml", Xml.CT + "wordprocessingml.settings+xml", settingsXml(bg != null));
        main.rels.add("settings", "settings.xml");
        if (!lists.isEmpty()) {
            out.xml("word/numbering.xml", Xml.CT + "wordprocessingml.numbering+xml", lists.xml());
            main.rels.add("numbering", "numbering.xml");
        }
        if (notes.hasFootnotes()) {
            out.xml("word/footnotes.xml", Xml.CT + "wordprocessingml.footnotes+xml", notes.xml(false));
            main.rels.add("footnotes", "footnotes.xml");
            if (!notes.footPart.rels.isEmpty()) {
                out.xml("word/_rels/footnotes.xml.rels", null, notes.footPart.rels.xml());
            }
        }
        if (notes.hasEndnotes()) {
            out.xml("word/endnotes.xml", Xml.CT + "wordprocessingml.endnotes+xml", notes.xml(true));
            main.rels.add("endnotes", "endnotes.xml");
            if (!notes.endPart.rels.isEmpty()) {
                out.xml("word/_rels/endnotes.xml.rels", null, notes.endPart.rels.xml());
            }
        }
        out.xml("word/_rels/document.xml.rels", null, main.rels.xml());
        out.xml("_rels/.rels", null, Xml.HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                + "relationships\"><Relationship Id=\"rId1\" Type=\"" + Xml.REL_TYPE + "officeDocument\" Target=\""
                + "word/document.xml\"/></Relationships>");
        out.finish();
        List<String> warnings = new ArrayList<>();
        if (externalSkipped) {
            warnings.add("Skipped active content: linked files and pictures (not fetched)");
        }
        return warnings;
    }

    private String pageBackground() {
        Element m = styles.firstMaster();
        if (m == null) {
            return null;
        }
        Props p = pages.layout(Dom.attr(m, Ns.STYLE, "name"));
        return Colors.fill(p.get("fo:background-color"));
    }

    double textWidth(TextBody body) {
        Element m = styles.master(body.master());
        if (m == null) {
            m = styles.firstMaster();
        }
        Props p = pages.layout(m == null ? null : Dom.attr(m, Ns.STYLE, "name"));
        double width = p.pt("fo:page-width", 612) - p.pt("fo:margin-left", 56.7) - p.pt("fo:margin-right", 56.7);
        return Math.max(36, width);
    }

    String styleId(String name) {
        return styleIds.computeIfAbsent(name, n -> {
            String display = decode(n).replaceAll("[^A-Za-z0-9]", "");
            String id = display.isEmpty() ? "S" : display;
            String unique = id;
            int k = 1;
            while (styleIds.containsValue(unique)) {
                unique = id + (++k);
            }
            return unique;
        });
    }

    static String decode(String name) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < name.length()) {
            char c = name.charAt(i);
            if (c == '_' && i + 3 < name.length() + 1) {
                int end = name.indexOf('_', i + 1);
                if (end > i + 1 && end - i <= 7) {
                    try {
                        b.appendCodePoint(Integer.parseInt(name.substring(i + 1, end), 16));
                        i = end + 1;
                        continue;
                    } catch (IllegalArgumentException e) {
                        // not an escaped character
                    }
                }
            }
            b.append(c);
            i++;
        }
        return b.toString();
    }

    boolean outlineNumbered(int level) {
        Element o = styles.outlineStyle();
        Element l = o == null ? null : WordLists.level(o, Math.min(8, level));
        if (l == null) {
            return false;
        }
        String fmt = Dom.attr(l, Ns.STYLE, "num-format", "");
        return !fmt.isEmpty() || !Dom.attr(l, Ns.STYLE, "num-prefix", "").isEmpty()
                || !Dom.attr(l, Ns.STYLE, "num-suffix", "").isEmpty();
    }

    WordLists.Chain outlineChain() {
        if (outline == null) {
            outline = lists.chain(styles.outlineStyle(), Styles.Scope.STYLES);
        }
        return outline;
    }

    private String stylesXml() {
        Props tp = styles.props("paragraph", null, Styles.Scope.CONTENT, "text-properties", true);
        Props pp = styles.props("paragraph", null, Styles.Scope.CONTENT, "paragraph-properties", true);
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<w:styles ").append(NAMESPACES).append(">");
        b.append("<w:docDefaults><w:rPrDefault><w:rPr>").append(WordRun.rPr(tp, styles))
                .append("</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>").append(WordPara.spacing(pp))
                .append("</w:pPr></w:pPrDefault></w:docDefaults>");
        b.append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"OdfDefault\"><w:name w:val=\"Default\"/>")
                .append("</w:style>");
        b.append("<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"OdfTable\"><w:name w:val=\"Normal Table\"/>")
                .append("<w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/>")
                .append("<w:left w:w=\"0\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/>")
                .append("<w:right w:w=\"0\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>");
        for (Map.Entry<String, String> e : styleIds.entrySet()) {
            Element s = styles.common("paragraph", e.getKey());
            String display = Dom.attr(s, Ns.STYLE, "display-name", decode(e.getKey()));
            b.append("<w:style w:type=\"paragraph\" w:customStyle=\"1\" w:styleId=\"").append(e.getValue())
                    .append("\"><w:name w:val=\"").append(Xml.esc(display)).append("\"/></w:style>");
        }
        return b.append("</w:styles>").toString();
    }

    private String settingsXml(boolean background) {
        Props pp = styles.props("paragraph", null, Styles.Scope.CONTENT, "paragraph-properties", true);
        double tab = pp.pt("style:tab-stop-distance", 35.4);
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<w:settings ").append(NAMESPACES).append(">");
        if (background) {
            b.append("<w:displayBackgroundShape/>");
        }
        b.append("<w:defaultTabStop w:val=\"").append(Length.twips(tab > 1 ? tab : 35.4)).append("\"/>");
        if (autoHyphenation) {
            b.append("<w:autoHyphenation/>");
        }
        if (pages.evenAndOdd) {
            b.append("<w:evenAndOddHeaders/>");
        }
        if (pages.mirrored) {
            b.append("<w:mirrorMargins/>");
        }
        b.append(notes.settings());
        int mode = compatibilityMode;
        b.append("<w:compat><w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\"")
                .append(" w:val=\"").append(mode).append("\"/></w:compat>");
        return b.append("</w:settings>").toString();
    }
}
