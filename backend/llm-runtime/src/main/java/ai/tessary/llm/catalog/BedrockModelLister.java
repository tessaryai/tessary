// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import ai.tessary.open.coverage.ExcludeFromJacocoGeneratedReport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.FoundationModelSummary;

/**
 * Lists what a Bedrock credential's own region serves via {@code ListFoundationModels}, filtered to
 * {@link SupportedMaker#fromBedrockProviderName}; what an account serves varies by region. Drops non-{@code ACTIVE}
 * models, which would 400 once AWS retires them. {@code iam_role} uses the ambient provider; see
 * {@link ResolvedCredential}.
 */
public final class BedrockModelLister implements ProviderModelLister {

    /** Test seam; production uses {@link #defaultClient}. */
    @FunctionalInterface
    interface ClientFactory {
        BedrockClient create(Region region, AwsCredentialsProvider credentialsProvider, Duration timeout);
    }

    private final Duration timeout;
    private final ClientFactory clientFactory;

    /** @param timeout per-call ceiling on {@code ListFoundationModels} */
    public BedrockModelLister(Duration timeout) {
        this(timeout, BedrockModelLister::defaultClient);
    }

    /** Test seam only. */
    BedrockModelLister(Duration timeout, ClientFactory clientFactory) {
        this.timeout = timeout;
        this.clientFactory = clientFactory;
    }

    @ExcludeFromJacocoGeneratedReport("builds the real AWS Bedrock client; only a live AWS call runs it")
    private static BedrockClient defaultClient(
            Region region, AwsCredentialsProvider credentialsProvider, Duration timeout) {
        return BedrockClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(timeout)
                        .build())
                .build();
    }

    @Override
    public List<ProviderModel> list(ResolvedCredential credential) {
        // A local: spotbugs flags a repeated accessor call as a possible NPE despite the null check.
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
