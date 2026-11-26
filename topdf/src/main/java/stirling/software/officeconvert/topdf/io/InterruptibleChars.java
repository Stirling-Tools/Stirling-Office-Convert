package stirling.software.officeconvert.topdf.io;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;

/** Text for a regex that stops when the thread is interrupted, since {@code java.util.regex} never polls. */
public final class InterruptibleChars implements CharSequence {

    private static final int POLL_EVERY = 4096;

    private final CharSequence text;

    private int calls;

    private InterruptibleChars(CharSequence text) {
        this.text = text;
    }

    public static CharSequence of(CharSequence text) {
        return text instanceof InterruptibleChars || text.length() < POLL_EVERY ? text : new InterruptibleChars(text);
    }

    @Override
    public char charAt(int index) {
        if (++calls >= POLL_EVERY) {
            calls = 0;
            if (Thread.currentThread().isInterrupted()) {
                throw new UncheckedIOException(new InterruptedIOException("Conversion interrupted"));
            }
        }
        return text.charAt(index);
    }

    @Override
    public int length() {
        return text.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return of(text.subSequence(start, end));
    }

    @Override
    public String toString() {
        return text.toString();
    }
}
