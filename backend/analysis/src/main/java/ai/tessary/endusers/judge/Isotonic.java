// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import java.util.ArrayList;
import java.util.List;

/** Pool adjacent violators and its linear read, as the calibration of spec section 6.1 uses them. */
public final class Isotonic {

    private Isotonic() {}

    /**
     * Weighted pool adjacent violators over points sorted by {@code x}. Each block carries its weighted mean {@code x}
     * as its centre and its weighted mean {@code y} as its value; values do not fall from block to block.
     */
    public static List<Block> pav(List<Point> points) {
        List<double[]> stack = new ArrayList<>();
        for (Point pt : points) {
            stack.add(new double[] {pt.x() * pt.weight(), pt.y() * pt.weight(), pt.weight()});
            while (stack.size() > 1 && mean(stack.get(stack.size() - 2)) > mean(stack.getLast())) {
                double[] b = stack.removeLast();
                double[] top = stack.getLast();
                top[0] += b[0];
                top[1] += b[1];
                top[2] += b[2];
            }
        }
        List<Block> blocks = new ArrayList<>(stack.size());
        for (double[] b : stack) {
            blocks.add(new Block(b[0] / b[2], b[1] / b[2]));
        }
        return List.copyOf(blocks);
    }

    private static double mean(double[] sums) {
        return sums[1] / sums[2];
    }

    /** Linear interpolation between block centres, flat beyond the first and last; no blocks returns {@code p}. */
    public static double interp(List<Block> blocks, double p) {
        if (blocks.isEmpty()) {
            return p;
        }
        if (p <= blocks.getFirst().centre()) {
            return blocks.getFirst().value();
        }
        if (p >= blocks.getLast().centre()) {
            return blocks.getLast().value();
        }
        double[] centres = new double[blocks.size()];
        for (int k = 0; k < centres.length; k++) {
            centres[k] = blocks.get(k).centre();
        }
        // Bisect, as the reference does: rounded centres can step back by an ulp, and the probes still give
        // centres[i] <= p < centres[i + 1], so the segment has a positive width.
        int i = Stats.bisectRight(centres, p) - 1;
        Block b0 = blocks.get(i);
        Block b1 = blocks.get(i + 1);
        return b0.value() + (b1.value() - b0.value()) * (p - b0.centre()) / (b1.centre() - b0.centre());
    }

    public record Point(double x, double y, double weight) {}

    public record Block(double centre, double value) {}
}
