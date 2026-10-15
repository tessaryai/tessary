// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

public enum ModelConfigError implements ErrorCode {
    UNKNOWN_PROVIDER(HttpStatus.BAD_REQUEST, "Unknown provider: %s"),
    BEDROCK_MISSING_REGION(HttpStatus.BAD_REQUEST, "Bedrock (%s) credential is missing aws_region"),
    UNKNOWN_LANE(HttpStatus.BAD_REQUEST, "Unknown model lane: %s"),
    UNKNOWN_PLATFORM_MODEL(HttpStatus.BAD_REQUEST, "Model %s is not one of the platform's Bedrock models"),
    /** A pairing failure, not a bad model: sandbox lanes need a model that sustains a long tool-use loop. */
    MODEL_NOT_AGENTIC(
            HttpStatus.BAD_REQUEST,
            "Model %s cannot run the %s lane — that lane runs an agent in a sandbox, which needs a model that can drive a long tool-use loop"),
    /** Unlike {@link #MODEL_NOT_AGENTIC}, the model could do the work and is simply not offered for it. */
    MODEL_NOT_OFFERED_FOR_LANE(HttpStatus.BAD_REQUEST, "Model %s is not offered for the %s lane"),
    SECRET_KEY_NOT_CONFIGURED(HttpStatus.PRECONDITION_FAILED, "TESSARY_SECRET_KEY is required to store credentials"),
    MISSING_CREDENTIALS(
            HttpStatus.PRECONDITION_FAILED,
            "Provider %s has no credentials — add credentials for it under Settings → Providers (an API key,"
                    + " AWS keys, or an IAM-role opt-in for Bedrock/mantle). Every provider requires an"
                    + " organization credential; there is no credential-free platform any more."),
    /** Fires at save time, unlike {@link #MISSING_CREDENTIALS} at run time. */
    /** The deployment supplies that provider, so there is no org credential to store or delete. */
    PROVIDER_NOT_EDITABLE(
            HttpStatus.BAD_REQUEST, "%s is supplied by this deployment and takes no credential of your own."),
    PROVIDER_NOT_CONFIGURED(
            HttpStatus.BAD_REQUEST,
            "Model %s needs a %s credential — add one under Settings → Providers before pointing a lane at" + " it."),
    /** An E2B microVM cannot assume the operator's ambient AWS identity; a sandbox lane needs {@code api_key} mode. */
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
