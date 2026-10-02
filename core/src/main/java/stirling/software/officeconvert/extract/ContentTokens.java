package stirling.software.officeconvert.extract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.util.Matrix;

public final class ContentTokens {

    private static final Object UNUSUAL = new Object();

    private static final int MAX_NESTING = 100;

    private final byte[] data;
    private final int length;
    private final RandomAccessReadBuffer source;
    private final PDFStreamParser parser;
    private int pos;
    private boolean closed;

    private ContentTokens(byte[] data, int length) throws IOException {
        this.data = data;
        this.length = length;
        this.source = new RandomAccessReadBuffer(data);
        this.parser = new PDFStreamParser(new Source(source));
    }

    static StreamRunner.Tokens open(PDContentStream stream) throws IOException {
        byte[] bytes;
        int count;
        try (RandomAccessRead in = stream.getContentsForStreamParsing()) {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk, 0, chunk.length)) > 0) {
                all.write(chunk, 0, n);
            }
            bytes = all.toByteArray();
            count = bytes.length;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            PDFStreamParser exact = new PDFStreamParser(stream);
            return exact::parseNextToken;
        }
        ContentTokens tokens = new ContentTokens(bytes, count);
        return tokens::next;
    }

    public static List<Object> parse(byte[] bytes) throws IOException {
        ContentTokens tokens = new ContentTokens(bytes, bytes.length);
        List<Object> out = new ArrayList<>(100);
        try {
            for (Object token; (token = tokens.next()) != null; ) {
                out.add(token);
            }
            return out;
        } finally {
            tokens.parser.close();
        }
    }

    Object next() throws IOException {
        if (closed) {
            return null;
        }
        pos = skipSpaces(pos);
        if (pos >= length) {
            closed = true;
            source.close();
            return null;
        }
        int start = pos;
        Object token;
        try {
            token = token();
        } catch (IOException | RuntimeException e) {
            token = UNUSUAL;
        }
        if (token != UNUSUAL) {
            return token;
        }
        source.seek(start);
        Object exact = parser.parseNextToken();
        if (source.isClosed()) {
            closed = true;
        } else {
            pos = (int) source.getPosition();
        }
        return exact;
    }

    private Object token() throws IOException {
        int c = data[pos] & 0xFF;
        switch (c) {
            case '(':
                return string();
            case '/':
                return name();
            case '[':
                return array(0);
            case ']':
                pos++;
                return COSNull.NULL;
            case '<':
                return peekAt(pos + 1) == '<' ? UNUSUAL : hex();
            case 'B': {
                String word = readString();
                return "BI".equals(word) ? UNUSUAL : Operator.getOperator(word);
            }
            case 'n': {
                String word = readString();
                return "null".equals(word) ? COSNull.NULL : Operator.getOperator(word);
            }
            case 't':
            case 'f': {
                String word = readString();
                if ("true".equals(word)) {
                    return COSBoolean.TRUE;
                }
                return "false".equals(word) ? COSBoolean.FALSE : Operator.getOperator(word);
            }
            case 'I':
                return UNUSUAL;
            case '+':
            case '-':
            case '.':
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
                return number(c);
            default:
                return operator();
        }
    }

    private Object number(int first) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append((char) first);
        pos++;
        if (first == '-' && peekAt(pos) == first) {
            pos++;
        }
        boolean dotNotRead = first != '.';
        while (true) {
            int p = peekAt(pos);
            char c = (char) p;
            if (!(Character.isDigit(c) || dotNotRead && c == '.' || c == '-')) {
                break;
            }
            if (c != '-') {
                sb.append(c);
            }
            pos++;
            if (dotNotRead && c == '.') {
                dotNotRead = false;
            }
        }
        String s = sb.toString();
        if ("+".equals(s)) {
            return UNUSUAL;
        }
        return COSNumber.get(s);
    }

    private Object operator() {
        StringBuilder sb = new StringBuilder(4);
        int c = peekAt(pos);
        while (c != -1 && !whitespace(c) && c != '[' && c != '<' && c != '(' && c != '/' && c != '%'
                && (c < '0' || c > '9')) {
            char ch = (char) (data[pos++] & 0xFF);
            c = peekAt(pos);
            sb.append(ch);
            if (ch == 'd' && (c == '0' || c == '1')) {
                sb.append((char) (data[pos++] & 0xFF));
                c = peekAt(pos);
            }
        }
        String word = sb.toString().trim();
        return word.isEmpty() ? UNUSUAL : Operator.getOperator(word);
    }

    private String readString() {
        int from = pos;
        while (pos < length && !endOfName(data[pos] & 0xFF)) {
            pos++;
        }
        return latin1(from, pos);
    }

    private Object name() {
        int from = ++pos;
        while (pos < length) {
            int c = data[pos] & 0xFF;
            if (endOfName(c)) {
                break;
            }
            if (c == '#') {
                return UNUSUAL;
            }
            pos++;
        }
        byte[] bytes = new byte[pos - from];
        System.arraycopy(data, from, bytes, 0, bytes.length);
        return COSName.getPDFName(bytes);
    }

    private Object string() {
        pos++;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int braces = 1;
        int c = read();
        while (braces > 0 && c != -1) {
            char ch = (char) c;
            int nextc = -2;
            if (ch == ')') {
                braces--;
                braces = checkForEndOfString(braces);
                if (braces != 0) {
                    out.write(ch);
                }
            } else if (ch == '(') {
                braces++;
                out.write(ch);
            } else if (ch == '\\') {
                if (pos >= length) {
                    return UNUSUAL;
                }
                char next = (char) read();
                switch (next) {
                    case 'n' -> out.write(10);
                    case 'r' -> out.write(13);
                    case 't' -> out.write(9);
                    case 'b' -> out.write(8);
                    case 'f' -> out.write(12);
                    case ')' -> {
                        braces = checkForEndOfString(braces);
                        out.write(braces != 0 ? next : '\\');
                    }
                    case '(', '\\' -> out.write(next);
                    case 10, 13 -> {
                        c = read();
                        while ((c == 10 || c == 13) && c != -1) {
                            c = read();
                        }
                        nextc = c;
                    }
                    case '0', '1', '2', '3', '4', '5', '6', '7' -> {
                        StringBuilder octal = new StringBuilder();
                        octal.append(next);
                        c = read();
                        char digit = (char) c;
                        if (digit >= '0' && digit <= '7') {
                            octal.append(digit);
                            c = read();
                            digit = (char) c;
                            if (digit >= '0' && digit <= '7') {
                                octal.append(digit);
                            } else {
                                nextc = c;
                            }
                        } else {
                            nextc = c;
                        }
                        out.write(Integer.parseInt(octal.toString(), 8));
                    }
                    default -> out.write(next);
                }
            } else {
                out.write(ch);
            }
            c = nextc != -2 ? nextc : read();
        }
        if (c != -1) {
            pos--;
        }
        return new COSString(out.toByteArray());
    }

    private int checkForEndOfString(int braces) {
        if (braces == 0) {
            return 0;
        }
        if (length - pos < 3) {
            return braces;
        }
        byte b0 = data[pos];
        byte b1 = data[pos + 1];
        byte b2 = data[pos + 2];
        if ((b0 == '\r' || b0 == '\n') && (b1 == '/' || b1 == '>')
                || b0 == '\r' && b1 == '\n' && (b2 == '/' || b2 == '>')) {
            return 0;
        }
        return braces;
    }

    private Object hex() throws IOException {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = read();
            if (c == -1) {
                return UNUSUAL;
            }
            if (c == '>') {
                break;
            }
            if (hexDigit(c)) {
                sb.append((char) c);
            } else if (c != ' ' && c != '\n' && c != '\t' && c != '\r' && c != 8 && c != 12) {
                return UNUSUAL;
            }
        }
        return COSString.parseHex(sb.toString());
    }

    private Object array(int depth) throws IOException {
        if (depth > MAX_NESTING) {
            return UNUSUAL;
        }
        pos++;
        COSArray array = new COSArray();
        pos = skipSpaces(pos);
        while (true) {
            int c = peekAt(pos);
            if (c <= 0 || c == ']') {
                if (c != -1) {
                    pos++;
                }
                pos = skipSpaces(pos);
                return array;
            }
            Object item = element(c, depth);
            if (item == UNUSUAL) {
                return UNUSUAL;
            }
            array.add((COSBase) item);
            pos = skipSpaces(pos);
        }
    }

    private Object element(int c, int depth) throws IOException {
        switch (c) {
            case '(':
                return string();
            case '/':
                return name();
            case '[':
                return array(depth + 1);
            case '<':
                return peekAt(pos + 1) == '<' ? UNUSUAL : hex();
            default:
                if (c >= '0' && c <= '9' || c == '-' || c == '+' || c == '.') {
                    return arrayNumber();
                }
                return UNUSUAL;
        }
    }

    private Object arrayNumber() throws IOException {
        int from = pos;
        while (pos < length) {
            int c = data[pos] & 0xFF;
            if (!(c >= '0' && c <= '9' || c == '-' || c == '+' || c == '.' || c == 'E' || c == 'e')) {
                break;
            }
            pos++;
        }
        int to = pos;
        char last = (char) (data[to - 1] & 0xFF);
        if (last == 'e' || last == 'E') {
            to--;
            pos--;
        }
        return COSNumber.get(latin1(from, to));
    }

    private int skipSpaces(int at) {
        while (at < length) {
            int c = data[at] & 0xFF;
            if (c == '%') {
                at++;
                while (at < length && data[at] != '\n' && data[at] != '\r') {
                    at++;
                }
            } else if (whitespace(c)) {
                at++;
            } else {
                break;
            }
        }
        return at;
    }

    private int read() {
        return pos < length ? data[pos++] & 0xFF : -1;
    }

    private int peekAt(int at) {
        return at < length ? data[at] & 0xFF : -1;
    }

    private String latin1(int from, int to) {
        return new String(data, from, to - from, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static boolean whitespace(int c) {
        return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32;
    }

    private static boolean endOfName(int c) {
        return switch (c) {
            case -1, 0, 9, 10, 12, 13, 32, 37, 40, 41, 47, 60, 62, 91, 93 -> true;
            default -> false;
        };
    }

    private static boolean hexDigit(int c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    private record Source(RandomAccessRead contents) implements PDContentStream {
        @Override
        public InputStream getContents() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RandomAccessRead getContentsForRandomAccess() {
            return contents;
        }

        @Override
        public RandomAccessRead getContentsForStreamParsing() {
            return contents;
        }

        @Override
        public PDResources getResources() {
            return null;
        }

        @Override
        public PDRectangle getBBox() {
            return null;
        }

        @Override
        public Matrix getMatrix() {
            return null;
        }
    }
}
