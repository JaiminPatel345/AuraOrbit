package dev.jaimin.auraorbit;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Regression tests for {@link BackgroundStore#computeSampleSize}.
 *
 * The corrected algorithm is:
 *   int s = 1;
 *   while (maxSrc / s > maxDimension) { s *= 2; }
 *   return s;
 *
 * Expected values for maxDimension=2048 (computed from the corrected algorithm):
 *   1024 → 1   (1024/1=1024 ≤ 2048 — no scaling)
 *   2048 → 1   (2048/1=2048 ≤ 2048 — exactly fits)
 *   2049 → 2   (2049/1=2049 > 2048 → s=2; 2049/2=1024 ≤ 2048)
 *   3000 → 2   (3000/1=3000 > 2048 → s=2; 3000/2=1500 ≤ 2048)
 *   4096 → 2   (4096/1=4096 > 2048 → s=2; 4096/2=2048 ≤ 2048)
 *   4097 → 2   (4097/1=4097 > 2048 → s=2; 4097/2=2048 ≤ 2048 — integer div)
 *   8192 → 4   (8192/2=4096 > 2048 → s=4; 8192/4=2048 ≤ 2048)
 */
public class BackgroundStoreTest {

    private static final int MAX = 2048;

    /** Helper: calls computeSampleSize with equal width and height (square image). */
    private static int css(int srcMax) {
        return BackgroundStore.computeSampleSize(srcMax, srcMax, MAX);
    }

    // ── Named regression cases ────────────────────────────────────────────────

    @Test
    public void src1024_returnsOne() {
        assertEquals(1, css(1024));
    }

    @Test
    public void src2048_returnsOne_exactlyFits() {
        assertEquals(1, css(2048));
    }

    @Test
    public void src2049_returnsTwo_onePixelOver() {
        assertEquals(2, css(2049));
    }

    @Test
    public void src3000_returnsTwo() {
        assertEquals(2, css(3000));
    }

    @Test
    public void src4096_returnsTwo() {
        assertEquals(2, css(4096));
    }

    @Test
    public void src4097_returnsTwo_integerDivision() {
        // floor(4097/2) = 2048, which is ≤ 2048, so sample size stays at 2.
        assertEquals(2, css(4097));
    }

    @Test
    public void src8192_returnsFour() {
        assertEquals(4, css(8192));
    }

    // ── Rectangle images: width != height ────────────────────────────────────

    @Test
    public void rectangleImageUsesMaxDimension() {
        // 4096×100 — max dim is 4096, should return 2 same as square 4096.
        assertEquals(2, BackgroundStore.computeSampleSize(4096, 100, MAX));
        // 100×4096 — symmetric.
        assertEquals(2, BackgroundStore.computeSampleSize(100, 4096, MAX));
    }

    // ── Invariant sweep ───────────────────────────────────────────────────────

    /**
     * For every src in [1..10000] step 97:
     * 1. decoded dimension (maxSrc / result) must be ≤ MAX.
     * 2. result must be a power of two.
     * 3. result must be minimal: if result > 1, the previous power-of-2 (result/2)
     *    would have decoded to > MAX.
     */
    @Test
    public void invariant_decodedDimensionWithinBound_sweep() {
        for (int src = 1; src <= 10000; src += 97) {
            int result = css(src);

            // 1. Decoded max dim ≤ 2048
            assertTrue("src=" + src + ": decoded=" + (src / result) + " exceeds MAX",
                    src / result <= MAX);

            // 2. Result is a power of two (result & (result-1) == 0, and result ≥ 1)
            assertTrue("src=" + src + ": result=" + result + " is not a power of two",
                    result >= 1 && (result & (result - 1)) == 0);

            // 3. Minimality: halving the sample size would exceed the bound
            if (result > 1) {
                assertTrue("src=" + src + ": result=" + result + " is not minimal "
                                + "(result/2=" + (result / 2) + " also fits)",
                        src / (result / 2) > MAX);
            }
        }
    }
}
