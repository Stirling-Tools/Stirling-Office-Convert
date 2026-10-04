package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

class LineBuilderSelectTest {

    private static final float[] ODD = {0f, -0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.5f, -2f};

    @Test
    void selectFindsTheSortedElement() {
        Random r = new Random(11);
        for (int n = 1; n < 300; n++) {
            for (int round = 0; round < 20; round++) {
                float[] a = new float[n];
                for (int i = 0; i < n; i++) {
                    int pick = r.nextInt(10);
                    a[i] = pick < 2 ? ODD[r.nextInt(ODD.length)] : pick < 5 ? r.nextInt(4) : r.nextFloat() * 20 - 5;
                }
                if (round == 0) {
                    Arrays.sort(a);
                } else if (round == 1) {
                    Arrays.sort(a);
                    for (int i = 0; i < n / 2; i++) {
                        float t = a[i];
                        a[i] = a[n - 1 - i];
                        a[n - 1 - i] = t;
                    }
                }
                float[] sorted = a.clone();
                Arrays.sort(sorted);
                int k = r.nextBoolean() ? n / 2 : r.nextInt(n);
                assertEquals(0, Float.compare(sorted[k], LineBuilder.select(a.clone(), k)), Arrays.toString(a) + " k=" + k);
            }
        }
    }
}
