package stirling.software.officeconvert.optimize;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSName;

final class Content {

    enum Category {
        FONT(COSName.FONT), XOBJECT(COSName.XOBJECT), EXT_G_STATE(COSName.EXT_G_STATE), SHADING(COSName.SHADING),
        PATTERN(COSName.PATTERN), COLOR_SPACE(COSName.COLORSPACE), PROPERTIES(COSName.PROPERTIES);

        final COSName key;

        Category(COSName key) {
            this.key = key;
        }
    }

    record Usage(Map<Category, Set<COSName>> names, boolean complete) {

        boolean any() {
            return names.values().stream().anyMatch(s -> !s.isEmpty());
        }

        static Usage unknown() {
            return new Usage(new EnumMap<>(Category.class), false);
        }
    }

    interface Shows {
        boolean show(COSName font, byte[] text);
    }

    static final int MAX_OPERANDS = 1 << 20;

    static final int MAX_SAVED_STATES = 1 << 12;

    private Content() {}

    static byte[] minify(byte[] in) {
        try {
            boolean leading = in.length > 0 && Lexical.whitespace(in[0] & 0xFF);
            boolean trailing = in.length > 0 && Lexical.whitespace(in[in.length - 1] & 0xFF);
            ContentLexer lex = new ContentLexer(in);
            Bytes out = new Bytes(in.length);
            if (leading) {
                out.write('\n');
            }
            boolean regularEnd = false;
            boolean first = true;
            int lastStart = -1;
            int lastRawStart = -1;
            int lastRawEnd = -1;
            boolean lastOperator = false;
            while (lex.next()) {
                ContentLexer.Kind kind = lex.kind();
                if (kind == ContentLexer.Kind.OPERATOR && (lex.operator("BI") || lex.operator("ID")
                        || lex.operator("EI"))) {
                    return null;
                }
                byte[] input = lex.input();
                int s0 = lex.start();
                int s1 = lex.end();
                boolean raw = first && !leading || kind != ContentLexer.Kind.NUMBER && kind != ContentLexer.Kind.STRING;
                boolean startsRegular = raw ? Lexical.regular(input[s0] & 0xFF) : kind == ContentLexer.Kind.NUMBER;
                if (regularEnd && startsRegular) {
                    out.write(' ');
                }
                lastStart = out.size();
                if (raw) {
                    out.write(input, s0, s1 - s0);
                    regularEnd = Lexical.regular(input[s1 - 1] & 0xFF);
                } else if (kind == ContentLexer.Kind.NUMBER) {
                    Lexical.writeNumber(input, s0, s1, out);
                    regularEnd = true;
                } else {
                    Lexical.string(lex.stringBytes(), out);
                    regularEnd = false;
                }
                lastRawStart = s0;
                lastRawEnd = s1;
                lastOperator = kind == ContentLexer.Kind.OPERATOR;
                if (lastOperator) {
                    out.write('\n');
                    regularEnd = false;
                }
                first = false;
            }
            if (lex.commentAtEnd()) {
                return null;
            }
            if (lastStart < 0) {
                return leading || trailing ? new byte[] {'\n'} : in;
            }
            if (!trailing) {
                out.size(lastStart);
                out.write(in, lastRawStart, lastRawEnd - lastRawStart);
            } else if (!lastOperator) {
                out.write('\n');
            }
            return out.toByteArray();
        } catch (ContentLexer.Malformed e) {
            return null;
        }
    }

    private static byte[] raw(ContentLexer lex) {
        byte[] b = new byte[lex.end() - lex.start()];
        System.arraycopy(lex.input(), lex.start(), b, 0, b.length);
        return b;
    }

    static boolean text(byte[] in, Shows shows, Set<COSName> states) {
        List<COSName> saved = new ArrayList<>();
        List<byte[]> strings = new ArrayList<>();
        COSName font = null;
        COSName name = null;
        int operands = 0;
        try {
            ContentLexer lex = new ContentLexer(in);
            while (lex.next()) {
                ContentLexer.Kind kind = lex.kind();
                if (kind != ContentLexer.Kind.OPERATOR) {
                    if (++operands > MAX_OPERANDS) {
                        return false;
                    }
                    if (kind == ContentLexer.Kind.NAME) {
                        name = COSName.getPDFName(lex.nameBytes());
                    } else if (kind == ContentLexer.Kind.STRING) {
                        strings.add(lex.stringBytes());
                    }
                    continue;
                }
                if (lex.operator("BI") || lex.operator("ID") || lex.operator("EI")) {
                    return false;
                }
                if (lex.operator("q")) {
                    if (saved.size() >= MAX_SAVED_STATES) {
                        return false;
                    }
                    saved.add(font);
                } else if (lex.operator("Q")) {
                    if (!saved.isEmpty()) {
                        font = saved.remove(saved.size() - 1);
                    }
                } else if (lex.operator("Tf")) {
                    if (name == null) {
                        return false;
                    }
                    font = name;
                } else if (lex.operator("gs")) {
                    if (name != null) {
                        states.add(name);
                    }
                } else if (lex.operator("Tj") || lex.operator("'") || lex.operator("\"")) {
                    if (!strings.isEmpty() && (font == null
                            || !shows.show(font, strings.get(strings.size() - 1)))) {
                        return false;
                    }
                } else if (lex.operator("TJ")) {
                    for (byte[] text : strings) {
                        if (font == null || !shows.show(font, text)) {
                            return false;
                        }
                    }
                }
                strings.clear();
                name = null;
                operands = 0;
            }
            return operands == 0;
        } catch (ContentLexer.Malformed e) {
            return false;
        }
    }

    static Usage scan(byte[] in) {
        Map<Category, Set<COSName>> names = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            names.put(c, new HashSet<>());
        }
        List<COSName> operandNames = new ArrayList<>();
        List<ContentLexer.Kind> operandKinds = new ArrayList<>();
        try {
            ContentLexer lex = new ContentLexer(in);
            while (lex.next()) {
                ContentLexer.Kind kind = lex.kind();
                if (kind != ContentLexer.Kind.OPERATOR) {
                    if (operandKinds.size() >= MAX_OPERANDS) {
                        return new Usage(names, false);
                    }
                    operandKinds.add(kind);
                    operandNames.add(kind == ContentLexer.Kind.NAME ? COSName.getPDFName(lex.nameBytes()) : null);
                    continue;
                }
                if (lex.operator("BI") || lex.operator("ID") || lex.operator("EI")) {
                    return new Usage(names, false);
                }
                Category category = null;
                int operand = -1;
                if (lex.operator("Tf")) {
                    category = Category.FONT;
                    operand = operandNames.size() - 2;
                } else if (lex.operator("Do")) {
                    category = Category.XOBJECT;
                    operand = operandNames.size() - 1;
                } else if (lex.operator("gs")) {
                    category = Category.EXT_G_STATE;
                    operand = operandNames.size() - 1;
                } else if (lex.operator("sh")) {
                    category = Category.SHADING;
                    operand = operandNames.size() - 1;
                } else if (lex.operator("cs") || lex.operator("CS")) {
                    category = Category.COLOR_SPACE;
                    operand = operandNames.size() - 1;
                } else if (lex.operator("scn") || lex.operator("SCN")) {
                    category = Category.PATTERN;
                    operand = operandNames.size() - 1;
                } else if (lex.operator("BDC") || lex.operator("DP")) {
                    category = Category.PROPERTIES;
                    operand = operandNames.size() - 1;
                }
                if (category != null) {
                    for (int i = 0; i < operandNames.size(); i++) {
                        COSName n = operandNames.get(i);
                        if (n != null && (i == operand || category != Category.PATTERN
                                && category != Category.PROPERTIES)) {
                            names.get(category).add(n);
                        }
                    }
                }
                operandNames.clear();
                operandKinds.clear();
            }
            if (!operandKinds.isEmpty()) {
                return new Usage(names, false);
            }
        } catch (ContentLexer.Malformed e) {
            return new Usage(names, false);
        }
        return new Usage(names, true);
    }
}
