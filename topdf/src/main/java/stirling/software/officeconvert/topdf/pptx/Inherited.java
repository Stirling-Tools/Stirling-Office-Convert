package stirling.software.officeconvert.topdf.pptx;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

record Inherited<T>(T value, RuntimeException failure) {

    T get() {
        if (failure != null) {
            throw failure;
        }
        return value;
    }

    static <P, T> Inherited<T> resolve(List<P> levels, RuntimeException end, BiConsumer<P, Consumer<T>> fetcher) {
        Found<T> found = new Found<>();
        try {
            for (P props : levels) {
                fetcher.accept(props, found);
                if (found.set()) {
                    return new Inherited<>(found.value(), null);
                }
            }
        } catch (RuntimeException e) {
            return new Inherited<>(null, e);
        }
        return new Inherited<>(null, end);
    }

    static <T> Inherited<T> of(Supplier<T> lookup) {
        try {
            return new Inherited<>(lookup.get(), null);
        } catch (RuntimeException e) {
            return new Inherited<>(null, e);
        }
    }

    static final class Found<T> implements Consumer<T> {

        private boolean set;

        private T value;

        @Override
        public void accept(T v) {
            value = v;
            set = true;
        }

        boolean set() {
            return set;
        }

        T value() {
            return value;
        }
    }
}
