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

    static final int MAX_OPERANDS = 1 << 20;

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

    private static byte[] shortest(ContentLexer lex) {
        return switch (lex.kind()) {
            case NUMBER -> Lexical.plainNumber(lex.text()).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            case STRING -> {
                Bytes b = new Bytes(lex.end() - lex.start());
                Lexical.string(lex.stringBytes(), b);
                yield b.toByteArray();
            }
            default -> raw(lex);
        };
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
            if (lex.commentAtEnd()) {
                return new Usage(names, true);
            }
        } catch (ContentLexer.Malformed e) {
            return new Usage(names, false);
        }
        return new Usage(names, true);
    }
}
