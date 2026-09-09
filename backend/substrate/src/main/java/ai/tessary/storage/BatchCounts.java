// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import java.sql.Statement;

/**
 * The one check every batched substrate write shares (#984 M2): the driver must report a real per-row
 * count. {@code SUCCESS_NO_INFO} means a batch rewrite ({@code reWriteBatchedInserts}) merged the rows
 * into one multi-row statement, which would hide the {@code event_ts} guard's per-row verdict on the
 * two {@code ON CONFLICT DO UPDATE} tables and make same-batch duplicates a statement Postgres refuses
 * ("cannot affect row a second time"). The {@code DO NOTHING} tables carry the check too so the rewrite
 * cannot be half on.
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
