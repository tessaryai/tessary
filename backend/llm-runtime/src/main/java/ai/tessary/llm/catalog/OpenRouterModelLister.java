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
import java.util.Optional;

/**
 * Lists {@code GET https://openrouter.ai/api/v1/models} — confirmed live and unauthenticated during
 * #939 TASK 2's implementation (2026-09-04): the endpoint needs no key to enumerate models (only to
 * run one), and every id observed was namespaced {@code <maker>/<slug>} (e.g. {@code openai/gpt-5.5},
 * {@code anthropic/claude-...}, {@code moonshotai/kimi-...}, {@code z-ai/glm-...}, {@code
 * x-ai/grok-...}), plus a small set of {@code ~<maker>/<slug>-latest} pointer aliases. {@code
 * name} carries a human-readable label (e.g. {@code "Meta: Muse Spark 1.3"}).
 *
 * <p>Unlike the single-maker listers, OpenRouter hosts dozens of makers — Meta, Mistral, DeepSeek,
 * Cohere and more, none of them one of D6's six — so every entry is run through
 * {@link SupportedMaker#fromOpenRouterPrefix} and dropped on a miss. This is the intersection step
 * the corrective brief specifies for OpenRouter: "its reported catalog INTERSECT the six makers."
 */
public final class OpenRouterModelLister implements ProviderModelLister {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final String DEFAULT_URL = "https://openrouter.ai/api/v1/models";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String url;
    private final Duration timeout;

    public OpenRouterModelLister(HttpClient http, ObjectMapper mapper) {
        this(http, mapper, DEFAULT_URL, DEFAULT_TIMEOUT);
    }

    /** @param timeout per-call ceiling on the {@code GET /models} request — production wires this to
     *  {@code ModelCatalogProperties#getFetchTimeout()}; the 2-arg constructor keeps the previous
     *  fixed default for callers (tests) that do not care. */
    public OpenRouterModelLister(HttpClient http, ObjectMapper mapper, Duration timeout) {
        this(http, mapper, DEFAULT_URL, timeout);
    }

    /** Test seam only — production always resolves to {@link #DEFAULT_URL}: OpenRouter is a single
     *  fixed host, not a credential-supplied one like the OpenAI-compat providers. */
    OpenRouterModelLister(HttpClient http, ObjectMapper mapper, String url) {
        this(http, mapper, url, DEFAULT_TIMEOUT);
    }

    OpenRouterModelLister(HttpClient http, ObjectMapper mapper, String url, Duration timeout) {
        this.http = http;
        this.mapper = mapper;
        this.url = url;
        this.timeout = timeout;
    }

    @Override
    public List<ProviderModel> list(ResolvedCredential credential) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
        // OpenRouter's /models needs no auth to enumerate, but a caller with a key sends it anyway —
        // some gateways vary the listing by account (e.g. org-specific model access), and there is no
        // reason to ask unauthenticated when a key is sitting right there. A local rather than
        // repeated credential.apiKey() calls: spotbugs cannot correlate two invocations of the same
        // accessor as returning the same (or non-null) value, and flags the second as a possible NPE
        // even though this null-check already guards it.
        String apiKey = credential.apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ModelListingException("GET " + url + " returned HTTP " + response.statusCode(), null);
            }
            return parse(response.body());
        } catch (IOException e) {
            throw new ModelListingException("GET " + url + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelListingException("GET " + url + " interrupted", e);
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
                Optional<SupportedMaker> maker = SupportedMaker.fromOpenRouterPrefix(id);
                if (maker.isEmpty()) continue;
                String name = node.path("name").asText(id);
                models.add(new ProviderModel(id, name, vendorLabel(maker.get())));
            }
        }
        return List.copyOf(models);
    }

    /** {@link SupportedMaker} → the same display-vendor spelling {@code ModelCatalog}'s static entries
     *  already use, so a live-merged OpenRouter entry groups in the UI next to its static siblings. */
    private static String vendorLabel(SupportedMaker maker) {
        return switch (maker) {
            case OPENAI -> "OpenAI";
            case ANTHROPIC -> "Anthropic";
            case GOOGLE -> "Google";
            case MOONSHOT -> "Moonshot";
            case ZHIPU -> "Zhipu";
            case XAI -> "xAI";
        };
    }
}
