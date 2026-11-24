// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

/**
 * Python's {@code random.Random(seed).random()}: MT19937 seeded from an integer the way CPython does it ({@code
 * init_by_array} over the 32-bit words of the seed's absolute value), and a 53-bit double from two draws. The own null
 * of an issue must draw the same sequence as the reference {@code logic.py}.
 */
public final class PyRandom {

    private static final int N = 624;
    private static final int M = 397;
    private static final int MATRIX_A = 0x9908b0df;
    private static final int UPPER_MASK = 0x80000000;
    private static final int LOWER_MASK = 0x7fffffff;

    private final int[] mt = new int[N];
    private int index = N;

    public PyRandom(long seed) {
        long magnitude = Math.abs(seed);
        int high = (int) (magnitude >>> 32);
        int[] key = high != 0 ? new int[] {(int) magnitude, high} : new int[] {(int) magnitude};
        initByArray(key);
    }

    /** A double in [0, 1) with 53 random bits, as CPython's {@code random_random}. */
    public double random() {
        int a = next() >>> 5;
        int b = next() >>> 6;
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0);
    }

    private void initGenrand(int s) {
        mt[0] = s;
        for (int i = 1; i < N; i++) {
            mt[i] = 1812433253 * (mt[i - 1] ^ (mt[i - 1] >>> 30)) + i;
        }
        index = N;
    }

    private void initByArray(int[] key) {
        initGenrand(19650218);
        int i = 1;
        int j = 0;
        for (int k = Math.max(N, key.length); k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525)) + key[j] + j;
            i++;
            j++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
            if (j >= key.length) {
                j = 0;
            }
        }
        for (int k = N - 1; k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941)) - i;
            i++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
        }
        mt[0] = UPPER_MASK;
    }

    private int next() {
        if (index >= N) {
            twist();
        }
        int y = mt[index++];
        y ^= y >>> 11;
        y ^= (y << 7) & 0x9d2c5680;
        y ^= (y << 15) & 0xefc60000;
        y ^= y >>> 18;
        return y;
    }

    private void twist() {
        for (int kk = 0; kk < N; kk++) {
            int y = (mt[kk] & UPPER_MASK) | (mt[(kk + 1) % N] & LOWER_MASK);
            mt[kk] = mt[(kk + M) % N] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
        }
        index = 0;
    }
}
