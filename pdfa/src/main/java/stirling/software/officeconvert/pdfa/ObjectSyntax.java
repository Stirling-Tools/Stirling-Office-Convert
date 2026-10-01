package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.COSWriter;

final class ObjectSyntax {

    interface Numbering {
        int number(COSBase indirect);
    }

    private static final int MAX_NESTING = 512;

    private final Numbering numbering;

    private final Set<COSBase> path = Collections.newSetFromMap(new IdentityHashMap<>());

    ObjectSyntax(Numbering numbering) {
        this.numbering = numbering;
    }

    void object(COSBase b, OutputStream out) throws IOException {
        path.clear();
        if (b instanceof COSDictionary d) {
            path.add(d);
            dictionary(d, out, null);
        } else if (b instanceof COSArray a) {
            path.add(a);
            array(a, out);
        } else {
            value(b, out);
        }
    }

    void dictionary(COSDictionary d, OutputStream out, Map<COSName, COSBase> replace) throws IOException {
        out.write('<');
        out.write('<');
        for (Map.Entry<COSName, COSBase> e : d.entrySet()) {
            COSBase v = replace != null && replace.containsKey(e.getKey()) ? replace.get(e.getKey()) : e.getValue();
            if (v == null || v instanceof COSObject o && o.getObject() == null) {
                continue;
            }
            e.getKey().writePDF(out);
            if (!startsWithDelimiter(v)) {
                out.write(' ');
            }
            value(v, out);
        }
        if (replace != null) {
            for (Map.Entry<COSName, COSBase> e : replace.entrySet()) {
                if (!d.containsKey(e.getKey()) && e.getValue() != null) {
                    e.getKey().writePDF(out);
                    if (!startsWithDelimiter(e.getValue())) {
                        out.write(' ');
                    }
                    value(e.getValue(), out);
                }
            }
        }
        out.write('>');
        out.write('>');
    }

    private void array(COSArray a, OutputStream out) throws IOException {
        out.write('[');
        for (int i = 0; i < a.size(); i++) {
            COSBase v = a.get(i);
            if (i > 0 && !startsWithDelimiter(v)) {
                out.write(' ');
            }
            value(v, out);
        }
        out.write(']');
    }

    private void value(COSBase v, OutputStream out) throws IOException {
        if (v == null || v instanceof COSNull) {
            out.write(NULL);
        } else if (v instanceof COSObject o) {
            COSBase target = o.getObject();
            if (target == null) {
                out.write(NULL);
            } else {
                reference(target, out);
            }
        } else if (v instanceof COSStream) {
            reference(v, out);
        } else if (v instanceof COSDictionary d) {
            if (inline(d) && path.add(d)) {
                dictionary(d, out, null);
                path.remove(d);
            } else {
                reference(d, out);
            }
        } else if (v instanceof COSArray a) {
            if (inline(a) && path.add(a)) {
                array(a, out);
                path.remove(a);
            } else {
                reference(a, out);
            }
        } else if (v instanceof COSString s) {
            COSWriter.writeString(s, out);
        } else if (v instanceof COSName n) {
            n.writePDF(out);
        } else if (v instanceof COSInteger i) {
            i.writePDF(out);
        } else if (v instanceof COSFloat f) {
            f.writePDF(out);
        } else if (v instanceof COSBoolean b) {
            b.writePDF(out);
        } else {
            out.write(NULL);
        }
    }

    private boolean inline(COSDictionary d) {
        return d.isDirect() && path.size() < MAX_NESTING;
    }

    private boolean inline(COSArray a) {
        if (path.size() >= MAX_NESTING) {
            return false;
        }
        if (a.isDirect()) {
            return true;
        }
        for (int i = 0; i < a.size(); i++) {
            COSBase v = a.get(i);
            if (v instanceof COSObject || v instanceof COSDictionary || v instanceof COSArray) {
                return false;
            }
        }
        return true;
    }

    private void reference(COSBase target, OutputStream out) throws IOException {
        out.write(Integer.toString(numbering.number(target)).getBytes(StandardCharsets.US_ASCII));
        out.write(REF);
    }

    private boolean startsWithDelimiter(COSBase v) {
        if (v instanceof COSName || v instanceof COSString) {
            return true;
        }
        if (v instanceof COSStream) {
            return false;
        }
        if (v instanceof COSDictionary d) {
            return inline(d) && !path.contains(d);
        }
        return v instanceof COSArray a && inline(a) && !path.contains(a);
    }

    private static final byte[] NULL = "null".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] REF = " 0 R".getBytes(StandardCharsets.US_ASCII);
}
