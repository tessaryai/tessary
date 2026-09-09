// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.FoundationModelSummary;

/**
 * Lists what a Bedrock credential's OWN configured region actually serves, via Bedrock's control
 * plane ({@code ListFoundationModels} — a different client, and a different host, from the
 * runtime/Converse client {@code ChatModelFactory} builds requests through). Filtered to
 * {@link SupportedMaker#fromBedrockProviderName}'s six-maker allowlist, per {@code
 * FoundationModelSummary.providerName()}.
 *
 * <p><b>This is the corrective brief's own explicit fix</b> for a wrong prior instruction that
 * hardcoded Bedrock to "Anthropic only": what a Bedrock ACCOUNT serves varies by REGION and by what
 * AWS has enabled for that account, so this class enumerates live rather than assuming — if Bedrock
 * ever serves a Gemini or Grok model in some region, it appears automatically once that maker's
 * {@code providerName()} spelling is in the allowlist, no code change beyond (at most) widening
 * {@link SupportedMaker#fromBedrockProviderName}'s table.
 *
 * <p>Also drops any summary whose {@code modelLifecycle().status()} is not {@code ACTIVE} (Bedrock's
 * own {@code LEGACY} marker) — offering a model AWS itself is retiring would be a live-fetched entry
 * that resolves today and 400s the day AWS finishes pulling it, which is a worse failure mode than
 * simply not listing it.
 *
 * <p><b>Credential shape:</b> a Bedrock/mantle credential is either {@code auth_mode=api_key}
 * (sealed static AWS keys on the row) or {@code auth_mode=iam_role} (the ambient
 * {@code DefaultCredentialsProvider} — the same identity {@code ChatModelFactory}'s own generation
 * calls use for such a row; see {@link ResolvedCredential}'s javadoc for why that is fine here and
 * is NOT fine for an agentic sandbox run).
 */
public final class BedrockModelLister implements ProviderModelLister {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /** Builds the {@link BedrockClient} used for one {@link #list} call. Test seam — production
     *  always goes through {@link #defaultClient}; a unit test injects a factory that returns a
     *  mocked {@link BedrockClient} instead of making a real AWS connection, which is the seam this
     *  class previously had no way to offer (it used to call {@code BedrockClient.builder()} inline). */
    @FunctionalInterface
    interface ClientFactory {
        BedrockClient create(Region region, AwsCredentialsProvider credentialsProvider, Duration timeout);
    }

    private final Duration timeout;
    private final ClientFactory clientFactory;

    public BedrockModelLister() {
        this(DEFAULT_TIMEOUT);
    }

    /** @param timeout per-call ceiling on the {@code ListFoundationModels} request — production wires
     *  this to {@code ModelCatalogProperties#getFetchTimeout()}; the no-arg constructor keeps the
     *  previous fixed default for callers (tests) that do not care. */
    public BedrockModelLister(Duration timeout) {
        this(timeout, BedrockModelLister::defaultClient);
    }

    /** Test seam only. */
    BedrockModelLister(Duration timeout, ClientFactory clientFactory) {
        this.timeout = timeout;
        this.clientFactory = clientFactory;
    }

    private static BedrockClient defaultClient(
            Region region, AwsCredentialsProvider credentialsProvider, Duration timeout) {
        return BedrockClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .overrideConfiguration(b -> b.apiCallTimeout(timeout))
                .build();
    }

    @Override
    public List<ProviderModel> list(ResolvedCredential credential) {
        // A local rather than repeated credential.awsRegion() calls: spotbugs cannot correlate two
        // invocations of the same accessor as returning the same (or non-null) value, and flags a
        // later call as a possible NPE even though this null-check already guards it.
        String region = credential.awsRegion();
        if (region == null || region.isBlank()) {
            throw new ModelListingException("no AWS region configured for this credential", null);
        }
        try (BedrockClient client =
                clientFactory.create(Region.of(region.trim()), credentialsProvider(credential), timeout)) {
            List<ProviderModel> models = new ArrayList<>();
            for (FoundationModelSummary summary :
                    client.listFoundationModels(r -> {}).modelSummaries()) {
                if (summary.modelLifecycle() != null
                        && summary.modelLifecycle().status() != null
                        && !"ACTIVE".equals(summary.modelLifecycle().status().toString())) {
                    continue;
                }
                Optional<SupportedMaker> maker = SupportedMaker.fromBedrockProviderName(summary.providerName());
                if (maker.isEmpty()) continue;
                models.add(new ProviderModel(summary.modelId(), summary.modelName(), summary.providerName()));
            }
            return List.copyOf(models);
        } catch (SdkException e) {
            throw new ModelListingException("ListFoundationModels failed in " + region, e);
        }
    }

    private static AwsCredentialsProvider credentialsProvider(ResolvedCredential credential) {
        if (credential.usesIamRole()) {
            return DefaultCredentialsProvider.create();
        }
        if (credential.awsAccessKey() == null || credential.awsSecretKey() == null) {
            throw new ModelListingException("no AWS keys configured for this api_key-mode credential", null);
        }
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(credential.awsAccessKey(), credential.awsSecretKey()));
    }
}
