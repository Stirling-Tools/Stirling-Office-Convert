package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import stirling.software.officeconvert.extract.PdfFiles;

final class StructureCheck {

    private final TreeSet<Integer> textPages = new TreeSet<>();

    private final TreeSet<Integer> imagePages = new TreeSet<>();

    private final TreeSet<Integer> orphanPages = new TreeSet<>();

    private int text;

    private int images;

    private int orphans;

    private int artifacts;

    private StructureCheck() {}

    static void run(PDDocument doc, PdfALevel level, Report report) throws IOException {
        COSDictionary cat = doc.getDocumentCatalog().getCOSObject();
        COSDictionary root = ContentGraph.dict(cat.getDictionaryObject(COSName.STRUCT_TREE_ROOT));
        if (root == null) {
            throw new IOException("The PDF has no tagged structure, which PDF/A level a needs; use level b or u");
        }
        StructTree tree = StructTree.read(root);
        StructureCheck c = new StructureCheck();
        Map<COSDictionary, Integer> pageNumbers = new IdentityHashMap<>();
        Map<COSDictionary, Set<Integer>> referenced = tree.mcidsByOwner();
        int index = 0;
        for (PDPage page : doc.getPages()) {
            PdfFiles.stopIfInterrupted();
            pageNumbers.put(page.getCOSObject(), ++index);
            c.page(page, index, referenced.getOrDefault(page.getCOSObject(), Set.of()), referenced);
        }
        List<String> problems = new ArrayList<>();
        if (c.text > 0) {
            problems.add(count(c.text, "piece") + " of text outside the structure tree (" + pages(c.textPages) + ")");
        }
        if (c.images > 0) {
            problems.add(count(c.images, "image") + " outside the structure tree (" + pages(c.imagePages) + ")");
        }
        if (c.orphans > 0) {
            problems.add(count(c.orphans, "marked content item") + " that no structure element refers to ("
                    + pages(c.orphanPages) + ")");
        }
        if (!tree.figuresWithoutAlt.isEmpty()) {
            TreeSet<Integer> where = new TreeSet<>();
            for (COSDictionary f : tree.figuresWithoutAlt) {
                Integer p = pageNumbers.get(ContentGraph.dict(f.getDictionaryObject(COSName.PG)));
                if (p != null) {
                    where.add(p);
                }
            }
            problems.add(count(tree.figuresWithoutAlt.size(), "figure") + " without alternative text"
                    + (where.isEmpty() ? "" : " (" + pages(where) + ")"));
        }
        if (!(cat.getDictionaryObject(COSName.LANG) instanceof COSString lang) || lang.getString().isBlank()) {
            String guess = tree.rootLanguage();
            if (guess == null) {
                problems.add("no document language");
            } else {
                cat.setString(COSName.LANG, guess);
                report.warn("Set the document language to " + guess + " from its structure");
            }
        }
        if (!problems.isEmpty()) {
            throw new IOException(level.label() + " needs every piece of content tagged or marked as an artifact, "
                    + "figures with alternative text and a document language, but the PDF has "
                    + String.join("; ", problems) + "; use level b or u");
        }
        if (c.artifacts > 0) {
            report.warn("Marked " + count(c.artifacts, "untagged drawing") + " as artifacts");
        }
        if (!tree.parentTreeMatches()) {
            tree.rebuildParentTree();
            report.warn("Rebuilt the structure parent tree from the structure elements");
        }
    }

    private void page(PDPage page, int number, Set<Integer> referenced, Map<COSDictionary, Set<Integer>> byOwner)
            throws IOException {
        COSDictionary p = page.getCOSObject();
        List<COSStream> streams = ContentGraph.contents(p);
        if (streams.isEmpty()) {
            return;
        }
        COSDictionary res = page.getResources() == null ? null : page.getResources().getCOSObject();
        TaggedContent t = new TaggedContent(referenced, byOwner);
        List<Object> rewritten = t.scan(ContentTokens.parse(streams), res);
        if (rewritten != null) {
            ContentTokens.replacePage(p, rewritten);
        }
        text += t.text;
        images += t.images;
        artifacts += t.artifacts;
        if (t.text > 0) {
            textPages.add(number);
        }
        if (t.images > 0) {
            imagePages.add(number);
        }
        orphans += t.orphans;
        if (t.orphans > 0) {
            orphanPages.add(number);
        }
    }

    private static String count(int n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }

    private static String pages(TreeSet<Integer> pages) {
        StringBuilder b = new StringBuilder(pages.size() == 1 ? "page " : "pages ");
        int shown = 0;
        for (int p : pages) {
            if (shown++ == 10) {
                b.append(" and ").append(pages.size() - 10).append(" more");
                break;
            }
            if (shown > 1) {
                b.append(", ");
            }
            b.append(p);
        }
        return b.toString();
    }
}
