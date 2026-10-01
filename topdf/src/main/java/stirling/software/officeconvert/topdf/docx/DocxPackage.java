package stirling.software.officeconvert.topdf.docx;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.io.Relationships;

final class DocxPackage {

    record Note(int id, String type, List<Block> blocks) {}

    final OfficeZip zip;

    final RenderJob job;

    final String main;

    Theme theme = Theme.DEFAULT;

    Settings settings = new Settings(null);

    Styles styles;

    Numbering numbering;

    Map<String, String> altFonts = Map.of();

    // The page colour, painted under every page when the document shows its background
    java.awt.Color pageColor;

    final List<Section> sections = new ArrayList<>();

    final Map<Integer, Note> footnotes = new HashMap<>();

    final Map<Integer, Note> endnotes = new HashMap<>();

    private final Map<String, List<Block>> headerFooterCache = new HashMap<>();

    // A chart part is read once however many drawings show it; the empty list marks one that could not be read
    final Map<String, List<Chart>> charts = new HashMap<>();

    private int missingParts;

    private FieldValues fieldValues;

    DocxPackage(OfficeZip zip, RenderJob job) throws IOException {
        this.zip = zip;
        this.job = job;
        this.main = zip.mainPart();
    }

    FieldValues fieldValues() {
        if (fieldValues == null) {
            try {
                fieldValues = FieldValues.read(zip, job == null ? null : job.options().displayName());
            } catch (java.io.InterruptedIOException e) {
                Thread.currentThread().interrupt();
                fieldValues = FieldValues.NONE;
            } catch (IOException | RuntimeException e) {
                fieldValues = FieldValues.NONE;
            }
        }
        return fieldValues;
    }

    // Content a bound refused to read: say so and mark the PDF partial
    void leftOut(String warning) {
        if (job != null) {
            job.warn(warning);
            job.losePart();
        }
    }

    void load() throws IOException {
        XEl themeXml = partXml(first(main, "theme"));
        theme = new Theme(themeXml);
        XEl settingsXml = partXml(first(main, "settings"));
        settings = new Settings(settingsXml);
        theme.mapping(settings.colorMapping);
        styles = new Styles(partXml(first(main, "styles")), theme);
        numbering = new Numbering(partXml(first(main, "numbering")), styles, theme);
        EmbeddedFonts.load(this);
        altFonts = AltFonts.read(this);
        readBody();
        notes(first(main, "footnotes"), footnotes, "w:footnote");
        notes(first(main, "endnotes"), endnotes, "w:endnote");
    }

    private void readBody() throws IOException {
        ContentReader reader = new ContentReader(this, main);
        List<Block> pending = new ArrayList<>();
        try (InputStream in = zip.open(main)) {
            XEl outside = XTree.streamBody(in, e -> {
                job.checkpoint();
                if (e.is("w:sectPr")) {
                    sections.add(new Section(SectionProps.parse(e), ContentReader.joinMarks(pending)));
                    pending.clear();
                    return;
                }
                List<Block> added = new ArrayList<>();
                reader.block(e, added, null);
                for (Block b : added) {
                    pending.add(b);
                    if (b instanceof Para p && p.section != null) {
                        sections.add(new Section(p.section, ContentReader.joinMarks(pending)));
                        pending.clear();
                    }
                }
            });
            XEl bg = outside.child("w:background");
            if (bg != null && settings.displayBackgroundShape && !"auto".equals(bg.attr("color"))) {
                pageColor = Colors.attribute(bg, theme);
            }
        }
        if (sections.isEmpty()) {
            sections.add(new Section(new SectionProps(), ContentReader.joinMarks(pending)));
        } else if (!pending.isEmpty()) {
            sections.add(new Section(copyLayout(sections.get(sections.size() - 1).props()),
                    ContentReader.joinMarks(pending)));
        }
        inheritHeaders();
        Para[] last = new Para[1];
        for (Section s : sections) {
            link(s.blocks(), last);
        }
    }

    private static void link(List<Block> blocks, Para[] last) {
        for (Block b : blocks) {
            if (b instanceof Para p) {
                p.docPrev = last[0];
                if (last[0] != null) {
                    last[0].docNext = p;
                }
                last[0] = p;
            } else if (b instanceof TableBlock t) {
                for (TableBlock.Row row : t.rows) {
                    for (TableBlock.Cell c : row.cells) {
                        link(c.blocks, last);
                    }
                }
            }
        }
    }

    private static SectionProps copyLayout(SectionProps s) {
        SectionProps c = new SectionProps();
        c.pageW = s.pageW;
        c.pageH = s.pageH;
        c.top = s.top;
        c.bottom = s.bottom;
        c.left = s.left;
        c.right = s.right;
        c.header = s.header;
        c.footer = s.footer;
        c.type = "continuous";
        return c;
    }

    private void inheritHeaders() {
        Map<String, String> headers = new HashMap<>();
        Map<String, String> footers = new HashMap<>();
        for (Section s : sections) {
            for (String t : new String[] {"default", "first", "even"}) {
                if (s.props().headers.containsKey(t)) {
                    headers.put(t, s.props().headers.get(t));
                } else if (headers.containsKey(t)) {
                    s.props().headers.put(t, headers.get(t));
                }
                if (s.props().footers.containsKey(t)) {
                    footers.put(t, s.props().footers.get(t));
                } else if (footers.containsKey(t)) {
                    s.props().footers.put(t, footers.get(t));
                }
            }
        }
    }

    private void notes(Relationship r, Map<Integer, Note> into, String element) throws IOException {
        XEl root = partXml(r);
        if (root == null) {
            return;
        }
        ContentReader reader = new ContentReader(this, r.part());
        for (XEl n : root.children(element)) {
            Integer id = Ooxml.integer(n.attr("id"));
            if (id == null) {
                continue;
            }
            job.checkpoint();
            into.put(id, new Note(id, n.attr("type", "normal"), reader.blocks(n, null)));
        }
    }

    List<Block> headerFooter(String relId) {
        if (relId == null) {
            return null;
        }
        Relationship r = relationship(main, relId);
        if (r == null || !ActiveContent.mayFollow(r)) {
            return null;
        }
        return headerFooterCache.computeIfAbsent(r.part(), p -> {
            try {
                XEl root = partXml(r);
                if (root == null) {
                    return List.of();
                }
                return new ContentReader(this, p).blocks(root, null);
            } catch (IOException e) {
                job.warn("A header or footer could not be read: " + e.getMessage());
                return List.of();
            }
        });
    }

    Relationship relationship(String sourcePart, String id) {
        if (id == null) {
            return null;
        }
        try {
            Relationships rels = zip.relationships(sourcePart);
            return rels.get(id);
        } catch (IOException e) {
            return null;
        }
    }

    Relationship first(String sourcePart, String typeName) {
        try {
            Relationship r = zip.relationships(sourcePart).first(typeName);
            return r != null && ActiveContent.mayFollow(r) ? r : null;
        } catch (IOException e) {
            return null;
        }
    }

    XEl partXml(Relationship r) throws IOException {
        if (r == null || !ActiveContent.mayFollow(r) || !zip.exists(r.part())) {
            if (r != null && missingParts++ < 3) {
                job.warn("A part the document refers to is missing or refused"
                        + (r.part() == null ? "" : ": " + r.part()));
            }
            return null;
        }
        try (InputStream in = zip.open(r)) {
            return XTree.parse(in);
        }
    }
}
