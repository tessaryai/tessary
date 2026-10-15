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
 * Lists an OpenAI-compatible {@code GET /models} ({@code data[].id}), bearer auth. Each instance is one maker, so
 * nothing needs classifying; {@code CUSTOM} is listed unfiltered, the one exemption from the maker allowlist. No
 * display name in this shape, so it equals the model name.
 */
public final class OpenAiCompatModelLister implements ProviderModelLister {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String vendor;
    private final Duration timeout;

    /** @param timeout per-call ceiling on the {@code GET /models} request */
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
