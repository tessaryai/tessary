// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm.catalog;

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
 * Lists {@code GET https://api.anthropic.com/v1/models} — native Anthropic auth ({@code x-api-key} +
 * {@code anthropic-version}), NOT the OpenAI-compat shape {@link OpenAiCompatModelLister} covers,
 * because Anthropic's direct API never spoke that dialect (the same reason {@code ChatModelFactory}
 * builds Anthropic through {@code AnthropicChatModel} rather than {@code OpenAiChatModel}). Response
 * shape: {@code {"data":[{"id":"...", "display_name":"..."}], "has_more":bool, "last_id":"..."}} — a
 * cursor-paginated list, unlike OpenAI's single page; {@link #MAX_PAGES} bounds the walk so a vendor
 * bug in {@code has_more} cannot loop this call forever.
 *
 * <p>Single-maker like the rest of the direct providers, so returned unfiltered — every id Anthropic
 * reports is offered, with {@code vendor} fixed to {@code "Anthropic"}.
 */
public final class AnthropicModelLister implements ProviderModelLister {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1/models";

    /** Anthropic's stable API version header — the same value langchain4j-anthropic's client pins. */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** A page is up to 1,000 models; six pages is generous headroom over Anthropic's real catalog size
     *  while still bounding a buggy/malicious {@code has_more} to a fixed number of round trips. */
    private static final int MAX_PAGES = 6;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final Duration timeout;

    public AnthropicModelLister(HttpClient http, ObjectMapper mapper) {
        this(http, mapper, DEFAULT_BASE_URL, DEFAULT_TIMEOUT);
    }

    /** @param timeout per-call ceiling on each page request — production wires this to
     *  {@code ModelCatalogProperties#getFetchTimeout()}; the 2-arg constructor keeps the previous
     *  fixed default for callers (tests) that do not care. */
    public AnthropicModelLister(HttpClient http, ObjectMapper mapper, Duration timeout) {
        this(http, mapper, DEFAULT_BASE_URL, timeout);
    }

    /** Test seam only — production always resolves to {@link #DEFAULT_BASE_URL}: unlike the
     *  OpenAI-compat providers, Anthropic's direct API has exactly one real host, so there is no
     *  credential-supplied override to honor here. */
    AnthropicModelLister(HttpClient http, ObjectMapper mapper, String baseUrl) {
        this(http, mapper, baseUrl, DEFAULT_TIMEOUT);
    }

    AnthropicModelLister(HttpClient http, ObjectMapper mapper, String baseUrl, Duration timeout) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.timeout = timeout;
    }

    @Override
    public List<ProviderModel> list(ResolvedCredential credential) {
        // A local rather than two separate credential.apiKey() calls: spotbugs cannot correlate two
        // invocations of the same accessor as returning the same (or non-null) value, and flags the
        // second as a possible NPE even though this null-check already guards it.
        String apiKey = credential.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModelListingException("no api key configured for this credential", null);
        }
        List<ProviderModel> models = new ArrayList<>();
        String afterId = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode body = fetchPage(apiKey, afterId);
            for (JsonNode node : body.path("data")) {
                String id = node.path("id").asText(null);
                if (id == null || id.isBlank()) continue;
                String displayName = node.path("display_name").asText(id);
                models.add(new ProviderModel(id, displayName, "Anthropic"));
            }
            if (!body.path("has_more").asBoolean(false)) break;
            afterId = body.path("last_id").asText(null);
            if (afterId == null) break;
        }
        return List.copyOf(models);
    }

    private JsonNode fetchPage(String apiKey, String afterId) {
        String url = afterId == null ? baseUrl : baseUrl + "?after_id=" + afterId;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ModelListingException("GET " + url + " returned HTTP " + response.statusCode(), null);
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new ModelListingException("GET " + url + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelListingException("GET " + url + " interrupted", e);
        }
    }
}
