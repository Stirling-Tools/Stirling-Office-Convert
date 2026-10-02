package stirling.software.officeconvert.topdf.rtf;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

final class Chunks implements Appendable {

    private static final int CHUNK = 1 << 16;

    private final List<String> parts = new ArrayList<>();

    private final StringBuilder tail = new StringBuilder();

    @Override
    public Chunks append(CharSequence s) {
        if (tail.length() + s.length() <= CHUNK) {
            tail.append(s);
            return this;
        }
        settle();
        if (s.length() > CHUNK) {
            parts.add(s.toString());
        } else {
            tail.append(s);
        }
        return this;
    }

    @Override
    public Chunks append(CharSequence s, int start, int end) {
        return append(s.subSequence(start, end));
    }

    @Override
    public Chunks append(char c) {
        return append(String.valueOf(c));
    }

    boolean isEmpty() {
        return parts.isEmpty() && tail.isEmpty();
    }

    boolean contains(String s) {
        settle();
        for (String p : parts) {
            if (p.contains(s)) {
                return true;
            }
        }
        return false;
    }

    boolean editFirst(String marker, UnaryOperator<String> edit) {
        settle();
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).contains(marker)) {
                parts.set(i, edit.apply(parts.get(i)));
                return true;
            }
        }
        return false;
    }

    void prepend(String s) {
        settle();
        parts.addFirst(s);
    }

    void writeTo(Writer w) throws IOException {
        for (String p : parts) {
            w.write(p);
        }
        w.write(tail.toString());
    }

    @Override
    public String toString() {
        settle();
        return String.join("", parts);
    }

    private void settle() {
        if (!tail.isEmpty()) {
            parts.add(tail.toString());
            tail.setLength(0);
        }
    }
}
