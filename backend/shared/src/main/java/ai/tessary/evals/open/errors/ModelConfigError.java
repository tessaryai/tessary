// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.errors;

import org.springframework.http.HttpStatus;

public enum ModelConfigError implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "No %s provider credential found"),
    UNKNOWN_PROVIDER(HttpStatus.BAD_REQUEST, "Unknown provider: %s"),
    UNKNOWN_MODEL(HttpStatus.BAD_REQUEST, "Model %s is not in the catalog for provider %s"),
    BEDROCK_MISSING_REGION(HttpStatus.BAD_REQUEST, "Bedrock (%s) credential is missing aws_region"),
    UNKNOWN_LANE(HttpStatus.BAD_REQUEST, "Unknown model lane: %s"),
    UNKNOWN_PLATFORM_MODEL(HttpStatus.BAD_REQUEST, "Model %s is not one of the platform's Bedrock models"),
    /** Batch has no online wire form — it exists for pricing only. See {@code ServiceTier}. */
    TIER_NOT_ONLINE(HttpStatus.BAD_REQUEST, "Service tier %s cannot be used for live inference"),
    /**
     * The pair is the failure, not either half: Flex is a valid tier and Haiku 4.5 is a valid model,
     * but Haiku 4.5 on Bedrock is Standard-only, so the combination would 400 at Bedrock mid-grade.
     */
    TIER_UNSUPPORTED_BY_MODEL(HttpStatus.BAD_REQUEST, "Model %s does not support the %s service tier"),
    /**
     * Also a pairing failure rather than a bad model: the sandbox lanes ask the model to sustain a long
     * tool-use loop from inside a microVM, so a model that grades perfectly well can still be
     * unrunnable there. Not a vendor rule — GPT-5.6 Terra drives the sandbox and Nova 2 Lite does not.
     */
    MODEL_NOT_AGENTIC(
            HttpStatus.BAD_REQUEST,
            "Model %s cannot run the %s lane — that lane runs an agent in a sandbox, which needs a model that can drive a long tool-use loop"),
    /**
     * The model could do the work and is simply not offered for it. Distinct from
     * {@link #MODEL_NOT_AGENTIC}, which says the model would not function: Claude Haiku 4.5 can drive a
     * sandbox agent and is still not a model we put on a repository-wide microVM run, and Claude
     * Sonnet 5 grades well and is still not something to run per trace. Blaming capability for either
     * would be a lie the reader could disprove.
     */
    MODEL_NOT_OFFERED_FOR_LANE(HttpStatus.BAD_REQUEST, "Model %s is not offered for the %s lane"),
    /**
     * Also a pairing failure: {@code high} is a valid effort and every model here is a valid model, but
     * only the GPT-5.6 line on bedrock-mantle takes a reasoning effort at all. The Claude models cannot
     * — their effort rides in the same request object as our structured output, and the endpoint
     * rejects the pair — so offering one on them would be a setting that could never take effect.
     */
    EFFORT_UNSUPPORTED_BY_MODEL(HttpStatus.BAD_REQUEST, "Model %2$s does not support the %1$s reasoning effort"),
    SECRET_KEY_NOT_CONFIGURED(HttpStatus.PRECONDITION_FAILED, "EVALS_SECRET_KEY is required to store credentials"),
    MISSING_CREDENTIALS(
            HttpStatus.PRECONDITION_FAILED,
            "Provider %s has no credentials — add credentials for it under Settings → Providers (an API key,"
                    + " AWS keys, or an IAM-role opt-in for Bedrock/mantle). Every provider requires an"
                    + " organization credential; there is no credential-free platform any more (#939)."),
    /**
     * #939 D3: {@code ProjectModelSettings#set} refuses to persist a lane pointed at a provider the
     * ORG has no credential for. Distinct from {@link #MISSING_CREDENTIALS}, which fires at RUN time
     * when a resolved lane's credential has since gone missing (deleted, or the row predates this
     * check) — this one fires at SAVE time, on an explicit user choice the settings picker should
     * already have shown disabled. Seeing this instead of a disabled option is itself a bug report.
     */
    PROVIDER_NOT_CONFIGURED(
            HttpStatus.BAD_REQUEST,
            "Model %s needs a %s credential — add one under Settings → Providers before pointing a lane at" + " it."),
    /**
     * #939 D4: an {@code auth_mode=iam_role} Bedrock/{@code BEDROCK_MANTLE} credential was resolved
     * for an agentic (RCA/TRIAGE) lane. Settled by design, not a gap to close: {@code iam_role} means
     * "the operator's own ambient AWS identity" ({@code DefaultCredentialsProvider}), and an E2B
     * microVM has no way to assume that identity — there is no {@code roleArn} column or STS-relay
     * path for it to use instead. IAM-role auth stays usable for the backend's own direct judge
     * calls; a Bedrock/mantle credential meant for a sandbox lane must be {@code api_key} mode.
     */
    AGENTIC_IAM_ROLE_UNSUPPORTED(
            HttpStatus.PRECONDITION_FAILED,
            "Provider %s's credential uses an IAM-role identity, which a sandboxed agent cannot assume — set"
                    + " it to access + secret key auth under Settings → Providers to use it for RCA/Triage.");

    private final HttpStatus status;
    private final String template;

    ModelConfigError(HttpStatus status, String template) {
        this.status = status;
        this.template = template;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String template() {
        return template;
    }

    @Override
    public Class<? extends Enum<?>> declaringClass() {
        return ModelConfigError.class;
    }
}
