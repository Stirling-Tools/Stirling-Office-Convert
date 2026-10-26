package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.util.IdentityHashMap;
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
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;

final class Syntax {

    interface References {
        void reference(int node, Syntax syntax) throws IOException;
    }

    private static final Map<COSName, byte[]> NAMES = new IdentityHashMap<>();

    private final Graph graph;

    private final References references;

    private Bytes out;

    private java.util.function.UnaryOperator<byte[]> strings;

    private boolean copyUnknown;

    private boolean regularEnd;

    Syntax(Graph graph, References references) {
        this.graph = graph;
        this.references = references;
    }

    Syntax into(Bytes target) {
        out = target;
        regularEnd = target.size() > 0 && Lexical.regular(target.at(target.size() - 1) & 0xFF);
        return this;
    }

    Bytes out() {
        return out;
    }

    Syntax strings(java.util.function.UnaryOperator<byte[]> encrypt) {
        strings = encrypt;
        return this;
    }

    Syntax copyUnknown() {
        copyUnknown = true;
        return this;
    }

    void node(int id) throws IOException {
        COSBase b = graph.node(id);
        if (b instanceof COSArray a) {
            array(a);
        } else {
            dictionary((COSDictionary) b, null);
        }
    }

    void value(COSBase v) throws IOException {
        if (v instanceof COSObject o) {
            COSBase t = o.getObject();
            int id = graph.id(t);
            if (id >= 0) {
                references.reference(id, this);
            } else if (copyUnknown && t instanceof COSDictionary d && !(t instanceof COSStream)) {
                dictionary(d, null);
            } else if (copyUnknown && t instanceof COSArray a) {
                array(a);
            } else if (t instanceof COSDictionary || t instanceof COSArray) {
                word("null");
            } else {
                value(t);
            }
            return;
        }
        int id = v instanceof COSDictionary || v instanceof COSArray ? graph.id(v) : -1;
        if (id >= 0) {
            references.reference(id, this);
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
            Lexical.string(strings == null ? s.getBytes() : strings.apply(s.getBytes()), out);
            regularEnd = false;
        } else if (v instanceof COSBoolean b) {
            word(b.getValue() ? "true" : "false");
        } else {
            word("null");
        }
    }

    void dictionary(COSDictionary d, Map<COSName, COSBase> replace) throws IOException {
        open("<<");
        for (Map.Entry<COSName, COSBase> e : d.entrySet()) {
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
        synchronized (NAMES) {
            b = NAMES.get(n);
        }
        if (b == null) {
            Bytes tmp = new Bytes(n.getName().length() + 4);
            try {
                n.writePDF(tmp);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            b = tmp.toByteArray();
            synchronized (NAMES) {
                if (NAMES.size() < 100_000) {
                    NAMES.put(n, b);
                }
            }
        }
        out.write(b);
        regularEnd = true;
    }

    static boolean absent(COSBase v) {
        return v == null || v instanceof COSNull || v instanceof COSObject o && (o.getObject() == null
                || o.getObject() instanceof COSNull);
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
        return n != null ? n : Lexical.number(Float.toString(f.floatValue())) != null
                ? Lexical.number(Float.toString(f.floatValue())) : "0";
    }

    static boolean isStream(COSBase b) {
        return b instanceof COSStream;
    }
}
