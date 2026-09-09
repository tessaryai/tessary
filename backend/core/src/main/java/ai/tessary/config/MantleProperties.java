// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Connection settings for the {@code bedrock-mantle} endpoint, bound from {@code tessary.mantle.*}.
 *
 * <p><b>Why this is not {@code AWS_REGION}.</b> Mantle is a separate Bedrock endpoint with no
 * cross-region inference profiles, and the GPT-5.6 line is served only from three US regions, which
 * need not be where the rest of the stack runs. So the mantle region is an independent setting
 * rather than the ambient one, and pointing a lane at a mantle model means its trace content leaves
 * the region every other platform-funded call stays inside. That is a data-residency consequence,
 * which is why it is a named key with a documented default rather than something inherited.
 *
 * <p>The identity is unchanged: mantle signs with the same ambient AWS credentials
 * {@code bedrock-runtime} uses (see {@code llm/MantleHttpClient}), only against a different SigV4
 * service name and region.
 */
@Component
@ConfigurationProperties(prefix = "tessary.mantle")
public class MantleProperties {

    /** Host, split around the region. Note {@code .api.aws}, not {@code .amazonaws.com} — mantle is
     *  not on the standard Bedrock host, and the one AWS doc page that says otherwise is stale.
     *  Concatenated rather than formatted: {@code String#formatted} is locale-sensitive and banned. */
    private static final String HOST_PREFIX = "https://bedrock-mantle.";

    private static final String HOST_SUFFIX = ".api.aws";

    /**
     * The path the OpenAI GPT line is served on. Deliberately NOT the plain {@code /v1} the Projects
     * API and the open-weight models use: sending a GPT-5.6 request there is a 404 that reads like the
     * model is unavailable in the region.
     */
    public static final String OPENAI_API_PATH = "/openai/v1";

    /**
     * The region mantle is called in. Defaults to {@code us-east-1}: it is the only region that hosts
     * every GPT-5.6 model, and mantle cannot route across regions the way an inference profile can.
     */
    private String region = "us-east-1";

    /**
     * The Bedrock Project inference is attributed to ({@code proj_…}), or blank for the account's
     * {@code default} project.
     *
     * <p>Projects are mantle's equivalent of an inference profile: an IAM-addressable boundary that
     * also carries AWS tags for cost allocation. Blank is a working configuration, not a degraded one
     * — it is what keeps local development and any environment without its own project running — but
     * production names one so the IAM policy can be scoped to it rather than to {@code project/*}.
     */
    private String projectId = "";

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    /** The region, trimmed and lowercased; blank when unconfigured, which disables the mantle path. */
    public String region() {
        return region == null ? "" : region.trim().toLowerCase(Locale.ROOT);
    }

    /** The project id, trimmed; blank means "use the account default project" and sends no header. */
    public String projectId() {
        return projectId == null ? "" : projectId.trim();
    }

    /**
     * The full base URL for a model served on {@code apiPath}.
     *
     * <p>The path is per model family, not per endpoint: the GPT line is on {@code /openai/v1} while
     * Anthropic models on mantle are on {@code /anthropic/v1} and the Projects API is on plain
     * {@code /v1}. That is why the path comes from the model descriptor and only the host is
     * configured here — deriving one from the other would be wrong for at least one family.
     */
    public String baseUrl(String apiPath) {
        return baseUrl(region(), apiPath);
    }

    /**
     * {@link #baseUrl(String)} for a region this config does not own — the customer-credential path,
     * where the region comes from their {@code provider_credential} row rather than from the
     * deployment.
     */
    public static String baseUrl(String region, String apiPath) {
        return HOST_PREFIX + region.trim().toLowerCase(Locale.ROOT) + HOST_SUFFIX + apiPath;
    }
}
