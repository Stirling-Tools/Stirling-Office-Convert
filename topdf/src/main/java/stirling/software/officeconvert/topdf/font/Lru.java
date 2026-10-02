package stirling.software.officeconvert.topdf.font;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

// A small thread-safe cache that forgets the least recently used entry past its size
final class Lru<K, V> {

    private final Map<K, V> map;

    Lru(int max) {
        this.map = new LinkedHashMap<>(64, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        };
    }

    synchronized V get(K key) {
        return map.get(key);
    }

    V putIfAbsent(K key, V value) {
        synchronized (this) {
            V old = map.get(key);
            if (old != null) {
                return old;
            }
            map.put(key, value);
            return null;
        }
    }

    V computeIfAbsent(K key, Function<K, V> make) {
        V known = get(key);
        if (known != null) {
            return known;
        }
        V made = make.apply(key);
        V raced = putIfAbsent(key, made);
        return raced == null ? made : raced;
    }

    synchronized int size() {
        return map.size();
    }
}
