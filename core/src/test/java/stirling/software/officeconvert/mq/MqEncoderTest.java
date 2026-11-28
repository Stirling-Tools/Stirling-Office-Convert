package stirling.software.officeconvert.mq;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

class MqEncoderTest {

    @Test
    void batchedSymbolsCodeExactlyLikeSingleOnes() {
        Random r = new Random(5);
        MqEncoder single = new MqEncoder(64);
        MqEncoder batched = new MqEncoder(64);
        for (int stream = 0; stream < 300; stream++) {
            int contexts = 1 + r.nextInt(19);
            MqEncoder.Contexts a = new MqEncoder.Contexts(contexts);
            MqEncoder.Contexts b = new MqEncoder.Contexts(contexts);
            for (int k = 0; k < 3; k++) {
                int context = r.nextInt(contexts);
                int index = r.nextInt(47);
                a.set(context, index);
                b.set(context, index);
            }
            single.reset();
            batched.reset();
            int n = r.nextInt(stream % 10 == 0 ? 50_000 : 3000);
            double skew = r.nextDouble();
            int[] symbols = new int[n];
            for (int i = 0; i < n; i++) {
                int c = r.nextInt(contexts);
                symbols[i] = c << 1 | (r.nextDouble() < skew * (c + 1) / contexts ? 1 : 0);
            }
            for (int i = 0; i < n;) {
                int run = Math.min(n - i, 1 + r.nextInt(500));
                for (int k = i; k < i + run; k++) {
                    single.encode(a, symbols[k] >>> 1, symbols[k] & 1);
                }
                batched.encode(b, Arrays.copyOfRange(symbols, i, i + run), run);
                single.mark();
                batched.mark();
                i += run;
            }
            byte[] x = single.flush();
            byte[] y = batched.flush();
            assertArrayEquals(x, y);
            assertArrayEquals(single.truncations(x, x.length), batched.truncations(y, y.length));
        }
    }
}
