package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

import org.junit.jupiter.api.Test;

class TrendsTest {

    private static List<double[]> points(DoubleUnaryOperator f, double... xs) {
        List<double[]> out = new ArrayList<>();
        for (double x : xs) {
            out.add(new double[] {x, f.applyAsDouble(x)});
        }
        return out;
    }

    @Test
    void eachKindRecoversTheCurveItWasFittedTo() {
        double[] xs = {1, 2, 3, 4, 5, 6};
        assertEquals(17, Trends.at("linear", Trends.fit("linear", 2, points(x -> 3 * x + 2, xs)), 5), 1e-9);
        assertEquals(51, Trends.at("poly", Trends.fit("poly", 2, points(x -> 2 * x * x + 1, xs)), 5), 1e-6);
        assertEquals(3 * Math.exp(0.5 * 7), Trends.at("exp", Trends.fit("exp", 2,
                points(x -> 3 * Math.exp(0.5 * x), xs)), 7), 1e-6);
        assertEquals(4 + 2 * Math.log(9), Trends.at("log", Trends.fit("log", 2,
                points(x -> 4 + 2 * Math.log(x), xs)), 9), 1e-9);
        assertEquals(2 * Math.pow(8, 1.5), Trends.at("power", Trends.fit("power", 2,
                points(x -> 2 * Math.pow(x, 1.5), xs)), 8), 1e-6);
    }

    @Test
    void curvesThatNeedPositiveValuesRefuseOthers() {
        assertNull(Trends.fit("exp", 2, points(x -> x - 3, 1, 2, 3, 4)));
        assertNull(Trends.fit("log", 2, points(x -> x, -1, 2, 3, 4)));
        assertNull(Trends.fit("poly", 4, points(x -> x, 1, 2, 3)));
    }
}
