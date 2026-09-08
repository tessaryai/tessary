// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm.catalog;

/**
 * A {@link ProviderModelLister} could not reach or parse its provider's models endpoint. Unchecked,
 * and deliberately not a typed {@code EvalsException}: nothing between {@code ModelCatalogFetchService}
 * and this exception's throw site should ever surface it to a caller as an error — mandatory property
 * (iii) is that a vendor outage degrades to a stale-cache or empty-for-that-provider read, never a
 * failed request. See {@code ModelCatalogFetchService#fetch} for where it is caught.
 */
public final class ModelListingException extends RuntimeException {

    public ModelListingException(String message, Throwable cause) {
        super(message, cause);
    }
}
