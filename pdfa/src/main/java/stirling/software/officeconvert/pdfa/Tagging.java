package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;

final class Tagging {

    static final Set<String> STANDARD = Set.of("Document", "Part", "Art", "Sect", "Div", "BlockQuote", "Caption",
            "TOC", "TOCI", "Index", "NonStruct", "Private", "P", "H", "H1", "H2", "H3", "H4", "H5", "H6", "L", "LI",
            "Lbl", "LBody", "Table", "TR", "TH", "TD", "THead", "TBody", "TFoot", "Span", "Quote", "Note", "Reference",
            "BibEntry", "Code", "Link", "Annot", "Ruby", "RB", "RT", "RP", "Warichu", "WT", "WP", "Figure", "Formula",
            "Form");

    private static final Set<String> SINCE_PDF_15 = Set.of("THead", "TBody", "TFoot", "Annot", "Ruby", "RB", "RT",
            "RP", "Warichu", "WT", "WP");

    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*");

    private static final COSName ROLE_MAP = COSName.getPDFName("RoleMap");

    private static final COSName MARKED = COSName.getPDFName("Marked");

    private static final int MAX_ELEMENTS = 2_000_000;

    private Tagging() {}

    static Set<String> standard(PdfALevel level) {
        if (level.part() > 1) {
            return STANDARD;
        }
        Set<String> s = new HashSet<>(STANDARD);
        s.removeAll(SINCE_PDF_15);
        return s;
    }

    static void run(PDDocument doc, PdfALevel level, Report report) throws IOException {
        Set<String> standard = standard(level);
        COSDictionary cat = doc.getDocumentCatalog().getCOSObject();
        COSDictionary root = ContentGraph.dict(cat.getDictionaryObject(COSName.STRUCT_TREE_ROOT));
        if (root == null) {
            throw new IOException("The PDF has no tagged structure, which PDF/A level a needs; use level b or u");
        }
        COSDictionary mark = ContentGraph.dict(cat.getDictionaryObject(COSName.MARK_INFO));
        if (mark == null) {
            mark = new COSDictionary();
            cat.setItem(COSName.MARK_INFO, mark);
        }
        mark.setBoolean(MARKED, true);
        language(cat, report);
        Set<String> types = new HashSet<>();
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<COSBase> stack = new ArrayDeque<>();
        stack.push(root.getDictionaryObject(COSName.K) == null ? new COSArray() : root.getDictionaryObject(COSName.K));
        while (!stack.isEmpty() && seen.size() < MAX_ELEMENTS) {
            COSBase b = stack.pop();
            if (b instanceof COSArray a) {
                for (int i = 0; i < a.size(); i++) {
                    if (a.getObject(i) != null) {
                        stack.push(a.getObject(i));
                    }
                }
                continue;
            }
            COSDictionary e = ContentGraph.dict(b);
            if (e == null || !seen.add(e) || e.getDictionaryObject(COSName.S) == null) {
                continue;
            }
            if (e.getDictionaryObject(COSName.S) instanceof COSName s) {
                types.add(s.getName());
            }
            language(e, report);
            COSBase k = e.getDictionaryObject(COSName.K);
            if (k != null) {
                stack.push(k);
            }
        }
        COSDictionary roles = ContentGraph.dict(root.getDictionaryObject(ROLE_MAP));
        if (roles == null) {
            roles = new COSDictionary();
            root.setItem(ROLE_MAP, roles);
        }
        for (COSName k : new ArrayList<>(roles.keySet())) {
            if (standard.contains(k.getName())) {
                roles.removeItem(k);
            }
        }
        for (String type : types) {
            if (!standard.contains(type) && !resolves(roles, type, standard)) {
                roles.setName(type, "NonStruct");
                report.warn("Mapped the structure type " + type + ", which has no standard meaning in "
                        + level.label() + ", to NonStruct");
            }
        }
    }

    private static boolean resolves(COSDictionary roles, String type, Set<String> standard) {
        Set<String> visited = new HashSet<>();
        String t = type;
        while (visited.add(t)) {
            if (standard.contains(t)) {
                return true;
            }
            if (!(roles.getDictionaryObject(COSName.getPDFName(t)) instanceof COSName next)) {
                return false;
            }
            t = next.getName();
        }
        return false;
    }

    private static void language(COSDictionary d, Report report) {
        COSBase lang = d.getDictionaryObject(COSName.LANG);
        if (lang == null) {
            return;
        }
        if (!(lang instanceof COSString s) || !LANGUAGE.matcher(s.getString().strip()).matches()) {
            d.removeItem(COSName.LANG);
            report.warn("Removed a language tag that is not a valid language identifier");
        }
    }
}
