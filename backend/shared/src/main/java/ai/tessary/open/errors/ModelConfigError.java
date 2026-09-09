// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

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
     * A pairing failure, not a bad model: sandbox lanes need a model that can sustain a long tool-use
     * loop from inside a microVM. GPT-5.6 Terra drives the sandbox; Nova 2 Lite does not.
     */
    MODEL_NOT_AGENTIC(
            HttpStatus.BAD_REQUEST,
            "Model %s cannot run the %s lane — that lane runs an agent in a sandbox, which needs a model that can drive a long tool-use loop"),
    /**
     * The model could do the work and is simply not offered for it. Distinct from
     * {@link #MODEL_NOT_AGENTIC}: Claude Haiku 4.5 can drive a sandbox agent and still isn't put on a
     * repository-wide run, and Claude Sonnet 5 grades well and still isn't run per trace.
     */
    MODEL_NOT_OFFERED_FOR_LANE(HttpStatus.BAD_REQUEST, "Model %s is not offered for the %s lane"),
    /**
     * Also a pairing failure: only the GPT-5.6 line on bedrock-mantle takes a reasoning effort. Claude
     * models can't — effort rides in the same request object as our structured output, and the endpoint
     * rejects the pair — so offering it there would be a setting that could never take effect.
     */
    EFFORT_UNSUPPORTED_BY_MODEL(HttpStatus.BAD_REQUEST, "Model %2$s does not support the %1$s reasoning effort"),
    SECRET_KEY_NOT_CONFIGURED(HttpStatus.PRECONDITION_FAILED, "TESSARY_SECRET_KEY is required to store credentials"),
    MISSING_CREDENTIALS(
            HttpStatus.PRECONDITION_FAILED,
            "Provider %s has no credentials — add credentials for it under Settings → Providers (an API key,"
                    + " AWS keys, or an IAM-role opt-in for Bedrock/mantle). Every provider requires an"
                    + " organization credential; there is no credential-free platform any more."),
    /**
     * {@code ProjectModelSettings#set} refuses to persist a lane pointed at a provider the org has no
     * credential for. Distinct from {@link #MISSING_CREDENTIALS}, which fires at run time when a
     * resolved lane's credential has since gone missing: this fires at save time, on a choice the
     * settings picker should already have shown disabled.
     */
    PROVIDER_NOT_CONFIGURED(
            HttpStatus.BAD_REQUEST,
            "Model %s needs a %s credential — add one under Settings → Providers before pointing a lane at" + " it."),
    /**
     * An {@code auth_mode=iam_role} Bedrock/{@code BEDROCK_MANTLE} credential was resolved for an
     * agentic (RCA/TRIAGE) lane. {@code iam_role} means the operator's own ambient AWS identity
     * ({@code DefaultCredentialsProvider}), and an E2B microVM has no way to assume it. IAM-role auth
     * still works for the backend's own direct judge calls; a sandbox lane needs {@code api_key} mode.
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
