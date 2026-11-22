package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFParser;
import org.apache.fontbox.type1.Type1Font;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.DefaultResourceCache;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import stirling.software.officeconvert.fonts.FontExpansion;

public final class FontGuard {

    private static final Log LOG = LogFactory.getLog(FontGuard.class);

    private static final Map<COSStream, Boolean> BOUNDED = Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<COSDocument, Boolean> CHECKED = Collections.synchronizedMap(new WeakHashMap<>());

    private FontGuard() {}

    public static int check(PDDocument doc) throws IOException {
        COSDocument cos = doc.getDocument();
        if (CHECKED.containsKey(cos)) {
            return 0;
        }
        int removed = 0;
        Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<COSBase> todo = new ArrayDeque<>();
        todo.push(cos.getTrailer());
        while (!todo.isEmpty()) {
            COSBase next = todo.pop();
            if (next instanceof COSObject reference) {
                try {
                    next = reference.getObject();
                } catch (RuntimeException e) {
                    continue;
                }
            }
            if (next == null || !seen.add(next)) {
                continue;
            }
            if (seen.size() % 1024 == 0) {
                PdfFiles.stopIfInterrupted();
            }
            if (next instanceof COSArray array) {
                for (int i = 0; i < array.size(); i++) {
                    todo.push(array.get(i));
                }
            } else if (next instanceof COSDictionary dictionary) {
                removed += leaveOutRunaway(dictionary);
                todo.addAll(dictionary.getValues());
            }
        }
        if (removed > 0) {
            doc.setResourceCache(new DefaultResourceCache());
            Warnings.leftOut(LOG, removed + " embedded font program(s) left out: their glyphs expand too far to draw safely");
        }
        CHECKED.put(cos, Boolean.TRUE);
        return removed;
    }

    private static int leaveOutRunaway(COSDictionary descriptor) throws InterruptedIOException {
        int removed = 0;
        if (descriptor.getDictionaryObject(COSName.FONT_FILE3) instanceof COSStream program
                && !bounded(program, () -> cff(program))) {
            descriptor.removeItem(COSName.FONT_FILE3);
            removed++;
        }
        if (descriptor.getDictionaryObject(COSName.FONT_FILE) instanceof COSStream program
                && !bounded(program, () -> type1(descriptor))) {
            descriptor.removeItem(COSName.FONT_FILE);
            removed++;
        }
        return removed;
    }

    private interface Check {
        void run() throws IOException;
    }

    private static boolean bounded(COSStream program, Check check) throws InterruptedIOException {
        Boolean known = BOUNDED.get(program);
        if (known != null) {
            return known;
        }
        boolean ok;
        try {
            check.run();
            ok = true;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (Unreadable e) {
            ok = true;
        } catch (IOException | RuntimeException e) {
            ok = false;
        }
        BOUNDED.put(program, ok);
        return ok;
    }

    private static final class Unreadable extends IOException {
        Unreadable(Throwable cause) {
            super(cause);
        }
    }

    private static void cff(COSStream program) throws IOException {
        List<CFFFont> fonts;
        try {
            fonts = new CFFParser().parse(new RandomAccessReadBuffer(bytes(program)));
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new Unreadable(e);
        }
        for (CFFFont font : fonts) {
            FontExpansion.checkCff(font);
        }
    }

    private static void type1(COSDictionary descriptor) throws IOException {
        Type1Font font;
        try {
            COSDictionary dict = new COSDictionary();
            dict.setItem(COSName.TYPE, COSName.FONT);
            dict.setItem(COSName.SUBTYPE, COSName.TYPE1);
            COSBase name = descriptor.getDictionaryObject(COSName.FONT_NAME);
            dict.setItem(COSName.BASE_FONT, name instanceof COSName n ? n : COSName.getPDFName("Embedded"));
            dict.setItem(COSName.FONT_DESC, descriptor);
            font = new PDType1Font(dict).getType1Font();
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new Unreadable(e);
        }
        if (font != null) {
            FontExpansion.checkType1(font);
        }
    }

    private static byte[] bytes(COSStream program) throws IOException {
        try (InputStream in = program.createInputStream()) {
            byte[] data = in.readNBytes((int) StreamGuard.MAX_DECODED + 1);
            if (data.length > StreamGuard.MAX_DECODED) {
                throw new IOException("The font program is too large");
            }
            return data;
        }
    }
}
