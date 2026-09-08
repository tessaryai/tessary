// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.errors;

import org.springframework.http.HttpStatus;

public enum GitError implements ErrorCode {
    INTEGRATION_NOT_FOUND(HttpStatus.NOT_FOUND, "No git integration for project '%s'"),
    GITHUB_REQUIRED(
            HttpStatus.CONFLICT,
            "Grader generation needs the project's repo: connect the GitHub App to project '%s' first"),
    DUPLICATE_INTEGRATION(HttpStatus.CONFLICT, "Project '%s' is already bound to a repo"),
    UNSUPPORTED_PROVIDER(HttpStatus.BAD_REQUEST, "Unsupported git provider '%s'"),
    INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED, "Webhook signature verification failed"),
    INSTALLATION_NOT_FOUND(HttpStatus.BAD_REQUEST, "No installation '%s' on record"),
    TOKEN_MINT_FAILED(HttpStatus.BAD_GATEWAY, "Could not mint a %s access token"),
    PROVIDER_CALL_FAILED(HttpStatus.BAD_GATEWAY, "%s API call failed: %s"),
    MISSING_APP_CONFIG(HttpStatus.FAILED_DEPENDENCY, "Git provider %s is not configured on this server"),
    INSTALL_STATE_INVALID(HttpStatus.BAD_REQUEST, "GitHub install state is invalid or expired"),
    NO_INSTALLED_REPOS(HttpStatus.BAD_REQUEST, "The GitHub App installation grants no repositories"),
    NO_ADMIN_INSTALLATIONS(
            HttpStatus.BAD_REQUEST,
            "No GitHub App installation found for the authorizing user — install the Tessary GitHub App on your account or org first, then connect"),
    INSTALL_NOT_VERIFIED(
            HttpStatus.BAD_REQUEST,
            "GitHub install could not be verified — enable 'Request user authorization (OAuth) during installation' on the App and configure its client id/secret"),
    INSTALL_FORBIDDEN(HttpStatus.FORBIDDEN, "This GitHub installation is not one the authorizing user can administer"),
    MANIFEST_CONVERSION_FAILED(
            HttpStatus.BAD_GATEWAY,
            "Could not complete GitHub App registration — the manifest code may have already been used or expired"),
    APP_ALREADY_CONFIGURED(
            HttpStatus.CONFLICT,
            "A GitHub App is already configured on this deployment — remove the existing github_app_config row"
                    + " before running the manifest wizard again, since it mints one shared App for every org");

    private final HttpStatus status;
    private final String template;

    GitError(HttpStatus status, String template) {
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
        return GitError.class;
    }
}
