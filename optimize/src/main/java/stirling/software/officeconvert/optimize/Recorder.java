package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;

final class Recorder {

    private final Index index;

    private final boolean encrypted;

    private final Set<COSName> skip;

    private final Bytes out = new Bytes(1 << 12);

    private int[] at = new int[16];

    private int[] ref = new int[16];

    private byte[][] raw = new byte[4][];

    private int holes;

    private int strings;

    private final Syntax syntax;

    Recorder(Index index, boolean encrypted, Set<COSName> skipAtTop) {
        this.index = index;
        this.encrypted = encrypted;
        this.skip = skipAtTop;
        this.syntax = new Syntax(this::reference, skipAtTop);
        if (encrypted) {
            syntax.strings(this::string);
        }
    }

    private void reference(COSObject o, Syntax s) {
        hole(index.id(o.getKey()));
        s.hole();
    }

    private void string(byte[] bytes, Syntax s) {
        if (strings == raw.length) {
            raw = java.util.Arrays.copyOf(raw, strings * 2);
        }
        raw[strings++] = bytes;
        hole(-1);
    }

    private void hole(int target) {
        if (holes == at.length) {
            at = java.util.Arrays.copyOf(at, holes * 2);
            ref = java.util.Arrays.copyOf(ref, holes * 2);
        }
        at[holes] = out.size();
        ref[holes++] = target;
    }

    void record(int id, COSBase object, Map<COSName, COSBase> replace) throws IOException {
        out.reset();
        holes = 0;
        strings = 0;
        syntax.into(out);
        byte kind;
        if (object instanceof COSStream s) {
            syntax.top(s, replace);
            kind = Index.STREAM;
        } else if (object instanceof COSDictionary d) {
            syntax.top(d, replace);
            kind = Index.DICT;
        } else if (object instanceof COSArray a) {
            syntax.value(a);
            kind = Index.ARRAY;
        } else if (object == null || object instanceof org.apache.pdfbox.cos.COSNull) {
            kind = Index.MISSING;
        } else {
            syntax.value(object);
            kind = Index.SCALAR;
        }
        int[] pairs = null;
        if (holes > 0) {
            pairs = new int[holes * 2];
            for (int k = 0; k < holes; k++) {
                pairs[2 * k] = at[k];
                pairs[2 * k + 1] = ref[k];
            }
        }
        index.record(id, kind, out.toByteArray(), pairs, strings == 0 ? null : java.util.Arrays.copyOf(raw, strings));
        java.util.Arrays.fill(raw, 0, strings, null);
    }

    Set<COSName> skip() {
        return skip;
    }
}
