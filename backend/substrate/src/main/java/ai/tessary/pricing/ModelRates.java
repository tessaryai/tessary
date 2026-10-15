// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * One model's USD rates per million tokens. A null bucket means never billed for it, which differs from having no
 * {@code model_price} row (an unpriced span); that is why the fields are nullable rather than zero.
 */
public record ModelRates(
        @Nullable BigDecimal inputPerMtok,
        @Nullable BigDecimal outputPerMtok,
        @Nullable BigDecimal cacheReadPerMtok,
        @Nullable BigDecimal cacheWritePerMtok) {}
