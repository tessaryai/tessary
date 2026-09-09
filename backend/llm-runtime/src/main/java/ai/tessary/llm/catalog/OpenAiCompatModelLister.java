// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists an OpenAI-compatible {@code GET /models} endpoint — the shape OpenAI itself, and every
 * OpenAI-compat provider this platform builds against, report: {@code {"data":[{"id": "..."}, ...]}}.
 * Bearer auth, per the convention every one of these providers follows. Confirmed against a live,
 * unauthenticated read of {@code https://openrouter.ai/api/v1/models}
 * (2026-09-04) — same {@code data[].id} shape, OpenRouter's own {@code /models} just
 * carries more fields per entry.
 *
 * <p>One instance covers {@link ai.tessary.llm.ModelProvider#OPENAI}, {@code MOONSHOT},
 * {@code GEMINI}, {@code GLM}, {@code GROK} and {@code CUSTOM} — each constructed with its own fixed
 * {@code vendor} label, because unlike {@link ai.tessary.llm.catalog.SupportedMaker}-filtered
 * OpenRouter/Bedrock, each of these five IS a single maker (the credential names the maker; nothing
 * this endpoint returns needs classifying). {@code CUSTOM} is the exception the {@code vendor} label
 * makes visible rather than hides: it is passed {@code "Custom"} and returned wholly unfiltered — the
 * one deliberate exemption from the six-maker allowlist, because an arbitrary user-supplied endpoint's
 * model list is not enumerable against a maker at all.
 *
 * <p>{@code /models} carries no display-name field in the OpenAI shape (just {@code id}, {@code
 * object}, {@code created}, {@code owned_by}) — {@link ProviderModel#displayName} is set to the same
 * value as {@link ProviderModel#modelName} here; {@code ModelCatalog#mergeLive} only uses a live
 * listing's display name when a static entry does not already have a better one.
 */
public final class OpenAiCompatModelLister implements ProviderModelLister {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String vendor;
    private final Duration timeout;

    public OpenAiCompatModelLister(HttpClient http, ObjectMapper mapper, String vendor) {
        this(http, mapper, vendor, DEFAULT_TIMEOUT);
    }

    /** @param timeout per-call ceiling on the {@code GET /models} request — production wires this to
     *  {@code ModelCatalogProperties#getFetchTimeout()}; the 3-arg constructor keeps the previous fixed
     *  default for callers (tests) that do not care. */
    public OpenAiCompatModelLister(HttpClient http, ObjectMapper mapper, String vendor, Duration timeout) {
        this.http = http;
        this.mapper = mapper;
        this.vendor = vendor;
        this.timeout = timeout;
    }

    @Override
    public List<ProviderModel> list(ResolvedCredential credential) {
        String baseUrl = credential.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ModelListingException("no base URL configured for this credential", null);
        }
        URI uri = URI.create(trimTrailingSlash(baseUrl) + "/models");
        HttpRequest.Builder request =
                HttpRequest.newBuilder(uri).timeout(timeout).GET();
        String apiKey = credential.apiKey();
        if (apiKey != null) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ModelListingException("GET " + uri + " returned HTTP " + response.statusCode(), null);
            }
            return parse(response.body());
        } catch (IOException e) {
            throw new ModelListingException("GET " + uri + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelListingException("GET " + uri + " interrupted", e);
        }
    }

    private List<ProviderModel> parse(String body) {
        JsonNode data;
        try {
            data = mapper.readTree(body).path("data");
        } catch (IOException e) {
            throw new ModelListingException("unparseable /models response", e);
        }
        List<ProviderModel> models = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode node : data) {
                String id = node.path("id").asText(null);
                if (id == null || id.isBlank()) continue;
                models.add(new ProviderModel(id, id, vendor));
            }
        }
        return List.copyOf(models);
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
