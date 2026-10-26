package stirling.software.officeconvert.optimize;

import java.math.BigDecimal;

final class Lexical {

    private Lexical() {}

    static boolean whitespace(int c) {
        return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32;
    }

    static boolean delimiter(int c) {
        return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}'
                || c == '/' || c == '%';
    }

    static boolean regular(int c) {
        return !whitespace(c) && !delimiter(c);
    }

    static String number(String s) {
        String plain = plainNumber(s);
        if (plain != null) {
            return plain;
        }
        try {
            String n = plainNumber(new BigDecimal(s.strip()).toPlainString());
            return n != null && n.indexOf('.') < 0 && n.replace("-", "").length() > 9 ? n + "." : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean isPlainNumber(byte[] b, int start, int end) {
        int i = start;
        if (i < end && (b[i] == '+' || b[i] == '-')) {
            i++;
        }
        int digits = 0;
        while (i < end && b[i] >= '0' && b[i] <= '9') {
            i++;
            digits++;
        }
        if (i < end && b[i] == '.') {
            i++;
            while (i < end && b[i] >= '0' && b[i] <= '9') {
                i++;
                digits++;
            }
        }
        return i == end && digits > 0;
    }

    static void writeNumber(byte[] b, int start, int end, Bytes out) {
        int i = start;
        boolean negative = false;
        if (b[i] == '+' || b[i] == '-') {
            negative = b[i] == '-';
            i++;
        }
        int intStart = i;
        while (i < end && b[i] != '.') {
            i++;
        }
        int intEnd = i;
        int fracStart = i < end ? i + 1 : end;
        int fracEnd = end;
        boolean hadPoint = i < end;
        while (intStart < intEnd && b[intStart] == '0') {
            intStart++;
        }
        while (fracEnd > fracStart && b[fracEnd - 1] == '0') {
            fracEnd--;
        }
        if (intStart == intEnd && fracStart == fracEnd) {
            out.write('0');
            return;
        }
        if (negative) {
            out.write('-');
        }
        out.write(b, intStart, intEnd - intStart);
        if (fracStart < fracEnd) {
            out.write('.');
            out.write(b, fracStart, fracEnd - fracStart);
        } else if (hadPoint && intEnd - intStart > 9) {
            out.write('.');
        }
    }

    static String plainNumber(String s) {
        int n = s.length();
        int i = 0;
        boolean negative = false;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            negative = s.charAt(i) == '-';
            i++;
        }
        int intStart = i;
        while (i < n && isDigit(s.charAt(i))) {
            i++;
        }
        int intEnd = i;
        int fracStart = i;
        int fracEnd = i;
        if (i < n && s.charAt(i) == '.') {
            i++;
            fracStart = i;
            while (i < n && isDigit(s.charAt(i))) {
                i++;
            }
            fracEnd = i;
        }
        if (i != n || intEnd == intStart && fracEnd == fracStart) {
            return null;
        }
        while (intStart < intEnd && s.charAt(intStart) == '0') {
            intStart++;
        }
        while (fracEnd > fracStart && s.charAt(fracEnd - 1) == '0') {
            fracEnd--;
        }
        StringBuilder b = new StringBuilder(n);
        if (fracEnd == fracStart) {
            if (intEnd == intStart) {
                return "0";
            }
            b.append(s, intStart, intEnd);
            if (intEnd - intStart > 9 && s.indexOf('.') >= 0) {
                b.append('.');
            }
        } else {
            b.append(s, intStart, intEnd).append('.').append(s, fracStart, fracEnd);
        }
        return negative ? "-" + b : b.toString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    static void string(byte[] b, Bytes out) {
        int depth = 0;
        boolean balanced = true;
        int escapes = 0;
        for (byte x : b) {
            int c = x & 0xFF;
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    balanced = false;
                } else {
                    depth--;
                }
            } else if (c == '\\' || c == '\r') {
                escapes++;
            }
        }
        balanced &= depth == 0;
        if (!balanced) {
            for (byte x : b) {
                if (x == '(' || x == ')') {
                    escapes++;
                }
            }
        }
        if (b.length + escapes <= 2 * b.length) {
            out.write('(');
            for (byte x : b) {
                int c = x & 0xFF;
                if (c == '\\') {
                    out.write('\\');
                    out.write('\\');
                } else if (c == '\r') {
                    out.write('\\');
                    out.write('r');
                } else if (!balanced && (c == '(' || c == ')')) {
                    out.write('\\');
                    out.write(c);
                } else {
                    out.write(c);
                }
            }
            out.write(')');
        } else {
            out.write('<');
            for (byte x : b) {
                out.write(HEX[(x >> 4) & 15]);
                out.write(HEX[x & 15]);
            }
            out.write('>');
        }
    }

    private static final byte[] HEX = "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
}
