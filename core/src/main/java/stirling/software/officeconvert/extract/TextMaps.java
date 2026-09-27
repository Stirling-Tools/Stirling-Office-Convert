package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

final class TextMaps {

    private static final Log LOG = LogFactory.getLog(TextMaps.class);
    private static final int MAX_DEPTH = 6;
    private static final int MAX_CODES = 0x10000;
    private static final int BLOCK = 100;
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private TextMaps() {}

    static void seed(PDDocument doc) {
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PDPage page : doc.getPages()) {
            try {
                PDResources resources = page.getResources();
                visit(doc, resources == null ? null : resources.getCOSObject(), seen, 0);
                appearances(doc, page, seen);
            } catch (RuntimeException e) {
                LOG.debug("Page resources left as they are", e);
            }
        }
    }

    private static void appearances(PDDocument doc, PDPage page, Set<COSDictionary> seen) {
        COSArray annots = page.getCOSObject().getCOSArray(COSName.ANNOTS);
        if (annots == null) {
            return;
        }
        for (int i = 0; i < annots.size(); i++) {
            if (!(annots.getObject(i) instanceof COSDictionary annot)
                    || !(annot.getDictionaryObject(COSName.AP) instanceof COSDictionary ap)) {
                continue;
            }
            COSBase normal = ap.getDictionaryObject(COSName.N);
            if (normal instanceof COSStream stream) {
                visit(doc, stream.getCOSDictionary(COSName.RESOURCES), seen, 1);
            } else if (normal instanceof COSDictionary states) {
                for (COSName state : states.keySet()) {
                    if (states.getDictionaryObject(state) instanceof COSStream stream) {
                        visit(doc, stream.getCOSDictionary(COSName.RESOURCES), seen, 1);
                    }
                }
            }
        }
    }

    private static void visit(PDDocument doc, COSDictionary resources, Set<COSDictionary> seen, int depth) {
        if (resources == null || depth > MAX_DEPTH || !seen.add(resources)) {
            return;
        }
        COSDictionary fonts = resources.getCOSDictionary(COSName.FONT);
        if (fonts != null) {
            for (COSName name : fonts.keySet()) {
                COSBase item = fonts.getItem(name);
                COSBase base = item instanceof COSObject o ? o.getObject() : item;
                if (base instanceof COSDictionary font && seen.add(font)) {
                    seed(doc, item, font);
                }
            }
        }
        for (COSName kind : new COSName[] {COSName.XOBJECT, COSName.PATTERN}) {
            COSDictionary children = resources.getCOSDictionary(kind);
            if (children == null) {
                continue;
            }
            for (COSName name : children.keySet()) {
                if (children.getDictionaryObject(name) instanceof COSDictionary child) {
                    visit(doc, child.getCOSDictionary(COSName.RESOURCES), seen, depth + 1);
                }
            }
        }
    }

    private static void seed(PDDocument doc, COSBase ref, COSDictionary dict) {
        if (!COSName.TYPE0.equals(dict.getCOSName(COSName.SUBTYPE)) || dict.containsKey(COSName.TO_UNICODE)) {
            return;
        }
        COSName encoding = dict.getCOSName(COSName.ENCODING);
        if (!COSName.IDENTITY_H.equals(encoding) && !COSName.IDENTITY_V.equals(encoding)) {
            return;
        }
        try {
            PDFont font = PDFontFactory.createFont(dict);
            if (!(font instanceof PDType0Font t0) || !t0.isEmbedded() || !(t0.getDescendantFont() instanceof PDCIDFontType2 cid)
                    || cid.getTrueTypeFont() == null) {
                return;
            }
            Map<Integer, String> text = UnicodeRecovery.programText(t0, codes(cid));
            if (text.isEmpty()) {
                return;
            }
            COSStream map = doc.getDocument().createCOSStream();
            try (OutputStream out = map.createOutputStream()) {
                out.write(cmap(text).getBytes(StandardCharsets.US_ASCII));
            }
            dict.setItem(COSName.TO_UNICODE, map);
            if (ref instanceof COSObject indirect && doc.getResourceCache() != null) {
                doc.getResourceCache().put(indirect, (PDFont) null);
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("No text map read from the font program", e);
        }
    }

    private static int codes(PDCIDFontType2 cid) throws IOException {
        COSBase map = cid.getCOSObject().getDictionaryObject(COSName.CID_TO_GID_MAP);
        if (map instanceof COSStream stream) {
            try (java.io.InputStream in = stream.createInputStream()) {
                return Math.min(MAX_CODES, in.readAllBytes().length / 2);
            }
        }
        return Math.min(MAX_CODES, cid.getTrueTypeFont().getNumberOfGlyphs());
    }

    private static String cmap(Map<Integer, String> text) {
        StringBuilder sb = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
                .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
                .append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
                .append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
        int left = text.size();
        int inBlock = 0;
        for (Map.Entry<Integer, String> e : text.entrySet()) {
            if (inBlock == 0) {
                sb.append(Math.min(BLOCK, left)).append(" beginbfchar\n");
            }
            sb.append('<').append(HEX.toHexDigits((char) e.getKey().intValue())).append("> <");
            for (char c : e.getValue().toCharArray()) {
                sb.append(HEX.toHexDigits(c));
            }
            sb.append(">\n");
            left--;
            if (++inBlock == BLOCK || left == 0) {
                sb.append("endbfchar\n");
                inBlock = 0;
            }
        }
        return sb.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n").toString();
    }
}
