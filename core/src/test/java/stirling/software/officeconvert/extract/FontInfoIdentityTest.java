package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

class FontInfoIdentityTest {

    private record Plain(String family, boolean bold, boolean italic, boolean serif, boolean mono, boolean symbolic,
            String postScriptName, boolean substituted, boolean smallCaps, boolean icons, boolean exact) {}

    private static final String[] NAMES = {null, "", "Arial", "Times New Roman", "ArialMT"};

    @Test
    void equalityAndHashMatchTheRecordDefaults() {
        Random r = new Random(7);
        for (int i = 0; i < 20_000; i++) {
            Object[] a = values(r);
            Object[] b = r.nextInt(4) == 0 ? a.clone() : values(r);
            if (r.nextBoolean()) {
                b[r.nextInt(b.length)] = a[0];
            }
            FontInfo fa = font(a);
            FontInfo fb = font(b);
            Plain pa = plain(a);
            Plain pb = plain(b);
            assertEquals(pa.hashCode(), fa.hashCode());
            assertEquals(pa.equals(pb), fa.equals(fb));
            assertEquals(pb.equals(pa), fb.equals(fa));
        }
        assertEquals(false, FontInfo.DEFAULT.equals(null));
        assertEquals(false, FontInfo.DEFAULT.equals("Times New Roman"));
    }

    private static Object[] values(Random r) {
        Object[] v = new Object[11];
        for (int i = 0; i < v.length; i++) {
            v[i] = i == 0 || i == 6 ? NAMES[r.nextInt(NAMES.length)] : r.nextBoolean();
        }
        return v;
    }

    private static FontInfo font(Object[] v) {
        return new FontInfo((String) str(v[0]), bool(v[1]), bool(v[2]), bool(v[3]), bool(v[4]), bool(v[5]),
                (String) str(v[6]), bool(v[7]), bool(v[8]), bool(v[9]), bool(v[10]));
    }

    private static Plain plain(Object[] v) {
        return new Plain((String) str(v[0]), bool(v[1]), bool(v[2]), bool(v[3]), bool(v[4]), bool(v[5]),
                (String) str(v[6]), bool(v[7]), bool(v[8]), bool(v[9]), bool(v[10]));
    }

    private static Object str(Object o) {
        return o instanceof String ? o : null;
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b && b;
    }
}
