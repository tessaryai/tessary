// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

/**
 * A lister could not reach or parse its provider. Not a {@code TessaryException}: a vendor outage must degrade to a
 * stale or empty listing, never a failed request; {@code ModelCatalogFetchService#fetch} catches it.
 */
public final class ModelListingException extends RuntimeException {

    public ModelListingException(String message, Throwable cause) {
        super(message, cause);
    }
}
