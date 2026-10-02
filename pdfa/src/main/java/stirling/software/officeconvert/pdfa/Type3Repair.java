package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType3Font;

import stirling.software.officeconvert.extract.PdfFiles;

final class Type3Repair {

    private Type3Repair() {}

    static void run(PDDocument doc, PDType3Font font, Set<Integer> codes, PdfALevel level, Report report)
            throws IOException {
        COSDictionary procs = font.getCharProcs();
        if (procs == null) {
            procs = new COSDictionary();
            font.getCOSObject().setItem(COSName.CHAR_PROCS, procs);
        }
        for (int code : codes) {
            PdfFiles.stopIfInterrupted();
            float width = font.getWidth(code);
            COSName name = COSName.getPDFName(font.getEncoding().getName(code));
            COSStream glyph = procs.getDictionaryObject(name) instanceof COSStream s ? s : null;
            if (glyph == null) {
                name = COSName.getPDFName("Missing" + code);
                glyph = doc.getDocument().createCOSStream();
                ContentTokens.write(glyph, List.of(Limits.number(new COSFloat(width), level),
                        COSInteger.ZERO, Operator.getOperator("d0")));
                procs.setItem(name, glyph);
                difference(font, code, name);
                report.warn("Gave a missing Type3 glyph an empty appearance with its original advance");
                continue;
            }
            List<Object> tokens = new ArrayList<>(ContentTokens.parse(List.of(glyph)));
            int at = -1;
            for (int i = 0; i < tokens.size(); i++) {
                if (tokens.get(i) instanceof Operator op && (op.getName().equals("d0") || op.getName().equals("d1"))) {
                    int operands = op.getName().equals("d0") ? 2 : 6;
                    if (i >= operands) {
                        at = i - operands;
                    }
                    break;
                }
            }
            if (at < 0) {
                tokens.addAll(0, List.of(Limits.number(new COSFloat(width), level), COSInteger.ZERO,
                        Operator.getOperator("d0")));
            } else {
                tokens.set(at, Limits.number(new COSFloat(width), level));
                tokens.set(at + 1, COSInteger.ZERO);
            }
            COSStream repaired = doc.getDocument().createCOSStream();
            repaired.addAll(glyph);
            String base = "Repaired" + code;
            int suffix = 0;
            name = COSName.getPDFName(base);
            while (procs.containsKey(name)) {
                name = COSName.getPDFName(base + "_" + ++suffix);
            }
            ContentTokens.write(repaired, tokens);
            procs.setItem(name, repaired);
            difference(font, code, name);
        }
    }

    private static void difference(PDType3Font font, int code, COSName name) {
        COSDictionary dict = font.getCOSObject();
        COSDictionary encoding = ContentGraph.dict(dict.getDictionaryObject(COSName.ENCODING));
        if (encoding == null) {
            encoding = new COSDictionary();
            if (dict.getDictionaryObject(COSName.ENCODING) instanceof COSName base) {
                encoding.setItem(COSName.BASE_ENCODING, base);
            }
            dict.setItem(COSName.ENCODING, encoding);
        }
        COSArray differences = ContentGraph.array(encoding.getDictionaryObject(COSName.DIFFERENCES));
        if (differences == null) {
            differences = new COSArray();
            encoding.setItem(COSName.DIFFERENCES, differences);
        }
        differences.add(COSInteger.get(code));
        differences.add(name);
    }
}
