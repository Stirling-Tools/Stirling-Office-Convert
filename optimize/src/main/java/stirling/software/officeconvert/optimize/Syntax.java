package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSString;

final class Syntax {

    interface References {
        void reference(COSObject ref, Syntax syntax) throws IOException;
    }

    interface Strings {
        void string(byte[] raw, Syntax syntax);
    }

    private static final Map<COSName, byte[]> NAMES = new java.util.concurrent.ConcurrentHashMap<>();

    private final References references;

    private Strings strings;

    private Bytes out;

    private boolean regularEnd;

    private final java.util.Set<COSName> skip;

    Syntax(References references) {
        this(references, java.util.Set.of());
    }

    Syntax(References references, java.util.Set<COSName> skipAtTop) {
        this.references = references;
        this.skip = skipAtTop;
    }

    Syntax into(Bytes target) {
        out = target;
        regularEnd = target.size() > 0 && Lexical.regular(target.at(target.size() - 1) & 0xFF);
        return this;
    }

    Bytes out() {
        return out;
    }

    Syntax strings(Strings hook) {
        strings = hook;
        return this;
    }

    void hole() {
        regularEnd = false;
    }

    void top(COSBase b, Map<COSName, COSBase> replace) throws IOException {
        if (b instanceof COSDictionary d) {
            dictionary(d, replace, skip);
        } else {
            value(b);
        }
    }

    void value(COSBase v) throws IOException {
        if (v instanceof COSObject o) {
            if (o.getKey() != null) {
                references.reference(o, this);
            } else {
                value(o.getObject());
            }
        } else if (v instanceof COSDictionary d) {
            dictionary(d, null);
        } else if (v instanceof COSArray a) {
            array(a);
        } else if (v instanceof COSName n) {
            name(n);
        } else if (v instanceof COSInteger i) {
            word(Long.toString(i.longValue()));
        } else if (v instanceof COSFloat f) {
            word(number(f));
        } else if (v instanceof COSString s) {
            if (strings != null) {
                strings.string(s.getBytes(), this);
            } else {
                Lexical.string(s.getBytes(), out);
            }
            regularEnd = false;
        } else if (v instanceof COSBoolean b) {
            word(b.getValue() ? "true" : "false");
        } else {
            word("null");
        }
    }

    void dictionary(COSDictionary d, Map<COSName, COSBase> replace) throws IOException {
        dictionary(d, replace, java.util.Set.of());
    }

    private void dictionary(COSDictionary d, Map<COSName, COSBase> replace, java.util.Set<COSName> without)
            throws IOException {
        open("<<");
        for (Map.Entry<COSName, COSBase> e : d.entrySet()) {
            if (without.contains(e.getKey())) {
                continue;
            }
            COSBase v = e.getValue();
            if (replace != null && replace.containsKey(e.getKey())) {
                v = replace.get(e.getKey());
            }
            if (absent(v)) {
                continue;
            }
            name(e.getKey());
            value(v);
        }
        if (replace != null) {
            for (Map.Entry<COSName, COSBase> e : replace.entrySet()) {
                if (!d.containsKey(e.getKey()) && !absent(e.getValue())) {
                    name(e.getKey());
                    value(e.getValue());
                }
            }
        }
        close(">>");
    }

    void array(COSArray a) throws IOException {
        open("[");
        for (int i = 0; i < a.size(); i++) {
            value(a.get(i));
        }
        close("]");
    }

    void reference(int number) {
        word(Integer.toString(number));
        word("0");
        word("R");
    }

    void word(String w) {
        if (regularEnd) {
            out.write(' ');
        }
        out.ascii(w);
        regularEnd = true;
    }

    void open(String delimiter) {
        out.ascii(delimiter);
        regularEnd = false;
    }

    void close(String delimiter) {
        out.ascii(delimiter);
        regularEnd = false;
    }

    void name(COSName n) {
        byte[] b;
        b = NAMES.get(n);
        if (b == null) {
            Bytes tmp = new Bytes(n.getName().length() + 4);
            try {
                n.writePDF(tmp);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            b = tmp.toByteArray();
            if (NAMES.size() < 100_000) {
                NAMES.put(n, b);
            }
        }
        out.write(b);
        regularEnd = true;
    }

    static boolean absent(COSBase v) {
        return v == null || v instanceof COSNull;
    }

    static String number(COSFloat f) {
        Bytes tmp = new Bytes(24);
        try {
            f.writePDF(tmp);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String s = new String(tmp.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
        String n = Lexical.number(s);
        if (n == null) {
            n = Lexical.number(Float.toString(f.floatValue()));
        }
        if (n == null) {
            return "0";
        }
        return n.indexOf('.') < 0 && n.replace("-", "").length() > 9 ? n + "." : n;
    }

}
