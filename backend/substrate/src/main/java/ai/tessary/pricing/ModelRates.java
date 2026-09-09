// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * One model's USD rates, per million tokens, one field per billable bucket.
 *
 * <p>A null bucket means the model is <b>never billed</b> for it and contributes zero to a cost. That is
 * not the same as holding no rate for the model at all, which is the absence of a {@code model_price} row
 * and yields an unpriced span. Keeping the two distinguishable is the whole reason every field here is
 * nullable rather than defaulted to zero.
 */
public record ModelRates(
        @Nullable BigDecimal inputPerMtok,
        @Nullable BigDecimal outputPerMtok,
        @Nullable BigDecimal cacheReadPerMtok,
        @Nullable BigDecimal cacheWritePerMtok) {}
