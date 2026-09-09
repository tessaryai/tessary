// SPDX-License-Identifier: Apache-2.0
/**
 * springdoc code-first OpenAPI configuration. Lives in its own package — NOT
 * {@code web} — because ArchUnit's {@code web_is_http_plumbing_only} rule forbids Swagger/OpenAPI model
 * types in the HTTP-plumbing layer. Holds the {@code OpenAPI} info bean and the deterministic
 * {@code operationId} customizer that keeps the generated {@code /v3/api-docs} spec stable across builds
 * (the input to the checked-in-spec drift guard).
 */
@NullMarked
package ai.tessary.apidoc;

import org.jspecify.annotations.NullMarked;
