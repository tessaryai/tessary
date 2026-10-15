// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import java.sql.Statement;

/**
 * Requires a real per-row batch count. {@code SUCCESS_NO_INFO} means {@code reWriteBatchedInserts} merged rows into one
 * statement, which hides the {@code event_ts} guard's verdict and makes same-batch duplicates fail ("cannot affect row
 * a second time"). {@code DO NOTHING} tables check too so the rewrite cannot be half on.
 */
final class BatchCounts {
    private BatchCounts() {}

    static void requireReal(int[] applied) {
        for (int n : applied) {
            if (n == Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException(
                        "batched write returned SUCCESS_NO_INFO: reWriteBatchedInserts must stay off");
            }
        }
    }
}
